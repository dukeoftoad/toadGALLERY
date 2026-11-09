package com.toadgallery.gallery

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Backup-rules tests (audit finding A, vault backup exposure).
 *
 * These run on the JVM inside the ordinary `testDebugUnitTest` task --- no
 * emulator needed --- and pin the privacy contract introduced when the app
 * first holds real user data: the vault (filesDir/vault: PIN record, index,
 * sealed blobs, biometric.binding) must stay excluded from cloud backup and
 * device transfer, because its AndroidKeyStore key is non-exportable (a
 * restored ciphertext would be permanently unopenable) and its state must
 * not leave the device (ADR-0014). They are plain source-file assertions,
 * matching the enforcement model of [PrivacyPolicyTest].
 */
class BackupRulesTest {

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    @Test
    fun `manifest wires both backup rule sets and keeps allowBackup`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "The manifest must declare android:allowBackup (the rules exclude the vault; " +
                "preferences and diagnostics stay backable).",
            Regex("""android:allowBackup\s*=\s*"true"""").containsMatchIn(manifest),
        )
        assertTrue(
            "The manifest must wire android:fullBackupContent to @xml/backup_rules " +
                "(pre-API-31 cloud backup exclusion).",
            Regex("""android:fullBackupContent\s*=\s*"@xml/backup_rules"""").containsMatchIn(manifest),
        )
        assertTrue(
            "The manifest must wire android:dataExtractionRules to @xml/data_extraction_rules " +
                "(API-31+ cloud backup + device transfer exclusion).",
            Regex("""android:dataExtractionRules\s*=\s*"@xml/data_extraction_rules"""").containsMatchIn(manifest),
        )
    }

    @Test
    fun `pre API 31 rules exclude the vault from cloud backup`() {
        val rules = moduleFile("src/main/res/xml/backup_rules.xml").readText()
        assertTrue(
            "backup_rules.xml must contain a <full-backup-content> exclude of " +
                "domain=\"file\" path=\"vault\".",
            Regex(
                """<full-backup-content>.*<exclude\s+domain\s*=\s*"file"\s+path\s*=\s*"vault"\s*/>.*</full-backup-content>""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(rules),
        )
    }

    @Test
    fun `API 31 plus rules exclude the vault from both cloud backup and device transfer`() {
        val rules = moduleFile("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { scope ->
            assertTrue(
                "data_extraction_rules.xml must exclude the vault under <$scope> " +
                    "(domain=\"file\" path=\"vault\"); otherwise the sealed vault " +
                    "leaves the device without its key.",
                Regex(
                    """<$scope>\s*<exclude\s+domain\s*=\s*"file"\s+path\s*=\s*"vault"\s*/>"""
                ).containsMatchIn(rules),
            )
        }
    }
}
