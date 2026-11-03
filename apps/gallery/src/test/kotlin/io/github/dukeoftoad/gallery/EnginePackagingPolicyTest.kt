package io.github.dukeoftoad.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r28 (Workstream 8) ENGINE PACKAGING POLICY pins --- the testable
 * half of the low-memory / ABI matrix verification
 * (`docs/phase-5/hardening.md`, §Low-memory behavior and §ABI matrix):
 * WHAT the app ships per ABI, and under which build properties, is a
 * BUILD-SCRIPT + build-script + cargo-config contract, so it is pinned
 * the same way the privacy suites pin the manifest and the gate wiring
 * (`PrivacyPolicyTest` / `BackupRulesTest` style source assertions).
 *
 * The matrix this suite pins (the full narrative, including the honest
 * 32-bit-device story, lives in the hardening doc):
 *
 *  - a DEFAULT build (`assembleDebug` with no properties) ships NO
 *    native libraries and NO bundled models: the engine jniLibs srcDir
 *    and the bundled-models assets srcDir exist only inside their
 *    `-PwithEngine` / `-PwithBundledModels` property gates;
 *  - a `-PwithEngine` build ships exactly TWO native libraries for
 *    arm64-v8a (`libtoad_ml_jni.so` + `libonnxruntime.so`) --- the
 *    script's default target is aarch64-linux-android; x86_64 is an
 *    explicit `--all` opt-in for emulator dogfood builds;
 *  - every Android target the engine can build links with the 16 KB
 *    page-size discipline (Play requirement, mid-2025) --- pinned in
 *    `engine/.cargo/config.toml` for aarch64, armv7 AND x86_64;
 *  - the NDK is pinned (`28.1.13356709`) and ONNX Runtime is fetched
 *    sha256-verified from Microsoft's official Maven AAR (the r22
 *    supply-chain decision);
 *  - the platform envelope the matrix is stated against: minSdk 26 /
 *    targetSdk 36 / compileSdk 36.
 *
 * Deliberately NOT pinned here: `.github/workflows/ci.yml` content
 * (shared with parallel rounds and expected to move; the engine-android
 * job's own `unzip` gate enforces the packaged libraries at build
 * time), and anything inside the vendored `engine` crates tree (the
 * ADR-0020 pin --- byte-identical to upstream, never edited).
 */
class EnginePackagingPolicyTest {

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    /** Repo-root file (engine/, .github/, ...): module dir -> repo root. */
    private fun repoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = sequenceOf(cwd.parentFile?.parentFile, cwd.parentFile, cwd)
            .firstOrNull { it != null && File(it, "engine/scripts/build-android.sh").exists() }
            ?: error("Cannot locate the repo root from ${cwd.absolutePath}")
        return File(root, relative)
    }

    /**
     * Engine native libraries ship ONLY behind `-PwithEngine`: the
     * property read gates the jniLibs srcDir, and the srcDir (build
     * directory, never src/main) appears only INSIDE that gate. A
     * default build therefore packages no `lib/` entries at all.
     */
    @Test
    fun `engine native libraries ship only behind the withEngine property`() {
        val buildScript = moduleFile("build.gradle.kts").readText()
        val gate = buildScript.indexOf("providers.gradleProperty(\"withEngine\").isPresent")
        val srcDir = buildScript.indexOf("rust-jniLibs")
        assertTrue(
            "The withEngine property gate must stay in the build script --- without it every " +
                "build would require a Rust toolchain (ADR-0020's opt-in packaging).",
            gate >= 0,
        )
        assertTrue(
            "The rust-jniLibs srcDir must exist (the staged native libraries' source dir).",
            srcDir >= 0,
        )
        assertTrue(
            "The rust-jniLibs srcDir must live INSIDE the withEngine gate (position $srcDir vs " +
                "gate at $gate) --- a default build ships no native libraries.",
            srcDir > gate,
        )
    }

    /**
     * The r28 offline model pack ships ONLY behind
     * `-PwithBundledModels`: same gate shape --- a default build's APK
     * carries no model bytes; the modelpack workflow turns the gate on
     * after staging the sha256-verified pack into the build directory.
     */
    @Test
    fun `the offline model pack ships only behind the withBundledModels property`() {
        val buildScript = moduleFile("build.gradle.kts").readText()
        val gate = buildScript.indexOf("providers.gradleProperty(\"withBundledModels\").isPresent")
        val srcDir = buildScript.indexOf("bundled-models")
        assertTrue(
            "The withBundledModels property gate must stay in the build script (r28, ADR-0020 " +
                "offline pack).",
            gate >= 0,
        )
        assertTrue(
            "The bundled-models assets srcDir must exist.",
            srcDir >= 0,
        )
        assertTrue(
            "The bundled-models assets srcDir must live INSIDE the withBundledModels gate " +
                "(position $srcDir vs gate at $gate) --- a default build ships no model bytes.",
            srcDir > gate,
        )
    }

    /**
     * The engine's Android build targets arm64-v8a BY DEFAULT; x86_64
     * is an explicit `--all` opt-in. This is the ABI the shipped
     * engine APKs carry (verified per-build by the engine-android CI
     * job's unzip gate) --- 64-bit ARM is the matrix's shipped row.
     */
    @Test
    fun `the engine build targets arm64-v8a by default and x86_64 only with all`() {
        val script = repoFile("engine/scripts/build-android.sh").readText()
        assertTrue(
            "The default Rust target list must be exactly aarch64-linux-android (arm64-v8a).",
            Regex("""RUST_TARGETS=\(aarch64-linux-android\)""").containsMatchIn(script),
        )
        assertTrue(
            "x86_64 must remain the explicit --all opt-in, never a default target.",
            Regex("\\[ \"\\\$ALL\" -eq 1 \\] && RUST_TARGETS\\+=\\(\x86_64-linux-android\\)").containsMatchIn(script),
        )
        assertTrue(
            "cargo-ndk must be invoked with the arm64-v8a target by default.",
            Regex("""NDK_ARGS=\(-t arm64-v8a\)""").containsMatchIn(script),
        )
    }

    /**
     * Both native libraries the engine surface requires are staged per
     * ABI: the JNI bridge built from the vendored workspace AND the
     * pinned ONNX Runtime shared library fetched from Microsoft's
     * official Maven AAR (supply-chain decision r22: sha256-verified,
     * the exact version the ort-sys rc.15 bindings pin).
     */
    @Test
    fun `both native libraries are staged per abi with a pinned NDK`() {
        val script = repoFile("engine/scripts/build-android.sh").readText()
        assertTrue(
            "The JNI bridge library must be staged as libtoad_ml_jni.so.",
            script.contains("libtoad_ml_jni.so"),
        )
        assertTrue(
            "ONNX Runtime must be staged as libonnxruntime.so.",
            script.contains("libonnxruntime.so"),
        )
        assertTrue(
            "The ONNX Runtime AAR must be sha256-verified before extraction.",
            script.contains("ORT_AAR_SHA256="),
        )
        assertTrue(
            "The NDK must stay pinned (r22 verification: 28.1.13356709).",
            script.contains("ndk;28.1.13356709"),
        )
    }

    /**
     * The 16 KB page-size link discipline covers every Android target
     * the engine can build (aarch64, armv7, x86_64) --- the Play
     * requirement from mid-2025, carried verbatim from upstream's
     * cargo config at the pin. Even though only arm64-v8a ships today,
     * the armv7 row is what a future 32-bit build would need, and its
     * presence documents that the discipline is target-level, not
     * per-build.
     */
    @Test
    fun `the 16 KB page-size link discipline covers every android target`() {
        val config = repoFile("engine/.cargo/config.toml").readText()
        // Split into [target.<name>] sections (one per line-start marker)
        // and require the flag inside each named section --- a plain
        // contains() could match the flag in a DIFFERENT target's block.
        val sections = config.split(Regex("(?m)^\\[target\\.")).drop(1)
        listOf(
            "aarch64-linux-android",
            "armv7-linux-androideabi",
            "x86_64-linux-android",
        ).forEach { target ->
            assertTrue(
                "engine/.cargo/config.toml must link $target with max-page-size=16384 (the Play " +
                    "16 KB page requirement).",
                sections.any { it.startsWith("$target]") && it.contains("max-page-size=16384") },
            )
        }
    }

    /**
     * The platform envelope the ABI matrix is stated against: minSdk
     * 26 (the pre-Q reduced-projection fallback paths stay live),
     * target/compileSdk 36. Pinned from the version catalog the build
     * reads, so a bump that would invalidate the matrix's device-class
     * narrative fails here instead of shipping silently.
     */
    @Test
    fun `the platform envelope is minSdk 26 targetSdk 36 compileSdk 36`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        assertEquals(
            "minSdk must stay 26 (the matrix's pre-Q rows document the API 26-28 fallbacks).",
            "app-build-minimumSDK = \"26\"",
            catalog.lines().firstOrNull { it.trim().startsWith("app-build-minimumSDK") }?.trim(),
        )
        assertEquals(
            "targetSdk must stay 36 while the matrix documents it.",
            "app-build-targetSDK = \"36\"",
            catalog.lines().firstOrNull { it.trim().startsWith("app-build-targetSDK") }?.trim(),
        )
        assertEquals(
            "compileSdk must stay 36 while the matrix documents it.",
            "app-build-compileSDKVersion = \"36\"",
            catalog.lines().firstOrNull { it.trim().startsWith("app-build-compileSDKVersion") }?.trim(),
        )
    }
}
