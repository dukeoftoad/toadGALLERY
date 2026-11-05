package io.github.dukeoftoad.gallery.ui.screenshot

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.dukeoftoad.gallery.MainActivity
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Roborazzi README captures of the REAL app shell (the toadFILES pattern,
 * Phase 11 screenshot pipeline round two): unlike the three state-card
 * baselines (ErrorState / LoadingState / UnsupportedFileState), this suite
 * hosts [MainActivity] itself (`setContent { ToadGalleryApp() }`) and walks
 * the real bottom navigation --- the five top-level destinations the shell
 * pins in `Destinations.topLevel` --- with a SEEDED media world behind it.
 *
 * The seeding is the whole trick, and it is deliberately two-sided:
 *
 *  1. **A fake MediaStore provider.** One [FakeMediaProvider] is registered
 *     under the `media` authority through
 *     `ShadowContentResolver.registerProviderInternal`, answering every
 *     `content://media/...` query with the same FIXED twelve rows (any
 *     collection shape --- `external_primary/file`, `external/images/media`,
 *     ... --- gets the same set; the row identity is the trailing `_id`).
 *     Every read media permission is granted up front, so no permission
 *     card intercepts the first composition.
 *  2. **Real JPEG bytes on the real filesystem.** Each row's thumbnail is a
 *     generated 240x180 JPEG (Bitmap + Canvas under NATIVE graphics), so the
 *     app's own Coil pipeline (`ToadGalleryApplication.newImageLoader`)
 *     decodes REAL pixels through `openFile` --- no ImageLoader swap is
 *     needed (Coil 2 has no global loader setter; the app's
 *     ImageLoaderFactory loader is used as-is).
 *  3. **A pre-seeded catalogue.** The same twelve rows are upserted into the
 *     process-wide Room catalogue (`ToadGalleryApplication.mediaCatalogue`)
 *     BEFORE the activity launches, because the Photos timeline is
 *     catalogue-backed (`PhotosViewModel`). The provider and the catalogue
 *     describe the SAME world: whatever the app-start scan pipeline does
 *     with it, the two truths cannot disagree.
 *
 * Rule order matters and is pinned: `seed` is declared BEFORE `compose`, so
 * JUnit makes it the OUTERMOST rule and the media world exists before the
 * activity (and its first composition) starts.
 *
 * Known determinism caveats, honestly recorded (lead review):
 *  - the app-start Phase 4 scan (`ToadGalleryApplication.onCreate` ->
 *    `MediaScanDriver.start`) runs on Dispatchers.Default BEFORE any test
 *    rule executes. If a volume walk completed against a not-yet-registered
 *    provider it reports zero rows; a reconcile landing in the microseconds
 *    between that walk and this rule's catalogue upsert could tombstone the
 *    seeds. The window is microscopic (the walk finishes at app boot, rules
 *    run later) and a lost race fails loudly (`timeline_header` never
 *    appears), never as a silently wrong PNG.
 *  - captures write EXPLICIT `roborazzi/NN-name.png` paths (the toadFILES
 *    convention), so the module's `roborazzi.record.namingStrategy =
 *    fullyQualifiedName` property does not apply here; running more than one
 *    flavor's unit tests in one build overwrites the same file names (the
 *    existing baseline suite has the same property, which is why
 *    `screenshots.yml` records one flavor).
 *  - the suite must stay green under EVERY flavor variant
 *    (`BuildShapeTest`'s rule): all readiness tags used here are OUTSIDE the
 *    engine `BuildShape.withEngine` gates, so basic / engine / semantic /
 *    face all reach them. The Settings capture legitimately DIFFERS per
 *    flavor (the AI section is engine-gated).
 *
 * Navigation is driven exactly the way a user drives it: the bottom
 * `NavigationBar`'s items carry their meaning in the LABEL (`Text(stringResource(...))`
 * --- the icons are decorative), so each tab is opened by clicking its
 * `nav_*` label text and confirmed by a screen-specific readiness tag before
 * the capture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ReadmeScreenshotsTest {

    /**
     * The media-world seeding rule. Declared FIRST so it wraps the compose
     * rule (JUnit applies the first-declared rule outermost): the fake
     * provider, the seed files and the catalogue rows all exist before
     * [MainActivity] launches.
     */
    @get:Rule
    val seed = SeedMediaRule()

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun readme01Photos() {
        // The one test that needs the runtime-permission grant DELIVERED BY
        // HAND. Robolectric 4.17's ShadowActivity.requestPermissions records
        // the request and then calls the REAL Activity implementation, which
        // startActivityForResult()es into a system permission UI that does
        // not exist on the JVM --- the grant callback NEVER fires, the
        // ViewModel stays in PhotosState.Loading, and no state tag ever
        // composes (the first CI round caught exactly that: a 20s timeout
        // with neither the chips row nor a timeline header on screen). The
        // fix is the documented Robolectric pattern: read the shadow-recorded
        // request and feed onRequestPermissionsResult back into the activity
        // (ComponentActivity's registry dispatches it to the
        // RequestMultiplePermissions callback, which routes to
        // onPermissionGranted exactly as a real grant would).
        compose.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(compose.activity).lastRequestedPermission != null
        }
        val request = Shadows.shadowOf(compose.activity).lastRequestedPermission
        compose.activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        // The grid state tag (photos_state_grid) is the shell's documented
        // state-tag scheme: it composes when the generation publishes Ready
        // and the paged timeline has items.
        waitForAnyTag(TAG_PHOTOS_GRID)
        // Best effort, never fatal: give the paged grid extra seconds so the
        // capture shows month headers + decoded thumbnails rather than the
        // first screenful still settling.
        runCatching { compose.waitUntil(timeoutMillis = 10_000) { hasTag(TAG_TIMELINE_HEADER) } }
        capture("01-photos.png")
    }

    @Test
    fun readme02Albums() {
        openTab(R.string.nav_albums)
        // Whichever Albums state the seeded world produces: the collections
        // section, the favorites card, or (never expected) the empty state.
        waitForAnyTag(TAG_ALBUMS_COLLECTIONS, TAG_ALBUMS_FAVORITES, TAG_ALBUMS_EMPTY)
        capture("02-albums.png")
    }

    @Test
    fun readme03Search() {
        openTab(R.string.nav_search)
        // The search field is the tab's permanent chrome.
        waitForAnyTag(TAG_SEARCH_FIELD)
        capture("03-search.png")
    }

    @Test
    fun readme04Map() {
        openTab(R.string.nav_map)
        // The seeded rows carry no EXIF GPS, so the honest states are the
        // empty map or (depending on the location-consent gate) the consent
        // card; ready covers the geotagged shape for completeness.
        waitForAnyTag(TAG_MAP_READY, TAG_MAP_EMPTY, TAG_MAP_CONSENT)
        capture("04-map.png")
    }

    @Test
    fun readme05Settings() {
        openTab(R.string.nav_settings)
        // The Layout section is the first Settings block; it composes on
        // every flavor (the engine-gated AI section sits far below it).
        waitForAnyTag(TAG_SETTINGS_LAYOUT)
        capture("05-settings.png")
    }

    // ---- helpers -----------------------------------------------------------------

    /** Captures the whole app shell to the explicit README path. */
    private fun capture(fileName: String) {
        // Settle BEFORE capturing (the toadFILES settle pattern): Coil
        // decodes thumbnails on background dispatchers that compose idling
        // does not track, and the first CI round's albums capture beat the
        // decode (real album tiles, blank covers). A bounded wall-clock
        // loop of waitForIdle + short sleeps gives the decodes time to land
        // without ever hanging a broken run.
        val deadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < deadline) {
            runCatching { compose.waitForIdle() }
            Thread.sleep(100)
        }
        runCatching { compose.waitForIdle() }
        compose.onRoot().captureRoboImage("roborazzi/$fileName")
    }

    /** Opens a top-level tab by its bottom-bar LABEL (the icon is decorative). */
    private fun openTab(labelRes: Int) {
        val label = compose.activity.getString(labelRes)
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Waits until ANY of [tags] is composed (each tag is screen-verified). */
    private fun waitForAnyTag(vararg tags: String) {
        compose.waitUntil(timeoutMillis = 20_000) { tags.any { hasTag(it) } }
    }

    private companion object {
        /** Photos: the shell's state-tag scheme (PhotosScreen KDoc); grid =
         *  Ready + items (MediaPagingGrid.kt's timeline_header rides inside). */
        const val TAG_PHOTOS_GRID = "photos_state_grid"
        const val TAG_TIMELINE_HEADER = "timeline_header"

        /** Albums: AlbumsScreen.kt:625 / :741 / :219. */
        const val TAG_ALBUMS_COLLECTIONS = "albums_collections"
        const val TAG_ALBUMS_FAVORITES = "albums_favorites_card"
        const val TAG_ALBUMS_EMPTY = "albums_state_empty"

        /** Search: SearchScreen.kt:187. */
        const val TAG_SEARCH_FIELD = "search_field"

        /** Map: MapScreen.kt:140 / :123 / :107. */
        const val TAG_MAP_READY = "map_state_ready"
        const val TAG_MAP_EMPTY = "map_state_empty"
        const val TAG_MAP_CONSENT = "map_state_consent"

        /** Settings: SettingsScreen.kt:248 (first block, outside the engine gates). */
        const val TAG_SETTINGS_LAYOUT = "settings_layout_grid"
    }
}

// The media-world seeding ------------------------------------------------------------------

/**
 * The outermost rule: grants the read-media permissions, writes the seed
 * JPEGs, registers the [FakeMediaProvider] under the `media` authority and
 * upserts the matching catalogue rows --- all BEFORE the compose rule
 * launches the activity. (NOT private: the @get:Rule property exposing it
 * is public, and Kotlin rejects a public property exposing a
 * private-in-file type.)
 */
class SeedMediaRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication() as ToadGalleryApplication
                // All read-media permissions the app declares in the merged
                // manifest, granted up front so no permission card intercepts
                // the first composition.
                Shadows.shadowOf(app).grantPermissions(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
                val media = SeedMedia.install(app)
                // Provider FIRST, then catalogue rows: any late volume walk
                // from the app-start scan finds the same world the catalogue
                // describes.
                ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider(media.rows))
                runBlocking { app.mediaCatalogue.upsertAll(media.records) }
                base.evaluate()
            }
        }
}

/** The seeded world handed to both the fake provider and the catalogue. */
private class SeededMedia(
    val rows: List<SeedRow>,
    val records: List<CatalogueRecord>,
)

/**
 * Builds the FIXED media world: twelve 240x180 JPEG photos across three
 * months (May/June/July 2024, so the timeline renders several month
 * headers) and two folders ("Camera", "Screenshots"), one favorite.
 */
private object SeedMedia {

    private const val VOLUME = "external_primary"
    private const val DAY_MILLIS = 86_400_000L

    /** 2024-05-12T00:00:00Z --- the fixed (deterministic) seed epoch. */
    private const val BASE_MILLIS = 1_715_472_000_000L

    private val PALETTE = listOf(
        0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
        0xFF42A5F5, 0xFF66BB6A, 0xFFFFCA28, 0xFFFF7043,
    ).map { it.toInt() }

    fun install(app: ToadGalleryApplication): SeededMedia {
        val dir = File(app.filesDir, "readme-seed")
        dir.mkdirs()
        val rows = (0 until 12).map { i ->
            val id = 101L + i
            val file = File(dir, "seed_$id.jpg")
            writeJpeg(file, PALETTE[i % PALETTE.size], 0x33FFFFFF)
            SeedRow(
                id = id,
                file = file,
                name = "IMG_$id.jpg",
                // Every 5th day from the seed epoch: three distinct months.
                addedMillis = BASE_MILLIS + (i * 5L * DAY_MILLIS),
                bucketId = if (i < 8) 3001L else 3002L,
                bucketName = if (i < 8) "Camera" else "Screenshots",
                width = 240,
                height = 180,
                favorite = i == 3,
            )
        }
        val records = rows.map { row ->
            CatalogueRecord(
                localMediaKey = "$VOLUME:${row.id}",
                // The Q+ scan-pipeline shape (`MediaStore.Files.getContentUri`
                // + row id); the fake provider serves ANY `content://media/...`
                // shape by trailing id.
                canonicalUri = "content://media/$VOLUME/file/${row.id}",
                mimeType = row.mimeType,
                sizeBytes = row.file.length(),
                dateModifiedMillis = row.addedMillis,
                dateAddedMillis = row.addedMillis,
                displayName = row.name,
                width = row.width,
                height = row.height,
                isFavorite = row.favorite,
                bucketId = row.bucketId,
                bucketDisplayName = row.bucketName,
                resolvedCaptureDateMillis = row.addedMillis,
                lastSeenAtMillis = row.addedMillis,
            )
        }
        return SeededMedia(rows, records)
    }

    /**
     * One real JPEG under NATIVE graphics (the same Skia path the app's Coil
     * pipeline decodes with). The PNG fallback keeps the file non-empty even
     * if a runtime cannot encode JPEG --- thumbnails degrade, rows do not.
     */
    private fun writeJpeg(file: File, baseColor: Int, accentColor: Int) {
        val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(baseColor)
            val paint = Paint().apply { color = accentColor }
            canvas.drawRect(16f, 16f, 224f, 88f, paint)
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * One fixed MediaStore row. [column] answers any requested projection
 * column: the known ones from the pinned map, unknown ones as 0 (the app's
 * reads are absence-tolerant and typed; an unknown column never crashes a
 * walk).
 */
private class SeedRow(
    val id: Long,
    val file: File,
    val name: String,
    val addedMillis: Long,
    val bucketId: Long,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val favorite: Boolean,
) {
    val mimeType: String = "image/jpeg"

    private val columns: Map<String, Any?> = mapOf(
        MediaStore.MediaColumns._ID to id,
        MediaStore.MediaColumns.MIME_TYPE to mimeType,
        MediaStore.MediaColumns.SIZE to file.length(),
        // MediaStore DATE_* columns are SECONDS; the scan multiplies by 1000.
        MediaStore.MediaColumns.DATE_MODIFIED to (addedMillis / 1000L),
        MediaStore.MediaColumns.DATE_ADDED to (addedMillis / 1000L),
        MediaStore.MediaColumns.DISPLAY_NAME to name,
        MediaStore.Images.ImageColumns.WIDTH to width,
        MediaStore.Images.ImageColumns.HEIGHT to height,
        MediaStore.Images.ImageColumns.TITLE to name,
        MediaStore.Images.ImageColumns.DATA to file.absolutePath,
        MediaStore.Images.ImageColumns.DATE_TAKEN to addedMillis,
        MediaStore.MediaColumns.BUCKET_ID to bucketId,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME to bucketName,
        MediaStore.Video.VideoColumns.DURATION to 0L,
        // The Favorites literal the app's own walk projects (Q+ spelling).
        "is_favorite" to if (favorite) 1 else 0,
        MediaStore.MediaColumns.IS_TRASHED to 0,
        MediaStore.MediaColumns.IS_PENDING to 0,
        MediaStore.MediaColumns.GENERATION_MODIFIED to 1L,
        MediaStore.Files.FileColumns.MEDIA_TYPE to 1,
    )

    fun column(name: String): Any? = columns[name] ?: 0
}

/**
 * The `media`-authority stand-in: answers EVERY `content://media/...` query
 * with the fixed rows (the selection is satisfied by construction --- every
 * row is an un-trashed, un-pending image) and serves each row's bytes from
 * its seeded file through [openFile], which is exactly the path the app's
 * Coil `ContentUri` fetcher drives for thumbnails.
 *
 * The Bundle-args query variant (`ContentResolver.query(uri, projection,
 * queryArgs, cancellationSignal)`) needs no override: the framework's
 * default implementation translates it into this 5-arg query.
 */
private class FakeMediaProvider(
    private val rows: List<SeedRow>,
) : ContentProvider() {

    private val byId: Map<Long, SeedRow> = rows.associateBy { it.id }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val columns = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns, rows.size)
        rows.forEach { row -> cursor.addRow(columns.map { row.column(it) }) }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.toLongOrNull()?.let { byId[it]?.mimeType }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode.contains('w')) return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val file = byId[id]?.file ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    private companion object {
        /** The revision-contract columns every walk since API 1 accepts. */
        private val DEFAULT_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )
    }
}
