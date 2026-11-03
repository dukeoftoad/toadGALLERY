pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
    }
}

rootProject.name = "toadGALLERY"

include(":apps:gallery")
// r29 (Issue #14 gate): the versioned Gallery <-> Edit contract, pure JVM,
// shared by both apps (ADR-0002 / ADR-0007; docs/architecture/editor-contract.md).
include(":shared:editor-contract")
// r29 (Issue #14 gate): the toadGALLERY Edit prototype APK --- the separate
// installable editor app the contract launches (roadmap Phase 6, first slice).
include(":apps:edit")
