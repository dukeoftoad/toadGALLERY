import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :editor:core — the editor library: photo chassis, tune rail, video editor, export
// pipeline. Consumed as a standard Gradle project dependency by :editor:app (the
// standalone APK); it owns its namespace and resources.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Screenshot harness: record/verify tasks run on the library variants.
    alias(libs.plugins.roborazzi)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/resources/roborazzi"))
}

android {
    namespace = "com.toadgallery.editor"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    // Screenshot tests: NATIVE graphics and fully-qualified baseline naming.
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

// Pin compose-group-mapping to the catalog Kotlin: AGP 9's built-in Kotlin reports
// a version that was never published.
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
