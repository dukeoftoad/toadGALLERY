package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaScanRow.toRecord] is the bridge from a provider scan into the
 * catalogue cache: it must map every observed field verbatim and start the
 * in-app state at its zero point. Pure JUnit 4 / JVM (ADR-0016). The r13
 * additions pin the display/shape ride-alongs --- the fields the timeline's
 * cells render from --- which must travel verbatim while NEVER entering the
 * reconciler's revision comparison (a favorite toggle or dimension refresh
 * must not cost an embedding recompute).
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
    fun `per-row generation stamp defaults to null for pre-30 scans`() {
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

    // --- display/shape ride-alongs (r13 round) -------------------------------

    @Test
    fun `toRecord carries the display and shape ride-alongs verbatim`() {
        // A record must mirror its last-seen provider row well enough to
        // render a timeline cell --- these five fields are the whole point
        // of carrying them through the scan pipeline.
        val row = MediaScanRow(
            localMediaKey = "external_primary:21",
            canonicalUri = "content://media/external/video/21",
            mimeType = "video/mp4",
            sizeBytes = 8_192L,
            dateModifiedMillis = 2_000L,
            dateAddedMillis = 1_000L,
            displayName = "VID_20240101_120000.mp4",
            width = 1920,
            height = 1080,
            durationMs = 12_345L,
            isFavorite = true,
        )

        val record = row.toRecord()

        assertEquals("VID_20240101_120000.mp4", record.displayName)
        assertEquals(1920, record.width)
        assertEquals(1080, record.height)
        assertEquals(12_345L, record.durationMs)
        assertTrue(record.isFavorite)
    }

    @Test
    fun `display and shape ride-alongs default to the unknown zero-point`() {
        // Same convention as MediaItem: null name / 0 dimensions /
        // 0 duration / false favorite all mean "unknown" or "not
        // projected", which is what the pre-Q walks and the below-Q image
        // leg legitimately produce.
        val record = MediaScanRow(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            dateModifiedMillis = 1L,
            dateAddedMillis = 1L,
        ).toRecord()

        assertNull(record.displayName)
        assertEquals(0, record.width)
        assertEquals(0, record.height)
        assertEquals(0L, record.durationMs)
        assertFalse(record.isFavorite)
    }
}
