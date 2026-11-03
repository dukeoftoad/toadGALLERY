plugins {
    alias(libs.plugins.android) apply false
    // KSP for the Room schema compiler (Phase 4 media catalogue, ADR-0006).
    // Declared here with `apply false` --- same shape as the android alias
    // above --- so the catalog alias resolves at the root and only the
    // :apps:gallery module (the sole Room consumer) applies it.
    alias(libs.plugins.ksp) apply false
    // kotlin-jvm for the :shared:editor-contract module (r29, the Issue #14
    // gate). Declared at the root with `apply false` --- same shape as the
    // aliases above --- because AGP 9.2.1 already puts the Kotlin Gradle
    // plugin (its built-in Kotlin) on the classpath with an unknown
    // version: a per-module version request would then fail with
    // "already on the classpath ... compatibility cannot be checked".
    // The root declaration pins 2.3.10 (the catalog Kotlin) once.
    alias(libs.plugins.kotlin.jvm) apply false
}
