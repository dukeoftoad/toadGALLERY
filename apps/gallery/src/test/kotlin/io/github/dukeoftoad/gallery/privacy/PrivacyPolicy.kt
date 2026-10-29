package io.github.dukeoftoad.gallery.privacy

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared policy constants and helpers for the ADR-0014 privacy test suite
 * (issue #20, Phase 2 exit criterion).
 *
 * The Gradle-side deny-list lives in `apps/gallery/build.gradle.kts`
 * (`privacyDenyList`); this JVM mirror is deliberately duplicated so the two
 * layers fail independently --- if either drifts, a test or the build gate
 * catches it. Keep them in sync (a contract test asserts the build-script
 * side still exists and still denies the network family).
 */
object PrivacyPolicy {

    /** Permissions that must NEVER appear in any toadGALLERY variant, any phase. */
    val NETWORK_DENY_LIST = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
    )

    /** Network client APIs banned from `src/main` source (ADR-0014; Phase 8 adds runtime audit). */
    val BANNED_SOURCE_TOKENS = listOf(
        "HttpURLConnection",
        "openConnection(",
        "java.net.URL",
        "java.net.Socket",
        "ServerSocket(",
        "okhttp3.",
        "retrofit2.",
        "com.android.volley",
        "io.ktor.client",
        "android.permission.INTERNET",
    )

    /**
     * Locates the `apps/gallery` module directory by walking up from the JVM
     * working directory (Gradle sets it to the module dir; IDEs and shells may
     * differ, so walk up to six levels looking for the module marker).
     */
    fun moduleDir(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (File(current, "src/main/AndroidManifest.xml").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate apps/gallery module dir from ${System.getProperty("user.dir")}")
    }

    /** Locates the repository root by walking up until `.github/workflows/ci.yml` is found. */
    fun repoRoot(): File {
        var current = moduleDir()
        repeat(4) {
            if (File(current, ".github/workflows/ci.yml").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate repo root (no .github/workflows/ci.yml above ${moduleDir()})")
    }

    /** Parses an Android manifest XML file and returns every declared permission name. */
    fun declaredPermissions(manifest: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val declared = mutableSetOf<String>()
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            if (element.tagName == "uses-permission" || element.tagName == "uses-permission-sdk-23") {
                val name = element.getAttribute("android:name").trim()
                if (name.isNotEmpty()) declared.add(name)
            }
        }
        return declared
    }

    /** Reads the `android:usesCleartextTraffic` value of the application element, if present. */
    fun usesCleartextTraffic(manifest: File): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length == 0) return null
        val app = appNodes.item(0) as org.w3c.dom.Element
        return app.getAttribute("android:usesCleartextTraffic").ifEmpty { null }
    }

    /** Raw-text scan of a file that ignores `tools:node="remove"` lines. */
    fun rawTextWithoutRemoveNodes(file: File): String =
        file.readText().lineSequence()
            .filterNot { it.contains("""tools:node="remove"""") }
            .joinToString("\n")
}
