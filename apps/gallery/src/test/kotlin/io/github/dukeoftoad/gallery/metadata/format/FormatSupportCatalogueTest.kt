package io.github.dukeoftoad.gallery.metadata.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning the format-support catalogue (Phase 4 format audit):
 * every [SniffedFormat] value has exactly one declared [FormatSupport]
 * row, and the platform/library verdicts on those rows match the audit
 * doc (`docs/phase-4/format-support-audit.md`), where every claim carries
 * its citation. A future change to a verdict is therefore a deliberate,
 * visible edit here --- never a silent drift of the catalogue away from
 * the documented truth.
 *
 * These tests pin DECLARATIONS, not device behaviour: the per-device
 * questions (does THIS phone decode HEIF? which codecs does it carry?)
 * are the on-device release gate per the repo's established doctrine
 * (ADR-0016).
 */
class FormatSupportCatalogueTest {

    @Test
    fun `every sniffed format has exactly one catalogue row`() {
        // Adding a SniffedFormat value without a row must fail here, not
        // surface later as a silent UNKNOWN fallback.
        assertEquals(
            SniffedFormat.entries.toSet(),
            FormatSupportCatalogue.cataloguedFormats,
        )
        assertEquals(
            SniffedFormat.entries.size,
            FormatSupportCatalogue.all.size,
        )
        assertEquals(
            FormatSupportCatalogue.all.size,
            FormatSupportCatalogue.all.map { it.format }.toSet().size,
        )
    }

    @Test
    fun `supportFor answers every family and never throws`() {
        for (format in SniffedFormat.entries) {
            assertEquals(format, FormatSupportCatalogue.supportFor(format).format)
        }
    }

    @Test
    fun `jpeg is the fully supported reference row`() {
        val jpeg = FormatSupportCatalogue.supportFor(SniffedFormat.JPEG)
        assertEquals(StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES, jpeg.stillDecode)
        assertEquals(AnimatedSupport.STATIC_ONLY, jpeg.animated)
        assertEquals(ExifReadSupport.READ_BY_ANDROIDX, jpeg.exifRead)
        assertEquals(ExifWriteSupport.WRITABLE_BY_ANDROIDX, jpeg.exifWrite)
        assertEquals(PlaybackSupport.NOT_A_VIDEO_CONTAINER, jpeg.playback)
    }

    @Test
    fun `png decodes everywhere and is androidx-writable`() {
        val png = FormatSupportCatalogue.supportFor(SniffedFormat.PNG)
        assertEquals(StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES, png.stillDecode)
        assertEquals(AnimatedSupport.STATIC_ONLY, png.animated)
        assertEquals(ExifReadSupport.READ_BY_ANDROIDX, png.exifRead)
        assertEquals(ExifWriteSupport.WRITABLE_BY_ANDROIDX, png.exifWrite)
    }

    @Test
    fun `gif is the one animated-wired family with no exif carrier`() {
        val gif = FormatSupportCatalogue.supportFor(SniffedFormat.GIF)
        assertEquals(StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES, gif.stillDecode)
        assertEquals(AnimatedSupport.ANIMATED_WIRED_IN_VIEWER, gif.animated)
        assertEquals(ExifReadSupport.NO_STANDARD_EXIF_CARRIER, gif.exifRead)
        assertEquals(ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX, gif.exifWrite)
    }

    @Test
    fun `all three webp subtypes decode everywhere and carry androidx exif`() {
        for (format in listOf(
            SniffedFormat.WEBP_VP8,
            SniffedFormat.WEBP_VP8L,
            SniffedFormat.WEBP_VP8X,
        )) {
            val webp = FormatSupportCatalogue.supportFor(format)
            assertEquals(StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES, webp.stillDecode)
            assertEquals(ExifReadSupport.READ_BY_ANDROIDX, webp.exifRead)
            assertEquals(ExifWriteSupport.WRITABLE_BY_ANDROIDX, webp.exifWrite)
            assertEquals(PlaybackSupport.NOT_A_VIDEO_CONTAINER, webp.playback)
        }
        // Only the extended subtype has an animated variant at all.
        assertEquals(
            AnimatedSupport.STATIC_ONLY,
            FormatSupportCatalogue.supportFor(SniffedFormat.WEBP_VP8).animated,
        )
        assertEquals(
            AnimatedSupport.STATIC_ONLY,
            FormatSupportCatalogue.supportFor(SniffedFormat.WEBP_VP8L).animated,
        )
        assertEquals(
            AnimatedSupport.ANIMATION_NOT_WIRED,
            FormatSupportCatalogue.supportFor(SniffedFormat.WEBP_VP8X).animated,
        )
    }

    @Test
    fun `heif is device-optional from api 28 with api-gated exif read`() {
        val heif = FormatSupportCatalogue.supportFor(SniffedFormat.HEIF)
        assertEquals(StillDecodeSupport.DEVICE_OPTIONAL_FROM_API_28, heif.stillDecode)
        assertEquals(AnimatedSupport.ANIMATION_NOT_WIRED, heif.animated)
        assertEquals(ExifReadSupport.READ_BY_ANDROIDX_FROM_API_28, heif.exifRead)
        assertEquals(ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX, heif.exifWrite)
    }

    @Test
    fun `avif is eight-bit from api 31 with api-gated exif read`() {
        val avif = FormatSupportCatalogue.supportFor(SniffedFormat.AVIF)
        assertEquals(StillDecodeSupport.EIGHT_BIT_FROM_API_31, avif.stillDecode)
        assertEquals(AnimatedSupport.ANIMATION_NOT_WIRED, avif.animated)
        assertEquals(ExifReadSupport.READ_BY_ANDROIDX_FROM_API_31, avif.exifRead)
        assertEquals(ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX, avif.exifWrite)
    }

    @Test
    fun `raw rows have no platform decode but androidx reads their exif`() {
        for (format in listOf(
            SniffedFormat.TIFF_CR2,
            SniffedFormat.TIFF_DNG,
            SniffedFormat.TIFF_RAW_NIKON,
            SniffedFormat.TIFF_RAW_SONY,
        )) {
            val raw = FormatSupportCatalogue.supportFor(format)
            assertEquals(StillDecodeSupport.NOT_DECODED_BY_PLATFORM, raw.stillDecode)
            assertEquals(AnimatedSupport.STATIC_ONLY, raw.animated)
            assertEquals(ExifReadSupport.READ_BY_ANDROIDX, raw.exifRead)
            assertEquals(ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX, raw.exifWrite)
        }
    }

    @Test
    fun `plain tiff is neither decoded nor exif-read`() {
        val tiff = FormatSupportCatalogue.supportFor(SniffedFormat.TIFF)
        assertEquals(StillDecodeSupport.NOT_DECODED_BY_PLATFORM, tiff.stillDecode)
        assertEquals(ExifReadSupport.NOT_READ_BY_ANDROIDX, tiff.exifRead)
        assertEquals(ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX, tiff.exifWrite)
    }

    @Test
    fun `video containers carry container-side verdicts`() {
        // Containers listed by media3's table.
        for (format in listOf(SniffedFormat.MP4_VIDEO, SniffedFormat.MATROSKA_WEBM)) {
            val video = FormatSupportCatalogue.supportFor(format)
            assertEquals(StillDecodeSupport.NOT_A_STILL_IMAGE_FORMAT, video.stillDecode)
            assertEquals(AnimatedSupport.NOT_AN_IMAGE_FORMAT, video.animated)
            assertEquals(ExifReadSupport.NO_STANDARD_EXIF_CARRIER, video.exifRead)
            assertEquals(PlaybackSupport.LISTED_BY_MEDIA3, video.playback)
        }
        // ISO-BMFF relatives without a dedicated media3 container row.
        for (format in listOf(SniffedFormat.QUICKTIME_MOV, SniffedFormat.THREEGP)) {
            assertEquals(
                PlaybackSupport.DEMUXES_IN_PRACTICE_NOT_LISTED,
                FormatSupportCatalogue.supportFor(format).playback,
            )
        }
        // The classic "unusual video format": recognized, not playable.
        val avi = FormatSupportCatalogue.supportFor(SniffedFormat.AVI)
        assertEquals(PlaybackSupport.NOT_PLAYABLE_BY_MEDIA3, avi.playback)
        assertEquals(StillDecodeSupport.NOT_DECODED_BY_PLATFORM, avi.stillDecode)
    }

    @Test
    fun `the unknown row claims nothing`() {
        val unknown = FormatSupportCatalogue.supportFor(SniffedFormat.UNKNOWN)
        assertEquals(StillDecodeSupport.NOT_CLAIMED, unknown.stillDecode)
        assertEquals(AnimatedSupport.NOT_CLAIMED, unknown.animated)
        assertEquals(ExifReadSupport.NOT_CLAIMED, unknown.exifRead)
        assertEquals(ExifWriteSupport.NOT_CLAIMED, unknown.exifWrite)
        assertEquals(PlaybackSupport.NOT_CLAIMED, unknown.playback)
    }

    @Test
    fun `every row states its sniff signature and honest notes`() {
        for (row in FormatSupportCatalogue.all) {
            assertTrue(row.signature.isNotBlank())
            // Notes are the honest-why; the audit doc carries the full
            // citations for them.
            assertTrue(row.notes.isNotBlank())
        }
    }
}
