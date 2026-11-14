// :shared:video-edit — the lightweight video editor's pure export-plan module.
// No Android types: every export policy is data, JVM-tested in milliseconds; the media3
// realizations (preview, Transformer export) live in :editor:core behind VideoExportRunner.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed")
    }
}
