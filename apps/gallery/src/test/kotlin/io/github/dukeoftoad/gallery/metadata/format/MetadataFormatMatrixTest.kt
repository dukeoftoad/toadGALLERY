package io.github.dukeoftoad.gallery.metadata.format

import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning the declared parser capability matrix (Phase 4
 * decision #4): every row is asserted, so a future change to what we
 * claim to parse/write is a deliberate, visible edit --- never a silent
 * drift that turns "unsupported" into a promise.
 */
class MetadataFormatMatrixTest {

    @Test
    fun `jpeg is the reference format with editable exif`() {
        val jpeg = MetadataFormatMatrix.capabilitiesFor("image/jpeg")
        assertEquals(MetadataAvailability.PRESENT, jpeg.exif)
        assertEquals(MetadataAvailability.ABSENT, jpeg.containerMetadata)
        assertEquals(MetadataAvailability.UNSUPPORTED, jpeg.iptc)
        assertEquals(MetadataAvailability.UNSUPPORTED, jpeg.xmp)
        assertTrue(jpeg.editable)
    }

    @Test
    fun `heic and heif read exif but are not declared editable`() {
        for (mime in listOf("image/heic", "image/heif")) {
            val heif = MetadataFormatMatrix.capabilitiesFor(mime)
            assertEquals(mime, heif.mimeType)
            assertEquals(MetadataAvailability.PRESENT, heif.exif)
            assertEquals(MetadataAvailability.ABSENT, heif.containerMetadata)
            assertFalse(heif.editable)
            // The row models the verified SDK-28 floor: on the app's
            // minSdk 26/27 the androidx read yields empty attributes,
            // and the note must say so rather than claiming an
            // unconditional read.
            assertTrue(heif.notes!!.contains("API 28"))
        }
    }

    @Test
    fun `png is declared exif-absent with the eXIf chunk out of scope`() {
        val png = MetadataFormatMatrix.capabilitiesFor("image/png")
        assertEquals(MetadataAvailability.ABSENT, png.exif)
        assertEquals(MetadataAvailability.UNSUPPORTED, png.xmp)
        assertFalse(png.editable)
        assertTrue(png.notes!!.contains("eXIf"))
    }

    @Test
    fun `webp reads and writes exif this round while gif carries none`() {
        val webp = MetadataFormatMatrix.capabilitiesFor("image/webp")
        assertEquals(MetadataAvailability.PRESENT, webp.exif)
        // Drift guard: the r15 write spine (ExifWriteFormats --- the
        // androidx-writable set intersected with this read matrix) makes
        // WebP EDITABLE, and MediaEditCapabilityTest pins the same; this
        // row must agree with the gate, never re-declare a second truth.
        assertTrue(webp.editable)
        assertTrue(webp.notes!!.contains("r15 write spine"))
        val gif = MetadataFormatMatrix.capabilitiesFor("image/gif")
        assertEquals(MetadataAvailability.ABSENT, gif.exif)
        assertEquals(MetadataAvailability.ABSENT, gif.iptc)
        assertFalse(gif.editable)
    }

    @Test
    fun `avif exif is unsupported this round with the api-31 floor stated`() {
        val avif = MetadataFormatMatrix.capabilitiesFor("image/avif")
        assertEquals(MetadataAvailability.UNSUPPORTED, avif.exif)
        assertEquals(MetadataAvailability.ABSENT, avif.containerMetadata)
        assertEquals(MetadataAvailability.UNSUPPORTED, avif.xmp)
        assertFalse(avif.editable)
        // Drift guard: the note must not claim androidx "does not parse"
        // AVIF --- the pinned 1.4.1 reads it from API 31 (empty below),
        // exactly what the format-support catalogue's
        // READ_BY_ANDROIDX_FROM_API_31 documents; the row's honest claim
        // is that THIS minSdk-26 pinned reader keeps the format
        // UNSUPPORTED.
        assertTrue(avif.notes!!.contains("API 31"))
        assertFalse(avif.notes!!.contains("does not parse"))
    }

    @Test
    fun `raw dng is unsupported this round`() {
        val dng = MetadataFormatMatrix.capabilitiesFor("image/x-adobe-dng")
        assertEquals(MetadataAvailability.UNSUPPORTED, dng.exif)
        assertEquals(MetadataAvailability.UNSUPPORTED, dng.xmp)
        assertFalse(dng.editable)
    }

    @Test
    fun `video containers declare container metadata present`() {
        for (mime in listOf("video/mp4", "video/quicktime", "video/x-matroska", "video/3gpp")) {
            val video = MetadataFormatMatrix.capabilitiesFor(mime)
            assertEquals(mime, video.mimeType)
            assertEquals(MetadataAvailability.PRESENT, video.containerMetadata)
            assertEquals(MetadataAvailability.UNSUPPORTED, video.exif)
            assertEquals(MetadataAvailability.ABSENT, video.iptc)
            assertEquals(MetadataAvailability.UNSUPPORTED, video.xmp)
            assertFalse(video.editable)
        }
    }

    @Test
    fun `unknown mime types get a fully unsupported row`() {
        val unknown = MetadataFormatMatrix.capabilitiesFor("application/x-foo")
        assertEquals("application/x-foo", unknown.mimeType)
        assertEquals(MetadataAvailability.UNSUPPORTED, unknown.exif)
        assertEquals(MetadataAvailability.UNSUPPORTED, unknown.containerMetadata)
        assertEquals(MetadataAvailability.UNSUPPORTED, unknown.iptc)
        assertEquals(MetadataAvailability.UNSUPPORTED, unknown.xmp)
        assertFalse(unknown.editable)
    }

    @Test
    fun `lookups normalize case and parameter suffixes`() {
        val upper = MetadataFormatMatrix.capabilitiesFor("IMAGE/JPEG")
        assertEquals(MetadataAvailability.PRESENT, upper.exif)
        assertTrue(upper.editable)
        val parameterized = MetadataFormatMatrix.capabilitiesFor("video/mp4; charset=binary")
        assertEquals("video/mp4", parameterized.mimeType)
        assertEquals(MetadataAvailability.PRESENT, parameterized.containerMetadata)
    }

    @Test
    fun `pinned mime types match the declared rows`() {
        // The pinned set is exactly the formats this round claims; adding
        // a row without a deliberate test change will fail this pin.
        assertEquals(
            setOf(
                "image/jpeg",
                "image/heic",
                "image/heif",
                "image/png",
                "image/webp",
                "image/gif",
                "image/avif",
                "image/x-adobe-dng",
                "video/mp4",
                "video/quicktime",
                "video/x-matroska",
                "video/3gpp",
            ),
            MetadataFormatMatrix.pinnedMimeTypes,
        )
    }
}
