import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
    // KSP drives the Room schema compiler for the Phase 4 media catalogue
    // (ADR-0006 persistence layer); see the room-* entries in the catalog.
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.dukeoftoad.gallery"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = "io.github.dukeoftoad.gallery" // provisional --- ADR-0009
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = libs.versions.app.build.targetSDK.get().toInt()
        // 0.4.1-alpha: the Phase 3 completion round --- waves 1+2 (search
        // #62, viewer ops + decoder fallback #63/#65, album-detail ops #64,
        // vault biometric binding + thumbnails #57/#58, favorites +
        // sorting #66) plus the #69 vault thumbnail lock-race guard, on top
        // of the versionCode-8 slices (file ops #30, trash #31, vault #32,
        // viewer #50 + follow-ups).
        // versionCode 12 (r8 round): the vault integrity round-2 fixes
        // (#73: confirmed publication before vault-copy deletion,
        // transactional setup) + the diagnostics logger and the
        // appearance/about settings round.
        // versionCode 13 (r9 round): the two r8 device findings --- the
        // AndroidKeyStore caller-IV seal rejection (provider-IV init in
        // VaultCrypto, the actual "vault PIN creating failed" root cause)
        // and the staggered-grid stale-index key crash.
        // versionCode 14 (r10 round): the r9 device finding --- the
        // biometric sheet crash (androidx.biometric requires non-empty
        // negative text for a BIOMETRIC_WEAK CryptoObject prompt; the
        // prompt now carries a "Use PIN" negative button) --- plus the
        // Phase 4 contract foundation (media identity/eligibility,
        // revision, dates, metadata seams, Room catalogue, change feed,
        // read boundary, decode plan) that has no UI yet.
        // versionCode 15 (r11 round): the build-14 device finding --- the
        // biometric crypto prompt crashed on `Crypto-based authentication
        // is not supported for Class 2 (Weak) biometrics.` (androidx.biometric
        // 1.1.0 rejects a CryptoObject-bound prompt that requests
        // BIOMETRIC_WEAK, unconditionally); the prompt and the readiness
        // probe now use BIOMETRIC_STRONG, Class-2-only devices honestly
        // get PIN-only, plus the audit's Room transactional and
        // schema-export hardening.
        // versionCode 16 (r12 round): the Phase 4 scan driver --- the
        // catalogue + change feed + reconciliation pipeline now runs from
        // app start (per-volume MediaStore walks with trashed rows
        // excluded, generation-token checkpoints, ContentObserver-driven
        // rescans, tombstoning), and the capture-date resolver is adopted
        // into the pipeline: every added/changed row gets its
        // timezone-aware capture date resolved once from EXIF evidence
        // and persisted onto the record (Room v1 -> v2, non-destructive
        // migration). No UI consumes the catalogue yet --- the timeline
        // switch is the next round.
        // versionCode 17 (r13 round): the timeline adoption --- the Photos
        // timeline now orders and groups by the EFFECTIVE capture date
        // (resolved capture date when the indexer produced one, date_added
        // otherwise). While the first scan populates the catalogue the
        // timeline keeps the MediaStore windows unchanged; once populated,
        // windowed reads come from the catalogue (Room v3: displayName +
        // width/height/durationMs/isFavorite ride-along columns), ordered
        // by COALESCE(resolvedCaptureDateMillis, dateAddedMillis) with a
        // local-media-key tiebreak, invalidated by the catalogue's own
        // change flow. Per maintainer directive: no release tags until
        // Phase 5 completes; builds ride CI from main.
        // versionCode 18 (r14 round): the metadata Details view (read
        // path) --- a new screen presenting dimensions, size, MIME,
        // capture date with explicit timezone semantics, camera,
        // exposure triangle and GPS (display-gated on the
        // ACCESS_MEDIA_LOCATION consent state), plus the full EXIF block
        // in a collapsible tree --- and the ACCESS_MEDIA_LOCATION
        // permission flow (Settings section + a pure state machine).
        // versionCode 19 (r15 round): the editable metadata fields ---
        // EXIF write-back through androidx.exifinterface with a pure
        // edit-capability gate, the API 30+ createWriteRequest
        // escalation state machine, and Details-view edit affordances;
        // fields the pinned library physically cannot write (Rating,
        // XPKeywords) render honest later-round states.
        // versionCode 20 (r16 round): the on-device map view --- a
        // bundled Natural Earth 110m world outline (zero network tiles
        // ever), the consent-gated Map tab with offline photo dots, and
        // GPS persistence (Room v3 -> v4: latitude/longitude +
        // provenance via a second ScanIndexHook location indexer).
        // versionCode 21 (r17 round): collections + smart-filter chips
        // --- a pure filter engine projected over both timeline paths
        // (the Photos chips row: All/Photos/Videos/GIFs/Favorites) and
        // the Albums Collections section; the folder chip stays
        // engine-only until the catalogue gains a bucket column.
        // versionCode 22 (r18 round): the format-support audit --- a
        // magic-byte sniffer + capability catalogue with the cited
        // Phase 4 format matrix (every platform claim carries a repo
        // cite or an official-docs URL). With r15-r18 the four Phase 4
        // still-open items are landed; per maintainer directive no
        // release tags until Phase 5 completes.
        // versionCode 23 (r19 round): the code-improvement round --- a
        // whole-codebase audit (data, UI, metadata/vault) consolidated
        // into seven slices: the media_type favorites/search bug fixed
        // (audio leaked in, videos dropped out), the vault
        // unlock-vs-move data-loss race closed (store-level mutex +
        // sweep grace + GCM tail drain), GPS NaN input corruption, the
        // viewer zoom-lock after delete, the Photos permission race,
        // scan indexer per-row isolation, the sniffer major-brand
        // rule, catalogue bucket columns (Room v4 -> v5) with the
        // Photos folder chip surfaced on both paths, honest
        // Albums/AlbumDetail/Search error + permission states,
        // lifecycle-aware video pause, and every grid honoring the
        // columns setting. Per maintainer directive (2026-11-02)
        // releases resume: build 23 ships r13-r19.
        // versionCode 24 (r20 round): the scan-debt round --- the four
        // r19-named data-layer follow-ups closed: the combined
        // single-read EXIF pass (one open + one parse per row serving
        // BOTH the capture-date and location dimensions; the r12/r16
        // standalone indexers removed), the NULL-provenance healing
        // pass (Room v5 -> v6 provenanceIndexedAtMillis stamp; failed
        // rows re-offered bounded-per-start until they succeed), volume
        // hot-plug (ACTION_MEDIA_MOUNTED shell -> idempotent observer
        // registration + a coalesced scan for the new volume), and the
        // vault batch store (one index rewrite per move batch with
        // per-item failure isolation). Per maintainer directive
        // (2026-11-02, after build 23): NO new device builds until
        // Phase 5 completes; this and subsequent rounds ride CI from
        // main only.
        // versionCode 25 (r21 round): the Ente engine pin + model-provisioning
        // core --- the Phase 5 AI engine workstream's first slice: the Ente
        // Rust ML engine vendored at pinned commit 423b2d80 as the root-level
        // engine/ workspace (ente-ml + ente-assets + ente-image, cargo-check
        // gated in CI, AGPL-3.0 recorded in the licensing ledger), and the
        // Kotlin model-delivery core per ADR-0020 (pinned ModelManifest of
        // all 11 assets, streaming size+SHA-256 verifier, verify-then-stage-
        // then-commit provisioner; the HTTP downloader + INTERNET permission
        // + JNI bridge are r22). Per maintainer directive (2026-11-02, after
        // build 23): NO new device builds until Phase 5 completes; this and
        // subsequent rounds ride CI from main only.
        // versionCode 26 (r22 round): the Android engine bridge --- the two
        // r21-named r22 deliverables of ADR-0020: the HTTP model downloader
        // behind the r21 `ModelDownloader` seam, pinned to models.ente.com
        // (the app's first and only network permission, gated by the egress
        // suites), and the Rust JNI bridge crate engine/crates/toad-ml-jni
        // (net-new ours, AGPL-3.0 via the engine workspace), built for
        // Android ABIs by engine/scripts/build-android.sh and packaged ONLY
        // when Gradle runs with -PwithEngine (the withEngine block below) ---
        // the default build stays byte-for-byte the r21 one. Per maintainer
        // directive (2026-11-02, after build 23): NO new device builds until
        // Phase 5 completes; this and subsequent rounds ride CI from main
        // only.
        versionCode = 26
        versionName = "0.4.1-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" // issue #20 privacy suite
    }

    buildFeatures {
        compose = true     // ADR-0009
    }

    // --- Release signing (issue #59) ---
    // The release keystore is NEVER committed --- .gitignore excludes
    // *.jks / *.keystore, so the signing material lives only outside the
    // repo. All four inputs are injected from the environment
    // (TG_KEYSTORE_FILE / TG_KEYSTORE_PASSWORD / TG_KEY_ALIAS /
    // TG_KEY_PASSWORD); the release workflow feeds them from the GitHub
    // Actions secrets TG_RELEASE_KEYSTORE_BASE64,
    // TG_RELEASE_KEYSTORE_PASSWORD, TG_RELEASE_KEY_ALIAS and
    // TG_RELEASE_KEY_PASSWORD. providers.environmentVariable keeps this
    // configuration-cache friendly. When the variables are absent --- local
    // dev or CI jobs without the release secrets --- the signing config is
    // deliberately left unwired and assembleRelease falls back to the
    // unsigned APK as before; only the release pipeline (issue #59) signs.
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
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Wire signing only when the keystore env is present; otherwise
            // this variant stays unsigned by design (see signingConfigs above).
            if (releaseKeystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        getByName("test").java.directories.add("src/test/kotlin")
        getByName("androidTest").java.directories.add("src/androidTest/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    // AGP 9 built-in Kotlin: jvm target mirrors the verified Fossify baseline configuration
    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    dependenciesInfo {
        includeInApk = false
    }
}

// --- Optional Rust engine packaging (r22, ADR-0020) ---
// The engine's Android native libraries --- the JNI bridge libtoad_ml_jni.so
// plus the ONNX Runtime libonnxruntime.so it dynamically loads --- are
// packaged ONLY when Gradle is invoked with -PwithEngine. Without the
// property NOTHING here exists: no task, no extra jniLibs source dir, and
// the build is byte-for-byte the pre-r22 one (no Rust toolchain needed; the
// Kotlin engine facade treats a missing native library as an unavailable
// engine). With it, `buildRustEngine` runs engine/scripts/build-android.sh
// (cargo-ndk release build for arm64-v8a; run the script with --all to also
// stage x86_64), which stages BOTH libraries into the build-dir folder
// rust-jniLibs/ --- never src/main, so the source tree stays clean --- and
// that folder is added below as a jniLibs srcDir. The script needs bash
// (POSIX shell / WSL).
val withEngine = providers.gradleProperty("withEngine").isPresent
if (withEngine) {
    val buildRustEngine = tasks.register<Exec>("buildRustEngine") {
        group = "build"
        description =
            "Builds the vendored Rust engine for Android (cargo-ndk) and stages " +
                "libtoad_ml_jni.so + libonnxruntime.so into the build-dir jniLibs " +
                "folder (r22, ADR-0020)."
        workingDir = rootProject.projectDir
        commandLine("bash", rootProject.file("engine/scripts/build-android.sh").absolutePath)
        // ANDROID_HOME for the script's NDK discovery: the ambient env first
        // (CI runners export it), else local.properties sdk.dir --- the same
        // place AGP itself resolves the SDK from. With neither, the script's
        // own discovery (ANDROID_NDK_HOME, common SDK roots) applies.
        val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
            ?: Properties().apply {
                rootProject.file("local.properties").takeIf { it.isFile }
                    ?.inputStream()?.use { load(it) }
            }.getProperty("sdk.dir")
        if (androidHome != null) {
            environment("ANDROID_HOME", androidHome)
        }
    }
    android {
        sourceSets {
            // AGP 9 forbids Provider instances on the sourceSet API; resolve
            // to a plain File at configuration time (the directory is created
            // by buildRustEngine before this srcDir is ever read).
            getByName("main").jniLibs.srcDir(
                layout.buildDirectory.dir("rust-jniLibs").get().asFile
            )
        }
    }
    // preBuild is registered by AGP after this script evaluates, so anchor
    // the dependency the same way the privacy gate anchors on assemble.
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRustEngine) }
}

/**
 * Privacy gate (issue #20): the core gallery app must never hold
 * `android.permission.INTERNET`. Dependencies can silently inject
 * `<uses-permission>` entries via manifest merger, so this task inspects the
 * MERGED manifest of a variant (the same file that is packaged into the APK)
 * and fails the build when INTERNET appears without an explicit, justified
 * entry in `apps/gallery/internet-permission-allowlist.txt`.
 *
 * Why the merged manifest and not the source manifest: manifest merger can add
 * permissions from any dependency's AndroidManifest; the source alone proves
 * nothing about what actually ships.
 *
 * Enforcement model: docs/phase-2/privacy-gate.md. Privacy posture:
 * product-vision.md (offline-first), ADR-0010 (on-device AI only).
 */
abstract class InternetPermissionGateTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val allowlist: RegularFileProperty

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
                "Privacy gate (${variantName.get()}): merged manifest declares no INTERNET permission --- OK"
            )
            return
        }
        val authorized = allowlist.get().asFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .any { it == "android.permission.INTERNET" }
        if (authorized) {
            logger.warn(
                "Privacy gate (${variantName.get()}): INTERNET is present in the merged manifest " +
                    "and explicitly allowlisted. Revisit docs/phase-2/privacy-gate.md before shipping."
            )
        } else {
            throw GradleException(
                """
                |
                |**************************************************************************
                |* PRIVACY GATE FAILED (variant '${variantName.get()}')
                |*
                |* The merged AndroidManifest declares android.permission.INTERNET, but the
                |* core toadGALLERY app must never hold network permissions (offline by
                |* construction; see docs/phase-2/privacy-gate.md and issue #20).
                |*
                |* Most likely a dependency injected it via manifest merger. Find it:
                |*   ./gradlew :apps:gallery:dependencies
                |* Then remove that dependency --- or, if a future ADR authorizes network
                |* access, add a justified entry to
                |*   apps/gallery/internet-permission-allowlist.txt
                |**************************************************************************
                """.trimMargin()
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantTitle = variant.name.replaceFirstChar { it.uppercase() }
        val gate = tasks.register<InternetPermissionGateTask>("gate${variantTitle}InternetPermission") {
            group = "verification"
            description = "Fails the build if the merged ${variant.name} manifest declares " +
                "android.permission.INTERNET without an allowlist entry (issue #20)."
            this.variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowlist.set(layout.projectDirectory.file("internet-permission-allowlist.txt"))
        }
        // The provider-based manifest input makes Gradle schedule process<Variant>MainManifest
        // before the gate automatically, so anchoring on assemble/bundle/check is cycle-free.
        // `assemble` is the binding CI path (ADR-0013) and also covers install/connected runs.
        listOf("assemble$variantTitle", "bundle$variantTitle").forEach { anchor ->
            tasks.matching { it.name == anchor }.configureEach { dependsOn(gate) }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(gate) }
    }
}

/**
 * Release-only Compose mapping classpath fixup (issue #59).
 *
 * The release variant has never been assembled before (CI builds debug
 * only), so this integration path had not run until the release-signing
 * work. With AGP 9's built-in Kotlin, the Compose compiler plugin builds its
 * composeMappingProducerClasspath dependency as
 * `org.jetbrains.kotlin:compose-group-mapping:<kotlinPluginVersion>`, where
 * the version is AGP's built-in Kotlin (2.2.10) --- a version that was never
 * published for that artifact (its earliest release is 2.3.0). Resolution
 * then dies inside produceReleaseComposeMapping. Pin it to 2.3.10, the
 * Kotlin / Compose compiler toolchain this project actually pins via the
 * version catalog, so the mapping artifact resolves and release builds work.
 */
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
                        "Compose compiler plugin (Kotlin 2.3.10, issue #59)."
                )
            }
        }
    }
}

/**
 * Room schema export (Phase 4 audit finding K): the Room KSP processor
 * writes one versioned JSON snapshot per database version into the
 * directory below (MediaCatalogueDatabase is at version 1), and those
 * snapshots are committed to source control --- every schema change ships
 * with a reviewable diff, and migrations must be judged against them
 * before any schema-changing release. See apps/gallery/schemas/README.md.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // --- Privacy test suite (issue #20) ---
    // JVM policy tests (manifest + allowlist invariants); run in testDebugUnitTest.
    testImplementation(libs.junit)
    // Instrumented egress suite: runtime permission assert + socket-egress probe +
    // cold-launch/tab-walk; run in connectedDebugAndroidTest on the CI emulator.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules) // GrantPermissionRule (Phase 3 media-permission pre-grant)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit) // @RunWith/Assert surface for the instrumented suite
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Compose (ADR-0009) --- canonical UI toolkit for all screens
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core) // TopAppBar icons (issue #29)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    // --- Phase 3: Lifecycle/ViewModel for the Photos timeline state holder (issue #25)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // --- Vault biometric unlock binding (issue #57, ADR-0019): androidx
    // BiometricPrompt + CryptoObject over a user-auth-bound AndroidKeyStore
    // key. Pure on-device auth library: it declares USE_BIOMETRIC (a normal
    // permission merged in from its manifest) but NOT INTERNET, so the
    // ADR-0014 offline-by-construction gate is unaffected. Requires the
    // activity to be a FragmentActivity (see MainActivity).
    implementation(libs.androidx.biometric)
    // --- Phase 3: Paging 3 --- windowed timeline loads + LazyPagingItems grid (issue #28)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    debugImplementation(libs.compose.ui.tooling)

    // --- Image loading (Compose-first per compose-migration.md)
    implementation(libs.coil.compose)
    // On-device video frame decoding for thumbnails (issue #33): a pure
    // decoder artifact, no network code (ADR-0014). Coil 2.x has no decoder
    // auto-discovery, so VideoFrameDecoder is registered explicitly via the
    // app's ImageLoaderFactory (ToadGalleryApplication).
    implementation(libs.coil.video)
    // Animated GIF decoding for the full-screen viewer (issue #50 follow-up):
    // like coil-video, a pure on-device decoder artifact with no network code
    // (ADR-0014) --- coil-gif declares no INTERNET permission, so the
    // merged-manifest privacy gate is unaffected. It is NOT registered on the
    // app-wide loader (ToadGalleryApplication stays as-is): the viewer asks
    // for it per request (ViewerScreen's decoderFactory), so the grid keeps
    // decoding static GIF first frames (issues #33/#34).
    implementation(libs.coil.gif)

    // --- Metadata (ADR-0008: androidx.exifinterface, NOT the Aves fork)
    implementation(libs.androidx.exifinterface)

    // --- Phase 4: Room media catalogue (ADR-0006 persistence layer) ---
    // Persists the media identity/revision cache, metadata-cache state and
    // scan checkpoints (Phase 4 audit, "Room catalogue" contract item).
    // Scope guard: face/CLIP vectors and all other AI outputs live in the
    // engine-owned ML stores, NOT here --- Room stays catalogue-only. Pure
    // on-device storage (no INTERNET permission, ADR-0014 unaffected).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx) // suspend-DAO coroutine dispatch
    ksp(libs.androidx.room.compiler) // schema validation via KSP, not kapt

    // --- In-app video playback for the full-screen viewer (issue #50):
    // ExoPlayer + the views-based PlayerView surface. Local content URIs
    // only (ADR-0006); media3 declares no INTERNET permission, so the
    // merged-manifest privacy gate (ADR-0014) is unaffected.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // --- Theme resource (Theme.Material3.DayNight.NoActionBar for the manifest)
    implementation(libs.material)
}
