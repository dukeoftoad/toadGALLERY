// :shared:editor-contract --- the versioned Gallery <-> Edit integration
// contract (r29, Issue #14 gate; ADR-0002 / ADR-0007).
//
// A deliberate PURE-JVM Kotlin module: no Android dependency at all. The
// contract's logic (identity matching, version negotiation, request
// validation, result parsing) is exactly the part both apps must agree on,
// and compiling it as a plain jar means every rule is exercised on the JVM
// in milliseconds --- no emulator, no Robolectric --- by BOTH apps' CI
// gates. The Android layers (gallery's editor launcher, the edit app's
// activity) adapt the primitive types (Uri, Bundle, Intent) onto these
// pure surfaces; the contract module never sees them.

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
