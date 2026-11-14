package com.toadgallery.gallery.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.8 chrome-wiring suite: source-scan pins for the round's two
 * prototype-parity fixes, in the established pure-JVM style of
 * `AccessibilityWiringTest` (the r37 lesson --- "CI compiles what's there,
 * it cannot test for absence" --- is exactly what a source scan is for).
 *
 * The pure halves have their own behavioral tests ([ViewerInfoTest] pins
 * [ViewerInfoFormat.centerDate]'s output); these scans pin the WIRING:
 *
 *  - the viewer top bar must center the media date over the counter
 *    (the prototype lightbox's `lb-date` stack) and feed it from the
 *    pure formatter, never an ad-hoc inline format;
 *  - the search results count must render through the ONE shared
 *    [com.toadgallery.gallery.ui.search]-side count composable in the
 *    prototype's chrome (12sp bold `ToadBodyFont` over `textMuted`) at
 *    BOTH pipelines' call sites, with the plurals carrying the
 *    prototype's "N matches found" wording.
 *
 * A failing assertion means the wiring was removed or drifted --- restore
 * it, do not relax the scan.
 */
class ViewerSearchChromeWiringTest {

    /** The gallery module-relative source prefix (AccessibilityWiringTest convention). */
    private val GALLERY_SRC = "src/main/kotlin/com/toadgallery/gallery/"

    /** The gallery module-relative resources prefix. */
    private val GALLERY_RES = "src/main/res/values/"

    /**
     * Unit-test working dir is the module dir under Gradle (gallery/core)
     * but is the repo root under the pre-push harness; be tolerant of
     * both (AccessibilityWiringTest convention).
     */
    private fun sourceFile(moduleRelative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative),
            File(cwd, "gallery/core/$moduleRelative"),
            File(cwd.parentFile, "gallery/core/$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate gallery/core/$moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    private fun sourceOf(moduleRelative: String): String =
        sourceFile(moduleRelative).readText()

    /** The source between two anchors, for pinning chrome INSIDE a block. */
    private fun blockOf(source: String, startAnchor: String, endAnchor: String): String {
        val start = source.indexOf(startAnchor)
        assertTrue("missing anchor: $startAnchor", start >= 0)
        val end = source.indexOf(endAnchor, start)
        assertTrue("missing end anchor: $endAnchor", end > start)
        return source.substring(start, end)
    }

    // ---- The viewer top bar's lb-date stack ----

    @Test
    fun `viewer top bar centers the media date over the counter through the pure formatter`() {
        val source = sourceOf(GALLERY_SRC + "ui/viewer/ViewerScreen.kt")

        // The label is computed from the CURRENT page item through the pure
        // formatter (the JVM-tested half), never an inline SimpleDateFormat
        // that would drift from the pinned contract.
        assertTrue(
            "ViewerScreen must compute the top-bar date through " +
                "ViewerInfoFormat.centerDate (the JVM-pinned formatter).",
            source.contains("ViewerInfoFormat.centerDate("),
        )
        assertTrue(
            "ViewerScreen must hand the computed label to ViewerTopBar " +
                "(dateLabel = centerDateLabel).",
            source.contains("dateLabel = centerDateLabel"),
        )

        // The bar's center block: the date is the PRIMARY line in the
        // prototype's `text-xs font-bold` chrome (12sp bold), the counter
        // the secondary line, and the r40 polite live region stays on the
        // block so swipes still announce position. Anchored on the r77.8
        // comment so the scan reads the CENTER block, not the first
        // Column( in the file.
        val center = blockOf(
            source,
            "// r77.8: the center block takes the prototype lightbox's shape",
            "// ---- Slideshow (r27) ----",
        )
        assertTrue(
            "The top-bar center must render the date line in the prototype's " +
                "12sp bold chrome before the counter line.",
            Regex(
                "dateLabel,\\s*\\n\\s*style = TextStyle\\(" +
                    "[\\s\\S]*?fontFamily = ToadBodyFont,[\\s\\S]*?" +
                    "fontWeight = FontWeight\\.Bold,[\\s\\S]*?" +
                    "fontSize = 12\\.sp,",
            ).containsMatchIn(center),
        )
        assertTrue(
            "The counter must stay the center block's secondary line " +
                "(the date renders first).",
            center.indexOf("dateLabel,") < center.indexOf("R.string.viewer_counter"),
        )
        assertTrue(
            "The r40 polite live region must stay on the center block " +
                "(swipes announce date + position).",
            center.contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    // ---- The search results-count header (both pipelines) ----

    @Test
    fun `search results count renders the prototype chrome through one shared composable`() {
        val source = sourceOf(GALLERY_SRC + "ui/search/SearchScreen.kt")

        // ONE shared count composable for both pipelines (the anti-drift
        // seam), carrying the prototype's results-header chrome.
        val fn = blockOf(
            source,
            "private fun SearchResultsCountText(",
            "private fun MagicSearchStates(",
        )
        assertTrue(
            "The count line must render in the prototype's 12sp bold " +
                "ToadBodyFont chrome.",
            fn.contains("fontFamily = ToadBodyFont") &&
                fn.contains("fontWeight = FontWeight.Bold") &&
                fn.contains("fontSize = 12.sp"),
        )
        assertTrue(
            "The count line must use the palette's muted tone " +
                "(the prototype's var(--text-muted)), not onSurfaceVariant.",
            fn.contains("ToadTheme.palette.textMuted"),
        )

        // BOTH pipelines' result rows feed it --- no call site may keep its
        // own inline count Text. The scan is BLOCK-SCOPED to each Results
        // row (the file's other stock bodyMedium Texts --- the state cards
        // --- are legitimate M3 surfaces, not the drifted count line).
        val nameResults = blockOf(
            source,
            "is SearchUiState.Results -> Column(",
            "MediaGrid(",
        )
        assertTrue(
            "The Name pipeline's Results row must render through the shared " +
                "count composable.",
            nameResults.contains("SearchResultsCountText("),
        )
        assertFalse(
            "The Name pipeline's count may not keep a stock bodyMedium Text " +
                "(the drift this retires).",
            nameResults.contains("MaterialTheme.typography"),
        )
        val contentResults = blockOf(
            source,
            "is MagicSearchUiState.Results -> Column(",
            "MediaGrid(",
        )
        assertTrue(
            "The Content pipeline's Results row must render through the shared " +
                "count composable.",
            contentResults.contains("SearchResultsCountText("),
        )
        assertFalse(
            "The Content pipeline's count may not keep a stock bodyMedium Text " +
                "(the drift this retires).",
            contentResults.contains("MaterialTheme.typography"),
        )
    }

    @Test
    fun `both result-count plurals carry the prototype's matches-found wording`() {
        val mainPlurals = sourceOf(GALLERY_RES + "strings.xml")
        val magicPlurals = sourceOf(GALLERY_RES + "strings_r26.xml")
        assertTrue(
            "search_results_count must carry the prototype's 'matches found' " +
                "wording (the results header, \"9 matches found\").",
            mainPlurals.contains("%1\$d matches found") &&
                mainPlurals.contains("%1\$d match found"),
        )
        assertTrue(
            "magic_results_count must share the same wording --- one " +
                "vocabulary for both pipelines.",
            magicPlurals.contains("%1\$d matches found") &&
                magicPlurals.contains("%1\$d match found"),
        )
    }

    // ---- The results header SLOT (r77.9) ----

    @Test
    fun `the CLIP badge rides the shared results header below the chip rows`() {
        val source = sourceOf(GALLERY_SRC + "ui/search/SearchScreen.kt")

        // r77.9 slot move: the prototype renders the "CLIP + OCR Active"
        // badge as the results header's TRAILING edge --- beside the live
        // count, immediately above the grid, BELOW the secondary attribute
        // filters. The badge must therefore render INSIDE the shared
        // results-header composable (never back above the chip rows).
        val fn = blockOf(
            source,
            "private fun SearchResultsCountText(",
            "private fun MagicSearchStates(",
        )
        assertTrue(
            "The shared results header must render the CLIP badge on its " +
                "trailing edge (the prototype's header row).",
            fn.contains("ToadClipOcrBadge()"),
        )
        assertTrue(
            "The badge inside the results header must keep the r46 " +
                "build-shape gate (no semantic index -> no CLIP/OCR badge).",
            Regex(
                "if \\(BuildShape\\.semanticIndex\\) \\{\\s*\\n\\s*ToadClipOcrBadge\\(\\)",
            ).containsMatchIn(fn),
        )

        // The pre-move static header ("Matches" + badge above the mode/
        // type chip rows) must stay retired: the prototype never draws a
        // slot there, and the hardcoded label showed even with zero
        // results.
        assertFalse(
            "The static 'Matches' label row above the chip rows must stay " +
                "retired (r77.9: the badge lives in the results header).",
            source.contains("text = \"Matches\""),
        )
    }
}
