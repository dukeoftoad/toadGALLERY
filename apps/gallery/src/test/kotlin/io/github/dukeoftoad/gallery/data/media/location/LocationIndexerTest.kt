package io.github.dukeoftoad.gallery.data.media.location

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler
import io.github.dukeoftoad.gallery.data.media.changes.MediaScanRow
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateEligibilityGate
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the location index pass (r16 round): the pure mapping
 * ([resolveLocationWrite]) plus the end-to-end hook behavior against fakes
 * for the eligibility gate, the [MediaAccess] opener, the read handle, the
 * GPS extraction seam, the consent read gate and the [ItemLocationWriter]
 * write seam --- the same fake-row/fake-handle discipline as
 * `CaptureDateIndexerTest` (the indexer this one mirrors).
 *
 * What is pinned here:
 * - a parsed fix lands as latitude/longitude + EXIF_GPS provenance, read
 *   through the byte boundary exactly once;
 * - the CONSENT SUBTLETY: on an ungranted read gate an EXIF-capable row
 *   persists the honest withheld provenance (MEDIA_LOCATION_UNGRANTED)
 *   with NO bytes read, while a non-EXIF-capable row persists plain nulls
 *   (the redaction is moot for a format that cannot carry EXIF GPS);
 * - ABSENT / UNREADABLE / UNSUPPORTED evidence EACH persist null
 *   provenance --- each for its own reason, which stays distinguishable at
 *   the seam even though the catalogue null is single-valued (the
 *   capture-date doctrine, unchanged);
 * - a PRESENT flag with null coordinates is a seam-contract violation and
 *   is defended against (nulls persisted, never a trusted half-write);
 * - ineligible records never reach the reader at all (vault gate by type);
 * - the changed-record path runs on the NEW row's key/uri;
 * - one unreadable item never aborts the batch;
 * - the consent gate is consulted ONCE per hook call, not per row;
 * - a throwing hook in the composite fan-out never starves its sibling
 *   (the composite's failure isolation, exercised through DiagLog's
 *   swappable sink so `android.util.Log` is never touched on the JVM).
 *
 * MIGRATION-POLICY NOTE (deliberately NOT a test, the
 * `CaptureDateIndexerTest` pattern): the Room v3 -> v4 migration (three
 * `ALTER TABLE ... ADD COLUMN ... DEFAULT NULL` statements, registered via
 * `addMigrations(MIGRATION_3_4)` and never
 * `fallbackToDestructiveMigration`) cannot be exercised by this JVM suite
 * --- android.* / Room runtime is off the unit-test classpath per
 * ADR-0016. It is verified by (a) the hand-written exported Room schema
 * JSON (apps/gallery/schemas/...MediaCatalogueDatabase/4.json) matching
 * the migrated-table shape, INCLUDING an identityHash recomputed with
 * Room 2.8.5's own algorithm (SchemaIdentityKey over entity id-keys ---
 * source: androidx/room/room-compiler/2.8.5-sources.jar,
 * androidx/room/vo/SchemaIdentityKey.kt) and cross-validated by
 * reproducing the committed v1/v2/v3 hashes with the same computation,
 * and (b) the explicit `addMigrations(MIGRATION_1_2, MIGRATION_2_3,
 * MIGRATION_3_4)` registration in `MediaCatalogueDatabase.build`, both
 * reviewed before commit.
 */
class LocationIndexerTest {

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
    private class FakeGpsSource(
        private val evidenceFor: suspend (MediaReadHandle, String) -> GpsEvidence =
            { _, _ -> GpsEvidence(null, MetadataAvailability.ABSENT) },
    ) : GpsCandidateSource {
        val calls = mutableListOf<Pair<MediaReadHandle, String>>()

        override suspend fun extractCoordinates(
            handle: MediaReadHandle,
            mimeType: String,
        ): GpsEvidence {
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

    /** One recorded provenance write; plain data for exact assertions. */
    private data class LocationWrite(
        val localMediaKey: String,
        val latitude: Double?,
        val longitude: Double?,
        val sourceName: String?,
    )

    /** The write-seam fake: records every targeted location write. */
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

    /** The assembled kit: the indexer plus the fakes, for post-hoc assertions. */
    private class Kit(
        val indexer: LocationIndexer,
        val writer: RecordingLocationWriter,
        val access: FakeMediaAccess,
        val source: FakeGpsSource,
        val readGate: FakeReadGate,
    )

    // --- helpers ------------------------------------------------------------

    private fun record(
        key: String = "external_primary:1",
        canonicalUri: String = "content://media/external/images/1",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 100L,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
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

    /** Assembles the indexer with recording fakes wired around [writer]. */
    private fun kit(
        eligibleKeys: Set<String>,
        handle: MediaReadHandle?,
        evidenceFor: suspend (MediaReadHandle, String) -> GpsEvidence =
            { _, _ -> GpsEvidence(null, MetadataAvailability.ABSENT) },
        gpsReadable: Boolean = true,
    ): Kit {
        val writer = RecordingLocationWriter()
        val access = FakeMediaAccess { handle }
        val source = FakeGpsSource(evidenceFor)
        val readGate = FakeReadGate(gpsReadable)
        return Kit(
            indexer = LocationIndexer(
                locationWriter = writer,
                mediaAccess = access,
                candidateSource = source,
                eligibilityGate = FakeGate(eligibleKeys),
                locationReadGate = readGate,
            ),
            writer = writer,
            access = access,
            source = source,
            readGate = readGate,
        )
    }

    // --- end-to-end hook behavior -------------------------------------------

    @Test
    fun `an added record with a parsed fix persists coordinates with EXIF_GPS provenance`() =
        runBlocking {
            val handle = FakeHandle(byteArrayOf(1))
            val added = record()
            val kit = kit(
                eligibleKeys = setOf(added.localMediaKey),
                handle = handle,
                evidenceFor = { h, _ ->
                    // Mimic the production extractor: the bytes are read
                    // once through the handle (the cap assertion below
                    // checks the boundary's default reaches the read).
                    h.readBytes()
                    GpsEvidence(sf, MetadataAvailability.PRESENT)
                },
            )

            kit.indexer.onRecordsApplied(added = listOf(added), changed = emptyList())

            val write = kit.writer.writes.single()
            assertEquals(added.localMediaKey, write.localMediaKey)
            assertEquals(37.7749, write.latitude!!, 0.0)
            assertEquals(-122.4194, write.longitude!!, 0.0)
            assertEquals("EXIF_GPS", write.sourceName)
            // The item really was read through the boundary, once, and the
            // evidence seam saw the handle + the record's MIME type.
            assertEquals(1, kit.access.openCalls)
            assertEquals(1, kit.source.calls.size)
            assertEquals(handle, kit.source.calls.single().first)
            assertEquals("image/jpeg", kit.source.calls.single().second)
            assertEquals(MediaReadHandle.DEFAULT_MAX_BYTES, handle.requestedMaxBytes)
        }

    @Test
    fun `an ungranted read gate withholds bytes and writes the honest withheld provenance`() =
        runBlocking {
            val added = record(key = "external_primary:2")
            val kit = kit(
                eligibleKeys = setOf(added.localMediaKey),
                handle = FakeHandle(byteArrayOf(1)), // would serve bytes if (wrongly) asked
                evidenceFor = { _, _ -> throw AssertionError("extraction must not run ungranted") },
                gpsReadable = false,
            )

            kit.indexer.onRecordsApplied(listOf(added), emptyList())

            val write = kit.writer.writes.single()
            assertNull(write.latitude)
            assertNull(write.longitude)
            assertEquals("MEDIA_LOCATION_UNGRANTED", write.sourceName)
            // NO bytes were read: the platform would hand back redacted
            // tags anyway, so the pass must not even open the item.
            assertEquals(0, kit.access.openCalls)
            assertTrue(kit.source.calls.isEmpty())
            // The consent gate itself was consulted exactly once for the
            // whole batch (not per row).
            assertEquals(1, kit.readGate.consultations)
        }

    @Test
    fun `an ungranted read gate on a non-EXIF-capable format writes plain nulls`() =
        runBlocking {
            // PNG: the format matrix says EXIF ABSENT --- the redaction is
            // moot, so claiming "withheld" would be a lie.
            val added = record(key = "external_primary:3", mimeType = "image/png")
            val kit = kit(
                eligibleKeys = setOf(added.localMediaKey),
                handle = FakeHandle(byteArrayOf(1)),
                gpsReadable = false,
            )

            kit.indexer.onRecordsApplied(listOf(added), emptyList())

            val write = kit.writer.writes.single()
            assertNull(write.latitude)
            assertNull(write.longitude)
            assertNull(write.sourceName)
        }

    @Test
    fun `absent metadata persists null provenance`() = runBlocking {
        val added = record(key = "external_primary:4")
        val kit = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ -> GpsEvidence(null, MetadataAvailability.ABSENT) },
        )

        kit.indexer.onRecordsApplied(listOf(added), emptyList())

        val write = kit.writer.writes.single()
        assertNull(write.latitude)
        assertNull(write.longitude)
        assertNull(write.sourceName)
    }

    @Test
    fun `unreadable metadata persists null provenance - distinct from absent`() = runBlocking {
        val added = record(key = "external_primary:5")
        val kit = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(bytes = null), // the read itself fails
            evidenceFor = { h, _ ->
                val bytes = h.readBytes()
                if (bytes != null) throw AssertionError("expected a failed read")
                GpsEvidence(null, MetadataAvailability.UNREADABLE)
            },
        )

        kit.indexer.onRecordsApplied(listOf(added), emptyList())

        val write = kit.writer.writes.single()
        assertNull(write.latitude)
        assertNull(write.longitude)
        assertNull(write.sourceName)
    }

    @Test
    fun `unsupported metadata persists null provenance - distinct from absent`() = runBlocking {
        val added = record(key = "external_primary:6", mimeType = "image/avif")
        val kit = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ -> GpsEvidence(null, MetadataAvailability.UNSUPPORTED) },
        )

        kit.indexer.onRecordsApplied(listOf(added), emptyList())

        val write = kit.writer.writes.single()
        assertNull(write.latitude)
        assertNull(write.longitude)
        assertNull(write.sourceName)
    }

    @Test
    fun `a failed open persists nulls - one unreadable item never aborts the batch`() =
        runBlocking {
            val vanished = record(key = "external_primary:7")
            val healthy = record(
                key = "external_primary:8",
                canonicalUri = "content://media/external/images/8",
            )
            val writer = RecordingLocationWriter()
            val access = FakeMediaAccess { media ->
                if (media.identity.canonicalUri == vanished.canonicalUri) null
                else FakeHandle(byteArrayOf(1))
            }
            val source = FakeGpsSource { _, _ -> GpsEvidence(sf, MetadataAvailability.PRESENT) }
            val indexer = LocationIndexer(
                locationWriter = writer,
                mediaAccess = access,
                candidateSource = source,
                eligibilityGate = FakeGate(setOf(vanished.localMediaKey, healthy.localMediaKey)),
                locationReadGate = FakeReadGate(readable = true),
            )

            indexer.onRecordsApplied(listOf(vanished, healthy), emptyList())

            assertEquals(2, writer.writes.size)
            val vanishedWrite = writer.writes[0]
            assertNull(vanishedWrite.latitude)
            assertNull(vanishedWrite.longitude)
            assertNull(vanishedWrite.sourceName)
            val healthyWrite = writer.writes[1]
            assertEquals(37.7749, healthyWrite.latitude!!, 0.0)
            assertEquals("EXIF_GPS", healthyWrite.sourceName)
        }

    @Test
    fun `records outside eligibility never reach the reader or the writer`() = runBlocking {
        val added = record(key = "external_primary:9")
        val kit = kit(
            eligibleKeys = emptySet(), // e.g. VAULT_EXCLUDED
            handle = FakeHandle(byteArrayOf(1)),
        )

        kit.indexer.onRecordsApplied(listOf(added), emptyList())

        // Nothing was read and nothing was written: a vault-ineligible
        // row must not be touched by the index pass at all (ADR-0010
        // consent boundary).
        assertTrue(kit.writer.writes.isEmpty())
        assertEquals(0, kit.access.openCalls)
        assertTrue(kit.source.calls.isEmpty())
    }

    @Test
    fun `a changed record indexes the new state`() = runBlocking {
        val key = "external_primary:10"
        val old = record(key = key, canonicalUri = "content://media/external/images/old")
        val newRow = scanRow(key = key, canonicalUri = "content://media/external/images/10")
        val handle = FakeHandle(byteArrayOf(1))
        val writer = RecordingLocationWriter()
        val gate = FakeGate(setOf(key))
        val access = FakeMediaAccess { handle }
        val source = FakeGpsSource { _, _ -> GpsEvidence(sf, MetadataAvailability.PRESENT) }
        val indexer = LocationIndexer(
            locationWriter = writer,
            mediaAccess = access,
            candidateSource = source,
            eligibilityGate = gate,
            locationReadGate = FakeReadGate(readable = true),
        )

        indexer.onRecordsApplied(
            added = emptyList(),
            changed = listOf(
                CatalogueReconciler.ChangedRecord(old = old, new = newRow),
            ),
        )

        // The write is keyed by the NEW row's key, the read used the NEW
        // row's access handle, and the gate saw the new state's record.
        val write = writer.writes.single()
        assertEquals(key, write.localMediaKey)
        assertEquals("EXIF_GPS", write.sourceName)
        assertEquals(newRow.canonicalUri, access.opened.single().identity.canonicalUri)
        assertEquals(newRow.toRecord(), gate.consultedRecords.single())
    }

    @Test
    fun `an empty hook call is a no-op`() = runBlocking {
        val kit = kit(
            eligibleKeys = emptySet(),
            handle = FakeHandle(byteArrayOf(1)),
        )

        kit.indexer.onRecordsApplied(emptyList(), emptyList())

        assertTrue(kit.writer.writes.isEmpty())
        assertEquals(0, kit.access.openCalls)
        assertTrue(kit.source.calls.isEmpty())
    }

    // --- the pure mapping ----------------------------------------------------

    @Test
    fun `the resolveLocationWrite decision table is exhaustive and honest`() {
        val present = GpsEvidence(sf, MetadataAvailability.PRESENT)

        // Readable + PRESENT -> coordinates + EXIF_GPS.
        val hit = resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = present)
        assertEquals(37.7749, hit.latitude!!, 0.0)
        assertEquals(-122.4194, hit.longitude!!, 0.0)
        assertEquals(ItemLocationSource.EXIF_GPS, hit.source)

        // Ungranted + EXIF-capable -> withheld provenance, no coordinates.
        assertEquals(
            ResolvedLocation(null, null, ItemLocationSource.MEDIA_LOCATION_UNGRANTED),
            resolveLocationWrite(gpsReadable = false, exifCapable = true, evidence = present),
        )
        // Ungranted + not EXIF-capable -> plain nulls (the redaction is moot).
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = false, exifCapable = false, evidence = present),
        )
        // Ungranted + unopenable is the same story: no bytes, no claim.
        assertEquals(
            ResolvedLocation(null, null, ItemLocationSource.MEDIA_LOCATION_UNGRANTED),
            resolveLocationWrite(gpsReadable = false, exifCapable = true, evidence = null),
        )
        // Readable + failed open -> honest nulls (for a changed row, stale
        // coordinates must NOT survive as truth).
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = null),
        )
        // Readable + each non-PRESENT availability -> nulls, one per reason.
        for (availability in listOf(
            MetadataAvailability.ABSENT,
            MetadataAvailability.UNSUPPORTED,
            MetadataAvailability.UNREADABLE,
        )) {
            assertEquals(
                ResolvedLocation(null, null, null),
                resolveLocationWrite(
                    gpsReadable = true,
                    exifCapable = true,
                    evidence = GpsEvidence(null, availability),
                ),
            )
        }
    }

    @Test
    fun `a PRESENT flag with null coordinates is defended against - never a trusted half-write`() {
        val broken = GpsEvidence(null, MetadataAvailability.PRESENT)
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = broken),
        )
    }

    @Test
    fun `a prime-meridian fix survives as a coordinate - W is negative, not absent`() {
        // A Western-hemisphere fix parsed at the meridian edge is a REAL
        // coordinate (ExifMath already normalizes -0.0 to +0.0); the
        // mapping must not "helpfully" null anything.
        val greenwich = GpsCoordinates(latitude = 51.4779, longitude = 0.0)
        val resolved = resolveLocationWrite(
            gpsReadable = true,
            exifCapable = true,
            evidence = GpsEvidence(greenwich, MetadataAvailability.PRESENT),
        )
        assertEquals(51.4779, resolved.latitude!!, 0.0)
        assertEquals(0.0, resolved.longitude!!, 0.0)
        assertEquals(ItemLocationSource.EXIF_GPS, resolved.source)
    }

    // --- the composite fan-out -----------------------------------------------

    @Test
    fun `a throwing hook does not starve its sibling in the composite`() = runBlocking {
        val added = record(key = "external_primary:11")
        val healthyKit = kit(
            eligibleKeys = setOf(added.localMediaKey),
            handle = FakeHandle(byteArrayOf(1)),
            evidenceFor = { _, _ -> GpsEvidence(sf, MetadataAvailability.PRESENT) },
        )
        val throwing = LocationIndexer(
            locationWriter = ItemLocationWriter { _, _, _, _ -> error("boom") },
            mediaAccess = FakeMediaAccess { null },
            candidateSource = FakeGpsSource(),
            eligibilityGate = FakeGate(setOf(added.localMediaKey)),
            locationReadGate = FakeReadGate(readable = true),
        )
        val composite = CompositeScanIndexHook(listOf(throwing, healthyKit.indexer))

        // The composite logs the sibling's failure through DiagLog, whose
        // default sink touches android.util.Log --- unmocked on the JVM.
        // Swap it (the DiagLogTest pattern) so the isolation test stays
        // pure-JVM, and restore it no matter how the test ends.
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        try {
            composite.onRecordsApplied(listOf(added), emptyList())
        } finally {
            DiagLog.sink = stockSink
        }

        // The sibling ran despite the throw: exactly its own write landed,
        // and the failure was contained (not propagated --- the scan
        // survives either way; the driver's own catch-all is the second
        // lock, this test pins the first).
        assertEquals(1, healthyKit.writer.writes.size)
        assertEquals("EXIF_GPS", healthyKit.writer.writes.single().sourceName)
    }
}
