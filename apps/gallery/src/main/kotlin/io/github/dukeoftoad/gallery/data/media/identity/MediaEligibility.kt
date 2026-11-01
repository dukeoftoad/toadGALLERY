package io.github.dukeoftoad.gallery.data.media.identity

/**
 * Whether a media item may enter the public (non-vault) index and, by
 * extension, any AI pipeline built on top of it (Phase 4 contract,
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`).
 *
 * This is the type-level vault boundary the audit demands: the only way to
 * obtain an [EligiblePublicMedia] --- the argument every downstream
 * read/decode/index API accepts --- is through [asEligible], which returns
 * `null` for every state except [State.PUBLIC_INDEXABLE]. A vault entry,
 * a trashed row or a permission-revoked item therefore CANNOT be passed to
 * an indexing call by accident; the compiler enforces what review would
 * otherwise have to.
 */
data class MediaEligibility(
    /** The classification; see [State]. */
    val state: State,
    /** Optional human-readable reason (never persisted; diagnostics only). */
    val reason: String? = null,
) {
    /** The six eligibility states of the Phase 4 contract. */
    enum class State {
        /** Public MediaStore row, readable, supported: may be indexed. */
        PUBLIC_INDEXABLE,

        /** Lives inside the encrypted vault: never indexed, never decoded. */
        VAULT_EXCLUDED,

        /** In the trash / pending deletion: excluded until restored. */
        TRASHED,

        /** Media permission no longer covers this item: excluded. */
        PERMISSION_REVOKED,

        /** Format outside the declared capability matrix: excluded. */
        UNSUPPORTED,

        /** Unreadable/corrupt content: excluded, surfaced as a failure. */
        CORRUPT,
    }

    val isPublicIndexable: Boolean
        get() = state == State.PUBLIC_INDEXABLE

    /**
     * The gateway to [EligiblePublicMedia]: vouches for [identity] and
     * returns the wrapper only when this eligibility is
     * [State.PUBLIC_INDEXABLE] --- `null` for every other state, so a vault
     * entry, a trashed row or a permission-revoked item cannot be smuggled
     * past the gate. Every AI-side API takes the returned wrapper --- never
     * a bare [MediaIdentity] or Uri --- so vault and revoked media are
     * unrepresentable at the call site.
     */
    fun asEligible(identity: MediaIdentity): EligiblePublicMedia? =
        if (isPublicIndexable) EligiblePublicMedia(identity) else null

    companion object {
        val PUBLIC_INDEXABLE = MediaEligibility(State.PUBLIC_INDEXABLE)
        val VAULT_EXCLUDED = MediaEligibility(State.VAULT_EXCLUDED)
        val TRASHED = MediaEligibility(State.TRASHED)
        val PERMISSION_REVOKED = MediaEligibility(State.PERMISSION_REVOKED)

        /** [State.UNSUPPORTED] with the offending format as the reason. */
        fun unsupported(format: String) = MediaEligibility(State.UNSUPPORTED, format)

        /** [State.CORRUPT] with the failing stage as the reason. */
        fun corrupt(reason: String) = MediaEligibility(State.CORRUPT, reason)
    }
}

/**
 * A [MediaIdentity] PROVEN to be eligible for the public index (see
 * [MediaEligibility.asEligible]). Constructing this directly from
 * arbitrary data is deliberately impossible outside the eligibility
 * gateway: downstream consumers (metadata enrichment, the future
 * Ente-derived ML pipeline) accept only this type, which is what keeps
 * vault bytes, vault faces and vault embeddings structurally out of every
 * index (ADR-0010 consent boundary + the audit's vault-leakage gate).
 *
 * Inline so the gate costs nothing at runtime; the safety is purely in
 * the type system at compile time.
 */
@JvmInline
value class EligiblePublicMedia internal constructor(
    /** The identity of the eligible item (with its access Uri). */
    val identity: MediaIdentity,
)
