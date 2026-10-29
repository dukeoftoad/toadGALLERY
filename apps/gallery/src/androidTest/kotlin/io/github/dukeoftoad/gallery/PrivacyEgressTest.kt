package io.github.dukeoftoad.gallery

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * Instrumented privacy suite (issue #20, Phase 2 exit criteria).
 *
 * Proves the offline-by-construction guarantee at runtime, on a real Android
 * system image (CI emulator, API 34):
 *
 *  1. `internetPermission_isNotGrantedAtRuntime` --- the app uid holds no
 *     INTERNET permission. Without it the OS itself refuses every socket the
 *     app could open: no code path, dependency, or future AI feature can phone
 *     home. This is strictly stronger than any proxy-based egress sniffing.
 *  2. `socketEgress_isBlockedByOs` --- actively tries to open a TCP connection
 *     to a public resolver (raw IP, no DNS dependency) and asserts the OS
 *     blocks it. If any future change silently grants network access, this
 *     test turns red.
 *  3. `coreFlows_coldLaunchAndTabWalk_noCrash` --- walks the core shell flows
 *     (cold launch, all four tabs) so the guarantee is exercised against real
 *     app startup, not a permission check in isolation.
 *
 * Scope note (issue #20): "open a photo" joins the walk in Phase 3 when media
 * browsing lands; the shell has no photo content to open yet. The walk is
 * intentionally extended rather than replaced at that point.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyEgressTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun internetPermission_isNotGrantedAtRuntime() {
        val granted = targetContext.packageManager.checkPermission(
            Manifest.permission.INTERNET,
            targetContext.packageName,
        )
        assertEquals(
            "Core toadGALLERY must never hold INTERNET; without it the OS blocks all " +
                "egress (docs/phase-2/privacy-gate.md, issue #20).",
            PackageManager.PERMISSION_DENIED,
            granted,
        )
    }

    @Test
    fun socketEgress_isBlockedByOs() {
        val socket = Socket()
        try {
            // Raw IP literal (no DNS lookup), public resolver port. If the app held
            // INTERNET this would connect or time out; without INTERNET the OS must
            // refuse the socket outright.
            socket.connect(InetSocketAddress("1.1.1.1", 53), 3_000)
            fail(
                "Network egress was NOT blocked: connected to 1.1.1.1:53 without " +
                    "INTERNET permission --- the offline guarantee is broken."
            )
        } catch (expected: SecurityException) {
            // Documented Android refusal path for missing INTERNET permission.
        } catch (expected: SocketException) {
            // Kernel-level EACCES variant of the same block.
        } finally {
            runCatching { socket.close() }
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coreFlows_coldLaunchAndTabWalk_noCrash() {
        val activity = composeRule.activity

        // Cold launch lands on the Photos empty state.
        composeRule.onNodeWithText(activity.getString(R.string.state_photos_empty_title))
            .assertExists()

        // Walk the remaining tabs, then return to Photos.
        listOf(R.string.nav_albums, R.string.nav_search, R.string.nav_settings).forEach { label ->
            composeRule.onNodeWithText(activity.getString(label)).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(activity.getString(R.string.nav_photos)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(activity.getString(R.string.state_photos_empty_title))
            .assertExists()
    }
}
