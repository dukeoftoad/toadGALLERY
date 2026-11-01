package io.github.dukeoftoad.gallery.ui.details

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.catalogue.room.MediaCatalogueDatabase
import io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
import io.github.dukeoftoad.gallery.data.media.access.ContentResolverMediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.access.MediaReadPermissionGate
import io.github.dukeoftoad.gallery.data.media.dates.CatalogueRecordEligibilityGate
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.data.media.location.AndroidMediaLocationAccess
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationDisplayRules
import io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.exif.AndroidExifReader
import io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix
import io.github.dukeoftoad.gallery.metadata.video.MediaMetadataRetrieverVideoMetadataSource
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailRow
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Details screen renders for one item, in its three honest
 * states: still reading, rendered rows (see [DetailRow]), or structurally
 * unreadable --- an item the eligibility gate refuses (vault, trashed,
 * permission-revoked) is NEVER read, not "shown empty": the read boundary
 * ([MediaAccess] takes only an [io.github.dukeoftoad.gallery.data.media
 * .identity.EligiblePublicMedia]) makes ineligible bytes unrepresentable,
 * and the screen narrates that instead of pretending.
 */
sealed interface DetailsUiState {

    /** The catalogue lookup + byte read are still in flight. */
    data object Loading : DetailsUiState

    /** The ordered display rows; `recordFound` is false when the item was not in the catalogue. */
    data class Ready(val rows: List<DetailRow>, val recordFound: Boolean) : DetailsUiState

    /** The item is not readable through the public read boundary (ineligible/revoked). */
    data object Unavailable : DetailsUiState
}

/**
 * State holder for the Phase 4 metadata Details screen (roadmap
 * "Metadata Details view with read"). DI-less by repo convention: the
 * process-wide catalogue and the read boundary are constructed HERE from
 * the [Application], exactly the seams
 * [ToadGalleryApplication.startPhase4ScanPipeline] wires for the scan
 * driver --- [RoomMediaCatalogue] over the one Room database,
 * [ContentResolverMediaAccess] behind [MediaReadPermissionGate], and
 * [CatalogueRecordEligibilityGate] as the type-level vault gate. A
 * screen-scoped ViewModel (not shell-scoped) because the details state is
 * per-item transitive UI state, unlike the viewer window hand-off.
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
 * NOT JVM-unit-tested, by design (ADR-0016): this class is Android
 * plumbing --- catalogue/ContentResolver/retriever wiring around the pure,
 * tested [DetailsPresentation]; the same exemption the other
 * AndroidViewModels in the repo carry.
 */
class DetailsViewModel(app: Application) : AndroidViewModel(app) {

    private val catalogue = RoomMediaCatalogue(
        MediaCatalogueDatabase.build(app).catalogueDao(),
    )

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
        return DetailsUiState.Ready(rows = rows, recordFound = record != null)
    }

    /**
     * Finds the live catalogue record for the routed item: the key whose
     * LAST segment is this MediaStore id (volume-qualified keys end in
     * `:<id>`; a page-id suffix would not be a plain MediaStore row) AND
     * whose canonical Uri matches, so two volumes sharing an id never
     * cross-match. Full-table scan, one call per Details open: the
     * catalogue is thousands of small rows and the alternative (a
     * volume-aware route argument) does not exist yet.
     */
    private suspend fun findRecord(mediaId: Long, uriString: String): CatalogueRecord? {
        val suffix = ":$mediaId"
        return catalogue.recordsMap().values.firstOrNull { record ->
            record.localMediaKey.endsWith(suffix) &&
                record.canonicalUri == uriString &&
                record.metadataCacheState != MetadataCacheState.STALE
        }
    }

    /**
     * The not-in-catalogue fallback identity (see the class KDoc).
     * `null` when the Uri is not a MediaStore-shaped path --- an
     * unidentifiable item is refused, never read by guesswork.
     */
    private fun fallbackEligible(mediaId: Long, uri: Uri): io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia? {
        val volume = uri.pathSegments?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return MediaEligibility.PUBLIC_INDEXABLE.asEligible(
            MediaIdentity(
                volumeName = volume,
                mediaStoreId = mediaId,
                canonicalUri = uri.toString(),
            ),
        )
    }
}
