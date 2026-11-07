package io.github.dukeoftoad.gallery.edit.contract

/**
 * Editor identity + version negotiation (spec sections 1 and 2): the
 * gallery-side decision logic for "can I launch this editor?".
 *
 * Identity is matched EXACTLY on the contract application ID --- never
 * implicit resolution, so a spoofed editor cannot receive URI grants. The
 * one documented exception is the exact `.debug` suffix: the dogfood builds
 * of BOTH apps carry Gradle's standard `applicationIdSuffix = ".debug"`, and
 * the round-trip prototype must resolve on the same device where both debug
 * APKs are installed. A `.debug` package is still first-party-shaped; the
 * grant it receives is read-only and its result is a fresh copy, so the
 * widened match costs nothing the exact match did not already risk.
 */
object EditorIdentity {

    /** The only accepted suffixes beyond the exact release application ID. */
    private val ACCEPTED_SUFFIXES: List<String> = listOf("", ".debug")

    /** True iff [packageName] is the contract editor (release or `.debug` dogfood build). */
    fun matches(packageName: String?): Boolean = matches(packageName, ownPackage = null)

    /**
     * r59 (the unified-app round): [ownPackage] extends the match to the
     * CALLING app itself, so the unified build (`io.github.dukeoftoad.gallery.unified`,
     * which embeds the editor's activities in-process) resolves its own
     * contract surfaces. The grant security story is unchanged --- a
     * self-grant to the caller's own package carries no risk the exact
     * package match did not already carry (the result is still a fresh
     * save-copy; the editor code is the same first-party source). Every
     * other package keeps matching ONLY the contract id + `.debug`.
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
     * The additive-versioning rule (spec header): the gallery accepts any
     * editor declaring `>= CONTRACT_VERSION_MIN` --- a NEWER editor may add
     * optional fields but must still honour v1 requests, so its declared
     * version is passed through verbatim as the negotiated version. A
     * declared version below the minimum (including non-positive garbage)
     * is refused.
     */
    fun negotiate(declaredVersion: Int?): LaunchDecision = when {
        declaredVersion == null -> LaunchDecision.MissingVersionMetadata
        declaredVersion >= EditContract.CONTRACT_VERSION_MIN ->
            LaunchDecision.Ready(declaredVersion)
        else -> LaunchDecision.Outdated(declaredVersion)
    }
}
