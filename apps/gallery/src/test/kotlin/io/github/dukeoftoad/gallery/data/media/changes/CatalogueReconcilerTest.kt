package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler rules are the foundation the future AI incremental index
 * builds on, so they get the exhaustive treatment (Phase 4 contract,
 * "Incremental discovery"; ADR-0016: pure JVM, no android.* types).
 *
 * The two rules most worth guarding forever:
 * - a generation-token bump with IDENTICAL size and date must be CHANGED
 *   (that is the entire reason the token is part of the revision), and
 * - a metadataWriteRevision-only delta must be UNCHANGED (an in-app EXIF
 *   write is not an external edit; re-indexing on it would burn the
 *   battery and the embedding store for bytes that never moved).
 */
class CatalogueReconcilerTest {

    private fun row(
        key: String = "external_primary:1",
        sizeBytes: Long = 100L,
        dateModifiedMillis: Long = 1_000L,
        dateAddedMillis: Long = 500L,
        mimeType: String = "image/jpeg",
        canonicalUri: String = "content://media/external/images/1",
        generationToken: String? = null,
    ): MediaScanRow =
        MediaScanRow(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = dateModifiedMillis,
            dateAddedMillis = dateAddedMillis,
            generationToken = generationToken,
        )

    private fun recordOf(
        key: String = "external_primary:1",
        sizeBytes: Long = 100L,
        dateModifiedMillis: Long = 1_000L,
        dateAddedMillis: Long = 500L,
        mimeType: String = "image/jpeg",
        canonicalUri: String = "content://media/external/images/1",
        generationToken: String? = null,
        metadataWriteRevision: Int = 0,
        metadataCacheState: MetadataCacheState = MetadataCacheState.CACHED,
        lastSeenAtMillis: Long = 5_000L,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = dateModifiedMillis,
            dateAddedMillis = dateAddedMillis,
            generationToken = generationToken,
            metadataWriteRevision = metadataWriteRevision,
            metadataCacheState = metadataCacheState,
            lastSeenAtMillis = lastSeenAtMillis,
        )

    // --- added ---------------------------------------------------------------

    @Test
    fun `scan row without a catalogue record is added`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(key = "external_primary:9")),
            catalogueRecords = emptyMap(),
        )
        assertEquals(listOf(row(key = "external_primary:9")), result.added)
        assertTrue(result.changed.isEmpty())
        assertEquals(0, result.unchangedCount)
        assertTrue(result.removedKeys.isEmpty())
    }

    @Test
    fun `resurrected tombstone absent from the live map reports as added`() {
        // The driver passes recordsMap() (tombstones excluded), so a row
        // that came back from the dead is a first sighting again and gets a
        // full re-index --- exactly what resurrection semantics require.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(key = "external_primary:1")),
            catalogueRecords = emptyMap(),
        )
        assertEquals(1, result.added.size)
    }

    // --- changed ---------------------------------------------------------------

    @Test
    fun `size change is reported as changed`() {
        val old = recordOf(sizeBytes = 100L)
        val fresh = row(sizeBytes = 101L)

        val result = CatalogueReconciler.reconcile(listOf(fresh), mapOf("external_primary:1" to old))

        assertEquals(1, result.changed.size)
        assertEquals(old, result.changed.single().old)
        assertEquals(fresh, result.changed.single().new)
        assertEquals(0, result.unchangedCount)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun `dateModified change is reported as changed`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(dateModifiedMillis = 2_000L)),
            catalogueRecords = mapOf("external_primary:1" to recordOf(dateModifiedMillis = 1_000L)),
        )
        assertEquals(1, result.changed.size)
    }

    @Test
    fun `generation token bump with identical size and date is reported as changed`() {
        // THE reason the generation token is part of the revision: an
        // external edit (or a whole-volume event) can bump the generation
        // while per-row size and date_modified look untouched. A reconciler
        // that compared only size+date would skip the re-index entirely.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(generationToken = "18")),
            catalogueRecords = mapOf("external_primary:1" to recordOf(generationToken = "17")),
        )
        assertEquals(1, result.changed.size)
        assertEquals(0, result.unchangedCount)
    }

    @Test
    fun `generation token appearing where the record had none is changed`() {
        // API-30 upgrade path: pre-30 records carry null tokens; the first
        // post-30 scan sees a real token. That is a platform-visibility
        // change, but treating it as unchanged would freeze the null
        // forever --- report it so the record adopts the token.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(generationToken = "5")),
            catalogueRecords = mapOf("external_primary:1" to recordOf(generationToken = null)),
        )
        assertEquals(1, result.changed.size)
    }

    // --- unchanged -------------------------------------------------------------

    @Test
    fun `identical revision is unchanged`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(sizeBytes = 100L, dateModifiedMillis = 1_000L, generationToken = "17")),
            catalogueRecords = mapOf(
                "external_primary:1" to recordOf(sizeBytes = 100L, dateModifiedMillis = 1_000L, generationToken = "17"),
            ),
        )
        assertEquals(0, result.changed.size)
        assertEquals(1, result.unchangedCount)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun `metadata write revision alone does NOT mark a record changed`() {
        // KEY REGRESSION GUARD: metadataWriteRevision is in-app state. If a
        // future edit adds it to the comparison, every EXIF write would
        // trigger a full re-index of unchanged bytes.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row()),
            catalogueRecords = mapOf("external_primary:1" to recordOf(metadataWriteRevision = 7)),
        )
        assertEquals(0, result.changed.size)
        assertEquals(1, result.unchangedCount)
    }

    @Test
    fun `fields outside the revision contract do not mark a record changed`() {
        // canonicalUri re-resolution, a MIME touch with no byte change, a
        // dateAdded drift and a stale lastSeen stamp are all bookkeeping ---
        // none of them justifies an embedding recompute.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(
                row(
                    canonicalUri = "content://media/external/images/1?re-resolved",
                    mimeType = "image/jpg",
                    dateAddedMillis = 501L,
                ),
            ),
            catalogueRecords = mapOf(
                "external_primary:1" to recordOf(
                    canonicalUri = "content://media/external/images/1",
                    mimeType = "image/jpeg",
                    dateAddedMillis = 500L,
                    lastSeenAtMillis = 123L,
                ),
            ),
        )
        assertEquals(0, result.changed.size)
        assertEquals(1, result.unchangedCount)
    }

    // --- removed -----------------------------------------------------------------

    @Test
    fun `catalogue record with no scan row is removed`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(row(key = "external_primary:1")),
            catalogueRecords = mapOf(
                "external_primary:1" to recordOf(key = "external_primary:1"),
                "external_primary:2" to recordOf(key = "external_primary:2"),
            ),
        )
        assertEquals(listOf("external_primary:2"), result.removedKeys)
    }

    @Test
    fun `empty scan rows report every record as removed`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = emptyList(),
            catalogueRecords = mapOf(
                "external_primary:1" to recordOf(key = "external_primary:1"),
                "external_primary:2" to recordOf(key = "external_primary:2"),
            ),
        )
        assertEquals(setOf("external_primary:1", "external_primary:2"), result.removedKeys.toSet())
        assertEquals(2, result.removedKeys.size)
    }

    @Test
    fun `ignore tombstones suppresses removed keys but keeps adds and changes`() {
        // Partial scan (one volume of several; an unmounted SD card still
        // holds live records): absence is not deletion, so no tombstone
        // candidates may be emitted --- but what the scan DID see is still
        // evidence.
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(
                row(key = "external_primary:1"), // unchanged
                row(key = "external_primary:3", sizeBytes = 999L), // changed
                row(key = "external_primary:4"), // added
            ),
            catalogueRecords = mapOf(
                "external_primary:1" to recordOf(key = "external_primary:1"),
                "external_primary:2" to recordOf(key = "external_primary:2"), // not scanned
                "external_primary:3" to recordOf(key = "external_primary:3"),
            ),
            ignoreTombstones = true,
        )
        assertTrue(result.removedKeys.isEmpty())
        assertEquals(1, result.added.size)
        assertEquals(1, result.changed.size)
        assertEquals(1, result.unchangedCount)
    }

    // --- empty inputs -------------------------------------------------------------

    @Test
    fun `empty scan and empty catalogue produce an empty reconciliation`() {
        val result = CatalogueReconciler.reconcile(scanRows = emptyList(), catalogueRecords = emptyMap())
        assertTrue(result.added.isEmpty())
        assertTrue(result.changed.isEmpty())
        assertEquals(0, result.unchangedCount)
        assertTrue(result.removedKeys.isEmpty())
    }

    // --- mixed scenario -----------------------------------------------------------

    @Test
    fun `mixed scan sorts every row into exactly one bucket`() {
        val records = mapOf(
            "external_primary:1" to recordOf(key = "external_primary:1"), // unchanged
            "external_primary:2" to recordOf(key = "external_primary:2", sizeBytes = 1L), // changed
            "external_primary:3" to recordOf(key = "external_primary:3"), // removed
        )
        val rows = listOf(
            row(key = "external_primary:1"),
            row(key = "external_primary:2", sizeBytes = 2L),
            row(key = "external_primary:4"), // added
        )

        val result = CatalogueReconciler.reconcile(rows, records)

        assertEquals(listOf("external_primary:4"), result.added.map { it.localMediaKey })
        assertEquals(listOf("external_primary:2"), result.changed.map { it.new.localMediaKey })
        assertEquals(1, result.unchangedCount)
        assertEquals(listOf("external_primary:3"), result.removedKeys)
        // No key in two buckets, no row lost.
        val accounted = result.added.size + result.changed.size + result.unchangedCount
        assertEquals(rows.size, accounted)
    }

    @Test
    fun `duplicate scan keys are counted once with the first row winning`() {
        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(
                row(key = "external_primary:1"),
                row(key = "external_primary:1", sizeBytes = 5L),
            ),
            catalogueRecords = emptyMap(),
        )
        assertEquals(1, result.added.size)
        assertEquals(100L, result.added.single().sizeBytes)
    }
}
