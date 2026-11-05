package io.github.dukeoftoad.gallery.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dukeoftoad.gallery.ui.media.UnsupportedFileState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline capture for [UnsupportedFileState] (Phase 11,
 * docs/phase-11/roborazzi.md).
 *
 * Subject choice: [UnsupportedFileState] is the shared unsupported-file
 * state card of the screen-state system (issue #13) --- a plain public
 * composable taking one nullable [String] file name plus an optional tag.
 * No ViewModel, no Coil request, no LaunchedEffect, no network: the render
 * depends only on the pinned Robolectric SDK (34), the fixed
 * w411dp-h891dp-420dpi viewport and the default light [MaterialTheme] color
 * scheme, so the capture is deterministic by construction. The non-null
 * file-name branch is captured (it exercises strictly more layout than the
 * null branch: the middle-ellipsized file-name row) with a FIXED literal ---
 * fixed strings only, per the determinism rule.
 *
 * Determinism notes:
 * - The title and footer resolve from the app's REAL resources
 *   (`state_unsupported_title` / `state_shell_badge`), which is exactly why
 *   the module's testOptions set `unitTests.isIncludeAndroidResources =
 *   true`.
 * - The baseline PNG name is pinned by the module's
 *   `roborazzi.record.namingStrategy = fullyQualifiedName` property to the
 *   fully-qualified test method:
 *   `io.github.dukeoftoad.gallery.ui.screenshot.UnsupportedFileStateScreenshotTest.unsupportedFileStateRecordsBaseline.png`
 *   (the method name is deliberately space-free --- with the
 *   fullyQualifiedName strategy the name becomes the file name, and the
 *   repo's backtick sentence-style names would put spaces in baseline paths).
 *
 * A plain unit-test run RECORDS this PNG (Roborazzi's record-by-default
 * behavior; no verify flag is set); once baselines are committed to the
 * repo, the plugin's `verifyRoborazziBasicDebug` task (flavor-qualified,
 * docs/phase-11/roborazzi.md) compares fresh renders against them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UnsupportedFileStateScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun unsupportedFileStateRecordsBaseline() {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                UnsupportedFileState(fileName = "IMG_19700101_000000_HEIC.heic")
            }
        }
        rule.onRoot().captureRoboImage()
    }
}
