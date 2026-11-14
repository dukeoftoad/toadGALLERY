package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The non-destructive MediaStore policy: every output is a NEW-row insert, the "-edit"
 * naming rule per format, the Movies/Pictures split, pending discipline and failure recovery.
 */
class VideoOutputPolicyTest {

    // ---- the naming rule ----

    @Test
    fun `the display name derives an -edit suffix per format`() {
        assertEquals(
            "holiday-edit.mp4",
            VideoOutputPlan.displayNameFor("holiday.mp4", VideoOutputKind.TRIMMED_VIDEO),
        )
        assertEquals(
            "holiday-edit.jpg",
            VideoOutputPlan.displayNameFor("holiday.mp4", VideoOutputKind.FRAME_JPEG),
        )
        assertEquals(
            "holiday-edit.png",
            VideoOutputPlan.displayNameFor("holiday.mp4", VideoOutputKind.FRAME_PNG),
        )
        assertEquals(
            "holiday-edit.gif",
            VideoOutputPlan.displayNameFor("holiday.mp4", VideoOutputKind.ANIMATION_GIF),
        )
        assertEquals(
            "holiday-edit.webp",
            VideoOutputPlan.displayNameFor("holiday.mp4", VideoOutputKind.ANIMATION_WEBP),
        )
    }

    @Test
    fun `a name with no extension still gains the suffix and extension`() {
        assertEquals(
            "clip-edit.mp4",
            VideoOutputPlan.displayNameFor("clip", VideoOutputKind.TRIMMED_VIDEO),
        )
    }

    @Test
    fun `a null or hostile source name falls back to a stable generic name`() {
        assertEquals(
            "video-edit.mp4",
            VideoOutputPlan.displayNameFor(null, VideoOutputKind.TRIMMED_VIDEO),
        )
        assertEquals(
            "video-edit.mp4",
            VideoOutputPlan.displayNameFor("  ", VideoOutputKind.TRIMMED_VIDEO),
        )
        // path separators and control characters are stripped, never kept
        val hostile = VideoOutputPlan.displayNameFor("../ev/il" + "\u0001" + ".mp4", VideoOutputKind.TRIMMED_VIDEO)
        assertFalse(hostile.contains("/"))
        assertFalse(hostile.contains(".."))
        assertTrue(hostile.endsWith("-edit.mp4"))
    }

    // ---- the insert: a NEW row, never an update ----

    @Test
    fun `the insert values are a new-row insert with the pending flag on Q+`() {
        val values = VideoOutputPlan.insertValues(
            "holiday-edit.mp4",
            VideoOutputKind.TRIMMED_VIDEO,
            api29Plus = true,
        )
        assertEquals(
            mapOf(
                "_display_name" to "holiday-edit.mp4",
                "mime_type" to "video/mp4",
                "relative_path" to "Movies/toadGALLERY Edit",
                "is_pending" to 1,
            ),
            values,
        )
    }

    @Test
    fun `pre-Q inserts carry the display name only (the legacy path)`() {
        val values = VideoOutputPlan.insertValues(
            "holiday-edit.mp4",
            VideoOutputKind.TRIMMED_VIDEO,
            api29Plus = false,
        )
        assertEquals(
            mapOf(
                "_display_name" to "holiday-edit.mp4",
                "mime_type" to "video/mp4",
            ),
            values,
        )
    }

    @Test
    fun `video outputs land under Movies and still outputs under Pictures`() {
        assertEquals("Movies/toadGALLERY Edit", VideoOutputPlan.VIDEO_RELATIVE_DIR)
        assertEquals("Pictures/toadGALLERY Edit", VideoOutputPlan.IMAGE_RELATIVE_DIR)
        assertEquals(
            VideoOutputPlan.IMAGE_RELATIVE_DIR,
            VideoOutputPlan.relativeDirFor(VideoOutputKind.FRAME_JPEG),
        )
        assertEquals(
            VideoOutputPlan.IMAGE_RELATIVE_DIR,
            VideoOutputPlan.relativeDirFor(VideoOutputKind.ANIMATION_GIF),
        )
        assertEquals(
            VideoOutputPlan.VIDEO_RELATIVE_DIR,
            VideoOutputPlan.relativeDirFor(VideoOutputKind.TRIMMED_VIDEO),
        )
    }

    // ---- the pending-row discipline ----

    @Test
    fun `the finalizer clears IS_PENDING on Q+ and is empty pre-Q`() {
        assertEquals(mapOf("is_pending" to 0), VideoOutputPlan.finalizeValues(api29Plus = true))
        assertEquals(emptyMap<String, Any>(), VideoOutputPlan.finalizeValues(api29Plus = false))
    }

    @Test
    fun `a failed write cancels the pending row on Q+ and leaves pre-Q alone`() {
        assertEquals(
            VideoOutputPlan.Recovery.CANCEL_PENDING_ROW,
            VideoOutputPlan.failureRecovery(api29Plus = true),
        )
        assertEquals(
            VideoOutputPlan.Recovery.LEAVE_ROW,
            VideoOutputPlan.failureRecovery(api29Plus = false),
        )
    }

    // ---- the no-replace-original rule ----

    @Test
    fun `there is no replace-original operation anywhere in the policy`() {
        // Source scan: no method name may even suggest writing to the source.
        val methodNames = VideoOutputPlan::class.java.declaredMethods
            .map { it.name }
            .filter { it != "values" && it != "valueOf" }
        for (name in methodNames) {
            assertFalse(
                "the policy must never expose a source-writing op: $name",
                name.contains("update", ignoreCase = true) ||
                    name.contains("delete", ignoreCase = true) ||
                    name.contains("replace", ignoreCase = true),
            )
        }
        // Insert values never carry a source identifier: a new row keys on name + mime only.
        val values = VideoOutputPlan.insertValues("a-edit.mp4", VideoOutputKind.TRIMMED_VIDEO, true)
        assertFalse(values.keys.any { it.contains("id") })
    }

    @Test
    fun `every output kind carries its MediaStore facts`() {
        assertEquals(VideoOutputStore.VIDEO, VideoOutputKind.TRIMMED_VIDEO.store)
        for (kind in listOf(
            VideoOutputKind.FRAME_JPEG,
            VideoOutputKind.FRAME_PNG,
            VideoOutputKind.ANIMATION_GIF,
            VideoOutputKind.ANIMATION_WEBP,
        )) {
            assertEquals(VideoOutputStore.IMAGE, kind.store)
        }
    }
}
