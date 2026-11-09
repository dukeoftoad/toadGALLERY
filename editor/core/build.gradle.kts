import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :editor:core --- the editor LIBRARY (Phase 1 modularization).
//
// The whole editor implementation moved here from :apps:edit verbatim (zero
// logic changes): the photo editor chassis, the Ente tune-rail, the video
// editor, the export pipeline. Two consumers now reach it as a STANDARD
// Gradle project dependency:
//   - :editor:app      (the standalone installable APK)
//   - :unified:app     (the everything-in-one build)
// which REPLACES the old :apps:gallery unified-flavor sourceSets hack
// (../edit/src/main registrations + the generated edit-package R class) ---
// that whole F-74 machinery is retired: R resolves normally because this
// library owns its namespace (com.toadgallery.editor) and its resources.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Roborazzi (r56, the editor's screenshot harness): record/verify tasks
    // on the library variants (:editor:core:recordRoborazziDebug ...).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.toadgallery.editor"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
    }

    buildFeatures {
        compose = true // ADR-0009
    }

    // Screenshot tests (r56): verbatim wiring from the old :apps:edit build.
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

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }
}

// AGP 9's built-in Kotlin reports 2.2.10 for compose-group-mapping, a
// version never published; pin the mapping artifact to the catalog Kotlin
// (the exact fixup the gallery module carries since issue #59).
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
                        "Compose compiler plugin (Kotlin 2.3.10)."
                )
            }
        }
    }
}

dependencies {
    implementation(project(":shared:editor-contract"))
    implementation(project(":shared:video-edit"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.material)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
