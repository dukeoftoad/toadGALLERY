package io.github.dukeoftoad.gallery.data.catalogue

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue semantics contract (Phase 4, ADR-0016): Room glue is
 * untested, so [InMemoryMediaCatalogue] --- the reference implementation
 * [RoomMediaCatalogue][io.github.dukeoftoad.gallery.data.catalogue.room
 * .RoomMediaCatalogue] must match --- pins every rule on the JVM:
 * upsert/replace, tombstone/removal, resurrection, checkpoints and purge
 * retention. All timestamps are caller-supplied (no clock), which is what
 * makes the retention boundaries exactly assertable.
 *
 * Pure JUnit 4 / JVM: no android.* types; `runBlocking` drives the suspend
 * surface (kotlinx-coroutines-core is already on the unit-test classpath
 * via room-ktx / Compose).
 */
class InMemoryMediaCatalogueTest {

    private fun record(
        key: String = "external_primary:1",
        sizeBytes: Long = 100L,
        dateModifiedMillis: Long = 1_000L,
        generationToken: String? = null,
        metadataWriteRevision: Int = 0,
        metadataCacheState: MetadataCacheState = MetadataCacheState.EMPTY,
        lastSeenAtMillis: Long = 0L,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = sizeBytes,
            dateModifiedMillis = dateModifiedMillis,
            dateAddedMillis = 500L,
            generationToken = generationToken,
            metadataWriteRevision = metadataWriteRevision,
            metadataCacheState = metadataCacheState,
            lastSeenAtMillis = lastSeenAtMillis,
        )

    @Test
    fun `upsert then record round-trips the full row`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val original = record(
            key = "external_primary:42",
            sizeBytes = 2_048L,
            generationToken = "17",
            metadataWriteRevision = 3,
            metadataCacheState = MetadataCacheState.STALE,
            lastSeenAtMillis = 99L,
        )
        catalogue.upsertAll(listOf(original))

        assertEquals(original, catalogue.record("external_primary:42"))
    }

    @Test
    fun `upsertAll replaces an existing record instead of duplicating it`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:7", sizeBytes = 1L)))
        val updated = record(key = "external_primary:7", sizeBytes = 2L)

        catalogue.upsertAll(listOf(updated))

        assertEquals(1, catalogue.allRecords().size)
        assertEquals(2L, catalogue.record("external_primary:7")!!.sizeBytes)
    }

    @Test
    fun `record for an unknown key is null and the catalogue starts empty`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        assertNull(catalogue.record("external_primary:404"))
        assertTrue(catalogue.allRecords().isEmpty())
        assertTrue(catalogue.recordsMap().isEmpty())
        assertTrue(catalogue.tombstonedKeys().isEmpty())
        assertNull(catalogue.scanCheckpoint("external_primary"))
    }

    @Test
    fun `recordsMap keys every record by its local media key`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val a = record(key = "external_primary:1")
        val b = record(key = "0123-4567:1")
        catalogue.upsertAll(listOf(a, b))

        val map = catalogue.recordsMap()
        assertEquals(setOf("external_primary:1", "0123-4567:1"), map.keys)
        assertEquals(a, map["external_primary:1"])
        assertEquals(b, map["0123-4567:1"])
    }

    @Test
    fun `tombstone removes live records and lists the keys`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1"), record(key = "external_primary:2")))

        catalogue.tombstone(listOf("external_primary:1"), atMillis = 10_000L)

        assertNull(catalogue.record("external_primary:1"))
        assertEquals(setOf("external_primary:1"), catalogue.tombstonedKeys())
        // The survivor is untouched --- a tombstone is per key, never a
        // catalogue wipe.
        assertEquals(1, catalogue.allRecords().size)
        assertNotNull(catalogue.record("external_primary:2"))
    }

    @Test
    fun `tombstoning an unknown key still records the tombstone`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()

        catalogue.tombstone(listOf("external_primary:999"), atMillis = 1L)

        assertEquals(setOf("external_primary:999"), catalogue.tombstonedKeys())
    }

    @Test
    fun `upsert after tombstone resurrects the record and clears the tombstone`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))
        catalogue.tombstone(listOf("external_primary:1"), atMillis = 10_000L)

        // The item reappears in an authoritative scan (restored from trash,
        // volume remounted) --- the upsert must bring it back to life.
        catalogue.upsertAll(listOf(record(key = "external_primary:1", sizeBytes = 5L)))

        assertTrue(catalogue.tombstonedKeys().isEmpty())
        assertEquals(5L, catalogue.record("external_primary:1")!!.sizeBytes)
        assertTrue(catalogue.recordsMap().containsKey("external_primary:1"))
    }

    @Test
    fun `scan checkpoint round-trips and a later scan overwrites it`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()

        catalogue.saveScanCheckpoint("external_primary", generationToken = "17", scannedAtMillis = 1_000L)
        assertEquals(
            ScanCheckpoint("external_primary", "17", 1_000L),
            catalogue.scanCheckpoint("external_primary"),
        )

        // The next authoritative scan replaces, never accumulates.
        catalogue.saveScanCheckpoint("external_primary", generationToken = "18", scannedAtMillis = 2_000L)
        assertEquals(
            ScanCheckpoint("external_primary", "18", 2_000L),
            catalogue.scanCheckpoint("external_primary"),
        )
    }

    @Test
    fun `scan checkpoint for one volume leaves other volumes untouched`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.saveScanCheckpoint("external_primary", generationToken = "1", scannedAtMillis = 1_000L)

        catalogue.saveScanCheckpoint("0123-4567", generationToken = null, scannedAtMillis = 1_500L)

        assertEquals("1", catalogue.scanCheckpoint("external_primary")!!.generationToken)
        assertNull(catalogue.scanCheckpoint("0123-4567")!!.generationToken)
    }

    @Test
    fun `purge drops only tombstones strictly older than the cutoff`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.tombstone(listOf("external_primary:1"), atMillis = 1_000L)
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 2_000L)
        catalogue.tombstone(listOf("external_primary:3"), atMillis = 3_000L)

        catalogue.purgeTombstonesOlderThan(2_000L)

        // 1_000 < cutoff is gone; 2_000 is AT the cutoff and survives (the
        // cutoff is the oldest age still kept); 3_000 survives.
        assertEquals(setOf("external_primary:2", "external_primary:3"), catalogue.tombstonedKeys())
    }

    @Test
    fun `purge never touches live records`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 1L)

        catalogue.purgeTombstonesOlderThan(Long.MAX_VALUE)

        assertTrue(catalogue.tombstonedKeys().isEmpty())
        assertFalse(catalogue.allRecords().isEmpty())
        assertNotNull(catalogue.record("external_primary:1"))
    }

    @Test
    fun `empty upsert and tombstone calls are no-ops`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))

        catalogue.upsertAll(emptyList())
        catalogue.tombstone(emptyList(), atMillis = 1L)

        assertEquals(1, catalogue.allRecords().size)
        assertTrue(catalogue.tombstonedKeys().isEmpty())
    }
}
