package io.github.dukeoftoad.gallery.edit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// The editor's Material 3 theme --- r47 (the device-screenshot round).
// ---------------------------------------------------------------------------
//
// The field evidence (the maintainer's 2026-11-05 toadGALLERY Engine
// screenshots): both editor surfaces rendered with the Material 3 BASELINE
// PURPLE scheme, because their `setContent` wrapped the content in a bare
// `MaterialTheme {}` with no colorScheme --- the library default leaked
// through every accent role (sliders, chips, the Save-copy pill), reading
// as a different app family next to the toad-green gallery.
//
// The fix mirrors the gallery's fallback dark palette
// (`apps/gallery/.../ui/theme/Theme.kt`, the issue-#11 token system:
// docs/architecture/design-tokens.md §3 --- the WCAG-checked warm-gray
// neutrals + the single toad-green accent family, #3E9B4F seed at tone-80
// lightness for the dark roles). The values are duplicated ON PURPOSE:
// the edit app must stay a zero-dependency sibling of the gallery (the
// r46 APK-matrix separation), so a shared theme module would couple the
// two builds again --- the palette is data, and this file records where
// it comes from.
//
// Deliberately NOT dynamic-color (the gallery's ADR-0009 behavior): the
// editor is a fixed-dark surface over the photo canvas by design (the
// always-black Surface + white chrome), and its brand promise is that the
// toadGALLERY family accent is always the toad green here --- a
// wallpaper-derived scheme could re-introduce any hue (purple included)
// on a device with dynamic color set. Fixed, deterministic, family-green.
private val ToadGreenPrimaryDark = Color(0xFF9CD8A2)

/** The editor's fixed dark scheme: toad-green accent, warm near-black neutrals. */
private val EditorDarkColors = darkColorScheme(
    primary = ToadGreenPrimaryDark,
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF14522A),
    onPrimaryContainer = Color(0xFFEAF6EC),
    inversePrimary = Color(0xFF2E7A3C),
    secondary = Color(0xFFB9CCB3),
    onSecondary = Color(0xFF253424),
    secondaryContainer = Color(0xFF3B4B39),
    onSecondaryContainer = Color(0xFFD4E8D0),
    tertiary = Color(0xFFB3CCB4),
    onTertiary = Color(0xFF1F3524),
    tertiaryContainer = Color(0xFF35543A),
    onTertiaryContainer = Color(0xFFCCEBCB),
    background = Color(0xFF151512),
    onBackground = Color(0xFFE5E2D8),
    surface = Color(0xFF151512),
    onSurface = Color(0xFFE5E2D8),
    surfaceVariant = Color(0xFF3A3830),
    onSurfaceVariant = Color(0xFFC6C2B6),
    surfaceTint = ToadGreenPrimaryDark,
    inverseSurface = Color(0xFFE5E2D8),
    inverseOnSurface = Color(0xFF1D1C17),
    outline = Color(0xFF918F85),
    outlineVariant = Color(0xFF46453F),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601C10),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * The editor theme every `setContent` in this app wraps its content in:
 * the toadGALLERY family palette over the deliberately fixed-dark chrome
 * (the photo canvas is always black; the scheme carries the accents,
 * sliders, chips and controls). Replaces the bare `MaterialTheme {}`
 * whose baseline purple leaked through before r47.
 */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EditorDarkColors,
        content = content,
    )
}
