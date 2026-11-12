package com.toadgallery.gallery.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.toadgallery.gallery.data.media.location.AndroidMediaLocationAccess
import com.toadgallery.gallery.ui.theme.ToadThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The r25 People-albums consent key (Workstream 6): persisted in the
 * house `toad_settings` preferences, default FALSE --- ADR-0010's
 * opt-in rule (AI features are off until the user turns them on).
 * Lives at FILE scope (not in the private companion) because the
 * People/Albums ViewModels read the same key at refresh time; a single
 * public definition keeps it from drifting between readers.
 */
const val KEY_PEOPLE_ALBUM_ENABLED = "peopleAlbumEnabled"

/**
 * The r26 Magic Search consent key (Workstream 7): persisted in the
 * house `toad_settings` preferences, default FALSE --- ADR-0010's
 * opt-in rule (AI features are off until the user turns them on), the
 * exact `peopleAlbumEnabled` shape one round later. Lives at FILE
 * scope for the same reason: the Search tab's Content-mode ViewModel
 * reads the same key at query time; a single public definition keeps
 * it from drifting between readers.
 */
const val KEY_MAGIC_SEARCH_ENABLED = "magicSearchEnabled"

/**
 * The r39 Things consent key (the ente-port exit round): persisted in
 * the house `toad_settings` preferences, default FALSE --- ADR-0010's
 * opt-in rule, the exact `peopleAlbumEnabled`/`magicSearchEnabled`
 * shape. Lives at FILE scope for the same reason: the Albums tab's
 * ViewModel reads the same key at refresh time; a single public
 * definition keeps it from drifting between readers.
 */
const val KEY_THINGS_ALBUM_ENABLED = "thingsAlbumEnabled"

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
 *    render survives any corrupt or future-format file;
 *  - the "People albums" consent (r25, Workstream 6) under
 *    [KEY_PEOPLE_ALBUM_ENABLED], default FALSE --- ADR-0010's opt-in rule
 *    for AI features. The People/Albums surfaces read the preference
 *    directly at refresh time (they are separate ViewModels without a
 *    shared scope), so this flow's write-through matters for the Settings
 *    row's own render; cross-screen propagation rides each surface's
 *    entry refresh.
 *  - the "Magic search" consent (r26, Workstream 7) under
 *    [KEY_MAGIC_SEARCH_ENABLED], default FALSE --- the same opt-in rule
 *    and the same write-through shape, one round later. The Search
 *    tab's Content-mode ViewModel reads the preference at query time
 *    (its own refresh), so the same cross-screen story holds.
 *  - the "Things collections" consent (r39, the ente-port exit round)
 *    under [KEY_THINGS_ALBUM_ENABLED], default FALSE --- the same
 *    opt-in rule and write-through shape. The Albums tab's ViewModel
 *    reads the preference at its entry refresh.
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

    // r25 (Workstream 6): the People-albums consent, default FALSE per
    // ADR-0010 --- the same write-through shape as the picker toggle.
    private val _peopleAlbumsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_PEOPLE_ALBUM_ENABLED, false))

    /** Whether the People feature is consented on (r25; default false). */
    val peopleAlbumsEnabled: StateFlow<Boolean> = _peopleAlbumsEnabled.asStateFlow()

    // r26 (Workstream 7): the Magic Search consent, default FALSE per
    // ADR-0010 --- the same write-through shape as the picker toggle and
    // the People row.
    private val _magicSearchEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_MAGIC_SEARCH_ENABLED, false))

    /** Whether Magic Search is consented on (r26; default false). */
    val magicSearchEnabled: StateFlow<Boolean> = _magicSearchEnabled.asStateFlow()

    // r39 (the ente-port exit round): the Things consent, default FALSE
    // per ADR-0010 --- the same write-through shape as the sibling rows.
    private val _thingsAlbumsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_THINGS_ALBUM_ENABLED, false))

    /** Whether Things collections are consented on (r39; default false). */
    val thingsAlbumsEnabled: StateFlow<Boolean> = _thingsAlbumsEnabled.asStateFlow()

    private val _appearance = MutableStateFlow(
        Appearance(
            layout = parseLayout(prefs.getString(KEY_PHOTOS_LAYOUT, null)),
            columns = clampColumns(prefs.getInt(KEY_PHOTOS_COLUMNS, DEFAULT_COLUMNS)),
            groupBy = parseGroupBy(prefs.getString(KEY_PHOTOS_GROUP_BY, null)),
            // r64: the light/dark/system mode (the Theme section's
            // segmented control); the default SYSTEM = the pre-r64 render.
            themeMode = parseThemeMode(prefs.getString(KEY_THEME_MODE, null)),
            // r62: the Stories shelf toggle, default TRUE = the pre-r62
            // render (the shelf always showed); a missing key keeps it.
            storiesOnHome = prefs.getBoolean(KEY_STORIES_ON_HOME, true),
        )
    )
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    // r62: the Material You toggle (the Appearance extras slice). Default
    // TRUE = the pre-r62 behavior (ToadGalleryTheme's `dynamicColor`
    // parameter defaulted to true); turning it OFF forces the deterministic
    // toad-green fallback palette on every API level --- the app's own
    // brand look, independent of the wallpaper.
    private val _useDynamicColor =
        MutableStateFlow(prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, true))

    /** Whether Material You dynamic color is honoured (Android 12+; r62). */
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    /**
     * Phase 4 location consent (ACCESS_MEDIA_LOCATION): the shell-scoped
     * state holder the Settings "Location data" section renders and the
     * Details view's display gate observes. Seeds its state from the OS
     * at construction (one cheap `checkSelfPermission`); below API 29 it
     * sits in NOT_REQUIRED forever.
     */
    val mediaLocation: AndroidMediaLocationAccess = AndroidMediaLocationAccess(app)

    fun setUsePhotoPicker(enabled: Boolean) {
        _usePhotoPicker.value = enabled
        prefs.edit().putBoolean(KEY_USE_PHOTO_PICKER, enabled).apply()
    }

    /** Sets the People-albums consent (r25) and persists it write-through. */
    fun setPeopleAlbumsEnabled(enabled: Boolean) {
        if (enabled == _peopleAlbumsEnabled.value) return
        _peopleAlbumsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PEOPLE_ALBUM_ENABLED, enabled).apply()
    }

    /** Sets the Magic Search consent (r26) and persists it write-through. */
    fun setMagicSearchEnabled(enabled: Boolean) {
        if (enabled == _magicSearchEnabled.value) return
        _magicSearchEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MAGIC_SEARCH_ENABLED, enabled).apply()
    }

    /** Sets the Things-collections consent (r39) and persists it write-through. */
    fun setThingsAlbumsEnabled(enabled: Boolean) {
        if (enabled == _thingsAlbumsEnabled.value) return
        _thingsAlbumsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_THINGS_ALBUM_ENABLED, enabled).apply()
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

    /**
     * r64: sets the light/dark/system appearance mode and persists it
     * write-through. The SHELL resolves [Appearance.themeMode] into the
     * theme call's darkTheme argument (see ToadGalleryApp), so the whole
     * app re-themes in the same session --- no restart.
     */
    fun setThemeMode(themeMode: ThemeMode) {
        if (themeMode == _appearance.value.themeMode) return
        _appearance.value = _appearance.value.copy(themeMode = themeMode)
        prefs.edit().putString(KEY_THEME_MODE, serializeThemeMode(themeMode)).apply()
    }

    /**
     * r62: sets the Stories-on-home toggle and persists it write-through.
     * The Photos timeline reads [Appearance.storiesOnHome] from the same
     * shell-scoped flow, so the shelf appears/disappears on the next
     * composition without a restart.
     */
    fun setStoriesOnHome(enabled: Boolean) {
        if (enabled == _appearance.value.storiesOnHome) return
        _appearance.value = _appearance.value.copy(storiesOnHome = enabled)
        prefs.edit().putBoolean(KEY_STORIES_ON_HOME, enabled).apply()
    }

    /** r62: sets the Material You toggle; the shell theme re-composes live. */
    fun setUseDynamicColor(enabled: Boolean) {
        if (enabled == _useDynamicColor.value) return
        _useDynamicColor.value = enabled
        prefs.edit().putBoolean(KEY_USE_DYNAMIC_COLOR, enabled).apply()
    }

    // r76: the Theme & Colors palette selection --- one of the five
    // prototype palettes (Toad/Amber/Ocean/OLED/Light), persisted beside
    // the r64 theme mode with the same write-through shape. The shell
    // theme call reads the flow directly (ToadGalleryApp), so a palette
    // card tap re-themes every surface in the same session.
    private val _toadPaletteId = MutableStateFlow(
        parseToadThemeId(prefs.getString(KEY_TOAD_PALETTE_ID, null)),
    )

    /** The active Material 3 Expressive palette (r76; default [ToadThemeId.TOAD]). */
    val toadPaletteId: StateFlow<ToadThemeId> = _toadPaletteId.asStateFlow()

    /** r76: selects one of the five palettes; write-through, live re-theme. */
    fun setToadPaletteId(id: ToadThemeId) {
        if (id == _toadPaletteId.value) return
        _toadPaletteId.value = id
        prefs.edit().putString(KEY_TOAD_PALETTE_ID, id.name).apply()
    }

    private companion object {
        /** Module-private settings file (issue #27). */
        const val PREFS_FILE = "toad_settings"
        const val KEY_USE_PHOTO_PICKER = "use_photo_picker"
        const val KEY_PHOTOS_LAYOUT = "photos_layout"
        const val KEY_PHOTOS_COLUMNS = "photos_columns"
        const val KEY_PHOTOS_GROUP_BY = "photos_group_by"

        /** r62: the Stories shelf toggle key (default true). */
        const val KEY_STORIES_ON_HOME = "stories_on_home"

        /** r62: the Material You dynamic-color key (default true). */
        const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"

        /** r64: the light/dark/system mode key (default SYSTEM). */
        const val KEY_THEME_MODE = "theme_mode"

        /** r76: the Theme & Colors palette key (default TOAD). */
        const val KEY_TOAD_PALETTE_ID = "toad_palette_id"

        /** [Appearance.columns]' default; kept beside its keys for symmetry. */
        const val DEFAULT_COLUMNS = 3
    }
}

/**
 * The forgiving parse for the r76 palette preference: the enum NAME is the
 * wire format (the same posture as [parseThemeMode]/[parseLayout]); any
 * unknown or corrupt stored value degrades to the [ToadThemeId.TOAD]
 * default --- the pre-r76 render --- instead of crashing.
 */
fun parseToadThemeId(stored: String?): ToadThemeId =
    stored?.let { raw -> ToadThemeId.entries.firstOrNull { it.name == raw } } ?: ToadThemeId.TOAD
