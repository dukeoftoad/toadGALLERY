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

    /** The r42 two-surface meta-data pin: image declares v1 (the byte-identical v1 wire), video declares v2. */
    private fun contractVersionMetaValues(): List<Int> {
        val doc = manifest()
        val metas = doc.getElementsByTagName("meta-data")
        val values = mutableListOf<Int>()
        for (i in 0 until metas.length) {
            val meta = metas.item(i)
            val name = meta.attributes.getNamedItem("android:name")?.nodeValue
            if (name == EditContract.METADATA_CONTRACT_VERSION) {
                values.add(meta.attributes.getNamedItem("android:value").nodeValue.toInt())
            }
        }
        return values
    }

    @Test
    fun `the image surface declares contract v1 (the v1 wire stays byte-identical)`() {
        // The image activity keeps declaring 1: an image launch against a
        // v1-era editor behaves exactly as before --- the additive rule.
        val values = contractVersionMetaValues()
        assertTrue("the image surface must declare version 1", values.contains(1))
    }

    @Test
    fun `the video surface declares the current contract version`() {
        // The r42 video surface IS the v2 addition, so it declares the
        // module's current version and the gallery's video query
        // negotiates against exactly it.
        val values = contractVersionMetaValues()
        assertTrue(
            "the video surface must declare version ${EditContract.CONTRACT_VERSION}",
            values.contains(EditContract.CONTRACT_VERSION),
        )
    }

    // ---- The r42 video launch surface (the additive contract v2 half) ----

    @Test
    fun `the video edit activity is exported and filtered on the contract action with video mimes`() {
        val doc = manifest()
        val activities = doc.getElementsByTagName("activity")
        var found = false
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as org.w3c.dom.Element
            val name = activity.attributes.getNamedItem("android:name").nodeValue
            if (name == ".video.VideoEditActivity" || name.endsWith(".video.VideoEditActivity")) {
                found = true
                assertEquals(
                    "the video surface MUST be exported",
                    "true",
                    activity.attributes.getNamedItem("android:exported").nodeValue,
                )
                val filters = activity.getElementsByTagName("intent-filter")
                var hasAction = false
                var hasDefault = false
                val mimes = mutableListOf<String>()
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
                            "data" -> mimes.add(node.getAttribute("android:mimeType"))
                        }
                    }
                }
                assertTrue("the video surface must carry the contract action", hasAction)
                assertTrue("the video filter must keep the DEFAULT category", hasDefault)
                // The honest encodable set the media3 Transformer pipeline
                // re-encodes on-device (MP4 / MOV / 3GP).
                assertEquals(
                    listOf("video/mp4", "video/quicktime", "video/3gpp"),
                    mimes,
                )
            }
        }
        assertTrue("no VideoEditActivity found in the manifest", found)
    }

    @Test
    fun `the image surface stays filtered on image mimes only`() {
        // The additive rule's other half: the IMAGE activity never gained a
        // video mime (a video launch cannot resolve the image surface).
        val doc = manifest()
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as org.w3c.dom.Element
            val name = activity.attributes.getNamedItem("android:name").nodeValue
            if (name == ".EditActivity" || name.endsWith(".EditActivity")) {
                val mimes = activity.getElementsByTagName("data")
                for (j in 0 until mimes.length) {
                    val mime = mimes.item(j).attributes.getNamedItem("android:mimeType").nodeValue
                    assertFalse("the image surface must stay image-only: $mime", mime.startsWith("video/"))
                }
            }
        }
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
