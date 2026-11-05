// :shared:video-edit --- the Phase 10 lightweight video editor's pure plan
// module (r42, sub-scope B; ADR-0012; docs/phase-10/video-editor.md).
//
// A deliberate PURE-JVM Kotlin module (the :shared:editor-contract pattern):
// no Android type anywhere. The plan layer encodes every policy as data ---
// the ExportPlan precedent verbatim in spirit --- so every edge is JVM-tested
// in milliseconds and the activity merely executes the validated plan. The
// media3 realizations (the ExoPlayer preview, the Transformer export, the
// retriever frame path) live in the edit app behind the VideoExportRunner
// seam this module defines.

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
