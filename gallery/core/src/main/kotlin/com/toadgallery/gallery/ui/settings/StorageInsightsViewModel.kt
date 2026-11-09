package com.toadgallery.gallery.ui.settings

import android.app.Application
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * r62 (the Settings craft round): one measured location of the app's
 * on-device footprint, for the System tab's Storage dashboard.
 *
 * Entries are stable string keys (the composable maps each to its label
 * resource), because the VM runs on the JVM-friendly side of the screen:
 * [directorySize] is a pure recursive walk, JVM-temp-folder testable
 * (ADR-0016: every rule that can be tested without the Android runtime IS
 * tested there).
 *
 * The five honest locations (all app-private; the vault and the ML index
 * are ciphertext/blob stores whose sizes are exactly what they look like):
 *  - the scan catalogue's Room database trio (`media_catalogue.db` +
 *    `-wal` + `-shm`) under `filesDir/databases`;
 *  - the shared ML index store (`filesDir/face-index`, the
 *    [com.toadgallery.gallery.ml.index.FaceIndexWorker]'s
 *    `ml-index.sqlite` + its state file --- the face AND semantic queues
 *    write through the same store);
 *  - the ML model cache (`filesDir/models`, the ModelProvisioner's
 *    staged packs);
 *  - the hidden vault (`filesDir/vault`);
 *  - the app cache directory (`context.cacheDir`, thumbnails + temp) ---
 *    the one entry with a Clear action (cache is disposable by
 *    definition; nothing else on this page is deleted from here).
 *
 * The trash row is a MediaStore COUNT of trashed rows (API 30+; below
 * that, and on any resolver failure, it reports -1 and the row hides ---
 * never an invented zero).
 */
/** Stable entry keys; the dashboard composable maps each to its label. */
const val STORAGE_KEY_CATALOGUE = "catalogue_db"
const val STORAGE_KEY_ML_INDEX = "ml_index_store"
const val STORAGE_KEY_MODELS = "ml_models"
const val STORAGE_KEY_VAULT = "vault"
const val STORAGE_KEY_CACHE = "app_cache"

/** One measured location: [key] maps to the row's label resource. */
data class StorageEntry(
    val key: String,
    val bytes: Long,
)

/** The dashboard's state: measured entries, the trash count, the banner. */
data class StorageInsightsState(
    val entries: List<StorageEntry> = emptyList(),
    /** MediaStore trashed-row count; -1 = not measurable (row hides). */
    val trashCount: Int = -1,
    /** True while a measurement pass runs (the rows show placeholders). */
    val measuring: Boolean = false,
)

/**
 * Recursively measures [dir]; 0 for a null, missing or non-directory path.
 * Pure JVM (a plain walk), so the unit test pins it against a temp folder.
 */
fun directorySize(dir: File?): Long {
    if (dir == null || !dir.exists() || !dir.isDirectory) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Pure: the Room database trio's total ([dbName] + `-wal` + `-shm`), the
 * honest on-disk footprint of the catalogue (WAL is real disk).
 */
fun databaseFilesSize(databasesDir: File?, dbName: String): Long {
    if (databasesDir == null) return 0L
    return listOf(dbName, "$dbName-wal", "$dbName-shm").sumOf {
        val f = File(databasesDir, it)
        if (f.exists() && f.isFile) f.length() else 0L
    }
}

/**
 * State holder for the System tab's Storage dashboard (r62). Measures on
 * init and on every explicit [refresh] (pull-to-refresh is not wired ---
 * the dashboard is a glanceable page, not a live monitor), on
 * Dispatchers.IO; the compose side only renders.
 */
class StorageInsightsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(StorageInsightsState(measuring = true))
    val state: StateFlow<StorageInsightsState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-measures every entry (one IO pass; sizes can change any time). */
    fun refresh() {
        _state.value = _state.value.copy(measuring = true)
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val filesDir = app.filesDir
            val entries = listOf(
                StorageEntry(
                    STORAGE_KEY_CATALOGUE,
                    databaseFilesSize(File(filesDir, "databases"), "media_catalogue.db"),
                ),
                StorageEntry(
                    STORAGE_KEY_ML_INDEX,
                    directorySize(File(filesDir, "face-index")),
                ),
                StorageEntry(
                    STORAGE_KEY_MODELS,
                    directorySize(File(filesDir, "models")),
                ),
                StorageEntry(
                    STORAGE_KEY_VAULT,
                    directorySize(File(filesDir, "vault")),
                ),
                StorageEntry(
                    STORAGE_KEY_CACHE,
                    directorySize(app.cacheDir),
                ),
            )
            _state.value = StorageInsightsState(
                entries = entries,
                trashCount = measureTrashCount(),
                measuring = false,
            )
        }
    }

    /**
     * The one action the dashboard offers: clear the app cache directory
     * (disposable by definition --- thumbnails rebuild, temp files are
     * temp). Nothing else on the page deletes from here; the ML models'
     * delete lives on the AI tab (the provisioning cards), the vault's
     * wipe on the vault screen, the catalogue has no user-facing reset.
     */
    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            refresh()
        }
    }

    /**
     * The MediaStore trashed-row count, or -1 when it cannot be known
     * (below API 30, or the resolver refuses --- a permission revocation
     * mid-session, a vendor quirk). Never an invented zero: the row hides
     * on -1.
     */
    private fun measureTrashCount(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1
        return try {
            getApplication<Application>().contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf("count(*)"),
                "${MediaStore.MediaColumns.IS_TRASHED} = 1",
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            } ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
