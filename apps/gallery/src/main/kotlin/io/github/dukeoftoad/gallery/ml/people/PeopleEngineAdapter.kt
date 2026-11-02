package io.github.dukeoftoad.gallery.ml.people

import android.app.Application
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import java.io.File

/**
 * The production [PeopleEngineSeam] (r25, Workstream 6): a THIN
 * delegation over the `MlEngine` facade's r25 cluster surface
 * ([MlEngine.faceClusters] / [MlEngine.clusterFileIds] /
 * [MlEngine.mergeFaceClusters] / [MlEngine.removeFacesFromCluster] ---
 * Agent A's engine-side half of this round's contract), plus the session
 * lifecycle those calls need.
 *
 * ## Session lifecycle
 * The store reads run against an OPEN face-index store, so the adapter
 * mirrors `FaceIndexWorker`'s known-good sequence lazily and exactly
 * once per instance: `initialize(modelsDir)` then
 * `openFaceIndex(faceIndexDir)`. Every seam method calls
 * [ensureSessionOpen] first (idempotent: an already-open session is
 * reused); a failure throws `IllegalStateException` carrying the
 * engine's OWN message --- the repository propagates it, the ViewModel
 * maps it to the policy's `Error` state, and nothing is paraphrased
 * away. A consented-but-unprovisioned device (models never downloaded,
 * index never run) therefore surfaces as that honest Error with the
 * engine's text, whose recovery path (the Settings AI section's model
 * download + background index) the error copy points at.
 *
 * [close] releases the session (`closeFaceIndex` + `release`) and is
 * called from the owning ViewModel's `onCleared`. Known residual: the
 * r24 background worker drives its own `MlEngine` instance; if the
 * worker runs while a People screen holds an open store, the two
 * sessions alias the same process-global native engine --- the engine
 * seam's own serialization ([MlEngine] is `@Synchronized` throughout) is
 * the only guard. The queue's writes land between this class's reads,
 * which is precisely the live-table semantics the People feature
 * documents (clusters change under the app).
 *
 * Android-adjacent glue, untested per ADR-0016 (the pure logic this
 * adapter feeds is JVM-tested behind the seam); privacy: ADR-0010
 * (on-device only, consent-gated in the UI layer) and ADR-0020 (the
 * models.ente.com delivery that provisioned the models it reads).
 */
class PeopleEngineAdapter(
    private val engine: MlEngine = MlEngine(),
    private val modelsDir: String,
    private val faceIndexDir: File,
) : PeopleEngineSeam {

    override val engineAvailable: Boolean
        get() = engine.available

    /** Whether the lazily-opened session has been opened already. */
    private var sessionOpen = false

    override fun faceClusters(): List<FaceCluster> {
        ensureSessionOpen()
        return engine.faceClusters().map { cluster ->
            FaceCluster(
                clusterId = cluster.clusterId,
                faceCount = cluster.faceCount,
                coverFileId = cluster.coverFileId,
            )
        }
    }

    override fun clusterFileIds(clusterId: String): List<Long> {
        ensureSessionOpen()
        return engine.clusterFileIds(clusterId)
    }

    override fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean {
        ensureSessionOpen()
        return engine.mergeFaceClusters(sourceClusterId, targetClusterId)
    }

    override fun removeFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean {
        ensureSessionOpen()
        return engine.removeFacesFromCluster(clusterId, fileIds)
    }

    /**
     * Releases the session; safe to call repeatedly and from a ViewModel
     * that never opened one. After a close the adapter lazily re-opens on
     * the next seam call (the engine contract's re-init guarantee).
     */
    @Synchronized
    fun close() {
        if (!engineAvailable) return
        engine.closeFaceIndex()
        engine.release()
        sessionOpen = false
    }

    /**
     * Opens the engine session once, in `FaceIndexWorker`'s order
     * (initialize, then open the face-index store at the SAME directory
     * the r24 queue's `ml-index.sqlite` and `index-state.json` live in).
     * Synchronized on this adapter: the seam's four methods may be called
     * from different coroutines, and the open must land exactly once.
     */
    @Synchronized
    private fun ensureSessionOpen() {
        if (sessionOpen) return
        if (!engineAvailable) {
            // Dominates: a library-less build fails with the build-shape
            // message, never a confusing state-machine one.
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        when (val init = engine.initialize(modelsDir)) {
            is MlEngine.State.Ready -> Unit
            is MlEngine.State.Error ->
                throw IllegalStateException("engine init failed: ${init.message}")
            else ->
                throw IllegalStateException("engine init: $init")
        }
        if (!engine.openFaceIndex(faceIndexDir.absolutePath)) {
            throw IllegalStateException("face index store did not open")
        }
        sessionOpen = true
    }

    companion object {
        /**
         * The production wiring: the process-wide provisioner's model
         * install root and the app-private `face-index` directory (the
         * r24 constants --- the SAME directory whose recursive delete in
         * Settings resets the store, the queue bookkeeping AND the r25
         * people state together).
         */
        fun fromApplication(app: Application): PeopleEngineAdapter {
            val application = app as ToadGalleryApplication
            return PeopleEngineAdapter(
                modelsDir = application.modelProvisioner.modelsDir.absolutePath,
                faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME),
            )
        }
    }
}
