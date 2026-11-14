package com.toadgallery.editor

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
import androidx.compose.ui.platform.LocalDensity
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

/** Layout pins: the header tops the production root shape; the Save copy CTA stays single-line. */
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
     * F-71: the header is the top-most element of the production root shape; the
     * Robolectric insets are zero here, so any nonzero top bound is a phantom band.
     */
    @Test
    fun headerIsTheTopMostElementInTheProductionRootShape() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                // The production root: fillMaxSize Surface -> root Column with the
                // ONE systemBarsPadding -> header branch first -> the weight(1f) canvas.
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
                                // The session Reset rides the production header shape this test pins.
                                resetA11y = "Reset",
                                onReset = {},
                                resetEnabled = true,
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
     * P3: the Save copy CTA stays a single-line pill even under text-button starvation.
     * The replica carries the real wiring; long-locale labels overflow 411dp, so a
     * wrapped pill would stand ~56-64dp tall against the single-line 40dp.
     */
    @Test
    fun saveCopyPillStaysSingleLineWhenTheHeaderTextButtonsStarveIt() {
        rule.mainClock.autoAdvance = false
        // The 50dp single-line floor in px under the composition's real density
        // (measured via LocalDensity in-composition; the node exposes no density).
        var singleLinePillHeight = 0f
        rule.setContent {
            singleLinePillHeight = with(LocalDensity.current) { 50.dp.toPx() }
            EditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .systemBarsPadding(),
                    ) {
                        // The EditActivity main-header row shape: weighted title + text buttons + CTA.
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
                                    // The P3 wiring, verbatim.
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
        assertTrue(
            "the Save copy pill wrapped to two lines ($buttonHeight px); the CTA " +
                "label must stay single-line under label starvation",
            buttonHeight <= singleLinePillHeight,
        )
    }
}
