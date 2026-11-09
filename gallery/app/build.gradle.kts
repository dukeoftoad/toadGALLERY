@file:Suppress("DEPRECATION")

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.util.Properties

// :gallery:app --- the gallery APPLICATION shell (Phase 1 modularization).
//
// A thin launcher over :gallery:core (the whole gallery implementation).
// This module owns exactly what an application module must own: the
// applicationId scheme, versionCode/versionName, signing, build types, the
// per-flavor launcher placeholder, the INTERNET privacy gate, and the
// optional -PwithEngine / -PwithBundledModels packaging blocks (moved here
// verbatim from the old :apps:gallery monolith).

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.toadgallery.app"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        // Phase 1 namespace migration: the applicationId scheme moves to the
        // com.toadgallery.* root per the modularization directive. The basic
        // flavor carries the ".basic" suffix so every family member keeps a
        // distinct ID.
        applicationId = "com.toadgallery"
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        // versionCode 46 (r65 round): F-72 lands --- the photo editor canvas
        // zooms; the r63 proposal cross-verified. Version history lives in
        // the git history of the old apps/gallery/build.gradle.kts.
        versionCode = 46
        versionName = "0.4.5-alpha"
        // r55 (the one-launcher unification round): the MAIN/LAUNCHER entry
        // is an activity-alias whose enabled state is this placeholder
        // (default true = basic is the family's ONE launcher icon).
        manifestPlaceholders["launcherAliasEnabled"] = true
    }

    buildFeatures {
        compose = true     // ADR-0009
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                project.libs.versions.app.build.kotlinJVMTarget.get()
            )
        )
    }

    dependenciesInfo {
        includeInApk = false
    }

    // --- Release signing (issue #59) --- verbatim from the old monolith:
    // the release keystore is NEVER committed; all four inputs come from the
    // TG_* environment (fed by the release workflow's secrets). Absent env =
    // unsigned release by design. The r61 public CI debug key wires only
    // when TG_DEBUG_KEYSTORE_FILE points at tools/ci-signing/ci-debug.p12.
    val releaseKeystoreFile = providers.environmentVariable("TG_KEYSTORE_FILE")
    val ciDebugKeystoreFile = providers.environmentVariable("TG_DEBUG_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystoreFile.isPresent) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = providers.environmentVariable("TG_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TG_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TG_KEY_PASSWORD").get()
            }
        }
        if (ciDebugKeystoreFile.isPresent) {
            create("ciDebug") {
                storeFile = file(ciDebugKeystoreFile.get())
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "ci-debug"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (ciDebugKeystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // --- The r46 APK matrix (one flavor dimension: "engine") ---
    // Same dimension name as :gallery:core so variants align. The
    // applicationIdSuffix per flavor yields the directive's ID scheme:
    //   basic    com.toadgallery.basic
    //   engine   com.toadgallery.engine
    //   semantic com.toadgallery.semantic
    //   face     com.toadgallery.face
    // (debug's ".debug" suffix composes as usual.)
    flavorDimensions += "engine"
    productFlavors {
        create("basic") {
            dimension = "engine"
            // No extra suffix beyond the family scheme above.
            manifestPlaceholders["launcherAliasEnabled"] = true
        }
        create("engine") {
            dimension = "engine"
            applicationIdSuffix = ".engine"
            manifestPlaceholders["launcherAliasEnabled"] = false
        }
        create("semantic") {
            dimension = "engine"
            applicationIdSuffix = ".semantic"
            manifestPlaceholders["launcherAliasEnabled"] = false
        }
        create("face") {
            dimension = "engine"
            applicationIdSuffix = ".face"
            manifestPlaceholders["launcherAliasEnabled"] = false
        }
    }
}

// --- Optional Rust engine packaging (r22, ADR-0020) --- verbatim from the
// old monolith (the jniLibs staging is APP packaging, so it lives here).
// The engine's native libraries are packaged ONLY with -PwithEngine.
val withEngine = providers.gradleProperty("withEngine").isPresent
if (withEngine) {
    val buildRustEngine = tasks.register<Exec>("buildRustEngine") {
        group = "build"
        description =
            "Builds the vendored Rust engine for Android (cargo-ndk) and stages " +
                "libtoad_ml_jni.so + libonnxruntime.so into the build-dir jniLibs " +
                "folder (r22, ADR-0020)."
        workingDir = rootProject.projectDir
        commandLine("bash", rootProject.file("engine/scripts/build-android.sh").absolutePath)
        val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
            ?: Properties().apply {
                rootProject.file("local.properties").takeIf { it.isFile }
                    ?.inputStream()?.use { load(it) }
            }.getProperty("sdk.dir")
        if (androidHome != null) {
            environment("ANDROID_HOME", androidHome)
        }
    }
    android {
        sourceSets {
            getByName("main").jniLibs.srcDir(
                layout.buildDirectory.dir("rust-jniLibs").get().asFile
            )
        }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRustEngine) }
}

// --- Optional offline model-pack assets (r28, ADR-0020 offline pack) ---
// -PwithBundledModels packages the CI-staged model bytes into assets/models/.
val withBundledModels = providers.gradleProperty("withBundledModels").isPresent
if (withBundledModels) {
    android {
        sourceSets {
            getByName("main").assets.srcDir(
                layout.buildDirectory.dir("bundled-models").get().asFile
            )
        }
    }
}

// --- The r50-hardened INTERNET privacy gate (issue #20; moved verbatim from
// the old :apps:gallery build --- the allowlist file moved with it to
// gallery/core/internet-permission-allowlist.txt). Fails the build when the
// MERGED manifest declares INTERNET without an allowlist entry; a basic*
// variant fails unconditionally (the flavor overlay strips the permission;
// no allowlist entry can ever re-authorize it for basic). -->
abstract class InternetPermissionGateTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun enforce() {
        val manifestText = mergedManifest.get().asFile.readText()
        val internetPermission = Regex(
            """<(uses-permission|uses-permission-sdk-\d+)[^>]*android:name\s*=\s*"android\.permission\.INTERNET""""
        )
        if (!internetPermission.containsMatchIn(manifestText)) {
            logger.lifecycle(
                "Privacy gate (${variantName.get()}): merged manifest declares no INTERNET permission --- OK"
            )
            return
        }
        if (variantName.get().startsWith("basic")) {
            throw GradleException(
                """
                |
                |**************************************************************************
                |* PRIVACY GATE FAILED (variant '${variantName.get()}')
                |*
                |* The BASIC flavor must never hold android.permission.INTERNET: it is
                |* the offline-first core gallery with the whole AI surface gated
                |* out (BuildShape), so nothing in it can use a socket. The flavor
                |* overlay (src/basic/AndroidManifest.xml) removes the permission;
                |* its reappearance means a dependency injected it via manifest
                |* merger or the overlay was dropped. Find it:
                |*   ./gradlew :gallery:app:dependencies
                |* and remove the source --- the allowlist cannot authorize INTERNET
                |* for basic (see docs/phase-2/privacy-gate.md).
                |**************************************************************************
                """.trimMargin()
            )
        }
        val authorized = allowlist.get().asFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .any { it == "android.permission.INTERNET" }
        if (authorized) {
            logger.warn(
                "Privacy gate (${variantName.get()}): INTERNET is present in the merged manifest " +
                    "and explicitly allowlisted. Revisit docs/phase-2/privacy-gate.md before shipping."
            )
        } else {
            throw GradleException(
                """
                |
                |**************************************************************************
                |* PRIVACY GATE FAILED (variant '${variantName.get()}')
                |*
                |* The merged AndroidManifest declares android.permission.INTERNET, but the
                |* core toadGALLERY app must never hold network permissions (offline by
                |* construction; see docs/phase-2/privacy-gate.md and issue #20).
                |*
                |* Most likely a dependency injected it via manifest merger. Find it:
                |*   ./gradlew :gallery:app:dependencies
                |* Then remove that dependency --- or, if a future ADR authorizes network
                |* access, add a justified entry to
                |*   gallery/core/internet-permission-allowlist.txt
                |**************************************************************************
                """.trimMargin()
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantTitle = variant.name.replaceFirstChar { it.uppercase() }
        val gate = tasks.register<InternetPermissionGateTask>("gate${variantTitle}InternetPermission") {
            group = "verification"
            description = "Fails the build if the merged ${variant.name} manifest declares " +
                "android.permission.INTERNET without an allowlist entry (issue #20)."
            this.variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowlist.set(rootProject.projectDir.resolve("gallery/core/internet-permission-allowlist.txt"))
        }
        listOf("assemble$variantTitle", "bundle$variantTitle").forEach { anchor ->
            tasks.matching { it.name == anchor }.configureEach { dependsOn(gate) }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(gate) }
    }
}

dependencies {
    implementation(project(":gallery:core"))
}
