package io.github.dukeoftoad.gallery.ml.engine

import java.io.InputStream

/**
 * The r28 offline model-pack seam (Workstream 8, Phase 5 hardening)
 * --- ADR-0020's delivery decision, second half: "follow Ente's model
 * --- after install, the app downloads the models itself, on demand
 * --- **while CI retains the ability to ship an offline pack**."
 *
 * A build-time-bundled model pack puts the pinned asset bytes into the
 * APK's `assets/models/<fileName>` (staged by the
 * `modelpack-apk.yml` workflow through the Gradle
 * `-PwithBundledModels` assets srcDir; a normal build ships NO such
 * assets), so a device provisioned from that build never opens a
 * socket at all. On this build the [ModelProvisioner]'s transport
 * prefers the bundled bytes; on every other build the seam reports
 * "not bundled" and the transport is exactly the r22
 * [HttpModelDownloader] one.
 *
 * Security posture (identical to the network path, by construction):
 *  - the asset lookup runs through [requireSafeModelFileName] FIRST
 *    (the same structural guard every HTTP fetch passes), so a hostile
 *    manifest entry can no more reach an arbitrary asset path than it
 *    could reach an arbitrary URL;
 *  - the provisioner's verify-then-stage-then-commit discipline
 *    applies unchanged: a bundled pack whose bytes do not match the
 *    CURRENT pin's size + SHA-256 fails
 *    [ModelProvisioner.ProvisionException.VerificationFailed] and
 *    installs NOTHING --- the offline path is verified exactly like
 *    the network path (the HTTP content-length pre-check is the
 *    network transport's own fast-fail; [ModelVerifier] is the real
 *    gate either way);
 *  - no egress surface is added: when the bundle has the asset, the
 *    network transport is never consulted (`EgressSolePathTest` pins
 *    that models.ente.com remains the only host the app can reach at
 *    all); when it does not, the fetch is the ordinary r22 one.
 */
fun interface BundledModelAssets {

    /**
     * Opens the bundled model asset named [fileName], or `null` when
     * this build ships no such asset. Implementations must not buffer
     * the whole asset --- return a streaming [InputStream] (an Android
     * `AssetManager.open` stream is compressed-on-demand and streams).
     */
    fun open(fileName: String): InputStream?
}

/**
 * The [ModelDownloader] of a build that may carry a bundled model
 * pack: BUNDLED bytes first, [network] as the fallback. The
 * [requireSafeModelFileName] guard runs before the asset lookup, so
 * every byte the provisioner ever stages --- from assets or from the
 * wire --- passed the same choke point.
 */
class BundledFirstModelDownloader(
    private val bundled: BundledModelAssets,
    private val network: ModelDownloader,
) : ModelDownloader {

    override fun open(spec: ModelManifest.Model): InputStream =
        bundled.open(requireSafeModelFileName(spec.fileName)) ?: network.open(spec)
}
