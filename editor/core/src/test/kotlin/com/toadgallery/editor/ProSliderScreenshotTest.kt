package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The r67 ProSlider's screenshot fixtures (the Lilypad foundation round's
 * PR1 proof shots: the ember-when-live value pill against the neutral
 * track).
 *
 * Subject choice, determinism-first (the EditChassisScreenshotTest harness
 * shape, reused EXACTLY): the row is PARAM-LIGHT (no Activity, no
 * ViewModel, no bitmap decode, no indeterminate animation) --- each
 * capture depends only on the pinned Robolectric SDK (34), the fixed
 * w411dp-h891dp-420dpi viewport and the r67 theme. The frame clock is
 * frozen before setContent and advanced one fixed 16ms frame; every
 * capture paints the theme's background first (the r56 rule: a
 * transparent-root capture would gate near-white ink against white).
 *
 * The two captures pin the C1 contract's PILL STATES: the QUIET row (the
 * value at its neutral: the raised-pad pill with reed text, the bare
 * track, the center notch) and the LIVE row (the value off neutral: the
 * EMBER pill with its on-ember text, the fill drawn from the notch to the
 * thumb). What the fixtures do NOT pin this round: the drag-time ember
 * ring (a live-gesture state the frozen clock cannot express honestly).
 *
 * NO BASELINES ARE RECORDED in this round: CI does not run the editor's
 * screenshot tests, and the record pass (recordRoborazziDebug) needs a
 * local Android build. The PNG names are pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property:
 * `com.toadgallery.editor.ProSliderScreenshotTest.<method>.png` under
 * src/test/resources/roborazzi (the plugin outputDir) --- the round's
 * deliberate re-record pass (the design plan's R1 mitigation) records
 * them together with the re-themed chassis baselines.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ProSliderScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The QUIET row: a bipolar ProSlider resting at its neutral --- the
     * pill is the raised pad with reed text, the track is bare except the
     * center notch, and the thumb sits ON the notch.
     */
    @Test
    fun proSliderNeutralRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        ProSlider(
                            name = "Exposure",
                            label = "Exposure",
                            state = "Exposure: 0",
                            valueText = "0",
                            value = 0f,
                            valueRange = -100f..100f,
                            neutral = 0f,
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

    /**
     * The LIVE row: the same slider with the value drafted to +35 --- the
     * pill burns ember with its on-ember "+35", the fill runs from the
     * center notch to the thumb, and the thumb stands right of center.
     */
    @Test
    fun proSliderEmberPillRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        ProSlider(
                            name = "Exposure",
                            label = "Exposure",
                            state = "Exposure: 35",
                            valueText = "+35",
                            value = 35f,
                            valueRange = -100f..100f,
                            neutral = 0f,
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
