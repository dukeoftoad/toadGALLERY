package io.github.dukeoftoad.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// toadGALLERY identity tokens (Phase 2 seed set - issue #11).
// Single source of truth for the brand palette until the design system doc freezes it.
private val ToadGreen = Color(0xFF3E9B4F)
private val ToadGreenDark = Color(0xFF2E7A3C)
private val ToadGreenAccent = Color(0xFF5DBF6E)
private val ToadGreenContainerLight = Color(0xFFEAF6EC)

private val ToadLightColors = lightColorScheme(
    primary = ToadGreen,
    onPrimary = Color.White,
    primaryContainer = ToadGreenContainerLight,
    onPrimaryContainer = Color(0xFF00390F),
    secondary = ToadGreenDark,
    onSecondary = Color.White,
)

private val ToadDarkColors = darkColorScheme(
    primary = ToadGreenAccent,
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF14522A),
    onPrimaryContainer = ToadGreenContainerLight,
    secondary = ToadGreenAccent,
    onSecondary = Color(0xFF00390F),
)

/**
 * toadGALLERY Material 3 theme (ADR-0009).
 *
 * Material You dynamic color is honoured on Android 12+ so the app adapts to the
 * user's wallpaper palette; the toad green brand scheme is the fallback on older
 * devices and the accessible baseline everywhere. The design-token expansion
 * (typography, shape, custom color roles) is issue #11.
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}
