package io.github.dukeoftoad.gallery.metadata.format

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tested sample corpus for the Phase 4 format-support audit
 * (`docs/phase-4/format-support-audit.md`, roadmap deliverable "format-
 * support matrix and tested sample corpus"): hex-literal byte fixtures
 * pinning EVERY verdict [FormatSniffer] can return.
 *
 * Corpus doctrine (mirrored in the audit doc): this corpus is
 * SIGNATURE-LEVEL. It proves what leading bytes identify --- valid magics,
 * subtype disambiguation, and the honest UNKNOWN for truncated or
 * unrecognized input. It deliberately does NOT claim decode-level truth
 * ("does THIS device decode THIS HEIC?"): that stays the on-device release
 * gate (ADR-0016 --- CI compiles and runs the JVM suite; physical devices
 * validate runtime behaviour).
 *
 * Fixtures are hand-built minimal headers, not real camera files: the
 * sniffer only ever reads a bounded prefix, so the corpus pins exactly the
 * bytes its decision tree consumes (plus filler where a real file would
 * continue). Every fixture's byte layout is commented so a failing test
 * reads as "this signature rule changed", not "some blob broke".
 */
class FormatSnifferTest {

    // ---- JPEG ----------------------------------------------------------

    @Test
    fun `jpeg prefixes with jfif and exif first markers are recognized`() {
        // FF D8 FF E0 (APP0/JFIF) and FF D8 FF E1 (APP1/EXIF) --- both
        // carry the SOI-plus-marker prefix the sniffer proves JPEG by.
        val jfif = hex(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10) + ascii("JFIF") + hex(0x00, 0x01)
        assertEquals(SniffedFormat.JPEG, FormatSniffer.sniff(jfif))
        val exif = hex(0xFF, 0xD8, 0xFF, 0xE1, 0x1C, 0x45) + ascii("Exif") + hex(0x00, 0x00)
        assertEquals(SniffedFormat.JPEG, FormatSniffer.sniff(exif))
    }

    @Test
    fun `jpeg truncated below its three-byte magic stays unknown`() {
        // FF D8 alone proves nothing --- the third byte is part of the
        // proof (SOI is always followed by 0xFF), so two bytes are UNKNOWN.
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(hex(0xFF, 0xD8)))
    }

    // ---- PNG -----------------------------------------------------------

    @Test
    fun `png signature is recognized and its truncation stays unknown`() {
        val png = hex(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ascii("IHDR")
        assertEquals(SniffedFormat.PNG, FormatSniffer.sniff(png))
        // Seven of the eight signature bytes prove nothing.
        val truncated = hex(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(truncated))
    }

    // ---- GIF -----------------------------------------------------------

    @Test
    fun `gif87a and gif89a are recognized`() {
        for (version in listOf("GIF87a", "GIF89a")) {
            val gif = ascii(version) + hex(0x01, 0x00, 0x01, 0x00)
            assertEquals(SniffedFormat.GIF, FormatSniffer.sniff(gif))
        }
    }

    @Test
    fun `gif with an invalid version stays unknown`() {
        // Only 87a/89a exist; "GIF88a" is not a signature the sniffer may
        // accept, and a four-byte "GIF8" prefix proves even less.
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ascii("GIF88a")))
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ascii("GIF8")))
    }

    // ---- RIFF containers: WebP, AVI -------------------------------------

    @Test
    fun `webp subtypes vp8 vp8l and vp8x are distinguished`() {
        for (chunk in listOf("VP8 ", "VP8L", "VP8X")) {
            val webp = ascii("RIFF") + hex(0x24, 0x00, 0x00, 0x00) + ascii("WEBP") + ascii(chunk) +
                hex(0x00, 0x00, 0x00, 0x00)
            val expected = when (chunk) {
                "VP8 " -> SniffedFormat.WEBP_VP8
                "VP8L" -> SniffedFormat.WEBP_VP8L
                else -> SniffedFormat.WEBP_VP8X
            }
            assertEquals(expected, FormatSniffer.sniff(webp))
        }
    }

    @Test
    fun `webp truncated before its chunk type stays unknown`() {
        // "RIFF" + size + "WEBP" is 12 bytes: the container is provably
        // WebP, but the subtype would be a guess.
        val truncated = ascii("RIFF") + hex(0x24, 0x00, 0x00, 0x00) + ascii("WEBP")
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(truncated))
    }

    @Test
    fun `webp with an unrecognized first chunk stays unknown`() {
        val unknownChunk = ascii("RIFF") + hex(0x24, 0x00, 0x00, 0x00) + ascii("WEBP") +
            ascii("ABCD") + hex(0x00, 0x00, 0x00, 0x00)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(unknownChunk))
    }

    @Test
    fun `riff form types the sniffer does not handle stay unknown`() {
        // WAVE audio: a RIFF file, but not one this round classifies.
        val wave = ascii("RIFF") + hex(0x24, 0x00, 0x00, 0x00) + ascii("WAVE") + ascii("fmt ")
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(wave))
    }

    @Test
    fun `avi is recognized by its riff form type`() {
        val avi = ascii("RIFF") + hex(0x24, 0x00, 0x00, 0x00) + ascii("AVI ") + ascii("LIST")
        assertEquals(SniffedFormat.AVI, FormatSniffer.sniff(avi))
    }

    // ---- ISO-BMFF (ftyp brands) -----------------------------------------

    @Test
    fun `heif still-image brands are recognized`() {
        for (brand in listOf("heic", "heix", "heim")) {
            // Minimal 16-byte ftyp: size, "ftyp", major brand, minor
            // version; no compatible brands needed.
            val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii(brand) +
                hex(0x00, 0x00, 0x00, 0x00)
            assertEquals(SniffedFormat.HEIF, FormatSniffer.sniff(box))
        }
    }

    @Test
    fun `mif1 major brand with a heic compatible brand classifies as heif`() {
        // Generic HEIF container brand first, image brand in the list ---
        // the brand scan exists exactly for this case.
        val box = hex(0x00, 0x00, 0x00, 0x18) + ascii("ftyp") + ascii("mif1") +
            hex(0x00, 0x00, 0x00, 0x00) + ascii("heic")
        assertEquals(SniffedFormat.HEIF, FormatSniffer.sniff(box))
    }

    @Test
    fun `heif sequence brands classify as heif`() {
        // msf1/hevc/hevx are the spec's image-sequence brands; the enum
        // documents the deliberate still-image classification.
        for (brand in listOf("msf1", "hevc")) {
            val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii(brand) +
                hex(0x00, 0x00, 0x00, 0x00)
            assertEquals(SniffedFormat.HEIF, FormatSniffer.sniff(box))
        }
    }

    @Test
    fun `avif still and sequence brands are recognized`() {
        for (brand in listOf("avif", "avis")) {
            val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii(brand) +
                hex(0x00, 0x00, 0x00, 0x00)
            assertEquals(SniffedFormat.AVIF, FormatSniffer.sniff(box))
        }
    }

    @Test
    fun `avif brands win over heif brands when both are listed`() {
        // An AVIF that also lists mif1/heic compatible brands is AVIF ---
        // the AVIF check runs first, mirroring androidx's own sniff order.
        val box = hex(0x00, 0x00, 0x00, 0x1C) + ascii("ftyp") + ascii("mif1") +
            hex(0x00, 0x00, 0x00, 0x00) + ascii("heic") + ascii("avif")
        assertEquals(SniffedFormat.AVIF, FormatSniffer.sniff(box))
    }

    @Test
    fun `mp4 family brands are recognized`() {
        // isom with a realistic compatible-brand list...
        val isom = hex(0x00, 0x00, 0x00, 0x18) + ascii("ftyp") + ascii("isom") +
            hex(0x00, 0x00, 0x02, 0x00) + ascii("isom") + ascii("iso2")
        assertEquals(SniffedFormat.MP4_VIDEO, FormatSniffer.sniff(isom))
        // ...plus the other common majors as minimal boxes.
        for (brand in listOf("mp42", "avc1", "dash")) {
            val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii(brand) +
                hex(0x00, 0x00, 0x00, 0x00)
            assertEquals(SniffedFormat.MP4_VIDEO, FormatSniffer.sniff(box))
        }
    }

    @Test
    fun `quicktime qt major brand is recognized`() {
        // "qt  " --- two trailing spaces are part of the brand.
        val box = hex(0x00, 0x00, 0x00, 0x14) + ascii("ftyp") + ascii("qt  ") +
            hex(0x00, 0x02, 0x00, 0x00) + ascii("qt  ")
        assertEquals(SniffedFormat.QUICKTIME_MOV, FormatSniffer.sniff(box))
    }

    @Test
    fun `a quicktime camcorder file with an hevc compatible brand is a mov not a heif`() {
        // THE audit doc's flagged case: a QuickTime/MP4 HEVC camcorder
        // file can carry the image-sequence brand `hevc` in its
        // COMPATIBLE list. The MAJOR brand declares the family (qt ->
        // QUICKTIME_MOV) and the compatible-brand scan classifies
        // images only --- classifying on the mixed list used to hand
        // back the still-image verdict (playback = NOT_A_VIDEO_CONTAINER)
        // for a playable video.
        val box = hex(0x00, 0x00, 0x00, 0x18) + ascii("ftyp") + ascii("qt  ") +
            hex(0x00, 0x02, 0x00, 0x00) + ascii("qt  ") + ascii("hevc")
        assertEquals(SniffedFormat.QUICKTIME_MOV, FormatSniffer.sniff(box))
    }

    @Test
    fun `an mp4 with a 3gp compatible brand stays the mp4 verdict`() {
        // Same rule, the MP4 side: the major brand (isom) declares the
        // video family, and a `3gp*` COMPATIBLE brand --- a
        // compatibility declaration, not a primary family claim --- must
        // not override it (the pre-fix scan order answered THREEGP here).
        val box = hex(0x00, 0x00, 0x00, 0x18) + ascii("ftyp") + ascii("isom") +
            hex(0x00, 0x00, 0x02, 0x00) + ascii("isom") + ascii("3gp5")
        assertEquals(SniffedFormat.MP4_VIDEO, FormatSniffer.sniff(box))
    }

    @Test
    fun `3gpp and 3gpp2 brand prefixes are recognized`() {
        for (brand in listOf("3gp5", "3g2a")) {
            val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii(brand) +
                hex(0x00, 0x00, 0x00, 0x00)
            assertEquals(SniffedFormat.THREEGP, FormatSniffer.sniff(box))
        }
    }

    @Test
    fun `ftyp with an unlisted brand stays unknown`() {
        // M4A audio: a legal ISO-BMFF brand this sniffer makes no claim
        // about --- honest UNKNOWN, never a forced family.
        val box = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp") + ascii("M4A ") +
            hex(0x00, 0x00, 0x00, 0x00)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(box))
    }

    @Test
    fun `ftyp truncated below its sixteen-byte header stays unknown`() {
        // Size + "ftyp" alone (8 bytes) cannot carry a major brand.
        val truncated = hex(0x00, 0x00, 0x00, 0x10) + ascii("ftyp")
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(truncated))
    }

    // ---- Matroska / WebM -------------------------------------------------

    @Test
    fun `the ebml magic identifies the matroska-webm family`() {
        // 1A 45 DF A3 opens both Matroska and WebM; separating them needs
        // the DocType element, which this round deliberately does not
        // parse (family-level verdict by honesty).
        val ebml = hex(0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42, 0x82, 0x88)
        assertEquals(SniffedFormat.MATROSKA_WEBM, FormatSniffer.sniff(ebml))
    }

    // ---- TIFF family ------------------------------------------------------

    @Test
    fun `tiff magic is recognized in both byte orders`() {
        // Little-endian "II*\0" and big-endian "MM\0*" with an IFD0
        // offset that points past this minimal buffer: the magic proves
        // TIFF, nothing deeper.
        val littleEndian = hex(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(littleEndian))
        val bigEndian = hex(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(bigEndian))
    }

    @Test
    fun `tiff magic truncated to four bytes still proves tiff, three do not`() {
        assertEquals(
            SniffedFormat.TIFF,
            FormatSniffer.sniff(hex(0x49, 0x49, 0x2A, 0x00)),
        )
        // "II*" is not the magic --- the 0x00 terminator is part of it.
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(hex(0x49, 0x49, 0x2A)))
    }

    @Test
    fun `the canon cr2 marker is recognized`() {
        // TIFF magic, an IFD0 offset, then the documented Canon marker
        // "43 52 02 00" ("CR", 2, 0) at bytes 8..11.
        val cr2 = hex(0x49, 0x49, 0x2A, 0x00, 0x10, 0x00, 0x00, 0x00, 0x43, 0x52, 0x02, 0x00)
        assertEquals(SniffedFormat.TIFF_CR2, FormatSniffer.sniff(cr2))
    }

    @Test
    fun `the dngversion tag in ifd0 proves dng`() {
        // II*\0 | IFD0 at 8 | 1 entry | tag 0xC612 (DNGVersion), type
        // BYTE, count 4, value inline (1, 4, 0, 0 = DNG 1.4).
        val dng = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0xC6, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x04, 0x00, 0x00,
        )
        assertEquals(SniffedFormat.TIFF_DNG, FormatSniffer.sniff(dng))
    }

    @Test
    fun `dngversion outranks the nikon make tag`() {
        // A DNG converted from a Nikon NEF keeps Make = "NIKON..." AND
        // carries DNGVersion: the spec-required tag wins.
        val dngFromNikon = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x02, 0x00,
            // Entry 0: Make (0x010F), ASCII, 6 bytes, at offset 34.
            0x0F, 0x01, 0x02, 0x00, 0x06, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00,
            // Entry 1: DNGVersion (0xC612), BYTE, 4 bytes, inline.
            0x12, 0xC6, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x04, 0x00, 0x00,
        ) + ascii("NIKON") + hex(0x00)
        assertEquals(SniffedFormat.TIFF_DNG, FormatSniffer.sniff(dngFromNikon))
    }

    @Test
    fun `a nikon make tag flags the nef candidate`() {
        // II*\0 | IFD0 at 8 | 1 entry | Make, ASCII, 6 bytes, at offset
        // 22 -> "NIKON\0". Candidate, not proof: the enum KDoc says so.
        val nef = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x0F, 0x01, 0x02, 0x00, 0x06, 0x00, 0x00, 0x00, 0x16, 0x00, 0x00, 0x00,
        ) + ascii("NIKON") + hex(0x00)
        assertEquals(SniffedFormat.TIFF_RAW_NIKON, FormatSniffer.sniff(nef))
    }

    @Test
    fun `a sony make tag flags the arw candidate`() {
        // Same shape, but "SONY" fits the 4-byte inline value field.
        val arw = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x0F, 0x01, 0x02, 0x00, 0x04, 0x00, 0x00, 0x00,
        ) + ascii("SONY")
        assertEquals(SniffedFormat.TIFF_RAW_SONY, FormatSniffer.sniff(arw))
    }

    @Test
    fun `a big-endian nikon make tag flags the nef candidate`() {
        // The IFD walk honors the MM byte order end to end.
        val nef = hex(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x0F, 0x00, 0x02, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x16,
        ) + ascii("NIKON") + hex(0x00)
        assertEquals(SniffedFormat.TIFF_RAW_NIKON, FormatSniffer.sniff(nef))
    }

    @Test
    fun `other makers tiffs stay plain tiff`() {
        // Canon Make (as plain TIFF files from Canon cameras carry): the
        // sniffer refines only Nikon/Sony by maker, so this stays TIFF.
        val canon = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x0F, 0x01, 0x02, 0x00, 0x06, 0x00, 0x00, 0x00, 0x16, 0x00, 0x00, 0x00,
        ) + ascii("Canon") + hex(0x00)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(canon))
    }

    @Test
    fun `corrupt tiff structures degrade to plain tiff`() {
        // IFD0 offset far past the buffer...
        val hugeOffset = hex(0x49, 0x49, 0x2A, 0x00, 0xFF, 0xFF, 0xFF, 0x0F)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(hugeOffset))
        // ...entry count of zero (nothing to walk)...
        val zeroEntries = hex(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(zeroEntries))
        // ...and an absurd entry count (257 > the 256 cap) --- the magic
        // still proves TIFF; no deeper verdict is invented.
        val absurdCount = hex(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x01)
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(absurdCount))
    }

    @Test
    fun `a make value pointing out of bounds degrades to plain tiff`() {
        // Make claims 6 bytes at offset 256 in a 22-byte buffer: the
        // string read fails its bounds check and the weaker verdict wins.
        val badMake = hex(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x0F, 0x01, 0x02, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
        )
        assertEquals(SniffedFormat.TIFF, FormatSniffer.sniff(badMake))
    }

    // ---- The honest negative ----------------------------------------------

    @Test
    fun `empty and unrecognized inputs stay unknown`() {
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ByteArray(0)))
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ascii("not a signature")))
    }

    @Test
    fun `deliberately unsniffed formats stay unknown`() {
        // The audit doc's documented gaps: BMP ("BM"), Olympus ORF
        // ("IIRO" --- not the TIFF magic), Fujifilm RAF ("FUJIFILMCCD-RAW").
        // Each lands honestly in UNKNOWN rather than being guessed.
        val bmp = ascii("BM") + hex(0x90, 0x00)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(bmp))
        val orf = ascii("IIRO") + hex(0x08, 0x00)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(orf))
        val raf = ascii("FUJIFILMCCD-RAW ") + hex(0x12, 0x00, 0x00, 0x00)
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(raf))
    }

    // ---- Stream overload ----------------------------------------------------

    @Test
    fun `stream sniffing matches byte-array sniffing`() {
        val jpeg = hex(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10) + ascii("JFIF")
        assertEquals(SniffedFormat.JPEG, FormatSniffer.sniff(ByteArrayInputStream(jpeg)))
        // An empty stream proves nothing, same as an empty array.
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a stream failing mid-read never throws - the collected prefix is the whole proof`() {
        // The byte overload's never-throws doctrine extends to the stream
        // overload: an IOException mid-read ends the read early ---
        // exactly like EOF --- and the verdict comes from the bytes
        // collected before the failure (a fully-present signature still
        // proves its family; nothing collected still proves nothing).
        // The stream's owner surfaces provider failures on its own reads;
        // this overload is a classification call, not an error channel.
        val jpegThenFailure = object : InputStream() {
            private var served = false

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!served) {
                    served = true
                    hex(0xFF, 0xD8, 0xFF).copyInto(b, off)
                    return 3
                }
                throw IOException("provider stream died")
            }

            override fun read(): Int = throw IOException("provider stream died")
        }
        assertEquals(SniffedFormat.JPEG, FormatSniffer.sniff(jpegThenFailure))
        val alwaysFailing = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw IOException("provider stream died")

            override fun read(): Int = throw IOException("provider stream died")
        }
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(alwaysFailing))
    }

    // ---- Fixture helpers ------------------------------------------------------

    /**
     * Bytes from unsigned byte literals (`0xFF`-style values are coerced
     * through [Int] so fixtures read as the hex dumps in the audit doc).
     */
    private fun hex(vararg values: Int): ByteArray = ByteArray(values.size) { index ->
        values[index].toByte()
    }

    /** ASCII fixture text as bytes (fourccs, magics, make strings). */
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
