package io.github.dukeoftoad.gallery.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the design-token invariants (issue #11; frozen values:
 * docs/architecture/design-tokens.md §2).
 *
 * The tokens are pure Kotlin values — `Dp`/`TextUnit` value classes and ints,
 * no `android.*` runtime — so these run in plain `testDebugUnitTest` (no
 * emulator, no Robolectric) and pin what the design system depends on:
 *
 *  - the 48dp touch-target accessibility floor (Material guidance),
 *  - the strictly ascending spacing, shape and elevation scales,
 *  - the wiring of the token radii into the Material 3 `Shapes`,
 *  - the motion durations,
 *  - explicit (never unspecified) sizes/line heights for all 15 type roles.
 *
 * If one of these fails, a token was tuned without updating the frozen
 * tables in docs/architecture/design-tokens.md — fix the doc or revert the
 * value; do not relax the test.
 */
class DesignTokensTest {

    @Test
    fun `touch target meets the 48dp material minimum`() {
        assertEquals(48f, ToadTouchTarget.minimum.value)
        assertTrue(
            "The a11y floor must never drop below 48dp",
            ToadTouchTarget.minimum.value >= 48f,
        )
    }

    @Test
    fun `spacing scale is the frozen seven-step grid`() {
        assertEquals(
            listOf(2f, 4f, 8f, 12f, 16f, 24f, 32f),
            ToadSpacing.scale.map { it.value },
        )
    }

    @Test
    fun `spacing scale is strictly ascending`() {
        val scale = ToadSpacing.scale
        assertTrue("The scale must have steps to compare", scale.size >= 2)
        for (i in 1 until scale.size) {
            assertTrue(
                "Spacing step ${i - 1} (${scale[i - 1]}) must be smaller than step $i (${scale[i]})",
                scale[i - 1].value < scale[i].value,
            )
        }
    }

    @Test
    fun `shape radii are the frozen scale and strictly ascending`() {
        assertEquals(
            listOf(4f, 8f, 12f, 16f, 28f),
            ToadShapes.radii.map { it.value },
        )
        val radii = ToadShapes.radii
        for (i in 1 until radii.size) {
            assertTrue(
                "Radius step ${i - 1} (${radii[i - 1]}) must be smaller than step $i (${radii[i]})",
                radii[i - 1].value < radii[i].value,
            )
        }
    }

    @Test
    fun `material shapes are wired with the token radii`() {
        assertEquals(ToadShapes.extraSmall, ToadShapes.material.extraSmall.size)
        assertEquals(ToadShapes.small, ToadShapes.material.small.size)
        assertEquals(ToadShapes.medium, ToadShapes.material.medium.size)
        assertEquals(ToadShapes.large, ToadShapes.material.large.size)
        assertEquals(ToadShapes.extraLarge, ToadShapes.material.extraLarge.size)
    }

    @Test
    fun `elevation levels are non-negative and strictly ascending`() {
        assertEquals(0f, ToadElevation.level0.value)
        val levels = ToadElevation.levels
        for (i in 1 until levels.size) {
            assertTrue(
                "Elevation level ${i - 1} (${levels[i - 1]}) must be smaller than level $i (${levels[i]})",
                levels[i - 1].value < levels[i].value,
            )
        }
    }

    @Test
    fun `motion durations are fast then standard`() {
        assertEquals(150, ToadMotion.fast)
        assertEquals(250, ToadMotion.standard)
        assertTrue(
            "fast (${ToadMotion.fast}ms) must be quicker than standard (${ToadMotion.standard}ms)",
            ToadMotion.fast < ToadMotion.standard,
        )
    }

    @Test
    fun `state sizes are pinned to the frozen contract`() {
        assertEquals(72f, ToadSizing.stateIcon.value)
        // The loading indicator rides the touch-target grid.
        assertTrue(
            "stateProgress must be at least the touch-target floor",
            ToadSizing.stateProgress >= ToadTouchTarget.minimum,
        )
    }

    @Test
    fun `every type role has an explicit size and adequate line height`() {
        val roles = listOf(
            ToadTypography.displayLarge,
            ToadTypography.displayMedium,
            ToadTypography.displaySmall,
            ToadTypography.headlineLarge,
            ToadTypography.headlineMedium,
            ToadTypography.headlineSmall,
            ToadTypography.titleLarge,
            ToadTypography.titleMedium,
            ToadTypography.titleSmall,
            ToadTypography.bodyLarge,
            ToadTypography.bodyMedium,
            ToadTypography.bodySmall,
            ToadTypography.labelLarge,
            ToadTypography.labelMedium,
            ToadTypography.labelSmall,
        )
        assertEquals("All fifteen M3 roles must be pinned", 15, roles.size)
        roles.forEach { style ->
            assertTrue(
                "fontSize must be explicit and positive (was ${style.fontSize})",
                style.fontSize.value > 0f,
            )
            assertTrue(
                "lineHeight (${style.lineHeight}) must be at least fontSize (${style.fontSize})",
                style.lineHeight >= style.fontSize,
            )
        }
    }
}
