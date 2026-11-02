package io.github.dukeoftoad.gallery.ml.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.CancellationException

/**
 * Downloads, verifies and atomically installs the pinned models of
 * [ModelManifest] into an app-private directory --- the Ente asset-manager
 * flow, reimplemented in Kotlin (upstream reference:
 * `engine/crates/ente-assets` + `rust/crates/ml/src/assets.rs`).
 *
 * Contract (audit workstream 3, "Model pack"):
 * - download to a temp file in the SAME directory, verify expected size and
 *   SHA-256, then atomically rename into place; an interrupted download
 *   leaves NO half-installed state and never touches an already-installed
 *   model;
 * - the install receipt (a flat properties file) is written LAST and is the
 *   source of truth: a file without a matching receipt is an orphan and is
 *   re-provisioned;
 * - the receipt's recorded hash must equal the CURRENT pin's hash --- a pin
 *   change silently invalidates the installed copy, which is the
 *   per-capability invalidation story (face-model change invalidates FACE
 *   outputs, CLIP change invalidates SEMANTIC outputs);
 * - no bytes are ever read into memory whole: verification streams
 *   ([ModelVerifier]).
 *
 * The transport is injected ([ModelDownloader]): this round pins the catalog
 * and the verify/install mechanics; the HTTP implementation lands with the
 * ADR-0020 egress rules in the next round, so nothing here needs the INTERNET
 * permission and unit tests run hermetically against fakes. All work is
 * blocking IO; the Phase 5 index coordinator wraps calls in its own bounded
 * dispatcher.
 *
 * [catalog] defaults to [ModelManifest.all]; tests inject tiny synthetic
 * specs so no test ever hashes megabytes.
 */
class ModelProvisioner(
    storeDir: File,
    private val downloader: ModelDownloader,
    private val catalog: List<ModelManifest.Model> = ModelManifest.all,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One installed model, as recorded in the receipt. */
    data class InstalledModel(val fileName: String, val sha256: String, val installedAtMillis: Long)

    /** Readiness of one [ModelManifest.Capability]. */
    sealed class CapabilityState {
        /** Nothing installed for this capability. */
        object NotProvisioned : CapabilityState()

        /** Some assets installed, [missing] names are not. */
        data class Partial(val missing: List<String>) : CapabilityState()

        /** Every asset of the capability is installed and receipt-verified. */
        object Ready : CapabilityState()
    }

    sealed class ProvisionException(message: String, cause: Throwable? = null) :
        Exception(message, cause) {
        /** The injected transport failed (network, HTTP status, cancelled stream). */
        class DownloadFailure(fileName: String, cause: Throwable?) :
            ProvisionException("download failed for $fileName", cause)

        /** Bytes arrived but failed size or hash verification; nothing was installed. */
        class VerificationFailed(fileName: String, val error: ModelVerifier.VerificationError) :
            ProvisionException("verification failed for $fileName: ${error.message}")
    }

    private val dir: File = storeDir
    private val receiptFile: File = File(storeDir, RECEIPT_NAME)

    private fun tempFile(spec: ModelManifest.Model) = File(dir, spec.fileName + PART_SUFFIX)
    private fun finalFile(spec: ModelManifest.Model) = File(dir, spec.fileName)
    private fun forCapability(capability: ModelManifest.Capability) =
        catalog.filter { it.capability == capability }

    // ---------------------------------------------------------------- state

    /** All receipt-verified installs, by file name. */
    fun installed(): Map<String, InstalledModel> {
        if (!receiptFile.exists()) return emptyMap()
        val props = Properties()
        try {
            receiptFile.inputStream().use { props.load(it) }
        } catch (_: IOException) {
            return emptyMap()
        }
        return props.stringPropertyNames()
            .filter { it.endsWith(SHA_SUFFIX) }
            .mapNotNull { key ->
                val fileName = key.removeSuffix(SHA_SUFFIX)
                val sha = props.getProperty(key)
                val at = props.getProperty(fileName + TIME_SUFFIX)?.toLongOrNull()
                    ?: return@mapNotNull null
                InstalledModel(fileName, sha, at)
            }
            .associateBy { it.fileName }
    }

    /**
     * The usable file for [spec], or null. Usable = present on disk, size
     * correct, and the receipt records exactly the CURRENT pin's hash. A pin
     * change therefore reads as "not installed" without deleting anything
     * (the stale copy is replaced on the next [provision]).
     */
    fun installedFile(spec: ModelManifest.Model): File? {
        val record = installed()[spec.fileName] ?: return null
        if (record.sha256 != spec.sha256) return null
        val file = finalFile(spec)
        if (!file.exists() || file.length() != spec.sizeBytes) return null
        return file
    }

    fun capabilityState(capability: ModelManifest.Capability): CapabilityState {
        val specs = forCapability(capability)
        if (specs.isEmpty()) return CapabilityState.NotProvisioned
        val missing = specs.filter { installedFile(it) == null }.map { it.fileName }
        return when {
            missing.isEmpty() -> CapabilityState.Ready
            missing.size == specs.size -> CapabilityState.NotProvisioned
            else -> CapabilityState.Partial(missing)
        }
    }

    // ----------------------------------------------------------- provisioning

    /**
     * Ensures every asset of [capability] is installed per the CURRENT pin.
     * Idempotent: already-installed assets are skipped without transport
     * calls. Throws the first [ProvisionException]; completed installs stay
     * installed (per-item failure isolation).
     */
    fun provision(
        capability: ModelManifest.Capability,
        onProgress: ((ModelManifest.Model) -> Unit)? = null,
    ) {
        ensureDir()
        for (spec in forCapability(capability)) {
            if (installedFile(spec) != null) continue
            installOne(spec)
            onProgress?.invoke(spec)
        }
    }

    private fun installOne(spec: ModelManifest.Model) {
        val final = finalFile(spec)
        val temp = tempFile(spec)
        try {
            downloader.open(spec).use { stream ->
                temp.outputStream().buffered().use { out -> stream.copyTo(out) }
            }
            val error = ModelVerifier.verifyFile(temp, spec)
            if (error != null) throw ProvisionException.VerificationFailed(spec.fileName, error)
            // Atomic promotion: same-directory rename. Replace any orphan
            // left by a previous crash (a file with no matching receipt).
            if (final.exists() && installedFile(spec) == null) final.delete()
            if (!temp.renameTo(final)) {
                throw IOException("rename to ${final.name} failed")
            }
            writeReceipt(spec)
        } catch (e: ProvisionException.VerificationFailed) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProvisionException.DownloadFailure(spec.fileName, e)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun writeReceipt(spec: ModelManifest.Model) {
        val props = Properties()
        if (receiptFile.exists()) {
            try {
                receiptFile.inputStream().use { props.load(it) }
            } catch (_: IOException) {
                // An unreadable receipt is replaced wholesale below.
            }
        }
        props[spec.fileName + SHA_SUFFIX] = spec.sha256
        props[spec.fileName + TIME_SUFFIX] = clock().toString()
        receiptFile.outputStream().use { props.store(it, "toadGALLERY model install receipt") }
    }

    // ---------------------------------------------------------- invalidation

    /**
     * Removes the installed copy of [fileName] (file + receipt entry) so the
     * next [provision] fetches fresh bytes. This is the deletion half of the
     * audit's model-update invalidation rule; the affected AI outputs are
     * invalidated by the caller that knows the capability.
     */
    fun invalidate(fileName: String) {
        val spec = catalog.firstOrNull { it.fileName == fileName } ?: return
        finalFile(spec).delete()
        tempFile(spec).delete()
        rewriteReceipt { it.remove(fileName) }
    }

    fun invalidateCapability(capability: ModelManifest.Capability) {
        forCapability(capability).forEach { invalidate(it.fileName) }
    }

    private fun rewriteReceipt(edit: (MutableMap<String, InstalledModel>) -> Unit) {
        val current = installed().toMutableMap()
        edit(current)
        if (current.isEmpty()) {
            receiptFile.delete()
            return
        }
        val props = Properties()
        current.values.forEach {
            props[it.fileName + SHA_SUFFIX] = it.sha256
            props[it.fileName + TIME_SUFFIX] = it.installedAtMillis.toString()
        }
        receiptFile.outputStream().use { props.store(it, "toadGALLERY model install receipt") }
    }

    private fun ensureDir() {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create model dir ${dir.path}")
    }

    private companion object {
        const val RECEIPT_NAME = "install.properties"
        const val PART_SUFFIX = ".part"
        const val SHA_SUFFIX = ".sha256"
        const val TIME_SUFFIX = ".installedAtMillis"
    }
}

/**
 * Transport abstraction for [ModelProvisioner]. Implementations MUST fetch
 * from the pinned allowlisted host only (ADR-0020) and MUST NOT buffer the
 * whole asset in memory --- return a streaming [InputStream].
 */
fun interface ModelDownloader {
    fun open(spec: ModelManifest.Model): InputStream
}
