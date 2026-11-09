@file:Suppress("DEPRECATION")

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.util.Properties

// :unified:app --- the EVERYTHING-in-one application (Phase 1
// modularization; r59's unified round made concrete as its own module).
//
// THE UNIFIED FIX, per the modularization directive: the editor reaches this
// build through STANDARD project dependencies: no relative-path source
// registrations, no generated R shim. The
// directive's exact dependency wiring:
//
//     implementation(project(":gallery:core"))
//     implementation(project(":editor:core"))
//     implementation(project(":shared:editor-contract"))
//     implementation(project(":shared:video-edit"))
//
// (:shared:video-edit is a real module in this repo --- the pure video-plan
// module both editors consume --- so it is wired alongside the contract.)
// missingDimensionStrategy selects :gallery:core's "unified" flavor, so the
// embedded gallery code compiles with the unified BuildConfig shape
// (WITH_ENGINE/INDEX_FACE/INDEX_SEMANTIC all true) exactly as before.

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.toadgallery.unified"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "com.toadgallery.unified"
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        // The unified build rides the gallery's version counter (46, r65)
        // with the -unified versionName suffix, exactly like the old flavor.
        versionCode = 46
        versionName = "0.4.5-alpha"
        versionNameSuffix = "-unified"
        // r55: the launcher alias stays ENABLED (the unified build is a
        // launcher app --- it inherits basic's true default).
        manifestPlaceholders["launcherAliasEnabled"] = true
        // This shell carries no flavors of its own; select :gallery:core's
        // unified variant explicitly.
        missingDimensionStrategy("engine", "unified")
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
}

// --- Optional Rust engine packaging (r22, ADR-0020) --- the unified build
// embeds the engine, so it carries the same -PwithEngine block as
// :gallery:app (verbatim).
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

// --- Optional offline model-pack assets (r28) --- same as :gallery:app.
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

// --- The r50-hardened INTERNET privacy gate --- the unified app holds the
// gallery's ADR-0020 model-download INTERNET grant (allowlisted); the same
// gate with the same allowlist file guards it (the editor's strip directive
// deliberately does NOT ride :editor:core's library manifest, so the merged
// unified manifest keeps the model-download grant --- the old unified
// overlay's rule, now expressed by the module graph itself).
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
                |* unified app's only authorized network use is the ADR-0020 model
                |* download (offline by construction otherwise; docs/phase-2/privacy-gate.md).
                |*
                |* Find the injecting dependency:
                |*   ./gradlew :unified:app:dependencies
                |* Then remove it --- or add a justified entry to
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
    implementation(project(":editor:core"))
    implementation(project(":shared:editor-contract"))
    implementation(project(":shared:video-edit"))
}
