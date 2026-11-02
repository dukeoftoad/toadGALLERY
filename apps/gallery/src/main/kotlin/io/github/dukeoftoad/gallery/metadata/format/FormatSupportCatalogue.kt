package io.github.dukeoftoad.gallery.metadata.format

/**
 * One row of the format-support catalogue (Phase 4 format audit,
 * `docs/phase-4/format-support-audit.md`): the PLATFORM-AND-LIBRARY
 * capability truth for one sniffed format family.
 *
 * Two axes, deliberately kept apart (the same split as
 * [FormatCapability]'s "capability truth vs file truth"):
 *  - [io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix]
 *    is the APP-POLICY axis --- what toadGALLERY's pinned parser set
 *    claims to read/write this round;
 *  - this catalogue is the DEVICE-AND-LIBRARY axis --- what Android's
 *    platform decoders, `androidx.exifinterface` 1.4.1 (the pinned
 *    version, `gradle/libs.versions.toml`) and media3 1.11.1 actually
 *    do, with every claim sourced to the repo's own code or the official
 *    docs (full citations in the audit doc and in the enum KDocs here).
 *
 * The axis exists so the future unsupported-format surface and Details
 * rows can say "THIS device cannot decode HEIF" (library truth) instead
 * of either repeating the policy matrix or inventing facts.
 */
data class FormatSupport(
    /** The family this row describes. */
    val format: SniffedFormat,
    /** The leading-bytes signature that identifies this family (the sniffer's proof). */
    val signature: String,
    /** Platform still-image decode support across the app's API 26-36 span. */
    val stillDecode: StillDecodeSupport,
    /** Animated-format capability: what animates, what only could, what is static. */
    val animated: AnimatedSupport,
    /** EXIF read truth for `androidx.exifinterface` 1.4.1. */
    val exifRead: ExifReadSupport,
    /** EXIF write truth (`saveAttributes`) for `androidx.exifinterface` 1.4.1. */
    val exifWrite: ExifWriteSupport,
    /** Video playback truth for the app's media3 1.11.1 stack. */
    val playback: PlaybackSupport,
    /** The honest limitations, with repo-file pointers; full citations live in the audit doc. */
    val notes: String,
)

/**
 * Platform still-image decode support for a family across the app's
 * API 26-36 span (minSdk 26, targetSdk 36).
 *
 * All platform claims below are from Android's official supported media
 * formats page, "Image support":
 * <https://developer.android.com/media/platform/supported-formats>.
 */
enum class StillDecodeSupport {
    /**
     * The supported-formats page lists an unconditional decoder (BMP,
     * GIF, JPEG, PNG, WebP) --- within this app's API 26-36 span that
     * means every device decodes it.
     */
    DECODES_ON_ALL_SUPPORTED_RELEASES,

    /**
     * HEIF: the decoder ships from Android 9.0 (API 28) and is DEVICE
     * OPTIONAL --- the page's own instruction is the runtime check
     * `ImageDecoder.isMimeTypeSupported("image/heif")`; 10-bit HEIF
     * needs the optional HEVC Main10 profile.
     */
    DEVICE_OPTIONAL_FROM_API_28,

    /**
     * AVIF: 8-bit decoding from Android 12 (API 31); 10-bit is optional
     * (`ImageDecoder.isMimeTypeSupported("image/avif")` reports 10-bit
     * only, and 8-bit still works when it returns false, per the page).
     */
    EIGHT_BIT_FROM_API_31,

    /**
     * The family is absent from the supported image formats page (TIFF
     * and the RAW variants, AVI) --- no platform still decode is
     * promised. Individual OEM builds may add RAW decoders to their own
     * stacks; that is device-dependent and unpromised, so the app says
     * unsupported.
     */
    NOT_DECODED_BY_PLATFORM,

    /** Video containers: frames are extracted (MediaMetadataRetriever), not still-decoded. */
    NOT_A_STILL_IMAGE_FORMAT,

    /** UNKNOWN input: no claim is made (never guess what the bytes are). */
    NOT_CLAIMED,
}

/**
 * Animated-format capability per family.
 *
 * Platform animation facts: `android.graphics.ImageDecoder` (API 28+)
 * returns an `AnimatedImageDrawable` "if the encoded image is an
 * animated GIF or WEBP" (ImageDecoder reference,
 * <https://developer.android.com/reference/android/graphics/ImageDecoder>);
 * on API 26/27 the app animates GIFs in software through coil-gif's
 * `GifDecoder` (see the viewer wiring cited in the GIF row).
 */
enum class AnimatedSupport {
    /** GIF: the viewer animates it today (per-request decoderFactory, `ui/viewer/ViewerScreen.kt`). */
    ANIMATED_WIRED_IN_VIEWER,

    /**
     * The family HAS animated variants (animated WebP inside VP8X; HEIF
     * sequence brands; AVIF `avis` sequences), but the app renders a
     * still today: the viewer attaches its animating decoder factory
     * only for the `image/gif` MIME type, and animated AVIF decode is
     * not documented by the platform at all --- see each row's notes for
     * what the platform could do versus what is unverified.
     */
    ANIMATION_NOT_WIRED,

    /** The family has no animated variant the platform documents (JPEG, PNG, VP8/VP8L WebP, TIFF). */
    STATIC_ONLY,

    /** Video containers are played, not "animated". */
    NOT_AN_IMAGE_FORMAT,

    /** UNKNOWN input: no claim is made. */
    NOT_CLAIMED,
}

/**
 * EXIF read truth for the PINNED `androidx.exifinterface` 1.4.1. The
 * read list below is quoted verbatim from the 1.4.1 sources jar
 * (<https://dl.google.com/android/maven2/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar>);
 * the current official class reference states the same list with HEIC
 * explicitly qualified "(on API 28+)"
 * (<https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface>):
 * "Supported for reading: JPEG, PNG, WebP, HEIC, DNG, CR2, NEF, NRW,
 * ARW, RW2, ORF, PEF, SRW, RAF, AVIF (on API 31+)".
 */
enum class ExifReadSupport {
    /** In the 1.4.1 read list with no additional API gate (JPEG, PNG, WebP, CR2, DNG, NEF, ARW). */
    READ_BY_ANDROIDX,

    /**
     * HEIC/HEIF: in the read list, but the 1.4.1 source reads HEIF EXIF
     * through `MediaMetadataRetriever` EXIF-offset metadata (its own
     * comment: those keys "was added in SDK 28 for HEIC and in SDK 31 for
     * AVIF"); below SDK 28 `getHeifAttributes` throws
     * `UnsupportedOperationException("Reading EXIF from HEIC files is
     * supported from SDK 28 and above")`, which `loadAttributes` catches
     * (`IOException | UnsupportedOperationException`) and IGNORES for
     * backwards compatibility --- so API 26/27 reads yield empty
     * attributes, not data and not a crash.
     */
    READ_BY_ANDROIDX_FROM_API_28,

    /** AVIF: "AVIF (on API 31+)" --- below 31 the 1.4.1 read path yields empty attributes. */
    READ_BY_ANDROIDX_FROM_API_31,

    /** Plain TIFF is NOT in the 1.4.1 read list. */
    NOT_READ_BY_ANDROIDX,

    /**
     * No standard EXIF carrier: GIF (none in GIF87a/89a) and the video
     * containers (EXIF-in-udta is a non-standard carrier the app's
     * policy matrix already declares UNSUPPORTED --- videos surface
     * container metadata via MediaMetadataRetriever instead).
     */
    NO_STANDARD_EXIF_CARRIER,

    /** UNKNOWN input: no claim is made. */
    NOT_CLAIMED,
}

/**
 * EXIF write truth (`ExifInterface.saveAttributes`) for the pinned 1.4.1:
 * "Supported for writing: JPEG, PNG, WebP" (same verified source), and
 * `saveAttributes` itself rejects other formats with "ExifInterface
 * only supports saving attributes for JPEG, PNG, and WebP formats".
 *
 * R15's metadata-write round depends on this truth: writes also require
 * a FILE-BACKED ExifInterface instance (file path or seekable
 * descriptor) --- the stream-based instances the read path builds
 * cannot save --- and the library stages its rewrite through its own
 * temp file, which that round must consciously accept and document.
 */
enum class ExifWriteSupport {
    /** JPEG, PNG, WebP --- the 1.4.1 write list. */
    WRITABLE_BY_ANDROIDX,

    /** Everything else: the library refuses the write. */
    NOT_WRITABLE_BY_ANDROIDX,

    /** UNKNOWN input: no claim is made. */
    NOT_CLAIMED,
}

/**
 * Video playback truth for the app's media3 1.11.1 stack
 * (`media3-exoplayer` + `media3-ui`, no software-decoder extensions are
 * built --- `gradle/libs.versions.toml`).
 *
 * Container claims are from media3's official supported-formats page
 * (<https://developer.android.com/media/media3/exoplayer/supported-formats>),
 * which also fixes the device-dependence doctrine: "By default ExoPlayer
 * uses Android's platform decoders. Hence the supported sample formats
 * depend on the underlying platform rather than on ExoPlayer ...
 * individual devices may support additional formats beyond those
 * listed."
 */
enum class PlaybackSupport {
    /** MP4, WebM, Matroska: listed "YES" in media3's container-formats table. */
    LISTED_BY_MEDIA3,

    /**
     * QuickTime MOV and 3GP: ISO-BMFF-family containers that media3
     * demuxes in practice through the MP4 extractor, but the official
     * container table lists no row for them --- no promise is made here;
     * on-device verification is the release gate.
     */
    DEMUXES_IN_PRACTICE_NOT_LISTED,

    /** AVI: no media3 extractor is listed --- the honest no-playback verdict. */
    NOT_PLAYABLE_BY_MEDIA3,

    /** Still-image formats: nothing to play. */
    NOT_A_VIDEO_CONTAINER,

    /** UNKNOWN input: no claim is made. */
    NOT_CLAIMED,
}

/**
 * The data-driven format-support catalogue (Phase 4 format audit): one
 * [FormatSupport] row per [SniffedFormat], stating the platform/library
 * truth with citations. THE AUDIT DELIVERABLE is the matrix in
 * `docs/phase-4/format-support-audit.md`; this object is its code form,
 * so the Phase 5 adoption (the unsupported-format surface,
 * `ui/media/UnsupportedFileState.kt`, and the Details view's format
 * rows) reads one typed truth instead of re-deriving it per screen.
 *
 * NOT wired this round (R15/R17 own the UI files): no screen, ViewModel
 * or string resource touches this catalogue yet --- the adoption note
 * lives here so the wiring round does not have to re-derive the design.
 *
 * Corpus doctrine: the catalogue's per-family verdicts are pinned by
 * `FormatSupportCatalogueTest`; the SIGNATURES those verdicts hang off
 * are pinned by the hex-literal corpus in `FormatSnifferTest`. Both are
 * signature/declaration-level --- decode-level validation (does THIS
 * device decode THIS HEIC?) stays the on-device release gate per the
 * repo's established doctrine (ADR-0016; the platform rows above say
 * exactly which checks to run there: `ImageDecoder.isMimeTypeSupported`).
 */
object FormatSupportCatalogue {

    /**
     * Every declared row, one per [SniffedFormat] value (pinned by the
     * catalogue test --- adding an enum value without a row is a visible
     * test failure, not a silent null).
     */
    val all: List<FormatSupport> = listOf(
        FormatSupport(
            format = SniffedFormat.JPEG,
            signature = "FF D8 FF",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Reference format: full-resolution decode on every " +
                "API 26-36 device (base + progressive, per the supported-" +
                "formats page); the one format the app's policy matrix " +
                "declares editable.",
        ),
        FormatSupport(
            format = SniffedFormat.PNG,
            signature = "89 50 4E 47 0D 0A 1A 0A",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "APNG (animated PNG) is not documented by the platform " +
                "--- the ImageDecoder reference names animated GIF and WebP " +
                "only --- so it renders as a still. androidx 1.4.1 reads the " +
                "PNG eXIf chunk AND writes PNG, but the app's policy matrix " +
                "keeps PNG EXIF out of scope this round (a policy choice, " +
                "not a library limit).",
        ),
        FormatSupport(
            format = SniffedFormat.GIF,
            signature = "GIF87a / GIF89a",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.ANIMATED_WIRED_IN_VIEWER,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "The viewer animates GIFs today via a per-request " +
                "decoderFactory: coil-gif's ImageDecoderDecoder on API 28+, " +
                "software GifDecoder on 26/27 (ui/viewer/ViewerScreen.kt); " +
                "the grid deliberately renders static first frames.",
        ),
        FormatSupport(
            format = SniffedFormat.WEBP_VP8,
            signature = "RIFF....WEBP + 'VP8 ' chunk",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Lossy WebP; never animated by construction.",
        ),
        FormatSupport(
            format = SniffedFormat.WEBP_VP8L,
            signature = "RIFF....WEBP + 'VP8L' chunk",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Lossless WebP; the platform decoder covers lossless " +
                "from Android 4.2.1 --- below the app's minSdk, so every " +
                "supported device decodes it.",
        ),
        FormatSupport(
            format = SniffedFormat.WEBP_VP8X,
            signature = "RIFF....WEBP + 'VP8X' chunk",
            stillDecode = StillDecodeSupport.DECODES_ON_ALL_SUPPORTED_RELEASES,
            animated = AnimatedSupport.ANIMATION_NOT_WIRED,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Extended WebP (alpha/EXIF/animation). The platform can " +
                "animate it via ImageDecoder on API 28+ and coil-gif 2.7's " +
                "ImageDecoderDecoder.Factory accepts animated WebP --- but " +
                "the app attaches that factory only for image/gif MIME " +
                "(ui/viewer/ViewerScreen.kt), so an animated WebP renders " +
                "its static first frame today. ANIM subchunk detection is " +
                "deeper parsing this round's sniffer does not do.",
        ),
        FormatSupport(
            format = SniffedFormat.HEIF,
            signature = "ftyp image brands: heic, heix, heim, heis, hevc, hevx, mif1, msf1",
            stillDecode = StillDecodeSupport.DEVICE_OPTIONAL_FROM_API_28,
            animated = AnimatedSupport.ANIMATION_NOT_WIRED,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX_FROM_API_28,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Decode is device-optional from API 28 --- the supported-" +
                "formats page's own runtime check is ImageDecoder." +
                "isMimeTypeSupported(\"image/heif\"); 10-bit needs the " +
                "optional HEVC Main10 profile. androidx 1.4.1 reads HEIC " +
                "EXIF only from SDK 28 (empty below). HEIF sequence brands " +
                "(msf1/hevc/hevx) could animate via coil-gif 2.7.0's " +
                "ImageDecoderDecoder --- its own KDoc documents animated " +
                "HEIF decode on API 30+ --- while the platform's " +
                "ImageDecoder reference documents AnimatedImageDrawable for " +
                "GIF and WebP only; sequence animation is therefore an " +
                "unverified library claim, not a platform promise. Not " +
                "wired.",
        ),
        FormatSupport(
            format = SniffedFormat.AVIF,
            signature = "ftyp brands: avif, avis",
            stillDecode = StillDecodeSupport.EIGHT_BIT_FROM_API_31,
            animated = AnimatedSupport.ANIMATION_NOT_WIRED,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX_FROM_API_31,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "8-bit decode from API 31 (10-bit optional, same runtime " +
                "check pattern with \"image/avif\"); nothing AVIF-shaped " +
                "decodes or parses on API 26-30. androidx 1.4.1 reads AVIF " +
                "EXIF from API 31 only. Animated AVIF (avis sequences) is " +
                "not documented as platform-animatable --- treat as static " +
                "until the on-device release gate proves otherwise.",
        ),
        FormatSupport(
            format = SniffedFormat.TIFF,
            signature = "II* 00 / MM 00* (TIFF magic)",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.NOT_READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Plain TIFF: not in the platform's supported image " +
                "formats and not in androidx 1.4.1's read list. The " +
                "TIFF-magic RAWs the sniffer does not refine by maker " +
                "(Pentax PEF, Samsung SRW) land here honestly --- same " +
                "verdicts, since neither the platform nor the app decodes " +
                "them.",
        ),
        FormatSupport(
            format = SniffedFormat.TIFF_CR2,
            signature = "TIFF magic + bytes 8..11 '43 52 02 00' (Canon CR2 header)",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Canon RAW: proven by the documented CR2 header marker. " +
                "androidx 1.4.1 reads CR2 EXIF; the platform does not " +
                "decode the image itself --- no RAW develop exists in this " +
                "app (and none is claimed).",
        ),
        FormatSupport(
            format = SniffedFormat.TIFF_DNG,
            signature = "TIFF magic + DNGVersion tag (0xC612) in IFD0",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Adobe DNG: proven by the spec-required DNGVersion tag " +
                "(the DNG specification's IFD0 baseline tag), not by magic " +
                "bytes. androidx 1.4.1 reads DNG EXIF; the platform image " +
                "formats page lists no DNG decoder --- OEM extras are " +
                "device-dependent and unpromised.",
        ),
        FormatSupport(
            format = SniffedFormat.TIFF_RAW_NIKON,
            signature = "TIFF magic + Make tag starting 'NIKON'",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Nikon NEF/NRW CANDIDATE: the Make tag proves the " +
                "camera maker; 'NEF' is the naming convention for Nikon's " +
                "TIFF-magic RAWs, not a container proof (the sniff is " +
                "honest about that distinction), and the bytes cannot " +
                "separate NEF from Nikon's other TIFF-magic RAW family " +
                "(NRW) --- both carry Make 'NIKON'. androidx 1.4.1 reads " +
                "NEF and NRW EXIF; no platform still decode.",
        ),
        FormatSupport(
            format = SniffedFormat.TIFF_RAW_SONY,
            signature = "TIFF magic + Make tag starting 'SONY'",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.STATIC_ONLY,
            exifRead = ExifReadSupport.READ_BY_ANDROIDX,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_A_VIDEO_CONTAINER,
            notes = "Sony ARW CANDIDATE: same honesty rule as the Nikon row " +
                "--- the bytes prove the maker, the ARW identification is " +
                "the convention. androidx 1.4.1 reads ARW EXIF; no " +
                "platform still decode.",
        ),
        FormatSupport(
            format = SniffedFormat.MP4_VIDEO,
            signature = "ftyp major brand: isom, iso2, mp41, mp42, avc1, dash, mp4*",
            stillDecode = StillDecodeSupport.NOT_A_STILL_IMAGE_FORMAT,
            animated = AnimatedSupport.NOT_AN_IMAGE_FORMAT,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.LISTED_BY_MEDIA3,
            notes = "media3's container table lists MP4 \"YES\". Grid " +
                "thumbnails come from MediaMetadataRetriever via coil-video " +
                "(ToadGalleryApplication.newImageLoader); container " +
                "metadata (rotation/duration/date) via " +
                "metadata/video/VideoMetadataSource.kt. Codecs are the " +
                "DEVICE's MediaCodec set --- the filed issue #65 log (10-bit " +
                "HEVC rejected by c2.qti.hevc.decoder, recovered by the " +
                "viewer's decoder fallback) is the standing evidence.",
        ),
        FormatSupport(
            format = SniffedFormat.QUICKTIME_MOV,
            signature = "ftyp major brand 'qt  '",
            stillDecode = StillDecodeSupport.NOT_A_STILL_IMAGE_FORMAT,
            animated = AnimatedSupport.NOT_AN_IMAGE_FORMAT,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.DEMUXES_IN_PRACTICE_NOT_LISTED,
            notes = "media3's official container table has no MOV row; the " +
                "MP4 extractor demuxes QuickTime atoms in practice. " +
                "Container metadata reads via MediaMetadataRetriever either " +
                "way (the app's policy matrix declares video/quicktime " +
                "PRESENT there). On-device playback verification belongs " +
                "to the release gate.",
        ),
        FormatSupport(
            format = SniffedFormat.THREEGP,
            signature = "ftyp major brand: 3gp*, 3g2*",
            stillDecode = StillDecodeSupport.NOT_A_STILL_IMAGE_FORMAT,
            animated = AnimatedSupport.NOT_AN_IMAGE_FORMAT,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.DEMUXES_IN_PRACTICE_NOT_LISTED,
            notes = "3GPP/3GPP2: ISO-BMFF family, no dedicated media3 " +
                "container row; demuxes in practice. Platform codec caveat " +
                "from the supported-formats video table: H.263 support is " +
                "OPTIONAL from Android 7.0 --- an H.263-only 3GP may have " +
                "no decoder on a modern device.",
        ),
        FormatSupport(
            format = SniffedFormat.MATROSKA_WEBM,
            signature = "EBML magic 1A 45 DF A3",
            stillDecode = StillDecodeSupport.NOT_A_STILL_IMAGE_FORMAT,
            animated = AnimatedSupport.NOT_AN_IMAGE_FORMAT,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.LISTED_BY_MEDIA3,
            notes = "media3 lists BOTH Matroska and WebM \"YES\". The sniff is " +
                "family-level by honesty: separating them needs the EBML " +
                "DocType element, which this round does not parse. Issue " +
                "#65's 10-bit HEVC-in-MKV evidence is why the viewer runs " +
                "with setEnableDecoderFallback(true).",
        ),
        FormatSupport(
            format = SniffedFormat.AVI,
            signature = "RIFF....AVI form type",
            stillDecode = StillDecodeSupport.NOT_DECODED_BY_PLATFORM,
            animated = AnimatedSupport.NOT_AN_IMAGE_FORMAT,
            exifRead = ExifReadSupport.NO_STANDARD_EXIF_CARRIER,
            exifWrite = ExifWriteSupport.NOT_WRITABLE_BY_ANDROIDX,
            playback = PlaybackSupport.NOT_PLAYABLE_BY_MEDIA3,
            notes = "The classic 'unusual video format': media3's container " +
                "table lists no AVI extractor and the platform supported-" +
                "formats page lists no AVI row --- the honest verdict is " +
                "recognized-but-unplayable, surfaced as unsupported rather " +
                "than a frozen black frame.",
        ),
        FormatSupport(
            format = SniffedFormat.UNKNOWN,
            signature = "(none --- unrecognized or truncated signature)",
            stillDecode = StillDecodeSupport.NOT_CLAIMED,
            animated = AnimatedSupport.NOT_CLAIMED,
            exifRead = ExifReadSupport.NOT_CLAIMED,
            exifWrite = ExifWriteSupport.NOT_CLAIMED,
            playback = PlaybackSupport.NOT_CLAIMED,
            notes = "The sniffer refuses to guess; no capability is claimed " +
                "for bytes it cannot identify. This is the row the Phase 5 " +
                "unsupported-format surface renders (ui/media/" +
                "UnsupportedFileState.kt) with the file's name, not a " +
                "fabricated format.",
        ),
    )

    /**
     * The families with a declared row. Exposed for tests and the future
     * Details surface; rows are looked up via [supportFor]. Always
     * exactly the [SniffedFormat] values --- pinned by test.
     */
    val cataloguedFormats: Set<SniffedFormat>
        get() = CATALOGUE.keys

    /** The declared rows, keyed by family. */
    private val CATALOGUE: Map<SniffedFormat, FormatSupport> = all.associateBy { it.format }

    /**
     * The capability row for [format]. Unknown families cannot occur
     * (the enum is closed and every value has a row), but a future enum
     * addition without a row falls back to the UNKNOWN row's honest
     * no-claims verdict instead of throwing --- the same never-crash
     * contract as [MetadataFormatMatrix.capabilitiesFor].
     */
    fun supportFor(format: SniffedFormat): FormatSupport =
        CATALOGUE[format] ?: CATALOGUE.getValue(SniffedFormat.UNKNOWN)
}
