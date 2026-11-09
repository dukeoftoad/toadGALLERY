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

/**
 * The video surface's screenshot baselines (r63d, the approved section-5
 * spec) --- the trim-on-filmstrip strip, the Time row and the flex tab bar,
 * the pieces the r63d extraction made composable in isolation. Harness
 * shape = [EditChassisScreenshotTest] verbatim (the r56 rules): param-light
 * subjects (no Activity, no ViewModel, no real bitmap decode, no
 * indeterminate animation), the pinned Robolectric SDK 34 and the fixed
 * w411dp-h891dp-420dpi viewport, the frame clock frozen before setContent
 * and advanced one fixed 16ms frame, and the theme's background painted
 * first (a transparent-root capture would gate near-white ink against
 * white, not the contrast the user actually sees).
 *
 * The strip fixture renders the honest dark placeholder slots (the decode
 * callback delivers an empty list --- the same surface a slow decode
 * shows), with the window at 1200..6800ms of an 8000ms clip and the
 * playhead at 2600ms: one capture pins the two 16dp handles, the dim scrim
 * OUTSIDE the window, and the 2dp white playhead clamped INSIDE it.
 *
 * The PNG names are pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property:
 * `com.toadgallery.editor.VideoTrimStripScreenshotTest.<method>.png`
 * under src/test/resources/roborazzi (the plugin outputDir). The plugin's
 * recordRoborazziDebug task records them; verifyRoborazziDebug gates
 * against the committed baselines once they exist.
 */
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
        // The drag-live state: the leading timestamp rides accent (the
        // section-5 "accent, mono" rule) and the window hugs the start
        // handle, so the golden pins the accent + scrim + clamp trio.
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
