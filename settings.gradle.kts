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
// r42 (Phase 10 sub-scope B): the lightweight video editor's pure plan
// module --- trim/mute/speed plans, the nine-row ADR-0012 adjustment
// projection, the frame/animation plans, the export runner seam and the
// non-destructive MediaStore policy (ADR-0012; docs/phase-10/video-editor.md).
include(":shared:video-edit")
// r29 (Issue #14 gate): the toadGALLERY Edit app --- the separate
// installable editor APK the contract launches (roadmap Phase 6, first
// slice; a release deliverable since the v0.4.5-alpha-r45 both-APK
// close-out, no longer a prototype).
include(":apps:edit")
