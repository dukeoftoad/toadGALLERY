package io.github.dukeoftoad.gallery.edit

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// The editor's Material 3 theme --- r63b (the chrome round: violet retires).
// ---------------------------------------------------------------------------
//
// r63b history: the r53 re-pin put the photo editor into the r52 video
// violet (the "one app, one design language" move), and the approved r63
// proposal undoes the SPLIT the other way --- every violet role re-pins to
// the gallery's toad-green ToadDarkColors family (apps/gallery ...
// ui/theme/Theme.kt:80-110), per the r63 proposal's §3 hex table. The fixed
// contract STANDS: the scheme is still deterministic and NOT dynamic color
// (the r47/ADR-0009 promise --- no wallpaper-derived hue can leak in), the
// canvas matting stays pure #000000, and the r52 selection mechanism
// survives re-colored: secondaryContainer = primary, so a SELECTED
// FilterChip renders as the #9CD8A2 accent-filled pill with its label in
// #00390F (8.0:1) with zero per-chip overrides at the call sites.
//
// F-69 structure (this round, per the approved ledger plan): the hexes no
// longer live loose in a darkColorScheme call --- they live in a named
// [EditorPalette] registry below, with the toad-green set as the FIXED
// DEFAULT. The future Settings accent tri-state (toad-green / wallpaper /
// mono) plugs in here; NO UI may switch palettes yet --- structure only.
// The palette-invariant roles (the error family, scrim, the canvas matting)
// stay outside the registry: no accent choice may move them.

/**
 * The editor's palette registry (F-69): every accent-and-neutral role the
 * editor's dark scheme needs, as named data. The r63b toad-green set is the
 * fixed default ([EditorPalette.Toad]); the planned Settings tri-state
 * (toad-green / wallpaper / mono) would add further instances here and pass
 * them through [EditorTheme] --- until that round ships, every call site
 * MUST use the default and nothing may switch palettes at runtime.
 *
 * Roles the registry deliberately does NOT carry (they are accent- and
 * wallpaper-invariant by contract): the error family, the scrim, and the
 * canvas matting black --- those stay pinned in this file.
 */
internal data class EditorPalette(
    val name: String,
    // ---- The accent family ----
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    // ---- The warm near-black neutrals ----
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
) {
    companion object {
        /**
         * The r63b default (and until F-69 ships, the ONLY palette): the
         * gallery's ToadDarkColors family, hex-for-hex (Theme.kt:80-110;
         * the §3 table's source-of-truth column). The accent does all the
         * expressive work: #9CD8A2 on #00390F reads 8.0:1.
         */
        val Toad = EditorPalette(
            name = "toad",
            primary = Color(0xFF9CD8A2),
            onPrimary = Color(0xFF00390F),
            primaryContainer = Color(0xFF14522A),
            onPrimaryContainer = Color(0xFFEAF6EC),
            inversePrimary = Color(0xFF2E7A3C),
            secondary = Color(0xFFB9CCB3),
            onSecondary = Color(0xFF253424),
            onSecondaryContainer = Color(0xFFD4E8D0),
            tertiary = Color(0xFFB3CCB4),
            onTertiary = Color(0xFF1F3524),
            tertiaryContainer = Color(0xFF35543A),
            onTertiaryContainer = Color(0xFFCCEBCB),
            background = Color(0xFF151512),
            onBackground = Color(0xFFE5E2D8),
            surfaceVariant = Color(0xFF3A3830),
            onSurfaceVariant = Color(0xFFC6C2B6),
            outline = Color(0xFF918F85),
            outlineVariant = Color(0xFF46453F),
        )
    }
}

// Palette-invariant roles (see [EditorPalette]): the error family is
// already toad's (the §3 table: "identical"), the scrim is pure black, and
// inverseOnSurface is the gallery's fixed inversion tone. The canvas
// matting black is not a scheme role at all --- EditActivity paints it.
private val EditorError = Color(0xFFF2B8B5)
private val EditorOnError = Color(0xFF601C10)
private val EditorErrorContainer = Color(0xFF8C1D18)
private val EditorOnErrorContainer = Color(0xFFF9DEDC)
private val EditorScrim = Color(0xFF000000)
private val EditorInverseOnSurface = Color(0xFF1D1C17)

/**
 * The fixed dark [ColorScheme] a palette produces. The r52 selection
 * mechanism lives HERE, once, for every palette: `secondaryContainer` =
 * [EditorPalette.primary] and `onSecondaryContainer` = [EditorPalette.onPrimary],
 * so a SELECTED FilterChip is always the accent-filled pill with the
 * on-accent label --- re-colored with the registry, re-broken by no one.
 */
internal fun EditorPalette.toDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    // The r52 mechanism (re-colored r63b): a SELECTED FilterChip fills with
    // the accent and reads its label in OnAccent --- the design's
    // selected-chip pill, with zero per-chip overrides at the call sites.
    secondaryContainer = primary,
    onSecondaryContainer = onPrimary,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = background,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = onBackground,
    inverseOnSurface = EditorInverseOnSurface,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = EditorScrim,
    error = EditorError,
    onError = EditorOnError,
    errorContainer = EditorErrorContainer,
    onErrorContainer = EditorOnErrorContainer,
)

/**
 * The editor theme every `setContent` in this app wraps its content in:
 * the r63b toad-green palette over the deliberately fixed-dark chrome (the
 * photo canvas is always black; the scheme carries the accents, sliders,
 * chips and controls). Same fixed-deterministic contract since r47; the
 * family is now the gallery's toad-green per the approved §3 table.
 *
 * The [palette] parameter is the F-69 plug-in point: structure only this
 * round --- every call site (and every test) uses the toad default, and no
 * UI may switch palettes until the Settings accent tri-state round ships.
 */
@Composable
internal fun EditorTheme(
    palette: EditorPalette = EditorPalette.Toad,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = palette.toDarkColorScheme(),
        content = content,
    )
}
