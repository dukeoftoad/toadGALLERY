import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

// :editor:app --- the standalone editor APPLICATION shell (Phase 1
// modularization). A thin launcher over :editor:core; owns the applicationId,
// versioning, signing, build types, and the editor's STRICTER privacy gate
// (contract section 4.5: the standalone editor NEVER requires network ---
// there is no allowlist mechanism at all; any INTERNET in the merged
// manifest fails the build).

plugins {
    alias(libs.plugins.android)
}

android {
    namespace = "com.toadgallery.editor.app"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "com.toadgallery.editor"
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        // versionCode 19 (0.4.14-edit-alpha): the last value the old
        // :apps:edit defaultConfig carried (round history in git).
        versionCode = 19
        versionName = "0.4.14-edit-alpha"
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                project.libs.versions.app.build.kotlinJVMTarget.get()
            )
        )
    }

    dependenciesInfo {
        includeInApk = false
    }

    // --- Release signing (r43, the Phase 11 release-engineering round) ---
    // The SAME four TG_* env vars convention as the gallery; absent env =
    // unsigned release by design.
    val releaseKeystoreFile = providers.environmentVariable("TG_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystoreFile.isPresent) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = providers.environmentVariable("TG_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TG_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TG_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // The dogfood suffix the gallery's EditorIdentity explicitly
            // accepts (the only suffix beyond the exact release ID).
            applicationIdSuffix = ".debug"
        }
        // r43: the release variant mirrors the gallery's --- R8 minify +
        // resource shrink; the keeps live in proguard-rules.pro.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// --- The editor privacy gate (contract section 4.5) --- verbatim from the
// old :apps:edit build: NO allowlist, ANY INTERNET in the merged manifest
// fails the build.
abstract class EditInternetPermissionGateTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun enforce() {
        val manifestText = mergedManifest.get().asFile.readText()
        val internetPermission = Regex(
            """<(uses-permission|uses-permission-sdk-\d+)[^>]*android:name\s*=\s*"android\.permission\.INTERNET""""
        )
        if (!internetPermission.containsMatchIn(manifestText)) {
            logger.lifecycle(
                "Editor privacy gate (${variantName.get()}): merged manifest declares no INTERNET permission --- OK"
            )
            return
        }
        throw GradleException(
            """
            |
            |**************************************************************************
            |* EDITOR PRIVACY GATE FAILED (variant '${variantName.get()}')
            |*
            |* The toadGALLERY Edit merged AndroidManifest declares
            |* android.permission.INTERNET. The editor contract (section 4.5 of
            |* docs/architecture/editor-contract.md) forbids network access in this
            |* app unconditionally: no model downloads, no analytics, no telemetry.
            |* There is NO allowlist mechanism --- find the dependency injecting it:
            |*   ./gradlew :editor:app:dependencies
            |* and remove it, or land an ADR that amends the contract first.
            |**************************************************************************
            """.trimMargin()
        )
    }
}

androidComponents {
    onVariants { variant ->
        val variantTitle = variant.name.replaceFirstChar { it.uppercase() }
        val gate = tasks.register<EditInternetPermissionGateTask>("gate${variantTitle}InternetPermission") {
            group = "verification"
            description = "Fails the build if the merged ${variant.name} manifest declares " +
                "android.permission.INTERNET (the editor never requires network; contract 4.5)."
            this.variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        listOf("assemble$variantTitle", "bundle$variantTitle").forEach { anchor ->
            tasks.matching { it.name == anchor }.configureEach { dependsOn(gate) }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(gate) }
    }
}

// AGP 9's built-in Kotlin reports 2.2.10 for compose-group-mapping, a
// version never published; pin the mapping artifact to the catalog Kotlin
// (the exact fixup the old :apps:edit build carried).
configurations.configureEach {
    if (name == "composeMappingProducerClasspath") {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.module.name == "compose-group-mapping"
            ) {
                useVersion("2.3.10")
                because(
                    "AGP 9.2.1 built-in Kotlin reports version 2.2.10, which was never " +
                        "published for compose-group-mapping; 2.3.10 matches the applied " +
                        "Compose compiler plugin (Kotlin 2.3.10)."
                )
            }
        }
    }
}

dependencies {
    implementation(project(":editor:core"))
}
