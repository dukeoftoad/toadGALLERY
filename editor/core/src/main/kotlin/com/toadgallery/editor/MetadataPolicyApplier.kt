package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy

/**
 * The EXIF metadata plan for the saved copy (contract section 4.4: the policy is
 * respected EXACTLY --- GPS removal is opt-in, explicit, and reported; nothing is ever
 * stripped silently). Pure model over the androidx.exifinterface tag surface. The
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
     * The EXIF tags the saved-copy pass walks: androidx.exifinterface exposes NO public
     * tag enumeration, so "preserve" is this explicit surface (EXIF 2.3 identifiers).
     * Absent tags are skipped; GPS tags ride so STRIP_LOCATION can null them; purely
     * structural tags the encoder recomputes (length, compression, thumbnail offsets)
     * are deliberately absent --- the encoder owns those on the new copy.
     */
    val COPYABLE_TAGS: List<String> = listOf(
        // IFD0 (TIFF) descriptive
        "ImageDescription", "Make", "Model", "Orientation",
        "XResolution", "YResolution", "ResolutionUnit", "Software",
        "ModifyDate", "Artist", "Copyright",
        // Exposure + capture settings (Exif IFD)
        "ExposureTime", "FNumber", "ExposureProgram", "ISOSpeedRatings",
        "SensitivityType", "RecommendedExposureIndex", "ExifVersion",
        "DateTimeOriginal", "DateTimeDigitized", "OffsetTime",
        "OffsetTimeOriginal", "OffsetTimeDigitized", "ComponentsConfiguration",
        "ShutterSpeedValue", "ApertureValue", "BrightnessValue",
        "ExposureBiasValue", "MaxApertureValue", "SubjectDistance",
        "MeteringMode", "LightSource", "Flash", "FocalLength",
        "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
        "FlashpixVersion", "ColorSpace", "PixelXDimension", "PixelYDimension",
        "InteroperabilityIndex", "FileSource", "SceneType", "CFAPattern",
        "CustomRendered", "ExposureMode", "WhiteBalance",
        "DigitalZoomRatio", "FocalLengthIn35mmFilm", "SceneCaptureType",
        "GainControl", "Contrast", "Saturation", "Sharpness",
        "DeviceSettingDescription", "SubjectDistanceRange", "ImageUniqueID",
        "CameraOwnerName", "BodySerialNumber", "LensSpecification",
        "LensMake", "LensModel", "LensSerialNumber", "UserComment",
        // GPS (decided per policy --- the LOCATION_TAGS floor)
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef",
        "GPSLongitude", "GPSAltitudeRef", "GPSAltitude", "GPSTimestamp",
        "GPSSatellites", "GPSStatus", "GPSMeasureMode", "GPSDOP",
        "GPSSpeedRef", "GPSSpeed", "GPSTrackRef", "GPSTrack",
        "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum",
        "GPSDestLatitudeRef", "GPSDestLatitude", "GPSDestLongitudeRef",
        "GPSDestLongitude", "GPSDestBearingRef", "GPSDestBearing",
        "GPSDestDistanceRef", "GPSDestDistance", "GPSProcessingMethod",
        "GPSAreaInformation", "GPSDateStamp", "GPSDifferential",
        "GPSHPositioningError",
    )

    /**
     * Whether the applied policy must be narrated back to the gallery as a location
     * removal (the honesty contract section 4.4 promises).
     */
    fun removedLocation(policy: MetadataPolicy): Boolean =
        policy == MetadataPolicy.STRIP_LOCATION || policy == MetadataPolicy.STRIP_ALL
}
