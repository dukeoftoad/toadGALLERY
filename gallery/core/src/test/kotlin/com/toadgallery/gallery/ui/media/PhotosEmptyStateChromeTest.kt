package com.toadgallery.gallery.ui.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.9 filtered-empty suite (source-scan, the
 * `AccessibilityWiringTest` convention): pins the Photos filtered-empty
 * state's prototype CTA --- the "Reset Filters" pill.
 *
 * The prototype's empty state (`renderPhotosTab`, the `groups.length === 0`
 * arm) closes with a primary pill button --- `rounded-full` over
 * `var(--primary)`, `text-xs font-bold` --- wired to `resetPhotoFilters`.
 * The Compose side kept the chips row reachable but offered NO reset
 * affordance on the state itself; an over-narrow filter stranded the user
 * with only the copy's "try clearing a chip or two".
 *
 * Pinned here:
 *  - the filtered-empty [StateScreen] call carries the action wiring
 *    (`state_photos_filtered_empty_action` +
 *    `onFilterChange(TimelineFilter())` --- the SAME reset the chips row's
 *    Clear affordance runs, nothing else) and `pillAction = true`;
 *  - [StateScreen] renders the house [ToadPillButton] for the pill chrome;
 *  - [ToadPillButton] carries `Role.Button` (the prototype's markup is a
 *    real `<button>`; the r77.8 convention on interactive chrome).
 */
class PhotosEmptyStateChromeTest {

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
    fun `the filtered-empty state carries the prototype's reset-filters pill`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/photos/PhotosScreen.kt",
        )
        // The filtered-empty arm only (anchored on the state's KDoc so the
        // scan reads THIS call site, not the clean-library state beside it).
        val arm = Regex(
            "r77\\.9: the prototype's empty state closes with a[\\s\\S]*?pillAction = true,",
        ).find(source)?.value
        assertTrue(
            "the filtered-empty StateScreen must carry the r77.9 pill wiring " +
                "(actionRes + onAction + pillAction = true)",
            arm != null,
        )
        val body = arm ?: ""
        assertTrue(
            "the pill's label must be the state_photos_filtered_empty_action string",
            body.contains("R.string.state_photos_filtered_empty_action"),
        )
        assertTrue(
            "the pill must run the SAME reset the Clear chip runs --- " +
                "onFilterChange(TimelineFilter()), nothing else",
            body.contains("viewModel.onFilterChange(TimelineFilter())"),
        )
    }

    @Test
    fun `StateScreen renders the pill CTA through the house ToadPillButton`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/ToadGalleryApp.kt",
        )
        val actionBlock = Regex(
            "if \\(pillAction\\) \\{[\\s\\S]*?\\n        \\}",
        ).find(source)?.value
        assertTrue(
            "StateScreen must branch on pillAction for the pill chrome",
            actionBlock != null,
        )
        assertTrue(
            "the pill branch must render the house ToadPillButton " +
                "(the prototype's primary pill, one token source)",
            (actionBlock ?: "").contains("ToadPillButton("),
        )
    }

    @Test
    fun `the pill CTA carries the button role for TalkBack`() {
        val source = sourceOf(
            "src/main/kotlin/com/toadgallery/gallery/ui/components/ToadComponents.kt",
        )
        // The role sits on ToadPillButton's clickable Box (the r77.8
        // convention: the prototype's <button> markup, announced as a
        // button --- not plain text).
        val fn = Regex("fun ToadPillButton\\([\\s\\S]*?\\n\\}")
            .find(source)?.value
        assertTrue(
            "ToadPillButton must exist (the house pill component)",
            fn != null,
        )
        assertTrue(
            "ToadPillButton must carry Role.Button so TalkBack announces " +
                "the affordance (the prototype's real <button> markup)",
            (fn ?: "").contains(".semantics { role = Role.Button }"),
        )
    }

    @Test
    fun `the reset-filters string exists with the prototype's wording`() {
        val strings = sourceOf("src/main/res/values/strings_r17.xml")
        assertTrue(
            "strings_r17 must declare state_photos_filtered_empty_action " +
                "(the empty state's pill CTA)",
            strings.contains("<string name=\"state_photos_filtered_empty_action\">"),
        )
    }
}
