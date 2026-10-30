package io.github.dukeoftoad.gallery.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the Settings tab (issue #27).
 *
 * v1 owns exactly one preference --- the "Use system Photo Picker" toggle
 * that gates the issue #27 alternative access path. It ships default OFF
 * per the issue's review note ("default off in v1; review for default on"),
 * and persists in `SharedPreferences("toad_settings")` under the key
 * `"use_photo_picker"`. Plain prefs are the right-sized store here: one
 * boolean, read synchronously once at ViewModel creation (tiny file, no
 * first-frame flicker), written through `apply()` (async, no disk stall).
 *
 * MVVM + StateFlow per house style: the screen collects [usePhotoPicker]
 * and mutates it ONLY through [setUsePhotoPicker], so the in-memory value
 * and the persisted copy can never drift apart.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _usePhotoPicker = MutableStateFlow(prefs.getBoolean(KEY_USE_PHOTO_PICKER, false))
    val usePhotoPicker: StateFlow<Boolean> = _usePhotoPicker.asStateFlow()

    fun setUsePhotoPicker(enabled: Boolean) {
        _usePhotoPicker.value = enabled
        prefs.edit().putBoolean(KEY_USE_PHOTO_PICKER, enabled).apply()
    }

    private companion object {
        /** Module-private settings file (issue #27). */
        const val PREFS_FILE = "toad_settings"
        const val KEY_USE_PHOTO_PICKER = "use_photo_picker"
    }
}
