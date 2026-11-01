package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MediaScanRow.toRecord] is the bridge from a provider scan into the
 * catalogue cache: it must map every observed field verbatim and start the
 * in-app state at its zero point. Pure JUnit 4 / JVM (ADR-0016).
 */
class MediaScanRowTest {

    @Test
    fun `toRecord maps every observed field verbatim`() {
        val row = MediaScanRow(
            localMediaKey = "0123-4567:77",
            canonicalUri = "content://media/external/0123-4567/video/77",
            mimeType = "video/mp4",
            sizeBytes = 4_096L,
            dateModifiedMillis = 2_000L,
            dateAddedMillis = 1_000L,
            generationToken = "31",
        )

        val record = row.toRecord()

        assertEquals("0123-4567:77", record.localMediaKey)
        assertEquals("content://media/external/0123-4567/video/77", record.canonicalUri)
        assertEquals("video/mp4", record.mimeType)
        assertEquals(4_096L, record.sizeBytes)
        assertEquals(2_000L, record.dateModifiedMillis)
        assertEquals(1_000L, record.dateAddedMillis)
        assertEquals("31", record.generationToken)
    }

    @Test
    fun `toRecord starts in-app state at its zero point`() {
        // A first sighting has no metadata snapshot (EMPTY), no in-app
        // metadata writes (0) and no scan stamp yet (0) --- "now" is a
        // caller decision, never a hidden clock read inside a data class.
        val record = MediaScanRow(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            dateModifiedMillis = 1L,
            dateAddedMillis = 1L,
        ).toRecord()

        assertEquals(MetadataCacheState.EMPTY, record.metadataCacheState)
        assertEquals(0, record.metadataWriteRevision)
        assertEquals(0L, record.lastSeenAtMillis)
    }

    @Test
    fun `generation token defaults to null for pre-30 scans`() {
        val row = MediaScanRow(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            dateModifiedMillis = 1L,
            dateAddedMillis = 1L,
        )
        assertNull(row.generationToken)
        assertNull(row.toRecord().generationToken)
    }
}
