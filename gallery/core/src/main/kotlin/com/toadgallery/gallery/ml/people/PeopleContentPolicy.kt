package com.toadgallery.gallery.ml.people

/**
 * The People surfaces' six honest content states (r25, Workstream 6),
 * decided by the PURE [PeopleContentPolicy] --- the [MapContentPolicy]
 * (r16) discipline applied to the People feature: no Android imports,
 * JVM-tested, so the "which state does the user see" rules are pinned
 * rather than implicit in a ViewModel's control flow (ADR-0016).
 */
sealed interface PeopleContentState {

    /** First frame: consent, clusters and state have not resolved yet. */
    data object Loading : PeopleContentState

    /**
     * The People albums consent is OFF (the `peopleAlbumEnabled`
     * preference, default FALSE --- ADR-0010's opt-in rule). Persisted
     * clusters may exist, but the feature must not present them while the
     * user's choice says off --- showing face groupings the user never
     * asked for is the consent-facing twin of the map screen's
     * "secretly kept back" display. The state's copy points at the
     * Settings toggle.
     */
    data object ConsentOff : PeopleContentState

    /**
     * Consent is ON but this build carries no engine library
     * (`libtoad_ml_jni.so` --- built only with the engine property): the
     * honest build-shape state, never a disguised "no people yet".
     */
    data object EngineMissing : PeopleContentState

    /**
     * Consent on, engine present, ZERO clusters: the index has not run
     * (or the queue is still draining --- a half-indexed store clusters
     * only when the queue drains, per the r24 run contract). Copy says
     * people appear after the background index runs.
     */
    data object NotIndexed : PeopleContentState

    /**
     * Ready: [visible] are the live clusters minus the hidden ones, with
     * names applied, in the seam's face-count-descending order. CAN BE
     * EMPTY when every cluster is hidden --- a state distinct from
     * [NotIndexed] (the index ran; the user hid everyone) that the UI
     * renders with its own honest copy.
     */
    data class Ready(val visible: List<VisiblePerson>) : PeopleContentState

    /**
     * A read failed: [message] is the engine's/store's own diagnostic
     * text, surfaced verbatim (never paraphrased away). Dominates every
     * other input --- a failed read must not mask as consent, build or
     * empty states.
     */
    data class Error(val message: String) : PeopleContentState
}

/**
 * One cluster as the People UI renders it (r25): the domain
 * [FaceCluster] with the user's [name] applied (`null` renders as
 * "Unnamed" --- absence IS the unnamed state, there is no stored empty
 * name to confuse it with).
 */
data class VisiblePerson(
    val clusterId: String,
    val name: String?,
    val faceCount: Int,
    val coverFileId: Long?,
)

/**
 * The PURE mapping from the People feature's inputs to the screen's
 * content state (r25, Workstream 6). The ViewModels add nothing but the
 * I/O that produces the inputs; every rule below is pinned by
 * `PeopleContentPolicyTest`.
 *
 * Rule order (error dominance first, consent before capability, honesty
 * before emptiness):
 *  1. A non-null [errorMessage] wins over EVERYTHING: consent, engine
 *     presence and cluster data cannot turn a failed read into a
 *     presentable screen (the honest-states doctrine --- Error is a
 *     failed read, never a disguised empty one).
 *  2. Consent OFF is [PeopleContentState.ConsentOff] regardless of what
 *     the store holds (ADR-0010: the feature is opt-in, off by default;
 *     rendering clustered faces while the toggle says off would present
 *     data the user's own choice withholds --- same reasoning as
 *     [MapContentPolicy]'s display gate).
 *  3. Consent ON but engine missing is
 *     [PeopleContentState.EngineMissing] --- the build-shape fact.
 *  4. Consent + engine + zero RAW clusters is
 *     [PeopleContentState.NotIndexed] (includes "the queue is still
 *     draining"). Zero-visible-because-hidden does NOT land here: the
 *     index ran, the user just hid everyone --- that is Ready(empty),
 *     and the UI says so.
 *  5. Otherwise [PeopleContentState.Ready]: clusters minus [hiddenIds],
 *     each carrying `names[id]` (blank stored names --- which the state
 *     store already refuses to persist --- fall back to null/Unnamed),
 *     in the SEAM'S OWN face-count-descending order. The policy never
 *     re-sorts: the seam's ordering is the contract (the same
 *     "mapping, not sorter" rule [MapContentPolicy] pins).
 */
object PeopleContentPolicy {

    fun decide(
        consentEnabled: Boolean,
        engineAvailable: Boolean,
        clusters: List<FaceCluster>,
        hiddenIds: Set<String>,
        names: Map<String, String>,
        errorMessage: String? = null,
    ): PeopleContentState = when {
        errorMessage != null -> PeopleContentState.Error(errorMessage)
        !consentEnabled -> PeopleContentState.ConsentOff
        !engineAvailable -> PeopleContentState.EngineMissing
        clusters.isEmpty() -> PeopleContentState.NotIndexed
        else -> {
            val visible = ArrayList<VisiblePerson>(clusters.size)
            for (cluster in clusters) {
                if (cluster.clusterId in hiddenIds) continue
                val stored = names[cluster.clusterId]
                visible.add(
                    VisiblePerson(
                        clusterId = cluster.clusterId,
                        name = stored?.takeIf { it.isNotBlank() },
                        faceCount = cluster.faceCount,
                        coverFileId = cluster.coverFileId,
                    )
                )
            }
            PeopleContentState.Ready(visible)
        }
    }

    /**
     * The mirror of `Ready(visible)` for the HIDDEN half (r26, the
     * People follow-ups round): the live clusters that ARE in
     * [hiddenIds] --- stale hidden ids (a recluster the overlay could
     * not match) render nothing, exactly like stale names --- with
     * names applied and the seam's own order preserved. The People
     * screen's hidden section renders these behind the per-person
     * unhide action. The same "mapping, not sorter" rule as [decide].
     */
    fun hiddenPersons(
        clusters: List<FaceCluster>,
        hiddenIds: Set<String>,
        names: Map<String, String>,
    ): List<VisiblePerson> {
        val hidden = ArrayList<VisiblePerson>()
        for (cluster in clusters) {
            if (cluster.clusterId !in hiddenIds) continue
            val stored = names[cluster.clusterId]
            hidden.add(
                VisiblePerson(
                    clusterId = cluster.clusterId,
                    name = stored?.takeIf { it.isNotBlank() },
                    faceCount = cluster.faceCount,
                    coverFileId = cluster.coverFileId,
                )
            )
        }
        return hidden
    }
}
