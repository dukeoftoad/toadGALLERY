package io.github.dukeoftoad.gallery.metadata.format

import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import java.util.Locale

/**
 * The declared, data-driven parser capability matrix (Phase 4 decision
 * #4): which metadata blocks toadGALLERY's pinned parser set reads, per
 * MIME type --- "what we actually parse", never "what the format could
 * theoretically carry".
 *
 * Why a matrix at all (the Aves-informed assessment's demand): Aves's
 * native metadata layer is far broader than `androidx.exifinterface`, and
 * pretending one EXIF reader covers every format produces silent lies ---
 * an AVIF's EXIF reported "absent" when it was never read, or a DNG
 * promised writable when no writer handles it. This object is the single
 * place those claims are declared, so the Details UI, the write path and
 * the AI indexer's eligibility checks all read the same truth.
 *
 * Scope of THIS round (read: EXIF via `androidx.exifinterface` + video
 * containers via `MediaMetadataRetriever`; write: the r15 EXIF write
 * spine --- JPEG + WebP only, pinned per-row by `editable` and in one
 * place by `ExifWriteFormats`): IPTC and XMP are capability entries
 * only --- UNSUPPORTED with notes ---
 * because the audit requires the distinction to exist in the model before
 * the parsers are pinned. Swapping an UNSUPPORTED to PRESENT later is a
 * one-line matrix change plus its parser.
 *
 * Unknown MIME types get a fully-UNSUPPORTED row rather than a crash or a
 * fake JPEG row: an unlisted type is outside the pinned parser set, full
 * stop, and callers can say so honestly.
 */
object MetadataFormatMatrix {

    /**
     * The MIME types with a declared capability row. Exposed for tests
     * and the future catalogue UI; the rows themselves are looked up via
     * [capabilitiesFor].
     */
    val pinnedMimeTypes: Set<String>
        get() = MATRIX.keys

    /** The declared rows, keyed by normalized (lowercase) MIME type. */
    private val MATRIX: Map<String, FormatCapability> = listOf(
        row(
            mimeType = "image/jpeg",
            exif = MetadataAvailability.PRESENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.UNSUPPORTED,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = true,
            notes = "Reference format --- full androidx.exifinterface read, " +
                "and the first format the write round pinned (WebP joined " +
                "it; see that row).",
        ),
        row(
            mimeType = "image/heic",
            exif = MetadataAvailability.PRESENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.UNSUPPORTED,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Read follows androidx.exifinterface from API 28 only " +
                "--- its HEIF path reads through MediaMetadataRetriever keys " +
                "added in SDK 28, so on the app's minSdk 26/27 the read " +
                "honestly yields empty attributes (never a crash); " +
                "attribute writing not pinned this round.",
        ),
        row(
            mimeType = "image/heif",
            exif = MetadataAvailability.PRESENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.UNSUPPORTED,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Read follows androidx.exifinterface from API 28 only " +
                "(same SDK-28 floor as image/heic --- on minSdk 26/27 the " +
                "read honestly yields empty attributes); attribute writing " +
                "not pinned this round.",
        ),
        row(
            mimeType = "image/png",
            exif = MetadataAvailability.ABSENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "PNG eXIf chunk is out of scope this round; files are " +
                "treated as EXIF-less rather than falsely readable (XMP in " +
                "iTXt is common in exports, so XMP stays UNSUPPORTED, not " +
                "ABSENT).",
        ),
        row(
            mimeType = "image/webp",
            exif = MetadataAvailability.PRESENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = true,
            notes = "EXIF chunk read follows androidx.exifinterface; " +
                "attribute writing via the r15 write spine " +
                "(ExifWriteFormats --- the androidx-writable set " +
                "intersected with this read matrix, which keeps JPEG + " +
                "WebP and leaves PNG until its read round).",
        ),
        row(
            mimeType = "image/gif",
            exif = MetadataAvailability.ABSENT,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "No EXIF carrier in GIF87a/89a; XMP-in-GIF exists but " +
                "is rare and unhandled.",
        ),
        row(
            mimeType = "image/avif",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "AVIF carries EXIF/XMP inside its ISOBMFF container; " +
                "androidx.exifinterface reads it from API 31 only (empty " +
                "attributes below --- the same verified 1.4.1 source the " +
                "format-support catalogue's READ_BY_ANDROIDX_FROM_API_31 " +
                "cites), so this minSdk-26 pinned reader keeps the format " +
                "UNSUPPORTED rather than device-dependent; revisit when " +
                "the parser set is pinned (Aves demuxes AVIF natively).",
        ),
        row(
            mimeType = "image/x-adobe-dng",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.ABSENT,
            iptc = MetadataAvailability.UNSUPPORTED,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "RAW/DNG deliberately excluded this round --- the TIFF " +
                "structure is parseable but the sensor-adjacent maker " +
                "notes Aves leans on are not; revisit with the parser set.",
        ),
        row(
            mimeType = "video/mp4",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.PRESENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Container metadata (rotation, duration, creation time) " +
                "via MediaMetadataRetriever; EXIF-in-udta is a non-standard " +
                "carrier some makers write --- declared UNSUPPORTED, not " +
                "read.",
        ),
        row(
            mimeType = "video/quicktime",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.PRESENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Container metadata via MediaMetadataRetriever, same " +
                "scope as MP4.",
        ),
        row(
            mimeType = "video/x-matroska",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.PRESENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Container metadata via MediaMetadataRetriever.",
        ),
        row(
            mimeType = "video/3gpp",
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.PRESENT,
            iptc = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "Container metadata via MediaMetadataRetriever.",
        ),
    ).associateBy { it.mimeType }

    /**
     * The capability row for [mimeType]: normalized (trimmed, lowercased,
     * any `;parameter` suffix stripped --- MediaStore types are clean but
     * callers hand over everything) and looked up. An unknown type returns
     * a fresh fully-UNSUPPORTED row with the normalized name, never null
     * and never a throw.
     */
    fun capabilitiesFor(mimeType: String): FormatCapability {
        val normalized = normalize(mimeType)
        return MATRIX[normalized] ?: FormatCapability(
            mimeType = normalized,
            exif = MetadataAvailability.UNSUPPORTED,
            containerMetadata = MetadataAvailability.UNSUPPORTED,
            iptc = MetadataAvailability.UNSUPPORTED,
            xmp = MetadataAvailability.UNSUPPORTED,
            editable = false,
            notes = "MIME type is outside the pinned parser set; nothing " +
                "is read or written for it this round.",
        )
    }

    /** Lowercases and strips any trailing `;parameter` suffix. */
    private fun normalize(mimeType: String): String {
        val trimmed = mimeType.trim().lowercase(Locale.ROOT)
        val parameter = trimmed.indexOf(';')
        val base = if (parameter >= 0) trimmed.substring(0, parameter) else trimmed
        return base.trim()
    }

    /** Builds a row with the normalized key so lookups always hit. */
    private fun row(
        mimeType: String,
        exif: MetadataAvailability,
        containerMetadata: MetadataAvailability,
        iptc: MetadataAvailability,
        xmp: MetadataAvailability,
        editable: Boolean,
        notes: String? = null,
    ): FormatCapability = FormatCapability(
        mimeType = normalize(mimeType),
        exif = exif,
        containerMetadata = containerMetadata,
        iptc = iptc,
        xmp = xmp,
        editable = editable,
        notes = notes,
    )
}
