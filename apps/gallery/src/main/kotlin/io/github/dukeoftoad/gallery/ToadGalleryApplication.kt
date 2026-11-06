package io.github.dukeoftoad.gallery

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import io.github.dukeoftoad.gallery.data.catalogue.room.MediaCatalogueDatabase
import io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.access.ContentResolverMediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadPermissionGate
import io.github.dukeoftoad.gallery.data.media.changes.AndroidMediaScanRowSource
import io.github.dukeoftoad.gallery.data.media.changes.AndroidMediaStoreChangeFeed
import io.github.dukeoftoad.gallery.data.media.changes.AndroidVolumeMonitor
import io.github.dukeoftoad.gallery.data.media.changes.AndroidCombinedExifSource
import io.github.dukeoftoad.gallery.data.media.changes.CombinedExifIndexer
import io.github.dukeoftoad.gallery.data.media.changes.MediaScanDriver
import io.github.dukeoftoad.gallery.data.media.changes.ProvenanceHealingPass
import io.github.dukeoftoad.gallery.data.media.dates.CatalogueRecordEligibilityGate
import io.github.dukeoftoad.gallery.data.media.location.AndroidMediaLocationAccess
import io.github.dukeoftoad.gallery.data.media.location.ItemLocationWriter
import io.github.dukeoftoad.gallery.data.media.location.LocationReadGate
import io.github.dukeoftoad.gallery.ml.engine.BundledFirstModelDownloader
import io.github.dukeoftoad.gallery.ml.engine.BundledModelAssets
import io.github.dukeoftoad.gallery.ml.engine.HttpModelDownloader
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner

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
 * (`ScanIndexHook`) is wired through the combined EXIF indexer
 * ([CombinedExifIndexer] --- the capture-date indexer of r12 plus the
 * EXIF-GPS location indexer of r16 feeding the Map tab's Room v4
 * location columns, failure-isolated per hook). r50 note: the ML queues
 * (face/clip) are NOT wired to the scan feed --- they start only from
 * their Settings cards (a recorded follow-up, not an oversight; see
 * follow-up.md).
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
 * Privacy (ADR-0006 / ADR-0014, amended by ADR-0020): [VideoFrameDecoder]
 * decodes frames purely on-device through `MediaMetadataRetriever`;
 * `coil-video` is a decoder artifact, not a network component, and this
 * builder adds no network code. Since r22 the app holds the INTERNET
 * permission for exactly the ADR-0020 model fetch (the [modelProvisioner]
 * above; pinned single host, verified bytes) --- enforced at build time by
 * the merged-manifest privacy gate + allowlist protocol and at runtime by
 * the privacy suites; media, embeddings and indexes never cross the
 * network.
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
     * The process-wide model provisioner (r22, ADR-0020):
     * verify-then-stage-then-commit delivery of the pinned FACE/SEMANTIC
     * assets into `filesDir/models`, over the ADR-0020 transport stack:
     * BUNDLED model-pack bytes first (r28: builds assembled with
     * `-PwithBundledModels` ship `assets/models/` and a device
     * provisioned from them never opens a socket), then the REAL HTTP
     * transport ([HttpModelDownloader] --- the single allowlisted host;
     * the INTERNET permission exists for exactly this fetch, see the
     * manifest). Lazy like [mediaCatalogue]: constructing it allocates
     * nothing, opens no files and touches no network --- the first
     * reader is the Settings dogfood section's download/self-test
     * action. One instance per process so the receipt file is never
     * concurrently rewritten.
     */
    val modelProvisioner: ModelProvisioner by lazy {
        ModelProvisioner(
            storeDir = File(filesDir, "models"),
            downloader = BundledFirstModelDownloader(
                bundled = BundledModelAssets { fileName ->
                    // A normal build ships no assets/models/ directory:
                    // the lookup throws and degrades to null (the network
                    // path). Android-adjacent wiring only; the bundled-first
                    // logic is JVM-tested in BundledModelSourceTest.
                    runCatching { assets.open("models/$fileName") }.getOrNull()
                },
                network = HttpModelDownloader(),
            ),
        )
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
        // ONE read boundary for the index pass (r20): the combined EXIF
        // indexer reads each row's bytes ONCE and extracts BOTH provenance
        // dimensions from the single read, where the r12/r16 pair of
        // standalone indexers each opened and streamed the same bytes.
        val mediaAccess: MediaAccess = ContentResolverMediaAccess(
            resolver = contentResolver,
            permissionGate = MediaReadPermissionGate(this),
        )
        // The eligibility gate is dimension-agnostic by construction ("may
        // this process read this record's bytes"), so the combined pass
        // reuses the same production gate both standalone indexers used.
        val eligibilityGate = CatalogueRecordEligibilityGate()
        // The r14 consent machinery decides whether the GPS HALF of the
        // combined evidence may be consulted ([AndroidMediaLocationAccess]
        // --- on API 29+ an ungranted ACCESS_MEDIA_LOCATION means the
        // platform redacts EXIF location, so the pass persists the honest
        // withheld provenance instead of reading redacted bytes).
        val locationAccess = AndroidMediaLocationAccess(this)
        val combinedIndexer = CombinedExifIndexer(
            catalogue = catalogue,
            locationWriter = ItemLocationWriter { key, latitude, longitude, sourceName ->
                catalogue.updateItemLocation(key, latitude, longitude, sourceName)
            },
            healingStore = catalogue,
            mediaAccess = mediaAccess,
            combinedSource = AndroidCombinedExifSource(),
            eligibilityGate = eligibilityGate,
            locationReadGate = LocationReadGate { locationAccess.isGranted() },
        )
        // The NULL-provenance healing pass (r20): re-offers rows whose
        // indexing never completed --- selected by the v6 stamp column ---
        // bounded per app start (see [ProvenanceHealingPass]).
        val healingPass = ProvenanceHealingPass(
            healingStore = catalogue,
            indexHook = combinedIndexer,
        )
        val scanDriver = MediaScanDriver(
            rowSource = AndroidMediaScanRowSource(this),
            catalogue = catalogue,
            changeFeed = AndroidMediaStoreChangeFeed(this),
            scanIndexHook = combinedIndexer,
            scanScope = appScope,
            healingPass = healingPass,
        )
        scanDriver.start(appScope)
        // Volume hot-plug (r20): a storage volume mounted after process
        // start registers its observers and gets a coalesced scan pass.
        // Held for the process lifetime (the receiver is process state,
        // like the driver itself).
        AndroidVolumeMonitor(this) { scanDriver.onVolumesChanged() }.register()
        DiagLog.i(
            TAG,
            "phase 4 scan driver started " +
                "(catalogue + change feed + combined EXIF indexer + healing pass + volume hot-plug wired)",
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
