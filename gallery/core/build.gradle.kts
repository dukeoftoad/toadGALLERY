import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :gallery:core --- the gallery LIBRARY (Phase 1 modularization).
//
// The whole gallery UI/domain implementation moved here from :apps:gallery
// verbatim (zero logic changes): Compose screens, the Room media catalogue,
// the ML/engine facade, the vault, the viewer. The application shells
// (:gallery:app for the four-family builds, :unified:app for the everything
// build) are thin launchers depending on this module --- the old monolith's
// applicationId/versionCode/signing/packaging logic lives THERE now, and the
// sourceSets hack that compiled :apps:edit's sources into the unified
// variant is GONE (the unified build consumes :editor:core as a standard
// project dependency instead).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // KSP drives the Room schema compiler for the Phase 4 media catalogue
    // (ADR-0006 persistence layer); see the room-* entries in the catalog.
    alias(libs.plugins.ksp)
    // Roborazzi (r47, docs/phase-11/roborazzi.md): the record AND verify
    // task pairs live on the library's per-flavor variants now
    // (:gallery:core:recordRoborazziBasicDebug and siblings) --- the
    // screenshot tests moved with the test source set.
    alias(libs.plugins.roborazzi)
}

// --- Roborazzi capture output location (r47, docs/phase-11/roborazzi.md) ----
// The plugin's default output dir is build/outputs/roborazzi --- a BUILD
// directory, which can never hold committed baselines (build/ is gitignored
// and wiped by every clean). The F-34 verify gate needs the goldens committed
// under the module, so the output dir is pointed at the test resources tree:
// the recordRoborazziBasicDebug task writes the PNGs STRAIGHT into the
// directory they are committed from, the verifyRoborazziBasicDebug task reads
// them back from the same place, and no copy step ever exists between a
// recorded artifact and the committed baseline. (Restored at the Phase-1
// split's cost: the block was lost when the monolith became :gallery:core,
// which is why the #93 verify leg could not find its originals --- the
// pre-split wording from apps/gallery/build.gradle.kts, verbatim.)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/resources/roborazzi"))
}

android {
    namespace = "com.toadgallery.gallery"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        // The application modules auto-generate VERSION_NAME/VERSION_CODE in
        // their own BuildConfig; a LIBRARY's BuildConfig does not, and the
        // About card (SettingsScreen) reads them through the old monolith's
        // single BuildConfig. Mirror the shell values here (gallery/app and
        // :unified:app carry versionCode 46 / versionName 0.4.5-alpha) so
        // the code sees the same constants as before the split.
        buildConfigField("int", "VERSION_CODE", "46")
        buildConfigField("String", "VERSION_NAME", "\"0.4.5-alpha\"")
        // Same story for APPLICATION_ID: an application module's BuildConfig
        // carries it, a LIBRARY's does not --- but LauncherConsolidationTest
        // (the r55 consolidation contract) names the offending family build
        // in its failure message via BuildConfig.APPLICATION_ID. Mirror the
        // shells' applicationId scheme (gallery/app defaultConfig
        // "com.toadgallery" + the per-flavor suffixes; :unified:app keeps
        // "com.toadgallery.unified"). Flavor-level fields below override the
        // default per variant, so each engine-family task reports its own
        // real install ID; build-type suffixes (".debug") are deliberately
        // NOT mirrored --- the field exists to identify the FAMILY build in
        // test diagnostics, not to sign manifests.
        buildConfigField("String", "APPLICATION_ID", "\"com.toadgallery\"")
        // r55 launcher-alias placeholder: the LIBRARY manifest carries the
        // MAIN/LAUNCHER activity-alias whose enabled state is a placeholder,
        // so library-level manifest processing (the unit-test manifest merge
        // the Roborazzi gate runs, and the release-resource verification
        // CodeQL's autobuild `assemble` triggers) needs the value too ---
        // without it the merge fails with "no value for <launcherAliasEnabled>
        // is provided". Mirrors the app-side defaultConfig value; the app
        // flavors still override (basic=true, engine/semantic/face=false).
        manifestPlaceholders["launcherAliasEnabled"] = true
    }

    buildFeatures {
        compose = true     // ADR-0009
        // r46 (the APK-matrix round): the BuildShape source of truth reads
        // per-flavor WITH_ENGINE / INDEX_FACE / INDEX_SEMANTIC fields, so
        // BuildConfig generation must stay on.
        buildConfig = true
    }

    // --- Screenshot tests (Phase 11, docs/phase-11/roborazzi.md) ---
    // Roborazzi captures render the real Compose UI under Robolectric, so:
    // - isIncludeAndroidResources gives the JVM tests the actual resources
    //   (the state cards resolve stringResource copy);
    // - NATIVE graphics mode is mandatory (Robolectric's default LEGACY mode
    //   cannot produce pixels for captureToImage).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.record.namingStrategy", "fullyQualifiedName")
            }
        }
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

    // --- The r46 APK matrix (one flavor dimension: "engine") ---
    // The flavor dimension stays on the LIBRARY: the BuildShape flags are
    // consumed by THIS module's code through its own per-flavor BuildConfig,
    // and variant alignment with the app shells works because they declare
    // the same dimension ("missingDimensionStrategy" covers :unified:app,
    // which carries no flavors of its own). The applicationIdSuffix /
    // manifestPlaceholders halves of the old flavors are application-level
    // and live on :gallery:app / :unified:app now.
    flavorDimensions += "engine"
    productFlavors {
        // The canonical gallery: no engine library, no engine UI.
        create("basic") {
            dimension = "engine"
            buildConfigField("boolean", "WITH_ENGINE", "false")
            buildConfigField("boolean", "INDEX_FACE", "false")
            buildConfigField("boolean", "INDEX_SEMANTIC", "false")
        }
        // The full engine build: everything the r22-r39 rounds shipped.
        create("engine") {
            dimension = "engine"
            buildConfigField("boolean", "WITH_ENGINE", "true")
            buildConfigField("boolean", "INDEX_FACE", "true")
            buildConfigField("boolean", "INDEX_SEMANTIC", "true")
            // The :gallery:app engine shell's install ID (applicationId
            // "com.toadgallery" + ".engine" suffix).
            buildConfigField("String", "APPLICATION_ID", "\"com.toadgallery.engine\"")
        }
        // Semantic-index-only: Magic Search + Things, no People.
        create("semantic") {
            dimension = "engine"
            buildConfigField("boolean", "WITH_ENGINE", "true")
            buildConfigField("boolean", "INDEX_FACE", "false")
            buildConfigField("boolean", "INDEX_SEMANTIC", "true")
            buildConfigField("String", "APPLICATION_ID", "\"com.toadgallery.semantic\"")
        }
        // Face-index-only: People, no Magic Search / Things.
        create("face") {
            dimension = "engine"
            buildConfigField("boolean", "WITH_ENGINE", "true")
            buildConfigField("boolean", "INDEX_FACE", "true")
            buildConfigField("boolean", "INDEX_SEMANTIC", "false")
            buildConfigField("String", "APPLICATION_ID", "\"com.toadgallery.face\"")
        }
        // r59: EVERYTHING in one installable. As a LIBRARY flavor this
        // variant exists so :unified:app (flavorless) can select it with
        // missingDimensionStrategy and receive the unified BuildConfig
        // shape; the shell owns the applicationId/versionName suffix.
        create("unified") {
            dimension = "engine"
            buildConfigField("boolean", "WITH_ENGINE", "true")
            buildConfigField("boolean", "INDEX_FACE", "true")
            buildConfigField("boolean", "INDEX_SEMANTIC", "true")
            // :unified:app's applicationId (no suffix scheme there).
            buildConfigField("String", "APPLICATION_ID", "\"com.toadgallery.unified\"")
        }
    }
}

// Release/Compose mapping classpath fixup (issue #59) --- verbatim from the
// old :apps:gallery build: AGP 9's built-in Kotlin reports 2.2.10 for
// compose-group-mapping, a version never published; pin it to the catalog
// Kotlin so the mapping artifact resolves.
configurations.configureEach {
    if (name == "composeMappingProducerClasspath") {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.module.name == "compose-group-mapping"
            ) {
                useVersion("2.3.10")
                because(
                    "AGP 9.2.1 built-in Kotlin reports version 2.2.10, which was never " +
                        "published for compose-group-mapping; 2.3.10 matches the applied " +
                        "Compose compiler plugin (Kotlin 2.3.10, issue #59)."
                )
            }
        }
    }
}

// Room schema export (Phase 4 audit finding K): versioned JSON snapshots
// under gallery/core/schemas/, committed to source control.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // --- The versioned Gallery <-> Edit contract (r29, ADR-0002/0007):
    // pure-JVM module shared with the editor (one truth, both apps).
    implementation(project(":shared:editor-contract"))

    // --- Privacy test suite (issue #20) ---
    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    // --- Screenshot tests (Phase 11, docs/phase-11/roborazzi.md) ---
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    // Instrumented egress suite: runtime permission assert + socket-egress probe +
    // cold-launch/tab-walk; run in connectedDebugAndroidTest on the CI emulator.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules) // GrantPermissionRule (Phase 3 media-permission pre-grant)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit) // @RunWith/Assert surface for the instrumented suite
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Compose (ADR-0009) --- canonical UI toolkit for all screens
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core) // TopAppBar icons (issue #29)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx) // suspend-DAO coroutine dispatch
    ksp(libs.androidx.room.compiler) // schema validation via KSP, not kapt
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.material)
}
