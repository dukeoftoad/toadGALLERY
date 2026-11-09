package io.github.dukeoftoad.gallery.ui.settings

/**
 * The Ente-inspired appearance preferences (roadmap Phase 3: appearance
 * settings slice) --- grid-vs-collage layout, photos-per-row density and the
 * timeline's group-by grain --- as one pure-JVM model so the (de)serialization
 * contract is JVM-unit-testable (ADR-0016) and free of any `android.*` type.
 *
 * Design intent:
 *  - [LayoutStyle.COLLAGE] is the aspect-aware staggered grid the app has
 *    shipped since issues #34/#29 (`LazyVerticalStaggeredGrid` sizing each
 *    cell from [io.github.dukeoftoad.gallery.data.MediaItem] dimensions);
 *  - [LayoutStyle.GRID] is the Ente-style uniform square grid --- every cell
 *    forced to `1f` aspect (see `MediaThumb`'s `forceSquare`), the look of a
 *    classic dense gallery;
 *  - [GroupBy.NONE] is the flat timeline of today; [GroupBy.DAY] /
 *    [GroupBy.WEEK] (r27) / [GroupBy.MONTH] layer full-line date headers
 *    between the cells (the `TimelineRows` model + the paging grid's
 *    header slots).
 *
 * The defaults (COLLAGE / 3 columns / NONE) are the values the app rendered
 * before this slice existed, so a fresh install keeps its render
 * byte-identical: the settings only ever change what the user explicitly
 * changes. Persistence lives in [SettingsViewModel] (`"toad_settings"`
 * SharedPreferences, keys `"photos_layout"` / `"photos_columns"` /
 * `"photos_group_by"`) using [serializeLayout]/[parseLayout] and
 * [serializeGroupBy]/[parseGroupBy] --- the enum NAME is the wire format, so
 * hand-edited or future values degrade to the defaults instead of crashing
 * (the same forgiving-parse posture as `TimelineSort`'s resource mapping:
 * never trust stored strings).
 *
 * [Appearance.columns] is an invariant, not an enforced property: callers
 * keep it inside `[MIN_COLUMNS, MAX_COLUMNS]` by routing every write (and
 * the initial read) through [clampColumns]; keeping the data class a plain
 * data class preserves structural equality for `StateFlow` distinct-until-
 * changed semantics.
 */

/** How the media grids lay out their cells (Ente's two layout cards). */
enum class LayoutStyle {
    /** Aspect-aware staggered cells --- the render shipped with #34. */
    COLLAGE,

    /** Uniform square cells, Ente-style (`MediaThumb(forceSquare = true)`). */
    GRID,
}

/**
 * The timeline's grouping grain (Ente's "Group by" row).
 */
enum class GroupBy {
    /** Flat timeline --- the render shipped with #25/#28. */
    NONE,

    /** A full-line header before the first cell of each new day. */
    DAY,

    /**
     * A full-line header before the first cell of each new ISO-8601
     * week (r27, the Moments round): the header anchors the week's
     * Monday, carries the section's item count, and groups the
     * Sunday-night / Monday-morning boundary the DAY grain splits. The
     * grouping math lives in the pure `MomentGrouper` (data/moments),
     * projected onto timeline rows by `buildTimelineRows`.
     */
    WEEK,

    /** A full-line header before the first cell of each new month. */
    MONTH,
}

/**
 * r64 (the Settings re-craft round): the app's appearance-mode
 * preference --- light, dark, or follow the system. The default
 * [SYSTEM] is the pre-r64 render (the theme call's own
 * `isSystemInDarkTheme()`), so a fresh install and every existing
 * install keep today's behavior until the user picks otherwise. Like
 * the other persisted enums here, the NAME is the wire format and any
 * unknown stored value degrades to [SYSTEM] instead of crashing.
 */
enum class ThemeMode {
    /** Follow `isSystemInDarkTheme()` --- the pre-r64 behavior. */
    SYSTEM,

    /** Always the light color scheme, whatever the OS says. */
    LIGHT,

    /** Always the dark color scheme, whatever the OS says. */
    DARK,
}

/** The user's appearance preferences; see the file KDoc for the defaults. */
data class Appearance(
    val layout: LayoutStyle = LayoutStyle.COLLAGE,
    /** Column count in [MIN_COLUMNS, MAX_COLUMNS] (see [clampColumns]). */
    val columns: Int = 3,
    val groupBy: GroupBy = GroupBy.NONE,
    /**
     * r64: the light/dark/system appearance mode (see [ThemeMode]). The
     * default SYSTEM keeps the pre-r64 render --- the theme call resolved
     * `isSystemInDarkTheme()` on its own.
     */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * r62: whether the Photos timeline carries the Stories shelf at all.
     * The default TRUE is the pre-r62 render (the shelf always showed), so
     * a fresh install and every existing install keep today's behavior
     * until the user opts out --- the settings only ever change what the
     * user explicitly changes. When false the shelf is absent entirely
     * (not empty-styled); when true it still renders only on the plain
     * unfiltered timeline, per StoriesShelf's own when-it-renders rule.
     */
    val storiesOnHome: Boolean = true,
)

/** The smallest selectable photos-per-row count (2 keeps cells legible). */
const val MIN_COLUMNS = 2

/** The largest selectable photos-per-row count (6 keeps cells tappable). */
const val MAX_COLUMNS = 6

/** Clamps [value] into [MIN_COLUMNS, MAX_COLUMNS] (both inclusive). */
fun clampColumns(value: Int): Int = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)

/**
 * Parses a persisted layout name; `null` (missing key) and any unknown value
 * degrade to [LayoutStyle.COLLAGE] --- a corrupt or future-format preference
 * must never crash the timeline, only fall back to today's render.
 */
fun parseLayout(raw: String?): LayoutStyle =
    LayoutStyle.entries.firstOrNull { it.name == raw } ?: LayoutStyle.COLLAGE

/**
 * Parses a persisted group-by name; `null` and any unknown value degrade to
 * [GroupBy.NONE] (the flat timeline), the same forgiving-parse posture as
 * [parseLayout].
 */
fun parseGroupBy(raw: String?): GroupBy =
    GroupBy.entries.firstOrNull { it.name == raw } ?: GroupBy.NONE

/**
 * Parses a persisted theme-mode name (r64); `null` and any unknown value
 * degrade to [ThemeMode.SYSTEM] --- a corrupt or future-format preference
 * falls back to today's render, the same forgiving-parse posture as
 * [parseLayout]/[parseGroupBy].
 */
fun parseThemeMode(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

/** The persisted form of [layout] (the enum NAME --- see the file KDoc). */
fun serializeLayout(layout: LayoutStyle): String = layout.name

/** The persisted form of [groupBy] (the enum NAME --- see the file KDoc). */
fun serializeGroupBy(groupBy: GroupBy): String = groupBy.name

/** The persisted form of [themeMode] (the enum NAME, r64). */
fun serializeThemeMode(themeMode: ThemeMode): String = themeMode.name
