package io.github.dukeoftoad.gallery.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the Settings tab (issue #27; appearance preferences since
 * the roadmap Phase 3 appearance slice).
 *
 * Owns the app's SharedPreferences-backed preferences, all write-through
 * StateFlows so the in-memory value and the persisted copy can never drift
 * apart:
 *  - the "Use system Photo Picker" toggle (issue #27) that gates the
 *    alternative access path. It ships default OFF per the issue's review
 *    note ("default off in v1; review for default on") and persists in
 *    `SharedPreferences("toad_settings")` under `"use_photo_picker"`;
 *  - the [Appearance] bundle (layout / photos per row / group by) under
 *    `"photos_layout"` / `"photos_columns"` (Int) / `"photos_group_by"`,
 *    (de)serialized with the pure helpers in `Appearance.kt` --- unknown
 *    stored values degrade to the COLLAGE/3/NONE defaults, so the pre-slice
 *    render survives any corrupt or future-format file.
 *
 * Plain prefs remain the right-sized store: a handful of scalars, read
 * synchronously once at ViewModel creation (tiny file, no first-frame
 * flicker), written through `apply()` (async, no disk stall).
 *
 * The ViewModel is SHELL-scoped (the shell's `viewModel()` hoists one
 * instance for Photos, album detail and Settings), so an appearance change
 * propagates to every grid in the same session --- see `ToadGalleryApp`.
 *
 * MVVM + StateFlow per house style: screens collect [usePhotoPicker] /
 * [appearance] and mutate them ONLY through the setters below.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _usePhotoPicker = MutableStateFlow(prefs.getBoolean(KEY_USE_PHOTO_PICKER, false))
    val usePhotoPicker: StateFlow<Boolean> = _usePhotoPicker.asStateFlow()

    private val _appearance = MutableStateFlow(
        Appearance(
            layout = parseLayout(prefs.getString(KEY_PHOTOS_LAYOUT, null)),
            columns = clampColumns(prefs.getInt(KEY_PHOTOS_COLUMNS, DEFAULT_COLUMNS)),
            groupBy = parseGroupBy(prefs.getString(KEY_PHOTOS_GROUP_BY, null)),
        )
    )
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setUsePhotoPicker(enabled: Boolean) {
        _usePhotoPicker.value = enabled
        prefs.edit().putBoolean(KEY_USE_PHOTO_PICKER, enabled).apply()
    }

    fun setLayout(layout: LayoutStyle) {
        if (layout == _appearance.value.layout) return
        _appearance.value = _appearance.value.copy(layout = layout)
        prefs.edit().putString(KEY_PHOTOS_LAYOUT, serializeLayout(layout)).apply()
    }

    /** Clamps into [MIN_COLUMNS, MAX_COLUMNS] before persisting. */
    fun setColumns(columns: Int) {
        val clamped = clampColumns(columns)
        if (clamped == _appearance.value.columns) return
        _appearance.value = _appearance.value.copy(columns = clamped)
        prefs.edit().putInt(KEY_PHOTOS_COLUMNS, clamped).apply()
    }

    fun setGroupBy(groupBy: GroupBy) {
        if (groupBy == _appearance.value.groupBy) return
        _appearance.value = _appearance.value.copy(groupBy = groupBy)
        prefs.edit().putString(KEY_PHOTOS_GROUP_BY, serializeGroupBy(groupBy)).apply()
    }

    private companion object {
        /** Module-private settings file (issue #27). */
        const val PREFS_FILE = "toad_settings"
        const val KEY_USE_PHOTO_PICKER = "use_photo_picker"
        const val KEY_PHOTOS_LAYOUT = "photos_layout"
        const val KEY_PHOTOS_COLUMNS = "photos_columns"
        const val KEY_PHOTOS_GROUP_BY = "photos_group_by"

        /** [Appearance.columns]' default; kept beside its keys for symmetry. */
        const val DEFAULT_COLUMNS = 3
    }
}
