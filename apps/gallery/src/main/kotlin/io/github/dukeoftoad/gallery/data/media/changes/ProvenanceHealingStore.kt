package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The NULL-provenance healing pass's persistence seam (r20 round; the
 * r19-named follow-up, closed). Room implements it over the two r20 DAO
 * statements ([io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueDao
 * .rowsNeedingProvenanceHealing] / [updateProvenanceIndexedAt]); JVM tests
 * drive the pass through a fake of this interface, which is the only reason
 * it exists as a seam at all (ADR-0016).
 *
 * ## The problem this pass closes
 *
 * The scan's index hook isolates failures PER ROW (r19): a row whose
 * indexing throws (Room disk error, EXIF parse blowup) is logged, skipped,
 * and the batch continues. But the skipped row's revision did not move, so
 * the NEXT scan classifies it UNCHANGED --- the hook never sees it again,
 * and its provenance columns stay whatever they were. Before r20 there
 * was no marker to distinguish "indexed, honestly null" from "never indexed",
 * so the failure was permanent: one transient error during a scan could
 * strand a row's capture date / location forever.
 *
 * ## The fix, in two halves
 *
 * 1. [markProvenanceIndexed] is the LAST write the combined index pass
 *    makes for a row ([CombinedExifIndexer]): capture date, location, THEN
 *    the stamp. A row that made it through everything carries the stamp; a
 *    row that failed anywhere keeps `null`.
 * 2. [rowsNeedingIndexHealing] selects the `null` rows (bounded, newest
 *    first) and [ProvenanceHealingPass] re-offers them to the same hook ---
 *    idempotently: a row that succeeds is stamped and never re-selected
 *    unless a real add/change re-nulls it (which means it legitimately
 *    re-indexes). A row that fails again stays selectable for the next
 *    FEW passes, then rotates out of the head of the selection (see
 *    [ProvenanceHealingFailureMarker] --- the r51/E-3 round) so one
 *    poisoned block can no longer starve the rows behind it.
 */
interface ProvenanceHealingStore {

    /**
     * Up to [limit] live rows never fully indexed, most recently seen
     * first. Tombstoned keys are excluded by the live-set invariant; the
     * caller re-offers exactly these rows to the index hook.
     */
    suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord>

    /**
     * Stamps [atMillis] onto the live row for [localMediaKey] as the moment
     * its indexing completed. An unknown or tombstoned key is a no-op,
     * never a resurrection (the targeted-UPDATE discipline every scan-side
     * write in this catalogue keeps).
     */
    suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long)
}

// =====================================================================
// --- r51 round (audit E-3): the failure-marker rotation ----------------
// (additive section. The pass's two new halves --- the in-run no-progress
// break and the cross-start rotation --- live below; this block carries
// the marker type, its persistence seam, the production file store and
// the pure rotation policy, all JVM-tested: ADR-0016, no android.*.)
// =====================================================================

/**
 * The pass's record that [localMediaKey] failed healing RE-OFFERS:
 * how many times ([failureCount]), when the last failure was observed
 * ([lastFailedAtMillis] --- drives the 30-day expiry and the map's
 * eviction order), and the row's [CatalogueRecord.lastSeenAtMillis] AT THE
 * LAST FAILURE ([rowLastSeenAtMillis] --- the staleness sentinel: a row
 * whose CURRENT lastSeen differs was re-scanned, i.e. its bytes/revision
 * legitimately moved, so the old failures no longer describe the row and
 * it gets a fresh chance).
 *
 * Persisted by [ProvenanceHealingFailureMarkerFile]; kept out of Room on
 * purpose --- a schema change for auxiliary bookkeeping is the E-10
 * hand-written-identityHash risk, explicitly out of scope.
 */
data class ProvenanceHealingFailureMarker(
    /** The stable local media key the marker tracks. */
    val localMediaKey: String,
    /** Completed heal() passes that re-offered this row and it stayed un-stamped. */
    val failureCount: Int,
    /** Wall clock of the last observed failure (pass start it was recorded in). */
    val lastFailedAtMillis: Long,
    /** The row's lastSeenAtMillis when it last failed; a DIFFERENT current value means "revised since". */
    val rowLastSeenAtMillis: Long,
)

/**
 * The persistence seam for [ProvenanceHealingFailureMarker]s: a whole-map
 * load / atomic full-rewrite save, the same contract as the ML queues'
 * state stores ([io.github.dukeoftoad.gallery.ml.index.FaceIndexStateStore]
 * --- deliberately NOT Room: no schema, no migration, E-10-safe).
 *
 * Constructor-injected into [ProvenanceHealingPass] as an OPTIONAL
 * defaulted parameter: the pass's r20 constructor stays source-compatible
 * (call sites outside the r51 file scope construct it without a marker
 * store), and JVM tests inject a fake.
 */
interface ProvenanceHealingFailureMarkerStore {

    /** Every persisted marker (empty when none were ever persisted). */
    suspend fun load(): List<ProvenanceHealingFailureMarker>

    /**
     * Persists [markers] as the WHOLE marker state (an atomic full
     * rewrite). The pass hands over the already bounded/expired map
     * ([ProvenanceHealingRotationPolicy.bound]); the store is a dumb
     * serializer, never policy.
     */
    suspend fun save(markers: List<ProvenanceHealingFailureMarker>)
}

/**
 * The production [ProvenanceHealingFailureMarkerStore]: one versioned JSON
 * file, written with the atomic temp-then-move discipline the ML index
 * state files use (`FaceIndexStateFile`'s exact write contract: serialize
 * to `<file>.tmp`, then an NIO ATOMIC_MOVE over the previous file, with a
 * plain-filesystem fallback for layouts without atomic rename support).
 * Parsed FORGIVINGLY: a missing, empty, corrupt or wrong-version file is
 * "no markers yet" --- losing the markers costs rotation freshness only
 * (rows get re-offered and the counts re-accumulate), never correctness.
 *
 * JVM-tested directly (`ProvenanceHealingRotationTest`): round-trips,
 * atomic overwrite, corrupt-file recovery, version discipline.
 */
class ProvenanceHealingFailureMarkerFile(
    private val file: File,
) : ProvenanceHealingFailureMarkerStore {

    override suspend fun load(): List<ProvenanceHealingFailureMarker> {
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) return emptyList()
            val array = root.optJSONArray(MARKERS_KEY) ?: return emptyList()
            val out = ArrayList<ProvenanceHealingFailureMarker>(array.length())
            for (i in 0 until array.length()) {
                markerFromJson(array.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            // Corrupt / partially-written / hand-edited: the honest state
            // is "no markers" (rows are simply re-offered and the counts
            // re-accumulate), never a thrown parse error into the pass.
            emptyList()
        }
    }

    override suspend fun save(markers: List<ProvenanceHealingFailureMarker>) {
        val array = JSONArray()
        markers.forEach { array.put(markerToJson(it)) }
        val root = JSONObject()
            .put(VERSION_KEY, SUPPORTED_VERSION)
            .put(MARKERS_KEY, array)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Layouts without atomic rename support still get the durable
            // temp-write + replace ordering; only the atomicity window
            // widens (the FaceIndexStateFile fallback, verbatim).
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val VERSION_KEY = "version"
        const val MARKERS_KEY = "markers"
        const val SUPPORTED_VERSION = 1

        const val KEY = "key"
        const val FAILURE_COUNT = "failureCount"
        const val LAST_FAILED_AT = "lastFailedAtMillis"
        const val ROW_LAST_SEEN_AT = "rowLastSeenAtMillis"

        fun markerToJson(marker: ProvenanceHealingFailureMarker): JSONObject = JSONObject()
            .put(KEY, marker.localMediaKey)
            .put(FAILURE_COUNT, marker.failureCount)
            .put(LAST_FAILED_AT, marker.lastFailedAtMillis)
            .put(ROW_LAST_SEEN_AT, marker.rowLastSeenAtMillis)

        fun markerFromJson(obj: JSONObject): ProvenanceHealingFailureMarker? {
            val key = obj.optString(KEY)
            if (key.isEmpty()) return null
            return ProvenanceHealingFailureMarker(
                localMediaKey = key,
                failureCount = obj.optInt(FAILURE_COUNT),
                lastFailedAtMillis = obj.optLong(LAST_FAILED_AT),
                rowLastSeenAtMillis = obj.optLong(ROW_LAST_SEEN_AT),
            )
        }
    }
}

/**
 * The rotation's every decision, pure and JVM-tested (the pass only
 * sequences these; ADR-0016). Each rule, stated once:
 *
 * - EXCLUSION: a key whose marker records [FAILURE_EXCLUSION_THRESHOLD]
 *   (3) or more completed-pass failures is held out of the offering ---
 *   three honest chances is a poisoned row's fair trial (the same
 *   three-strike spirit as the ML queues' terminal-skip records). The
 *   exclusion lapses after [MARKER_EXPIRY_MILLIS] (30 days): a row whose
 *   bytes are still broken is re-offered once a month, which is bounded
 *   honest retry, not starvation.
 * - STALENESS: a marker whose [ProvenanceHealingFailureMarker
 *   .rowLastSeenAtMillis] differs from the row's CURRENT lastSeen was
 *   written against an older revision --- the row was re-scanned (a real
 *   add/change re-nulls the stamp and legitimately re-indexes), so the
 *   old failures no longer describe it and the row is offered fresh.
 * - OUTCOMES: after a pass, a key that is STILL selectable (no stamp)
 *   failed again (count + 1, timestamps refreshed from the live row); a
 *   key that left the selection HEALED (or was tombstoned) and its marker
 *   is removed --- success clears the record, per the audit's rule.
 * - BOUND: expired entries drop and the map caps at [MAX_TRACKED_KEYS]
 *   (5000) keeping the NEWEST failures --- the marker file stays small and
 *   honest on a pathological library.
 */
object ProvenanceHealingRotationPolicy {

    /**
     * Completed-pass failures before a key is excluded from the selection.
     * Three: enough honest chances for a transient failure to clear on its
     * own, few enough that a poisoned head cannot eat the whole budget for
     * long.
     */
    const val FAILURE_EXCLUSION_THRESHOLD = 3

    /**
     * How long an exclusion lasts before the row gets a fresh trial:
     * 30 days. The expiry is the blanket backstop for the per-revision
     * staleness rule (a row that stays broken is re-offered monthly).
     */
    const val MARKER_EXPIRY_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

    /**
     * The marker map's hard cap (eviction: oldest failure first). Keeps
     * the persisted file small and the exclusion set's over-fetch cost
     * (see the pass's query limit) bounded on a pathological library.
     */
    const val MAX_TRACKED_KEYS = 5_000

    /** True when [marker] has been failing longer than the expiry window. */
    fun isExpired(marker: ProvenanceHealingFailureMarker, nowMillis: Long): Boolean =
        nowMillis - marker.lastFailedAtMillis >= MARKER_EXPIRY_MILLIS

    /**
     * True when [marker] was written against an older revision of the row:
     * the row's CURRENT [rowLastSeenAtMillis] differs from the recorded
     * one, so the row was re-scanned since the failures and deserves a
     * fresh offering (a real add/change re-nulls the stamp and
     * legitimately re-indexes).
     */
    fun isStaleFor(marker: ProvenanceHealingFailureMarker, rowLastSeenAtMillis: Long): Boolean =
        marker.rowLastSeenAtMillis != rowLastSeenAtMillis

    /**
     * The keys held out of THIS pass's offering: failure count at or above
     * the threshold and not yet expired. Everything else --- untracked
     * keys, keys still inside their three strikes, stale-marker keys ---
     * stays offerable.
     */
    fun activeExclusions(
        markers: Collection<ProvenanceHealingFailureMarker>,
        nowMillis: Long,
    ): Set<String> {
        val out = HashSet<String>()
        for (marker in markers) {
            if (marker.failureCount >= FAILURE_EXCLUSION_THRESHOLD && !isExpired(marker, nowMillis)) {
                out += marker.localMediaKey
            }
        }
        return out
    }

    /**
     * Folds one pass's outcome into the marker map: every [offeredKeys]
     * entry that appears in [stillSelectableRows] (still un-stamped ---
     * the stamp is the only success signal, the hook's per-row catches are
     * invisible here by design) failed again and gets count + 1 with
     * refreshed timestamps; every offered key ABSENT from it healed (or
     * left the live set) and its marker is REMOVED. Markers of keys not
     * offered this pass (exclusions, never-selected rows) carry through
     * unchanged.
     */
    fun applyPassOutcomes(
        markersByKey: Map<String, ProvenanceHealingFailureMarker>,
        offeredKeys: Set<String>,
        stillSelectableRows: Map<String, CatalogueRecord>,
        nowMillis: Long,
    ): Map<String, ProvenanceHealingFailureMarker> {
        val out = HashMap<String, ProvenanceHealingFailureMarker>(markersByKey)
        for (key in offeredKeys) {
            val stillSelectable = stillSelectableRows[key]
            if (stillSelectable == null) {
                // Healed (stamped) or gone (tombstoned/unmounted): either
                // way the marker's job is done. Re-accumulates from zero
                // if the row ever returns to the selectable set.
                out.remove(key)
            } else {
                val previous = markersByKey[key]
                out[key] = ProvenanceHealingFailureMarker(
                    localMediaKey = key,
                    failureCount = (previous?.failureCount ?: 0) + 1,
                    lastFailedAtMillis = nowMillis,
                    rowLastSeenAtMillis = stillSelectable.lastSeenAtMillis,
                )
            }
        }
        return out
    }

    /**
     * The persisted map's bound: expired entries dropped, at most
     * [MAX_TRACKED_KEYS] kept (newest failures first), deterministically
     * ordered (newest first, then key) so consecutive saves diff small.
     */
    fun bound(
        markers: Collection<ProvenanceHealingFailureMarker>,
        nowMillis: Long,
    ): List<ProvenanceHealingFailureMarker> =
        markers.asSequence()
            .filterNot { isExpired(it, nowMillis) }
            .sortedWith(
                compareByDescending<ProvenanceHealingFailureMarker> { it.lastFailedAtMillis }
                    .thenBy { it.localMediaKey },
            )
            .take(MAX_TRACKED_KEYS)
            .toList()
}

/**
 * The healing pass itself (r20): re-offers the never-fully-indexed rows to
 * the (combined) index hook in bounded batches until the selectable set is
 * drained or the pass's work budget is spent. PURE ORCHESTRATION around
 * [ProvenanceHealingStore] and the hook --- every decision below is stated
 * here and JVM-tested; nothing Android-shaped anywhere.
 *
 * ## Trigger and budget
 *
 * The scan driver runs one pass right after the APP_START scan (see
 * [MediaScanDriver.start]) --- the same coroutine, so the pass observes the
 * catalogue the first scan just reconciled. The pass is BOUNDED
 * ([maxBatches] * [batchSize] rows per invocation) so a 50k-item cold
 * library --- where every legacy row reads `null` once, by migration
 * nature --- converges over a few app starts instead of stalling the first
 * one (the roadmap's "avoid rescanning the entire library on every
 * launch"). Rows heal newest-seen-first, so the rows the user is most
 * likely to open heal first.
 *
 * ## r51 rotation (audit E-3): no more poisoned-head starvation
 *
 * The r20 pass re-selected `WHERE provenanceIndexedAtMillis IS NULL ORDER
 * BY lastSeenAtMillis DESC ... LIMIT :limit` every batch, and a row that
 * fails DETERMINISTICALLY (corrupt EXIF that throws past the hook's catch,
 * a malformed-key row the eligibility gate skips) keeps its NULL stamp and
 * its constant lastSeenAtMillis --- so the same head batch was re-selected
 * within one pass (up to 10x re-offers of the same bytes) and across app
 * starts (the whole 1000-row budget spent on the same poisoned block,
 * re-reading the same bytes every launch, while the rows behind it never
 * healed). Two halves close it, with NO Room schema change (the E-10
 * hand-written-identityHash risk is explicitly out of scope):
 *
 * 1. IN-RUN NO-PROGRESS BREAK: the pass tracks every key it offered; a
 *    batch consisting ENTIRELY of already-offered keys is the no-progress
 *    signature, and the pass stops there instead of burning its remaining
 *    budget re-reading the same bytes. A MIXED batch (some repeats, some
 *    new rows) still proceeds --- re-offering a not-yet-excluded repeat is
 *    harmless idempotent work, and the new rows are forward progress.
 * 2. CROSS-START ROTATION: when a [ProvenanceHealingFailureMarkerStore] is
 *    wired, the pass persists per-key failure counts (atomic JSON file
 *    discipline, bounded map) and holds keys with [ProvenanceHealingRotationPolicy
 *    .FAILURE_EXCLUSION_THRESHOLD]+ failures out of the offering until
 *    their revision changes (marker staleness) or the marker expires (30
 *    days). Excluded keys leave the head via an OVER-FETCHED selection
 *    (`batchSize + exclusions` rows, then filtered): the fetch always
 *    covers at least `batchSize` offerable rows (pigeonhole --- at most
 *    one excluded row per exclusion occupies the fetch), so the budget
 *    reaches the rows BEHIND the poisoned block. A key that heals has its
 *    marker removed; a key that fails again gets count + 1.
 *
 * The marker store is an OPTIONAL defaulted constructor parameter: the
 * pass's r20 constructor stays source-compatible for call sites outside
 * this file's scope, and the in-run break protects the budget even when
 * no store is wired.
 *
 * ## Concurrency (deliberately lock-free)
 *
 * The pass takes NO lock against concurrent scan passes: the catalogue is
 * transaction-per-call and every write here is a targeted partial update,
 * so an observer scan upserting a row the pass is mid-indexing costs at
 * worst a redundant re-index of that row (the upsert re-nulls the stamp,
 * the next pass re-offers it --- idempotent). Serializing would buy
 * nothing the idempotence does not already provide and would make the
 * pass a contended stranger to the driver's mutex discipline. The same
 * reasoning makes the pass-end accounting (the selection read that tells
 * the pass which offered keys are still un-stamped) BEST-EFFORT: a
 * concurrent scan inserting rows at the head can push a failed key past
 * the fetch limit, the pass then reads it as healed and clears its marker
 * --- the cost is one redundant re-offer later, never a wrong exclusion.
 *
 * ## Failure isolation
 *
 * The hook's own per-row catch (the r19 structural boundary) is the whole
 * story for row failures: a row that throws again stays `null` and
 * (since r51) rotates out of the head after its strikes are spent. A pass
 * -level failure (the store itself throwing) propagates to the driver's
 * caller, which logs it and never lets it take the process down --- the
 * same envelope every driver stage runs in. Marker-file I/O is the ONE
 * deliberate exception: it is auxiliary bookkeeping, so a load or save
 * failure degrades to "no rotation THIS pass" with a diagnostic line,
 * never a lost healing pass.
 */
class ProvenanceHealingPass(
    private val healingStore: ProvenanceHealingStore,
    private val indexHook: ScanIndexHook,
    private val batchSize: Int = HEALING_BATCH_ROWS,
    private val maxBatches: Int = MAX_BATCHES_PER_PASS,
    private val failureMarkers: ProvenanceHealingFailureMarkerStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val diag: DiagLog = DiagLog,
) {

    /**
     * Runs the bounded healing pass and returns how many rows were
     * RE-OFFERED to the index hook (not how many succeeded --- the hook's
     * per-row failures are invisible here by design; the stamp is the only
     * success signal, and the next pass simply stops selecting the rows
     * that made it).
     */
    suspend fun heal(): Int {
        val passStartedAt = nowMillis()

        // E-3 part 2 state: the persisted failure markers, when a store is
        // wired. Marker I/O is auxiliary --- a failure degrades to "no
        // rotation this pass" (the in-run break still protects the budget)
        // and never costs the healing itself.
        val markersByKey = HashMap<String, ProvenanceHealingFailureMarker>()
        if (failureMarkers != null) {
            try {
                for (marker in failureMarkers.load()) markersByKey[marker.localMediaKey] = marker
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                diag.w(TAG, "provenance healing marker load failed (rotation disabled this pass)", t)
            }
        }
        val excluded = ProvenanceHealingRotationPolicy.activeExclusions(
            markersByKey.values,
            passStartedAt,
        )

        var offered = 0
        // E-3 part 1 state: every key offered THIS pass. A batch drawn
        // entirely from this set is the no-progress signature.
        val offeredKeys = HashSet<String>()
        var noProgressBatchRows = 0
        for (batch in 0 until maxBatches) {
            // E-3 part 2: OVER-FETCH by the exclusion count so an
            // exclusion-held head still fills the batch. The selection is
            // lastSeenAtMillis DESC; at most |excluded| of the fetched
            // rows can be held out, so fetching batchSize + |excluded|
            // rows always covers at least min(batchSize, offerable) rows
            // --- the budget reaches past the poisoned block. With no
            // exclusions the limit IS batchSize: the r20 behavior exactly.
            val queryLimit = batchSize + excluded.size
            val fetched = healingStore.rowsNeedingIndexHealing(queryLimit)
            val offerable = fetched.filter { row ->
                val marker = markersByKey[row.localMediaKey]
                // Offer the row unless it is BOTH exclusion-held AND
                // unchanged since its failures: a marker whose recorded
                // lastSeen differs from the row's CURRENT lastSeen is
                // stale (the row was re-scanned --- a real change re-nulls
                // the stamp and legitimately re-indexes), so a revised
                // row is offered fresh whatever its old count.
                marker == null ||
                    ProvenanceHealingRotationPolicy.isStaleFor(marker, row.lastSeenAtMillis) ||
                    row.localMediaKey !in excluded
            }
            if (offerable.isEmpty()) break
            if (offerable.all { it.localMediaKey in offeredKeys }) {
                // E-3 part 1: every row this batch would offer already
                // failed once THIS pass --- re-reading the same bytes buys
                // nothing. Stop (the rows stay selectable for the next
                // pass / the rotation) instead of burning the budget.
                noProgressBatchRows = offerable.size
                break
            }
            indexHook.onRecordsApplied(offerable, emptyList())
            offered += offerable.size
            offerable.forEach { offeredKeys += it.localMediaKey }
            // The r20 drain-tail break, restated for the over-fetch: a
            // fetch that came back SHORT means the selectable set itself
            // is exhausted (nothing hides behind the limit).
            if (fetched.size < queryLimit) break
        }

        if (noProgressBatchRows > 0) {
            diag.i(
                TAG,
                "provenance healing pass stopped on a no-progress batch " +
                    "($noProgressBatchRows already-offered rows skipped)",
            )
        }

        // E-3 part 2 accounting: one more selection read tells the pass
        // which offered keys are STILL un-stamped (failed again) vs gone
        // (healed or tombstoned) --- the stamp is the only success signal.
        // Unhealed offered keys sit at or above their offer position in
        // the order (healing only removes rows above them), so the same
        // over-fetched limit sees them all in the uncontended case.
        if (failureMarkers != null && offeredKeys.isNotEmpty()) {
            try {
                val stillSelectable = healingStore
                    .rowsNeedingIndexHealing(batchSize + excluded.size)
                    .associateBy { it.localMediaKey }
                val updated = ProvenanceHealingRotationPolicy.applyPassOutcomes(
                    markersByKey = markersByKey,
                    offeredKeys = offeredKeys,
                    stillSelectableRows = stillSelectable,
                    nowMillis = passStartedAt,
                )
                failureMarkers.save(
                    ProvenanceHealingRotationPolicy.bound(updated.values, passStartedAt),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                diag.w(TAG, "provenance healing marker save failed (rotation state not persisted)", t)
            }
        }

        if (offered > 0) {
            diag.i(TAG, "provenance healing pass offered $offered rows to the index hook")
        }
        return offered
    }

    private companion object {
        /** Diagnostics tag for the healing pass's summary line. */
        const val TAG = "ProvenanceHealing"

        /**
         * Rows per healing batch. Kept modest so each batch's hook work
         * (bytes reads through the media boundary) stays a small, honest
         * unit even on slow storage.
         */
        const val HEALING_BATCH_ROWS = 100

        /**
         * Batches per pass: 10 x 100 = at most 1000 rows re-offered per
         * app start. A cold 50k legacy library converges in ~50 starts
         * worst case, while each start stays cheap --- the roadmap's
         * launch-budget rule. (New rows never see the healing path at all:
         * they are ADDED, indexed by the live scan pass, and stamped in
         * the same breath.)
         */
        const val MAX_BATCHES_PER_PASS = 10
    }
}
