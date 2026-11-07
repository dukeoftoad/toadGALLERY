package io.github.dukeoftoad.gallery.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import io.github.dukeoftoad.gallery.edit.contract.EditContract
import io.github.dukeoftoad.gallery.edit.contract.EditLaunchExtrasBuilder
import io.github.dukeoftoad.gallery.edit.contract.EditRequest
import io.github.dukeoftoad.gallery.edit.contract.EditResultParser
import io.github.dukeoftoad.gallery.edit.contract.EditorIdentity
import io.github.dukeoftoad.gallery.edit.contract.EditorVersionMetadata
import io.github.dukeoftoad.gallery.edit.contract.EditorVersionNegotiator
import io.github.dukeoftoad.gallery.edit.contract.LaunchDecision
import io.github.dukeoftoad.gallery.edit.contract.MediaKind
import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy
import io.github.dukeoftoad.gallery.edit.contract.OperationHint
import io.github.dukeoftoad.gallery.edit.contract.RawResult

/**
 * The gallery half of the Gallery <-> Edit contract (r29, Issue #14 gate;
 * spec `docs/architecture/editor-contract.md`; ADR-0002 / ADR-0007).
 *
 * Resolution is EXPLICIT (never implicit `ACTION_EDIT` resolution): the
 * gallery queries the contract action, keeps only activities whose package
 * matches the contract application ID exactly (`.debug` dogfood suffix
 * included), reads the editor's declared contract version from its
 * manifest meta-data, and negotiates through the SHARED
 * [EditorVersionNegotiator] --- the same pure logic the editor validates
 * itself with on the other side.
 *
 * The four resolution states map to the roadmap's Phase 6 task list:
 * not installed (offer the third-party-editor fallback), outdated (refuse
 * the launch, offer the update), disabled/quiet (explanatory message), and
 * ready (launch with a read-only grant scoped to the one source URI).
 *
 * The r46 discovery hardening (the field round where BOTH current APKs
 * were installed and the gallery still narrated the outdated refusal):
 * the query now carries `GET_META_DATA` (the documented meta-data
 * population flag --- a query-result `ActivityInfo` without it can read
 * as "no marker" and map to `Outdated(null)`, the exact field symptom);
 * the declared version is resolved through the shared
 * activity-then-application precedence ([EditorVersionMetadata.pick])
 * with a direct `getActivityInfo` re-fetch when the query bundle came
 * back unpopulated; a zero-hit wildcard query retries with the editor's
 * own concrete mimes (a wildcard intent never matches a filter that
 * declares only specific subtypes); and a final package-probe fallback
 * bypasses intent-filter matching entirely --- identity is known exactly,
 * so the resolver can always ask for the package directly, which kills
 * the whole class of filter-shape-vs-query-shape mismatches and the
 * meta-data population gap in one honest fallback.
 * [EditorResolver.resolve] stays the pure, JVM-tested rule; only the
 * Android adapters grew.
 */
sealed interface EditorResolution {
    /** No contract editor installed; the caller offers the fallback path. */
    data object NotInstalled : EditorResolution

    /** The editor declares a version below [EditContract.CONTRACT_VERSION_MIN]: refuse. */
    data class Outdated(val declaredVersion: Int?) : EditorResolution

    /** The editor is installed but disabled (Settings-disabled app). */
    data object Disabled : EditorResolution

    /** Launch: the exact activity class + the negotiated contract version. */
    data class Ready(
        val packageName: String,
        val activityClassName: String,
        val negotiatedVersion: Int,
    ) : EditorResolution
}

/** The pure candidate record the resolver consumes (Android adapters map into it). */
data class EditorCandidate(
    val packageName: String,
    val activityClassName: String,
    val declaredVersion: Int?,
    val enabled: Boolean,
)

object EditorResolver {

    /**
     * The pure resolution rule, JVM-tested: identity match first, then the
     * enabled flag, then the version negotiation. The first matching
     * candidate wins (a device never has both the release and the `.debug`
     * editor resolving from one query in practice, and the first hit is
     * the launcher's own order).
     */
    fun resolve(candidates: List<EditorCandidate>, ownPackage: String? = null): EditorResolution {
        val matching = candidates.filter { EditorIdentity.matches(it.packageName, ownPackage) }
        val first = matching.firstOrNull() ?: return EditorResolution.NotInstalled
        if (!first.enabled) return EditorResolution.Disabled
        return when (val decision = EditorVersionNegotiator.negotiate(first.declaredVersion)) {
            is LaunchDecision.Ready -> EditorResolution.Ready(
                packageName = first.packageName,
                activityClassName = first.activityClassName,
                negotiatedVersion = decision.negotiatedVersion,
            )
            is LaunchDecision.Outdated -> EditorResolution.Outdated(first.declaredVersion)
            LaunchDecision.MissingVersionMetadata -> EditorResolution.Outdated(null)
        }
    }
}

/** The package-visible resolution (the thin Android adapter over [EditorResolver]). */
fun resolveEditor(context: Context): EditorResolution =
    resolveEditor(context, EDITOR_IMAGE_MIME)

/** The v1 image query mime (the editor image surface's filter shape). */
internal const val EDITOR_IMAGE_MIME: String = "image/*"

/**
 * The r42 video query mime: resolves only activities that ANSWER video
 * (the additive contract v2 surface; the editor's video activity). An
 * image-era editor contributes no hit here, so the caller narrates the
 * honest "cannot edit video" path instead of mis-launching.
 */
internal const val EDITOR_VIDEO_MIME: String = "video/*"

/**
 * The r46 concrete-mime retry lists, mirroring apps/edit's OWN manifest
 * intent-filters exactly (apps/edit/src/main/AndroidManifest.xml): the
 * image surface filters exactly the four image subtypes below, the video
 * surface exactly the three video subtypes. PackageManager mime matching
 * is asymmetric --- an intent carrying a WILDCARD image type does NOT
 * match a filter that declares only specific subtypes (a filter's own
 * wildcard covers the intent; the reverse never holds) --- so when a
 * wildcard query returns zero contract hits the resolver retries with
 * exactly these shapes before falling back to the package probe.
 */
internal val EDITOR_IMAGE_CONCRETE_MIMES: List<String> = listOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
)

internal val EDITOR_VIDEO_CONCRETE_MIMES: List<String> = listOf(
    "video/mp4",
    "video/quicktime",
    "video/3gpp",
)

/**
 * Reads the contract version out of one meta-data bundle: `null` when the
 * bundle is absent or does not carry the marker key (an honest absence;
 * `Bundle.getInt` alone would manufacture a lying 0).
 */
private fun bundleContractVersion(metaData: Bundle?): Int? =
    metaData
        ?.takeIf { it.containsKey(EditContract.METADATA_CONTRACT_VERSION) }
        ?.getInt(EditContract.METADATA_CONTRACT_VERSION)

/**
 * The application-level contract marker read: `getApplicationInfo` with
 * the documented `GET_META_DATA` flag reliably populates the application
 * bundle even where the query path did not. Every failure (a package
 * truly absent, an unreadable bundle) is the honest null --- never a
 * guessed value.
 */
private fun applicationContractVersion(pm: PackageManager, packageName: String): Int? =
    try {
        bundleContractVersion(
            pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData,
        )
    } catch (_: Exception) {
        null
    }

/**
 * The r46 declared-version read for one query candidate, via the shared
 * precedence rule: the ACTIVITY-level marker wins (the r29 declaration,
 * the only marker older editors carry); when the query-result bundle is
 * unpopulated --- not guaranteed across Android versions and query flags
 * --- the ONE activity is re-fetched directly with `GET_META_DATA`, the
 * documented population flag the query omitted; when even that reads
 * null, the r46 APPLICATION-level marker answers. `null` only when both
 * levels are honestly absent, which the negotiator refuses as before.
 */
private fun declaredContractVersion(
    pm: PackageManager,
    info: ResolveInfo,
    packageName: String,
    activityClassName: String,
): Int? {
    val activityLevel = bundleContractVersion(info.activityInfo.metaData)
        ?: try {
            bundleContractVersion(
                pm.getActivityInfo(
                    ComponentName(packageName, activityClassName),
                    PackageManager.GET_META_DATA,
                ).metaData,
            )
        } catch (_: Exception) {
            // Unreadable is unreadable: the honest null, never a guess.
            null
        }
    if (activityLevel != null) {
        return EditorVersionMetadata.pick(activityLevel = activityLevel, applicationLevel = null)
    }
    return EditorVersionMetadata.pick(
        activityLevel = null,
        applicationLevel = applicationContractVersion(pm, packageName),
    )
}

/**
 * One contract query: the contract action + the given mime type, with
 * `MATCH_DEFAULT_ONLY or GET_META_DATA` --- the r46 round adds the
 * documented meta-data flag so the result activities' bundles are
 * populated in the first place. Candidates are identity-filtered to the
 * contract editor exactly as before; only the version read hardened.
 */
private fun queryEditorCandidates(pm: PackageManager, mimeType: String, ownPackage: String? = null): List<EditorCandidate> =
    pm.queryIntentActivities(
        Intent(EditContract.ACTION_EDIT)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType(mimeType),
        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA,
    ).mapNotNull { info ->
        val packageName = info.activityInfo.packageName
        if (!EditorIdentity.matches(packageName, ownPackage)) return@mapNotNull null
        EditorCandidate(
            packageName = packageName,
            activityClassName = info.activityInfo.name,
            declaredVersion = declaredContractVersion(
                pm = pm,
                info = info,
                packageName = packageName,
                activityClassName = info.activityInfo.name,
            ),
            enabled = pm.getApplicationEnabledSetting(packageName) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        )
    }

/**
 * The r46 package-probe fallback, the resolver's last resort: identity is
 * known EXACTLY (release id + the `.debug` dogfood suffix), so instead of
 * trusting intent-filter matching (whose shapes --- specific mimes vs the
 * gallery's wildcard query, visibility, meta-data population --- caused
 * the field bug) the resolver asks for the editor's package directly and
 * synthesizes candidates from its own manifest. Only activities that
 * carry the activity-level contract marker (the contract declaration per
 * spec section 1) and are exported (a probe candidate must be launchable)
 * become candidates; each version resolves through the SAME
 * [EditorVersionMetadata.pick] precedence with the application-level
 * marker as the fallback. An installed-but-disabled editor contributes no
 * candidates here --- the Disabled refinement below narrates that state.
 * The requested mime only biases the ORDER of the synthetic candidates:
 * the r42 rule made the >= 2 declaration the video surface ("the
 * gallery's video query negotiates v2 against exactly this activity"), so
 * a video probe orders the higher declaration first; everything else
 * keeps manifest order, where the v1 image surface declares first.
 */
private fun probeEditorCandidates(
    pm: PackageManager,
    mimeType: String,
    ownPackage: String? = null,
): List<EditorCandidate> {
    val candidates = mutableListOf<EditorCandidate>()
    val probePackages = EditorIdentity.acceptedPackageNames() +
        (ownPackage?.takeIf { it !in EditorIdentity.acceptedPackageNames() }?.let { listOf(it) } ?: emptyList())
    for (packageName in probePackages) {
        val packageInfo = try {
            pm.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA,
            )
        } catch (_: Exception) {
            // Package not present (or invisible) --- keep looking.
            continue
        }
        if (packageInfo.applicationInfo?.enabled == false) {
            // Installed but app-disabled: not a launchable candidate.
            continue
        }
        val applicationLevel = bundleContractVersion(packageInfo.applicationInfo?.metaData)
        for (activity in packageInfo.activities.orEmpty()) {
            val activityLevel = bundleContractVersion(activity.metaData) ?: continue
            if (!activity.exported) continue
            candidates.add(
                EditorCandidate(
                    packageName = packageName,
                    activityClassName = activity.name,
                    declaredVersion = EditorVersionMetadata.pick(
                        activityLevel = activityLevel,
                        applicationLevel = applicationLevel,
                    ),
                    enabled = true,
                ),
            )
        }
    }
    return if (mimeType.startsWith("video/")) {
        candidates.sortedByDescending { it.declaredVersion ?: Int.MIN_VALUE }
    } else {
        candidates
    }
}

/**
 * The r42 media-kind-aware resolution: the SAME pure rule over a
 * kind-specific query (the image wildcard for the v1 surface, the video
 * wildcard for the v2 video surface) --- the candidate's own intent-filter
 * decides which activities answer, and the negotiator still governs the
 * version.
 *
 * The r46 hardening layers three fallbacks on the query (the pure
 * [EditorResolver.resolve] rule is untouched):
 * 1. the concrete-mime retry --- a zero-hit wildcard query retries with
 *    the editor's known concrete mimes (a wildcard intent does not match
 *    a filter declaring only specific subtypes);
 * 2. the package-probe fallback --- still nothing, and the identity is
 *    installed and enabled, the resolver synthesizes candidates straight
 *    from the editor's package (killing the whole class of filter-shape
 *    vs query-shape mismatches AND the meta-data population gap in one
 *    honest fallback);
 * 3. the pre-existing Disabled refinement, unchanged and last: an
 *    installed-but-disabled editor is the Disabled state.
 */
fun resolveEditor(context: Context, mimeType: String): EditorResolution {
    val pm = context.packageManager
    val candidates = queryEditorCandidates(pm, mimeType, ownPackage = context.packageName)
    val effective = if (candidates.isEmpty() && mimeType.endsWith("/*")) {
        // The concrete-mime retry: exactly the shapes apps/edit's own
        // manifest filters declare (see the constants' KDoc).
        val concreteMimes = if (mimeType.startsWith("video/")) {
            EDITOR_VIDEO_CONCRETE_MIMES
        } else {
            EDITOR_IMAGE_CONCRETE_MIMES
        }
        var retried = candidates
        for (concreteMime in concreteMimes) {
            retried = queryEditorCandidates(pm, concreteMime, ownPackage = context.packageName)
            if (retried.isNotEmpty()) break
        }
        retried
    } else {
        candidates
    }
    val resolution = EditorResolver.resolve(effective, ownPackage = context.packageName)
    if (resolution !is EditorResolution.NotInstalled) return resolution
    val probed = EditorResolver.resolve(probeEditorCandidates(pm, mimeType, ownPackage = context.packageName))
    if (probed !is EditorResolution.NotInstalled) return probed
    // A disabled app contributes no query hits, so "not installed" from
    // the query must be refined: an installed-but-disabled editor is the
    // Disabled state (the contract's explanatory-message path).
    for (packageName in EditorIdentity.acceptedPackageNames()) {
        try {
            if (pm.getApplicationEnabledSetting(packageName) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                return EditorResolution.Disabled
            }
        } catch (_: Exception) {
            // Package not present --- keep looking.
        }
    }
    return resolution
}

/** The v1 metadata policy the gallery sends: preserve (the contract default; never silently strips). */
internal val EDITOR_METADATA_POLICY: MetadataPolicy = MetadataPolicy.PRESERVE

/** The v1 operation hint: the prototype's session opens on the adjustment suite. */
internal val EDITOR_OPERATION_HINT: OperationHint = OperationHint.ADJUST

/**
 * Builds the launch intent for a ready editor: the contract extras (typed
 * per the spec --- the version extra is an Int, the source URI rides as a
 * Parcelable Uri), the ClipData source item, and the read-only grant
 * scoped to that one URI. Absolute filesystem paths never appear anywhere.
 * The r42 media kind rides through the shared builder: IMAGE launches stay
 * byte-identical to v1 (no extra); VIDEO launches carry
 * [EditContract.EXTRA_MEDIA_KIND] (the additive-versioning rule).
 */
fun buildEditorIntent(
    source: Uri,
    ready: EditorResolution.Ready,
    metadataPolicy: MetadataPolicy = EDITOR_METADATA_POLICY,
    operationHint: OperationHint = EDITOR_OPERATION_HINT,
    mediaKind: MediaKind = MediaKind.IMAGE,
): Intent {
    val request = EditRequest(
        sourceUri = source.toString(),
        contractVersion = ready.negotiatedVersion,
        exportMode = EditContract.EXPORT_MODE_SAVE_COPY,
        metadataPolicy = metadataPolicy,
        operationHint = operationHint,
        mediaKind = mediaKind,
    )
    val intent = Intent(EditContract.ACTION_EDIT)
        .setClassName(ready.packageName, ready.activityClassName)
    for ((key, value) in EditLaunchExtrasBuilder.build(request)) {
        if (key == EditContract.EXTRA_SOURCE_URI) {
            // Spec section 2: EXTRA_SOURCE_URI is a Uri (the grant rides the
            // ClipData below); the string-shaped builder value becomes the
            // Parcelable the editor reads back.
            intent.putExtra(key, Uri.parse(value as String))
        } else {
            when (value) {
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
    }
    intent.clipData = android.content.ClipData.newRawUri(EditContract.EXTRA_SOURCE_URI, source)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
}

/**
 * The fallback path for "editor not installed" (spec section 1): the plain
 * third-party ACTION_EDIT chooser with the same read-only grant. No editor
 * choice is made for the user --- the chooser does exactly that. The r42
 * kind parameter keeps the fallback honest (a video item offers video
 * editors, an image item image editors).
 */
fun buildFallbackEditIntent(source: Uri, mimeType: String = EDITOR_IMAGE_MIME): Intent =
    Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(source, mimeType)
        clipData = android.content.ClipData.newRawUri(EditContract.EXTRA_SOURCE_URI, source)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** Launches the chooser if any third-party editor exists; false when none answers. */
fun launchFallbackEditor(context: Context, source: Uri, mimeType: String = EDITOR_IMAGE_MIME): Boolean =
    try {
        context.startActivity(Intent.createChooser(buildFallbackEditIntent(source, mimeType), null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/** The gallery-side parse of an editor reply (the thin adapter over the shared parser). */
fun parseEditResult(resultCode: Int, data: Intent?) =
    EditResultParser.parse(
        RawResult(
            resultCode = resultCode,
            outputUri = data?.getStringExtra(EditContract.EXTRA_OUTPUT_URI),
            errorCode = data?.getStringExtra(EditContract.EXTRA_ERROR_CODE),
            errorMessage = data?.getStringExtra(EditContract.EXTRA_ERROR_MESSAGE),
            metadataPolicyApplied = data?.getStringExtra(EditContract.EXTRA_METADATA_POLICY_APPLIED),
        ),
    )
