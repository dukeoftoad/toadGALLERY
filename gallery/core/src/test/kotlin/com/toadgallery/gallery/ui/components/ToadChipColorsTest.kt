package com.toadgallery.gallery.ui.components

import com.toadgallery.gallery.ui.theme.AllToadPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.7 chip-vocabulary suite: pins BOTH halves of the prototype's chip
 * answer --- the pure palette mapping and its wiring.
 *
 * ## The pure mapping (behavioral)
 * [toadChipColors] must transcribe the prototype's filter/prompt chip
 * markup for EVERY palette (the five `[data-theme]` blocks):
 *  - selected -> the solid `primary` fill with an `onPrimary` label and
 *    NO stroke (the fill carries the edge);
 *  - unselected -> the `bgSurface` fill with a `textMuted` label and the
 *    `1dp borderSubtle` stroke.
 * A failing assertion here means the vocabulary drifted from the
 * prototype's CSS (e.g. someone re-pointed the selected fill at
 * `secondaryContainer` --- the exact near-black-pill divergence this
 * retires).
 *
 * ## The wiring (source-scan, the `AccessibilityWiringTest` convention)
 * The chip call sites MUST render through [ToadChipDefaults] --- the
 * Photos filter-chip row, the Search mode/type chips, and the prompt
 * chips' `borderSubtle` stroke. The r37 lesson ("CI compiles what's
 * there --- it cannot test for absence") is what the scan pins: a future
 * chip that silently drops back to stock M3 colors fails here, not on a
 * device screenshot.
 */
class ToadChipColorsTest {

    // ---- The pure mapping: every palette, both states ----

    @Test
    fun `selected chips map to primary fill with onPrimary label and no stroke`() {
        AllToadPalettes.forEach { palette ->
            val c = toadChipColors(palette, selected = true)
            assertEquals(
                "selected chip fill must be the palette primary (${palette.id})",
                palette.primary,
                c.container,
            )
            assertEquals(
                "selected chip label must be onPrimary (${palette.id})",
                palette.onPrimary,
                c.content,
            )
            assertNull(
                "selected chip must carry no stroke --- the fill IS the edge (${palette.id})",
                c.stroke,
            )
        }
    }

    @Test
    fun `unselected chips map to surface fill with muted label and subtle stroke`() {
        AllToadPalettes.forEach { palette ->
            val c = toadChipColors(palette, selected = false)
            assertEquals(
                "unselected chip fill must be bgSurface (${palette.id})",
                palette.bgSurface,
                c.container,
            )
            assertEquals(
                "unselected chip label must be textMuted (${palette.id})",
                palette.textMuted,
                c.content,
            )
            assertEquals(
                "unselected chip stroke must be borderSubtle (${palette.id})",
                palette.borderSubtle,
                c.stroke,
            )
        }
    }

    @Test
    fun `every palette's selected fill contrasts with its unselected fill`() {
        // The whole point of the vocabulary: the active chip reads as the
        // ONE bright element in the row. A palette whose primary ~= its
        // bgSurface would make the selection invisible --- reject it here.
        AllToadPalettes.forEach { palette ->
            val selected = toadChipColors(palette, selected = true).container
            val unselected = toadChipColors(palette, selected = false).container
            assertTrue(
                "palette ${palette.id}: selected and unselected chip fills must differ",
                selected != unselected,
            )
        }
    }

    // ---- The wiring: the chip call sites ----

    /** Same cwd tolerance as `AccessibilityWiringTest` (module dir vs repo root). */
    private fun sourceOf(moduleRelative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative),
            File(cwd, "gallery/core/$moduleRelative"),
            File(cwd.parentFile, "gallery/core/$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?.readText()
            ?: error(
                "Cannot locate gallery/core/$moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    @Test
    fun `photos filter chips row renders through ToadChipDefaults`() {
        val source = sourceOf("src/main/kotlin/com/toadgallery/gallery/ui/photos/PhotosFilterChips.kt")
        assertTrue(
            "PhotosFilterChipsRow must import the shared chip vocabulary",
            source.contains("import com.toadgallery.gallery.ui.components.ToadChipDefaults"),
        )
        assertTrue(
            "PhotosFilterChipsRow must feed ToadChipDefaults to its FilterChips",
            source.contains("colors = ToadChipDefaults.colors()"),
        )
        assertTrue(
            "PhotosFilterChipsRow must feed ToadChipDefaults' border to its FilterChips",
            source.contains("border = ToadChipDefaults.border()"),
        )
        // Every chip in the row carries the vocabulary: count the
        // FilterChip constructors against the `colors =` sites.
        val chipCount = Regex("FilterChip\\(").findAll(source).count()
        val colorCount = Regex("colors = ToadChipDefaults\\.colors\\(\\)").findAll(source).count()
        assertTrue(
            "every FilterChip in the row must carry the vocabulary ($colorCount of $chipCount)",
            colorCount >= chipCount,
        )
    }

    @Test
    fun `search mode and type chips render through ToadChipDefaults`() {
        val source = sourceOf("src/main/kotlin/com/toadgallery/gallery/ui/search/SearchScreen.kt")
        assertTrue(
            "SearchScreen must import the shared chip vocabulary",
            source.contains("import com.toadgallery.gallery.ui.components.ToadChipDefaults"),
        )
        val chipCount = Regex("FilterChip\\(").findAll(source).count()
        val colorCount = Regex("colors = ToadChipDefaults\\.colors\\(\\)").findAll(source).count()
        assertTrue(
            "SearchScreen's mode/type FilterChips must all carry the vocabulary " +
                "($colorCount of $chipCount)",
            colorCount >= chipCount,
        )
    }

    @Test
    fun `prompt chips carry the borderSubtle stroke on every state`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/screens/search/SearchChrome.kt",
        )
        // The stroke rides the SAME modifier chain as the fill (the
        // prototype paints border-color on both states of the chip).
        assertTrue(
            "ToadPromptChips must draw the borderSubtle stroke",
            source.contains(".border(1.dp, palette.borderSubtle, RoundedCornerShape(50))"),
        )
    }
}
