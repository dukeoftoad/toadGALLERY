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

// --- Phase 1 modularization (refactor/phase-1-modularization) ---
// The monolith (:apps:gallery + the :apps:edit sourceSets embed) is split
// into standard library/application modules. The old :apps:* includes are
// gone; the editor embed hack (the unified flavor's ../edit sourceSets
// registration + the generated edit-package R) died with it: the editor
// code now reaches the unified build through a standard Gradle project
// dependency on :editor:core.
include(":shared:editor-contract")
include(":shared:video-edit")
include(":gallery:core")
include(":gallery:app")
include(":editor:core")
include(":editor:app")
include(":unified:app")
