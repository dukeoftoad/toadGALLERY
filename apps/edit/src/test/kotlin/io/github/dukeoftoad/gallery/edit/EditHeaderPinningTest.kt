package io.github.dukeoftoad.gallery.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The r63a fix-first round's layout pins (the F-71 / P1 hard-pin + the P3
 * CTA wire).
 *
 * F-71: the r62 device screenshot showed the photo editor's header ~630px
 * below the top edge with pure black above it, while EditActivity composes
 * the header as the FIRST child of the ONLY root container. The code hunt
 * exonerates the composition tree (single systemBarsPadding, no wrappers,
 * no spacers, no alternate entry) --- the offset was a device-side window
 * state, not producible by this tree. This suite is the repro attempt's
 * negative result turned into a permanent pin: under the PRODUCTION root
 * shape (fillMaxSize Surface -> fillMaxSize Column with the single
 * systemBarsPadding -> header first -> weight(1f) stage), the header MUST
 * be the top-most composed element with zero phantom band above it and no
 * dead band before the canvas.
 *
 * Subject choice, determinism-first: the real `PhotoToolHeader` chrome and
 * the replica of the main header row are PARAM-LIGHT (no Activity, no
 * decode, no animation), so the captures depend only on the pinned
 * Robolectric SDK (34) and the fixed w411dp-h891dp-420dpi viewport --- the
 * EditChassisScreenshotTest harness shape, reused exactly. The frame clock
 * is frozen before setContent and advanced one fixed 16ms frame.
 *
 * The main header's Row is private inside EditActivity, so the P3 test
 * drives a replica carrying the same wiring (weighted title, the text
 * buttons, the CTA label with `maxLines = 1` + ellipsis --- the exact
 * r63a EditActivity.kt change); R63aFixPinsTest ties that wiring to the
 * real file so a revert cannot slip past source-scan either.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class EditHeaderPinningTest {

    @get:Rule
    val rule = createComposeRule()

    private val headerTag = "r63a_header"
    private val stageTag = "r63a_stage"
    private val saveTag = "r63a_save_copy"

    /**
     * F-71: the header is the top-most composed element of the production
     * root shape. At the pinned qualifiers the Robolectric system-bar insets
     * are zero, so ANY nonzero top bound would be exactly the phantom band
     * the device screenshot showed.
     */
    @Test
    fun headerIsTheTopMostElementInTheProductionRootShape() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                // The production root: EditActivity.onCreate's setContent
                // shape (fillMaxSize Surface) -> EditorContent's root Column
                // (fillMaxSize, black background, the ONE systemBarsPadding)
                // -> the header branch FIRST -> the weight(1f) canvas.
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .systemBarsPadding(),
                    ) {
                        Box(modifier = Modifier.testTag(headerTag)) {
                            PhotoToolHeader(
                                title = "Adjust",
                                backA11y = "Back",
                                confirmA11y = "Done",
                                onBack = {},
                                onConfirm = {},
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(stageTag),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()

        val headerBounds = rule.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
        val stageBounds = rule.onNodeWithTag(stageTag).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "a phantom band sits above the header (the F-71 device signature); " +
                "the first child of the root Column must start at the very top",
            0f,
            headerBounds.top,
            0.5f,
        )
        assertEquals(
            "a dead band sits between the header and the canvas stage",
            headerBounds.bottom,
            stageBounds.top,
            0.5f,
        )
        assertTrue(
            "the header must sit above the canvas stage",
            headerBounds.top < stageBounds.top,
        )
    }

    /**
     * P3: the Save copy CTA stays a single-line pill even when the header's
     * text buttons starve it (the r62 device symptom: the label wrapped to
     * two lines). The replica carries the r63a wiring verbatim; the
     * long-locale-shaped labels guarantee the row overflows its 411dp, so a
     * wrapped pill would stand ~56-64dp tall against the single-line 40dp.
     */
    @Test
    fun saveCopyPillStaysSingleLineWhenTheHeaderTextButtonsStarveIt() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .systemBarsPadding(),
                    ) {
                        // The r63a EditActivity main-header row shape:
                        // weighted title + text buttons + the primary CTA.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "toadGALLERY Edit",
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {}) { Text("Settings and consent") }
                            TextButton(onClick = {}) { Text("Undo the previous edit") }
                            TextButton(onClick = {}) { Text("Redo the undone edit") }
                            Button(onClick = {}, modifier = Modifier.testTag(saveTag)) {
                                Text(
                                    text = "Save copy",
                                    // The r63a P3 wiring, verbatim.
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()

        val buttonNode = rule.onNodeWithTag(saveTag).fetchSemanticsNode()
        val buttonHeight = buttonNode.boundsInRoot.height
        val singleLinePillHeight = with(buttonNode.density) { 50.dp.toPx() }
        assertTrue(
            "the Save copy pill wrapped to two lines ($buttonHeight px); the CTA " +
                "label must stay single-line under label starvation",
            buttonHeight <= singleLinePillHeight,
        )
    }
}
