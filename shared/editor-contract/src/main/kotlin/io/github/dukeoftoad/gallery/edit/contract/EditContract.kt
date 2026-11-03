package io.github.dukeoftoad.gallery.edit.contract

/**
 * The versioned toadGALLERY Gallery <-> Edit integration contract, v1
 * (ADR-0002 / ADR-0007; spec: `docs/architecture/editor-contract.md`).
 *
 * Every constant here is FROZEN for v1: the gallery and the editor ship
 * independently, so a constant renamed in one app breaks the other at the
 * user's tap. v2 may only ADD optional fields or bump
 * [CONTRACT_VERSION_MIN] explicitly.
 *
 * The module is pure JVM (see the module build file for why): no Android
 * types. Callers adapt `android.net.Uri` / `android.os.Bundle` onto the
 * String / Int primitives below.
 *
 * Absolute filesystem paths MUST NOT appear anywhere in the contract ---
 * launch and result carry `content://` URIs only (guarded by
 * [EditRequestValidator]).
 */
object EditContract {

    // ---- Identity & discovery (spec section 1) ----

    /** The contract action the editor's launch activity is filtered on. */
    const val ACTION_EDIT: String = "org.toadgallery.action.EDIT"

    /** Editor-manifest meta-data key carrying the editor's contract version. */
    const val METADATA_CONTRACT_VERSION: String = "org.toadgallery.contract.EDIT"

    /** The editor's release application ID. */
    const val EDITOR_APPLICATION_ID: String = "io.github.dukeoftoad.gallery.edit"

    /** The contract version this module implements. */
    const val CONTRACT_VERSION: Int = 1

    /** Gallery-side minimum accepted editor contract version. */
    const val CONTRACT_VERSION_MIN: Int = 1

    // ---- Launch extras (spec section 2) ----

    const val EXTRA_CONTRACT_VERSION: String = "org.toadgallery.extra.CONTRACT_VERSION"
    const val EXTRA_SOURCE_URI: String = "org.toadgallery.extra.SOURCE_URI"
    const val EXTRA_OPERATION_HINT: String = "org.toadgallery.extra.OPERATION_HINT"
    const val EXTRA_EXPORT_MODE: String = "org.toadgallery.extra.EXPORT_MODE"
    const val EXTRA_METADATA_POLICY: String = "org.toadgallery.extra.METADATA_POLICY"

    // ---- Result extras (spec section 3) ----

    const val EXTRA_OUTPUT_URI: String = "org.toadgallery.extra.OUTPUT_URI"
    const val EXTRA_ERROR_CODE: String = "org.toadgallery.extra.ERROR_CODE"
    const val EXTRA_ERROR_MESSAGE: String = "org.toadgallery.extra.ERROR_MESSAGE"

    /**
     * The metadata policy the editor ACTUALLY applied to the saved copy,
     * echoed back on RESULT_OK so the gallery can show honest narration
     * (e.g. "location removed"). Additive v1 field: editors that predate it
     * simply omit it, and [EditResultParser] tolerates the absence.
     */
    const val EXTRA_METADATA_POLICY_APPLIED: String = "org.toadgallery.extra.METADATA_POLICY_APPLIED"

    // ---- Activity result codes (spec section 3) ----

    /** Mirrors `Activity.RESULT_OK`. */
    const val RESULT_OK: Int = -1

    /** Mirrors `Activity.RESULT_CANCELED`. */
    const val RESULT_CANCELED: Int = 0

    /**
     * Structured-failure result code. `Activity.RESULT_FIRST_USER` (1) is
     * reserved for the contract's own future use; the first allocation is
     * 2 so an accidental `RESULT_FIRST_USER` reply can never be mistaken
     * for a success or a cancel.
     */
    const val RESULT_ERROR: Int = 2

    // ---- Export mode (spec section 2) ----

    /** The ONLY export mode in v1: the editor saves a new copy; the original is never modified. */
    const val EXPORT_MODE_SAVE_COPY: String = "save_copy"

    // ---- Metadata policy (spec section 2) ----

    const val METADATA_POLICY_PRESERVE: String = "preserve"
    const val METADATA_POLICY_STRIP_LOCATION: String = "strip_location"
    const val METADATA_POLICY_STRIP_ALL: String = "strip_all"

    // ---- Error codes (spec section 3) ----

    const val ERROR_VERSION: String = "ERROR_VERSION"
    const val ERROR_UNSUPPORTED: String = "ERROR_UNSUPPORTED"
    const val ERROR_IO: String = "ERROR_IO"
    const val ERROR_PERMISSIONS: String = "ERROR_PERMISSIONS"
    const val ERROR_INTERNAL: String = "ERROR_INTERNAL"

    /** Every v1 error code, for parser exhaustiveness checks. */
    val ERROR_CODES: Set<String> = setOf(
        ERROR_VERSION, ERROR_UNSUPPORTED, ERROR_IO, ERROR_PERMISSIONS, ERROR_INTERNAL,
    )

    /** Operation hints (spec section 2): requested entry tool, UI pre-selection only. */
    val OPERATION_HINTS: Set<String> = setOf("crop", "rotate", "resize", "draw", "adjust", "filter")

    /** The URI scheme every contract-carried media URI must use. */
    const val CONTENT_SCHEME: String = "content"
}
