import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

// :editor:app — the standalone editor APK shell over :editor:core. Owns the
// applicationId, versioning, signing, and the permission gate: the merged
// manifest may declare exactly android.permission.INTERNET (the on-demand
// AI model downloads); any other permission fails the build.

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
        // versionCode 19 (0.4.14-edit-alpha).
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

    // Release signing via the TG_* env vars; absent env = unsigned release.
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
            // The .debug suffix is the one EditorIdentity accepts beyond the exact release ID.
            applicationIdSuffix = ".debug"
        }
        // Release mirrors the gallery: R8 minify + resource shrink; keeps in proguard-rules.pro.
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

// Editor privacy gate: the merged manifest may declare exactly ONE permission ---
// android.permission.INTERNET, the on-demand AI model downloads. Anything else fails.
abstract class EditPermissionGateTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun enforce() {
        val manifestText = mergedManifest.get().asFile.readText()
        val declared = Regex(
            "<(uses-permission|uses-permission-sdk-\\d+)[^>]*android:name\\s*=\\s*\"([^\"]+)\""
        ).findAll(manifestText).map { it.groupValues[2] }.toSortedSet()
        val unexpected = declared.subtract(ALLOWED_PERMISSIONS)
        val missingInternet = "android.permission.INTERNET" !in declared
        if (unexpected.isEmpty() && !missingInternet) {
            logger.lifecycle(
                "Editor permission gate (${variantName.get()}): exactly the model-download INTERNET grant --- OK"
            )
            return
        }
        val problems = buildList {
            if (missingInternet) {
                add("android.permission.INTERNET is missing (the on-demand AI model downloads need it)")
            }
            unexpected.forEach { add("unexpected permission '$it' (not in the gate allowlist)") }
        }.joinToString("\n") { "* $it" }
        throw GradleException(
            """
            |
            |**************************************************************************
            |* EDITOR PRIVACY GATE FAILED (variant '${variantName.get()}')
            |*
            |$problems
            |*
            |* The toadGALLERY Edit merged AndroidManifest may declare exactly
            |* android.permission.INTERNET --- the single grant the on-demand AI
            |* model downloads need (pinned GitHub release URLs; no analytics,
            |* no telemetry, no other host). Find the dependency injecting the
            |* extra permission and remove it:
            |*   ./gradlew :editor:app:dependencies
            |**************************************************************************
            """.trimMargin()
        )
    }

    private companion object {
        val ALLOWED_PERMISSIONS = setOf("android.permission.INTERNET")
    }
}

androidComponents {
    onVariants { variant ->
        val variantTitle = variant.name.replaceFirstChar { it.uppercase() }
        val gate = tasks.register<EditPermissionGateTask>("gate${variantTitle}PermissionSet") {
            group = "verification"
            description = "Fails the build unless the merged ${variant.name} manifest declares " +
                "exactly android.permission.INTERNET (the on-demand model downloads)."
            this.variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        listOf("assemble$variantTitle", "bundle$variantTitle").forEach { anchor ->
            tasks.matching { it.name == anchor }.configureEach { dependsOn(gate) }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(gate) }
    }
}

// Pin compose-group-mapping to the catalog Kotlin: AGP 9's built-in Kotlin reports
// a version that was never published.
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
