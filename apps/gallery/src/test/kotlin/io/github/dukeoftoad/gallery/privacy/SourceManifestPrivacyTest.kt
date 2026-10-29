package io.github.dukeoftoad.gallery.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0014 privacy suite --- layer 2a: source-manifest assertions.
 *
 * The Gradle gate (`privacyGate<Variant>`) audits the MERGED manifest (app +
 * every dependency contribution) at build time. This suite locks down the
 * SOURCE manifest itself, so a violation fails the developer's test run too,
 * not only CI packaging.
 */
class SourceManifestPrivacyTest {

    private val sourceManifest: File =
        File(PrivacyPolicy.moduleDir(), "src/main/AndroidManifest.xml")

    @Test
    fun `source manifest exists and parses`() {
        assertTrue(
            "Missing source manifest at ${sourceManifest.absolutePath}",
            sourceManifest.isFile,
        )
    }

    @Test
    fun `network deny-list never appears in the source manifest`() {
        val declared = PrivacyPolicy.declaredPermissions(sourceManifest)
        val hits = declared.filter { it in PrivacyPolicy.NETWORK_DENY_LIST }
        assertTrue(
            "toadGALLERY must never declare network permissions (ADR-0014); found: $hits",
            hits.isEmpty(),
        )

        // Raw-text belt-and-braces scan, ignoring tools:node="remove" markers.
        val raw = PrivacyPolicy.rawTextWithoutRemoveNodes(sourceManifest)
        PrivacyPolicy.NETWORK_DENY_LIST.forEach { permission ->
            assertFalse(
                "deny-list token '$permission' appears in the raw source manifest",
                raw.contains(permission),
            )
        }
    }

    @Test
    fun `declared permissions match the ADR-0014 allow-list exactly`() {
        // Phase 2 shell expects ZERO permissions. Phase 3 media access adds the
        // READ_MEDIA_* family here AND in expectedPermissionsByVariant (build
        // script) in the same change --- both layers must stay in agreement.
        val expected: Set<String> = emptySet()
        val declared = PrivacyPolicy.declaredPermissions(sourceManifest)
        assertEquals(
            "Manifest permissions drifted from the ADR-0014 policy allow-list. " +
                "Update both this test and expectedPermissionsByVariant via roadmap review.",
            expected,
            declared,
        )
    }

    @Test
    fun `cleartext traffic is never enabled`() {
        val value = PrivacyPolicy.usesCleartextTraffic(sourceManifest)
        assertFalse(
            "android:usesCleartextTraffic must not be true (ADR-0014)",
            value?.equals("true", ignoreCase = true) == true,
        )
    }
}
