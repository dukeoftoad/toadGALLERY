package com.toadgallery.gallery.data.media.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 4 revision contract
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, item 2).
 *
 * The audit demands that "a changed revision invalidates only affected AI
 * outputs" --- which only works if [MediaRevision.diff] classifies every
 * field-combination exactly: an app-side metadata write must never
 * trigger model re-runs, and any bytes-level change must never be
 * mistaken for a harmless metadata touch.
 */
class MediaRevisionTest {

    private val base = MediaRevision(
        sizeBytes = 4_096_000L,
        dateModifiedMillis = 1_700_000_000_000L,
        // The item's OWN per-row generation-modified stamp
        // (MediaColumns.GENERATION_MODIFIED) --- NOT the per-volume
        // MediaStore.getGeneration scan checkpoint (audit finding C).
        generationToken = "gen-7",
        metadataWriteRevision = 0,
        contentFingerprint = "sha256:aa",
    )

    @Test
    fun `identical revisions are unchanged`() {
        assertEquals(RevisionDelta.UNCHANGED, base.diff(base))
        assertEquals(RevisionDelta.UNCHANGED, base.diff(base.copy()))
    }

    @Test
    fun `a metadata write revision bump alone is metadata changed`() {
        val afterInAppWrite = base.copy(metadataWriteRevision = 1)
        assertEquals(RevisionDelta.METADATA_CHANGED, base.diff(afterInAppWrite))
        // ...and a second, no-op scan of the already-written state is quiet.
        assertEquals(RevisionDelta.UNCHANGED, afterInAppWrite.diff(afterInAppWrite))
    }

    @Test
    fun `a size change is content changed`() {
        assertEquals(
            RevisionDelta.CONTENT_CHANGED,
            base.diff(base.copy(sizeBytes = base.sizeBytes + 1L)),
        )
    }

    @Test
    fun `a dateModified change is content changed`() {
        assertEquals(
            RevisionDelta.CONTENT_CHANGED,
            base.diff(base.copy(dateModifiedMillis = base.dateModifiedMillis + 1L)),
        )
    }

    @Test
    fun `two observed per-row generation-modified values that disagree are content changed`() {
        assertEquals(
            RevisionDelta.CONTENT_CHANGED,
            base.diff(base.copy(generationToken = "gen-8")),
        )
    }

    @Test
    fun `two observed fingerprints that disagree are content changed`() {
        assertEquals(
            RevisionDelta.CONTENT_CHANGED,
            base.diff(base.copy(contentFingerprint = "sha256:bb")),
        )
    }

    @Test
    fun `a content change dominates a concurrent metadata bump`() {
        // The app wrote metadata AND something rewrote the bytes: the safe
        // classification is CONTENT_CHANGED, never a metadata-only pass.
        assertEquals(
            RevisionDelta.CONTENT_CHANGED,
            base.diff(base.copy(metadataWriteRevision = 3, sizeBytes = 99L)),
        )
    }

    @Test
    fun `lazy fingerprints are absent-safe`() {
        val unhashed = base.copy(contentFingerprint = null)
        // null == null is trivially equal...
        assertEquals(
            RevisionDelta.UNCHANGED,
            unhashed.diff(base.copy(contentFingerprint = null)),
        )
        // ...and a hash appearing or disappearing is new observability of
        // the SAME bytes, not a content change (backfill must not nuke
        // every embedding in the library).
        assertEquals(RevisionDelta.UNCHANGED, unhashed.diff(base))
        assertEquals(RevisionDelta.UNCHANGED, base.diff(unhashed))
    }

    @Test
    fun `a per-row generation-modified value appearing after an os upgrade is not a content change`() {
        val beforeUpgrade = base.copy(generationToken = null)
        // Same story as fingerprints: API 30+ starts exposing generations
        // for identical bytes; invalidating AI on upgrade would be wrong.
        assertEquals(RevisionDelta.UNCHANGED, beforeUpgrade.diff(base))
        assertEquals(RevisionDelta.UNCHANGED, base.diff(beforeUpgrade))
    }

    @Test
    fun `diff is symmetric for every field combination`() {
        val variants = listOf(
            base,
            base.copy(metadataWriteRevision = 1),
            base.copy(sizeBytes = 1L),
            base.copy(dateModifiedMillis = 2L),
            base.copy(generationToken = "gen-9"),
            base.copy(generationToken = null),
            base.copy(contentFingerprint = "sha256:zz"),
            base.copy(contentFingerprint = null),
            base.copy(metadataWriteRevision = 2, contentFingerprint = null),
        )
        variants.forEach { a ->
            variants.forEach { b ->
                assertEquals(
                    "diff must be symmetric for $a vs $b",
                    a.diff(b),
                    b.diff(a),
                )
            }
        }
    }

    @Test
    fun `delta helpers map invalidation exactly`() {
        // UNCHANGED: keep everything.
        assertFalse(RevisionDelta.UNCHANGED.requiresMetadataRefresh)
        assertFalse(RevisionDelta.UNCHANGED.invalidatesAiOutputs)
        // METADATA_CHANGED: re-read metadata, keep face + CLIP embeddings.
        assertTrue(RevisionDelta.METADATA_CHANGED.requiresMetadataRefresh)
        assertFalse(RevisionDelta.METADATA_CHANGED.invalidatesAiOutputs)
        // CONTENT_CHANGED: drop metadata AND every pixel-derived AI output.
        assertTrue(RevisionDelta.CONTENT_CHANGED.requiresMetadataRefresh)
        assertTrue(RevisionDelta.CONTENT_CHANGED.invalidatesAiOutputs)
    }
}
