plugins {
    alias(libs.plugins.android) apply false
    alias(libs.plugins.android.library) apply false
    // kotlin-jvm is pinned at the root: AGP's built-in Kotlin is already on the
    // classpath, so a per-module version request would fail to resolve.
    alias(libs.plugins.kotlin.jvm) apply false
}
