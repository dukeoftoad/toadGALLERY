plugins {
    alias(libs.plugins.android) apply false
    // KSP for the Room schema compiler (Phase 4 media catalogue, ADR-0006).
    // Declared here with `apply false` --- same shape as the android alias
    // above --- so the catalog alias resolves at the root and only the
    // :apps:gallery module (the sole Room consumer) applies it.
    alias(libs.plugins.ksp) apply false
}
