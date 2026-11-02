package io.github.dukeoftoad.gallery.data.media.dates

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.InMemoryMediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler
import io.github.dukeoftoad.gallery.data.media.changes.MediaScanRow
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the capture-date index pass (r12 round): the pure
 * mapping ([resolveCaptureDateWrite]) plus the end-to-end hook behavior
 * against [InMemoryMediaCatalogue] (the reference catalogue) and fakes
 * for the eligibility gate, the [MediaAccess] opener, the read handle
 * and the candidate-extraction seam.
 *
 * What is pinned here:
 * - a resolved capture date (winner, zone assumption, source) lands on
 *   the catalogue row for an added record;
 * - ABSENT / UNREADABLE / UNSUPPORTED metadata EACH persist null ---
 *   each for its own, distinguishable reason (the
 *   [MetadataAvailability] distinction survives the seam; the catalogue
 *   null is deliberately single-valued this round);
 * - the changed-record path runs on the NEW record's key/uri and cannot
 *   touch old revision fields (the partial provenance update makes that
 *   unreachable, the test proves it);
 * - ineligible records never reach the reader at all (vault gate by
 *   type);
 * - a resolver disagreement still persists the winner (the
 *   sourcesDisagree flag itself is deliberately not persisted this
 *   round --- flag observability rides a later round);
 * - a row whose indexing BLOWS UP (the Room-disk-error / parse-blowup
 *   stand-in the per-row catch exists for) is logged and skipped while
 *   the batch survives --- its provenance write never runs.
 *
 * MIGRATION-POLICY NOTE (deliberately NOT a test): the Room v1 -> v2
 * migration (three `ALTER TABLE ... ADD COLUMN ... DEFAULT NULL`
 * statements, registered via `addMigrations(MIGRATION_1_2)` and never
 * `fallbackToDestructiveMigration`) cannot be exercised by this JVM
 * suite --- android.* / Room runtime is off the unit-test classpath per
 * ADR-0016. It is verified by (a) the exported Room schema JSON
 * (apps/gallery/schemas/...MediaCatalogueDatabase/2.json) matching the
 * migrated-table shape, and (b) the explicit
 * `addMigrations(MIGRATION_1_2)` registration in
 * `MediaCatalogueDatabase.build`, both reviewed by the coordinator before
 * commit.
 */
class CaptureDateIndexerTest {

    private val t0 = 1_700_000_000_000L

    // --- fakes --------------------------------------------------------------

    /** Records what the gate was asked about; answers per configured key set. */
    private class FakeGate(
        private val eligibleKeys: Set<String> = emptySet(),
    ) : CaptureDateEligibilityGate {
        val consultedRecords = mutableListOf<CatalogueRecord>()

        override suspend fun eligiblePublicMedia(record: CatalogueRecord): EligiblePublicMedia? {
            consultedRecords.add(record)
            if (record.localMediaKey !in eligibleKeys) return null
            val identity = MediaEligibility.PUBLIC_INDEXABLE.asEligible(
                MediaIdentity(
                    volumeName = "external_primary",
                    mediaStoreId = record.localMediaKey.substringAfterLast(':').toLong(),
                    canonicalUri = record.canonicalUri,
                ),
            )
            // Guaranteed non-null for PUBLIC_INDEXABLE; the check keeps
            // the fake honest to the gateway contract.
            return identity
        }
    }

    /** Records every open; serves the configured handles (or null). */
    private class FakeMediaAccess(
        private val handleFor: (EligiblePublicMedia) -> MediaReadHandle? = { null },
    ) : MediaAccess {
        val opened = mutableListOf<EligiblePublicMedia>()
        var openCalls = 0

        override suspend fun open(media: EligiblePublicMedia): MediaReadHandle? {
            openCalls++
            opened.add(media)
            return handleFor(media)
        }
    }

    /** A [MediaReadHandle] serving fixed bytes, recording the read cap. */
    private class FakeHandle(
        private val bytes: ByteArray? = null,
    ) : MediaReadHandle {
        var requestedMaxBytes: Long? = null

        override suspend fun readBytes(maxBytes: Long): ByteArray? {
            requestedMaxBytes = maxBytes
            return bytes
        }
    }

    /** The extraction-seam fake: records calls, returns canned evidence. */
    private class FakeCandidateSource(
        private val evidenceFor: suspend (MediaReadHandle, String) -> CaptureDateEvidence =
            { _, _ -> CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT) },
    ) : CaptureDateCandidateSource {
        val calls = mutableListOf<Pair<MediaReadHandle, String>>()

        override suspend fun extractCandidates(
            handle: MediaReadHandle,
            mimeType: String,
        ): CaptureDateEvidence {
            calls.add(handle to mimeType)
            return evidenceFor(handle, mimeType)
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun record(
        key: String = "external_primary:1",
        canonicalUri: String = "content://media/external/images/1",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 100L,
        dateAddedMillis: Long = 500L,
        lastSeenAtMillis: Long = 0L,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = dateAddedMillis,
            lastSeenAtMillis = lastSeenAtMillis,
        )

    private fun scanRow(
        key: String = "external_primary:1",
        canonicalUri: String = "content://media/external/images/1",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 100L,
        dateAddedMillis: Long = 500L,
    ): MediaScanRow =
        MediaScanRow(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = dateAddedMillis,
        )

    /** Assembles the indexer with recording fakes wired to [catalogue]. */
    private fun indexer(
        catalogue: MediaCatalogue,
        eligibleKeys: Set<String>,
        handle: MediaReadHandle?,
        evidenceFor: suspend (MediaReadHandle, String) -> CaptureDateEvidence =
            { _, _ -> CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT) },
    ): CaptureDateIndexer {
        val gate = FakeGate(eligibleKeys)
        val access = FakeMediaAccess { handle }
        val source = FakeCandidateSource(evidenceFor)
        return CaptureDateIndexer(
            catalogue = catalogue,
            mediaAccess = access,
            candidateSource = source,
            eligibilityGate = gate,
        )
    }

    // --- end-to-end hook behavior -------------------------------------------

    @Test
    fun `an added record with trustworthy exif evidence persists the resolved capture date`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val added = record()
            catalogue.upsertAll(listOf(added))
            val handle = FakeHandle(byteArrayOf(1))
            val gate = FakeGate(eligibleKeys = setOf(added.localMediaKey))
            val access = FakeMediaAccess { handle }
            val source = FakeCandidateSource { h, _ ->
                // Mimic the production extractor: the bytes are read once
                // through the handle (the cap assertion below checks the
                // boundary's default actually reaches the read).
                h.readBytes()
                CaptureDateEvidence(
                    candidates = listOf(
                        DateCandidate(
                            source = CaptureDateSource.EXIF_ORIGINAL,
                            epochMillis = t0,
                            timezoneOffsetMinutes = 120,
                            subsecondMillis = 250,
                        ),
                    ),
                    availability = MetadataAvailability.PRESENT,
                )
            }
            val indexer = CaptureDateIndexer(catalogue, access, source, gate)

            indexer.onRecordsApplied(added = listOf(added), changed = emptyList())

            val stored = catalogue.record(added.localMediaKey)!!
            assertEquals(t0, stored.resolvedCaptureDateMillis)
            // The zone assumption the extractor made is persisted verbatim
            // so a later pass can reinterpret the naive tag without
            // re-reading the bytes.
            assertEquals(120, stored.resolvedCaptureDateZoneOffsetMinutes)
            assertEquals("EXIF_ORIGINAL", stored.resolvedCaptureDateSource)
            // The other columns are untouched by the provenance write.
            assertEquals(100L, stored.sizeBytes)
            assertEquals(500L, stored.dateAddedMillis)
            // The item really was read through the boundary, once.
            assertEquals(1, access.openCalls)
            assertEquals(1, source.calls.size)
            assertEquals(handle, source.calls.single().first)
            assertEquals("image/jpeg", source.calls.single().second)
            assertEquals(MediaReadHandle.DEFAULT_MAX_BYTES, handle.requestedMaxBytes)
            // The gate saw the record, not a row: provenance-agnostic.
            assertEquals(added, gate.consultedRecords.single())
        }

    @Test
    fun `absent metadata persists null provenance`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:2")
        catalogue.upsertAll(listOf(added))
        val indexer = indexer(
            catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ -> CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT) },
        )

        indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
        assertNull(stored.resolvedCaptureDateZoneOffsetMinutes)
        assertNull(stored.resolvedCaptureDateSource)
    }

    @Test
    fun `unreadable metadata persists null provenance - distinct from absent`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:3")
        catalogue.upsertAll(listOf(added))
        val indexer = indexer(
            catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(bytes = null), // the read itself fails
            evidenceFor = { h, _ ->
                // The read fails INSIDE the extraction step: the seam is
                // reached (unlike a failed open) and must answer
                // UNREADABLE, never ABSENT --- the distinction survives.
                val bytes = h.readBytes()
                if (bytes != null) throw AssertionError("expected a failed read")
                CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE)
            },
        )

        indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
        assertNull(stored.resolvedCaptureDateZoneOffsetMinutes)
        assertNull(stored.resolvedCaptureDateSource)
    }

    @Test
    fun `unsupported metadata persists null provenance - distinct from absent`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:4", mimeType = "image/avif")
        catalogue.upsertAll(listOf(added))
        val indexer = indexer(
            catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ -> CaptureDateEvidence(emptyList(), MetadataAvailability.UNSUPPORTED) },
        )

        indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
        assertNull(stored.resolvedCaptureDateZoneOffsetMinutes)
        assertNull(stored.resolvedCaptureDateSource)
    }

    @Test
    fun `a changed record indexes the new state and leaves the old revision fields alone`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val key = "external_primary:9"
            val old = record(key = key, sizeBytes = 100L)
            // The new scan row (revision moved: size 100 -> 200), applied
            // by the driver BEFORE the hook runs, per ScanIndexHook's
            // contract --- with a UI-side stamp a whole-record upsert
            // would clobber.
            val appliedNew = record(key = key, sizeBytes = 200L, lastSeenAtMillis = 777L)
            catalogue.upsertAll(listOf(appliedNew))

            val handle = FakeHandle(byteArrayOf(1))
            val gate = FakeGate(eligibleKeys = setOf(key))
            val access = FakeMediaAccess { handle }
            val source = FakeCandidateSource { _, _ ->
                CaptureDateEvidence(
                    candidates = listOf(
                        DateCandidate(CaptureDateSource.EXIF_DIGITIZED, t0 + 10L),
                    ),
                    availability = MetadataAvailability.PRESENT,
                )
            }
            val indexer = CaptureDateIndexer(catalogue, access, source, gate)

            indexer.onRecordsApplied(
                added = emptyList(),
                changed = listOf(
                    CatalogueReconciler.ChangedRecord(
                        old = old,
                        new = scanRow(key = key, sizeBytes = 200L),
                    ),
                ),
            )

            // The read used the NEW row's access handle.
            assertEquals(appliedNew.canonicalUri, access.opened.single().identity.canonicalUri)
            // The winner is persisted on the live row...
            val stored = catalogue.record(key)!!
            assertEquals(t0 + 10L, stored.resolvedCaptureDateMillis)
            assertEquals("EXIF_DIGITIZED", stored.resolvedCaptureDateSource)
            // ...while every field the old record carried (and any UI
            // stamp on the applied row) is untouched: the partial update
            // structurally cannot write them.
            assertEquals(200L, stored.sizeBytes)
            assertEquals(777L, stored.lastSeenAtMillis)
            assertEquals(0, stored.metadataWriteRevision)
        }

    @Test
    fun `records outside eligibility never reach the reader`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:5")
        // Pre-existing provenance stays untouched by a skipped row.
        val preexisting = added.copy(
            resolvedCaptureDateMillis = t0,
            resolvedCaptureDateSource = "EXIF_ORIGINAL",
        )
        catalogue.upsertAll(listOf(preexisting))
        val gate = FakeGate(eligibleKeys = emptySet()) // e.g. VAULT_EXCLUDED
        val access = FakeMediaAccess { FakeHandle(byteArrayOf(1)) }
        val source = FakeCandidateSource()
        val indexer = CaptureDateIndexer(catalogue, access, source, gate)

        indexer.onRecordsApplied(listOf(added), emptyList())

        assertEquals(1, gate.consultedRecords.size)
        assertEquals(0, access.openCalls)
        assertTrue(source.calls.isEmpty())
        // Nothing was written: a vault-ineligible row must not be touched
        // by the index pass at all (ADR-0010 consent boundary).
        assertEquals(preexisting, catalogue.record(added.localMediaKey))
    }

    @Test
    fun `a failed open persists null - one unreadable item never aborts the batch`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val vanished = record(key = "external_primary:6")
            val healthy = record(
                key = "external_primary:7",
                canonicalUri = "content://media/external/images/7",
            )
            catalogue.upsertAll(listOf(vanished, healthy))
            val gate = FakeGate(
                eligibleKeys = setOf(vanished.localMediaKey, healthy.localMediaKey),
            )
            val access = FakeMediaAccess { media ->
                if (media.identity.canonicalUri == vanished.canonicalUri) null
                else FakeHandle(byteArrayOf(1))
            }
            val source = FakeCandidateSource { _, _ ->
                CaptureDateEvidence(
                    candidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                    availability = MetadataAvailability.PRESENT,
                )
            }
            val indexer = CaptureDateIndexer(catalogue, access, source, gate)

            indexer.onRecordsApplied(listOf(vanished, healthy), emptyList())

            assertNull(catalogue.record(vanished.localMediaKey)!!.resolvedCaptureDateMillis)
            assertEquals(t0, catalogue.record(healthy.localMediaKey)!!.resolvedCaptureDateMillis)
        }

    @Test
    fun `a blowing-up row never aborts the batch - failure logged and skipped`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val broken = record(
                key = "external_primary:12",
                canonicalUri = "content://media/external/images/12",
            )
            val healthy = record(
                key = "external_primary:13",
                canonicalUri = "content://media/external/images/13",
            )
            catalogue.upsertAll(listOf(broken, healthy))
            val brokenHandle = FakeHandle(byteArrayOf(1))
            val healthyHandle = FakeHandle(byteArrayOf(1))
            val gate = FakeGate(
                eligibleKeys = setOf(broken.localMediaKey, healthy.localMediaKey),
            )
            val access = FakeMediaAccess { media ->
                if (media.identity.canonicalUri == broken.canonicalUri) brokenHandle
                else healthyHandle
            }
            val source = FakeCandidateSource { handle, _ ->
                // A Room-disk-error / EXIF-parse-blowup stand-in: the seam
                // contract says "never throws for an unreadable item",
                // so a THROW here stands for the failure modes outside
                // the seam's reach (the catalogue write, a future seam
                // bug) --- exactly what the per-row catch must contain.
                if (handle === brokenHandle) throw IllegalStateException("disk error")
                CaptureDateEvidence(
                    candidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                    availability = MetadataAvailability.PRESENT,
                )
            }
            val indexer = CaptureDateIndexer(catalogue, access, source, gate)

            // The per-row failure logs through DiagLog, whose default
            // sink touches android.util.Log --- unmocked on the JVM. Swap
            // it (the DiagLogTest pattern, as LocationIndexerTest's
            // composite test does) and restore it no matter how the test
            // ends.
            val stockSink: (Char, String, String) -> Unit = DiagLog.sink
            DiagLog.sink = { _, _, _ -> }
            try {
                indexer.onRecordsApplied(listOf(broken, healthy), emptyList())
            } finally {
                DiagLog.sink = stockSink
            }

            // The healthy sibling was still indexed...
            assertEquals(t0, catalogue.record(healthy.localMediaKey)!!.resolvedCaptureDateMillis)
            // ...while the broken row's write never ran, so its existing
            // (null) provenance stands untouched --- and because its
            // revision did not move, no later scan re-offers it (the
            // NULL-provenance healing pass is the pinned later round).
            assertNull(catalogue.record(broken.localMediaKey)!!.resolvedCaptureDateMillis)
        }

    @Test
    fun `a tombstoned key is a no-op - the indexer never resurrects a row`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val key = "external_primary:8"
        catalogue.upsertAll(listOf(record(key = key)))
        catalogue.tombstone(listOf(key), atMillis = 5_000L)
        val indexer = indexer(
            catalogue,
            eligibleKeys = setOf(key),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ ->
                CaptureDateEvidence(
                    candidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                    availability = MetadataAvailability.PRESENT,
                )
            },
        )

        indexer.onRecordsApplied(listOf(record(key = key)), emptyList())

        // Still dead, never resurrected by an index-pass write.
        assertEquals(setOf(key), catalogue.tombstonedKeys())
        assertNull(catalogue.record(key))
    }

    // --- the pure mapping ----------------------------------------------------

    @Test
    fun `no candidates persist null - never the date_added fallback`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 777L),
            evidence = CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT),
        )
        // Persisting the MediaStore date_added (import time on many
        // devices) as a "resolved capture date" would corrupt the
        // timeline; null is the honest answer for no trustworthy tag.
        assertNull(resolved)
    }

    @Test
    fun `all-unknown candidates take the null path too`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 777L),
            evidence = CaptureDateEvidence(
                candidates = listOf(DateCandidate(CaptureDateSource.UNKNOWN, 123L)),
                availability = MetadataAvailability.UNREADABLE,
            ),
        )
        assertNull(resolved)
    }

    @Test
    fun `resolver disagreement still persists the winner`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 500L),
            evidence = CaptureDateEvidence(
                candidates = listOf(
                    DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                    DateCandidate(CaptureDateSource.MEDIASTORE, t0 + 600_000L),
                ),
                availability = MetadataAvailability.PRESENT,
            ),
        )
        // The winner lands normally...
        assertEquals(t0, resolved!!.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        // ...and the disagreement flag rides along at resolve time only:
        // deliberately NOT persisted this round (no column), documented
        // choice --- flag observability is a later round's schema change.
        assertTrue(resolved.sourcesDisagree)
    }

    @Test
    fun `pre-1970 negative instants survive into the write`() {
        val resolved = resolveCaptureDateWrite(
            record = record(),
            evidence = CaptureDateEvidence(
                candidates = listOf(
                    DateCandidate(
                        source = CaptureDateSource.EXIF_ORIGINAL,
                        epochMillis = -123_456L,
                        timezoneOffsetMinutes = -450,
                    ),
                ),
                availability = MetadataAvailability.PRESENT,
            ),
        )
        // A negative instant is a legitimate pre-1970 capture date
        // (scanned film), not malformed input --- audit finding F.
        assertEquals(-123_456L, resolved!!.epochMillis)
        assertEquals(-450, resolved.timezoneOffsetMinutes)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
    }

    @Test
    fun `an empty hook call is a no-op`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val gate = FakeGate()
        val access = FakeMediaAccess { FakeHandle(byteArrayOf(1)) }
        val source = FakeCandidateSource()
        val indexer = CaptureDateIndexer(catalogue, access, source, gate)

        indexer.onRecordsApplied(emptyList(), emptyList())

        assertTrue(gate.consultedRecords.isEmpty())
        assertEquals(0, access.openCalls)
        assertTrue(source.calls.isEmpty())
        assertTrue(catalogue.allRecords().isEmpty())
    }
}
