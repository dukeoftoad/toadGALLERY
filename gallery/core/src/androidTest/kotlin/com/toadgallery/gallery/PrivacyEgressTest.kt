package com.toadgallery.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.toadgallery.gallery.ml.engine.HttpModelDownloader
import com.toadgallery.gallery.ml.engine.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented privacy suite (issue #20, Phase 2 exit criteria; extended for
 * Phase 3 media access in issue #25; extended for the r22 ADR-0020
 * model-delivery round).
 *
 * RUN MODE (ADR-0016, 2026-10-30): this suite is a MANUAL RELEASE GATE ---
 * run it on a physical device before every tagged release:
 *   ./gradlew :apps:gallery:connectedDebugAndroidTest
 * CI no longer runs emulator jobs (no KVM on hosted runners made the CI
 * emulator slow and probabilistically red before any test executed).
 *
 * r22 POSTURE CHANGE (ADR-0020, on record): the app now HOLDS the INTERNET
 * permission for exactly one purpose --- fetching the pinned model assets
 * from the single allowlisted host `models.ente.com` (MODEL_HOST). The
 * pre-r22 OS-level guarantee ("the app cannot open any socket") is
 * deliberately superseded for that grant; what the suites prove instead is
 * the r22 boundary:
 *
 *  1. `internetPermission_isGrantedForTheAdr0020ModelFetch` --- the grant
 *     landed (a missing INTERNET would break every model fetch); the
 *     permission-set pin and the allowlist protocol live in the JVM suite.
 *  2. `nonAllowlistedModelTargets_areRejectedWithoutConnection` --- the
 *     downloader's structural guard refuses crafted traversal /
 *     absolute-URL / query / fragment targets BEFORE any connection can
 *     exist; the refusal is the guard's own message, not a network error.
 *     This replaces the pre-r22 `socketEgress_isBlockedByOs` probe (the
 *     OS no longer blocks sockets --- that was the point of the grant) and
 *     implements ADR-0020 §6's "no connection outside the allowlisted
 *     host" assertion at the only place it is now enforceable: the URL
 *     construction the app actually performs.
 *  3. `coreFlows_coldLaunchAndTabWalk_noCrash` --- walks the core shell flows
 *     (cold launch, all four tabs) so the posture is exercised against real
 *     app startup, not a permission check in isolation.
 *
 * ## r22 DEVICE CHECKLIST (the maintainer's dogfood run; the Settings
 * "AI engine (r22 dogfood)" section exists exactly for this)
 *
 *  1. Build WITH the engine property so `libtoad_ml_jni.so` is packaged;
 *     install on a device with a few JPEGs (some with people).
 *  2. Settings → AI engine section: the library line must read "present";
 *     with a clean install the FACE state reads "not downloaded" and both
 *     asset rows (yolov5s_face…, mobilefacenet…) read "missing".
 *  3. "Download face models (~36 MiB)": expect the asset rows to flip to
 *     "installed" one after the other (live per-asset updates), the state
 *     line to reach "Face models: ready", and the note "Face models
 *     installed and verified." A second tap performs NO new fetch
 *     (idempotence; watch the host's access log if in doubt).
 *  4. "Run engine self-test": expect "<jpeg name>: N faces in X ms —
 *     engine OK"; any N (including 0 on a face-free photo) is a pass ---
 *     the assertion is that one real JPEG went through the JNI engine.
 *  5. "Delete face models": rows flip back to "missing", the state line to
 *     "not downloaded", and `filesDir/models` holds no *.onnx files.
 *  6. Re-download and re-run the self-test (provisioning + re-provision
 *     after invalidation).
 *  7. Plain (non-engine) build as a control: the library line reads "not
 *     in this build (build with the engine flag)" and the self-test
 *     reports exactly that --- never a crash, never a fake result.
 *
 * Scope note: "open a photo" joins the walk later in Phase 3 (issue #25 follow-
 * up); the emulator image has no media, so the granted-path Photos state is the
 * empty timeline, which still asserts `state_photos_empty_title`. The
 * `GrantPermissionRule` below pre-grants the Phase 3 media permission set so
 * the cold-launch assertion sees the granted path, not the permission rationale.
 *
 * State test-tags (issue #38, finding 7): every user-visible Photos state
 * is wrapped with a stable `Modifier.testTag` in `PhotosScreen` ---
 * `photos_state_permission` / `photos_state_error` / `photos_state_empty` /
 * `photos_state_grid` --- so "reached the state" is provable independently
 * of the user-visible copy. The tab walk keeps its text-based `waitUntil`s
 * (behavioral proof: the copy IS what the user reads) and layers a
 * tag-based wait on top (state proof): the granted path must surface
 * `photos_state_empty` on this media-less image (or `photos_state_grid`
 * whenever the image grows media).
 */
@RunWith(AndroidJUnit4::class)
class PrivacyEgressTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Phase 3 (issue #25): pre-grant the media permission set so the cold-launch
     * tab-walk sees the granted path (empty timeline on a media-less emulator)
     * instead of the permission rationale. INTERNET is a normal permission ---
     * granted at install, no runtime dialog --- so it needs no rule here and is
     * asserted directly below.
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

    /**
     * r22 (ADR-0020 §1): the app HOLDS INTERNET --- the model-fetch grant.
     * This is the deliberate, on-record supersession of the pre-r22
     * assertion (`PERMISSION_DENIED`): a missing grant would now break the
     * ADR-0020 delivery path, so the suite pins the grant's PRESENCE. The
     * boundary that keeps the grant honest is not the OS (it no longer is)
     * but the single-host allowlist + URL guard, pinned by the JVM
     * `PrivacyPolicyTest` and by the rejection test below.
     */
    @Test
    fun internetPermission_isGrantedForTheAdr0020ModelFetch() {
        val granted = targetContext.packageManager.checkPermission(
            Manifest.permission.INTERNET,
            targetContext.packageName,
        )
        assertEquals(
            "The r22 ADR-0020 model-fetch grant must be present: the app declares INTERNET " +
                "solely to download the pinned model assets from the allowlisted host " +
                "(docs/integration-decisions.md, ADR-0020 §1; see PrivacyPolicyTest for the " +
                "permission-set pin and the allowlist protocol).",
            PackageManager.PERMISSION_GRANTED,
            granted,
        )
    }

    /**
     * r22 (ADR-0020 §6): no connection outside the allowlisted host. The
     * enforcement point is the downloader's structural guard: every fetch
     * URL is built as `https://models.ente.com/<guard-validated name>`, so
     * a crafted target is refused BEFORE any socket can exist. Each case
     * below uses the REAL production downloader (argument-less
     * construction, exactly like `ToadGalleryApplication` wires it) and
     * asserts the refusal is the GUARD's own message --- not a DNS,
     * connect or HTTP failure, which would mean a connection was
     * attempted. This replaces the pre-r22 raw-socket probe: the OS no
     * longer blocks sockets (that was the point of the grant), and the
     * app-side allowlist is the boundary that remains.
     */
    @Test
    fun nonAllowlistedModelTargets_areRejectedWithoutConnection() {
        val downloader = HttpModelDownloader()
        val hostile = mapOf(
            "https://evil.example.com/a.onnx" to "absolute-URL injection",
            "http://evil.example.com/a.onnx" to "cleartext absolute-URL injection",
            "//evil.example.com/a.onnx" to "protocol-relative injection",
            "../../other-app/files/x.onnx" to "parent traversal",
            "a\\b.onnx" to "backslash traversal",
            "a.onnx?host=evil.example.com" to "query smuggling",
            "a.onnx#evil.example.com" to "fragment smuggling",
        )
        hostile.forEach { (fileName, why) ->
            try {
                downloader.open(
                    ModelManifest.Model(fileName, ModelManifest.Capability.FACE, 1L, "0".repeat(64)),
                )
                fail("The production downloader accepted a $why target: \"$fileName\"")
            } catch (expected: IOException) {
                assertTrue(
                    "The refusal must be the structural guard (pre-connection), not a network " +
                        "error --- got: \"${expected.message}\"",
                    expected.message!!.contains("refusing unsafe model file name"),
                )
            }
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
        // State proof first, decoupled from copy (issue #38, finding 7): the
        // granted path must reach a Ready sub-state --- the empty-library
        // state on this media-less image, or the grid once media exists.
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("photos_state_empty").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("photos_state_grid").fetchSemanticsNodes().isNotEmpty()
        }
        // Behavioral proof on top: the empty state must show the user-facing
        // copy, not just any composed node under the tag.
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

        // Returning to Photos re-collects the SAME stream: since the issue
        // #38 idempotence fix, `onPermissionGranted()` early-returns while
        // the state is Ready, so re-entry no longer republishes Loading ->
        // Ready with a fresh Pager generation --- the `cachedIn` flow is
        // re-collected and the settled empty state returns from cache. The
        // same state-then-copy waits stay as the regression tripwire (the tag
        // wait also tolerates a hypothetical media-seeded image, which would
        // surface the grid instead of the empty state).
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("photos_state_empty").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("photos_state_grid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(activity.getString(R.string.state_photos_empty_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
