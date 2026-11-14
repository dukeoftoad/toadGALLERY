package com.toadgallery.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The editor's Material 3 theme: the Lilypad palette registry as the default, with a
// fixed-deterministic dark scheme (no dynamic color ever) and a single accent mechanism.

/**
 * The editor's palette registry: every accent-and-neutral role of the dark scheme as named
 * data. Call sites MUST use the default; no runtime palette switching until a Settings
 * round ships. Error family, scrim and canvas matting are accent-invariant, pinned below.
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
         * The default palette: the Lilypad ladder — cool neutrals plus a single ember accent
         * reserved for selection/live value/CTA. Every hex comes from LilypadTokens, so the
         * palette cannot drift from the documented language (WCAG contrast pinned in tests).
         */
        val Lilypad = EditorPalette(
            name = "lilypad",
            primary = Color(LilypadTokens.EMBER.toInt()),
            onPrimary = Color(LilypadTokens.ON_EMBER.toInt()),
            primaryContainer = Color(LilypadTokens.Roles.EMBER_DEEP.toInt()),
            onPrimaryContainer = Color(LilypadTokens.Roles.ON_EMBER_CONTAINER.toInt()),
            inversePrimary = Color(LilypadTokens.Roles.EMBER_DIM.toInt()),
            secondary = Color(LilypadTokens.Roles.STEEL.toInt()),
            onSecondary = Color(LilypadTokens.Roles.ON_STEEL.toInt()),
            onSecondaryContainer = Color(LilypadTokens.Roles.ON_EMBER_CONTAINER.toInt()),
            tertiary = Color(LilypadTokens.REED.toInt()),
            onTertiary = Color(LilypadTokens.PAD.toInt()),
            tertiaryContainer = Color(LilypadTokens.HAIRLINE.toInt()),
            onTertiaryContainer = Color(LilypadTokens.Roles.PAPER_SOFT.toInt()),
            background = Color(LilypadTokens.PAD.toInt()),
            onBackground = Color(LilypadTokens.PAPER.toInt()),
            surfaceVariant = Color(LilypadTokens.PAD_RAISED.toInt()),
            onSurfaceVariant = Color(LilypadTokens.REED.toInt()),
            outline = Color(LilypadTokens.Roles.OUTLINE.toInt()),
            outlineVariant = Color(LilypadTokens.HAIRLINE.toInt()),
        )

        /** The gallery's ToadDarkColors family, kept as the documented fallback palette. */
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

// Accent-invariant roles (see [EditorPalette]): error family, pure-black scrim, and the
// gallery's fixed inverseOnSurface. Canvas matting black is not a scheme role; EditActivity paints it.
private val EditorError = Color(0xFFF2B8B5)
private val EditorOnError = Color(0xFF601C10)
private val EditorErrorContainer = Color(0xFF8C1D18)
private val EditorOnErrorContainer = Color(0xFFF9DEDC)
private val EditorScrim = Color(0xFF000000)
private val EditorInverseOnSurface = Color(0xFF1D1C17)

/**
 * The fixed dark [ColorScheme] a palette produces. Selection mechanism lives here, once:
 * secondaryContainer = primary, so a selected FilterChip is the accent-filled pill.
 */
internal fun EditorPalette.toDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    // Selection mechanism: a selected FilterChip fills with the accent, no per-chip overrides.
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
 * The editor theme every `setContent` wraps content in: the Lilypad palette over fixed-dark
 * chrome — deterministic, never dynamic color. [palette] is the plug-in point; call sites
 * use the Lilypad default.
 */
@Composable
internal fun EditorTheme(
    palette: EditorPalette = EditorPalette.Lilypad,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = palette.toDarkColorScheme(),
        typography = LilypadTypography,
        content = content,
    )
}

// The Lilypad type scale: Manrope (OFL) for display/body, JetBrains Mono (OFL) for every
// number a control shows; both bundled at res/font. Four named stops fill material3's slots.

/** Manrope at the three weights the scale uses (400 reads, 600 labels, 800 shouts). */
internal val LilypadTypefaces: FontFamily = FontFamily(
    Font(R.font.manrope_400, FontWeight.W400),
    Font(R.font.manrope_600, FontWeight.W600),
    Font(R.font.manrope_800, FontWeight.W800),
)

/** JetBrains Mono at the value weight: the face every number sets in. */
internal val LilypadMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_600, FontWeight.W600),
)

/**
 * The VALUE stop: the style every control readout sets numbers in (value pills, counters,
 * timecodes) — mono 12sp/600 so counting digits never jitter the layout.
 */
internal val LilypadValueStyle: TextStyle = TextStyle(
    fontFamily = LilypadMono,
    fontSize = LilypadTokens.TYPE_VALUE_SP.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight(LilypadTokens.TYPE_VALUE_WEIGHT),
)

/**
 * The type scale: material3's fifteen roles on Manrope. Display/headline at 800 with +4sp
 * leading; titles at 600; bodies at 400; labels compact 600.
 */
private val LilypadTypography = Typography(
    displayLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = LilypadTokens.TYPE_DISPLAY_SP.sp, lineHeight = (LilypadTokens.TYPE_DISPLAY_SP + 4).sp),
    displayMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 56.sp, lineHeight = 60.sp),
    displaySmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 44.sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 36.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = LilypadTokens.TYPE_SECTION_SP.sp, lineHeight = (LilypadTokens.TYPE_SECTION_SP + 4).sp),
    headlineSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight(LilypadTokens.TYPE_BODY_WEIGHT), fontSize = LilypadTokens.TYPE_BODY_SP.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 13.sp),
)
