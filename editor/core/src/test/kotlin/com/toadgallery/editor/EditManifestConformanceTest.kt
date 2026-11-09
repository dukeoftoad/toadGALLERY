package com.toadgallery.editor

import com.toadgallery.editor.contract.EditContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The prototype's contract-conformance suite (Issue #14): parses the EDIT
 * APP's source manifests on the JVM and pins the contract surface the
 * gallery relies on. CI cannot install two APKs on an emulator (ADR-0016),
 * so the round-trip is proven in two layers: this manifest-level
 * conformance suite (the editor's side of the contract) plus the contract
 * module's negotiation/request/result tests (the shared logic), with the
 * on-device dogfood checklist carrying the physical round trip.
 *
 * Phase 1 layout (the deliberate split, see the manifest headers): the
 * CONTRACT SURFACE (activities, intent-filters, mime sets, the two
 * activity-level contract markers, the legacy capped write grant) rides
 * the :editor:core library manifest that both installable shells merge;
 * the APP-LEVEL pieces (the application attributes, the APPLICATION-level
 * r46 fallback marker, and the r41 INTERNET hard-strip) ride the
 * :editor:app SHELL manifest alone --- the unified build embeds the
 * library and keeps the gallery's model-download INTERNET grant, so the
 * strip must never ride the library. Each assertion below reads exactly
 * the manifest that owns its subject.
 */
class EditManifestConformanceTest {

    /**
     * Unit-test working dir is the module dir (:editor:core) under Gradle;
     * the local r41 harness pins the WORKTREE ROOT as the cwd --- be
     * tolerant of both (the repo-root candidate the round's harness runs
     * from, generalized for the post-Phase-1 module layout).
     */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, relative), // the Gradle module cwd (:editor:core)
            File(cwd.parentFile, "core/$relative"), // a sibling :editor:* module cwd
            File(cwd, "editor/core/$relative"), // the repo/worktree-root cwd (the harness)
        ).firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    /**
     * The APP-LEVEL shell manifest resolver: same tolerant pattern, pointed
     * at the :editor:app shell that carries the application attributes, the
     * APPLICATION-level r46 fallback marker, and the r41 INTERNET strip.
     */
    private fun appManifestFile(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, "app/src/main/AndroidManifest.xml"), // a sibling :editor:app cwd
            File(cwd.parentFile, "app/src/main/AndroidManifest.xml"), // the Gradle module cwd (:editor:core)
            File(cwd, "editor/app/src/main/AndroidManifest.xml"), // the repo/worktree-root cwd (the harness)
        ).firstOrNull { it.exists() }
            ?: error("Cannot locate the :editor:app shell manifest from ${cwd.absolutePath}")
    }

    private fun manifestText(): String = moduleFile("src/main/AndroidManifest.xml").readText()

    private fun appManifestText(): String = appManifestFile().readText()

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun manifest() = parse(moduleFile("src/main/AndroidManifest.xml"))

    private fun appManifest() = parse(appManifestFile())

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

    /**
     * The r46 extension to the parsing approach: `contractVersionMetaValues`
     * above collects the contract meta-data at EVERY level of the document
     * and cannot tell WHERE a marker was declared. The application-level
     * fallback marker is a DIRECT child of `<application>`, so this helper
     * walks only that element's own child nodes (the same child-element
     * walking style the intent-filter tests use) and returns the declared
     * value, or null when the application carries no marker at all.
     */
    private fun applicationLevelContractVersion(): Int? {
        val doc = appManifest()
        val application = doc.getElementsByTagName("application").item(0)
        val children = application.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is org.w3c.dom.Element) continue
            if (node.nodeName == "meta-data" &&
                node.getAttribute("android:name") == EditContract.METADATA_CONTRACT_VERSION
            ) {
                return node.getAttribute("android:value").toInt()
            }
        }
        return null
    }

    @Test
    fun `the application element carries the r46 fallback contract marker (value 2)`() {
        // r46 (the contract-discovery hardening round): the gallery's
        // hardened resolver falls back to the APPLICATION-level marker when
        // the activity-level bundle is unavailable on some PackageManager
        // paths (EditorVersionMetadata.pick: activity-first, application-
        // second). The fallback must declare a version the negotiator
        // accepts (>= CONTRACT_VERSION_MIN); the editor declares the
        // module's current version --- the fallback must never understate
        // what the newest surface can do.
        assertEquals(
            "the application-level contract marker must declare the current contract version",
            EditContract.CONTRACT_VERSION,
            applicationLevelContractVersion(),
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
        val doc = appManifest()
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
        assertEquals("exactly one INTERNET strip directive on the app shell", 1, internetStrips)
        val text = appManifestText()
        val mentions = Regex("android\\.permission\\.INTERNET").findAll(text).count()
        assertEquals(
            "every dotted INTERNET mention is the strip directive itself (comments say INTERNET, never the dotted form)",
            internetStrips,
            mentions,
        )
        assertEquals(
            "the :editor:core contract manifest carries no dotted INTERNET mention (the strip is the app shell's alone)",
            0,
            Regex("android\\.permission\\.INTERNET").findAll(manifestText()).count(),
        )
    }

    @Test
    fun `the only declared permission REQUEST is the pre-Q capped storage write`() {
        // Phase 1 layout: the legacy write request rides :editor:core (both
        // installable shells merge it); the APP shell's only uses-permission
        // element is the r41 INTERNET strip directive, which requests
        // nothing.
        val doc = manifest()
        val permissions = doc.getElementsByTagName("uses-permission")
        // Exactly one uses-permission ELEMENT on the library: the legacy
        // write request (the r41 INTERNET strip lives on the :editor:app
        // shell, asserted below).
        assertEquals("exactly the legacy write request on the library", 1, permissions.length)
        val p = permissions.item(0)
        assertEquals(
            "android.permission.WRITE_EXTERNAL_STORAGE",
            p.attributes.getNamedItem("android:name").nodeValue,
        )
        assertEquals(
            "the legacy write grant must be capped at API 28 (Q+ inserts need no permission)",
            "28",
            p.attributes.getNamedItem("android:maxSdkVersion").nodeValue,
        )
        // And the shell: exactly one element, the strip, no real request.
        val appDoc = appManifest()
        val appPermissions = appDoc.getElementsByTagName("uses-permission")
        assertEquals(
            "the APP shell carries exactly one uses-permission element (the INTERNET strip)",
            1,
            appPermissions.length,
        )
        val strip = appPermissions.item(0)
        assertEquals(
            "the APP shell's uses-permission is the r41 strip, never a request",
            "android.permission.INTERNET",
            strip.attributes.getNamedItem("android:name").nodeValue,
        )
        assertEquals("remove", strip.attributes.getNamedItem("tools:node").nodeValue)
    }

    // ---- The application identity ----

    @Test
    fun `allowBackup is off for the editor prototype`() {
        // The prototype holds no user data worth backing up; the gallery's
        // backup-exclusion posture carries over as the conservative default.
        // (Phase 1: the application attributes ride the :editor:app shell.)
        val doc = appManifest()
        val application = doc.getElementsByTagName("application").item(0)
        assertEquals("false", application.attributes.getNamedItem("android:allowBackup").nodeValue)
    }
}
