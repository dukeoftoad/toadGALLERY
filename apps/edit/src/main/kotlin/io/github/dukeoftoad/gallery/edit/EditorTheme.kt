package io.github.dukeoftoad.gallery.edit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// The editor's Material 3 theme --- r53 (the design-language round).
// ---------------------------------------------------------------------------
//
// r53 history: the maintainer's 2026-11-07 chat instruction ("Same ux design
// also") arrived right after the r52 video-surface redesign (PR #144) shipped
// the Image-2 dark+violet chassis there. The photo editor surface was still
// wearing this file's r47 toad-green scheme --- one app, two design languages,
// split at the activity boundary. This round retires the family-green scheme
// and re-pins the SAME palette the video surface shipped (the ten r52 tokens,
// duplicated here ON PURPOSE: the edit app stays a zero-dependency sibling of
// the gallery per the r46 APK-matrix separation, so the palette is data and
// this file records where it comes from --- the r52 `VideoEditTheme`, which
// pins the identical hexes locally to its own file).
//
// The r47 history stays true: the baseline-purple leak this file fixed (the
// bare `MaterialTheme {}` reading as a different app family) cannot come back
// --- the scheme is still fixed, deterministic, and NOT dynamic-color (the
// gallery's ADR-0009 behavior; a wallpaper-derived scheme could re-introduce
// any hue on a device with dynamic color set). What changed is the pinned
// family: the accent promise is now the violet the maintainer approved for
// the video surface, carried app-wide (the photo editor, its dialogs, sheets
// and consent prompts all wrap in this theme).
//
// The r52 mechanism rides along: `secondaryContainer` = the accent, so a
// SELECTED FilterChip renders as the accent-filled pill the design shows with
// zero per-chip color overrides (the `EditActivity` chips, the export-policy
// sheet's JPEG/PNG choice, the region kinds, the retouch modes --- all of
// them inherit it). The photo canvas matting stays pure black (the r47
// promise, untouched by a palette swap).
//
// Derived neutrals (primaryContainer/tertiaryContainer/outlineVariant and
// friends) are interpolations INSIDE the pinned violet family, not
// design-approved tokens --- follow-up F-52 records that the maintainer may
// pin exact values later and this file is the single edit point.
private val EditorAccent = Color(0xFFA78BFA)

private val EditorAccentSoft = Color(0xFF8B72E8)

private val EditorOnAccent = Color(0xFF171126)

private val EditorBg = Color(0xFF0D0A14)

private val EditorTextPrimary = Color(0xFFF3F0FA)

/** The editor's fixed dark scheme: violet accent, the r52 near-black violet neutrals. */
private val EditorDarkColors = darkColorScheme(
    primary = EditorAccent,
    onPrimary = EditorOnAccent,
    primaryContainer = Color(0xFF2E2347),
    onPrimaryContainer = Color(0xFFEDE7FA),
    inversePrimary = Color(0xFF7C5CE0),
    secondary = EditorAccentSoft,
    onSecondary = EditorOnAccent,
    // The r52 mechanism: a SELECTED FilterChip fills with the accent and
    // reads its label in OnAccent --- the design's selected-chip pill,
    // with zero per-chip overrides at the call sites.
    secondaryContainer = EditorAccent,
    onSecondaryContainer = EditorOnAccent,
    tertiary = EditorAccentSoft,
    onTertiary = EditorOnAccent,
    tertiaryContainer = Color(0xFF3A2D55),
    onTertiaryContainer = Color(0xFFE6DFF7),
    background = EditorBg,
    onBackground = EditorTextPrimary,
    surface = EditorBg,
    onSurface = EditorTextPrimary,
    surfaceVariant = Color(0xFF221B2E),
    onSurfaceVariant = Color(0xFFA99FBF),
    surfaceTint = EditorAccent,
    inverseSurface = EditorTextPrimary,
    inverseOnSurface = EditorBg,
    outline = Color(0xFF3A3148),
    outlineVariant = Color(0xFF2E2740),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601C10),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * The editor theme every `setContent` in this app wraps its content in:
 * the r52 Image-2 dark+violet palette over the deliberately fixed-dark chrome
 * (the photo canvas is always black; the scheme carries the accents,
 * sliders, chips and controls). The r47 toad-green scheme this function
 * previously pinned retired in r53 --- same fixed-deterministic contract,
 * new family.
 */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EditorDarkColors,
        content = content,
    )
}
