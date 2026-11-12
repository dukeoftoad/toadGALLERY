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
 * The r68 pro surfaces' screenshot fixtures (Phase 2, the photo pro
 * round: the curve deck, the parade, the zebra, the history strip and
 * the crop dial).
 *
 * Subject choice, determinism-first (the ProSliderScreenshotTest
 * harness shape, reused EXACTLY): every subject is PARAM-LIGHT (no
 * Activity, no ViewModel, no decode, no indeterminate animation) --- a
 * synthetic 256-step gradient histogram built through
 * [EditorHistogram.fromPixels] feeds the plot-dependent surfaces, a
 * synthetic clipped pixel field feeds the zebra builder, and each
 * capture depends only on the pinned Robolectric SDK (34), the fixed
 * w411dp-h891dp-420dpi viewport and the r67 theme. The frame clock is
 * frozen before setContent and advanced one fixed 16ms frame; every
 * capture paints the theme's background first (the r56 rule: a
 * transparent-root capture would gate near-white ink against white).
 *
 * What the fixtures pin, per surface:
 *  - the CURVE DECK, neutral (the identity diagonal over the faint
 *    histogram, the two endpoint handles, the channel chips, the Done
 *    affordance) and DRAGGED (a lifted midpoint on Master --- the baked
 *    monotone curve bowing above the diagonal, the middle handle riding
 *    it);
 *  - the PARADE (the three channel traces side-by-side over the
 *    gradient's own bins);
 *  - the ZEBRA (the stripe mask over a half-blown, half-crushed pixel
 *    field: red highlight stripes on the blown half, blue shadow
 *    stripes on the crushed half);
 *  - the HISTORY STRIP (four entries --- the Original chip, an
 *    adjustment, a curve, a straighten --- with the third marked
 *    current in ember);
 *  - the CROP DIAL, rotated (the ember arc burning +8 deg from the
 *    twelve o'clock detent, the mono readout at "+8"; the QUIET dial
 *    is already pinned by the chassis suite's crop-panel capture).
 *
 * NO BASELINES ARE RECORDED in this round: the record pass
 * (recordRoborazziDebug) runs as the round's deliberate re-record (the
 * design plan's R1 mitigation --- ONE pass per phase merge, never
 * scattered re-records). The PNG names are pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property:
 * `com.toadgallery.editor.ProSurfacesScreenshotTest.<method>.png`
 * under src/test/resources/roborazzi (the plugin outputDir).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ProSurfacesScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The synthetic 256-step gradient the plot-dependent surfaces read:
     * a diagonal ramp red-up/blue-down, one pixel per bin --- every
     * channel's histogram is a known single-count column set, so both
     * the deck's faint rendering and the parade's traces are pinned by
     * construction, not by luck.
     */
    private val gradientPixels: IntArray = IntArray(256) { v ->
        (0xFF shl 24) or (v shl 16) or (v shl 8) or (255 - v)
    }

    /**
     * The synthetic clipped field the zebra builder reads: a 16x16
     * field whose left quarter is blown to pure white, right quarter
     * crushed to pure black, and middle half a smooth ramp --- the mask
     * must stripe exactly the two clipped quarters and leave the ramp
     * bare.
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
     * The dragged deck: a lifted Master midpoint (0.5, 0.62) --- the
     * baked monotone curve bows above the identity diagonal and the
     * middle handle rides it.
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
     * The zebra: the stripe mask over the clipped field --- red stripes
     * on the blown quarter, blue on the crushed one, the ramp bare.
     * (The 16dp-tall host shows the stripe tiling at a legible pitch;
     * the mask itself is the same 16x16 field the builder read.)
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
     * The history strip: four entries --- the Original chip, an
     * exposure adjustment, a curve and a straighten --- with the CURVE
     * marked current in ember (the straighten behind it reads as
     * undone).
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
     * The rotated dial: the ember arc burning +8 deg from the twelve
     * o'clock detent, the mono readout at "+8" --- the C1 contract's
     * live state, at rest.
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
