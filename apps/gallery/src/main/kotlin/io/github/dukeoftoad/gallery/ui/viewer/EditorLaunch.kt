package io.github.dukeoftoad.gallery.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.dukeoftoad.gallery.edit.contract.EditContract
import io.github.dukeoftoad.gallery.edit.contract.EditLaunchExtrasBuilder
import io.github.dukeoftoad.gallery.edit.contract.EditRequest
import io.github.dukeoftoad.gallery.edit.contract.EditResultParser
import io.github.dukeoftoad.gallery.edit.contract.EditorIdentity
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
    fun resolve(candidates: List<EditorCandidate>): EditorResolution {
        val matching = candidates.filter { EditorIdentity.matches(it.packageName) }
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
 * The r42 media-kind-aware resolution: the SAME pure rule over a
 * kind-specific query (the image wildcard for the v1 surface, the video
 * wildcard for the v2 video surface) --- the candidate's own intent-filter
 * decides which activities answer, and the negotiator still governs the
 * version.
 */
fun resolveEditor(context: Context, mimeType: String): EditorResolution {
    val pm = context.packageManager
    // The query mirrors the editor's intent-filter shape: the contract
    // action + the kind's mime type (the filter's data specs are mime-only).
    val query = Intent(EditContract.ACTION_EDIT)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .setType(mimeType)
    val candidates = pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { info ->
            val packageName = info.activityInfo.packageName
            if (!EditorIdentity.matches(packageName)) return@mapNotNull null
            val metaData = info.activityInfo.metaData
            val declaredVersion = if (metaData != null &&
                metaData.containsKey(EditContract.METADATA_CONTRACT_VERSION)
            ) {
                metaData.getInt(EditContract.METADATA_CONTRACT_VERSION)
            } else {
                null
            }
            EditorCandidate(
                packageName = packageName,
                activityClassName = info.activityInfo.name,
                declaredVersion = declaredVersion,
                enabled = pm.getApplicationEnabledSetting(packageName) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            )
        }
    val resolution = EditorResolver.resolve(candidates)
    if (resolution is EditorResolution.NotInstalled) {
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
