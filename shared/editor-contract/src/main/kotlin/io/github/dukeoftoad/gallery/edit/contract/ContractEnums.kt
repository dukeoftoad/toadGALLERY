package io.github.dukeoftoad.gallery.edit.contract

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
