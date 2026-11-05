package io.github.dukeoftoad.gallery.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.media.ErrorState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline capture for [ErrorState] (Phase 11,
 * docs/phase-11/roborazzi.md).
 *
 * Subject choice: [ErrorState] is the shared error state card of the
 * screen-state system (issue #13) --- a plain public composable whose only
 * inputs are three string-resource ids, a no-op retry lambda and an optional
 * tag. No ViewModel, no Coil request, no LaunchedEffect, no network: the
 * rendered pixels depend only on the pinned Robolectric SDK (34), the fixed
 * w411dp-h891dp-420dpi viewport and the default light [MaterialTheme] color
 * scheme, so the capture is deterministic by construction.
 *
 * Determinism notes:
 * - The copy resolves from the app's REAL resources
 *   (`state_photos_error_title` / `state_photos_error_body` /
 *   `state_action_retry`), which is exactly why the module's testOptions set
 *   `unitTests.isIncludeAndroidResources = true`.
 * - The baseline PNG name is pinned by the module's
 *   `roborazzi.record.namingStrategy = fullyQualifiedName` property to the
 *   fully-qualified test method:
 *   `io.github.dukeoftoad.gallery.ui.screenshot.ErrorStateScreenshotTest.errorStateRecordsBaseline.png`
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
class ErrorStateScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun errorStateRecordsBaseline() {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ErrorState(
                    titleRes = R.string.state_photos_error_title,
                    bodyRes = R.string.state_photos_error_body,
                    actionRes = R.string.state_action_retry,
                    onRetry = {},
                )
            }
        }
        rule.onRoot().captureRoboImage()
    }
}
