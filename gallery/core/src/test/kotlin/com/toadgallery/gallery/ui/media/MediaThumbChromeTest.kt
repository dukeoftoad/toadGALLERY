package com.toadgallery.gallery.ui.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.7 media-cell chrome suite (source-scan, the
 * `AccessibilityWiringTest` convention): pins [MediaThumb]'s prototype
 * card chrome and overlay corner map --- the two visual contracts every
 * media grid inherits (Photos timeline, album detail, trash, favorites,
 * search, picked).
 *
 * ## The card chrome
 * The prototype's photo-card wrapper (`renderPhotoCard`) is a
 * `rounded-2xl overflow-hidden border` tile over `var(--bg-surface)`.
 * The cell must therefore clip to the 16dp shape, back with `bgSurface`
 * and stroke `borderSubtle` --- so loading cells and the dead-cell plate
 * read as the same bordered tile the prototype renders.
 *
 * ## The corner map
 *  - video duration chip: bottom-end (unchanged);
 *  - GIF badge: bottom-end (the prototype puts both media badges
 *    bottom-right; a GIF never carries a duration chip, so they cannot
 *    collide);
 *  - favorites heart: top-end (the prototype's `top-1.5 right-1.5`),
 *    keeping top-start free for the selection overlay's check badge.
 *
 * A failing assertion here means a grid cell lost the card chrome or an
 * overlay moved off its prototype corner --- restore the wiring or
 * re-pin this suite with the round's rationale.
 */
class MediaThumbChromeTest {

    private fun source(): String {
        val moduleRelative = "src/main/kotlin/com/toadgallery/gallery/ui/media/MediaThumb.kt"
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
    fun `cell clips to the rounded-2xl card shape`() {
        assertTrue(
            "MediaThumb must clip its cell to the 16dp (rounded-2xl) card shape",
            source().contains(".clip(cellShape)"),
        )
        assertTrue(
            "MediaThumb's card shape must be the 16dp rounded-2xl radius",
            source().contains("RoundedCornerShape(16.dp)"),
        )
    }

    @Test
    fun `cell backs with bgSurface and strokes borderSubtle`() {
        val source = source()
        assertTrue(
            "MediaThumb must back the cell with the palette's bgSurface " +
                "(the prototype's background: var(--bg-surface))",
            source.contains(".background(palette.bgSurface)"),
        )
        assertTrue(
            "MediaThumb must stroke the cell with the 1dp borderSubtle hairline",
            source.contains(".border(1.dp, palette.borderSubtle, cellShape)"),
        )
    }

    @Test
    fun `gif badge sits at the bottom-end corner`() {
        // The GIF badge's align must be BottomEnd: the only ThumbBadge
        // blocks are the duration chip and the GIF badge, both bottom-end.
        // r77.8 fix: the pre-r77.8 pattern `ThumbBadge\(\s*text = [\s\S]*?\)`
        // stopped at the FIRST closing paren --- which is the nested
        // formatDurationMs(...) call's own --- so the captured "block" was
        // the text line alone, never carried the modifier row, and the
        // BottomEnd assertion failed on a source that is actually correct
        // (the test never ran: CI compiles only the app module's tests).
        // The balanced-one-level pattern below captures the WHOLE call
        // (the `text = ` anchor keeps the private fun's DEFINITION out).
        val source = source()
        val badgeBlocks = Regex("ThumbBadge\\(\\s*text = (?:[^()]|\\([^()]*\\))*\\)")
            .findAll(source)
            .map { it.value }
            .toList()
        assertTrue(
            "the suite expects both ThumbBadge call sites (duration + GIF)",
            badgeBlocks.size == 2,
        )
        badgeBlocks.forEach { block ->
            assertTrue(
                "every ThumbBadge (duration chip + GIF badge) must align BottomEnd " +
                    "(the prototype's bottom-right media badges):\n$block",
                block.contains("Alignment.BottomEnd"),
            )
        }
    }

    @Test
    fun `favorites heart sits at the top-end corner`() {
        val source = source()
        val favoriteBlock = Regex("FavoriteBadge\\(\\s*modifier = [\\s\\S]*?\\)")
            .findAll(source)
            .firstOrNull()
        assertTrue(
            "MediaThumb must render the FavoriteBadge overlay",
            favoriteBlock != null,
        )
        assertTrue(
            "the favorites heart must align TopEnd (the prototype's " +
                "top-1.5 right-1.5 heart):\n${favoriteBlock?.value}",
            favoriteBlock?.value?.contains("Alignment.TopEnd") == true,
        )
        // And the old bottom-start seat must be gone (the r77.7 move).
        val mediaThumbBody = source.substringAfter("fun MediaThumb(")
        assertTrue(
            "the favorites heart must NOT sit bottom-start (the r77.7 corner map)",
            mediaThumbBody.contains("Alignment.BottomStart").not(),
        )
    }
}
