package io.github.dukeoftoad.gallery.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline capture for [LoadingState] (Phase 11,
 * docs/phase-11/roborazzi.md).
 *
 * Subject choice: [LoadingState] is the shared loading state card of the
 * screen-state system (issue #13) --- the most param-light public composable
 * in ui/media (one optional tag, nothing else). No ViewModel, no Coil
 * request, no LaunchedEffect, no network: the render depends only on the
 * pinned Robolectric SDK (34), the fixed w411dp-h891dp-420dpi viewport and
 * the default light [MaterialTheme] color scheme.
 *
 * Determinism notes (the one non-trivial part of the three captures):
 * [LoadingState] centers a `CircularProgressIndicator`, and an INDETERMINATE
 * spinner rotates on the frame clock --- an unclocked capture would record a
 * different rotation angle per run and make a future verify gate flaky by
 * construction. The Compose test clock is therefore frozen BEFORE
 * `setContent` (`autoAdvance = false`) and advanced by one fixed 16ms frame,
 * so every capture lands on the same frame of the rotation cycle and the
 * recorded pixels are stable.
 *
 * The baseline PNG name is pinned by the module's
 * `roborazzi.record.namingStrategy = fullyQualifiedName` property to the
 * fully-qualified test method:
 * `io.github.dukeoftoad.gallery.ui.screenshot.LoadingStateScreenshotTest.loadingStateRecordsBaseline.png`
 * (the method name is deliberately space-free --- with the
 * fullyQualifiedName strategy the name becomes the file name, and the repo's
 * backtick sentence-style names would put spaces in baseline paths).
 *
 * The PNG is recorded by the plugin's `recordRoborazziBasicDebug` task
 * (r47, toadFILES parity: since the gradle plugin is applied, a PLAIN
 * unit-test run SKIPS the captures --- roborazzi.test.record is false
 * unless a roborazzi task is in the graph); once baselines are committed
 * to the repo under src/test/resources/roborazzi, the plugin's
 * `verifyRoborazziBasicDebug` task (flavor-qualified,
 * docs/phase-11/roborazzi.md) compares fresh renders against them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class LoadingStateScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loadingStateRecordsBaseline() {
        // Freeze the frame clock before composition: the indeterminate
        // spinner's rotation angle then depends only on the fixed advance
        // below, never on wall-clock time between operations.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                LoadingState()
            }
        }
        // One frame: the spinner renders at a fixed point of its rotation
        // cycle, identical on every run and every machine.
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()
    }
}
