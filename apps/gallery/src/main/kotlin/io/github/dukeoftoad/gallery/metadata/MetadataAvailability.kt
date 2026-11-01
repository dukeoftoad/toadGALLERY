package io.github.dukeoftoad.gallery.metadata

/**
 * Why one metadata field is (not) available (Phase 4 contract, item 4 of
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`).
 *
 * The Phase 4 audit rejects a single `null` for every failure mode because
 * the Aves reference treats them differently: "this JPEG has no GPS block"
 * is normal data, "AVIF can carry EXIF but our parser does not read it" is
 * a capability statement, and "the GPS block exists but is corrupt" is a
 * diagnostics signal. Flattening the three into one value would make the
 * future Details UI and the AI indexer lie about the user's library, so
 * every metadata field carries one of these four states.
 */
enum class MetadataAvailability {
    /** The metadata block exists in this file and was parsed successfully. */
    PRESENT,

    /** The file (or format) genuinely carries no such metadata block. */
    ABSENT,

    /**
     * The format may carry this metadata, but toadGALLERY's pinned parser
     * set does not read it (yet) --- a capability statement, not a claim
     * about this specific file's bytes. See [MetadataFormatMatrix].
     */
    UNSUPPORTED,

    /** The block exists but could not be parsed --- corrupt or malformed. */
    UNREADABLE,
}

/**
 * One metadata field plus the honest reason it is (not) there:
 * [availability] is the audited four-way state, [value] the parsed payload
 * (non-null only for [MetadataAvailability.PRESENT]).
 *
 * A small wrapper rather than a nullable property, so a snapshot field can
 * say "GPS is unreadable" --- which a `GpsCoordinates?` cannot --- while
 * still handing the UI a typed value on the happy path. Construct through
 * the companion helpers ([present], [absent], [unsupported], [unreadable]);
 * they keep the availability/value pairing impossible to get wrong at the
 * (many) construction sites the metadata layer will grow.
 */
data class MetadataValue<T>(
    val availability: MetadataAvailability,
    val value: T? = null,
) {
    companion object {
        /**
         * A successfully parsed field. [value] must be non-null --- a
         * PRESENT wrapper with a null payload is a contradiction the
         * metadata layer never produces.
         */
        fun <T : Any> present(value: T): MetadataValue<T> =
            MetadataValue(MetadataAvailability.PRESENT, value)

        /** The format/file carries no such block. */
        fun <T> absent(): MetadataValue<T> =
            MetadataValue(MetadataAvailability.ABSENT)

        /** Our pinned parser set does not read this block for this format. */
        fun <T> unsupported(): MetadataValue<T> =
            MetadataValue(MetadataAvailability.UNSUPPORTED)

        /** The block exists but parsing failed. */
        fun <T> unreadable(): MetadataValue<T> =
            MetadataValue(MetadataAvailability.UNREADABLE)
    }
}
