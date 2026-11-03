package io.github.dukeoftoad.gallery.edit

import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy

/**
 * The EXIF metadata plan for the saved copy (contract section 4.4: the
 * editor respects the metadata policy EXACTLY --- GPS removal is opt-in,
 * explicit, and reported; nothing is ever stripped silently).
 *
 * Pure model over the androidx.exifinterface tag surface: the activity
 * walks the source's tags and applies the plan's per-tag decision. The
 * default is PRESERVE --- every tag the source carries rides onto the copy.
 */
object MetadataPolicyApplier {

    /** The EXIF tag names whose values carry geographic position. */
    val LOCATION_TAGS: Set<String> = setOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimestamp", "GPSDateStamp",
        "GPSSpeed", "GPSSpeedRef", "GPSTrack", "GPSTrackRef",
        "GPSImgDirection", "GPSImgDirectionRef", "GPSDestLatitude",
        "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef",
        "GPSProcessingMethod", "GPSAreaInformation", "GPSDifferential",
        "GPSHPositioningError",
    )

    sealed interface TagDecision {
        /** Copy the tag's value onto the saved copy. */
        data object Copy : TagDecision

        /** Write the tag as REMOVED (null) on the copy. */
        data object Strip : TagDecision
    }

    /** The per-tag decision for a policy (identity-free function of policy + tag). */
    fun decide(policy: MetadataPolicy, tagName: String): TagDecision = when (policy) {
        MetadataPolicy.PRESERVE -> TagDecision.Copy
        MetadataPolicy.STRIP_LOCATION -> if (tagName in LOCATION_TAGS) TagDecision.Strip else TagDecision.Copy
        MetadataPolicy.STRIP_ALL -> TagDecision.Strip
    }

    /**
     * Whether the applied policy must be narrated back to the gallery as a
     * location removal (the honesty UI contract section 4.4 promises).
     */
    fun removedLocation(policy: MetadataPolicy): Boolean =
        policy == MetadataPolicy.STRIP_LOCATION || policy == MetadataPolicy.STRIP_ALL
}
