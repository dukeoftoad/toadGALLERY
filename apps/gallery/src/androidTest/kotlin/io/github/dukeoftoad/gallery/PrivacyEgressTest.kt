package io.github.dukeoftoad.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * Instrumented privacy suite (issue #20, Phase 2 exit criteria; extended for
 * Phase 3 media access in issue #25).
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
 * Scope note: "open a photo" joins the walk later in Phase 3 (issue #25 follow-
 * up); the emulator image has no media, so the granted-path Photos state is the
 * empty timeline, which still asserts `state_photos_empty_title`. The
 * `GrantPermissionRule` below pre-grants the Phase 3 media permission set so
 * the cold-launch assertion sees the granted path, not the permission rationale.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyEgressTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Phase 3 (issue #25): pre-grant the media permission set so the cold-launch
     * tab-walk sees the granted path (empty timeline on a media-less emulator)
     * instead of the permission rationale. The OS-level INTERNET refusal is
     * independent of this rule and stays asserted by tests 1 and 2.
     */
    @get:Rule
    val grantMediaPermission: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    )

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

        // Cold launch lands on the Photos empty state (media access pre-granted
        // above; the emulator image has no photos so the timeline is empty).
        // The granted path is async and, since the Paging 3 slice (issue #28),
        // spans the full initial refresh chain: permission callback ->
        // ViewModel Ready(pager) -> collectAsLazyPagingItems -> initial Paging
        // refresh -> window-0 MediaStore query -> empty terminal page ->
        // load-state settle -> empty state composes. On a cold CI emulator
        // (KVM, freshly-booted, cold MediaProvider) that chain was observed to
        // exceed a 10 s budget (PR #36 CI runs #13/#15) while byte-identical
        // walked-path code passed elsewhere (PR #37 runs #14/#16): budget
        // variance, not a code regression. Hence a 30 s budget here.
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(activity.getString(R.string.state_photos_empty_title))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Walk the remaining tabs, then return to Photos.
        listOf(R.string.nav_albums, R.string.nav_search, R.string.nav_settings).forEach { label ->
            composeRule.onNodeWithText(activity.getString(label)).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(activity.getString(R.string.nav_photos)).performClick()
        composeRule.waitForIdle()

        // Returning to Photos re-enters the same refresh chain (re-entry
        // republishes Loading -> Ready with a fresh Pager generation); wait
        // again with the same cold-start budget as above.
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(activity.getString(R.string.state_photos_empty_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}

