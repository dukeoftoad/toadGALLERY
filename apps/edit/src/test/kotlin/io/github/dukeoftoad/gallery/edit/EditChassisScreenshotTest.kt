package io.github.dukeoftoad.gallery.edit

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
 * The edit app's screenshot baselines (r56, docs/phase-11/roborazzi.md) ---
 * the photo editor's CHROME and the r56 control kit, the pieces the r56
 * extraction made composable in isolation. This is the module's first
 * visual coverage: until now the edit app shipped with zero pixel gates
 * (the gallery has had the Roborazzi pair since r47), which is exactly how
 * a chassis round could change every screen with nothing catching a
 * regression.
 *
 * Subject choice, determinism-first: the tab bar, the tool header and the
 * slider row are PARAM-LIGHT (no Activity, no ViewModel, no bitmap decode,
 * no indeterminate animation), so each capture depends only on the pinned
 * Robolectric SDK (34), the fixed w411dp-h891dp-420dpi viewport and the
 * r53 theme. The frame clock is frozen before setContent and advanced one
 * fixed 16ms frame (the gallery suite's convention) so the captures are
 * stable by construction. Every capture paints the theme's background
 * first --- the app renders the editor on the fixed dark surface, and a
 * transparent-root capture would gate near-white ink against white
 * (invisible ghosts), not the contrast the user actually sees.
 *
 * The PNG names are pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property:
 * `io.github.dukeoftoad.gallery.edit.EditChassisScreenshotTest.<method>.png`
 * under src/test/resources/roborazzi (the plugin outputDir). The plugin's
 * recordRoborazziDebug task records them; verifyRoborazziDebug gates
 * against the committed baselines once they exist.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class EditChassisScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun photoTabBarRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PhotoTabBar(current = PhotoScreen.Main, enabled = true, onSelect = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun photoToolHeaderRecordsBaseline() {
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
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun labeledSliderRowsRecordBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        LabeledSlider(
                            name = "Brightness",
                            label = "Brightness: 12",
                            valueText = "12",
                            value = 12f,
                            valueRange = -100f..100f,
                            enabled = true,
                            onChange = {},
                            onFinish = {},
                            resetValue = 0,
                        )
                        LabeledSlider(
                            name = "Contrast",
                            label = "Contrast: -8",
                            valueText = "-8",
                            value = -8f,
                            valueRange = -100f..100f,
                            enabled = true,
                            onChange = {},
                            onFinish = null,
                            resetValue = 0,
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
