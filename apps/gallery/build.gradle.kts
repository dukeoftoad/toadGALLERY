import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.dukeoftoad.gallery"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "io.github.dukeoftoad.gallery" // provisional --- ADR-0009
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        versionCode = 3
        versionName = "0.2.1-alpha"
    }

    buildFeatures {
        compose = true     // ADR-0009
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        getByName("test").java.directories.add("src/test/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    // AGP 9 built-in Kotlin: jvm target mirrors the verified Fossify baseline configuration
    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    // --- Compose (ADR-0009) --- canonical UI toolkit for all screens
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // --- Image loading (Compose-first per compose-migration.md)
    implementation(libs.coil.compose)

    // --- Metadata (ADR-0008: androidx.exifinterface, NOT the Aves fork)
    implementation(libs.androidx.exifinterface)

    // --- Theme resource (Theme.Material3.DayNight.NoActionBar for the manifest)
    implementation(libs.material)

    // --- Privacy test suite (ADR-0014, issue #20) --- JVM-only manifest & policy assertions
    testImplementation(libs.junit)
}

// ═══════════════════════════════════════════════════════════════════════════════
// Privacy build gate --- ADR-0014 (issue #20, Phase 2 exit criterion)
//
// toadGALLERY is an offline-first gallery: the product-vision invariant is
// "no INTERNET permission in the core app manifest" and Fossify-grade privacy
// posture. Convention is not enforcement, so the invariant is a BUILD GATE:
// every variant's MERGED manifest (app + every dependency contribution) is
// audited before packaging, and the build fails on any violation.
//
// Layers:
//   1. privacyGate<Variant> Gradle tasks (below) --- merged-manifest audit,
//      wired into assemble<Variant> and check, blocking in CI (ADR-0013).
//   2. JVM unit-test suite under src/test/.../privacy/ --- source-manifest
//      assertions, build-script contract tests, CI-workflow contract tests,
//      and a source-level network-API ban scan.
//   3. Phase 8 (ADR-0010 §7) extends this with a runtime zero-outbound-
//      connections audit; this gate is the static foundation.
// ═══════════════════════════════════════════════════════════════════════════════

// Permissions that must NEVER appear in any toadGALLERY variant, in any phase.
// (Keep the JVM mirror in src/test/.../privacy/SourceManifestPrivacyTest.kt in sync.)
val privacyDenyList = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
)

// Policy-declared permission set per variant (ADR-0014). A permission is only
// present if it is listed here AND declared in the manifest --- any drift in
// either direction fails the build. Phase 2 shell: empty by design.
// Phase 3 will add the READ_MEDIA_* set here after roadmap review.
val expectedPermissionsByVariant = mapOf(
    "debug" to emptySet<String>(),
    "release" to emptySet<String>(),
)

/**
 * Audits one variant's merged manifest against the ADR-0014 policy.
 * Fails the build on: deny-list hits, undeclared-but-expected permissions,
 * expected-but-missing permissions, or cleartext traffic being enabled.
 */
abstract class PrivacyGateTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val denyList: ListProperty<String>

    @get:Input
    abstract val expectedPermissions: ListProperty<String>

    /**
     * Self-scoped permissions auto-allowed by policy (ADR-0014 §3 note): the
     * manifest merger injects `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
     * (signature protection, scoped to this app, inert for privacy) for
     * androidx dynamic-receiver support. Recorded in every gate report so the
     * allowance stays visible, never silent.
     */
    @get:Input
    abstract val selfScopedPermissions: ListProperty<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun enforce() {
        val manifestFile = mergedManifest.get().asFile
        val rawText = manifestFile.readText()

        // --- Element-level scan: the permissions aapt2 actually packages. ---
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val declared = sortedSetOf<String>()
        val doc = factory.newDocumentBuilder().parse(manifestFile)
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            if (element.tagName == "uses-permission" || element.tagName == "uses-permission-sdk-23") {
                val name = element.getAttribute("android:name").trim()
                if (name.isNotEmpty()) declared.add(name)
            }
        }

        val violations = mutableListOf<String>()

        // 1. Absolute deny-list (network family) --- never allowed, any phase.
        val denied = declared.filter { it in denyList.get() }
        if (denied.isNotEmpty()) {
            violations += "deny-list permissions declared: $denied"
        }

        // 2. Allow-list drift (both directions) --- every permission is policy-declared.
        val expected = expectedPermissions.get().toSet() + selfScopedPermissions.get().toSet()
        val unexpected = declared.filterNot { it in expected }
        if (unexpected.isNotEmpty()) {
            violations += "permissions present but not in the ADR-0014 allow-list (add them via roadmap review, never silently): $unexpected"
        }
        val missing = expected.filterNot { it in declared }
        if (missing.isNotEmpty()) {
            violations += "allow-list permissions missing from the manifest (declared policy drifted): $missing"
        }

        // 3. Cleartext traffic must never be enabled.
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length > 0) {
            val app = appNodes.item(0) as org.w3c.dom.Element
            if (app.getAttribute("android:usesCleartextTraffic").equals("true", ignoreCase = true)) {
                violations += "android:usesCleartextTraffic=true in the merged manifest"
            }
        }

        // 4. Raw-text belt-and-braces scan (catches deny tokens in any element or
        //    attribute the element scan does not model), ignoring tools:node="remove".
        val rawScanText = rawText.lineSequence()
            .filterNot { it.contains("""tools:node="remove"""") }
            .joinToString("\n")
        denyList.get().forEach { permission ->
            if (rawScanText.contains(permission)) {
                violations += "deny-list token '$permission' found in raw merged manifest text"
            }
        }

        // --- Report ---
        val policyOnly = expectedPermissions.get().toSet()
        val selfScoped = selfScopedPermissions.get().toSet()
        val reportText = buildString {
            appendLine("toadGALLERY privacy gate --- ADR-0014")
            appendLine("variant   : ${variantName.get()}")
            appendLine("manifest  : ${manifestFile.absolutePath}")
            appendLine("declared  : ${if (declared.isEmpty()) "(none)" else declared.joinToString(", ")}")
            appendLine("allow-list: ${if (policyOnly.isEmpty()) "(none)" else policyOnly.sorted().joinToString(", ")}")
            appendLine("self-scoped (auto-allowed, app-signature-protected): ${if (selfScoped.isEmpty()) "(none)" else selfScoped.sorted().joinToString(", ")}")
            appendLine("result    : ${if (violations.isEmpty()) "PASS" else "FAIL"}")
            violations.forEach { appendLine("violation : $it") }
            appendLine()
        }
        val report = reportFile.get().asFile.apply { parentFile.mkdirs() }.also { it.writeText(reportText) }
        logger.lifecycle(reportText)

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Privacy gate FAILED for variant '${variantName.get()}' (ADR-0014).\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\nSee $report"
            )
        }
    }
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val gate = tasks.register<PrivacyGateTask>("privacyGate$capitalized") {
        group = "verification"
        description = "ADR-0014 privacy gate: audits the ${variant.name} merged manifest."
        denyList.set(privacyDenyList)
        expectedPermissions.set(expectedPermissionsByVariant[variant.name].orEmpty())
        variantName.set(variant.name)
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        selfScopedPermissions.set(
            listOf("${variant.applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        )
        reportFile.set(layout.buildDirectory.file("privacy/privacy-gate-${variant.name}.txt"))
    }
    // Build-time enforcement: every assemble and the aggregate check run the gate.
    tasks.matching { it.name == "assemble$capitalized" }.configureEach { dependsOn(gate) }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(gate) }
}
