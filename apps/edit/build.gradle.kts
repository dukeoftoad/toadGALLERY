import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// :apps:edit --- toadGALLERY Edit, the separate installable editor APK
// (r29, Issue #14 gate; roadmap Phase 6 first slice; ADR-0002 / ADR-0007).
//
// The prototype proves the Gallery <-> Edit contract end to end: launch
// with a granted content URI -> real modify (pixel adjustment + rotation)
// -> non-destructive save-copy export through MediaStore insert -> the
// gallery's existing change observation refreshes. The app holds NO network
// capability of any kind (contract section 4.5: the editor never requires
// network; same offline-first rules as the gallery) --- enforced below by
// its own merged-manifest INTERNET gate, stricter than the gallery's (there
// is no allowlist mechanism at all: any INTERNET in the merged manifest
// fails the build).

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.dukeoftoad.gallery.edit"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "io.github.dukeoftoad.gallery.edit"
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()

        // The editor app carries its OWN version line, independent of the
        // gallery's (the two apps ship and version separately per the
        // contract; the gallery resolves the contract version from the
        // manifest meta-data below, not from versionCode).
        // versionCode 1 (r29 round): the Issue #14 contract-gate prototype
        // --- the exported EditActivity filtered on
        // org.toadgallery.action.EDIT with the v1 meta-data marker, the
        // contract-exact launch reading (version / source URI via extra or
        // ClipData / save_copy / metadata policy / optional operation hint,
        // through :shared:editor-contract's validator), the real modify
        // step (brightness + contrast LUT adjustment, left rotation, an
        // undo/redo session over the parameter history), and the
        // non-destructive export (full-resolution decode, adjust,
        // compress JPEG q95, MediaStore INSERT only --- the source URI is
        // read-only by construction and is never opened for write, never
        // updated, never deleted --- with the EXIF metadata policy applied
        // through androidx.exifinterface and the applied policy echoed in
        // the result), the three terminal states (RESULT_OK with the
        // output URI, RESULT_CANCELED, RESULT_ERROR with the structured
        // codes), and the zero-network posture with its own merged-manifest
        // INTERNET gate. The one permission is WRITE_EXTERNAL_STORAGE
        // capped at maxSdkVersion=28 --- MediaStore inserts on API 29+ are
        // own-contribution permissionless; pre-Q devices need the legacy
        // write grant at export time only.
        versionCode = 1
        versionName = "0.1.0-edit-alpha"
    }

    buildFeatures {
        compose = true // ADR-0009
    }

    buildTypes {
        debug {
            // The dogfood suffix the gallery's EditorIdentity explicitly
            // accepts (the only suffix beyond the exact release ID).
            applicationIdSuffix = ".debug"
        }
        // Release stays unsigned unless the same env-injected keystore
        // convention the gallery uses (issue #59) is extended here; the
        // prototype's CI path is debug-only.
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        getByName("test").java.directories.add("src/test/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    dependenciesInfo {
        includeInApk = false
    }
}

/**
 * Editor privacy gate (contract section 4.5; ADR-0010 / ADR-0014 posture):
 * the editor NEVER requires network. Unlike the gallery's gate there is no
 * allowlist file at all --- no ADR authorizes a single byte of egress from
 * this app (the gallery's models.ente.com grant belongs to the GALLERY's
 * model downloader; the editor has no models). If a dependency ever injects
 * INTERNET into the merged manifest, this fails the build with instructions.
 */
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
            |*   ./gradlew :apps:edit:dependencies
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
// (the exact fixup the gallery module carries since issue #59).
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
    // --- The versioned contract (ADR-0002 / ADR-0007): one truth, both apps ---
    implementation(project(":shared:editor-contract"))

    // --- Compose (ADR-0009) ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)

    // --- EXIF metadata policy on export (preserve / strip_location /
    // strip_all): androidx.exifinterface, NOT the Aves fork (ADR-0003).
    // A pure on-device metadata library, no network code.
    implementation(libs.androidx.exifinterface)

    // --- Theme resource for the manifest activity ---
    implementation(libs.material)

    // --- JVM tests (the real org.json is NOT needed here; the manifest
    // conformance test parses XML via javax.xml, on the JDK) ---
    testImplementation(libs.junit)
}
