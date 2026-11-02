package io.github.dukeoftoad.gallery.ui.details

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
import io.github.dukeoftoad.gallery.data.media.access.ContentResolverMediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.access.MediaReadPermissionGate
import io.github.dukeoftoad.gallery.data.media.dates.CatalogueRecordEligibilityGate
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.data.media.location.AndroidMediaLocationAccess
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationDisplayRules
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationState
import io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.edit.EditableExifCodec
import io.github.dukeoftoad.gallery.metadata.edit.EditableExifCurrentValues
import io.github.dukeoftoad.gallery.metadata.edit.ExifWriteFormats
import io.github.dukeoftoad.gallery.metadata.edit.FieldEditResult
import io.github.dukeoftoad.gallery.metadata.edit.MediaEditCapability
import io.github.dukeoftoad.gallery.metadata.edit.MediaEditField
import io.github.dukeoftoad.gallery.metadata.edit.MediaEditGate
import io.github.dukeoftoad.gallery.metadata.edit.MediaMetadataWriter
import io.github.dukeoftoad.gallery.metadata.edit.MetadataEditInput
import io.github.dukeoftoad.gallery.metadata.edit.MetadataWriteFlow
import io.github.dukeoftoad.gallery.metadata.edit.MetadataWriteResult
import io.github.dukeoftoad.gallery.metadata.edit.RawWriteOutcome
import io.github.dukeoftoad.gallery.metadata.edit.WriteGrantStateMachine
import io.github.dukeoftoad.gallery.metadata.edit.WriteRefusalReason
import io.github.dukeoftoad.gallery.metadata.exif.AndroidExifReader
import io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix
import io.github.dukeoftoad.gallery.metadata.video.MediaMetadataRetrieverVideoMetadataSource
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailRow
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.EditableFieldRow
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Details screen renders for one item, in its three honest
 * states: still reading, rendered rows (see [DetailRow]), or structurally
 * unreadable --- an item the eligibility gate refuses (vault, trashed,
 * permission-revoked) is NEVER read, not "shown empty": the read boundary
 * ([MediaAccess] takes only an [EligiblePublicMedia]) makes ineligible bytes unrepresentable,
 * and the screen narrates that instead of pretending.
 */
sealed interface DetailsUiState {

    /** The catalogue lookup + byte read are still in flight. */
    data object Loading : DetailsUiState

    /**
     * The ordered display rows; `recordFound` is false when the item was not
     * in the catalogue. `editRows` is the r15 "Editable fields" section
     * (one row per roadmap field with its honest capability).
     */
    data class Ready(
        val rows: List<DetailRow>,
        val recordFound: Boolean,
        val editRows: List<EditableFieldRow> = emptyList(),
    ) : DetailsUiState

    /** The item is not readable through the public read boundary (ineligible/revoked). */
    data object Unavailable : DetailsUiState
}

/**
 * One editor session's phase (r15 write round): typing (or viewing a
 * refusal reason), the write in flight, an honest failure (retryable),
 * or the user's denial of the system write-grant dialog (terminal for
 * this session --- the reason differs from a refusal, so it is not
 * collapsed into [MetadataEditorPhase.Failed]).
 */
sealed interface MetadataEditorPhase {

    /** The editor accepts input (or shows the consent-required view). */
    data object Input : MetadataEditorPhase

    /** The write is in flight (including the grant round-trip). */
    data object Saving : MetadataEditorPhase

    /** The write was refused; [reason] renders and the input stays editable. */
    data class Failed(val reason: WriteRefusalReason) : MetadataEditorPhase

    /** The system write-grant dialog was declined. */
    data object Denied : MetadataEditorPhase
}

/**
 * The metadata editor session state (r15): which field is being edited,
 * the honest capability the pure gate computed at open time, the prefill
 * from the writer's read-back (`valuesAvailable` false when that read
 * failed --- the editor then starts empty and says so), and the phase.
 */
sealed interface MetadataEditorState {

    /** No editor open. */
    data object Closed : MetadataEditorState

    /** The editor for [field] is open in [phase]. */
    data class Open(
        val field: MediaEditField,
        val capability: MediaEditCapability,
        val prefill: MetadataEditInput,
        val valuesAvailable: Boolean,
        val phase: MetadataEditorPhase,
    ) : MetadataEditorState
}

/**
 * One-shot UI events the editor needs the Activity side for (r15): the
 * system write-grant dialog (API 30+, launched via
 * `StartIntentSenderForResult`) and the ACCESS_MEDIA_LOCATION runtime
 * request (the GPS field's consent dimension, launched via
 * `RequestPermission`). Never replayed; the screen collects from first
 * composition.
 */
sealed interface MetadataEditEvent {

    /** Launch the write-grant dialog for this sender; report via
     * `onWriteGrantResult(resultCode == Activity.RESULT_OK)`. */
    data class RequestWriteGrant(val intentSender: IntentSender) : MetadataEditEvent

    /** Launch the ACCESS_MEDIA_LOCATION request; report via
     * `onLocationConsentResult(granted)`. */
    data object RequestLocationConsent : MetadataEditEvent
}

/**
 * State holder for the Phase 4 metadata Details screen (roadmap
 * "Metadata Details view with read"). DI-less by repo convention: the
 * process-wide catalogue is the SHARED instance the app holds
 * ([ToadGalleryApplication.mediaCatalogue] --- r19a; this screen used to
 * build its own database per visit, see [catalogue]), while the read
 * boundary is constructed HERE from the [Application], exactly the seam
 * [ToadGalleryApplication.startPhase4ScanPipeline] wires for the scan
 * driver --- [ContentResolverMediaAccess] behind
 * [MediaReadPermissionGate], and [CatalogueRecordEligibilityGate] as the
 * type-level vault gate. A screen-scoped ViewModel (not shell-scoped)
 * because the details state is per-item transitive UI state, unlike the
 * viewer window hand-off.
 *
 * Threading: all catalogue reads, byte reads and EXIF parsing run on
 * [Dispatchers] IO/Default --- nothing heavy touches the main thread
 * (Compose calls only collect [state]). The parsed snapshot is CACHED in
 * [cache] for the ViewModel's lifetime (the screen's life), keyed by
 * `mediaId|uri`: a configuration change re-composes from the cached
 * [DetailsUiState.Ready] without re-reading the file's bytes, and a
 * returning navigation to the same item reuses the parse. The cache is
 * process-light (one snapshot of small data classes per visited item) and
 * dies with the ViewModel.
 *
 * Not-in-catalogue fallback (documented deviation the roadmap allows):
 * the record lookup needs the catalogue's `localMediaKey`, which encodes
 * `volume:id` --- but the Details route carries only the MediaStore `_id`
 * + content Uri. The lookup matches a live record whose key ends in the
 * id AND whose canonical Uri equals the passed one; when no record
 * matches (a scan that has not indexed the item yet), the identity is
 * reconstructed from the route arguments --- volume taken from the Uri's
 * first path segment (`content://media/<volume>/...`, the MediaStore
 * convention), eligibility re-established through
 * [MediaEligibility.asEligible] so the type-level gate still applies.
 * Such items show `recordFound = false`; the catalogue-only provenance
 * rows (resolved capture date, zone, source) render their honest ABSENT
 * state.
 *
 * WRITE PATH (r15, "editable metadata fields"): the same ViewModel owns
 * the editor session. Every DECISION is pure and JVM-tested (the
 * [MediaEditGate] capability gate, the [EditableExifCodec] field codec,
 * the [WriteGrantStateMachine] escalation machine and the
 * [MetadataWriteFlow] failure classifier); this class only wires them to
 * the [MediaMetadataWriter] Android shell and the activity-result
 * launchers through [editEvents] (the same shape as
 * `FileOpsViewModel`'s consent round-trip). Writes run on the writer's
 * own Dispatchers.IO; a successful write drops the item's cached parse
 * and re-resolves so the rows show the edit immediately, while the r12
 * scan pipeline re-indexes the item on its own schedule (eventual
 * consistency --- see [MediaMetadataWriter]'s KDoc).
 *
 * NOT JVM-unit-tested, by design (ADR-0016): this class is Android
 * plumbing --- catalogue/ContentResolver/retriever wiring around the pure,
 * tested [DetailsPresentation]; the same exemption the other
 * AndroidViewModels in the repo carry.
 */
class DetailsViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The process-wide media catalogue (r19a): the SAME shared instance
     * ([ToadGalleryApplication.mediaCatalogue]) the scan pipeline, the
     * Photos timeline and the Map tab talk to, replacing this screen's
     * former per-visit build. That private database violated the
     * one-database-per-process invariant the catalogue layer states ---
     * Room invalidation only reaches observers of the SAME instance, so a
     * second database over the same file would silently miss every scan
     * write (and each Details visit leaked one open SQLite connection
     * until its ViewModel died). Typed as the concrete [RoomMediaCatalogue]
     * because the keyed [findRecord] lookup (r19a) lives on the concrete
     * class, exactly like [MapViewModel]'s binding.
     */
    private val catalogue: RoomMediaCatalogue =
        getApplication<ToadGalleryApplication>().mediaCatalogue

    private val mediaAccess = ContentResolverMediaAccess(
        resolver = app.contentResolver,
        permissionGate = MediaReadPermissionGate(app),
    )

    private val eligibilityGate = CatalogueRecordEligibilityGate()

    private val exifReader = AndroidExifReader()

    private val videoSource = MediaMetadataRetrieverVideoMetadataSource(app.contentResolver)

    /**
     * The location-consent state (r14): GPS rows in the Details view are
     * DISPLAY-GATED on it (MediaLocationDisplayRules.Default). The state is
     * observed for the ViewModel's lifetime so a grant made in Settings (or
     * revoked in the system UI) re-renders the open screen without a
     * process restart; the snapshot itself is never re-parsed --- only the
     * row assembly re-runs.
     */
    private val locationAccess = AndroidMediaLocationAccess(app)

    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)

    /** The current screen state; the composable collects this. */
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    // ------------------------------------------------------------------
    // Write path (r15)
    // ------------------------------------------------------------------

    /** The thin Android write shell (read-back prefill + in-place writes). */
    private val metadataWriter = MediaMetadataWriter(app.contentResolver)

    /**
     * The write-grant escalation machine, scoped to THIS ViewModel (one
     * editor session at a time); reset whenever a new session opens so a
     * denial never poisons the next edit.
     */
    private val grantMachine = WriteGrantStateMachine()

    /**
     * The item context the editor needs, keyed `mediaId|uri` exactly like
     * [cache] (so a cached navigation back to an earlier item restores ITS
     * session, never the last one's --- an editor must never target the
     * wrong file). Written by every successful [resolve].
     */
    private data class EditSession(
        val mediaId: Long,
        val uri: Uri,
        val mimeType: String,
        val eligible: EligiblePublicMedia,
        /** The writer's read-back for the editable tags, or `null` when the
         * format is outside the pinned write set / values unreadable. */
        val values: EditableExifCurrentValues?,
    )

    private val editSessions = HashMap<String, EditSession>()

    /** The write input awaiting the system grant dialog's answer. */
    private var pendingWrite: Pair<MediaEditField, MetadataEditInput>? = null

    private val _editor = MutableStateFlow<MetadataEditorState>(MetadataEditorState.Closed)

    /** The editor session state; the composable collects this. */
    val editor: StateFlow<MetadataEditorState> = _editor.asStateFlow()

    /**
     * One-shot editor events (system dialogs), never replayed --- the
     * screen collects from first composition, which precedes any
     * user-triggered write (same pattern as `FileOpsViewModel.events`).
     */
    private val _editEvents = MutableSharedFlow<MetadataEditEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** The activity-result seams the screen must launch (see [MetadataEditEvent]). */
    val editEvents: SharedFlow<MetadataEditEvent> = _editEvents.asSharedFlow()

    /** Per-item snapshot cache (see the class KDoc); key = `mediaId|uri`. */
    private val cache = HashMap<String, DetailsUiState>()

    /** The most recent routing arguments; a location-state flip re-resolves them. */
    private var lastArgs: Pair<Long, Uri>? = null

    init {
        viewModelScope.launch {
            var last = locationAccess.state.value
            locationAccess.state.collect { current ->
                if (current != last) {
                    last = current
                    cache.clear()
                    // The edit sessions ride along: their GPS read-back and
                    // capability inputs are consent-dependent, so they are
                    // re-derived by the reload below.
                    editSessions.clear()
                    lastArgs?.let { (id, uri) -> load(id, uri) }
                }
            }
        }
    }

    /**
     * Loads (or serves from [cache]) the details for the routed item.
     * Re-invocation with the same arguments is a no-op once resolved.
     */
    fun load(mediaId: Long, uri: Uri) {
        lastArgs = mediaId to uri
        val key = "$mediaId|${uri}"
        cache[key]?.let { cached ->
            _state.value = cached
            return
        }
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.Default) { resolve(mediaId, uri) }
            cache[key] = resolved
            _state.value = resolved
        }
    }

    /** The whole load pipeline, off the main thread (see the class KDoc). */
    private suspend fun resolve(mediaId: Long, uri: Uri): DetailsUiState {
        val app = getApplication<Application>()
        val uriString = uri.toString()

        // 1. Catalogue record: the ride-along shapes + capture-date
        //    provenance. Match by key suffix + canonical Uri (see the
        //    class KDoc for why the key alone is not derivable here).
        val record = findRecord(mediaId, uriString)

        // 2. Eligibility (the vault gate): the catalogue record goes
        //    through the SAME gate the scan pipeline uses; when there is
        //    no record, the fallback identity is re-admitted through
        //    MediaEligibility.asEligible, which refuses anything that
        //    cannot form a well-formed identity.
        val eligible = record?.let { eligibilityGate.eligiblePublicMedia(it) }
            ?: fallbackEligible(mediaId, uri)
        if (eligible == null) return DetailsUiState.Unavailable

        // 3. MIME type: the catalogue's echo when present, else the
        //    provider's own answer for the Uri (best-effort).
        val mimeType = record?.mimeType ?: app.contentResolver.getType(uri) ?: ""

        // 4. Bytes + snapshot. The video path skips the EXIF reader
        //    entirely (the format matrix declares video EXIF
        //    UNSUPPORTED --- a capability statement, honored without
        //    offering the bytes to a parser that cannot read them).
        val isVideo = mimeType.startsWith("video/")
        val capability = MetadataFormatMatrix.capabilitiesFor(mimeType)
        var snapshot: MediaMetadataSnapshot? = null
        var exifBlockAvailability: MetadataAvailability
        var bytesForSize: Long? = null
        var containerCaptureMillis: Long? = null
        if (isVideo) {
            // Video capture dates come from the CONTAINER, not EXIF (the
            // r10 split --- VideoMetadataSource). Best-effort: a container
            // that cannot be demuxed simply contributes no date, and the
            // rows below say so honestly.
            containerCaptureMillis = videoSource.read(uri)?.captureDateMillis
            exifBlockAvailability = capability.exif
        } else if (capability.exif != MetadataAvailability.PRESENT) {
            exifBlockAvailability = capability.exif
        } else {
            val handle = mediaAccess.open(eligible)
            val bytes = handle?.readBytes(MediaReadHandle.DEFAULT_MAX_BYTES)
            bytesForSize = bytes?.size?.toLong()
            snapshot = bytes?.let { exifReader.read(it, mimeType) }
            // The reader returns null for BOTH "no EXIF block" and
            // "block cannot be opened"; the byte read succeeded, so the
            // honest renderable state here is ABSENT (no block found).
            // The UNREADABLE state stays reserved for the failed byte
            // read --- the same split AndroidCaptureDateCandidateSource
            // makes (open-fail -> UNREADABLE, parsed-empty -> ABSENT).
            exifBlockAvailability =
                if (bytes == null) MetadataAvailability.UNREADABLE else MetadataAvailability.ABSENT
        }

        // 5. Rows. The record the mapper sees: the catalogue row with the
        //    freshly counted size when the stored one is missing, and with
        //    the CONTAINER capture date for videos whose catalogue row has
        //    no resolved date (the container instant is epoch-based, so no
        //    zone provenance exists --- the mapper renders the zone row
        //    ABSENT for it). Both fills are read-path conveniences; the
        //    catalogue row itself is never written here (a Details VIEW
        //    stays read-only).
        var effectiveRecord = record
        if (effectiveRecord != null && bytesForSize != null && effectiveRecord.sizeBytes <= 0L) {
            effectiveRecord = effectiveRecord.copy(sizeBytes = bytesForSize)
        }
        if (effectiveRecord != null && effectiveRecord.resolvedCaptureDateMillis == null) {
            effectiveRecord = effectiveRecord.copy(
                resolvedCaptureDateMillis = containerCaptureMillis,
                resolvedCaptureDateSource = if (containerCaptureMillis != null) "CONTAINER" else null,
            )
        }
        if (effectiveRecord == null && containerCaptureMillis != null) {
            // Fallback video item with a container date but no catalogue
            // row: a minimal record-shaped carrier for the provenance the
            // mapper reads. Nothing is persisted --- this lives only in
            // the screen state.
            effectiveRecord = CatalogueRecord(
                localMediaKey = "",
                canonicalUri = uriString,
                mimeType = mimeType,
                sizeBytes = 0L,
                dateModifiedMillis = 0L,
                dateAddedMillis = 0L,
                resolvedCaptureDateMillis = containerCaptureMillis,
                resolvedCaptureDateSource = "CONTAINER",
            )
        }
        val rows = DetailsPresentation.rows(
            snapshot = snapshot,
            record = effectiveRecord,
            mimeType = mimeType,
            exifBlockAvailability = exifBlockAvailability,
            locale = Locale.getDefault(),
            gpsVisible = MediaLocationDisplayRules.Default.shouldDisplayGps(locationAccess.state.value),
            gpsHiddenNote = if (MediaLocationDisplayRules.Default
                    .shouldDisplayGps(locationAccess.state.value)
            ) {
                null
            } else {
                app.getString(R.string.details_gps_hidden)
            },
        )

        // 6. Editable fields (r15). The current values are read back
        //    through the SAME writer shell the edit will go through, and
        //    ONLY where an editor can actually open: the format in the
        //    pinned write set AND the platform at Q+ (below Q the item is
        //    read-only and the rows say so --- no pointless file open).
        //    On API 29+ without the location grant the platform redacts
        //    EXIF location, so the GPS read-back honestly comes back null
        //    and the row's CONSENT_REQUIRED state carries the reason.
        val writableFormat = ExifWriteFormats.isWritable(mimeType) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val editValues = if (writableFormat) metadataWriter.readCurrentValues(uri) else null
        editSessions["$mediaId|$uri"] = EditSession(
            mediaId = mediaId,
            uri = uri,
            mimeType = mimeType,
            eligible = eligible,
            values = editValues,
        )
        val editRows = DetailsPresentation.editableRows(
            eligible = eligible,
            mimeType = mimeType,
            apiLevel = Build.VERSION.SDK_INT,
            locationState = locationAccess.state.value,
            currentValues = editValues,
        )
        return DetailsUiState.Ready(
            rows = rows,
            recordFound = record != null,
            editRows = editRows,
        )
    }

    /**
     * Finds the live catalogue record for the routed item: the row(s)
     * whose canonical Uri equals the route's (one keyed DAO read,
     * [RoomMediaCatalogue.recordsByCanonicalUri] --- r19a; this used to
     * materialize the FULL table through `recordsMap()` on every Details
     * open), narrowed to the one whose key's LAST segment is this
     * MediaStore id (volume-qualified keys end in `:<id>`; a page-id
     * suffix would not be a plain MediaStore row) AND whose metadata cache
     * is not STALE, so two volumes sharing an id never cross-match. The
     * key itself cannot be rebuilt from the route arguments --- the uri's
     * volume segment (`external`) is not the key's volume name
     * (`external_primary` and friends) --- which is why the lookup is
     * uri-keyed and id-suffix-narrowed rather than a primary-key read.
     */
    private suspend fun findRecord(mediaId: Long, uriString: String): CatalogueRecord? {
        val suffix = ":$mediaId"
        return catalogue.recordsByCanonicalUri(uriString).firstOrNull { record ->
            record.localMediaKey.endsWith(suffix) &&
                record.metadataCacheState != MetadataCacheState.STALE
        }
    }

    /**
     * The not-in-catalogue fallback identity (see the class KDoc).
     * `null` when the Uri is not a MediaStore-shaped path --- an
     * unidentifiable item is refused, never read by guesswork.
     */
    private fun fallbackEligible(mediaId: Long, uri: Uri): EligiblePublicMedia? {
        val volume = uri.pathSegments?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return MediaEligibility.PUBLIC_INDEXABLE.asEligible(
            MediaIdentity(
                volumeName = volume,
                mediaStoreId = mediaId,
                canonicalUri = uri.toString(),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Editor session (r15 write path)
    // ------------------------------------------------------------------

    /** The edit session for the item the screen is currently showing, if resolved. */
    private fun currentEditSession(): EditSession? {
        val (mediaId, uri) = lastArgs ?: return null
        return editSessions["$mediaId|$uri"]
    }

    /**
     * Opens the editor for [field] (a row tap): a fresh grant-machine
     * session (a previous denial never poisons this one), the honest
     * capability from the pure gate, and the prefill from the writer's
     * read-back. Only actionable capabilities open --- for
     * [MediaEditCapability.EDITABLE] the editor takes input, for
     * [MediaEditCapability.CONSENT_REQUIRED] it shows the consent view;
     * every other refusal was already narrated by the row's state text
     * (defense in depth: nothing to edit, nothing to fake).
     */
    fun openEditor(field: MediaEditField) {
        val session = currentEditSession() ?: return
        grantMachine.reset()
        val capability = MediaEditGate.capabilityFor(
            eligible = session.eligible,
            mimeType = session.mimeType,
            field = field,
            apiLevel = Build.VERSION.SDK_INT,
            locationState = locationAccess.state.value,
        )
        if (capability != MediaEditCapability.EDITABLE &&
            capability != MediaEditCapability.CONSENT_REQUIRED
        ) {
            return
        }
        _editor.value = MetadataEditorState.Open(
            field = field,
            capability = capability,
            prefill = EditableExifCodec.prefill(field, session.values),
            valuesAvailable = session.values != null,
            phase = MetadataEditorPhase.Input,
        )
    }

    /** Closes the editor; a grant round-trip in flight is abandoned. */
    fun closeEditor() {
        pendingWrite = null
        _editor.value = MetadataEditorState.Closed
    }

    /**
     * Submits the editor's input (Save, or Retry after a failure): the
     * pure codec validates and builds the tag writes, the writer shell
     * applies them off the main thread, and the pure classifier decides
     * the honest outcome. Invalid input is a no-op (the dialog validates
     * live; this is the defensive second line).
     */
    fun submitEdit(input: MetadataEditInput) {
        val session = currentEditSession() ?: return
        val open = _editor.value as? MetadataEditorState.Open ?: return
        val writes = when (val result = EditableExifCodec.writesFor(open.field, input)) {
            is FieldEditResult.Valid -> result.writes
            is FieldEditResult.Invalid -> return
        }
        viewModelScope.launch {
            _editor.value = open.copy(phase = MetadataEditorPhase.Saving)
            val raw = metadataWriter.write(session.uri, session.mimeType, writes)
            handleWriteOutcome(session, open.field, input, raw)
        }
    }

    /**
     * The answer of the system write-grant dialog: a grant retries the
     * pending write ONCE (the single-shot discipline the machines pin); a
     * denial is terminal for this session and renders as its own state.
     */
    fun onWriteGrantResult(granted: Boolean) {
        val pending = pendingWrite ?: return
        grantMachine.onGrantResult(granted)
        if (granted) {
            submitEdit(pending.second)
        } else {
            pendingWrite = null
            val open = _editor.value as? MetadataEditorState.Open ?: return
            _editor.value = open.copy(phase = MetadataEditorPhase.Denied)
        }
    }

    /**
     * The answer of the ACCESS_MEDIA_LOCATION runtime request (the GPS
     * field's consent view). The result folds into [locationAccess]; the
     * init collector clears the cache and re-resolves, so the rows (and
     * their capabilities) re-render from the new consent state. On a
     * grant the editor closes --- re-tapping the row then prefills with
     * the now-readable coordinates instead of showing a stale empty one.
     */
    fun onLocationConsentResult(granted: Boolean) {
        locationAccess.onPermissionResult(granted)
        if (granted) closeEditor()
    }

    /**
     * The GPS consent view's button: hands the ACCESS_MEDIA_LOCATION
     * request to the screen through [editEvents] (the Compose side owns
     * the `RequestPermission` launcher, the Settings-location-row wiring
     * mirrored).
     */
    fun requestLocationConsent() {
        _editEvents.tryEmit(MetadataEditEvent.RequestLocationConsent)
    }

    /**
     * Pure-classifier verdict to editor state + grant escalation, called
     * on the viewModelScope after the writer returned. If the user
     * dismissed the editor mid-write, the bookkeeping still runs (grant
     * machine, cache) but the editor stays closed --- an abandoned edit
     * never reopens a dialog on its own.
     */
    private fun handleWriteOutcome(
        session: EditSession,
        field: MediaEditField,
        input: MetadataEditInput,
        raw: RawWriteOutcome,
    ) {
        // The classifier reads the grant state as it was BEFORE this
        // attempt (IDLE = the dialog is still available, GRANTED = the
        // retry already used it); the machine transition follows after.
        val outcome = MetadataWriteFlow.classify(raw, Build.VERSION.SDK_INT, grantMachine.current)
        when (outcome) {
            MetadataWriteResult.Success -> {
                grantMachine.onWriteSucceeded()
                pendingWrite = null
                _editor.value = MetadataEditorState.Closed
                // The write changed the file's bytes: drop the cached
                // parse so the next load re-reads the file. The r12 scan
                // pipeline (ContentObserver + reconciler) re-indexes the
                // item on its own pass --- eventual consistency, the
                // contract MediaMetadataWriter's KDoc records.
                cache.remove("${session.mediaId}|${session.uri}")
                load(session.mediaId, session.uri)
            }
            MetadataWriteResult.NeedsGrant -> {
                grantMachine.onWriteBlocked(escalationPossible = true)
                val open = _editor.value as? MetadataEditorState.Open
                if (open == null) {
                    // The user dismissed the editor while the write was in
                    // flight: nothing to retry, no dialog to show.
                    pendingWrite = null
                    return
                }
                pendingWrite = field to input
                escalateForWriteGrant(session, open)
            }
            is MetadataWriteResult.Refused -> {
                if (raw == RawWriteOutcome.SECURITY) {
                    grantMachine.onWriteBlocked(
                        escalationPossible = Build.VERSION.SDK_INT >= MetadataWriteFlow.API_30,
                    )
                }
                (_editor.value as? MetadataEditorState.Open)?.let { open ->
                    _editor.value = open.copy(phase = MetadataEditorPhase.Failed(outcome.reason))
                }
            }
        }
    }

    /**
     * Builds and emits the system write-grant request for one item
     * (`MediaStore.createWriteRequest`, API 30+ --- the classifier only
     * says NeedsGrant there, so the guard is defense in depth that lint
     * can see). A failure to even build the request surfaces as the
     * honest still-blocked refusal, never a crash.
     */
    private fun escalateForWriteGrant(session: EditSession, open: MetadataEditorState.Open) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sender = MediaStore.createWriteRequest(
                    getApplication<Application>().contentResolver,
                    listOf(session.uri),
                ).intentSender
                grantMachine.onRequestLaunched()
                _editEvents.tryEmit(MetadataEditEvent.RequestWriteGrant(sender))
            } catch (e: RuntimeException) {
                grantMachine.onWriteBlocked(escalationPossible = false)
                _editor.value = open.copy(
                    phase = MetadataEditorPhase.Failed(WriteRefusalReason.READ_ONLY_STILL_BLOCKED),
                )
            }
        } else {
            // Unreachable in production (the classifier never escalates
            // below API 30); kept honest instead of crashing.
            grantMachine.onWriteBlocked(escalationPossible = false)
            _editor.value = open.copy(
                phase = MetadataEditorPhase.Failed(WriteRefusalReason.READ_ONLY_PLATFORM),
            )
        }
    }
}
