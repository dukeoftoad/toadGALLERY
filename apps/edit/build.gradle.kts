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
        // versionCode 2 (r30 round): the Phase 6 v1 pipeline round --- the
        // r29 prototype grows into the real editor core: (1) the ADR-0011
        // NON-DESTRUCTIVE SIDECAR: the whole session is one versioned
        // EditRecipe (geometry + tone + colour + grade + detail), serialized
        // JSON in app-private edit-sidecars/ keyed by the SHA-256 of the
        // source URI (atomic tmp+rename writes, corrupt-file self-heal,
        // additive-versioned codec --- unknown keys ignored, out-of-range
        // values clamped, malformed JSON a structured failure), saved on
        // every committed change, restored on relaunch, cleared on export;
        // (2) the GEOMETRY TOOLSET (pure, JVM-tested): normalized CropRect
        // + aspect presets, flips, straighten on an expanded canvas
        // (bilinear, transparent fill), the quad perspective correction;
        // (3) the FULL ADJUSTMENT SUITE (pure, JVM-tested): exposure /
        // brightness / contrast / highlights / shadows as ONE composed
        // per-channel LUT, saturation + temperature/tint as the full-pixel
        // stage, eight-band per-channel HSL (cosine-hat band weights),
        // radial vignette, 3x3 unsharp sharpen --- every zero state skipped
        // for an EXACT identity; (4) the EditPipeline orchestrator with the
        // one documented stage order (preview and export agree by
        // construction); (5) EXPORT CONTROL: JPEG quality slider, PNG,
        // WebP --- the honest encodable set (the platform Bitmap has no
        // HEIC encoder; HEIC stays decode-only), format-aware MediaStore
        // naming; and (6) the
        // editor surfaces for all of it (geometry row, straighten slider,
        // crop aspect presets, the ten-slider adjust section, the band
        // selector + per-band sliders, the export sheet). The pipeline
        // cores are all in pure Kotlin --- the session, sidecar, pipeline,
        // geometry and export plans are JVM-tested end to end.
        // versionCode 3 (r31 round): the Phase 6 exit round --- the
        // per-export metadata-policy choice (the launch request's policy as
        // the privacy floor; the sheet may strip more, never less) and the
        // export preflight (the 48 MP decode guard, the 1.5x-headroom
        // storage check, the documented process-death policy). The on-device
        // half rides the device checklist section 20.
        // versionCode 4 (r32 round): the Phase 6 hardening pass --- the
        // accessibility sweep over the whole editor surface (every slider
        // announces its name + live value via semantics; the HSL rows speak
        // the selected band; the flip toggles are proper FilterChips with
        // 48dp targets and selected-state announcements), the straighten
        // slider follows the drag live like every other slider, and the
        // crop-preset chips commit through the tested pure inverse
        // CropRect.fromPixelRect (5 new JVM tests).
        // versionCode 5 (r33 round): the Phase 7 v2 opener --- the LUT
        // filter system: the pure .cube parser + trilinear sampler +
        // intensity blend (24 JVM tests), the recipe's additive
        // LutSelection with the unresolvable-id tolerance contract (8
        // tests), pipeline stage 10 between the HSL bands and the spatial
        // effects, five project-authored bundled looks generated by the
        // committed deterministic generator (license record in
        // docs/phase-7/bundled-luts.md; 9 conformance tests with
        // hand-verified look probes), and the Filters chip row + intensity
        // slider following the r32 a11y conventions.
        // versionCode 6 (r33 window extension): the user-import .cube flow
        // completing the Phase 7 task-1 bullet --- ImportedLutStore (the
        // parse-before-write verbatim store with derived collision-free
        // ids that can never shadow the bundled set; 10 JVM tests) + the
        // SAF OpenDocument import chip, the merged registry chip row
        // (bundled + imported, reloaded per import/removal), the honest
        // rejection line, and the remove-look affordance for imported
        // looks (a sidecar naming a removed look degrades to no-look by
        // the tolerance contract).
        // versionCode 7 (r34 round): the text tool --- the OFL licensing
        // gate FIRST (four verbatim-bundled SIL OFL 1.1 faces from
        // google/fonts @ 2fe1e8c, each OFL.txt beside its TTF, sha256
        // ledger rows + docs/phase-7/bundled-fonts.md; the prep doc's
        // Noto Sans candidate swapped for Fira Sans --- Noto's only
        // unmodified full form is a 2.0 MB variable, and the CSS API
        // serves latin subsets); the additive TextLayers recipe (absent
        // key = clean pre-r34 sidecar; unresolvable fontId round-trips
        // verbatim and degrades to the default face at render; the
        // tolerant-read codec); the pure TextLayout (anchor fractions x
        // output canvas -> pixel specs, draw order = list order,
        // color-alpha x opacity); the shared TextCompositor (the
        // compositing pass AFTER the whole pixel pipeline --- preview and
        // export through one code path); the Text UI (Add chip, layer
        // chips, text field with per-commit draft granularity, font chip
        // row, size/opacity/rotation sliders with the r32 a11y
        // conventions, named color swatches, remove; drag-to-position on
        // the preview, live-drafted, one commit on release); undo/redo and
        // the sidecar promise extended to text by construction. JVM tests:
        // TextLayersTest, TextLayersRecipeTest, BundledFontsTest.
        // versionCode 8 (r35 round): the sticker tool --- the eight
        // project-authored bundled stickers as IN-CODE vector shapes
        // (integer coordinates in a 0..100 design box, fills only; no
        // image asset, so no sha256 pin and no section-5 ledger row ---
        // the authorship record is docs/phase-7/bundled-stickers.md), the
        // additive StickerLayers recipe with the image-shaped tolerance
        // delta (an unresolvable stickerId round-trips verbatim and
        // renders NOTHING --- there is no default sticker to degrade to;
        // a blank stickerId is a skip alongside a blank id), the pure
        // StickerLayout (the size box, the centre anchor, the
        // opacity-only alpha), the shared StickerCompositor BELOW the text
        // pass (scene objects under captions; vectors from op lists
        // scaled into the box, imported bitmaps fit inside it preserving
        // aspect), ImportedStickerStore (PNG/WebP magic-before-write,
        // verbatim bytes, derived collision-suffixed ids that can never
        // shadow the bundled set; the decode-at-render tolerance), the
        // Sticker UI (registry chip row as ADD actions, layer chips, the
        // r34 slider/drag conventions, one selected layer at a time
        // across the families --- selecting a sticker clears the text
        // selection and vice versa), and the preview/export passes both
        // routing through the one activity resolver. JVM tests:
        // StickerLayersTest, StickerLayersRecipeTest, BundledStickersTest,
        // ImportedStickerStoreTest.
        // versionCode 9 (r36 round): the freehand drawing tool --- the
        // stroke-per-layer model (one gesture = one layer = one undo
        // snapshot; per-layer re-edit of colour/width/opacity/smoothing,
        // the points not editable in v1), the additive DrawLayers recipe
        // (absent key = clean pre-r36 sidecar; points ride as 2-element
        // arrays of output-canvas fractions, thinned at commit to the
        // 512 cap by uniform stride, first/last always kept), the pure
        // DrawLayout (the brush width, the r34 text alpha math, Chaikin
        // corner-cutting round(smoothing/25) -> 0..4 passes at place time,
        // the affine-commute exactness), the shared DrawCompositor BETWEEN
        // the sticker pass and the text pass (annotation over the scene
        // objects, captions above the annotation; a single-point layer
        // renders as a dot of the brush width), the Draw UI (the Draw-mode
        // chip + stroke layer chips under the exclusive duality: paint
        // mode tunes the NEXT stroke through the brush state, a selected
        // layer re-binds the same controls through the live-draft pattern;
        // ONE selection at a time across ALL the families), the paint
        // gesture on the preview (live point list, one commit on
        // release/cancel), and the ONE sweepPendingDrafts() helper for
        // every draft across the families (undo/redo/export/add/remove/
        // switch/mode-exit --- a draft is never silently lost). JVM
        // tests: DrawLayersTest, DrawLayersRecipeTest.
        // versionCode 10 (r37 round): the mosaic/blur regions --- the
        // pixel-stage break from the family shape: the painted regions
        // censor the FULLY graded pixels as the pipeline's FINAL stage
        // (after sharpen; STAGE_ORDER grows the tail), grid-relative
        // like the vignette (exact in the export where the stage grid IS
        // the output canvas; approximate in the preview under an active
        // crop, exactly as the vignette's centre is). The additive
        // RegionLayers recipe (absent key = clean pre-r37 sidecar; an
        // unknown kind is corrupt data --- the skip; points tolerate
        // like the stroke family's, thinned at commit to the shared 512
        // cap by the duplicated-not-coupled stride rule), the pure
        // RegionMask (even-odd scanline fill, pixel centres strictly
        // inside) + RegionEffects (integer-deterministic: the mosaic
        // block average over origin-aligned cells; the linear blur's
        // sliding-window horizontal box over a snapshot), the region
        // paint mode (mutually exclusive with Draw mode; a tap under
        // three samples drops honestly --- a region needs an area), the
        // kind chips + strength slider under the paint duality, and the
        // draft region previewing AT PAINT TIME through the same stage.
        // JVM tests: RegionLayersTest, RegionLayersRecipeTest.
        // versionCode 11 (r38 round, the campaign's closing): the frames
        // --- the single FrameSelection (the LutSelection shape: ONE
        // mounting at a time; an unresolvable frameId round-trips
        // verbatim and degrades to no frame), five PROJECT-AUTHORED
        // parametric frames as pure geometry functions over integers
        // (plain / inner-line / double-line / film with punched sprocket
        // holes / polaroid; the thickness is the r37 kernels' formula
        // max(1, widthPct x minEdge / 1000)), the shared FrameCompositor
        // LAST in the composite order (the mounting over everything),
        // ImportedFrameStore (the sticker store's contract, frame-shaped:
        // PNG/WebP magic-before-write, verbatim bytes, collision-free
        // ids; an imported frame stretches to the full canvas with the
        // honest no-controls note), the look-row UI (None + bundled +
        // imported + import chips; the width slider and swatches for the
        // parametric set), and the undo/redo extension capstone:
        // UndoRedoExtensionTest --- a mixed five-family session, a commit
        // sequence across every family's controls, undo to launch, redo
        // forward, the EXACT recipe asserted at every step. This round
        // also lands the r37 UI-section debt fix (the r37 insertion
        // script wrote the gradle file but never the activity; the
        // section existed only in a Python variable --- the merged r37
        // carried the model, the recipe, the pipeline stage and the
        // gesture scaffolding without its surface, regionMode
        // unreachable; the correction is documented in the r37 delivery
        // record).
        // JVM tests: FramesTest, BundledFramesTest, FramesRecipeTest,
        // ImportedFrameStoreTest, UndoRedoExtensionTest.
        // versionCode 12 (r40, the Phase 10 hardening round): the editor
        // side of the a11y sweep --- the top-bar title carries the same
        // heading semantics the gallery screens landed (the editor's
        // per-control a11y was already at the r32 standard; this closes
        // the one remaining heading gap). No behavior change; no new
        // strings.

        // versionCode 13 (r41, the Phase 10 Edit v3 AI round): the four
        // on-device AI features --- magic erase (the third paint mode;
        // u2netp + MI-GAN), sky replacement (five PROJECT-AUTHORED
        // parametric skies + the import chip; the shared u2netp
        // saliency), bokeh (Depth-Anything-V2-Small int8) and the OPT-IN
        // face retouch (YuNet + the first consent dialog + the settings
        // kill-switch). Four ONNX graphs ship in assets/models/ (the
        // ship-in-APK rule, ADR-0010's revision: 61,613,133 bytes, the
        // sha256-pinned EditAiManifest) and ONNX Runtime 1.31.1 joins as
        // the prebuilt-AAR inference seam (NNAPI-first, CPU fallback;
        // no editor NDK build surface). The merged-manifest INTERNET
        // gate stays green and allowlist-free --- assets carry no
        // permission, and the zero-network posture is unconditional.
        // JVM tests: AiLayersRecipeTest, AiRenderTest, BundledSkiesTest,
        // EditAiEngineTest, EditAiManifestTest, EditAiPrivacyScanTest,
        // EditAiStoreTest, ImportedSkyStoreTest, RetouchConsentTest,
        // UndoRedoAiExtensionTest.
        versionCode = 13
        versionName = "0.4.8-edit-alpha"
// versionCode 14 (r42, the Phase 10 sub-scope B round): the
        // LIGHTWEIGHT VIDEO EDITOR (ADR-0012; docs/phase-10/
        // video-editor.md) --- the edit APK hosts it (the recorded lean;
        // the measured APK delta rides the human-only device rows). (1)
        // the pure :shared:video-edit module (the ExportPlan pattern,
        // video-shaped): the validated VideoEditPlan (trim TimeRange,
        // sorted non-overlapping 0.25x..2x speed segments with the
        // monotonicity rule + the derived output duration, the
        // audioTrackPresent input fact where mute on a no-audio clip is a
        // recorded no-op, the nine ADR-0012 colour parameters),
        // FrameExtractPlan, AnimationExportPlan (the deduplicated frame
        // count + the 32 MiB byte-budget preflight refusing BEFORE any
        // render, the round-pinned caps 64..1280 px width / 2..30 fps),
        // the VideoOutputPlan non-destructive MediaStore policy (INSERT
        // only, IS_PENDING on Q+, the failed-write pending-row cancel,
        // the "-edit" naming rule --- no replace-original path exists),
        // the project-authored GIF LZW encoder + animated WebP muxer, and
        // the VideoExportRunner seam + coordinator --- all JVM-tested;
        // (2) the media3 surface at the pinned 1.11.1 (Apache-2.0, no
        // INTERNET, minSdk 21): ExoPlayer preview + Transformer export
        // consuming the SAME shared shader/uniform struct
        // (VideoAdjustProjection), so preview and export agree by
        // construction (one model, two realizations); the Transformer
        // rides behind the runner seam so only the thin runner touches
        // media3; (3) the launch path: the ADDITIVE editor-contract v2
        // extension (the optional media-kind extra, absent = image so the
        // v1 wire is byte-identical for image launches; the video mimes
        // on this activity's own filter, meta-data contract version 2)
        // consumed by the gallery's launch site; (4) the surface:
        // VideoEditActivity --- trim/mute/speed controls (whole-clip
        // factor previewed exactly via playback speed; the optional region
        // honestly noted as export-applied), the nine adjustment sliders
        // live-previewed through the GL effect, the frame-extract +
        // animation sheets (the preflight refusal shown honestly), the
        // honest "no audio track" note instead of a dead toggle, honest
        // progress + cancellation, the r32 a11y conventions throughout
        // (TalkBack names + live values, 48dp targets, the no-audio note
        // announced); frame extraction + animation frames ride the
        // MediaMetadataRetriever path (documented in the contract doc).
        // Zero new permissions; the INTERNET gate stays green with the
        // media3 artifacts present.
        versionCode = 14
        versionName = "0.4.9-edit-alpha"

        // versionCode 15 (r43, the Phase 11 release-engineering round): the
        // release variant now exists --- env-injected signing per ADR-0018's
        // gallery convention (the same four TG_* vars, the same secrets, the
        // same unsigned fallback so local dev keeps working with no
        // secrets), R8 minify + resource shrink over the new r43
        // proguard-rules.pro so the release ships a mapping.txt like the
        // gallery, and the release workflow builds both apps. This is the
        // FIRST minified build of this APK --- R8 catches from the release
        // run will be recorded and fixed per the merge-gate convention
        // (the repo's standing honesty rule for first-time surfaces).
        versionCode = 15
        versionName = "0.4.10-edit-alpha"

        // versionCode 16 (r46 round): the contract-discovery hardening
        // round --- the application-level contract meta-data fallback
        // (org.toadgallery.contract.EDIT = 2, the rescue marker the
        // hardened gallery resolver reads when the activity-level bundle is
        // unavailable on some PackageManager paths); no editor behavior
        // change. The two activity-level markers stay byte-identical; the
        // gallery-side half of the same fix (GET_META_DATA on the query,
        // the activity-then-application marker precedence, the
        // concrete-mime retry and the package-probe fallback) rides the
        // shared EditorVersionMetadata rule and EditorLaunch.kt.
        versionCode = 16
        versionName = "0.4.11-edit-alpha"
    }

    buildFeatures {
        compose = true // ADR-0009
    }

    // --- Release signing (r43, the Phase 11 release-engineering round) ---
    // The Phase 11 extension of the ADR-0018 convention to the edit app:
    // the SAME four TG_* env vars, the SAME GitHub Encrypted Secrets
    // (TG_RELEASE_KEYSTORE_BASE64 / TG_RELEASE_KEYSTORE_PASSWORD /
    // TG_RELEASE_KEY_ALIAS / TG_RELEASE_KEY_PASSWORD), the SAME release
    // key the gallery signs with --- one key, both APKs, so both update
    // in place over their previous pre-releases. The keystore is NEVER
    // committed (.gitignore excludes *.jks / *.keystore); when the
    // variables are absent --- local dev or CI jobs without the release
    // secrets --- the signing config is deliberately left unwired and
    // assembleRelease falls back to the unsigned APK exactly as before
    // (editor-apk-build.yml's unsigned QA leg keeps working unchanged).
    // providers.environmentVariable keeps this configuration-cache
    // friendly, mirroring the gallery block it copies.
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
        // r43: the release variant now mirrors the gallery's --- R8 minify
        // + resource shrink (the first minified build of this APK; the
        // keeps live in the new proguard-rules.pro), so the release ships
        // a mapping.txt for crash symbolication like the gallery does.
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
 * model downloader, never here). Since r41 the editor BUNDLES its four AI
 * models in assets/models/ (the ship-in-APK rule, ADR-0010's revision) and
 * runs them through ONNX Runtime. The FIRST merge-gate run caught the r41
 * narration's own error: the onnxruntime-android AAR's manifest DECLARES
 * INTERNET (the sample-convenience packaging), so the merged manifest
 * gained it and this gate failed the build --- the honest fix is the app
 * manifest's hard strip (tools:node="remove", the r41 correction note in
 * AndroidManifest.xml): the merged manifest this gate reads, and the
 * device installs, carries NO INTERNET at all. A strip is recorded, never
 * an allowlist; the gate's logic is unchanged and stays the proof.
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

    // --- The pure video-edit plan module (r42): the plans, the nine-row
    // ADR-0012 adjustment projection + shader/uniform struct, the runner
    // seam, the encoders, the MediaStore policy. No Android type crosses
    // it --- the same pure-JVM shape as the editor contract module.
    implementation(project(":shared:video-edit"))

    // --- media3 at the pinned catalog version 1.11.1 (r42, ADR-0012):
    // ExoPlayer + PlayerView for the in-app preview (content URIs only,
    // the gallery viewer precedent), Transformer + effect for the export
    // pipeline. Apache-2.0; verified against the Google Maven POM + the
    // 1.11.1 release notes: no declared permissions at all, so the
    // module's own merged-manifest INTERNET gate above stays green.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

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

    // --- r41: the on-device inference seam (Phase 10 sub-scope A) ---
    // ONNX Runtime as a PREBUILT AAR (the contract's open-item-1 answer:
    // no editor NDK/Rust build surface is added). Apache-2.0; a local
    // inference library that declares no INTERNET permission --- the
    // merged-manifest gate above stays green with it aboard. NNAPI-first
    // with CPU fallback per ADR-0010 section 3; the classes stay behind
    // the EditAiInference facade (EditAiOrtRuntime.kt is the only file
    // that touches them --- pinned by EditAiPrivacyScanTest).
    implementation(libs.onnxruntime.android)

    // --- JVM tests (r30): the REAL org.json for the recipe/sidecar tests
    // --- android.jar's stub throws "not mocked" on the platform classes;
    // test classpath ONLY (the release APK ships the platform org.json,
    // zero new runtime surface). The same convention as the gallery's
    // r23 org-json note in the version catalog.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
