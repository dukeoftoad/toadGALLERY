package com.toadgallery.gallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The r76 Material 3 Expressive redesign's PALETTE ID --- one entry per
 * selectable theme in the prototype's `[data-theme]` CSS blocks
 * (`upload/toadgallery_redesign_prototype.html`), exactly as the Theme &
 * Colors sub-page lists them.
 *
 * The selection persists in the house `toad_settings` preferences under
 * [KEY_TOAD_THEME_ID] (see `SettingsViewModel`); the default [TOAD] keeps
 * every existing install on the signature botanical dark.
 */
enum class ToadThemeId {
    /** Toad-Green Dark --- the signature default. */
    TOAD,

    /** Dynamic Material You Warm Amber Dark. */
    AMBER,

    /** Botanical Light (the one light palette). */
    LIGHT,

    /** Ocean Slate Dark. */
    OCEAN,

    /** OLED Pure Black. */
    OLED,
}

/**
 * One full Material 3 Expressive palette --- the prototype's CSS custom
 * properties (`--bg-app`, `--primary`, ...) transposed to Compose [Color]s
 * 1:1, alpha channels included (the glass tints carry the exact backdrop
 * opacity; the borders the exact 10-38% alpha).
 *
 * [Immutable] because every field is a val and the instance is shared via
 * [LocalToadPalette] --- the Compose compiler may skip recompositions that
 * only re-read the same palette.
 */
@Immutable
data class ToadThemePalette(
    val id: ToadThemeId,
    /** `--bg-app`: the screen-level canvas. */
    val bgApp: Color,
    /** `--bg-surface`: cards and sheets. */
    val bgSurface: Color,
    /** `--bg-surface-high`: chips, icon wells, inset rows. */
    val bgSurfaceHigh: Color,
    /** `--bg-surface-highest`: switch tracks, progress wells. */
    val bgSurfaceHighest: Color,
    /** `--bg-glass`: the frosted scrim tint (with alpha). */
    val bgGlass: Color,
    /** `--border-subtle`: 10-15% hairlines. */
    val borderSubtle: Color,
    /** `--border-strong`: 25-38% emphasis strokes. */
    val borderStrong: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val textMain: Color,
    val textMuted: Color,
    val textDim: Color,
    /** `--danger-bg` / `--danger-text`: destructive actions. */
    val dangerBg: Color,
    val dangerText: Color,
    /** True when this palette renders on a light canvas ([LIGHT]). */
    val isLight: Boolean,
)

/** The Toad-Green Dark default --- the prototype's `:root` block verbatim. */
val ToadPaletteToad = ToadThemePalette(
    id = ToadThemeId.TOAD,
    bgApp = Color(0xFF07130E),
    bgSurface = Color(0xFF0E1F18),
    bgSurfaceHigh = Color(0xFF152B22),
    bgSurfaceHighest = Color(0xFF1D392E),
    bgGlass = Color(0xD107130E),
    borderSubtle = Color(0x248FD9B6),
    borderStrong = Color(0x598FD9B6),
    primary = Color(0xFF6EE7B7),
    onPrimary = Color(0xFF032619),
    primaryContainer = Color(0xFF134E3A),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondaryContainer = Color(0xFF18362A),
    textMain = Color(0xFFECFDF5),
    textMuted = Color(0xFF94B8A8),
    textDim = Color(0xFF628575),
    dangerBg = Color(0x24EF4444),
    dangerText = Color(0xFFFCA5A5),
    isLight = false,
)

/** Dynamic Material You Warm Amber Dark --- `[data-theme="amber"]`. */
val ToadPaletteAmber = ToadThemePalette(
    id = ToadThemeId.AMBER,
    bgApp = Color(0xFF14100B),
    bgSurface = Color(0xFF201A13),
    bgSurfaceHigh = Color(0xFF2B231A),
    bgSurfaceHighest = Color(0xFF382E22),
    bgGlass = Color(0xD614100B),
    borderSubtle = Color(0x26FBBF24),
    borderStrong = Color(0x61FBBF24),
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF291800),
    primaryContainer = Color(0xFF5C3C08),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondaryContainer = Color(0xFF362A1A),
    textMain = Color(0xFFFFFBEB),
    textMuted = Color(0xFFC7B699),
    textDim = Color(0xFF8C7B60),
    dangerBg = Color(0x26EF4444),
    dangerText = Color(0xFFFCA5A5),
    isLight = false,
)

/** Botanical Light --- `[data-theme="light"]`, the one light palette. */
val ToadPaletteLight = ToadThemePalette(
    id = ToadThemeId.LIGHT,
    bgApp = Color(0xFFF4F9F6),
    bgSurface = Color(0xFFFFFFFF),
    bgSurfaceHigh = Color(0xFFE8F3ED),
    bgSurfaceHighest = Color(0xFFD7EAE0),
    bgGlass = Color(0xD9F4F9F6),
    borderSubtle = Color(0x1A10412D),
    borderStrong = Color(0x4010412D),
    primary = Color(0xFF0F7654),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6F6E1),
    onPrimaryContainer = Color(0xFF063726),
    secondaryContainer = Color(0xFFE1EFE8),
    textMain = Color(0xFF091E16),
    textMuted = Color(0xFF436355),
    textDim = Color(0xFF739183),
    dangerBg = Color(0x1ADC2626),
    dangerText = Color(0xFFB91C1C),
    isLight = true,
)

/** Ocean Slate Dark --- `[data-theme="ocean"]`. */
val ToadPaletteOcean = ToadThemePalette(
    id = ToadThemeId.OCEAN,
    bgApp = Color(0xFF08111A),
    bgSurface = Color(0xFF0F1D2B),
    bgSurfaceHigh = Color(0xFF172A3D),
    bgSurfaceHighest = Color(0xFF203952),
    bgGlass = Color(0xD608111A),
    borderSubtle = Color(0x2438BDF8),
    borderStrong = Color(0x5C38BDF8),
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF042233),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondaryContainer = Color(0xFF153147),
    textMain = Color(0xFFF0F9FF),
    textMuted = Color(0xFF93B4CC),
    textDim = Color(0xFF5D7E96),
    dangerBg = Color(0x26EF4444),
    dangerText = Color(0xFFFCA5A5),
    isLight = false,
)

/** OLED Pure Black --- `[data-theme="oled"]`. */
val ToadPaletteOled = ToadThemePalette(
    id = ToadThemeId.OLED,
    bgApp = Color(0xFF000000),
    bgSurface = Color(0xFF0C0F0E),
    bgSurfaceHigh = Color(0xFF161B19),
    bgSurfaceHighest = Color(0xFF202724),
    bgGlass = Color(0xE0000000),
    borderSubtle = Color(0x1CFFFFFF),
    borderStrong = Color(0x596EE7B7),
    primary = Color(0xFF6EE7B7),
    onPrimary = Color(0xFF022417),
    primaryContainer = Color(0xFF0F3D2E),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondaryContainer = Color(0xFF161F1C),
    textMain = Color(0xFFFFFFFF),
    textMuted = Color(0xFF9CA3AF),
    textDim = Color(0xFF6B7280),
    dangerBg = Color(0x29EF4444),
    dangerText = Color(0xFFFCA5A5),
    isLight = false,
)

/** All five palettes in the Theme & Colors sub-page's listing order. */
val AllToadPalettes: List<ToadThemePalette> = listOf(
    ToadPaletteToad,
    ToadPaletteAmber,
    ToadPaletteOcean,
    ToadPaletteOled,
    ToadPaletteLight,
)

/** The palette lookup by id (the stored preference's wire value). */
fun toadPaletteFor(id: ToadThemeId): ToadThemePalette = when (id) {
    ToadThemeId.TOAD -> ToadPaletteToad
    ToadThemeId.AMBER -> ToadPaletteAmber
    ToadThemeId.LIGHT -> ToadPaletteLight
    ToadThemeId.OCEAN -> ToadPaletteOcean
    ToadThemeId.OLED -> ToadPaletteOled
}

/**
 * The palette the redesign's composables read --- provided by
 * [ToadGalleryTheme] at the root, consumed by every Toad* component. A
 * static local (never changes within a composition) so reads are free.
 */
val LocalToadPalette = staticCompositionLocalOf<ToadThemePalette> {
    error("ToadPalette not provided --- wrap the tree in ToadGalleryTheme")
}

/** Convenience accessor: the active palette. */
object ToadTheme {
    /** The active palette (read inside any ToadGalleryTheme subtree). */
    val palette: ToadThemePalette
        @Composable get() = LocalToadPalette.current
}

/**
 * Maps one palette onto the Material 3 color-scheme roles the library
 * components (NavigationBar, Slider, progress indicators, ripple) render
 * with --- the SAME values the old ADR-0009 schemes derived from, so the
 * M3 chrome matches the Toad chrome instead of flashing stock purple.
 *
 * Deliberately coarse: the redesign's own components read
 * [LocalToadPalette] directly for every custom token; the M3 scheme is
 * only the fallback vocabulary for library widgets.
 */
private fun toadColorScheme(p: ToadThemePalette) = if (p.isLight) {
    lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        secondaryContainer = p.secondaryContainer,
        onSecondaryContainer = p.textMain,
        background = p.bgApp,
        onBackground = p.textMain,
        surface = p.bgSurface,
        onSurface = p.textMain,
        surfaceVariant = p.bgSurfaceHigh,
        onSurfaceVariant = p.textMuted,
        surfaceTint = p.primary,
        outline = p.borderStrong,
        outlineVariant = p.borderSubtle,
        scrim = Color(0xFF000000),
        error = p.dangerText,
        onError = p.dangerBg,
    )
} else {
    darkColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        secondaryContainer = p.secondaryContainer,
        onSecondaryContainer = p.textMain,
        background = p.bgApp,
        onBackground = p.textMain,
        surface = p.bgSurface,
        onSurface = p.textMain,
        surfaceVariant = p.bgSurfaceHigh,
        onSurfaceVariant = p.textMuted,
        surfaceTint = p.primary,
        outline = p.borderStrong,
        outlineVariant = p.borderSubtle,
        scrim = Color(0xFF000000),
        error = p.dangerText,
        onError = p.dangerBg,
    )
}

/**
 * toadGALLERY Material 3 Expressive theme (the r76 redesign).
 *
 * Wraps the legacy [ToadGalleryTheme] name --- every existing call site
 * keeps compiling --- while providing BOTH vocabularies:
 *  - [LocalToadPalette] / [ToadTheme.palette]: the redesign's exact
 *    prototype tokens (bgGlass, borderStrong, the three text tiers...);
 *  - the Material 3 `colorScheme` mapped from the same palette, for
 *    library components.
 *
 * [dynamicColor] ("Use system wallpaper colors", Android 12+) swaps the
 * M3 scheme for `dynamicDark/LightColorScheme` --- the palette-driven
 * custom tokens STAY (the prototype's five selectable palettes remain
 * selectable), only the library-widget vocabulary follows the wallpaper.
 * [paletteId] is the persisted Theme & Colors selection.
 */
@Composable
fun ToadGalleryTheme(
    paletteId: ToadThemeId = ToadThemeId.TOAD,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = toadPaletteFor(paletteId)
    val m3Scheme = if (dynamicColor) {
        val context = LocalContext.current
        // The LIGHT palette is the only light canvas; every dark palette
        // maps to the dark dynamic scheme whatever the OS says.
        if (palette.isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else {
        toadColorScheme(palette)
    }
    CompositionLocalProvider(
        LocalToadPalette provides palette,
        LocalMinimumInteractiveComponentSize provides ToadTouchTarget.minimum,
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            shapes = ToadShapes.material,
            typography = ToadTypography,
            content = content,
        )
    }
}
