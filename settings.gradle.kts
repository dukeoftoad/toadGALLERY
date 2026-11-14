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

// Editor-branch modules: the standalone Edit APK, its library, and two pure-JVM support modules.
include(":shared:editor-contract")
include(":shared:video-edit")
include(":editor:core")
include(":editor:app")
