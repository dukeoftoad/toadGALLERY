package io.github.dukeoftoad.gallery.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.CornerSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * toadGALLERY design tokens --- issue #11 (full tables and rationale:
 * docs/architecture/design-tokens.md).
 *
 * Single source of truth for every non-color visual primitive: spacing,
 * shape, elevation, motion, the touch-target floor, frozen state-component
 * sizes, and the type roles ([ToadTypography]). Call sites consume these (or
 * the `MaterialTheme` roles they feed) instead of raw dp/sp literals so the
 * app keeps one coherent visual language. The only color tokens live in
 * `Theme.kt` as the fallback palettes.
 *
 * Provenance (ADR-0008): interaction-design inspiration only. The calm,
 * chrome-light, content-forward direction is informed by Mi Gallery; NO
 * upstream assets, fonts, icons, or branding are used or referenced.
 *
 * Everything here is a pure Kotlin value (`Dp` / `TextUnit` value classes and
 * ints), so the JVM test `DesignTokensTest` can pin the invariants --- the
 * 48dp touch floor, strictly ascending scales, shape-radius ordering ---
 * without an emulator or Robolectric.
 */

/**
 * Spacing scale --- the frozen seven-step grid (2/4/8/12/16/24/32dp) used for
 * all paddings and gaps. `DesignTokensTest` asserts the scale stays strictly
 * ascending; new screens pick the nearest step instead of inventing literals.
 */
object ToadSpacing {
    /** 2dp --- hairline gutters between media-grid cells. */
    val xs: Dp = 2.dp

    /** 4dp --- tight padding inside small components (thumbnail overlays). */
    val sm: Dp = 4.dp

    /** 8dp --- default gap between related elements. */
    val md: Dp = 8.dp

    /** 12dp --- dense group padding. */
    val lg: Dp = 12.dp

    /** 16dp --- screen margin / card padding default. */
    val xl: Dp = 16.dp

    /** 24dp --- separation between sections and action clusters. */
    val xxl: Dp = 24.dp

    /** 32dp --- full-screen state-column page padding. */
    val xxxl: Dp = 32.dp

    /** The whole scale, ascending; exposed for the JVM-test invariant. */
    val scale: List<Dp> = listOf(xs, sm, md, lg, xl, xxl, xxxl)
}

/**
 * Shape scale --- corner radii, handed to `MaterialTheme.shapes` through
 * [material]. The three headline steps (small 8 / medium 12 / large 16dp)
 * are the issue #11 brief; extraSmall/extraLarge complete the Material 3 set
 * so no component ever falls back to library-default radii.
 */
object ToadShapes {
    /** 4dp --- chips, badges, tiny overlays. */
    val extraSmall: Dp = 4.dp

    /** 8dp --- small controls (buttons, text fields). */
    val small: Dp = 8.dp

    /** 12dp --- cards, menus, dialogs. */
    val medium: Dp = 12.dp

    /** 16dp --- large cards, bottom-sheet tops, media cards. */
    val large: Dp = 16.dp

    /** 28dp --- extra-large surfaces (full-width sheets). */
    val extraLarge: Dp = 28.dp

    /** The whole radius scale, ascending; exposed for the JVM-test invariant. */
    val radii: List<Dp> = listOf(extraSmall, small, medium, large, extraLarge)

    /** The Material 3 `Shapes` wiring built from the token radii. */
    val material: Shapes = Shapes(
        extraSmall = CornerSize(extraSmall),
        small = CornerSize(small),
        medium = CornerSize(medium),
        large = CornerSize(large),
        extraLarge = CornerSize(extraLarge),
    )
}

/**
 * Elevation scale --- Material 3 levels 0-3. Tonal elevation (surface tint)
 * carries most of the depth story in dark theme; these levels cover the
 * shadow components (bars, dialogs, sheets).
 */
object ToadElevation {
    /** Level 0 --- flat surfaces (screens, plain cards, state columns). */
    val level0: Dp = 0.dp

    /** Level 1 --- raised chips and small FABs. */
    val level1: Dp = 3.dp

    /** Level 2 --- navigation bars, cards floating over imagery. */
    val level2: Dp = 6.dp

    /** Level 3 --- dialogs and floating sheets. */
    val level3: Dp = 8.dp

    /** The whole level scale, ascending; exposed for the JVM-test invariant. */
    val levels: List<Dp> = listOf(level0, level1, level2, level3)
}

/**
 * Motion durations --- issue #11 freezes the two-step scale now (fast 150ms,
 * standard 250ms) so the first animated slice adopts one consistent tempo.
 * No component consumes them yet: the state screens shipped in this slice
 * are static by design, and `DesignTokensTest` pins the values meanwhile.
 */
object ToadMotion {
    /** 150ms --- quick emphasized transitions (press feedback, fades). */
    const val fast: Int = 150

    /** 250ms --- standard transitions (state swaps, container transforms). */
    const val standard: Int = 250
}

/**
 * Accessibility floor for every interactive affordance (Material guidance,
 * WCAG 2.5.5 / 2.5.8 alignment). `ToadGalleryTheme` provides this value
 * through `LocalMinimumInteractiveComponentSize`, so every Material 3
 * interactive component (buttons, nav items, toggles) keeps at least a 48dp
 * touch area without growing its visual bounds. Custom low-level
 * affordances must apply `Modifier.minimumInteractiveComponentSize()`
 * themselves; `DesignTokensTest` fails if this constant ever drops.
 */
object ToadTouchTarget {
    /** 48dp --- minimum physical touch-target size. */
    val minimum: Dp = 48.dp
}

/**
 * One-off component sizes frozen by the screen-state system
 * (docs/architecture/screen-states.md §1), pinned as tokens so the state
 * layout cannot drift with material3 default changes.
 */
object ToadSizing {
    /** 72dp decorative state icon (frozen screen-state layout contract). */
    val stateIcon: Dp = 72.dp

    /** 48dp full-screen loading indicator --- aligned to the touch-target grid. */
    val stateProgress: Dp = ToadTouchTarget.minimum
}

/**
 * toadGALLERY type roles --- the Material 3 baseline pinned explicitly.
 *
 * Every one of the fifteen roles states size, line height, letter spacing and
 * weight instead of inheriting library defaults, so typography cannot drift
 * with a material3 upgrade (values equal today's baseline, hence zero visual
 * change; they are frozen because they must not silently change tomorrow).
 *
 * No font family is bundled: the platform default typeface is used. Shipping
 * a custom typeface would mean shipping binary assets, and nothing
 * proprietary is allowed into the tree (ADR-0008; the Mi typefaces are NOT
 * used --- interaction-design inspiration only).
 */
val ToadTypography: Typography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium),
)
