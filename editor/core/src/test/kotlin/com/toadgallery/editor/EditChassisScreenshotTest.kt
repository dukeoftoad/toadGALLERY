package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the editor chrome and control kit, composables in isolation;
 * captures paint the theme background first (a transparent root gates near-white ink on white).
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
    fun photoTabBarSelectedToolRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PhotoTabBar(current = PhotoScreen.Adjust, enabled = true, onSelect = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun photoTabBarCanSwitchToolsAndAnnouncesSelection() {
        rule.setContent {
            val current = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(PhotoScreen.Adjust)
            }
            EditorTheme {
                PhotoTabBar(
                    current = current.value,
                    enabled = true,
                    onSelect = { current.value = it },
                )
            }
        }

        rule.onNodeWithText("Filters").performClick()
        rule.onNodeWithText("Filters").assertIsSelected()
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
                        // The session Reset rides the capture (header's one-shape contract).
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

    /**
     * Crop panel baseline: quiet straighten dial, neutral keystone rows,
     * geometry chips and aspect presets.
     */
    @Test
    fun cropPanelStraightenDialRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        CropPanel(
                            committed = EditRecipe(),
                            draft = null,
                            onDraftChange = {},
                            exporting = false,
                            previewBase = null,
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
     * Draw panel baseline in draw mode: brush sliders, color swatches and
     * chip rows (plain-state contract: ticks write chassis state, no draft).
     */
    @Test
    fun drawPanelBrushSlidersRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        DrawPanel(
                            committed = EditRecipe(),
                            draft = null,
                            onDraftChange = {},
                            exporting = false,
                            commit = {},
                            sweepPendingDrafts = {},
                            commitSelectedDrawLayer = {},
                            drawMode = true,
                            onDrawModeChange = {},
                            regionMode = false,
                            onRegionModeChange = {},
                            eraseMode = false,
                            onEraseModeChange = {},
                            brushColor = 0xFFFFFFFFL,
                            onBrushColorChange = {},
                            brushWidth = 25,
                            onBrushWidthChange = {},
                            brushOpacity = 80,
                            onBrushOpacityChange = {},
                            brushSmoothing = 30,
                            onBrushSmoothingChange = {},
                            selectedTextLayerId = null,
                            onSelectTextLayerChange = {},
                            selectedStickerLayerId = null,
                            onSelectStickerLayerChange = {},
                            selectedDrawLayerId = null,
                            onSelectDrawLayerChange = {},
                            selectedRegionLayerId = null,
                            onSelectRegionLayerChange = {},
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
     * Regions panel baseline in region mode: kind chips, strength slider and
     * region-mode/layer chip row (plain-state contract).
     */
    @Test
    fun regionsPanelStrengthSliderRecordsBaseline() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Column {
                        RegionsPanel(
                            committed = EditRecipe(),
                            draft = null,
                            onDraftChange = {},
                            exporting = false,
                            commit = {},
                            sweepPendingDrafts = {},
                            commitSelectedRegionLayer = {},
                            drawMode = false,
                            onDrawModeChange = {},
                            regionMode = true,
                            onRegionModeChange = {},
                            eraseMode = false,
                            onEraseModeChange = {},
                            regionKind = RegionKind.MOSAIC,
                            onRegionKindChange = {},
                            regionStrength = 60,
                            onRegionStrengthChange = {},
                            selectedTextLayerId = null,
                            onSelectTextLayerChange = {},
                            selectedStickerLayerId = null,
                            onSelectStickerLayerChange = {},
                            selectedDrawLayerId = null,
                            onSelectDrawLayerChange = {},
                            selectedRegionLayerId = null,
                            onSelectRegionLayerChange = {},
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
