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
 *    [GroupBy.MONTH] layer full-line date headers between the cells (the
 *    `TimelineRows` model + the paging grid's header slots).
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

/** The timeline's grouping grain (Ente's "Group by" row). */
enum class GroupBy {
    /** Flat timeline --- the render shipped with #25/#28. */
    NONE,

    /** A full-line header before the first cell of each new day. */
    DAY,

    /** A full-line header before the first cell of each new month. */
    MONTH,
}

/** The user's appearance preferences; see the file KDoc for the defaults. */
data class Appearance(
    val layout: LayoutStyle = LayoutStyle.COLLAGE,
    /** Column count in [MIN_COLUMNS, MAX_COLUMNS] (see [clampColumns]). */
    val columns: Int = 3,
    val groupBy: GroupBy = GroupBy.NONE,
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

/** The persisted form of [layout] (the enum NAME --- see the file KDoc). */
fun serializeLayout(layout: LayoutStyle): String = layout.name

/** The persisted form of [groupBy] (the enum NAME --- see the file KDoc). */
fun serializeGroupBy(groupBy: GroupBy): String = groupBy.name
