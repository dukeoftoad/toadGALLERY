package io.github.dukeoftoad.gallery.metadata.edit

import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationState
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix
import java.util.Locale

/**
 * The roadmap's editable metadata fields (Phase 4 still-open item:
 * "Editable metadata fields: date/time, GPS, copyright, keywords/tags,
 * rating, colour label, comment" --- `roadmap.md`), reduced to the ones
 * EXIF can honestly hold. XMP-only concepts are still MODELLED here so the
 * Details view can render their honest "not supported yet" state instead
 * of silently omitting them (the same doctrine that keeps the IPTC/XMP
 * block rows visible in the r14 Details view).
 */
enum class MediaEditField {
    /** DateTimeOriginal + OffsetTimeOriginal (the capture instant + its zone). */
    DATE_TIME,

    /** GPSLatitude/Ref + GPSLongitude/Ref (both coordinates, always as a pair). */
    GPS,

    /** The EXIF Copyright string tag. */
    COPYRIGHT,

    /** The EXIF UserComment tag (the roadmap's "comment"). */
    COMMENT,

    /** Windows XPKeywords (0x9c9e) --- modelled, NOT writable this round. */
    KEYWORDS,

    /** Windows Rating (0x4746) / RatingPercent (0x4749) --- modelled, NOT writable this round. */
    RATING,

    /** Colour label --- XMP territory (a later round per the repo's own docs). */
    COLOUR_LABEL,
}

/**
 * Why one editable field is (not) editable (the write-path twin of
 * [io.github.dukeoftoad.gallery.metadata.MetadataAvailability]: the read
 * path never fakes a value, the write path never fakes a permission).
 *
 * The four states the round's contract names, plus two the pinned-library
 * truth forced into the open (both follow the repo's honest-states
 * doctrine --- collapsing them into a neighbour would lie about WHY a
 * field cannot be changed):
 *  - [EDITABLE]: the write may be attempted now.
 *  - [READ_ONLY]: the file is not writable by this app on this platform
 *    (below Android 10 the app holds only read permissions --- it never
 *    declares WRITE_EXTERNAL_STORAGE and there is no consent protocol;
 *    the same tiering that makes `MediaFileOps` Q+ only).
 *  - [UNSUPPORTED_FORMAT]: the format is outside this round's pinned EXIF
 *    write set (see [ExifWriteFormats] for the verified set and why PNG,
 *    which the library CAN write, is still excluded this round).
 *  - [NOT_ELIGIBLE]: vault / trashed / permission-revoked --- the same
 *    type-level gate the read path applies ([EligiblePublicMedia] is
 *    unrepresentable for those states, so it arrives here as `null`).
 *  - [UNSUPPORTED_FIELD]: the FIELD cannot be written by the pinned
 *    androidx.exifinterface 1.4.1 writer even though the format is
 *    writable (keywords/rating tags are not in the library's writable
 *    tag map; colour label is XMP, a later round). Distinct from
 *    [UNSUPPORTED_FORMAT] because the honest reason differs.
 *  - [CONSENT_REQUIRED]: the GPS field's ACCESS_MEDIA_LOCATION dimension
 *    --- on API 29+ the platform redacts EXIF location on ungranted
 *    reads, so editing coordinates is gated on the r14 consent state
 *    (mirroring how the r14 Details view display-gates GPS rows).
 */
enum class MediaEditCapability {
    EDITABLE,
    READ_ONLY,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_FIELD,
    CONSENT_REQUIRED,
    NOT_ELIGIBLE,
}

/**
 * The pinned EXIF WRITE format set (Phase 4 write round).
 *
 * VERIFIED against the pinned androidx.exifinterface **1.4.1** sources
 * (https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface,
 * cross-checked with the 1.4.1 sources artifact at
 * https://maven.google.com/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar):
 * the class document states "Supported for writing: JPEG, PNG, WebP" ---
 * HEIC/HEIF/DNG/RAW are READ-only in this version, and
 * `ExifInterface.saveAttributes()` throws
 * `IOException("ExifInterface only supports saving attributes for JPEG,
 * PNG, and WebP formats.")` for anything else.
 *
 * This round's EDITABLE set is the INTERSECTION of that verified library
 * set with the repo's own read matrix
 * ([MetadataFormatMatrix] --- the r10 pinned reader): a write round only
 * lands where the read round already shows the result, so an edit is
 * always observable and round-trippable in this app. That keeps
 * `image/jpeg` and `image/webp`; it drops `image/png` --- the library can
 * write a PNG `eXIf` chunk, but our pinned reader does not parse PNG EXIF
 * yet (the matrix row honestly says so), so a PNG edit would be a write
 * the app itself cannot display. The PNG write path waits for the PNG
 * read round; encoding it as UNSUPPORTED_FORMAT with this documented
 * reason is the honest state, not a capability claim about the library.
 */
object ExifWriteFormats {

    /** The formats androidx.exifinterface 1.4.1 can write (verified, see the class KDoc). */
    val ANDROIDX_WRITABLE_MIME_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    /**
     * Whether [mimeType] is in this round's pinned EXIF write set:
     * androidx-writable AND read by our pinned reader (see the class KDoc
     * for why both halves are required). Normalizes like
     * [MetadataFormatMatrix.capabilitiesFor] (trim, lowercase, strip any
     * `;parameter` suffix) so MediaStore and provider types both resolve.
     */
    fun isWritable(mimeType: String): Boolean {
        val normalized = normalize(mimeType)
        if (normalized !in ANDROIDX_WRITABLE_MIME_TYPES) return false
        return MetadataFormatMatrix.capabilitiesFor(normalized).exif == MetadataAvailability.PRESENT
    }

    private fun normalize(mimeType: String): String {
        val trimmed = mimeType.trim().lowercase(Locale.ROOT)
        val parameter = trimmed.indexOf(';')
        val base = if (parameter >= 0) trimmed.substring(0, parameter) else trimmed
        return base.trim()
    }
}

/**
 * The pure edit-capability gate: ONE deterministic answer to "may the app
 * write this field of this item right now?", composed from the same
 * inputs the read path uses. Pure Kotlin over plain values (ADR-0016):
 * the Android half (Build.VERSION.SDK_INT, the eligibility unwrap, the
 * consent StateFlow) stays in the ViewModel/writer shells, and the whole
 * transition table is JVM-pinned in `MediaEditCapabilityTest`.
 *
 * Inputs, in the order they are consulted (documented precedence so two
 * simultaneous refusals never render an arbitrary one):
 *  1. [eligible] `null` (vault / trashed / revoked --- the type-level
 *     read gate refused) -> [NOT_ELIGIBLE]; the item is never discussed
 *     field-by-field.
 *  2. Field outside the pinned writer's field set (keywords, rating,
 *     colour label) -> [UNSUPPORTED_FIELD]; this is true for EVERY
 *     format and platform this round, so it outranks both.
 *  3. Format outside the pinned write set -> [UNSUPPORTED_FORMAT]
 *     (platform-independent, so it outranks the platform tier).
 *  4. Platform below Android 10 -> [READ_ONLY]: the app holds only read
 *     permissions there (no WRITE_EXTERNAL_STORAGE, no consent protocol
 *     --- the same tiering that hides the whole file-ops UI below Q).
 *  5. GPS without the location consent -> [CONSENT_REQUIRED] (on API 29+
 *     the platform redacts EXIF location on ungranted reads; below 29
 *     nothing is redactable and [MediaLocationState.NOT_REQUIRED] sails
 *     through).
 *  6. Otherwise -> [EDITABLE]: the write may be attempted (whether it
 *     SUCCEEDS is the writer's honest outcome, not the gate's).
 */
object MediaEditGate {

    /** The fields this round's writer can actually persist. */
    val WRITER_PINNED_FIELDS: Set<MediaEditField> = setOf(
        MediaEditField.DATE_TIME,
        MediaEditField.GPS,
        MediaEditField.COPYRIGHT,
        MediaEditField.COMMENT,
    )

    /**
     * The consent states under which GPS may be EDITED: the same rule the
     * r14 display gate applies to showing coordinates
     * (`MediaLocationDisplayRules.Default`: GRANTED on 29+,
     * NOT_REQUIRED below 29 where the permission does not exist).
     */
    val GPS_EDITTABLE_CONSENT: Set<MediaLocationState> = setOf(
        MediaLocationState.GRANTED,
        MediaLocationState.NOT_REQUIRED,
    )

    /** Android 10 (Q): the first release with the media consent protocol. */
    const val API_29: Int = 29

    fun capabilityFor(
        eligible: EligiblePublicMedia?,
        mimeType: String,
        field: MediaEditField,
        apiLevel: Int,
        locationState: MediaLocationState,
    ): MediaEditCapability {
        if (eligible == null) return MediaEditCapability.NOT_ELIGIBLE
        if (field !in WRITER_PINNED_FIELDS) return MediaEditCapability.UNSUPPORTED_FIELD
        if (!ExifWriteFormats.isWritable(mimeType)) return MediaEditCapability.UNSUPPORTED_FORMAT
        if (apiLevel < API_29) return MediaEditCapability.READ_ONLY
        if (field == MediaEditField.GPS && !GPS_EDITTABLE_CONSENT.contains(locationState)) {
            return MediaEditCapability.CONSENT_REQUIRED
        }
        return MediaEditCapability.EDITABLE
    }
}
