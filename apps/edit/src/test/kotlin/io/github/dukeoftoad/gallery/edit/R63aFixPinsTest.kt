package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r63a fix-first round's source pins (the executable half of the five
 * fixes, in the established pure-JVM style of `AccessibilityWiringTest` /
 * `PrivacyPolicyTest`: the wiring is text in the tree, and the r37 lesson
 * --- "CI compiles what's there, it cannot test for absence" --- is exactly
 * what a source-scan test is for).
 *
 * Pinned here, per flaw:
 *  - P3  (EditActivity): the Save copy CTA label carries `maxLines = 1` +
 *        `TextOverflow.Ellipsis` --- the pill can never wrap;
 *  - F-71 (EditActivity): the root Column's `systemBarsPadding()` stays the
 *        edit app's ONLY inset payment (the header must sit one inset below
 *        the window top, never two);
 *  - V1  (VideoEditorScreen): `PreviewSurface` owns no height anymore ---
 *        the owning Column decides (weight(1f) on Main, fixed 220dp on the
 *        tool screens);
 *  - V5  (VideoEditorScreen): the dead weight(1f) Spacer between the
 *        filmstrip and the tab bar stays gone;
 *  - V2  (VideoEditorScreen): the fullscreen toggle lives in the transport
 *        cluster, not as an orphan inside the preview Box;
 *  - V6  (strings): the honest tool labels --- Frame and GIF --- and the
 *        jargon-free frame-timestamp description.
 *
 * A failing assertion here means an r63a wiring was reverted --- restore it
 * or (for a deliberate redesign) retire the pin WITH the design round's
 * approval; do not relax it silently.
 */
class R63aFixPinsTest {

    /** The edit module-relative prefix of every pinned source file. */
    private val EDIT_SRC = "src/main/kotlin/io/github/dukeoftoad/gallery/edit/"

    /**
     * Unit-test working dir is the module dir under Gradle (apps/edit) but
     * is the repo root under the pre-push harness; be tolerant of both (the
     * gallery AccessibilityWiringTest moduleFile pattern, generalized).
     */
    private fun sourceFile(app: String, moduleRelative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative),
            File(cwd, "apps/$app/$moduleRelative"),
            File(cwd.parentFile, "$app/$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate apps/$app/$moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    private fun editSource(moduleRelative: String): String =
        sourceFile("edit", moduleRelative).readText()

    // ---- P3: the Save copy CTA never wraps ----

    @Test
    fun `save copy CTA label pins single-line ellipsis`() {
        val source = editSource(EDIT_SRC + "EditActivity.kt")
        val anchor = source.indexOf("R.string.editor_save_copy")
        assertTrue("the Save copy stringResource call must exist", anchor >= 0)
        val textCall = source.substring(anchor, minOf(source.length, anchor + 600))
        assertTrue(
            "the Save copy Text must carry maxLines = 1 (r63a P3: the CTA pill " +
                "never wraps; longer locales ellipsize)",
            textCall.contains("maxLines = 1"),
        )
        assertTrue(
            "the Save copy Text must carry overflow = TextOverflow.Ellipsis (r63a P3)",
            textCall.contains("TextOverflow.Ellipsis"),
        )
    }

    // ---- F-71: insets are paid exactly once at the root ----

    @Test
    fun `photo editor root pays the system bar insets exactly once`() {
        val source = editSource(EDIT_SRC + "EditActivity.kt")
        val count = Regex(Regex.escape(".systemBarsPadding()")).findAll(source).count()
        assertEquals(
            "F-71: the root Column's systemBarsPadding must stay the edit app's " +
                "ONLY inset payment --- a second one would push the header down " +
                "by another bar height",
            1,
            count,
        )
    }

    // ---- V1: the preview's height belongs to the owning Column ----

    @Test
    fun `video preview surface no longer hardcodes its height`() {
        val source = editSource(EDIT_SRC + "video/VideoEditorScreen.kt")
        val start = source.indexOf("private fun PreviewSurface(")
        val end = source.indexOf("private fun TransportRow(")
        assertTrue("the PreviewSurface/TransportRow definitions must exist", start >= 0 && end > start)
        val preview = source.substring(start, end)
        assertFalse(
            "V1: a height() inside PreviewSurface re-locks the 220dp sliver --- " +
                "the owning Column decides (weight(1f) on Main)",
            preview.contains("height(220.dp)"),
        )
        assertTrue(
            "V1: PreviewSurface must take the caller's modifier",
            preview.contains("modifier: Modifier = Modifier"),
        )
    }

    @Test
    fun `main video chassis gives the preview the leftover stage`() {
        val source = editSource(EDIT_SRC + "video/VideoEditorScreen.kt")
        val callSite = Regex(
            "if \\(screen == EditorScreen\\.Main\\) \\{\\s*" +
                "Modifier\\.weight\\(1f\\)\\s*\\}\\s*else \\{\\s*" +
                "Modifier\\.height\\(220\\.dp\\)",
        )
        assertTrue(
            "V1: the PreviewSurface call must pass weight(1f) on the Main chassis " +
                "and keep the fixed 220dp on the tool screens",
            callSite.containsMatchIn(source),
        )
    }

    // ---- V5: the filmstrip docks against the tab bar ----

    @Test
    fun `filmstrip docks against the tab bar with no dead spacer`() {
        val source = editSource(EDIT_SRC + "video/VideoEditorScreen.kt")
        // The MAIN branch: from the chassis ScrubFilmstrip call (the first
        // occurrence) to the chassis EditorTabBar call (the first one).
        val start = source.indexOf("ScrubFilmstrip(")
        val end = source.indexOf("EditorTabBar(")
        assertTrue("the Main-branch filmstrip/tab bar calls must exist", start >= 0 && end > start)
        val mainBranch = source.substring(start, end)
        assertFalse(
            "V5: the dead weight(1f) Spacer between the filmstrip and the tab " +
                "bar must stay gone (the preview's weight(1f) takes the space)",
            mainBranch.contains("Spacer(Modifier.weight"),
        )
    }

    // ---- V2: the fullscreen toggle lives in the transport cluster ----

    @Test
    fun `fullscreen toggle lives in the transport cluster`() {
        val source = editSource(EDIT_SRC + "video/VideoEditorScreen.kt")
        val transportStart = source.indexOf("private fun TransportRow(")
        val transportEnd = source.indexOf("private fun TimestampRow(")
        assertTrue(
            "the TransportRow/TimestampRow definitions must exist",
            transportStart >= 0 && transportEnd > transportStart,
        )
        val transport = source.substring(transportStart, transportEnd)
        assertTrue(
            "V2: the transport row must host the fullscreen toggle (onToggleFullscreen)",
            transport.contains("onToggleFullscreen"),
        )
        assertTrue(
            "V2: the toggle's zoom state description wiring must ride the row",
            transport.contains("fullscreenZoomed"),
        )
        val previewStart = source.indexOf("private fun PreviewSurface(")
        val previewEnd = source.indexOf("private fun TransportRow(")
        val preview = source.substring(previewStart, previewEnd)
        assertFalse(
            "V2: the preview Box must not host the orphan fullscreen icon again",
            preview.contains("ic_vt_fullscreen"),
        )
    }

    // ---- V6: the honest tool labels ----

    @Test
    fun `video tool labels are the honest Frame and GIF`() {
        val tabs = editSource("src/main/res/values/strings_r52.xml")
        assertTrue(
            "V6: the Extract tool's label must read Frame (it saves a still frame)",
            tabs.contains("<string name=\"editor_v2_tab_extract\">Frame</string>"),
        )
        assertTrue(
            "V6: the Animate tool's label must read GIF (it exports a GIF segment)",
            tabs.contains("<string name=\"editor_v2_tab_animate\">GIF</string>"),
        )
        val r42 = editSource("src/main/res/values/strings_r42.xml")
        assertTrue(
            "V6: the GIF screen's export button must read Export GIF",
            r42.contains("<string name=\"editor_video_animation_export\">Export GIF</string>"),
        )
        assertTrue(
            "V6: the frame timestamp description drops the extraction jargon",
            r42.contains(
                "<string name=\"editor_a11y_video_frame_timestamp\">Frame timestamp</string>",
            ),
        )
    }
}
