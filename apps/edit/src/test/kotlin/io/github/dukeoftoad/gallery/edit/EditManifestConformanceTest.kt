package io.github.dukeoftoad.gallery.edit

import io.github.dukeoftoad.gallery.edit.contract.EditContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The prototype's contract-conformance suite (Issue #14): parses the EDIT
 * APP's own source manifest on the JVM and pins the contract surface the
 * gallery relies on. CI cannot install two APKs on an emulator (ADR-0016),
 * so the round-trip is proven in two layers: this manifest-level
 * conformance suite (the editor's side of the contract) plus the contract
 * module's negotiation/request/result tests (the shared logic), with the
 * on-device dogfood checklist carrying the physical round trip.
 */
class EditManifestConformanceTest {

    /**
     * Unit-test working dir is the module dir (apps/edit) under Gradle; the
     * local r41 harness pins the WORKTREE ROOT as the cwd --- be tolerant
     * of both (the repo-root candidate the round's harness runs from).
     */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, relative), // the Gradle module cwd (apps/edit)
            File(cwd, "apps/edit/$relative"), // the repo/worktree-root cwd (the local harness)
            File(cwd.parentFile, "apps/edit/$relative"), // any direct child of the root
        ).firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    private fun manifestText(): String = moduleFile("src/main/AndroidManifest.xml").readText()

    private fun manifest() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(moduleFile("src/main/AndroidManifest.xml"))

    // ---- The launch surface (spec section 1) ----

    @Test
    fun `the edit activity is exported and filtered on the contract action`() {
        val doc = manifest()
        val activities = doc.getElementsByTagName("activity")
        var found = false
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as org.w3c.dom.Element
            val name = activity.attributes.getNamedItem("android:name").nodeValue
            if (name == ".EditActivity" || name.endsWith(".EditActivity")) {
                found = true
                assertEquals(
                    "the contract launch activity MUST be exported",
                    "true",
                    activity.attributes.getNamedItem("android:exported").nodeValue,
                )
                // The filter's action/category live in the CHILD ELEMENTS'
                // attributes --- textContent is whitespace-only here.
                val filters = activity.getElementsByTagName("intent-filter")
                var hasAction = false
                var hasDefault = false
                for (j in 0 until filters.length) {
                    val children = filters.item(j).childNodes
                    for (k in 0 until children.length) {
                        val node = children.item(k)
                        if (node !is org.w3c.dom.Element) continue
                        when (node.nodeName) {
                            "action" ->
                                if (node.getAttribute("android:name") == EditContract.ACTION_EDIT) {
                                    hasAction = true
                                }
                            "category" ->
                                if (node.getAttribute("android:name") ==
                                    "android.intent.category.DEFAULT"
                                ) {
                                    hasDefault = true
                                }
                        }
                    }
                }
                assertTrue(
                    "the activity must carry the contract intent-filter action",
                    hasAction,
                )
                assertTrue(
                    "the intent-filter must keep the DEFAULT category",
                    hasDefault,
                )
            }
        }
        assertTrue("no EditActivity found in the manifest", found)
    }

    @Test
    fun `the activity supports exactly the formats the prototype decodes`() {
        val text = manifestText()
        for (mime in listOf("image/jpeg", "image/png", "image/webp", "image/heic")) {
            assertTrue("missing mimeType $mime", text.contains(mime))
        }
    }

    @Test
    fun `the contract version meta-data declares v1`() {
        val doc = manifest()
        val metas = doc.getElementsByTagName("meta-data")
        var found = false
        for (i in 0 until metas.length) {
            val meta = metas.item(i)
            val name = meta.attributes.getNamedItem("android:name")?.nodeValue
            if (name == EditContract.METADATA_CONTRACT_VERSION) {
                found = true
                assertEquals(
                    "the editor declares contract version ${EditContract.CONTRACT_VERSION}",
                    EditContract.CONTRACT_VERSION.toString(),
                    meta.attributes.getNamedItem("android:value").nodeValue,
                )
            }
        }
        assertTrue("no contract-version meta-data found", found)
    }

    // ---- The zero-network posture (spec section 4.5) ----

    @Test
    fun `the source manifest's only INTERNET mention is the hard-remove strip`() {
        // r41 (the CI merge-gate catch): the onnxruntime-android AAR's own
        // manifest declares INTERNET; the app manifest hard-strips it at
        // merge time (tools:node=remove) so the MERGED manifest the gate
        // reads --- and the device installs --- carries no INTERNET at
        // all. Every occurrence of the INTERNET string in the source
        // manifest must therefore live inside that strip directive and
        // nowhere else: no request, no allowlist, ever (contract 4.5).
        val doc = manifest()
        val permissions = doc.getElementsByTagName("uses-permission")
        var internetStrips = 0
        for (i in 0 until permissions.length) {
            val p = permissions.item(i)
            if (p.attributes.getNamedItem("android:name")?.nodeValue == "android.permission.INTERNET") {
                assertEquals(
                    "the INTERNET line must be the r41 merge-time strip, never a request",
                    "remove",
                    p.attributes.getNamedItem("tools:node")?.nodeValue,
                )
                internetStrips += 1
            }
        }
        assertEquals("exactly one INTERNET strip directive", 1, internetStrips)
        val text = manifestText()
        val mentions = Regex("android\\.permission\\.INTERNET").findAll(text).count()
        assertEquals(
            "every dotted INTERNET mention is the strip directive itself (comments say INTERNET, never the dotted form)",
            internetStrips,
            mentions,
        )
    }

    @Test
    fun `the only declared permission REQUEST is the pre-Q capped storage write`() {
        val doc = manifest()
        val permissions = doc.getElementsByTagName("uses-permission")
        // Two uses-permission ELEMENTS: the legacy write request + the
        // r41 INTERNET strip directive (which requests nothing).
        assertEquals("the write request + the INTERNET strip", 2, permissions.length)
        var realRequests = 0
        for (i in 0 until permissions.length) {
            val p = permissions.item(i)
            if (p.attributes.getNamedItem("tools:node")?.nodeValue == "remove") continue
            realRequests += 1
            assertEquals(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                p.attributes.getNamedItem("android:name").nodeValue,
            )
            assertEquals(
                "the legacy write grant must be capped at API 28 (Q+ inserts need no permission)",
                "28",
                p.attributes.getNamedItem("android:maxSdkVersion").nodeValue,
            )
        }
        assertEquals("exactly one real permission request", 1, realRequests)
    }

    // ---- The application identity ----

    @Test
    fun `allowBackup is off for the editor prototype`() {
        // The prototype holds no user data worth backing up; the gallery's
        // backup-exclusion posture carries over as the conservative default.
        val doc = manifest()
        val application = doc.getElementsByTagName("application").item(0)
        assertEquals("false", application.attributes.getNamedItem("android:allowBackup").nodeValue)
    }
}
