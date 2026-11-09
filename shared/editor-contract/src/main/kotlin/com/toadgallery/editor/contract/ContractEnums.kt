package com.toadgallery.editor.contract

/**
 * Enumerations shared by both sides of the contract. Each carries its
 * `wireName` --- the exact String that travels in the intent extras --- so
 * parsing is total and explicit (an unknown wire value is an error, never
 * a silently-mapped default).
 */
enum class MetadataPolicy(val wireName: String) {
    PRESERVE(EditContract.METADATA_POLICY_PRESERVE),
    STRIP_LOCATION(EditContract.METADATA_POLICY_STRIP_LOCATION),
    STRIP_ALL(EditContract.METADATA_POLICY_STRIP_ALL);

    companion object {
        /** Total parse; `null` for unknown/absent wire values (caller decides). */
        fun fromWire(value: String?): MetadataPolicy? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * The r42 additive media kind: image (the v1 default) or video. Absent on
 * the wire means IMAGE --- a v1 editor that never reads the extra behaves
 * exactly as before (the additive-versioning rule).
 */
enum class MediaKind(val wireName: String) {
    IMAGE(EditContract.MEDIA_KIND_IMAGE),
    VIDEO(EditContract.MEDIA_KIND_VIDEO);

    companion object {
        /** Total parse; `null` for unknown wire values (the validator refuses). */
        fun fromWire(value: String?): MediaKind? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class OperationHint(val wireName: String) {
    CROP("crop"),
    ROTATE("rotate"),
    RESIZE("resize"),
    DRAW("draw"),
    ADJUST("adjust"),
    FILTER("filter");

    companion object {
        /** Total parse; `null` for unknown/absent wire values (hint is optional). */
        fun fromWire(value: String?): OperationHint? =
            entries.firstOrNull { it.wireName == value }
    }
}
