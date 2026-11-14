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
 * ProSlider screenshot fixtures pinning the C1 pill states: the QUIET row (neutral value: raised
 * pad, bare track, center notch) and the LIVE row (ember pill, notch-to-thumb fill), frozen clock.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ProSliderScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /** The QUIET row: bipolar slider at neutral --- raised-pad pill with reed text, bare track, center notch. */
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

    /** The LIVE row: value at +35 --- ember pill with on-ember text, fill from notch to thumb. */
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
