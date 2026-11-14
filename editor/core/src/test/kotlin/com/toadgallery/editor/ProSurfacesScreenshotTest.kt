package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the pro surfaces (curve deck, parade, zebra, history strip, crop dial)
 * over synthetic fixtures; captures paint the theme background first (no transparent-root ghosts).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ProSurfacesScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Synthetic 256-step gradient (diagonal ramp, red up / blue down, one pixel per bin):
     * every histogram column is known, pinning the deck and parade by construction.
     */
    private val gradientPixels: IntArray = IntArray(256) { v ->
        (0xFF shl 24) or (v shl 16) or (v shl 8) or (255 - v)
    }

    /**
     * Synthetic 16x16 clipped field for the zebra: blown left quarter, crushed right
     * quarter, smooth middle ramp -- the mask must stripe the quarters, leave the ramp bare.
     */
    private val clippedPixels: IntArray = IntArray(256) { v ->
        when {
            v < 64 -> 0xFFFFFFFF.toInt()
            v >= 192 -> 0xFF000000.toInt()
            else -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** The neutral deck: the identity curve over the gradient. */
    @Test
    fun curveDeckNeutralRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        CurveDeck(
                            committed = EditRecipe(),
                            curveDraft = null,
                            onCurveDraftChange = {},
                            onFinish = {},
                            channel = CurveChannel.Master,
                            onChannelChange = {},
                            histogram = EditorHistogram.fromPixels(gradientPixels),
                            exporting = false,
                            onCollapse = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /**
     * Dragged deck: lifted Master midpoint (0.5, 0.62) -- the monotone curve bows
     * above the diagonal and the middle handle rides it.
     */
    @Test
    fun curveDeckDraggedRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        CurveDeck(
                            committed = EditRecipe(
                                toneCurves = ToneCurves(
                                    master = listOf(
                                        CurvePoint(0.0, 0.0),
                                        CurvePoint(0.5, 0.62),
                                        CurvePoint(1.0, 1.0),
                                    ),
                                ),
                            ),
                            curveDraft = null,
                            onCurveDraftChange = {},
                            onFinish = {},
                            channel = CurveChannel.Master,
                            onChannelChange = {},
                            histogram = EditorHistogram.fromPixels(gradientPixels),
                            exporting = false,
                            onCollapse = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /** The parade: the three channel traces over the gradient's bins. */
    @Test
    fun paradeChartRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        ParadeChart(
                            histogram = EditorHistogram.fromPixels(gradientPixels),
                            modifier = Modifier.height(120.dp),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /**
     * Zebra: red stripes on the blown quarter, blue on the crushed one, the ramp
     * bare (the 16dp host shows tiling at a legible pitch).
     */
    @Test
    fun zebraOverlayRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        val zebra = buildZebraOverlayImage(clippedPixels, 16, 16)
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        ZebraOverlay(
                            zebra = zebra,
                            modifier = Modifier.height(120.dp),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /**
     * History strip: four entries (Original, exposure, curve, straighten) with the
     * curve marked current in ember.
     */
    @Test
    fun historyStripRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        HistoryStrip(
                            changes = listOf(
                                null,
                                LilypadHistoryNames.Slider("exposure", 35),
                                LilypadHistoryNames.Curve(3),
                                LilypadHistoryNames.Straighten(4),
                            ),
                            currentIndex = 2,
                            looks = emptyMap(),
                            enabled = true,
                            onJump = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /**
     * Rotated dial: ember arc +8 deg from the twelve o'clock detent, mono
     * readout "+8" -- live state at rest.
     */
    @Test
    fun cropDialRotatedRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        LilypadDial(
                            name = "Straighten",
                            label = "Straighten",
                            state = "Straighten: 8",
                            valueText = "+8",
                            value = 8f,
                            valueRange = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
                            enabled = true,
                            onChange = {},
                            onFinish = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }
}
