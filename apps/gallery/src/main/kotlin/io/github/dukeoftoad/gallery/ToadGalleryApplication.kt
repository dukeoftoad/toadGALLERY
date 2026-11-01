package io.github.dukeoftoad.gallery

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.dukeoftoad.gallery.data.catalogue.room.MediaCatalogueDatabase
import io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.access.ContentResolverMediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadPermissionGate
import io.github.dukeoftoad.gallery.data.media.changes.AndroidMediaScanRowSource
import io.github.dukeoftoad.gallery.data.media.changes.AndroidMediaStoreChangeFeed
import io.github.dukeoftoad.gallery.data.media.changes.MediaScanDriver
import io.github.dukeoftoad.gallery.data.media.dates.AndroidCaptureDateCandidateSource
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateIndexer
import io.github.dukeoftoad.gallery.data.media.dates.CatalogueRecordEligibilityGate
import io.github.dukeoftoad.gallery.data.media.location.AndroidGpsCandidateSource
import io.github.dukeoftoad.gallery.data.media.location.AndroidMediaLocationAccess
import io.github.dukeoftoad.gallery.data.media.location.CompositeScanIndexHook
import io.github.dukeoftoad.gallery.data.media.location.ItemLocationWriter
import io.github.dukeoftoad.gallery.data.media.location.LocationIndexer
import io.github.dukeoftoad.gallery.data.media.location.LocationReadGate

/**
 * toadGALLERY's [Application]; exists to wire Coil's video frame decoder into
 * the app-wide image loader (issue #33).
 *
 * Explicit registration is required because Coil 2.x has NO service-registry
 * or classpath auto-discovery for its optional decoder artifacts --- the
 * reflection-based auto-detection was a Coil 1.x mechanism, removed in 2.0.
 * Adding the `coil-video` dependency alone therefore changes nothing: every
 * `AsyncImage` that does not take an explicit loader resolves the app-wide
 * loader via `context.imageLoader`, which asks the application for an
 * [ImageLoaderFactory] and otherwise builds a default `ImageLoader` whose
 * component registry holds only `BitmapFactoryDecoder` plus the standard
 * fetchers. BitmapFactory cannot decode a frame out of a video container, so
 * video content URIs would decode-fail and render blank cells in the
 * staggered grid (only the duration chip of `MediaThumb` would show). This
 * class appends [VideoFrameDecoder.Factory] to the registry, making video
 * thumbnails decode their preview frame locally, as `build.gradle.kts`
 * promises.
 *
 * The manifest names this class via `android:name` on the `<application>`
 * tag; Android instantiates it before any activity, so every `AsyncImage`
 * finds it through `context.imageLoader`.
 *
 * ## Phase 4 scan pipeline (r12)
 * [onCreate] also STARTS the scan driver
 * ([MediaScanDriver.start]), the orchestration layer that wires the r10
 * Phase 4 contracts together: the Room media catalogue
 * ([RoomMediaCatalogue] over [MediaCatalogueDatabase]) is the persistent
 * "what did the media world look like" cache, [AndroidMediaStoreChangeFeed]
 * supplies the per-volume MediaStore generation tokens (API 30+) and the
 * ContentObserver signals (API 26-29), and [AndroidMediaScanRowSource] is
 * the authoritative cursor walk. The driver's first APP_START pass compares
 * the saved scan checkpoints against the live generation tokens, then
 * reconciles whatever moved into the catalogue (adds, changes, tombstones)
 * and stamps fresh checkpoints; every later media change arrives through
 * the observers and triggers a coalesced re-scan. App start is the right
 * trigger because only a checkpoint-vs-live-token comparison can tell what
 * happened while the process was dead --- see [MediaScanDriver] for the
 * full pipeline rationale.
 *
 * [onCreate] itself stays CHEAP: constructing the database (a Room builder
 * does no I/O until first use), the feed and the driver is object
 * allocation only, and `start()` moves the observer registration and the
 * first scan onto a background `Dispatchers.Default` scope --- nothing
 * heavy touches the main thread. The downstream indexing stage
 * (`ScanIndexHook`) is wired as a [CompositeScanIndexHook] of the two
 * indexers the pipeline owns so far --- the capture-date indexer (r12) and
 * the EXIF-GPS location indexer (r16, feeding the Map tab's Room v4
 * location columns) --- with per-hook failure isolation, so one indexer
 * throwing can never starve its sibling or the scan itself.
 *
 * ## Diagnostics boot line (W1)
 * [onCreate] writes the diagnostics ring's FIRST entry --- one INFO line
 * (tag "ToadApp") naming the running build (`process start <versionName>
 * (build <versionCode>)`). A ring snapshot taken on a user device then
 * always says WHICH build produced the lines above it, which is the whole
 * point of the W1 on-device story (the versionCode-11 "vault PIN creating
 * failed" report carried no such line). Nothing else changes here: the
 * line is pure [DiagLog] bookkeeping with no secrets (ADR-0014).
 *
 * Privacy (ADR-0006 / ADR-0014): [VideoFrameDecoder] decodes frames purely
 * on-device through `MediaMetadataRetriever`; `coil-video` is a decoder
 * artifact, not a network component, and this builder adds no network code.
 * The offline guarantee rests on the app never holding the INTERNET
 * permission --- enforced at build time by the merged-manifest privacy gate
 * and proven at runtime by `PrivacyEgressTest` --- which a locally built
 * `ImageLoader` cannot weaken.
 */
class ToadGalleryApplication : Application(), ImageLoaderFactory {

    /**
     * The process-wide media catalogue (r13): ONE Room instance shared by
     * the scan pipeline below, the catalogue-backed Photos timeline
     * (`PhotosViewModel`) and the r16 Map tab (`MapViewModel`). Lazy, so
     * `onCreate` stays allocation-only until the first reader --- the scan
     * driver's start or the timeline's first generation --- actually opens
     * the database.
     *
     * Why the Application is the holder, not a second build: Room's
     * invalidation (what the catalogue's `changes()` flow rides on) only
     * reaches observers of the SAME database instance, so a second
     * instance over the same file would silently miss every change the
     * scan pipeline writes. "One per process, via whatever dependency
     * holder the app settles on" (`MediaCatalogueDatabase.build`'s KDoc)
     * settles on this.
     *
     * r16: the declared type is the CONCRETE [RoomMediaCatalogue], not the
     * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue] interface
     * --- the map's location surface (the geotagged-row projection, the
     * targeted location-column write) exists only on the Room
     * implementation this round (the interface + in-memory reference are
     * the Phase 5 Places index's contract to widen). Every existing
     * consumer that types against the interface keeps compiling unchanged
     * (the Room implementation implements it).
     */
    val mediaCatalogue: RoomMediaCatalogue by lazy {
        RoomMediaCatalogue(MediaCatalogueDatabase.build(this).catalogueDao())
    }

    /**
     * Writes the W1 boot line (see the class KDoc): the build identity as
     * the diagnostics ring's first entry. Read from PackageManager for
     * this very package (it cannot be missing --- the process is running
     * it); `longVersionCode` is API 28+, so the `SDK_INT` guard stays
     * lexically visible for NewApi lint (ADR-0016) and the deprecated
     * `versionCode` field serves the minSdk 26-27 leg.
     */
    override fun onCreate() {
        super.onCreate()
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") // pre-P spelling of the same number; guarded above
            info.versionCode.toLong()
        }
        DiagLog.i(TAG, "process start ${info.versionName ?: "unknown"} (build $build)")
        startPhase4ScanPipeline()
    }

    /**
     * Constructs and starts the Phase 4 scan pipeline (see the class KDoc
     * section). One process-lifetime [CoroutineScope] with a
     * [SupervisorJob]: a failed scan coroutine must never cancel sibling
     * work, and the scope outlives every Activity by design --- the
     * catalogue is process state, not screen state.
     *
     * The diagnostics ring gets a start line too: a device report that
     * shows the boot line but NOT this line pins a pipeline-construction
     * failure to the exact seam (the W1 lesson --- see the boot-line KDoc).
     */
    private fun startPhase4ScanPipeline() {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The ONE process-wide catalogue instance (see [mediaCatalogue]):
        // the driver, its downstream hooks AND the Photos/Map screens all
        // talk to the same Room database through it, so the timeline's and
        // the map's `changes()` collections see every write the pipeline
        // commits.
        val catalogue = mediaCatalogue
        // ONE read boundary shared by both indexers (r16): the capture-date
        // and location passes each read the same rows' bytes; a single
        // [MediaAccess] instance keeps the read-time permission re-check
        // and handle minting in one place.
        val mediaAccess: MediaAccess = ContentResolverMediaAccess(
            resolver = contentResolver,
            permissionGate = MediaReadPermissionGate(this),
        )
        // The eligibility gate is date-agnostic by construction ("may this
        // process read this record's bytes"), so the location indexer
        // reuses the same production gate as the capture-date pass.
        val eligibilityGate = CatalogueRecordEligibilityGate()
        val captureDateHook = CaptureDateIndexer(
            catalogue = catalogue,
            mediaAccess = mediaAccess,
            candidateSource = AndroidCaptureDateCandidateSource(),
            eligibilityGate = eligibilityGate,
        )
        // The r16 location pass: EXIF GPS through the same read boundary,
        // persisted via the catalogue's targeted location write (Room v4).
        // The read gate consults the SAME r14 consent machinery
        // ([AndroidMediaLocationAccess]) the Settings row and the Details
        // display gate use --- on API 29+ an ungranted ACCESS_MEDIA_LOCATION
        // means the platform redacts EXIF location, so the indexer skips
        // the bytes and persists the honest withheld provenance instead.
        val locationAccess = AndroidMediaLocationAccess(this)
        val locationHook = LocationIndexer(
            locationWriter = ItemLocationWriter { key, latitude, longitude, sourceName ->
                catalogue.updateItemLocation(key, latitude, longitude, sourceName)
            },
            mediaAccess = mediaAccess,
            candidateSource = AndroidGpsCandidateSource(),
            eligibilityGate = eligibilityGate,
            locationReadGate = LocationReadGate { locationAccess.isGranted() },
        )
        val scanDriver = MediaScanDriver(
            rowSource = AndroidMediaScanRowSource(this),
            catalogue = catalogue,
            changeFeed = AndroidMediaStoreChangeFeed(this),
            scanIndexHook = CompositeScanIndexHook(listOf(captureDateHook, locationHook)),
            scanScope = appScope,
        )
        scanDriver.start(appScope)
        DiagLog.i(
            TAG,
            "phase 4 scan driver started " +
                "(catalogue + change feed + capture-date and location indexers wired)",
        )
    }

    /**
     * The app-wide loader used by every `AsyncImage`: the default registry
     * (`BitmapFactoryDecoder` + standard fetchers) plus
     * [VideoFrameDecoder.Factory] for video content URIs. GIF cells need no
     * extra decoder --- `BitmapFactoryDecoder` already decodes a GIF's first
     * frame, which is all a static thumbnail shows.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

    private companion object {
        /** Diagnostics tag for the app shell's boot line (W1). */
        const val TAG = "ToadApp"
    }
}
