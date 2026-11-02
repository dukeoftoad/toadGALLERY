package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.InMemoryMediaCatalogue
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateEligibilityGate
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateEvidence
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateSource
import io.github.dukeoftoad.gallery.data.media.dates.DateCandidate
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.data.media.location.GpsEvidence
import io.github.dukeoftoad.gallery.data.media.location.ItemLocationWriter
import io.github.dukeoftoad.gallery.data.media.location.LocationReadGate
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the combined EXIF index pass (r20 round): the r12
 * capture-date matrix and the r16 location matrix, ported to the pass that
 * absorbed both standalone indexers, PLUS the r20-specific pins --- the
 * SINGLE-READ contract (one open + one extraction call per row, both
 * dimensions served from it) and the healing-stamp ordering (the stamp is
 * the LAST of the three writes; any failure leaves the row unstamped and
 * therefore selectable by [ProvenanceHealingPass]).
 *
 * Fakes: the eligibility gate, the [MediaAccess] opener, the read handle,
 * the combined extraction seam, the location write seam, the consent gate
 * and the healing store --- everything the pass touches that is not the
 * [InMemoryMediaCatalogue] reference implementation.
 *
 * MIGRATION-POLICY NOTE (deliberately NOT a test, the established
 * pattern): the Room v5 -> v6 migration (one `ALTER TABLE ... ADD COLUMN
 * ... DEFAULT NULL`, registered via `addMigrations(MIGRATION_5_6)`) cannot
 * be exercised by this JVM suite --- android.* / Room runtime is off the
 * unit-test classpath per ADR-0016. It is verified by (a) the hand-written
 * exported schema JSON (6.json) whose identityHash was re-derived with
 * Room 2.8.5's own algorithm, reproducing the committed v1-v5 hashes as
 * control, and (b) the explicit `addMigrations(..., MIGRATION_5_6)`
 * registration in `MediaCatalogueDatabase.build`, both reviewed before
 * commit. CI's KSP build regenerates the schema and must agree.
 */
class CombinedExifIndexerTest {

    private val t0 = 1_700_000_000_000L
    private val sf = GpsCoordinates(latitude = 37.7749, longitude = -122.4194)

    // --- fakes --------------------------------------------------------------

    /** Records what the gate was asked about; answers per configured key set. */
    private class FakeGate(
        private val eligibleKeys: Set<String> = emptySet(),
    ) : CaptureDateEligibilityGate {
        val consultedRecords = mutableListOf<CatalogueRecord>()

        override suspend fun eligiblePublicMedia(record: CatalogueRecord): EligiblePublicMedia? {
            consultedRecords.add(record)
            if (record.localMediaKey !in eligibleKeys) return null
            return MediaEligibility.PUBLIC_INDEXABLE.asEligible(
                MediaIdentity(
                    volumeName = "external_primary",
                    mediaStoreId = record.localMediaKey.substringAfterLast(':').toLong(),
                    canonicalUri = record.canonicalUri,
                ),
            )
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

    /** A [MediaReadHandle] serving fixed bytes, recording every read. */
    private class FakeHandle(
        private val bytes: ByteArray? = null,
    ) : MediaReadHandle {
        var readCalls = 0
        var requestedMaxBytes: Long? = null

        override suspend fun readBytes(maxBytes: Long): ByteArray? {
            readCalls++
            requestedMaxBytes = maxBytes
            return bytes
        }
    }

    /** The combined-seam fake: records calls, returns canned evidence. */
    private class FakeCombinedSource(
        private val evidenceFor: suspend (MediaReadHandle, String) -> CombinedExifEvidence =
            { _, _ ->
                CombinedExifEvidence(
                    date = CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT),
                    gps = GpsEvidence(null, MetadataAvailability.ABSENT),
                )
            },
    ) : CombinedExifSource {
        val calls = mutableListOf<Pair<MediaReadHandle, String>>()

        override suspend fun extract(
            handle: MediaReadHandle,
            mimeType: String,
        ): CombinedExifEvidence {
            calls.add(handle to mimeType)
            return evidenceFor(handle, mimeType)
        }
    }

    /** The consent seam fake: counts consultations, answers one flag. */
    private class FakeReadGate(private val readable: Boolean) : LocationReadGate {
        var consultations = 0

        override fun gpsReadable(): Boolean {
            consultations++
            return readable
        }
    }

    /** One recorded location write; plain data for exact assertions. */
    private data class LocationWrite(
        val localMediaKey: String,
        val latitude: Double?,
        val longitude: Double?,
        val sourceName: String?,
    )

    /** The location write-seam fake: records every targeted write. */
    private class RecordingLocationWriter : ItemLocationWriter {
        val writes = mutableListOf<LocationWrite>()

        override suspend fun updateLocation(
            localMediaKey: String,
            latitude: Double?,
            longitude: Double?,
            sourceName: String?,
        ) {
            writes.add(LocationWrite(localMediaKey, latitude, longitude, sourceName))
        }
    }

    /** The healing-store fake: stamps land in a map; rows re-selectable until stamped. */
    private class FakeHealingStore : ProvenanceHealingStore {
        val stamps = mutableMapOf<String, Long>()

        override suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord> = emptyList()

        override suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long) {
            stamps[localMediaKey] = atMillis
        }
    }

    /** The assembled kit: the indexer plus the fakes, for post-hoc assertions. */
    private class Kit(
        val indexer: CombinedExifIndexer,
        val catalogue: InMemoryMediaCatalogue,
        val writer: RecordingLocationWriter,
        val healingStore: FakeHealingStore,
        val access: FakeMediaAccess,
        val source: FakeCombinedSource,
        val readGate: FakeReadGate,
        val gate: FakeGate,
    )

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
    ): MediaScanRow =
        MediaScanRow(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
        )

    /** Evidence pair factory: keeps the invariant PRESENT iff value/list. */
    private fun evidence(
        dateCandidates: List<DateCandidate> = emptyList(),
        dateAvailability: MetadataAvailability =
            if (dateCandidates.isEmpty()) MetadataAvailability.ABSENT else MetadataAvailability.PRESENT,
        gps: GpsEvidence? = GpsEvidence(null, MetadataAvailability.ABSENT),
    ): CombinedExifEvidence =
        CombinedExifEvidence(
            date = CaptureDateEvidence(dateCandidates, dateAvailability),
            gps = gps,
        )

    /** Assembles the indexer with recording fakes wired around [writer]. */
    private fun kit(
        catalogue: InMemoryMediaCatalogue = InMemoryMediaCatalogue(),
        eligibleKeys: Set<String> = emptySet(),
        handleFor: (EligiblePublicMedia) -> MediaReadHandle? = { null },
        evidenceFor: suspend (MediaReadHandle, String) -> CombinedExifEvidence =
            { _, _ -> evidence() },
        gpsReadable: Boolean = true,
    ): Kit {
        val writer = RecordingLocationWriter()
        val healingStore = FakeHealingStore()
        val access = FakeMediaAccess(handleFor)
        val source = FakeCombinedSource(evidenceFor)
        val readGate = FakeReadGate(gpsReadable)
        val gate = FakeGate(eligibleKeys)
        return Kit(
            indexer = CombinedExifIndexer(
                catalogue = catalogue,
                locationWriter = writer,
                healingStore = healingStore,
                mediaAccess = access,
                combinedSource = source,
                eligibilityGate = gate,
                locationReadGate = readGate,
            ),
            catalogue = catalogue,
            writer = writer,
            healingStore = healingStore,
            access = access,
            source = source,
            readGate = readGate,
            gate = gate,
        )
    }

    // --- the r12 capture-date matrix, ported --------------------------------

    @Test
    fun `an added record with trustworthy exif evidence persists the resolved capture date`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val added = record()
            catalogue.upsertAll(listOf(added))
            val handle = FakeHandle(byteArrayOf(1))
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(added.localMediaKey),
                handleFor = { handle },
                evidenceFor = { h, _ ->
                    // Mimic the production extractor: the bytes are read
                    // once through the handle (the cap assertion below
                    // checks the boundary's default actually reaches the
                    // read).
                    h.readBytes()
                    evidence(
                        dateCandidates = listOf(
                            DateCandidate(
                                source = CaptureDateSource.EXIF_ORIGINAL,
                                epochMillis = t0,
                                timezoneOffsetMinutes = 120,
                                subsecondMillis = 250,
                            ),
                        ),
                    )
                },
            )

            k.indexer.onRecordsApplied(added = listOf(added), changed = emptyList())

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
            // The gate saw the record, not a row: provenance-agnostic.
            assertEquals(added, k.gate.consultedRecords.single())
        }

    @Test
    fun `absent date metadata persists null provenance`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:2")
        catalogue.upsertAll(listOf(added))
        val k = kit(
            catalogue = catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { FakeHandle(byteArrayOf(1)) },
            evidenceFor = { _, _ -> evidence() }, // ABSENT date, ABSENT gps
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
        assertNull(stored.resolvedCaptureDateZoneOffsetMinutes)
        assertNull(stored.resolvedCaptureDateSource)
    }

    @Test
    fun `unreadable date metadata persists null provenance - distinct from absent`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:3")
        catalogue.upsertAll(listOf(added))
        val k = kit(
            catalogue = catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { FakeHandle(bytes = null) }, // the read itself fails
            evidenceFor = { h, _ ->
                // The read fails INSIDE the extraction step: the seam is
                // reached (unlike a failed open) and must answer
                // UNREADABLE, never ABSENT --- the distinction survives.
                val bytes = h.readBytes()
                if (bytes != null) throw AssertionError("expected a failed read")
                CombinedExifEvidence(
                    date = CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE),
                    gps = GpsEvidence(null, MetadataAvailability.UNREADABLE),
                )
            },
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
        assertNull(stored.resolvedCaptureDateZoneOffsetMinutes)
        assertNull(stored.resolvedCaptureDateSource)
    }

    @Test
    fun `unsupported date metadata persists null provenance - distinct from absent`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:4", mimeType = "image/avif")
        catalogue.upsertAll(listOf(added))
        val k = kit(
            catalogue = catalogue,
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { FakeHandle(byteArrayOf(1)) },
            evidenceFor = { _, _ ->
                CombinedExifEvidence(
                    date = CaptureDateEvidence(emptyList(), MetadataAvailability.UNSUPPORTED),
                    gps = GpsEvidence(null, MetadataAvailability.UNSUPPORTED),
                )
            },
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        val stored = catalogue.record(added.localMediaKey)!!
        assertNull(stored.resolvedCaptureDateMillis)
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
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(key),
                handleFor = { handle },
                evidenceFor = { _, _ ->
                    evidence(dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_DIGITIZED, t0 + 10L)))
                },
            )

            k.indexer.onRecordsApplied(
                added = emptyList(),
                changed = listOf(
                    CatalogueReconciler.ChangedRecord(
                        old = old,
                        new = scanRow(key = key, sizeBytes = 200L),
                    ),
                ),
            )

            // The read used the NEW row's access handle.
            assertEquals(appliedNew.canonicalUri, k.access.opened.single().identity.canonicalUri)
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
        val k = kit(
            catalogue = catalogue,
            eligibleKeys = emptySet(), // e.g. VAULT_EXCLUDED
            handleFor = { FakeHandle(byteArrayOf(1)) },
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        assertEquals(1, k.gate.consultedRecords.size)
        assertEquals(0, k.access.openCalls)
        assertTrue(k.source.calls.isEmpty())
        assertTrue(k.writer.writes.isEmpty())
        // Nothing was written: a vault-ineligible row must not be touched
        // by the index pass at all (ADR-0010 consent boundary).
        assertEquals(preexisting, catalogue.record(added.localMediaKey))
        // No stamp either: the row was skipped, not indexed.
        assertTrue(added.localMediaKey !in k.healingStore.stamps)
    }

    @Test
    fun `a failed open persists null date and null location - one unreadable item never aborts the batch`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val vanished = record(key = "external_primary:6")
            val healthy = record(
                key = "external_primary:7",
                canonicalUri = "content://media/external/images/7",
            )
            catalogue.upsertAll(listOf(vanished, healthy))
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(vanished.localMediaKey, healthy.localMediaKey),
                handleFor = { media ->
                    if (media.identity.canonicalUri == vanished.canonicalUri) null
                    else FakeHandle(byteArrayOf(1))
                },
                evidenceFor = { _, _ ->
                    evidence(
                        dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                        gps = GpsEvidence(sf, MetadataAvailability.PRESENT),
                    )
                },
            )

            k.indexer.onRecordsApplied(listOf(vanished, healthy), emptyList())

            // The vanished row's honest degradation: null date (its
            // revision moved; stale values must not survive as truth)...
            assertNull(catalogue.record(vanished.localMediaKey)!!.resolvedCaptureDateMillis)
            // ...null coordinates (could not read: honestly unknown)...
            val vanishedWrite = k.writer.writes[0]
            assertNull(vanishedWrite.latitude)
            assertNull(vanishedWrite.sourceName)
            // ...and STILL a stamp (the writes ran; only the reads failed).
            assertTrue(vanished.localMediaKey in k.healingStore.stamps)
            // The healthy sibling landed both dimensions.
            assertEquals(t0, catalogue.record(healthy.localMediaKey)!!.resolvedCaptureDateMillis)
            assertEquals("EXIF_GPS", k.writer.writes[1].sourceName)
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
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(broken.localMediaKey, healthy.localMediaKey),
                handleFor = { media ->
                    if (media.identity.canonicalUri == broken.canonicalUri) brokenHandle
                    else healthyHandle
                },
                evidenceFor = { handle, _ ->
                    // A Room-disk-error / EXIF-parse-blowup stand-in: the
                    // seam contract says "never throws for an unreadable
                    // item", so a THROW here stands for the failure modes
                    // outside the seam's reach (the catalogue write, a
                    // future seam bug) --- exactly what the per-row catch
                    // must contain.
                    if (handle === brokenHandle) throw IllegalStateException("disk error")
                    evidence(
                        dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                        gps = GpsEvidence(sf, MetadataAvailability.PRESENT),
                    )
                },
            )

            // The per-row failure logs through DiagLog, whose default sink
            // touches android.util.Log --- unmocked on the JVM. Swap it
            // (the DiagLogTest pattern) and restore it no matter how the
            // test ends.
            val stockSink: (Char, String, String) -> Unit = DiagLog.sink
            DiagLog.sink = { _, _, _ -> }
            try {
                k.indexer.onRecordsApplied(listOf(broken, healthy), emptyList())
            } finally {
                DiagLog.sink = stockSink
            }

            // The healthy sibling was still indexed in BOTH dimensions...
            assertEquals(t0, catalogue.record(healthy.localMediaKey)!!.resolvedCaptureDateMillis)
            assertEquals("EXIF_GPS", k.writer.writes.single().sourceName)
            // ...while the broken row's date write never ran, so its
            // existing (null) provenance stands untouched.
            assertNull(catalogue.record(broken.localMediaKey)!!.resolvedCaptureDateMillis)
            // THE r20 PIN: the broken row is also UNSTAMPED --- that is
            // exactly what makes the healing pass re-offer it later (the
            // r19-named follow-up this round closes).
            assertTrue(broken.localMediaKey !in k.healingStore.stamps)
            assertTrue(healthy.localMediaKey in k.healingStore.stamps)
        }

    @Test
    fun `a tombstoned key is a no-op - the pass never resurrects a row`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val key = "external_primary:8"
        catalogue.upsertAll(listOf(record(key = key)))
        catalogue.tombstone(listOf(key), atMillis = 5_000L)
        val k = kit(
            catalogue = catalogue,
            eligibleKeys = setOf(key),
            handleFor = { FakeHandle(byteArrayOf(1)) },
            evidenceFor = { _, _ ->
                evidence(dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)))
            },
        )

        k.indexer.onRecordsApplied(listOf(record(key = key)), emptyList())

        // Still dead, never resurrected by an index-pass write.
        assertEquals(setOf(key), catalogue.tombstonedKeys())
        assertNull(catalogue.record(key))
    }

    // --- the r16 location matrix, ported -------------------------------------

    @Test
    fun `a parsed fix persists coordinates with EXIF_GPS provenance`() = runBlocking {
        val handle = FakeHandle(byteArrayOf(1))
        val added = record()
        val k = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { handle },
            evidenceFor = { h, _ ->
                h.readBytes()
                evidence(gps = GpsEvidence(sf, MetadataAvailability.PRESENT))
            },
        )

        k.indexer.onRecordsApplied(added = listOf(added), changed = emptyList())

        val write = k.writer.writes.single()
        assertEquals(added.localMediaKey, write.localMediaKey)
        assertEquals(37.7749, write.latitude!!, 0.0)
        assertEquals(-122.4194, write.longitude!!, 0.0)
        assertEquals("EXIF_GPS", write.sourceName)
    }

    @Test
    fun `an ungranted read gate still reads for the date but writes the withheld location provenance`() =
        runBlocking {
            // The r20 behavior change, pinned deliberately: the DATE half
            // needs the bytes (capture dates are never redacted), so the
            // single read happens even ungranted --- but the GPS half of
            // the evidence is NOT consulted, and the location write is
            // decided from consent + capability alone (the r16 rule).
            val catalogue = InMemoryMediaCatalogue()
            val added = record(key = "external_primary:2")
            catalogue.upsertAll(listOf(added))
            val handle = FakeHandle(byteArrayOf(1))
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(added.localMediaKey),
                handleFor = { handle },
                evidenceFor = { h, _ ->
                    h.readBytes()
                    // The source WOULD hand back coordinates; the pass must
                    // ignore them ungranted.
                    evidence(
                        dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                        gps = GpsEvidence(sf, MetadataAvailability.PRESENT),
                    )
                },
                gpsReadable = false,
            )

            k.indexer.onRecordsApplied(listOf(added), emptyList())

            // The withheld provenance, exactly the r16 semantics.
            val write = k.writer.writes.single()
            assertNull(write.latitude)
            assertNull(write.longitude)
            assertEquals("MEDIA_LOCATION_UNGRANTED", write.sourceName)
            // The date half still landed from the SAME single read...
            assertEquals(t0, catalogue.record(added.localMediaKey)!!.resolvedCaptureDateMillis)
            // ...and the consent gate was consulted exactly once for the
            // whole batch (not per row).
            assertEquals(1, k.readGate.consultations)
        }

    @Test
    fun `an ungranted read gate on a non-EXIF-capable format writes plain nulls`() = runBlocking {
        // PNG: the format matrix says EXIF ABSENT --- the redaction is
        // moot, so claiming "withheld" would be a lie.
        val added = record(key = "external_primary:3", mimeType = "image/png")
        val k = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { FakeHandle(byteArrayOf(1)) },
            gpsReadable = false,
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        val write = k.writer.writes.single()
        assertNull(write.latitude)
        assertNull(write.longitude)
        assertNull(write.sourceName)
    }

    @Test
    fun `absent location metadata persists null provenance`() = runBlocking {
        val added = record(key = "external_primary:4")
        val k = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handleFor = { FakeHandle(byteArrayOf(1)) },
            evidenceFor = { _, _ -> evidence() }, // ABSENT both
        )

        k.indexer.onRecordsApplied(listOf(added), emptyList())

        val write = k.writer.writes.single()
        assertNull(write.latitude)
        assertNull(write.longitude)
        assertNull(write.sourceName)
    }

    // --- the r20 pins: single read + stamp ordering --------------------------

    @Test
    fun `one open and one extraction serve BOTH dimensions - the single-read contract`() =
        runBlocking {
            val catalogue = InMemoryMediaCatalogue()
            val first = record(key = "external_primary:21")
            val second = record(
                key = "external_primary:22",
                canonicalUri = "content://media/external/images/22",
            )
            catalogue.upsertAll(listOf(first, second))
            val handle1 = FakeHandle(byteArrayOf(1))
            val handle2 = FakeHandle(byteArrayOf(2))
            val k = kit(
                catalogue = catalogue,
                eligibleKeys = setOf(first.localMediaKey, second.localMediaKey),
                handleFor = { media ->
                    if (media.identity.canonicalUri == first.canonicalUri) handle1 else handle2
                },
                evidenceFor = { h, _ ->
                    h.readBytes() // the production extractor's single read
                    evidence(
                        dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                        gps = GpsEvidence(sf, MetadataAvailability.PRESENT),
                    )
                },
            )

            k.indexer.onRecordsApplied(listOf(first, second), emptyList())

            // THE r20 CONTRACT: per row exactly ONE open, ONE extraction
            // call, ONE byte read --- where the r12+r16 pair paid two of
            // each (the pre-r20 indexers read the same bytes twice).
            assertEquals(2, k.access.openCalls)
            assertEquals(2, k.source.calls.size)
            assertEquals(1, handle1.readCalls)
            assertEquals(1, handle2.readCalls)
            assertEquals(handle1, k.source.calls[0].first)
            assertEquals("image/jpeg", k.source.calls[0].second)
            assertEquals(MediaReadHandle.DEFAULT_MAX_BYTES, handle1.requestedMaxBytes)
            // Both dimensions landed for both rows.
            assertEquals(2, k.writer.writes.size)
            assertEquals(t0, catalogue.record(first.localMediaKey)!!.resolvedCaptureDateMillis)
            assertEquals(t0, catalogue.record(second.localMediaKey)!!.resolvedCaptureDateMillis)
            assertEquals("EXIF_GPS", k.writer.writes[0].sourceName)
            assertEquals("EXIF_GPS", k.writer.writes[1].sourceName)
            // And both rows are stamped (fully indexed).
            assertEquals(2, k.healingStore.stamps.size)
        }

    @Test
    fun `a failing location write leaves the row unstamped - the stamp is last`() = runBlocking {
        // The healing stamp must land ONLY after BOTH writes returned; a
        // location-write failure (disk error mid-pass) means the row is
        // not fully indexed and must stay selectable for healing.
        val catalogue = InMemoryMediaCatalogue()
        val added = record(key = "external_primary:31")
        catalogue.upsertAll(listOf(added))
        val writer = object : ItemLocationWriter {
            override suspend fun updateLocation(
                localMediaKey: String,
                latitude: Double?,
                longitude: Double?,
                sourceName: String?,
            ) {
                error("disk error")
            }
        }
        val healingStore = FakeHealingStore()
        val indexer = CombinedExifIndexer(
            catalogue = catalogue,
            locationWriter = writer,
            healingStore = healingStore,
            mediaAccess = FakeMediaAccess { FakeHandle(byteArrayOf(1)) },
            combinedSource = FakeCombinedSource { _, _ ->
                evidence(
                    dateCandidates = listOf(DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0)),
                    gps = GpsEvidence(sf, MetadataAvailability.PRESENT),
                )
            },
            eligibilityGate = FakeGate(setOf(added.localMediaKey)),
            locationReadGate = FakeReadGate(readable = true),
        )

        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        try {
            indexer.onRecordsApplied(listOf(added), emptyList())
        } finally {
            DiagLog.sink = stockSink
        }

        // The date write already landed (it runs first)...
        assertEquals(t0, catalogue.record(added.localMediaKey)!!.resolvedCaptureDateMillis)
        // ...but the row is NOT stamped: the stamp is the LAST write, and
        // the healing pass will re-offer this row (re-writing the date
        // idempotently, then the location, then the stamp).
        assertTrue(added.localMediaKey !in healingStore.stamps)
    }

    @Test
    fun `an empty hook call is a no-op`() = runBlocking {
        val k = kit(handleFor = { FakeHandle(byteArrayOf(1)) })

        k.indexer.onRecordsApplied(emptyList(), emptyList())

        assertTrue(k.gate.consultedRecords.isEmpty())
        assertEquals(0, k.access.openCalls)
        assertTrue(k.source.calls.isEmpty())
        assertTrue(k.writer.writes.isEmpty())
        assertTrue(k.healingStore.stamps.isEmpty())
        assertTrue(k.catalogue.allRecords().isEmpty())
        // The consent gate IS read once even for an empty batch (it is
        // per-CALL, ahead of the loop --- the r16 LocationIndexer's exact
        // batch-head semantics, kept by the combined pass; one no-op read
        // of a boolean gate costs nothing).
        assertEquals(1, k.readGate.consultations)
    }
}
