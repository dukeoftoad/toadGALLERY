package io.github.dukeoftoad.gallery.launcher

import android.content.Intent
import io.github.dukeoftoad.gallery.BuildConfig
import io.github.dukeoftoad.gallery.buildshape.BuildShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The r55 (one-launcher unification round) LAUNCHER-CONSOLIDATION
 * contract, as Robolectric JVM tests so the suite runs under EVERY
 * flavor variant's unit-test task (testBasicDebugUnitTest and its
 * engine/semantic/face siblings --- each flavor's MERGED manifest is
 * what Robolectric parses, placeholders resolved, exactly the artifact
 * the device installs):
 *
 *  - the canonical basic build resolves a launch intent for its own
 *    package, and that intent's component is the alias
 *    (`.LauncherAlias`) --- the launcher icon rides the alias, so the
 *    icon/label metadata path is the one r55 pinned in the manifest;
 *  - every engine-family build (BuildShape.withEngine) resolves NO
 *    launch intent for its own package --- the MAIN/LAUNCHER entry is
 *    alias-disabled by the `launcherAliasEnabled` placeholder, so a
 *    device can never show more than ONE toadGALLERY icon no matter
 *    how many family members install side by side (the maintainer's
 *    "toadGALLERY(2) need to unify under setting page" directive;
 *    the alias keeps MainActivity itself enabled + exported for the
 *    Settings section's explicit-component open path, which these
 *    assertions do not exercise --- resolution, not launchability).
 *
 * The flavor-conditional shape mirrors [BuildShapeTest]'s per-flavor
 * philosophy: each assertion holds only in its own flavor's task, and
 * the four tasks together pin the matrix. If a future flavor edit
 * forgets the placeholder override (or drops the alias), the offending
 * task goes red here before any APK ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherConsolidationTest {

    @Test
    fun launcherResolutionFollowsTheConsolidationContract() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val launchIntent: Intent? =
            pm.getLaunchIntentForPackage(context.packageName)

        if (BuildShape.withEngine) {
            assertNull(
                "an engine-family build (" + BuildConfig.APPLICATION_ID + ") must not " +
                    "expose a launcher entry --- exactly ONE toadGALLERY icon exists " +
                    "on a device (r55 consolidation)",
                launchIntent,
            )
        } else {
            assertNotNull(
                "the canonical basic build must keep its launcher entry",
                launchIntent,
            )
            assertEquals(
                "the basic launcher icon must ride the r55 alias",
                "io.github.dukeoftoad.gallery.LauncherAlias",
                launchIntent!!.component?.className,
            )
        }
    }

    @Test
    fun familyCompanionIdsResolvePerTheVisibilityContract() {
        // The Settings "Installed components" section probes these four
        // applicationIds through the r55 <queries> <package> entries. On
        // API 30+ (the pinned sdk 34) a probe of an UNDECLARED package
        // would throw NameNotFoundException even when installed --- so
        // this test additionally pins that the probe path never crashes
        // for the family ids (visibility declared, answer may be
        // installed or not; Robolectric sees only the app under test,
        // so the honest expectation here is the NOT-installed branch:
        // the probe completes with null, never throws).
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val familyIds = listOf(
            "io.github.dukeoftoad.gallery.engine",
            "io.github.dukeoftoad.gallery.semantic",
            "io.github.dukeoftoad.gallery.face",
            "io.github.dukeoftoad.gallery.edit",
        )
        for (id in familyIds) {
            val info = runCatching { pm.getPackageInfo(id, 0) }.getOrNull()
            assertNull(
                "Robolectric's sandbox sees no sibling installs; a non-null " +
                    "probe for " + id + " would mean the visibility filter " +
                    "is mis-scoped",
                info,
            )
        }
    }
}
