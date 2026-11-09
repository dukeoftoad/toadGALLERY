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
 * The r63c panels' screenshot baselines (the approved r63c card): the
 * Adjust panel's Ente tune-rail and the promoted tool-header Reset.
 *
 * Subject choice, determinism-first (the EditChassisScreenshotTest harness
 * shape, reused EXACTLY): the rail and the header are PARAM-LIGHT (no
 * Activity, no ViewModel, no bitmap decode, no indeterminate animation) ---
 * each capture depends only on the pinned Robolectric SDK (34), the fixed
 * w411dp-h891dp-420dpi viewport and the r53 theme. The frame clock is
 * frozen before setContent and advanced one fixed 16ms frame; every
 * capture paints the theme's background first (the r56 rule: a
 * transparent-root capture would gate near-white ink against white).
 *
 * The rail captures pin BOTH a11y-critical states of the r63c contract:
 * the at-rest rail (rings rendering the committed values, the first chip
 * active with its slider showing) and the LIVE-drag state (the ONE draft
 * slot mid-drag: the active chip's ring and the pill+slider showing the
 * draft value --- the r32 live-preview contract the round must not move).
 * The header captures pin the promoted Reset in both states (enabled and
 * the spec's 38%-alpha disabled).
 *
 * The PNG names are pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property:
 * `com.toadgallery.editor.TuneRailScreenshotTest.<method>.png`
 * under src/test/resources/roborazzi (the plugin outputDir). The plugin's
 * recordRoborazziDebug task records them; verifyRoborazziDebug gates
 * against the committed baselines once they exist.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class TuneRailScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The r63c tune-rail at rest: the full [ADJUST_SLIDERS] suite as chips
     * (exposure active by default), each ring rendering its committed
     * value's conic arc, ONE slider below. Fixture: three non-zero params
     * so the rings show positive, negative and zero states in one capture.
     */
    @Test
    fun tuneRailRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        AdjustPanel(
                            committed = EditRecipe(
                                exposure = 15,
                                brightness = 40,
                                temperature = -30,
                            ),
                            draft = null,
                            onDraftChange = {},
                            exporting = false,
                            commit = {},
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
     * The r63c tune-rail mid-drag: the ONE draft slot live on the active
     * parameter (exposure, drafted to -35) --- the ring renders the draft
     * value, the pill and slider show it, the committed recipe is
     * untouched (the r32 live-preview contract on the new surface).
     */
    @Test
    fun tuneRailLiveDraftRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        AdjustPanel(
                            committed = EditRecipe(exposure = 10),
                            draft = Pair("exposure", -35),
                            onDraftChange = {},
                            exporting = false,
                            commit = {},
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
     * The r63c tool header with the promoted session Reset, ENABLED (the
     * committed recipe differs from the entry snapshot) --- the one
     * consistent shape across all ten tools: back, title, Reset, check.
     */
    @Test
    fun photoToolHeaderResetEnabledRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PhotoToolHeader(
                        title = "Adjust",
                        backA11y = "Back",
                        confirmA11y = "Done",
                        onBack = {},
                        onConfirm = {},
                        resetA11y = "Reset",
                        onReset = {},
                        resetEnabled = true,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    /**
     * The r63c tool header with the promoted session Reset DISABLED (the
     * committed recipe IS the entry snapshot; the section 4 spec's
     * 38%-alpha disabled language) --- the state the ten tools show on
     * entry before any edit lands.
     */
    @Test
    fun photoToolHeaderResetDisabledRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PhotoToolHeader(
                        title = "Adjust",
                        backA11y = "Back",
                        confirmA11y = "Done",
                        onBack = {},
                        onConfirm = {},
                        resetA11y = "Reset",
                        onReset = {},
                        resetEnabled = false,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }
}
