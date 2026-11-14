package com.toadgallery.gallery.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r77.10 Settings-hub chrome suite (source-scan, the
 * `AccessibilityWiringTest` convention): pins the three hub chrome fixes
 * against the prototype's settings markup so a future edit cannot silently
 * revert them ("CI compiles what's there --- it cannot test for absence").
 *
 * The three pinned facts, each traceable to the prototype
 * (`docs/toadgallery_redesign_prototype.html`, `renderSettingsHub`):
 *
 *  1. The section headers are the TWO-EDGE eyebrow rows: an `11sp`
 *     ExtraBold primary label on the leading edge and the `text-[10px]`
 *     trailing hint on the far edge --- "Look & grid" on Appearance,
 *     "Local NPU" on On-Device AI, "Zero tracking" on Privacy.
 *  2. The hub header's version badge is the emphasized `primary-container`
 *     `text-[11px]` pill (the sub-page status pills stay on the muted
 *     default, so the pill's API grew a size/colors surface, not a global
 *     restyle).
 *  3. The Storage & Data Management section carries the LIVE total twice:
 *     the bold "N MB" eyebrow hint (only once a measurement RESOLVED ---
 *     never a lying zero) and the compact segmented preview bar above the
 *     manager row, on the `bg-surface-highest` track with the same
 *     five-key color vocabulary the Storage Manager sub-page renders.
 *
 * A failing assertion here means the hub chrome drifted --- restore the
 * wiring or extend this suite with the new shape; do not relax it.
 */
class SettingsHubChromeTest {

    private val SETTINGS_SRC =
        "src/main/kotlin/com/toadgallery/gallery/ui/settings/SettingsScreen.kt"

    private val COMPONENTS_SRC =
        "src/main/kotlin/com/toadgallery/gallery/ui/components/ToadComponents.kt"

    private val STRINGS_SRC =
        "src/main/res/values/strings.xml"

    private fun sourceFile(moduleRelative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative),
            File(cwd, "gallery/core/$moduleRelative"),
            File(cwd.parentFile, "gallery/core/$moduleRelative"),
            File(cwd.parentFile?.parentFile, "gallery/core/$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate gallery/core/$moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    // ---- 1. The two-edge section headers ----

    @Test
    fun `section headers grew the trailing hint slot`() {
        val src = sourceFile(SETTINGS_SRC).readText()
        assertTrue(
            "SettingsSectionHeader must take the optional trailing hint " +
                "(the prototype's justify-between eyebrow row)",
            src.contains("private fun SettingsSectionHeader(\n    title: String,\n    hint: String? = null"),
        )
        assertTrue(
            "the eyebrow must keep its heading() semantics (the TalkBack anchor)",
            src.contains(".weight(1f)\n                .semantics { heading() }"),
        )
    }

    @Test
    fun `hub appearance ai and privacy headers wire the prototype hints`() {
        val src = sourceFile(SETTINGS_SRC).readText()
        // The three hub call sites pass the hint resources; the storage
        // header wires the live total and About passes none (the
        // prototype's About & Diagnostics eyebrow carries no hint).
        assertTrue(
            "Appearance header must hint \"Look & grid\"",
            src.contains("hint = stringResource(R.string.settings_section_hint_appearance)"),
        )
        assertTrue(
            "AI header must hint \"Local NPU\"",
            src.contains("hint = stringResource(R.string.settings_section_hint_ai)"),
        )
        assertTrue(
            "Privacy header must hint \"Zero tracking\"",
            src.contains("hint = stringResource(R.string.settings_section_hint_access)"),
        )
    }

    @Test
    fun `hint strings exist with the prototype wording`() {
        val strings = sourceFile(STRINGS_SRC).readText()
        assertTrue(
            "\"Look & grid\" hint string missing",
            strings.contains(
                "<string name=\"settings_section_hint_appearance\">Look &amp; grid</string>",
            ),
        )
        assertTrue(
            "\"Local NPU\" hint string missing",
            strings.contains(
                "<string name=\"settings_section_hint_ai\">Local NPU</string>",
            ),
        )
        assertTrue(
            "\"Zero tracking\" hint string missing",
            strings.contains(
                "<string name=\"settings_section_hint_access\">Zero tracking</string>",
            ),
        )
    }

    // ---- 2. The emphasized hub version badge ----

    @Test
    fun `hub version badge is the primary-container 11sp pill`() {
        val src = sourceFile(SETTINGS_SRC).readText()
        assertTrue(
            "the hub header pill must render in the emphasized container colors",
            src.contains("background = MaterialTheme.colorScheme.primaryContainer") &&
                src.contains("textColor = MaterialTheme.colorScheme.onPrimaryContainer"),
        )
        assertTrue(
            "the hub header pill must be the prototype's text-[11px] size",
            src.contains("fontSize = 11.sp"),
        )
        // The pill's default size stays 10sp so every OTHER call site
        // (sub-page status pills) keeps its pre-r77.10 render.
        val components = sourceFile(COMPONENTS_SRC).readText()
        assertTrue(
            "ToadStatusPill default must stay 10.sp (only the hub opts up to 11sp)",
            components.contains("fontSize: TextUnit = 10.sp"),
        )
    }

    // ---- 3. The live storage hint + the compact preview bar ----

    @Test
    fun `storage hint is the live total hidden until a measurement resolved`() {
        val src = sourceFile(SETTINGS_SRC).readText()
        assertTrue(
            "the storage hint must gate on the resolved read (never a lying zero)",
            src.contains("!hubStorageState.measuring && hubStorageTotalBytes > 0L"),
        )
        assertTrue(
            "the storage hint must be the formatted live total",
            src.contains("DataSizeFormats.formatBytes(hubStorageTotalBytes)"),
        )
        assertTrue(
            "the storage hint must use the emphasized (bold muted) tone",
            src.contains("hintEmphasized = true"),
        )
    }

    @Test
    fun `hub storage card hosts the compact segmented preview bar`() {
        val src = sourceFile(SETTINGS_SRC).readText()
        assertTrue(
            "the hub storage card must render ToadStorageMiniBar above the manager row",
            src.contains("ToadStorageMiniBar(\n                    entries = hubStorageState.entries,") &&
                src.contains("measuring = hubStorageState.measuring,"),
        )
        // The bar's chrome: the 10dp rounded track on bgSurfaceHighest
        // with the 2dp inset (the markup's h-2.5 / p-0.5), and the honest
        // measuring placeholder instead of a fake fill.
        assertTrue(
            "the preview bar track must be the 10dp bgSurfaceHighest pill",
            src.contains(".height(10.dp)") &&
                src.contains(".background(palette.bgSurfaceHighest)") &&
                src.contains(".padding(2.dp)"),
        )
        assertTrue(
            "the preview bar must render the measuring placeholder, not a fake fill",
            src.contains("if (segments.isEmpty())"),
        )
        // The five-key color vocabulary, in the sub-page's order.
        listOf(
            "STORAGE_KEY_CATALOGUE to Color(0xFF38BDF8)",
            "STORAGE_KEY_MODELS to MaterialTheme.colorScheme.primary",
            "STORAGE_KEY_ML_INDEX to Color(0xFFA78BFA)",
            "STORAGE_KEY_VAULT to Color(0xFF34D399)",
            "STORAGE_KEY_CACHE to Color(0xFFFBBF24)",
        ).forEach { fragment ->
            assertTrue(
                "the preview bar must keep the sub-page's five-key vocabulary ($fragment)",
                src.contains(fragment),
            )
        }
    }
}
