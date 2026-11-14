package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.toadgallery.editor.video.EditorScreen
import com.toadgallery.editor.video.EditorTabBar
import com.toadgallery.editor.video.TrimStrip
import com.toadgallery.editor.video.VideoEditTheme
import com.toadgallery.editor.video.VideoTimeRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Video surface screenshot baselines (trim strip, Time row, tab bar) on the shared frozen-clock harness. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class VideoTrimStripScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /** The strip's no-decode fixture: honest placeholders, deterministic. */
    private val noDecode: (count: Int, onDone: (List<android.graphics.Bitmap>) -> Unit) -> Unit =
        { _, onDone -> onDone(emptyList()) }

    @Test
    fun videoTrimStripRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VideoEditTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        VideoTimeRow(
                            leadingText = "00:02.6",
                            trailingText = "00:06.8",
                            hint = "drag handles to trim · tap to seek",
                            leadingAccent = false,
                        )
                        TrimStrip(
                            durationMs = 8_000L,
                            trimStartMs = 1_200L,
                            trimEndMs = 6_800L,
                            playheadAbsoluteMs = 2_600L,
                            enabled = true,
                            filmstripA11yName = "Clip filmstrip; drag the handles to trim, tap to seek",
                            positionStateText = "Position 00:02.6",
                            startHandleA11y = "Trim start position",
                            endHandleA11y = "Trim end position",
                            startHandleState = "00:01.2",
                            endHandleState = "00:06.8",
                            onDecodeThumbnails = noDecode,
                            onDragStarted = {},
                            onHandleDragFinished = {},
                            onScrubFinished = {},
                            onStartDrag = {},
                            onEndDrag = {},
                            onSeekAbsolute = {},
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun videoDraggingTrimStripRecordsBaseline() {
        // The drag-live state: the leading timestamp rides accent ("accent, mono" rule) and the
        // window hugs the start handle; the golden pins the accent + scrim + clamp trio.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VideoEditTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    TrimStrip(
                        durationMs = 8_000L,
                        trimStartMs = 3_000L,
                        trimEndMs = 7_200L,
                        playheadAbsoluteMs = 3_000L,
                        enabled = true,
                        filmstripA11yName = "Clip filmstrip; drag the handles to trim, tap to seek",
                        positionStateText = "Position 00:03.0",
                        startHandleA11y = "Trim start position",
                        endHandleA11y = "Trim end position",
                        startHandleState = "00:03.0",
                        endHandleState = "00:07.2",
                        onDecodeThumbnails = noDecode,
                        onDragStarted = {},
                        onHandleDragFinished = {},
                        onScrubFinished = {},
                        onStartDrag = {},
                        onEndDrag = {},
                        onSeekAbsolute = {},
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun videoTimeRowRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VideoEditTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        VideoTimeRow(
                            leadingText = "00:01.6",
                            trailingText = "00:07.7",
                            hint = "drag handles to trim · tap to seek",
                            leadingAccent = false,
                        )
                        VideoTimeRow(
                            leadingText = "00:01.2",
                            trailingText = "00:07.7",
                            hint = null,
                            leadingAccent = true,
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun videoTabBarRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VideoEditTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    EditorTabBar(current = EditorScreen.Trim, onSelect = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }
}
