package com.toadgallery.gallery.ui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.7 timeline-header suite (source-scan, the
 * `AccessibilityWiringTest` convention): pins the grouped timeline's
 * prototype header chrome --- the two-edge row.
 *
 * The prototype's timeline group header (`renderPhotosTab`) is a
 * `justify-between` row: the `text-xs font-bold` `text-main` TITLE left,
 * the `text-[11px]` `text-dim` COUNT right. The pre-r77.7 render was one
 * inline "Month · N items" string in `onSurfaceVariant` --- title and
 * count jammed together, neither at the prototype's color nor edge.
 *
 * Pinned here:
 *  - the header renders a full-width `Row` (still the `timeline_header`
 *    test tag --- instrumented tests keep their wait surface);
 *  - the title carries `textMain` + the bold 12sp prototype style;
 *  - the count carries `textDim` + the 11sp prototype style;
 *  - the combined "Month · N items" string is RETIRED (its consumer is
 *    gone; the plural alone carries the count).
 */
class TimelineHeaderChromeTest {

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
    fun `header renders as a full-width row with the timeline_header tag`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/media/MediaPagingGrid.kt",
        )
        // The header Row and the tag: the Row modifier chain carries the
        // test tag AND fillMaxWidth (the two-edge layout needs the width).
        val headerBlock = Regex("TimelineRow.Header -> \\{[\\s\\S]*?is TimelineRow.Cell")
            .findAll(source)
            .firstOrNull()
        assertTrue(
            "the paged grid's header arm must exist",
            headerBlock != null,
        )
        val body = headerBlock?.value ?: ""
        assertTrue(
            "the header must render a Row (the prototype's justify-between row)",
            body.contains("Row("),
        )
        assertTrue(
            "the header Row must keep the timeline_header test tag",
            body.contains("testTag(\"timeline_header\")"),
        )
        assertTrue(
            "the header Row must fill the width (the count rides the trailing edge)",
            body.contains("fillMaxWidth()"),
        )
    }

    @Test
    fun `header title is bold 12sp textMain and count is 11sp textDim`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/media/MediaPagingGrid.kt",
        )
        val headerBlock = Regex("TimelineRow.Header -> \\{[\\s\\S]*?is TimelineRow.Cell")
            .findAll(source)
            .firstOrNull()
        val body = headerBlock?.value ?: ""
        assertTrue(
            "the header title must be the bold 12sp prototype style",
            body.contains("fontWeight = FontWeight.Bold") &&
                body.contains("fontSize = 12.sp"),
        )
        assertTrue(
            "the header title must paint textMain (the prototype's --text-main)",
            body.contains("ToadTheme.palette.textMain"),
        )
        assertTrue(
            "the header count must paint textDim (the prototype's --text-dim)",
            body.contains("ToadTheme.palette.textDim"),
        )
        assertTrue(
            "the header count must be the 11sp prototype style",
            body.contains("fontSize = 11.sp"),
        )
    }

    @Test
    fun `the combined Month-dot-count string stays retired`() {
        // The r77.7 split retired `moments_header_with_count` (title and
        // count now live in their own Texts at opposite edges). The string
        // must not come back into the header's render path.
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/media/MediaPagingGrid.kt",
        )
        assertFalse(
            "the header must not render the combined 'Month · N items' string anymore",
            source.contains("moments_header_with_count"),
        )
        val strings = sourceOf("src/main/res/values/strings_r27.xml")
        assertFalse(
            "the retired combined-header string must be removed from resources",
            strings.contains("moments_header_with_count"),
        )
    }
}
