package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan pins for editor wiring fixes (P3, F-71, V1, V2, V5, V6): a
 * failing pin means reverted wiring -- restore or retire it deliberately, never relax silently.
 */
class R63aFixPinsTest {

    /** The module-relative prefix of every pinned source file. */
    private val EDIT_SRC = "src/main/kotlin/com/toadgallery/editor/"

    /**
     * The working dir is the module dir under Gradle but the repo root under the
     * pre-push harness; accept both layouts.
     */
    private fun sourceFile(moduleRelative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative), // the Gradle module cwd (:editor:core)
            File(cwd.parentFile, "core/$moduleRelative"), // a sibling :editor:* module cwd
            File(cwd, "editor/core/$moduleRelative"), // the repo/worktree-root cwd (the harness)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate $moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    private fun editSource(moduleRelative: String): String =
        sourceFile(moduleRelative).readText()

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
        val end = source.indexOf("private fun PreviewTransportOverlay(")
        assertTrue("the PreviewSurface/PreviewTransportOverlay definitions must exist", start >= 0 && end > start)
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
        // The Main branch: from the first TrimStrip call to the first EditorTabBar call.
        val start = source.indexOf("TrimStrip(")
        val end = source.indexOf("EditorTabBar(")
        assertTrue("the Main-branch trim strip/tab bar calls must exist", start >= 0 && end > start)
        val mainBranch = source.substring(start, end)
        assertFalse(
            "V5: the dead weight(1f) Spacer between the filmstrip and the tab " +
                "bar must stay gone (the preview's weight(1f) takes the space)",
            mainBranch.contains("Spacer(Modifier.weight"),
        )
    }

    // ---- V2: the fullscreen toggle survives the transport retirement ----

    @Test
    fun `fullscreen toggle anchors inside the preview frame`() {
        val source = editSource(EDIT_SRC + "video/VideoEditorScreen.kt")
        // The three-button transport row is retired; the toggle lives inside the preview frame.
        assertFalse(
            "r63d: the three-button transport row must stay retired",
            source.contains("private fun TransportRow("),
        )
        val overlayStart = source.indexOf("private fun PreviewTransportOverlay(")
        val overlayEnd = source.indexOf("internal fun VideoTimeRow(")
        assertTrue(
            "the PreviewTransportOverlay/VideoTimeRow definitions must exist",
            overlayStart >= 0 && overlayEnd > overlayStart,
        )
        val overlay = source.substring(overlayStart, overlayEnd)
        assertTrue(
            "r63d: the transport overlay must host the fullscreen toggle (onToggleFullscreen)",
            overlay.contains("onToggleFullscreen"),
        )
        assertTrue(
            "r63d: the toggle's zoom state description wiring must ride the overlay",
            overlay.contains("fullscreenZoomed"),
        )
        assertTrue(
            "r63d: the overlay chip must carry the play/pause contentDescription",
            overlay.contains("contentDescription = if (isPlaying) pauseA11y else playA11y"),
        )
        val previewStart = source.indexOf("private fun PreviewSurface(")
        val preview = source.substring(previewStart, overlayStart)
        assertTrue(
            "r63d: PreviewSurface must render the transport slot INSIDE the frame",
            preview.contains("transport()"),
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
