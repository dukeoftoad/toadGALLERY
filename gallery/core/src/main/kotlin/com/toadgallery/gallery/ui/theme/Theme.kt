package com.toadgallery.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Fallback color palettes --- issue #11 (tables, rationale and the computed
// WCAG ratios: docs/architecture/design-tokens.md §3).
// ---------------------------------------------------------------------------
//
// MI-inspired interaction-design direction, no upstream assets (ADR-0008):
// a calm WARM-GRAY neutral scale with ONE restrained accent family --- the
// toad green brand hue (#3E9B4F seed) --- doing all expressive work. The
// seed itself is NOT the light primary: it pairs with white at only 3.5:1,
// below the 4.5:1 WCAG AA bar for normal text, so the light theme darkens it
// to #2E7A3C (5.3:1 with white button labels). Material You dynamic color
// still wins on Android 12+ (ADR-0009); these explicit schemes are the
// deterministic, AA-targeted baseline for API 26-31 and any device with
// dynamic color disabled.

/** Light-theme accent: the brand seed at tone-40 depth; 5.3:1 with white labels. */
private val ToadGreenPrimaryLight = Color(0xFF2E7A3C)

/** Dark-theme accent: the brand seed at tone-80 lightness; 8.0:1 with the dark onPrimary. */
private val ToadGreenPrimaryDark = Color(0xFF9CD8A2)

/**
 * Light fallback scheme: warm paper neutrals + deep toad-green accent.
 * Every role is stated explicitly (no library-default inheritance), and
 * `surfaceTint` is pinned to the primary so tonal elevation never flashes
 * the stock Material purple.
 */
private val ToadLightColors = lightColorScheme(
    primary = ToadGreenPrimaryLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAF6EC),
    onPrimaryContainer = Color(0xFF00390F),
    inversePrimary = ToadGreenPrimaryDark,
    secondary = Color(0xFF51634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E8D0),
    onSecondaryContainer = Color(0xFF0F1F10),
    tertiary = Color(0xFF4E7355),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCEBCB),
    onTertiaryContainer = Color(0xFF0A2010),
    background = Color(0xFFFCFBF7),
    onBackground = Color(0xFF1D1C17),
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF1D1C17),
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF48473F),
    surfaceTint = ToadGreenPrimaryLight,
    inverseSurface = Color(0xFF32312B),
    inverseOnSurface = Color(0xFFF5F3EA),
    outline = Color(0xFF78766C),
    outlineVariant = Color(0xFFC8C5BA),
    scrim = Color(0xFF000000),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Dark fallback scheme: the same warm-gray hue thread pushed to near-black,
 * with the accent family inverted to tone-80 lightness. Same neutrality
 * rule: one green family, everything else warm gray.
 */
private val ToadDarkColors = darkColorScheme(
    primary = ToadGreenPrimaryDark,
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF14522A),
    onPrimaryContainer = Color(0xFFEAF6EC),
    inversePrimary = ToadGreenPrimaryLight,
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
 * toadGALLERY Material 3 theme (ADR-0009; token system: issue #11).
 *
 * - Material You dynamic color is honoured on Android 12+ so the app adapts
 *   to the user's wallpaper palette. The explicit fallback schemes above
 *   (warm grays + single green accent) cover API 26-31 and any device with
 *   dynamic color disabled, and remain the accessible baseline everywhere.
 * - Light/dark follows `isSystemInDarkTheme()`.
 * - Shape and typography come from the design-token system
 *   ([ToadShapes], [ToadTypography]) instead of library defaults.
 * - The 48dp touch-target floor is provided app-wide through
 *   `LocalMinimumInteractiveComponentSize` from [ToadTouchTarget].minimum:
 *   every Material 3 interactive component keeps at least a 48dp touch area
 *   without growing its visual bounds.
 */
@Composable
fun ToadGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ToadDarkColors
        else -> ToadLightColors
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides ToadTouchTarget.minimum) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = ToadShapes.material,
            typography = ToadTypography,
            content = content,
        )
    }
}
