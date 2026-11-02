package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan driver's decision table is the piece that decides whether the
 * app re-walks MediaStore or sleeps through a boot, so it gets the
 * exhaustive treatment (the [MediaChangeFeed] contract's decision section,
 * cast into code; ADR-0016: pure JVM, no android.* types).
 *
 * The rules most worth guarding forever:
 * - a NULL token on either side must never produce SKIP --- null means
 *   "cannot tell", and "cannot tell" can only honestly mean "scan";
 * - the trigger must not influence the decision (APP_START, OBSERVER and
 *   MANUAL with identical arguments are the same question).
 */
class ScanDriverPolicyTest {

    // --- decision table -------------------------------------------------------

    /**
     * Exhaustive boundary table: (checkpoint, live) -> expected, asserted
     * for EVERY trigger (the trigger-independence rule is part of the
     * contract, so every row runs three times).
     */
    private fun assertDecision(
        checkpointToken: String?,
        liveToken: String?,
        expected: ScanDecision,
    ) {
        ScanTrigger.entries.forEach { trigger ->
            assertEquals(
                "trigger=$trigger checkpoint=$checkpointToken live=$liveToken",
                expected,
                ScanDriverPolicy.decide(trigger, checkpointToken, liveToken),
            )
        }
    }

    @Test
    fun `no checkpoint token means full rescan regardless of live token`() {
        // Never scanned (no checkpoint) or last scanned on a tokenless
        // platform: there is nothing to compare against, so the walk is
        // unconditional --- even when a live token exists.
        assertDecision(null, null, ScanDecision.FULL_RESCAN)
        assertDecision(null, "0", ScanDecision.FULL_RESCAN)
        assertDecision(null, "18446744073709551615", ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `null live token with a checkpoint means full rescan`() {
        // "null never means unchanged --- it means you cannot tell without
        // scanning" (the MediaChangeFeed contract): covers the API 26-29
        // world (no generation at all) and the 30+ unknown-volume case.
        assertDecision("5", null, ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `both tokens null pre-30 world means full rescan`() {
        // API 26-29: the observer is the ONLY signal and it cannot prove
        // the volume unchanged, so every pass walks.
        assertDecision(null, null, ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `equal tokens skip`() {
        // The generation contract's whole payoff: the token survived since
        // the last COMPLETED scan, so the platform itself proves nothing
        // moved anywhere on the volume.
        assertDecision("5", "5", ScanDecision.SKIP)
        assertDecision("0", "0", ScanDecision.SKIP)
        assertDecision("18446744073709551615", "18446744073709551615", ScanDecision.SKIP)
    }

    @Test
    fun `differing tokens mean full rescan`() {
        // A volume-wide counter moved; which items is the reconciler's job.
        assertDecision("5", "6", ScanDecision.FULL_RESCAN)
        assertDecision("6", "5", ScanDecision.FULL_RESCAN)
        assertDecision("0", "1", ScanDecision.FULL_RESCAN)
        assertDecision("999", "1000", ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `empty string tokens are opaque values not nulls`() {
        // Tokens are compared only for equality and non-null-ness (the
        // MediaChangeFeed contract); an empty string is a value like any
        // other and must not be conflated with "no token".
        assertDecision("", "", ScanDecision.SKIP)
        assertDecision("", "5", ScanDecision.FULL_RESCAN)
        assertDecision("5", "", ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `token flip from null checkpoint to a live value is a full rescan`() {
        // OS-upgrade path: pre-30 checkpoints are null, the first post-30
        // boot sees a real token. No comparison is possible yet --- scan,
        // and only the NEXT pass can skip.
        assertDecision(null, "1", ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `token flip from a checkpoint back to null is a full rescan`() {
        // Hypothetical downgrade or an unmounted-then-unknown volume: a
        // saved token cannot vouch for a volume we can no longer read a
        // token from.
        assertDecision("1", null, ScanDecision.FULL_RESCAN)
    }

    @Test
    fun `APP_START and OBSERVER with identical arguments decide identically`() {
        // The trigger is diagnostics, not policy: the same token state is
        // the same question no matter who asked (pinned explicitly, so a
        // future trigger-aware `optimization` fails here instead of
        // silently splitting the worlds).
        val tokenPairs = listOf(
            null to null,
            null to "5",
            "5" to null,
            "5" to "5",
            "5" to "6",
            "" to "",
        )
        tokenPairs.forEach { (checkpoint, live) ->
            val decisions = ScanTrigger.entries.map {
                ScanDriverPolicy.decide(it, checkpoint, live)
            }
            assertEquals(
                "checkpoint=$checkpoint live=$live",
                List(ScanTrigger.entries.size) { decisions.first() },
                decisions,
            )
        }
    }

    // --- recordForAppliedChange -------------------------------------------------

    private fun oldRecord(
        metadataWriteRevision: Int = 3,
        metadataCacheState: MetadataCacheState = MetadataCacheState.CACHED,
        displayName: String? = "IMG_0001.jpg",
        width: Int = 100,
        isFavorite: Boolean = false,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
            generationToken = "17",
            metadataWriteRevision = metadataWriteRevision,
            metadataCacheState = metadataCacheState,
            lastSeenAtMillis = 5_000L,
            displayName = displayName,
            width = width,
            height = 75,
            durationMs = 0L,
            isFavorite = isFavorite,
        )

    private fun changedRow(
        sizeBytes: Long = 200L,
        dateModifiedMillis: Long = 2_000L,
        generationToken: String? = "18",
    ) = CatalogueReconciler.ChangedRecord(
        old = oldRecord(),
        new = MediaScanRow(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = sizeBytes,
            dateModifiedMillis = dateModifiedMillis,
            dateAddedMillis = 500L,
            generationToken = generationToken,
            displayName = "IMG_0001_edited.jpg",
            width = 4000,
            height = 3000,
            durationMs = 0L,
            isFavorite = true,
        ),
    )

    @Test
    fun `changed record adopts the fresh row's revision fields`() {
        val merged = ScanDriverPolicy.recordForAppliedChange(changedRow(), lastSeenAtMillis = 9_000L)
        // Everything the provider observes comes from the NEW row...
        assertEquals(200L, merged.sizeBytes)
        assertEquals(2_000L, merged.dateModifiedMillis)
        assertEquals("18", merged.generationToken)
        assertEquals("external_primary:1", merged.localMediaKey)
        // ...and the lastSeen stamp is the scan's wall clock, not the old
        // record's and not zero.
        assertEquals(9_000L, merged.lastSeenAtMillis)
    }

    @Test
    fun `changed record preserves the old record's in-app write revision`() {
        // KEY REGRESSION GUARD: metadataWriteRevision counts THIS app's own
        // EXIF/IPTC/XMP writes; an external revision bump does not undo
        // them. Resetting it to 0 (as MediaScanRow.toRecord would) would
        // make the counter jump backwards on every external edit.
        val merged = ScanDriverPolicy.recordForAppliedChange(
            changedRow(),
            lastSeenAtMillis = 9_000L,
        )
        assertEquals(3, merged.metadataWriteRevision)
    }

    @Test
    fun `changed record stales a cached metadata snapshot`() {
        // The provider row moved, so any cached snapshot predates the
        // current revision: the safe direction is re-read, never trust.
        val cached = ScanDriverPolicy.recordForAppliedChange(
            changedRow(),
            lastSeenAtMillis = 9_000L,
        )
        assertEquals(MetadataCacheState.STALE, cached.metadataCacheState)
    }

    @Test
    fun `changed record keeps already-stale state stale`() {
        // A snapshot that was ALREADY stale is not improved by an external
        // revision move: STALE is the fixed point for "a snapshot exists".
        val staleOld = oldRecord(metadataCacheState = MetadataCacheState.STALE)
        val mergedStale = ScanDriverPolicy.recordForAppliedChange(
            CatalogueReconciler.ChangedRecord(old = staleOld, new = changedRow().new),
            lastSeenAtMillis = 9_000L,
        )
        assertEquals(MetadataCacheState.STALE, mergedStale.metadataCacheState)
    }

    @Test
    fun `changed record with no snapshot ever taken stays empty`() {
        // EMPTY means "there was never a snapshot"; staling out nothing
        // must not invent a STALE state (it would imply a snapshot exists
        // and needs re-reading --- forcing a pointless re-read of an item
        // that was never extracted).
        val emptyOld = oldRecord(metadataCacheState = MetadataCacheState.EMPTY)
        val merged = ScanDriverPolicy.recordForAppliedChange(
            CatalogueReconciler.ChangedRecord(old = emptyOld, new = changedRow().new),
            lastSeenAtMillis = 9_000L,
        )
        assertEquals(MetadataCacheState.EMPTY, merged.metadataCacheState)
    }

    // --- recordForAppliedChange: display/shape ride-alongs (r13 round) --------

    @Test
    fun `changed record refreshes the display and shape fields from the fresh row`() {
        // A CHANGED row's record must mirror its last-seen provider row: the
        // fresh name, dimensions and favorite flag travel through
        // toRecord(), while the revision fields come from the same fresh
        // row --- one consistent snapshot, not a mix of old and new.
        val merged = ScanDriverPolicy.recordForAppliedChange(changedRow(), lastSeenAtMillis = 9_000L)

        assertEquals("IMG_0001_edited.jpg", merged.displayName)
        assertEquals(4_000, merged.width)
        assertEquals(3_000, merged.height)
        // This row is an image: duration stays the unknown zero-point.
        assertEquals(0L, merged.durationMs)
        assertTrue(merged.isFavorite)
    }

    // --- recordForAppliedChange: folder/bucket ride-alongs (r19 round) --------

    @Test
    fun `changed record refreshes the folder bucket pair from the fresh row`() {
        // The bucket pair is ROW-SOURCED with no hook refill: the FRESH
        // provider row is the authority, so a CHANGED row's record mirrors
        // its bucket --- NOT the old record's. A folder move is the
        // canonical CHANGED sighting here (MediaStore rewrites
        // date_modified on a move), and the merged record must land in the
        // row's new folder or the timeline's folder filter would keep
        // serving the item from the old one.
        val merged = ScanDriverPolicy.recordForAppliedChange(
            CatalogueReconciler.ChangedRecord(
                old = oldRecord().copy(bucketId = 111L, bucketDisplayName = "Camera"),
                new = changedRow().new.copy(bucketId = 222L, bucketDisplayName = "Screenshots"),
            ),
            lastSeenAtMillis = 9_000L,
        )

        assertEquals(222L, merged.bucketId)
        assertEquals("Screenshots", merged.bucketDisplayName)
    }

    @Test
    fun `changed record with no bucket on the fresh row lands honestly bucketless`() {
        // The row-sourced honest null must travel like any value: a walk
        // that could not resolve buckets (the reduced-projection fallback,
        // a provider that dropped the column) upserts a null pair, never a
        // fake 0 --- and never the old record's stale bucket, which would
        // be the wipe-free rule silently violated.
        val merged = ScanDriverPolicy.recordForAppliedChange(
            CatalogueReconciler.ChangedRecord(
                old = oldRecord().copy(bucketId = 111L, bucketDisplayName = "Camera"),
                new = changedRow().new,
            ),
            lastSeenAtMillis = 9_000L,
        )

        assertNull(merged.bucketId)
        assertNull(merged.bucketDisplayName)
    }

    @Test
    fun `unchanged rows produce no record so the stored shape fields survive`() {
        // The other half of the carry-through contract: display-only drift
        // (favorite toggled, dimensions back-filled, name tweak) never makes
        // a row CHANGED --- the reconciler says UNCHANGED, the driver
        // upserts nothing, and the catalogue keeps its stored copy until a
        // REAL revision change refreshes it. Pinning that here means the
        // display fields can never accidentally leak into changedVersus()
        // (the embedding-recompute regression this whole design avoids).
        val unchangedRecord = oldRecord(displayName = "IMG_0001.jpg", width = 100, isFavorite = false)
        val displayOnlyDrift = MediaScanRow(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            // Revision contract identical --- only the ride-alongs moved.
            sizeBytes = unchangedRecord.sizeBytes,
            dateModifiedMillis = unchangedRecord.dateModifiedMillis,
            dateAddedMillis = 500L,
            generationToken = unchangedRecord.generationToken,
            displayName = "IMG_0001_renamed.jpg",
            width = 4000,
            height = 3000,
            isFavorite = true,
        )

        val result = CatalogueReconciler.reconcile(
            scanRows = listOf(displayOnlyDrift),
            catalogueRecords = mapOf("external_primary:1" to unchangedRecord),
        )

        assertEquals(0, result.changed.size)
        assertEquals(1, result.unchangedCount)
        assertTrue(result.added.isEmpty())
    }
}
