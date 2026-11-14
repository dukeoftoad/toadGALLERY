package com.toadgallery.editor.contract

/**
 * Editor identity + version negotiation: the gallery-side decision logic for
 * "can I launch this editor?". Identity matches EXACTLY on the contract
 * application ID — never implicit resolution, so a spoofed editor cannot
 * receive URI grants. The one exception is the exact `.debug` suffix (both
 * apps' dogfood builds carry `applicationIdSuffix = ".debug"`); the grant is
 * read-only and the result a fresh copy, so the widened match costs nothing the
 * exact match did not already risk.
 */
object EditorIdentity {

    /** The only accepted suffixes beyond the exact release application ID. */
    private val ACCEPTED_SUFFIXES: List<String> = listOf("", ".debug")

    /** True iff [packageName] is the contract editor (release or `.debug` dogfood build). */
    fun matches(packageName: String?): Boolean = matches(packageName, ownPackage = null)

    /**
     * [ownPackage] extends the match to the CALLING app itself (the unified
     * build embeds the editor's activities in-process and resolves its own
     * contract surfaces). A self-grant carries no risk the exact match did not
     * (fresh save-copy, same first-party source); every other package still
     * matches ONLY the contract id + `.debug`.
     */
    fun matches(packageName: String?, ownPackage: String?): Boolean =
        packageName != null && (
            ACCEPTED_SUFFIXES.any { suffix ->
                packageName == EditContract.EDITOR_APPLICATION_ID + suffix
            } || (ownPackage != null && packageName == ownPackage)
        )

    /** Every package name [matches] accepts, in release-first order. */
    fun acceptedPackageNames(): List<String> =
        ACCEPTED_SUFFIXES.map { suffix -> EditContract.EDITOR_APPLICATION_ID + suffix }
}

/**
 * The gallery's launch decision after reading the editor's declared
 * contract version (its `org.toadgallery.contract.EDIT` meta-data).
 */
sealed interface LaunchDecision {

    /** The editor declares an acceptable version; launch with [negotiatedVersion]. */
    data class Ready(val negotiatedVersion: Int) : LaunchDecision

    /**
     * The editor declares a version below [EditContract.CONTRACT_VERSION_MIN]:
     * refuse the launch and offer the update path (spec section 1).
     */
    data class Outdated(val declaredVersion: Int) : LaunchDecision

    /**
     * The editor's manifest carries no contract meta-data (or an unreadable
     * one): treat exactly like outdated --- refuse, with the honest reason.
     */
    data object MissingVersionMetadata : LaunchDecision
}

object EditorVersionNegotiator {

    /**
     * The additive-versioning rule: accept any editor declaring
     * `>= CONTRACT_VERSION_MIN` — a newer editor may add optional fields but must
     * still honour v1 requests, so its declared version passes through verbatim
     * as the negotiated version. Anything below the minimum (including garbage)
     * is refused.
     */
    fun negotiate(declaredVersion: Int?): LaunchDecision = when {
        declaredVersion == null -> LaunchDecision.MissingVersionMetadata
        declaredVersion >= EditContract.CONTRACT_VERSION_MIN ->
            LaunchDecision.Ready(declaredVersion)
        else -> LaunchDecision.Outdated(declaredVersion)
    }
}
