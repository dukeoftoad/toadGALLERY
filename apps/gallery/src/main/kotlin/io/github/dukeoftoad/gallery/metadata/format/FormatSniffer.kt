package io.github.dukeoftoad.gallery.metadata.format

import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * The format family identified from a byte stream's LEADING bytes (Phase 4
 * format-support audit, `docs/phase-4/format-support-audit.md`).
 *
 * Each constant documents what its detection actually PROVES, because the
 * audit's honesty rule is "distinguish what the magic bytes prove from what
 * requires deeper parsing": `TIFF_DNG` is proven by a spec-required tag
 * inside the file, while [TIFF_RAW_NIKON] proves only the camera MAKER ---
 * the RAW-ness is a naming convention, and the KDoc says so. Callers that
 * need a stronger statement must parse deeper; this enum never guesses.
 */
enum class SniffedFormat {
    /** JPEG: proven by the `FF D8 FF` SOI-plus-marker prefix. */
    JPEG,

    /** PNG: proven by the 8-byte `89 50 4E 47 0D 0A 1A 0A` signature. */
    PNG,

    /** GIF87a/GIF89a: proven by the 6-byte versioned GIF signature. */
    GIF,

    /** Lossy WebP: `RIFF....WEBP` with a `VP8 ` first chunk. */
    WEBP_VP8,

    /** Lossless WebP: `RIFF....WEBP` with a `VP8L` first chunk. Never animated. */
    WEBP_VP8L,

    /** Extended WebP: `RIFF....WEBP` with a `VP8X` first chunk (may be animated). */
    WEBP_VP8X,

    /**
     * HEIF/HEIC still-image family: an `ftyp` box carrying one of the
     * ISO/IEC 23008-12 image brands (heic, heix, heim, heis, hevc, hevx,
     * mif1, msf1). `hevc`/`hevx` are the spec's image-SEQUENCE brands and
     * are deliberately classified as HEIF (the image reading) --- when no
     * VIDEO major brand declared the file first: some HEVC camcorder
     * files carry `hevc` as a COMPATIBLE brand behind a `qt  `/`isom`
     * major, and those classify by their major brand (the exact case the
     * audit doc flags; see [FormatSniffer]).
     */
    HEIF,

    /**
     * AVIF: an `ftyp` box carrying the `avif` or `avis` brand (checked
     * before the HEIF set within the image scan, mirroring androidx's own
     * sniff order).
     */
    AVIF,

    /** Generic TIFF (`II*`/`MM*` magic) with no recognized RAW structure. PEF/SRW/NRW RAWs honestly land here too. */
    TIFF,

    /** Canon CR2: TIFF magic plus the documented `CR 02 00` marker at bytes 8..11. */
    TIFF_CR2,

    /** Adobe DNG: TIFF magic plus the spec-required DNGVersion tag (0xC612) in IFD0. */
    TIFF_DNG,

    /**
     * Nikon NEF candidate: TIFF magic plus a Make tag starting "NIKON".
     * The bytes prove the MAKER; the NEF identification is the strong
     * convention that Nikon's TIFF-magic files in a gallery are NEFs ---
     * stated as a candidate, never as container-level proof.
     */
    TIFF_RAW_NIKON,

    /** Sony ARW candidate: TIFF magic plus a Make tag starting "SONY" (same honesty rule as [TIFF_RAW_NIKON]). */
    TIFF_RAW_SONY,

    /**
     * MP4-family video: an `ftyp` box whose MAJOR brand is an ISO-BMFF
     * video brand (isom, iso2, mp41, mp42, avc1, dash, ...).
     */
    MP4_VIDEO,

    /** QuickTime movie: an `ftyp` box whose major brand is `qt  `. */
    QUICKTIME_MOV,

    /** 3GPP/3GPP2 video: an `ftyp` box whose MAJOR brand is a `3gp*`/`3g2*` brand. */
    THREEGP,

    /**
     * Matroska or WebM: the EBML magic `1A 45 DF A3`. The magic proves the
     * EBML container family; separating WebM from Matroska needs the
     * DocType element, which this round's sniffer deliberately does not
     * parse (honest family-level verdict; see the audit doc).
     */
    MATROSKA_WEBM,

    /** AVI: `RIFF....AVI ` form type. Recognized so the catalogue can state it is NOT playable via media3. */
    AVI,

    /**
     * The honest negative: unrecognized bytes, or a signature TRUNCATED
     * below the length needed to prove anything. Never a guess, never a
     * default JPEG; callers render the unsupported surface for it.
     */
    UNKNOWN,
}

/**
 * Pure magic-byte format sniffer (Phase 4 format-support audit ---
 * roadmap Phase 4 still-open item "Audit support for HEIC/HEIF, AVIF,
 * RAW, animated formats, and unusual video formats"; the audit itself is
 * `docs/phase-4/format-support-audit.md`).
 *
 * What this object is: the byte-level half of the format audit. The app
 * today dispatches decode and metadata work off the MediaStore MIME type
 * (see [MetadataFormatMatrix]); the sniffer adds what the MIME type
 * cannot say --- what the file's OWN leading bytes claim it is, which is
 * how the Details view and the unsupported-format surface can one day say
 * "this file's extension says JPEG but its bytes are WebP" instead of
 * trusting the row.
 *
 * Purity (ADR-0016): zero dependencies, zero `android.*` --- plain
 * Kotlin over `ByteArray`/`InputStream`, so the whole decision tree is
 * JVM-tested against the hex-literal corpus in `FormatSnifferTest`. That
 * corpus is SIGNATURE-LEVEL by doctrine: it pins what the leading bytes
 * prove, while decode-level validation (does this DEVICE actually decode
 * this HEIC?) remains the on-device release gate the repo already uses
 * for everything the JVM cannot answer.
 *
 * Honesty rules, fixed by this object:
 *  - never guess: any input whose signature is not fully present in the
 *    sniffed prefix yields [SniffedFormat.UNKNOWN], including truncated
 *    files (a 12-byte `RIFF....WEBP` without its chunk type proves too
 *    little to subtype and reports UNKNOWN rather than inventing one);
 *  - proof levels are documented per family (see [SniffedFormat]):
 *    magic-level (JPEG/PNG/GIF/EBML), structure-level (the ISO-BMFF
 *    brand scan, the TIFF IFD0 walk for DNGVersion/Make), and
 *    convention-level (NEF/ARW, which identify the MAKER);
 *  - everything bounded: stream sniffing reads at most
 *    [MAX_SNIFF_BYTES], the ftyp brand scan stops after
 *    [MAX_FTYP_BRAND_SCAN_BYTES], and the TIFF IFD walk reads at most
 *    [MAX_IFD_ENTRIES] entries with every offset bounds-checked ---
 *    a corrupt or adversarial file can only produce a verdict, never a
 *    long loop or a throw;
 *  - deliberately NOT sniffed (each lands honestly in UNKNOWN; see the
 *    audit doc's gaps section): WAV and M4A audio containers, BMP,
 *    MPEG-TS (no leading magic --- its 0x47 sync byte repeats inside the
 *    stream, so "leading bytes" cannot prove it), FLV, and the non-TIFF
 *    RAW magics ORF/RW2/RAF.
 *
 * Adoption (Phase 5, deliberately NOT wired this round): the catalogue's
 * [FormatSupportCatalogue] rows turn a sniffed family into the honest
 * capability statement for the unsupported-format surface
 * (`ui/media/UnsupportedFileState.kt`, currently without call sites) and
 * the Details view's format rows. No UI file changes in this slice.
 */
object FormatSniffer {

    /**
     * The maximum number of bytes [sniff] pulls from an [InputStream].
     * Generous against every signature (the longest structural walk ---
     * a TIFF IFD0 with a Make string --- needs a few hundred bytes in
     * real files); a memory guard, not a format judgement.
     */
    const val MAX_SNIFF_BYTES: Int = 4096

    // ---- Public API ---------------------------------------------------

    /**
     * Identifies the format family from [bytes]' leading prefix, or
     * [SniffedFormat.UNKNOWN] when the prefix proves nothing. Never
     * throws and never reads past [bytes].
     */
    fun sniff(bytes: ByteArray): SniffedFormat {
        if (bytes.size < MIN_SIGNATURE_BYTES) return SniffedFormat.UNKNOWN
        if (startsWith(bytes, JPEG_SIGNATURE)) return SniffedFormat.JPEG
        if (startsWith(bytes, PNG_SIGNATURE)) return SniffedFormat.PNG
        if (startsWith(bytes, GIF87A_SIGNATURE) || startsWith(bytes, GIF89A_SIGNATURE)) {
            return SniffedFormat.GIF
        }
        if (startsWith(bytes, RIFF_SIGNATURE)) return riffVariant(bytes)
        if (startsWith(bytes, EBML_SIGNATURE)) return SniffedFormat.MATROSKA_WEBM
        if (isTiffMagic(bytes)) return tiffVariant(bytes)
        if (bytes.size >= FTYP_HEADER_BYTES && fourccAt(bytes, FTYP_TYPE_OFFSET) == FTYP_TYPE) {
            return isoBmffVariant(bytes)
        }
        return SniffedFormat.UNKNOWN
    }

    /**
     * Same verdict as [sniff] over a stream: pulls at most
     * [MAX_SNIFF_BYTES] into a bounded buffer (short reads tolerated),
     * then sniffs the collected prefix. Does NOT close [stream] ---
     * ownership stays with the caller (the MediaReadHandle boundary owns
     * stream lifetimes).
     *
     * NEVER THROWS, exactly like the byte overload --- the deliberate
     * choice, weighed against documenting a divergence instead: a read
     * that fails partway (`IOException`) is treated as an ABNORMAL END
     * of the stream, the loop stops, and the verdict comes from the
     * bytes already collected --- which is precisely how the byte
     * overload treats a truncated prefix (a fully-present signature
     * still proves its family; nothing collected proves nothing).
     * Swallowing the exception hides no real error in practice: the
     * stream's owner (the read boundary) still surfaces provider
     * failures on its own reads, while this overload is a
     * classification call, not an error-reporting channel --- and a
     * throw would be the one code path out of an otherwise
     * never-throws object.
     */
    fun sniff(stream: InputStream): SniffedFormat {
        val prefix = ByteArray(MAX_SNIFF_BYTES)
        var total = 0
        while (total < prefix.size) {
            val read = try {
                stream.read(prefix, total, prefix.size - total)
            } catch (e: IOException) {
                // Abnormal end of stream: the collected prefix is all
                // the proof there is (see the KDoc for why this is the
                // byte overload's contract, not a swallowed error).
                break
            }
            if (read <= 0) break
            total += read
        }
        return sniff(prefix.copyOf(total))
    }

    // ---- RIFF containers (WebP, AVI) ----------------------------------

    /**
     * Dispatch on the RIFF form type at bytes 8..11: `WEBP` narrows to
     * the chunk subtype, `AVI ` is recognized for the catalogue's honest
     * no-playback row, anything else (e.g. `WAVE`, audio) is UNKNOWN.
     */
    private fun riffVariant(bytes: ByteArray): SniffedFormat {
        if (bytes.size < RIFF_FORM_TYPE_OFFSET + 4) return SniffedFormat.UNKNOWN
        return when (fourccAt(bytes, RIFF_FORM_TYPE_OFFSET)) {
            WEBP_FORM -> webpVariant(bytes)
            AVI_FORM -> SniffedFormat.AVI
            else -> SniffedFormat.UNKNOWN
        }
    }

    /**
     * The WebP chunk fourcc at bytes 12..15 selects the subtype. A WebP
     * truncated before the chunk type (12..15 bytes total) or carrying an
     * unrecognized first chunk yields UNKNOWN --- the container is
     * provably WebP, but a subtype verdict would be a guess.
     */
    private fun webpVariant(bytes: ByteArray): SniffedFormat {
        if (bytes.size < WEBP_CHUNK_TYPE_OFFSET + 4) return SniffedFormat.UNKNOWN
        return when (fourccAt(bytes, WEBP_CHUNK_TYPE_OFFSET)) {
            "VP8 " -> SniffedFormat.WEBP_VP8
            "VP8L" -> SniffedFormat.WEBP_VP8L
            "VP8X" -> SniffedFormat.WEBP_VP8X
            else -> SniffedFormat.UNKNOWN
        }
    }

    // ---- ISO-BMFF (ftyp brands) ----------------------------------------

    /**
     * Classifies the `ftyp` box in two deliberate halves:
     *
     *  - the MAJOR brand decides the VIDEO families (QuickTime, the MP4
     *    set, 3GPP/3GPP2) before anything is scanned. A compatible-brand
     *    list is a compatibility DECLARATION, not a primary family
     *    claim: a QuickTime/MP4 HEVC camcorder file can carry the
     *    image-sequence brand `hevc` in its list, and classifying on the
     *    mixed list handed back the still-image verdict (playback =
     *    NOT_A_VIDEO_CONTAINER) for a playable video --- the exact file
     *    the audit doc flags. Video families are therefore declared by
     *    the major brand ONLY; an exotic major with video brands in its
     *    compatible list stays UNKNOWN rather than guessing a family.
     *  - image classification falls back to scanning the major PLUS
     *    compatible brands, AVIF first (so an AVIF also listing
     *    mif1/heic is AVIF, mirroring androidx's own sniff order), then
     *    the HEIF image set --- a generic container (mif1) carries its
     *    real image brand in the compatible list, which is why the
     *    image half needs the scan the video half does not.
     *
     * A brand list truncated mid-box simply stops adding brands ---
     * available bytes only, nothing fabricated; an unrecognized brand
     * set is UNKNOWN.
     */
    private fun isoBmffVariant(bytes: ByteArray): SniffedFormat {
        val majorBrand = fourccAt(bytes, FTYP_MAJOR_BRAND_OFFSET)
            ?: return SniffedFormat.UNKNOWN
        // Video families, from the major brand alone (see the KDoc for
        // why the compatible list must not be consulted here).
        if (majorBrand == QUICKTIME_BRAND) return SniffedFormat.QUICKTIME_MOV
        if (majorBrand in MP4_BRANDS || majorBrand.startsWith(MP4_PREFIX)) {
            return SniffedFormat.MP4_VIDEO
        }
        if (majorBrand.startsWith(THREEGP_PREFIX) || majorBrand.startsWith(THREEG2_PREFIX)) {
            return SniffedFormat.THREEGP
        }
        // Image classification: the full brand scan (major plus
        // compatible brands).
        val brands = ArrayList<String>(8)
        brands.add(majorBrand)
        val declaredBoxSize = u32At(bytes, FTYP_BOX_SIZE_OFFSET, littleEndian = false)
        val declaredEnd =
            if (declaredBoxSize >= FTYP_HEADER_BYTES) declaredBoxSize else bytes.size.toLong()
        val scanEnd = minOf(
            declaredEnd,
            bytes.size.toLong(),
            FTYP_BRAND_LIST_OFFSET.toLong() + MAX_FTYP_BRAND_SCAN_BYTES,
        ).toInt()
        var offset = FTYP_BRAND_LIST_OFFSET
        while (offset + 4 <= scanEnd) {
            fourccAt(bytes, offset)?.let(brands::add)
            offset += 4
        }
        if (brands.any { it == AVIF_BRAND_STILL || it == AVIF_BRAND_SEQUENCE }) {
            return SniffedFormat.AVIF
        }
        if (brands.any { it in HEIF_IMAGE_BRANDS }) return SniffedFormat.HEIF
        return SniffedFormat.UNKNOWN
    }

    // ---- TIFF family ----------------------------------------------------

    /**
     * Refines the TIFF magic (`II*`/`MM*`) into a family member.
     *
     * Two proof levels, kept apart honestly:
     *  - the CR2 marker (bytes 8..11 == `43 52 02 00`, the documented
     *    Canon CR2 header) is signature-level proof;
     *  - the IFD0 walk reads the first image file directory for the
     *    DNGVersion tag (0xC612, required by the DNG specification in
     *    every DNG's IFD0) and the Make tag (0x010F, TIFF/EXIF standard).
     *    DNGVersion proves DNG outright; Make proves the camera MAKER,
     *    from which the NEF (Nikon) / ARW (Sony) identifications are the
     *    documented conventions --- candidates, not container proofs.
     *
     * Any structural failure (IFD offset out of bounds, absurd entry
     * count, string offset out of bounds) degrades to [SniffedFormat.TIFF]:
     * the magic still proves the TIFF container, and no verdict beyond
     * that is invented.
     */
    private fun tiffVariant(bytes: ByteArray): SniffedFormat {
        val littleEndian = bytes[0] == BYTE_ASCII_I
        if (hasCr2Marker(bytes)) return SniffedFormat.TIFF_CR2
        if (bytes.size < TIFF_HEADER_BYTES) return SniffedFormat.TIFF
        val ifdOffset = u32At(bytes, TIFF_IFD_OFFSET_FIELD, littleEndian)
        if (ifdOffset < TIFF_HEADER_BYTES || ifdOffset + 2 > bytes.size) {
            return SniffedFormat.TIFF
        }
        val entryCount = u16At(bytes, ifdOffset.toInt(), littleEndian)
        if (entryCount <= 0 || entryCount > MAX_IFD_ENTRIES) return SniffedFormat.TIFF
        val entriesStart = ifdOffset.toInt() + 2
        if (entriesStart + entryCount * TIFF_ENTRY_BYTES > bytes.size) return SniffedFormat.TIFF

        var hasDngVersion = false
        var make: String? = null
        for (index in 0 until entryCount) {
            val entryOffset = entriesStart + index * TIFF_ENTRY_BYTES
            val tag = u16At(bytes, entryOffset, littleEndian)
            if (tag == TIFF_TAG_DNG_VERSION) {
                hasDngVersion = true
            } else if (tag == TIFF_TAG_MAKE) {
                make = readAsciiValue(
                    bytes = bytes,
                    entryOffset = entryOffset,
                    valueCount = u32At(bytes, entryOffset + 4, littleEndian),
                    littleEndian = littleEndian,
                )
            }
        }
        // A DNG converted from a Nikon NEF keeps Make == "NIKON..." but
        // carries DNGVersion: the spec-required tag outranks the maker.
        if (hasDngVersion) return SniffedFormat.TIFF_DNG
        val maker = make?.trim()?.uppercase(Locale.ROOT)
        if (maker != null) {
            if (maker.startsWith(NIKON_PREFIX)) return SniffedFormat.TIFF_RAW_NIKON
            if (maker.startsWith(SONY_PREFIX)) return SniffedFormat.TIFF_RAW_SONY
        }
        return SniffedFormat.TIFF
    }

    /** True when bytes 8..11 are the documented Canon CR2 marker `43 52 02 00`. */
    private fun hasCr2Marker(bytes: ByteArray): Boolean =
        bytes.size >= CR2_MARKER_OFFSET + 4 &&
            byteIs(bytes, CR2_MARKER_OFFSET, 0x43) &&
            byteIs(bytes, CR2_MARKER_OFFSET + 1, 0x52) &&
            byteIs(bytes, CR2_MARKER_OFFSET + 2, 0x02) &&
            byteIs(bytes, CR2_MARKER_OFFSET + 3, 0x00)

    /**
     * Reads one IFD entry's ASCII value (the Make tag): inline when it
     * fits the 4-byte value field, at its byte offset otherwise. `null`
     * on any bound violation or absurd length --- the caller then keeps
     * the weaker (honest) verdict.
     */
    private fun readAsciiValue(
        bytes: ByteArray,
        entryOffset: Int,
        valueCount: Long,
        littleEndian: Boolean,
    ): String? {
        if (valueCount <= 0L || valueCount > MAX_ASCII_VALUE_BYTES) return null
        if (valueCount <= 4L) {
            return String(bytes, entryOffset + 8, valueCount.toInt(), Charsets.US_ASCII)
        }
        val valueOffset = u32At(bytes, entryOffset + 8, littleEndian)
        if (valueOffset < 0L || valueOffset + valueCount > bytes.size) return null
        return String(bytes, valueOffset.toInt(), valueCount.toInt(), Charsets.US_ASCII)
    }

    // ---- Low-level helpers ----------------------------------------------

    /** `true` when [bytes] starts with the TIFF magic (either byte order). */
    private fun isTiffMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val littleEndian =
            byteIs(bytes, 0, 0x49) && byteIs(bytes, 1, 0x49) &&
                byteIs(bytes, 2, 0x2A) && byteIs(bytes, 3, 0x00)
        val bigEndian =
            byteIs(bytes, 0, 0x4D) && byteIs(bytes, 1, 0x4D) &&
                byteIs(bytes, 2, 0x00) && byteIs(bytes, 3, 0x2A)
        return littleEndian || bigEndian
    }

    /** `true` when [index] holds [value] (unsigned byte compare). */
    private fun byteIs(bytes: ByteArray, index: Int, value: Int): Boolean =
        (bytes[index].toInt() and 0xFF) == value

    /** `true` when [bytes] starts with [signature] (and is long enough to). */
    private fun startsWith(bytes: ByteArray, signature: ByteArray): Boolean {
        if (bytes.size < signature.size) return false
        for (index in signature.indices) {
            if (bytes[index] != signature[index]) return false
        }
        return true
    }

    /**
     * The four ASCII characters at [offset], or `null` when unavailable
     * or non-printable (a fourcc is printable ASCII by definition, so a
     * non-printable slot is not a brand).
     */
    private fun fourccAt(bytes: ByteArray, offset: Int): String? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val builder = StringBuilder(4)
        for (index in offset until offset + 4) {
            val value = bytes[index].toInt() and 0xFF
            if (value < MIN_PRINTABLE_ASCII || value > MAX_PRINTABLE_ASCII) return null
            builder.append(value.toChar())
        }
        return builder.toString()
    }

    /** The unsigned 16-bit integer at [offset] in the given byte order. */
    private fun u16At(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    /** The unsigned 32-bit integer at [offset] (as a [Long]) in the given byte order. */
    private fun u32At(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return if (littleEndian) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    /** ASCII bytes for a fixed signature string. */
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    // ---- Constants -------------------------------------------------------

    /** Shortest signature this sniffer can prove: JPEG's three bytes. */
    private const val MIN_SIGNATURE_BYTES = 3

    /** `FF D8 FF` --- JPEG SOI plus the always-present first marker byte. */
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** The 8-byte PNG signature. */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** The two versioned GIF signatures. */
    private val GIF87A_SIGNATURE = ascii("GIF87a")
    private val GIF89A_SIGNATURE = ascii("GIF89a")

    /** `RIFF` --- the RIFF container magic (WebP and AVI live under it). */
    private val RIFF_SIGNATURE = ascii("RIFF")

    /** `1A 45 DF A3` --- the EBML magic shared by Matroska and WebM. */
    private val EBML_SIGNATURE = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** `I`, the little-endian TIFF byte-order mark's first byte. */
    private const val BYTE_ASCII_I: Byte = 0x49

    /** RIFF form type ("WEBP"/"AVI "/other) sits at bytes 8..11. */
    private const val RIFF_FORM_TYPE_OFFSET = 8

    /** The WebP first-chunk fourcc sits at bytes 12..15. */
    private const val WEBP_CHUNK_TYPE_OFFSET = 12

    private const val WEBP_FORM = "WEBP"
    private const val AVI_FORM = "AVI "

    /** ISO-BMFF box layout: size at 0, `ftyp` at 4, major brand at 8, brands from 16. */
    private const val FTYP_BOX_SIZE_OFFSET = 0
    private const val FTYP_TYPE_OFFSET = 4
    private const val FTYP_TYPE = "ftyp"
    private const val FTYP_MAJOR_BRAND_OFFSET = 8
    private const val FTYP_BRAND_LIST_OFFSET = 16
    private const val FTYP_HEADER_BYTES = 16

    /**
     * Brand-scan cap: real `ftyp` boxes list a handful of brands; 512
     * bytes (128 brands) is far beyond any real box and keeps the walk
     * bounded on adversarial sizes.
     */
    private const val MAX_FTYP_BRAND_SCAN_BYTES = 512

    /** AVIF still and sequence brands (checked before the HEIF set). */
    private const val AVIF_BRAND_STILL = "avif"
    private const val AVIF_BRAND_SEQUENCE = "avis"

    /**
     * HEIF image brands (ISO/IEC 23008-12): still (heic, heix, heim,
     * heis, mif1) and sequence (hevc, hevx, msf1) forms; see
     * [SniffedFormat.HEIF] for the deliberate `hevc` classification.
     */
    private val HEIF_IMAGE_BRANDS =
        setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

    /** The QuickTime major brand (two trailing spaces are part of it). */
    private const val QUICKTIME_BRAND = "qt  "

    /** 3GPP (`3gp*`) and 3GPP2 (`3g2*`) brand prefixes. */
    private const val THREEGP_PREFIX = "3gp"
    private const val THREEG2_PREFIX = "3g2"

    /** MP4-family brands; plus the `mp4` prefix (mp41/mp42/... handled exactly below). */
    private const val MP4_PREFIX = "mp4"
    private val MP4_BRANDS =
        setOf("isom", "iso2", "iso3", "iso4", "iso5", "mp41", "mp42", "mp43", "mp4v", "avc1", "dash", "msnv")

    /** TIFF layout: header 8 bytes, each IFD entry 12 bytes. */
    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_ENTRY_BYTES = 12
    private const val TIFF_IFD_OFFSET_FIELD = 4

    /** The documented Canon CR2 marker sits at bytes 8..11. */
    private const val CR2_MARKER_OFFSET = 8

    /** TIFF/EXIF tags read by the IFD0 walk: Make and the DNG-required DNGVersion. */
    private const val TIFF_TAG_MAKE = 0x010F
    private const val TIFF_TAG_DNG_VERSION = 0xC612

    /** Maker prefixes for the NEF/ARW conventions (see [SniffedFormat.TIFF_RAW_NIKON]). */
    private const val NIKON_PREFIX = "NIKON"
    private const val SONY_PREFIX = "SONY"

    /** IFD0 entry cap; a larger count is corrupt and degrades to plain TIFF. */
    private const val MAX_IFD_ENTRIES = 256

    /** ASCII value-length cap (the Make strings compared are far shorter). */
    private const val MAX_ASCII_VALUE_BYTES = 64L

    /** Printable-ASCII fourcc bounds (space through tilde). */
    private const val MIN_PRINTABLE_ASCII = 0x20
    private const val MAX_PRINTABLE_ASCII = 0x7E
}
