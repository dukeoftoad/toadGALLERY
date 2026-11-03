# toadGALLERY — Comprehensive Development Worklog

**Repository**: `https://github.com/dukeoftoad/toadGALLERY`  
**Account / Maintainer**: `dukeoftoad`  
**Application ID**: `io.github.dukeoftoad.gallery` (debug variant: `io.github.dukeoftoad.gallery.debug`)  
**Source Material**: Extracted strictly and verbatim from the raw 49-turn development conversation transcript (`New File.txt`, lines 1–4684). Zero facts, commits, issues, PRs, or metrics are assumed or invented.

## Executive Summary & Project Trajectory

| Metric / Dimension | Recorded Value in Transcript |
| :--- | :--- |
| **Total Conversation Turns** | 49 turns (`Thought Process` boundaries across 4,684 raw lines) |
| **Phases Traversed** | Phase 0 (Exited) → Phase 1 (Exited) → Phase 2 (Exited) → Phase 3 (Code-complete, 16/16 issues closed in M4) → Phase 4 (Complete, r10–r18 + r19–r20) → Phase 5 (In progress, Ente Rust ML engine port r21–r23) |
| **Latest Main Commit** | `7293610` (PR #96 squash merge — r23 indexing round) |
| **Latest App Version** | `0.4.2-alpha` (`versionCode 27` in r23; debug engine APK `toadGALLERY-0.4.2-alpha-r23-engine-debug.apk`, 71 MB) |
| **Latest Tagged Pre-Release** | `v0.4.1-alpha-r19` (build 23, `93a5d0f`); zero release tags after r19 per maintainer freeze until Phase 5 completes |
| **Room Database Schema** | Version 1 (r10) → v2 (r12) → v4 (r16) → v5 (r19) → v6 (r20, identityHash `46adb27b56f18c9ea5f4bfeb06ecb168`) |
| **Test Suite Growth** | 15 JVM tests (#20) → 28 (#37) → 142 (#56) → 189 (#67) → 233 (#68) → 283 (#74) → 290 (#76) → 457 (#78) → 491 (#82) → 522 (#83) → 561 (#84) → 583 (#85) → 837 (#93) → 855 (#95) + 526 upstream Rust tests + 11 JNI tests (#96) |
| **Architecture Evolution** | v1 (Fossify Kotlin View base + Aves reference) → v2/v2.1 (MI Gallery + Aves + Fossify vision, Jetpack Compose, no Flutter) → v2.2 (100% from-scratch Kotlin + Compose, zero upstream code from Fossify/Aves, GitHub Actions CI) → Phase 5 (Vendored Ente Rust ML crates `ente-ml`, `ente-assets`, `ente-image`, `ente-vecdb`, `ente-photos-ml` under AGPL-3.0 + ONNX Runtime 1.31.0 via JNI) |

## Chronological Turn-by-Turn Engineering Log

### Turn 1 (Lines 1–96) — Repository Bootstrap, Upstream Baselines & Phase 0 Tracking
* **User Prompt**: Uploaded `roadmap.md` (20.3 KB) and requested: *"Create new repo using pat token `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` toadGALLERY START WORKING ON ROADMAP"*.
* **Environment & Repo Discovery**:
  * Verified environment: `git 2.47.3`, `python 3.12`, GitHub API reachable.
  * Verified PAT token for GitHub account `dukeoftoad` (with `repo` scope).
  * Found `dukeoftoad/toadGALLERY` already existed (created minutes prior, public, size 0, stub commit `70f62c68` *"Initial commit"* with description *"web image gallery, upload and share"*).
* **Upstream Baseline Audit**:
  * Shallow-cloned and audited both upstream projects:
    * **Fossify Gallery**: Commit `e9812299` (`v1.14.1`, 2026-10-24), branch `main`, license `GPL-3.0`, 100% Kotlin, app ID `org.fossify.gallery`, `minSdk 26`, `targetSdk 36`, JDK 17. Single-module Kotlin app with built-in editor, widgets, hidden/excluded folders, and Glide.
    * **Aves**: Commit `4f940ae` (`v1.15.8+182`, 2026-10-28), branch `develop`, license `BSD-3-Clause` (+ Apache-2.0 `exifinterface` fork module), Dart 74% / Kotlin 18% / Java 7%, app ID `deckers.thibault.aves`, `compileSdk 37`, JDK 21, Flutter `3.47.8`, and a Foss `libreProfile` flavor.
  * **Key Licensing Discovery**: Aves is `BSD-3-Clause` (not GPL), meaning native code could legally be ported into a GPLv3 app with attribution (later superseded when the project pivoted to zero-code-copying from Fossify/Aves in Turn 5).
* **Deliverables Authored & Pushed**:
  * Authored and pushed commit `c01f073` to `main`: verbatim `roadmap.md`, `LICENSE` (GPL-3.0), `.gitignore`, `README.md`, Phase 0 upstream baselines record, build notes, risk register, feature matrix, 4 draft ADRs (0001–0004), licensing/attribution ledger, and architecture placeholder.
  * Created 10 GitHub milestones (Phase 0–9), issue labels, and 8 Phase 0 issues (`#1`–`#8`). Updated repository description. Advised user to rotate the exposed plaintext PAT.

### Turn 2 (Lines 97–235) — Phase 0 Baseline Builds, Phase 1 Source Audits & Phase 2 Buildable Shell
* **User Prompt**: Re-uploaded `roadmap.md` and instructed: *"you will only build this app by following roadmap.md. take as lead role with experience to do and follow up"*.
* **Phase 0 Baseline Builds**:
  * Provisioned full JDK and Android SDK (`cmdline-tools`) in foreground stages after noticing background `nohup` processes were reaped by the container.
  * **Fossify Gallery v1.14.1 Baseline Build (Issue #1)**: First attempt hit Gradle daemon OOM (`4GB` container limit). Constrained Gradle/Kotlin JVM heaps and ran Kotlin compilation in-process; `BUILD SUCCESSFUL` in 2m31s, producing `gallery-30-foss-debug.apk` (66 MB). Satisfied Phase 0 exit criterion #1.
  * **Aves v1.15.8 Baseline Build (Issue #2)**: Bootstrapped Flutter `3.47.8` submodule (Dart `3.13.7`). Diagnosed two upstream blockers: (1) `platforms;android-37` not published publicly (downgraded `compileSdk` for smoke build), and (2) `apply_flavor_libre.sh` overwrote `pubspec.yaml` with a stale `3.47.7` pin (restored `HEAD` `pubspec.yaml` and ran `gen_flavor_libre.sh` to swap in `aves_services_none`). Hit NDK `28.2.13676358` demand and container disk exhaustion (`9.9 GB` full, `0 bytes` free). Documented exact blocker recipe per Phase 0 rules (*"built or their blockers are documented"*) and extracted Flutter `3.47.8` SDK defaults (`minSdk 24`, `target 36`, `compile 36`).
* **Phase 1 Source Audits & Architecture Deliverables (PR #9)**:
  * Audited Fossify Gallery: path-first Room cache (`full_path` primary key, `media_store_id` added only in last migration), app-level virtual recycle bin backed by `deleted_ts` DB rows, OTG/Android-11 special paths, and `SearchActivity` loading all media to filter by name.
  * Audited Aves: URI-first model (`uri` + MediaStore `contentId` + platform trash API + progressive metadata enrichment via native `MethodChannel`), filter combinator algebra (`set_and`/`set_or`/`dynamic_album`), offline Leaflet maps, stats pages, and crop/rotate/straighten transform editor (`widgets/editor/transform/cropper.dart`).
  * Authored Phase 0 sign-off, Fossify source audit (`docs/phase-1/source-audit-fossify.md`), Aves source audit (`docs/phase-1/source-audit-aves.md`), dependency/license compatibility report (`docs/phase-1/dependency-license-compatibility.md`), audited feature matrix with implementation owners, promoted ADRs 0001–0004 to ACCEPTED, added ADRs 0005 (app identity), 0006 (URI-first data model), 0007 (editor contract), module diagram, data flow, and editor contract spec.
  * Opened and squash-merged **PR #9** (`42fd9981`). Closed completed Phase 0/1 issues, closed Milestones 0 & 1, and seeded Phase 2 issues `#10`–`#14`.
* **Phase 2 App Shell (Issue #10, PR #15)**:
  * Created buildable `:apps:gallery` scaffold inheriting `org.fossify:commons:7.1.3` (`BaseSimpleActivity`, `AppTheme.Base` Material 3 DayNight lineage), implementing `getAppIconIDs`, `getAppLauncherName`, `getRepositoryName`, adding `android.enableJetifier=true`, and fixing Material attribute namespaces (`textAppearanceBodyMedium`/`HeadlineSmall`).
  * Built first `toadGALLERY` APK in 28s: `io.github.dukeoftoad.gallery.debug` `v0.1.0-alpha`, `targetSdk 36`, 30 MB, 4-tab navigation, zero dangerous permissions.
  * Wrote `docs/phase-2/shell-verification.md`, opened and merged **PR #15**, closed Issue `#10`.

### Turn 3 (Lines 236–282) — Roadmap v2 Revision (MI Gallery + Aves Libre + Fossify Vision)
* **User Prompt**: *"the end goal of the project is to make an app cable of editing like how mi gallery editor , fossify like privacy , organise, meta info like aves libre , other features like mi gallery have , etc. if roadmap need changes so be it"*.
* **Gap Audit & v2 Authoring**:
  * Audited `roadmap.md` v1 against the clarified north star and identified 6 major gaps: (1) undersized editor, (2) deferred AI features, (3) shallow read-only metadata, (4) missing MI-style organisational UX, (5) missing video editor, (6) scattered vault/recycle bin.
  * Authored a 7-file v2 bundle (`~2k` lines) in `/home/z/my-project/download/toadGALLERY-v2/`:
    * `docs/product-vision.md` (new north star)
    * `roadmap.md` (v2: expanded to Phases 0–11; editor split into Phase 6 core adjustments/HSL/non-destructive pipeline, Phase 7 LUT filters/annotations/stickers/frames, Phase 8 on-device AI magic erase/sky replacement/bokeh/opt-in face retouch; Phase 4 deepened to EXIF/IPTC/XMP write + offline maps + smart-filter chips; new Phase 5 MI-style Moments/Stories/auto-albums/collage/slideshow; new Phase 9 lightweight video editor; privacy gate + PIN/biometric vault + recycle bin pulled forward into Phases 2–3)
    * `docs/feature-matrix.md` (revised)
    * `docs/integration-decisions.md` (added draft ADRs for on-device AI, non-destructive JSON sidecar + XMP export, and video editor scope)
    * `docs/phase-1/contract-prototype-spec.md` (Gallery↔Edit URI contract prototype spec — Phase 1 task `#7`)
    * `MANIFEST.md` and `CHANGELOG-v2.md`.

### Turn 4 (Lines 283–340) — Roadmap v2.1: Dropping Flutter & Locking Jetpack Compose
* **User Prompt**: *"flutter is necessary ? build on Kotlin and jest-compose if modern else follow up flutter but rethink/ and also use same pat token as i didn't concern. as expiry date been set..!"*.
* **Architectural Updates (v2.1 Bundle)**:
  * Removed Flutter completely from the required project toolchain (ADR-0001 strengthened; Phase 0 Issue `#2` Aves build downgraded from must-build to optional-build).
  * Codified **Jetpack Compose** as the canonical UI toolkit for all new screens with Material 3 + dynamic color, and expanded Phase 2 with explicit Compose BOM + compiler plugin + `ComposeView` interop tasks + `docs/architecture/compose-migration.md`.
  * Updated all 7 files in `/home/z/my-project/download/toadGALLERY-v2/` (`~2.3k` lines) and requested the PAT again since the session environment had reset.

### Turn 5 (Lines 341–442) — Roadmap v2.2: Reference-Only Upstreams, From-Scratch Compose & GitHub Actions CI
* **User Prompt**: *"we are taking only features, ui/ux, designs from all apps for toadGALLERY. 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 " clean up all docs which are dead and stale. another blocker you said about environment which we use github actions as solution."*
* **Execution (Branch `roadmap-v2`, PR #16)**:
  * Configured PAT in git credential store. Inspected repo state and identified conflicts between landed Phase 0/1/2 docs and the new reference-only + Compose directives.
  * Superseded **ADR-0001** → **ADR-0008** (features/UI/UX/designs reference only; zero code copied from Fossify, Aves, or MI Gallery; `licensing-and-attribution.md` provenance ledger emptied; GPL-3.0 voluntary per ADR-0004).
  * Superseded **ADR-0005** → **ADR-0009** (built from scratch in Kotlin + Jetpack Compose).
  * Added **ADRs 0008–0013** (including **ADR-0010** on-device AI, **ADR-0011** non-destructive sidecars, **ADR-0012** video editor scope, **ADR-0013** GitHub Actions CI as canonical build environment).
  * Created `.github/workflows/ci.yml` (JDK 17 + Android SDK `platforms;android-36`, `build-tools;36.0.0`, running `assembleDebug` + `lint` + `test` and uploading artifacts).
  * Added `docs/product-vision.md` and `docs/architecture/compose-migration.md`.
  * Added `SUPERSEDED` / `REFRAMED` notices to 5 stale docs (`docs/phase-0/build-notes.md`, `docs/phase-1/source-audit-fossify.md`, `docs/phase-1/source-audit-aves.md`, `docs/phase-1/dependency-license-compatibility.md`, `docs/phase-2/shell-verification.md`).
  * Opened **PR #16** (16 files changed, +2,092 / −732 lines).
  * Renumbered GitHub milestones to v2.2 Phase 0–11 structure; closed Issue `#2` (Aves baseline build optional); opened 5 new v2.2 issues (`#17` Compose toolchain setup, `#18` MainActivity migration, `#19` Remove commons, `#20` Privacy test suite, `#21` Drop androidphotofilters/jxl-coder, `#22` Risk register update) and updated `#11`, `#12`, `#13`, `#14`.

### Turn 6 (Lines 443–518) — Debugging GitHub Actions CI & Shipping Pure-Compose Shell (`#17`, `#18`, `#19`, `#12`)
* **User Prompt**: *"follow up"* (followed by *"Continue building and follow up"*).
* **CI Debugging (3 Rounds)**:
  * **Run 1 Failure**: `android-actions/setup-android@v3` `packages` input mangled multi-line YAML into `'platforms;android-36
 build-tools;36.0.0'`.
  * **Run 2 Failure**: Dropping the action caused `sdkmanager: command not found` because the runner image does not put Android SDK on `PATH`.
  * **Run 3 Failure**: `setup-android@v3` ran legacy `sdkmanager tools` which no longer exists (`Failed to find package 'tools'`).
  * **Final Fix**: Dropped `setup-android@v3` completely, resolved `sdkmanager` directly from preinstalled `$ANDROID_HOME` (`cmdline-tools`), pre-accepted licenses, installed `platforms;android-36` + `build-tools;36.0.0`, and bumped `setup-java@v4` → `v5`.
* **Compose Migration & Commons Removal (`#18`, `#19`)**:
  * Rewrote `MainActivity` as `ComponentActivity` + `enableEdgeToEdge()` + `setContent { ToadGalleryApp() }`.
  * Created `ui/` Compose package: Material 3 `Scaffold` + `NavigationBar` + `NavHost` (Photos, Albums, Search, Settings tabs with single top-level back stack and state restore) + `StateScreen` composable (`#13` semantics).
  * Created `ui/theme/Theme.kt`: toad-green Material 3 color scheme + Material You dynamic color on Android 12+ (`#11` seed).
  * Removed `org.fossify:commons`, `appcompat`, `fragment`, `core`, `constraintlayout`, and `viewBinding`. Deleted all View-shell XML layouts, fragment, menu, styles, and dimens. Bumped version to `0.2.0-alpha`.
* **Merge & Issue Closure**:
  * CI passed green on **PR #16** (Kotlin `2.3.10` + Compose BOM + AGP `9.2.1`). Squash-merged **PR #16** to `main` as `d04a257`.
  * Closed Issues `#17`, `#18`, `#19`, `#12`; commented progress on `#11` and `#13`.

### Turns 7 & 8 (Lines 519–634) — Local CI-Parity Verification & Privacy Gate Implementation (`#20`)
* **User Prompts**: *"Follow up"* (Turn 7) and *"Why are you stopping? Complete the tasks"* (Turn 8).
* **Environment & Discovery**:
  * Silenced `git` filemode flip noise. Found GitHub PAT had been wiped by an environment reset and the repository was now private.
  * Provisioned local CI-parity environment: Temurin JDK `17.0.21` + Android SDK (`platform 36`, `build-tools 36`) + Gradle `9.5.1`.
* **Privacy Gate & Test Suite (`#20`)**:
  * Authored Gradle task `privacyGateDebug`/`Release` auditing merged manifests across all dependencies, blocking `INTERNET` permission and cleartext traffic, and allowing self-scoped AndroidX `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
  * Authored 15 JVM unit tests across 4 test files (source manifest, build script contract, CI contract, and source network API ban scan) plus `PrivacyPolicy.kt`.
  * Performed negative verification: injected `INTERNET` into manifest → build failed with 3 independent violations; restored manifest → `PASS`.
  * Upgraded `lint` to blocking in `ci.yml`, bumped version to `0.2.1-alpha`, authored `ADR-0014` and `docs/phase-2/privacy-gate.md`, and committed `47c45ff` on branch `phase2-privacy-gate`. Requested fresh PAT to push.

### Turn 9 (Lines 635–745) — Phase 2 Exit & Phase 3 Slice 1: MediaStore Photo Timeline (`#25`, PR #26)
* **User Prompt**: Provided PAT `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`.
* **Duplicate PR Resolution**:
  * Pushed `phase2-privacy-gate` and opened **PR #24**, then discovered `#20` had already been merged on `main` via **PR #23** (commit `e9f458b`, branch `privacy/20-internet-gate`, with `gateDebugInternetPermission`/`gateReleaseInternetPermission` and emulator `PrivacyEgressTest`). Closed duplicate **PR #24** and synced `main`.
  * Triaged remaining Phase 2 issues: reassigned `#11` → Phase 5, `#13` → Phase 3, `#14` → Phase 6.
* **Phase 3 Slice 1 Implementation (Issue #25, PR #26)**:
  * Created kickoff Issue `#25` and implemented MediaStore photo timeline:
    * `AndroidManifest.xml`: Added `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (API 33+) and `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`). `INTERNET` remained absent.
    * Data layer: `MediaItem` value type and `MediaStoreSource` (URI-first per ADR-0006, cursor walk over Images + Video `UNION`, newest-first).
    * UI layer: `PhotosViewModel` (`AndroidViewModel` + `StateFlow` on `Dispatchers.IO`) and `PhotosScreen` (3-state flow: `Loading` → `PermissionNeeded` → `Ready`, 3-column `LazyVerticalGrid` with Coil `AsyncImage`).
    * Version bumped to `0.3.0-alpha` (`versionCode 4`).
  * **CI & Instrumented Test Fix**:
    * Opened **PR #26**. Fast build+lint+test job passed on CI Run #8, but emulator `PrivacyEgressTest` failed because cold launch now showed the permission rationale instead of `state_photos_empty_title`.
    * Added `androidx.test:rules:1.6.1`, `GrantPermissionRule` (pre-granting media permissions), and `waitUntil` + `onAllNodesWithText` to bridge async permission→query→recompose timing.
    * CI Run #9 passed both jobs (Build+lint+test and API 34 emulator suite). Merged **PR #26** (`5db89bd`) and closed `#25`.
  * Filed 8 Phase 3 follow-up issues (`#27`–`#34`): `#27` Photo Picker alt path, `#28` Paging 3, `#29` Albums view, `#30` file ops, `#31` recycle bin, `#32` hidden vault, `#33` video/GIF overlays, `#34` staggered grid.

### Turn 10 (Lines 746–839) — Phase 3 Slice 2: Albums Tab, Folder Detail & Shared Media UI (`#29`, PR #35)
* **User Prompt**: *"Follow up"*.
* **Implementation (Issue #29, PR #35)**:
  * Extended data layer: `Album` value type and `MediaStoreSource.queryAlbums()` (merging Images + Video by `BUCKET_ID` with combined counts and newest-item cover URI) + `queryBucket()` for folder detail.
  * Extracted shared UI components into `ui/media/`: permission requester, permission state composable, and `MediaGrid` (reused across Photos, Albums, and AlbumDetail, removing 144 lines from `PhotosScreen`).
  * Built `AlbumsViewModel`, `AlbumsScreen` (2-column folder-card grid with Coil cover, folder name, pluralized item count), and `AlbumDetailScreen` (top app bar with bucket name + back arrow, filtered `MediaGrid`).
  * Added navigation route `album/{bucketId}` in `ToadGalleryApp.kt` with auto-hiding bottom bar on sub-screens.
  * Added formal **ADR-0014** record to `docs/integration-decisions.md` and updated `README.md` (Phase 2 → Exited, Phase 3 → In progress; version `0.3.1-alpha`, `versionCode 5`).
  * Overcame local Maven Central HTTP 429 burst-throttling using a local-only Aliyun mirror Gradle init script.
  * Opened **PR #35**, passed both CI jobs on Run #11, squash-merged (`173581c`), and closed `#29`.

### Turn 11 (Lines 840–900) — Multi-Agent Wave: Paging 3 (`#28`) & Staggered Grid / Video-GIF Overlays (`#33`, `#34`)
* **User Prompt**: *"Continue on both with sub agents (5 to 7)"*.
* **Parallel Subagent Orchestration (6 Subagents across 2 Tracks × 3 Waves)**:
  * Created isolated worktrees `tg-paging` (Track P: `#28` Paging 3) and `tg-grid-ux` (Track G: `#33` overlays + `#34` staggered grid).
  * **Wave 1 (Data Layer)**:
    * Agent A1 committed `0323a10`: `WindowPagingSource` + native API 30+ window query (`queryTimelineWindow`), 12/12 JVM tests.
    * Agent B1 committed `b32e863`: `width`/`height`/`duration` metadata columns + pure helper functions + `coil-video`, 19/19 JVM tests.
  * **Wave 2 (UI & Integration)**:
    * Agent A2 committed `f60e497`: Paging timeline UI, **ADR-0015**, `v0.3.2-alpha` (`versionCode 6`), 12/12 tests.
    * Agent B2 committed `fdb56fd`: Aspect-ratio staggered grid + video duration/GIF badges, `v0.3.3-alpha` (`versionCode 7`), 19/19 tests.
  * Opened **PR #36** (Paging 3) and **PR #37** (Grid UX).
  * **Wave 3 (Parallel Code Review & Fixes)**:
    * Reviewers committed nit fixes (`217bb31`, `467aa03`) and flagged two critical blockers:
      1. **PR #36 Blocker**: `getRefreshKey` mid-window keys could overlap windows and produce duplicate `LazyGrid` keys → crash. Fixed in `37e3c1d` by snapping refresh keys to the window lattice (13/13 tests).
      2. **PR #37 Blocker**: `coil-video` was inert in Coil 2.x because `VideoFrameDecoder` is not auto-registered. Fixed in `65a07cf` by wiring an `ImageLoaderFactory` `Application` class (`ToadGalleryApplication`).
  * Pre-merged `phase3-paging` into `phase3-grid-ux` locally (`c7033b4`), resolved `build.gradle.kts` and `PhotosScreen.kt` conflicts, integrated `MediaPagingGrid` with staggered layout and metadata columns, verified 28/28 JVM tests locally, and pushed both branches.

### Turns 12 & 13 (Lines 901–1012) — Landing Paging + Grid UX (`#28`, `#33`, `#34`, PR #37) & CI Emulator Retry Hardening (PR #39)
* **User Prompts**: *"Follow up"* (Turn 12) and *"Act wisely and usage should be intelligence with efficient. Make sure you are over thinking...."* (Turn 13).
* **Root-Cause Diagnosis & Merge of PR #37**:
  * Investigated CI Run #12 (`main` install flake: `SplitApkInstallerBase` broken pipe) and Run #15 (`phase3-paging` timeout in `coreFlows_coldLaunchAndTabWalk_noCrash`).
  * Dispatched 2 verification subagents:
    * Subagent 1-a proved the `phase3-paging` failure was a 10s `waitUntil` budget racing the cold-launch Paging refresh chain on a software-emulated KVM-less runner.
    * Subagent 1-b reviewed the net diff of **PR #37** (`LGTM`).
  * Widened the two cold-launch `waitUntil` budgets in `PrivacyEgressTest` from `10s` to `30s` (`3d81b0d`), filed follow-up Issue `#38` (ContentObserver invalidation, append-state footer, idempotent `onPermissionGranted`), and verified CI Run #17 green.
  * Squash-merged **PR #37** (`db835dd`), auto-closing `#28`, `#33`, `#34`, and closed **PR #36** as contained in `#37`.
* **CI Emulator Flake Hardening (PR #39)**:
  * Post-merge Run #18 on `main` hit the `SplitApkInstallerBase` broken-pipe install flake again.
  * Opened **PR #39** wrapping the emulator test invocation in a retry loop. Run #19 failed with `Syntax error: end of file unexpected` because `reactivecircus/android-emulator-runner` wraps inline `script:` blocks in `sh -c "..."`, corrupting inner double quotes.
  * Extracted retry logic into a checked-in bash script and passed a quote-free one-liner in `ci.yml`. Run #20 (PR #39) and Run #21 (`main` @ `1ddd966`) passed green.

### Turns 14 & 15 (Lines 1013–1128) — Parallel Delivery of `#38`, `#13`, `#27` (PRs #40, #41, #42, #43)
* **User Prompts**: *"Complete #13, #38, #27 by using parallel sub agents with pr's(if needed only). Act wisely and use ci yml for compile, debug, etc for faster development by creating linking pr's( so that ci will not cancelled if new commits (if you do more work) done in minutes...!) Use resources wisely and install all needed(in parallel). Follow up"* (Turn 14) and *"Follow up"* (Turn 15).
* **3 Parallel Subagents in Isolated Worktrees**:
  * Dispatched 3 agents with zero-conflict file ownership:
    * Agent A (`#38`, commit `6169a5b` → **PR #40**): Paging follow-ups (ContentObserver invalidation, append footer, idempotent permission callback).
    * Agent B (`#13`, commit `6318a78` → **PR #41**): Screen-state system definition and wiring.
    * Agent C (`#27`, commit `2f03e9f` → **PR #42**): Photo Picker (`PickMultipleVisualMedia` via `activity-compose:1.10.1`) with persistent URI grants and Settings toggle (`PickedPhotosScreen`).
* **CI Compilation & Sequential Rebase/Merge**:
  * CI Run #23 on **PR #41** caught a compile error: `TextOverflow.Middle` does not exist in Compose UI `1.7.6` (BOM `2024.12.01`). Patched and pushed.
  * Merged **PR #40** (`827a3d7`, closing `#38`).
  * Rebased **PR #41** and **PR #42** onto `827a3d7` (resolving additive `strings.xml` conflicts).
  * Merged **PR #41** (closing `#13`).
  * Investigated emulator `PackageManagerInternal.freeStorage` NPE (`DELETE_FAILED_INTERNAL_ERROR` — emulator data partition full under software emulation `-accel off`). Created and merged hardening **PR #43** (larger `-partition-size`, package verifier off, uninstall+trim between attempts).
  * Rebased **PR #42**, passed CI Run #31, and merged **PR #42** (`aaf010f`, closing `#27`).
  * Post-merge Run #33 on `main` hit the TCG software-emulation install flake again; opened **PR #44** with `adb reboot` between attempts before user intervened in Turn 16.

### Turn 16 (Lines 1129–1217) — Retiring Emulator CI (ADR-0016) & Shipping `#21`, `#22`, `#11`, `#30` (PRs #45–#49)
* **User Prompt**: *"Don't run emulator based tests ever. Wasting time is not good..! Commit the changelogs and update the repo for next stages to follow now continue building"*.
* **Removing Emulator CI & Adding Changelogs (PR #45)**:
  * Closed obsolete flake-hardening **PR #44**.
  * Removed the emulator job from `.github/workflows/ci.yml` (~4 min feedback loop: `assembleDebug` + `lintDebug` + `testDebugUnitTest`). Recorded **ADR-0016** (amending ADR-0014: `PrivacyEgressTest` retained as a manual on-device release gate), created root `CHANGELOG.md` (`0.1.0-alpha` → `0.4.0-alpha`), and updated `roadmap.md` to `v2.3`.
  * Opened and merged **PR #45** (`1a76e64`, CI Run #35 green in ~4 min).
* **3 Parallel Build Agents (`#21+#22`, `#11`, `#30`)**:
  * Dispatched 3 parallel subagents and opened **PR #46**, **PR #47**, and **PR #48**:
    * **PR #46 (`#21` + `#22`)**: Marked `androidphotofilters` and `jxl-coder-glide` as DROPPED per ADR-0008 in `dependency-license-compatibility.md`; closed risk `R-14` and added `R-16`/`R-17`/`R-18` in risk register. Merged immediately after green CI.
    * **PR #48 (`#30` File Operations)**: Rename, copy, move, delete, multi-select, undo snackbars, batch progress, API 29+ `RecoverableSecurityException` / `createWriteRequest` / `createDeleteRequest` consent dialogs. Fixed 3 rounds of CI feedback: (1) `android.app.RecoverableSecurityException` import, (2) parameter signature ordering, (3) copy-naming loop format `"Sunset (copy 2).jpg"` instead of `"Sunset (copy) 2.jpg"` + `@Suppress("NewApi")` for pre-Q null guard. CI Run #45 green → merged **PR #48** (closing `#30`).
    * **PR #47 (`#11` Design Tokens)**: Spacing, shape, typography system, WCAG-checked light+dark palettes, edge-to-edge wiring. Fixed CI compile issues (`androidx.compose.foundation.shape.CornerSize` import and comparing `RoundedCornerShape(...).topStart` directly in test). CI Run #48 green → merged **PR #47** (closing `#11`).
  * Opened and merged **PR #49** (`0.4.0-alpha` changelog update). `main` green at CI Run #51.

### Turn 17 (Lines 1218–1412) — Logcat Analysis & Full-Screen Photo/Video Viewer (`#50`, PR #51)
* **User Prompt**: Pasted device logcat (`package: io.github.dukeoftoad.gallery.debug:8, targetSdk 36`, showing `E MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer` and `E GraphicBufferAllocator: Failed to allocate (1 x 1) ... format 56`) and asked: *"And still I can see image not able to open videos/images still in progress?"*.
* **Root-Cause Diagnosis**:
  * Confirmed two real gaps: (1) Tapping a grid cell in `Idle` state only toggled selection — no viewer screen existed yet; (2) Coil's `VideoFrameDecoder` called `getFrameAtTime`, which returned `NULL` on the device for certain video codecs, leaving blank grid cells.
* **Implementation (Issue #50, PR #51)**:
  * Filed Issue `#50` and added `androidx.media3` (`1.11.1`) dependencies (`media3-exoplayer`, `media3-ui`, `media3-common`).
  * Built pure-JVM `ViewerWindowState` (tested without Robolectric per ADR-0016), `ViewerViewModel`, and `ViewerScreen`: edge-to-edge black canvas, hidden system bars (`LocalView`), horizontal pager swiping between items, tap-to-toggle top chrome bar (`back` + `"n of m"` counter), and in-app video playback via `ExoPlayer` (local content URIs only, zero `INTERNET` permission) with honest `"Can't play this video"` fallback UI on playback errors.
  * Added `ic_video_fallback.xml` poster drawable when video thumbnail decoding fails in `MediaThumb`.
  * Wired item tap callbacks with index hand-off across `MediaGrid`, `MediaPagingGrid`, `PhotosScreen`, `AlbumDetailScreen`, and `ToadGalleryApp`.
  * Fixed 3 issues during review/CI: (1) passed `BoxScope` modifier to `ViewerTopBar`, (2) removed illegal `--` inside XML comment in `ic_video_fallback.xml`, (3) added missing `LocalView` import.
  * CI Run #54 green → squash-merged **PR #51** (`96f3e31`, closing `#50`).

### Turn 18 (Lines 1413–1519) — Fixing 19 Masked Lint Errors (PR #52), Recycle Bin (`#31`, PR #53) & Vault Crypto Core (`#32` Slice 1/2, PR #54)
* **User Prompt**: *"Fix lint issues as per lastest ci build, follow up on #31, #32 and further works as per plan. Continue"*.
* **Stream 1 — Lint Hard Gate (PR #52)**:
  * Inspected CI Run #55 log and discovered `continue-on-error: true` was masking **19 `NewApi` errors** and 32 warnings (17 in `FileOpsViewModel.kt`, 2 in `MediaFileOps.kt` where API 30 consent builders were referenced under a runtime Q-guard).
  * Added documented `@Suppress("NewApi")` on the Q-guarded functions, annotated the two consent builders with `@RequiresApi(Build.VERSION_CODES.R)`, and removed `continue-on-error: true` from `.github/workflows/ci.yml`.
  * Merged **PR #52** (`e025357`).
* **Stream 2 — Recycle Bin (Issue #31, PR #53)**:
  * Implemented MediaStore system trash integration (`API 30+` via `MediaStore.createTrashRequest`, ~30-day platform retention; Android 10 retains permanent delete):
    * Data layer: trashed media query in `MediaStoreSource` using raw AOSP `android:query-arg-match-trashed` contract constants + `isVideo` derived from row `MEDIA_TYPE`, plus pure byte-size formatter (`B`/`KB`/`MB`/`GB`/`TB`) with 13 JVM unit tests.
    * Ops & UI layer: `TrashViewModel`, `TrashScreen` (restore, delete permanently, or empty all via single consent request with pluralized snackbars), Settings entry row showing item count + total size, and nav route in `ToadGalleryApp`.
  * Fixed CI feedback (raw bundle constants, `@PluralsRes` typed events, integer byte formatting). CI Run #61 green → squash-merged **PR #53** (`4cef62e`, closing `#31`).
* **Stream 3 — Hidden Vault Slice 1/2 (Issue #32, PR #54)**:
  * Authored **ADR-0017** (vault cryptography specification) and pure-JVM `VaultCrypto`: PBKDF2 PIN digests + streaming-capable AES-256-GCM sealing for large videos, backed by 13 JVM unit tests.
  * CI Run #63 green → squash-merged **PR #54** (`02b9ca4`).

### Turn 19 (Lines 1520–1668) — Vault Storage/UI (`#32` Slice 2/2, PR #55) & Viewer Pinch-Zoom / Animated GIFs (PR #56)
* **User Prompt**: *"Follow up and complete Working end-to-end browsing and basic media operations . use parallel agents"*.
* **Parallel Subagent Execution (Worktrees `tg-vault` & `tg-zoom`)**:
  * Wrote brief files to disk to avoid Task service prompt-size timeouts and launched two parallel agents:
    * **Vault Agent (`#32` Slice 2/2, commit `89b4d90` → PR #55)**: Built `VaultStore` (crash-safe atomic write ordering, PIN wipe), strict index serialization, `AndroidVaultKeys` (KeyStore wrapper), `VaultRepository` (move-to-vault via consent flow + restore), `VaultViewModel` (`ON_STOP` lifecycle auto-lock via `LocalLifecycleOwner`), `VaultScreen` (PIN setup/unlock + sealed item list), `ic_vault` drawable, and 26 new JVM store/index tests (39 vault tests total). Fixed `LocalContentColor.current` compile error caught by CI Run #65. CI Run #67 green → squash-merged **PR #55** (`c12156d`, closing `#32`).
    * **Zoom/GIF Agent (`#50` Follow-ups, commit `6285fda` → PR #56)**: Built custom zoom gesture detector, pure zoom/pan/double-tap math core (18 JVM tests, verified with independent Python float32 mirror), `coil-gif` integration for animated GIFs/WebPs in `ViewerScreen`. Rebased onto `c12156d`, resolved `CHANGELOG.md` and `roadmap.md` conflicts, passed CI Run #69 → squash-merged **PR #56** (`75e841b`).
  * Filed follow-up Issues `#57` (biometric binding) and `#58` (vault thumbnails). Main CI Run #70 passed with 142 JVM tests total.

### Turn 20 (Lines 1669–1795) — Signed Pre-Release APK Pipeline (`#59`, PRs #60, #61, Release `v0.4.0-alpha-r3`)
* **User Prompt**: *"Follow up above indicate plans and then add release apk build for pre release(testing). Not unsigned but signed under release ci yml(new). Use sub agents parallel for faster development."*
* **Keystore & GitHub Secrets Setup**:
  * Closed Milestone M4 (10/10 initial issues) and opened Issue `#59` for the signed release pipeline.
  * Generated a dedicated RSA-2048 PKCS12 release keystore (valid through 2051, stored in `work/release-keystore/` outside git; pinned certificate SHA-256 digest `8a4640e0...`).
  * Uploaded 4 GitHub Actions repository secrets via `pynacl`: `TG_RELEASE_KEYSTORE_BASE64`, `TG_RELEASE_KEYSTORE_PASSWORD`, `TG_RELEASE_KEY_ALIAS`, `TG_RELEASE_KEY_PASSWORD` (set equal to store password per PKCS12 single-password requirement).
* **3 Parallel Subagents (`#59`, PR #60)**:
  * **Agent A (Gradle Signing)**: Configured `signingConfigs.release` in `apps/gallery/build.gradle.kts` reading env vars with unsigned fallback for local dev, and worked around AGP `9.2.1`'s unpublished `compose-group-mapping:2.2.10`.
  * **Agent B (Release CI)**: Created `.github/workflows/release.yml` triggered on `v*` tag push or `workflow_dispatch` → runs `assembleRelease` → enforces hard `apksigner verify` certificate-digest gate → publishes GitHub pre-release with signed `.apk`, `.sha256`, and R8 `mapping.txt`.
  * **Agent C (Docs)**: Authored **ADR-0018** (release signing architecture) and updated `CHANGELOG.md`, `README.md`, and `roadmap.md`.
  * Integrated and merged **PR #60** (`e926947`, closing `#59`).
* **Workflow Fix (PR #61) & First Pre-Release (`v0.4.0-alpha-r3`)**:
  * Manual dispatch of `release.yml` returned HTTP 422 because `${{ runner.temp }}` is invalid in job-level `env:`. Fixed line 59 to use step-level `$RUNNER_TEMP` in **PR #61**, merged, and dispatched Release Run #3.
  * Published **`v0.4.0-alpha-r3`**: downloaded the 3.4 MB signed APK via API, verified SHA-256 checksum and `apksigner` certificate digest end-to-end.

### Turn 21 (Lines 1796–2031) — Reopening Phase 3: 10-Bit HEVC Fix, Search Tab, Viewer/Album Ops, Biometrics & Favorites (`#57`, `#58`, `#62`–`#66`, PRs #67, #68)
* **User Prompt**: Pasted device logcat from Xiaomi (`peux, 2201116PI, Android 36`) showing `ExoPlayerImplInternal: Playback error ... video/hevc, hvc1.2.4.H123.90, res=1280x534, 10bit ... format_supported=NO_EXCEEDS_CAPABILITIES` (`Decoder failed: c2.qti.hevc.decoder`) and instructed: *"now don't mark phase 3 as completed. Search tab still need to complete(phase 5 for advanced but phase has should be timeline as indicated), basic operations like rename, file info, delete, other basic operations are still pending, hidden vault issues, etc"*.
* **Milestone Reopened & Issue Filing**:
  * Reopened Milestone M4 (Phase 3) and created Issues `#62`–`#66` alongside existing `#57` and `#58`.
* **Wave 1 — 3 Parallel Subagents (PR #67, Release `r4`)**:
  * **Search Agent (`#62`)**: Built `SearchScreen` + `SearchViewModel` + `SearchQuery` (debounced `DISPLAY_NAME LIKE` search with `All`/`Photos`/`Videos` filter chips, grid-to-viewer hand-off, and empty/no-results/permission states). Moved `SearchQuery` to data layer during integration.
  * **Viewer Ops & Playback Agent (`#63`, `#64`)**: Enabled `DefaultRenderersFactory(context).setEnableDecoderFallback(true)` in `ExoPlayer` so 10-bit HEVC (`Main 10`) falls back from hardware `c2.qti.hevc.decoder` to software decoder; added `Retry` playback error UI; added Viewer bottom/top actions for **Info** (file details modal sheet), **Share** (`ACTION_SEND`), **Rename**, and **Delete**.
  * **Album Ops Agent (`#65`)**: Wired full multi-select file operations (`FileOpsViewModel`: copy, move, rename, delete/trash, share) into `AlbumDetailScreen` with album-grid refresh.
  * Verified 189 JVM tests green, bumped to `versionCode 9`, merged **PR #67**, and published signed pre-release **`v0.4.0-alpha-r4`**.
* **Wave 2 — 2 Parallel Subagents (PR #68, Release `r5`)**:
  * **Vault Biometrics & Thumbnails Agent (`#57` + `#58`)**: Authored **ADR-0019**; implemented opt-in biometric vault unlock (`androidx.biometric:1.1.0` with `setUserAuthenticationRequired` KeyStore key and PIN fallback) and in-memory decrypted vault thumbnails (`VaultThumbnailCache` / `VaultThumbnailPolicy`, zero plaintext on disk, cleared on lock).
  * **Favorites & Sorting Agent (`#66`)**: Implemented Favorites (viewer heart toggle with `MediaStore.createFavoriteRequest` consent round-trip on API 30+, heart badges on grid cells, pinned Favorites album in `AlbumsScreen`) and user-facing sort order toggle (`Newest first` / `Oldest first`) on Photos timeline and Albums.
  * Verified 233 JVM tests green, bumped to `versionCode 10`, updated `roadmap.md` to *"code-complete pending device validation"*, merged **PR #68** (`d02c080`), and published signed pre-release **`v0.4.0-alpha-r5`**.

### Turns 22 & 23 (Lines 2032–2206) — Codebase Discovery, Vault Thumbnail Lock-Race Fix (`#69`, PRs #70, #71, #72) & Release `v0.4.1-alpha-r7`
* **User Prompts**: *"Take a lead role and act as expert and discover current codebase repo and completed the partial, incomplete, pending works upto phase 3. Before that proposed plan for approval and Q/A if needed."* (Turn 22) and provided PAT `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` after Q/A confirmation (Turn 23).
* **Discovery & Audit**:
  * After syncing `main` (`d02c080`), verified that Waves 1 & 2 (`#67`, `#68`) had landed, but audited `VaultViewModel.ensureThumbnails()` against the user's Q/A race-guard requirement and found a real bug: `ensureThumbnails()` checked `is Unlocked` before `decodeThumbnail(entry)`, but **did not re-check after async decode before publishing the bitmap**, nor did `onBackgrounded()` cancel the in-flight decode `Job`.
* **Implementation & Release (`#69`, PRs #70, #71, #72)**:
  * Filed Issue `#69` and extracted pure `VaultThumbnailGate` in `VaultThumbnailPolicy.kt` with a monotonic lock-generation token, wholesale decode `Job` cancellation in `onBackgrounded()`, and an immediate pre-publish generation + `Unlocked` state re-check, pinned by 7 new JVM tests.
  * Merged **PR #70** (`60b7c0f`, closing `#69`).
  * Bumped version to `0.4.1-alpha` (`versionCode 11`) in **PR #71** and merged.
  * Cut aligned pre-release **`v0.4.1-alpha-r7`** via `workflow_dispatch` (after deleting a misaligned manually-tagged `r1`/`-r6` release), verified SHA-256 and embedded UTF-16 `0.4.1-alpha` manifest string, and committed `docs/phase-3/device-validation-checklist.md` (16 closed issues in M4).

### Turn 24 (Lines 2208–2274) — Fixing Two Critical Vault Integrity Defects (PR #73)
* **User Prompt**: Reported two unfixed Vault integrity defects:
  * **Defect A**: `VaultRepository.restore()` ignored the return value of `resolver.update(newUri, settled, null, null)` and deleted the vault entry even if `update()` returned `0`.
  * **Defect B**: `VaultStore.setup()` wrote `pin.bin` then `index.bin` non-transactionally while `isConfigured()` checked only `pin.bin`, leaving a wedged vault if the second write failed, with no failure-injection tests.
* **Fixes & Verification (PR #73)**:
  * **Fix A (`VaultRepository.restore()` & `MediaFileOps.copy()`)**: Captured `resolver.update()` row count; treated `<= 0` rows as an IO publication failure that cleans up the pending MediaStore row, preserves the sealed vault copy, and returns `FAILED`. Applied the same check to `MediaFileOps.copy()` line 184.
  * **Fix B (`VaultStore.setup()` & `isConfigured()`)**: Implemented two-phase transactional commit: staged both ciphertexts to `.tmp`, renamed `index.bin` first and `pin.bin` last, with full rollback on any failure. Updated `isConfigured()` to require both `pin.bin` and `index.bin`, and added self-healing for one-file residue on subsequent `setup()`.
  * Added 4 failure-injection tests in `VaultStoreTest` (index-commit failure, PIN-commit failure with index rollback, two-file marker check, one-file residue self-heal).
  * Merged **PR #73** (`aaa9ea2`) after green CI.

### Turns 25 & 26 (Lines 2277–2471) — Ring-Buffer Diagnostics, StrongBox Fallback, Ente Appearance Settings & About Page (PRs #74, #75, Release `v0.4.1-alpha-r8`)
* **User Prompts**: Uploaded `Screenshot_20261031-230435_Ente Photos.png`, `toadGALLERY_Phase4_Compatibility_Audit_and_Phase5_Ente_AI_Plan.docx` (312.3 KB), and logcat (`package: io.github.dukeoftoad.gallery:11`), stating: *"Still vault pin creating failed. Need to find the bugs and also improve debug logs for better finding. Implement attached features with ui buttons under setting tab under appearance section , about page etc"* (Turn 25), followed by PAT in Turn 26.
* **Diagnosis & Implementation (Branch `tg-r3-device-round` → PRs #74, #75)**:
  * Diagnosed why the logcat had zero vault lines: `VaultViewModel.setup()` and `attemptUnlock()` swallowed all exceptions into a generic toast without logging, and custom ROMs (running `PIF`) could throw `ProviderException` during StrongBox key generation where `AndroidVaultKeys` only caught `StrongBoxUnavailableException`.
  * **Diagnostics Infrastructure**: Built `DiagLog` (400-line in-memory ring buffer mirroring to `logcat` under tags `ToadDiag`, `ToadVault`, `ToadOps`, `ToadApp`), instrumenting vault setup phases, keystore reuse/fallback, unlock/sweep counts, restore publish counts, and full exception cause chains (privacy-scanned to ensure zero PIN, key, or filename leaks).
  * **StrongBox Fallback Hardening**: Hardened `AndroidVaultKeys` so any `GeneralSecurityException` / `RuntimeException` on StrongBox key generation falls back to TEE KeyStore and logs the fallback.
  * **Settings → Appearance (Ente-Style)**: Added `AppearanceSettings` (`Layout`: Grid ↔ Collage cards; `Photos per row`: 2–6 stepper; `Group by`: None / Day / Month with full-width date headers in `TimelineRows`), applied across `PhotosScreen` and `AlbumDetailScreen`.
  * **About & Diagnostics Screen**: Built `AboutScreen` showing app version/device info, privacy posture, credits, GitHub link, live Vault state probe (`pin`/`index`/`binding`/`blobs`/`keystore-key`/`writability`), and live log ring buffer with **Copy diagnostics** button.
  * **Phase 4 Audit Doc**: Converted and committed `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md` and linked it from `roadmap.md`.
  * Verified 283 JVM tests green (`versionCode 12`), merged **PR #74** (`b2be3b5`) and **PR #75** (`4c12be3`, CHANGELOG + checklist §§9–11), and released **`v0.4.1-alpha-r8`**.

### Turn 27 (Lines 2473–2729) — Solving the Real-Device Vault IV Bug & Stale Paging Grid Crash (PR #76, Release `r9`)
* **User Prompt**: Pasted output from the new `Copy diagnostics` button on build 12 (`2201116PI · Android 36`):
  ```text
  11-01 03:04:33.320 I/ToadVault: setup: pre-state root=true pin=false index=false blobs=0
  11-01 03:04:33.674 I/ToadVault: key: existing keystore key reused
  11-01 03:04:33.682 W/ToadVault: setup: failed
   -> java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted
  ```
  Plus a main-thread crash stack trace: `java.lang.IndexOutOfBoundsException: Index: 60, Size: 60` in R8-obfuscated frames (`zp0.n`, `ad1.b:145`, `jk1.r:109`).
* **Root-Cause Diagnosis & Fixes (PR #76, `versionCode 13`)**:
  * **Fix A — AndroidKeyStore Caller-Provided IV Bug (`VaultCrypto.kt`)**:
    * `DiagLog` immediately exposed the root cause of all prior PIN setup failures: `VaultCrypto` initialized `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(128, iv))` with a caller-generated IV. While software `SecretKeySpec` in JVM unit tests permits caller IVs, AndroidKeyStore enforces `randomizedEncryptionRequired = true` by default and throws `InvalidAlgorithmParameterException: Caller-provided IV not permitted`.
    * Updated `VaultCrypto` encryption to call `cipher.init(Cipher.ENCRYPT_MODE, key)` (letting the provider generate the random IV and reading `cipher.iv`), with fallback to caller IV only for providers that require an explicit spec. Added provider-IV unit tests in `VaultCryptoTest`.
  * **Fix B — Stale Paging Index Crash (`MediaPagingGrid.kt` / `TimelineRows.kt`)**:
    * Retraced the R8 stack trace using the release `mapping.txt` and confirmed `MediaPagingGrid.kt:145`: the `LazyVerticalStaggeredGrid` `key`/`span` lambdas called `items[row.index]` (live `LazyPagingItems.get`, which throws on out-of-bounds) while `rows` was derived from snapshot state lagging by one frame (`Index: 60, Size: 60`).
    * Added pure `timelineCellKey` helper in `TimelineRows.kt` using bounds-safe `itemSnapshotList.getOrNull(index)` for `key`/`span` lambdas, and added guarded `cellItem` presenter read in the content lambda to preserve Paging 3 viewport load hints without throwing. Added regression tests in `TimelineRowsTest` covering the exact `Index: 60, Size: 60` scenario.
  * Verified 290/290 JVM tests green, bumped to `versionCode 13`, merged **PR #76** (`28e6a6a`) + docs PR, and tagged release **`v0.4.1-alpha-r9`**.

### Turns 28, 29 & 30 (Lines 2731–3241) — Biometric Negative-Button Fix, Phase 4 Foundation (4 Parallel Agents) & CI Metaspace Fix (PRs #78, #79, #80, Release `v0.4.1-alpha-r10`)
* **User Prompts**:
  * Turn 28: Uploaded `toadGALLERY_Phase4_Compatibility_Audit_and_Phase5_Ente_AI_Plan.docx` and build 13 logcat showing Vault setup succeeding (`ToadVault: setup: committed (pin+index)`, `unlock: swept=0 entries=2`) followed by a crash on biometric prompt initiation: `java.lang.IllegalArgumentException: Negative text must be set and non-empty.`, and instructed: *"fix it and then start working on phase 4 as per attached plan and update roadmap.md . use parallel agents"*.
  * Turn 29: *"Follow up"*.
  * Turn 30: Pasted CI failure lines (`> Task :apps:gallery:lintVitalAnalyzeRelease FAILED`, `> Task :apps:gallery:minifyReleaseWithR8 FAILED, so poll is taking long find the bugs and flaws`).
* **Biometric Crash Fix (`VaultBiometrics.kt`)**:
  * Diagnosed: `BiometricPrompt.PromptInfo.Builder` with a `CryptoObject` cannot use device credentials and therefore requires `.setNegativeButtonText(...)`. Extracted `buildPromptInfo` and set the negative button text to `"Use PIN"` (matching the silent PIN-fallback behavior on cancel), pinned by 2 reflection-based JVM unit tests on the builder fields.
* **Phase 4 Foundation (Base Keel + 4 Parallel Subagents → PRs #78, #79)**:
  * Authored shared keel types (`MediaIdentity`, `MediaEligibility`, `EligiblePublicMedia`) and launched 4 parallel subagents:
    * **Agent A (Identity, Revision & Dates)**: Volume-aware `MediaIdentity` (`content://media/<volume>/...`), `MediaRevision.diff()` invalidation classes, and timezone-aware `MediaDateResolver` (`CaptureDateSource` precedence hierarchy).
    * **Agent B (Metadata & Format Matrix)**: `MetadataAvailability` states, EXIF rational/GPS math seam (`ExifContract`), ISO BMFF/MP4 video container box parser, and format capability matrix.
    * **Agent C (Room Catalogue & Change Feed)**: Room `2.8.5` + KSP `2.3.10` setup (`MediaCatalogue`, `RoomMediaCatalogue` schema v1), self-healing tombstoning `CatalogueReconciler`, and `MediaChangeFeed` (generation tokens + `ContentObserver`).
    * **Agent D (Read Boundary & Canonical Decode)**: Descriptor-based `MediaReadHandle` accepting only `EligiblePublicMedia` (making vault items unrepresentable at AI/metadata call sites) and `CanonicalImageDecoder` deterministic subsample/memory-budget planner.
  * Fixed KSP/Room version pins (`Room 2.8.5`, bare `KSP 2.3.10`), Huawei/Aliyun mirror resolution, 3 compile issues, and 4 test edge cases. Verified **457 JVM tests green** (+174 new) and merged **PR #78** (code) and **PR #79** (docs + `roadmap.md` Phase 4 status & phase-map reconciliation, `versionCode 14`).
* **CI Metaspace Exhaustion Fix (PR #80) & Release `v0.4.1-alpha-r10`**:
  * Investigated Release Run #10 failure on CI and found `"Daemon will expire after the build after running out of JVM Metaspace"` in the logs: `gradle.properties` still had Phase 0 caps (`-Xmx1792m -XX:MaxMetaspaceSize=384m`), which could not hold KSP + Room compiler + R8 + `lintVitalAnalyzeRelease` in a single JVM.
  * Raised project `gradle.properties` to `-Xmx2048m -XX:MaxMetaspaceSize=768m` and workflow timeout to 45 minutes in **PR #80**, verified locally with project caps active, merged **PR #80**, re-tagged `v0.4.1-alpha-r10` (Run #11), renamed release assets from `-r11` to `-r10` for consistency, and verified SHA-256 + signature digest `8a4640e0`.

### Turns 31 & 32 (Lines 3242–3370) — Resolving User's Draft PR #81 & Shipping Build 15 Hardening (PR #82, `r11`)
* **User Prompts**: *"Follow up and checkout #81 pr and launch sub agents to resolve"* (Turn 31) and *"Pat token `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`"* (Turn 32).
* **Investigation of PR #81 (`docs/crash-investigation-draft`)**:
  * Read user's draft PR #81 containing 4 crash findings (including build-14 logcat crash `IllegalArgumentException: Crypto-based authentication is not supported for Class 2 (Weak) biometrics`) and 13 repo-wide audit items (`A`–`M`).
  * Verified `androidx.biometric:1.1.0` source (`BiometricPrompt.java` / `AuthenticatorUtils.java`): `authenticate(PromptInfo, CryptoObject)` unconditionally rejects any prompt whose allowed authenticators include `BIOMETRIC_WEAK` (checking requested flags, not device sensor class).
* **8 Parallel Subagents (Verification + Implementation → PR #82, `versionCode 15`)**:
  * **Finding 1 / Audit L (Biometric Crash Fix)**: Changed `VaultBiometrics` prompt and readiness probe from `BIOMETRIC_WEAK` to `BIOMETRIC_STRONG`; devices with only Class 2 sensors cleanly degrade to PIN-only unlock.
  * **Audit A (Vault Backup Exclusion)**: Added `<data-extraction-rules>` and `<full-backup-content>` XML rules excluding the `vault/` directory from cloud/device-transfer backups while keeping `allowBackup="true"` for preferences.
  * **Audit C (Per-Row Revision Token)**: Fixed re-index storm bug by comparing per-row `generationModified` instead of volume-level generation token.
  * **Audit D (Room Transactions)**: Wrapped multi-step reconciler mutations in `@Transaction` / `withTransaction`.
  * **Audit K (Room Schema Export)**: Enabled KSP `room.schemaLocation` export and committed `apps/gallery/schemas/.../1.json`.
  * **Audit J (Decode Overflow Guard)**: Hardened `CanonicalImageDecoder` pixel-byte multiplication against integer overflow.
  * **Audit F (Pre-1970 Dates)**: Fixed `MediaDateResolver` to allow valid pre-1970 epoch timestamps (e.g., 1969) while still rejecting `0` / sentinel values.
  * **Audit I-EXIF**: Added `TAG_DATETIME_DIGITIZED`, `TAG_OFFSET_TIME_DIGITIZED`, and `TAG_SUBSEC_TIME*` extraction feeding `MediaDateResolver`.
  * **Audit Verdicts Recorded in PR #81**: Marked Findings 2 & 3 as stale logcat buffer entries from older `r8-map-id` builds; corrected the false premise that `minSdk` was 34 (`minSdk` is 26); documented deferrals for `B`, `G`, `H`, `M`.
  * Verified **491 JVM tests green** (+34 new), opened **PR #82**, marked **PR #81** ready for review with a full resolution comment, and verified CI green on both.

### Turn 33 (Lines 3371–3458) — Merging `r11` & Building `r12`: MediaScanDriver + Capture-Date Adoption (PR #83, Release `v0.4.1-alpha-r12`)
* **User Prompt**: *"Follow up and continue building phase 4"*.
* **Closing Out `r11` & Fixing Release Workflow Asset Naming**:
  * Merged **PR #82** and **PR #81**, and pushed tag `v0.4.1-alpha-r11`.
  * Noticed `release.yml` named the published `r11` assets `toadGALLERY-0.4.1-alpha-r12.apk` because it used `GITHUB_RUN_NUMBER` instead of parsing the `v*-r<N>` tag. Renamed the published `r11` release assets and `.sha256` content via GitHub API, and fixed `.github/workflows/release.yml` to derive the round suffix directly from the pushed tag.
* **Building `r12` (2 Parallel Subagents + Coordinator Wiring → PR #83, `versionCode 16`)**:
  * **Subagent A (`MediaScanDriver`)**: Pure, exhaustively tested scan decision core (checkpoint generation token vs. live volume token; `null` token never treated as unchanged), per-volume MediaStore walks (excluding trashed rows on API 30+), reconciler diff application in self-healing order, coalesced `ContentObserver` rescans, and `AndroidMediaPermissionGate` wired at app startup (`ToadGalleryApplication`).
  * **Subagent B (`CaptureDateIndexer` & Room v1→v2)**: Resolved timezone-aware capture dates from EXIF evidence for every added/changed `EligiblePublicMedia` row (skipping vault/trashed/revoked items by type), persisting `captureEpochMillis`, `captureZoneOffsetSeconds`, `captureDateSource`, and `exifDateStatus` (`ABSENT`/`UNREADABLE`/`UNSUPPORTED` distinct) via hand-written Room `MIGRATION_1_2` (`2.json`).
  * Verified **522 JVM tests green** (+31 new), debug + R8 release builds green, merged **PR #83**, and released **`v0.4.1-alpha-r12`** (verified consistent asset naming from the workflow fix).

### Turn 34 (Lines 3459–3535) — Release Freeze Directive & Shipping `r13` (Timeline Capture Dates, PR #84) and `r14` (Metadata Details View + Location Permission, PR #85)
* **User Prompt**: *"Follow up and continue building as per plan and don't release and tag until phase 5 completion. So starting building / i am unable to see progress so i stopped and update me now"*.
* **Round `r13` — Timeline Sort/Group by Persisted Capture Dates (PR #84, `versionCode 17`)**:
  * Found `tg-r13-timeline-dates` was already built and pushed (27 files, +2,519 lines, Room v3 schema) switching the Photos timeline's sort and Day/Month grouping keys to the persisted Room catalogue capture dates (EXIF instant when present, `date_added` fallback otherwise) once populated.
  * Opened and merged **PR #84** (CI green, **561 JVM tests**). Zero tags/releases per user directive.
* **Round `r14` — Metadata Details View & `ACCESS_MEDIA_LOCATION` Flow (2 Parallel Subagents → PR #85, `versionCode 18`)**:
  * **Subagent R14-A (`MetadataDetailsScreen`)**: Full read-only Metadata Details screen reachable from the Viewer's Info sheet — displaying dimensions, file size, capture date with timezone offset + provenance source badge, camera make/model/lens, exposure triangle (aperture, shutter speed, ISO, focal length, flash), GPS coordinates, and full raw EXIF tag tree.
  * **Subagent R14-B (`ACCESS_MEDIA_LOCATION` Permission Flow)**: Pure JVM-tested permission state machine (`MediaLocationPermissionState`), Settings `"Location data"` section for API 29+ (`ACCESS_MEDIA_LOCATION`), and `MediaLocationDisplayRules.Default` gating GPS coordinate display in the Details view until location consent is granted.
  * Rebased onto `main`, killed stale local Gradle daemons, verified **583 JVM tests green** (+30 new) and R8 release build, opened and merged **PR #85** (untagged).

### Turn 35 (Lines 3536–3718) — Completing Phase 4 via 4 Parallel CI-Validated Rounds (`r15`, `r16`, `r17`, `r18` → PRs #86–#90)
* **User Prompt**: *"go with r15 , r16 , r17 and r18. use parallel agents and use ci and don't use this sandbox for heavy tasks which makes OOM AND insufficient memory and storage as like before so use ci and poll up after completing all branches in parallel for all branches then merge it. i think you understand if not please ask me now"*.
* **4 Parallel Rounds in Isolated Worktrees (Zero Local Builds — 100% Compiled & Tested on GitHub Actions CI)**:
  * **Round `r15` — Editable Metadata Fields (`tg-r15-metadata-edit`, commit `2dcc802` → PR #86, 66 new tests)**:
    * Implemented EXIF write-back via `androidx.exifinterface:1.4.1` (`EditableExifCodec`, format editability gate, Android 10+ `MediaStore.createWriteRequest` consent escalation state machine `WriteGrantStateMachine`, post-write revision bump + re-index trigger, and inline editing UI in `MetadataDetailsScreen` for capture date/time/offset, GPS lat/lon, copyright, artist, description, and user comment).
    * Documented platform discovery: `androidx.exifinterface:1.4.1` cannot write `Rating` / `XPKeywords` tags, so those fields render honest `"later round"` states rather than fake successes.
  * **Round `r16` — On-Device Offline Map View (`tg-r16-map-view`, commit `2a998e2` → PR #87, 48 new tests)**:
    * Staged public-domain Natural Earth 110m land vector outline (`ne_110m_land.geojson`, 136 KB) as a bundled offline asset (zero network tiles).
    * Added Room v3→v4 migration (`4.json`, verified byte-exact by re-implementing Room 2.8.5's `identityHash` algorithm in Python) persisting `gpsLatitude`/`gpsLongitude` columns via `LocationIndexer`, consent-gated on `ACCESS_MEDIA_LOCATION`, plus interactive pan/zoom `MapScreen` clustering geo-tagged photos and opening the viewer on tap.
  * **Round `r17` — Collections & Smart-Filter Chips (`tg-r17-filter-chips`, commit `3a26d60` → PR #88, 34 new tests)**:
    * Built `TimelineFilter` combinator engine (`ALL`, `PHOTOS`, `VIDEOS`, `FAVORITES`, `RECENT`, `RAW`, `GIF_ANIMATED`, `SCREENSHOTS`, `HAS_LOCATION`, plus engine support for folder/date-range), `PhotosFilterChips` bar on the Photos timeline filtering both Room catalogue and MediaStore fallback paths, and `DerivedCollections` cards.
    * Flagged pre-existing bug on `main`: `SearchQuery.kt` and `Favorites.kt` used `media_type IN (1, 2)` where `2 = AUDIO` and `3 = VIDEO`.
  * **Round `r18` — Format-Support Audit (`tg-r18-format-audit`, commit `75e2e06` → PR #89, 48 new tests)**:
    * Built magic-byte `FormatSniffer` (JPEG, PNG, WebP, GIF, HEIC/HEIF/AVIF ISO-BMFF `ftyp` brands, DNG/TIFF, BMP, MP4/MOV, MKV/WebM), programmatic `FormatCapabilityCatalogue`, and cited `docs/phase-4/format-support-audit.md`.
* **CI Polling, Remote Debugging & Sequential Merge**:
  * **Cycle 1**: `#87` (r16) and `#89` (r18) passed green on first try. `#86` (r15) and `#88` (r17) failed compile.
  * **Fixes from CI Logs**:
    * Fixed `#88` (r17): KDoc comments contained `video/*` and `image/*`, which opened nested Kotlin block comments `/*` that swallowed the rest of `TimelineFilter.kt` and `TimelineFilterCatalogue.kt`! Replaced with safe prose and wrote a stateful comment-balance scanner across all changed Kotlin files. Also fixed an `Array<Any> + Array<Int>` overload ambiguity at `RoomMediaCatalogue.kt:238` and a missing `()` parameter list on a backtick test method in `DerivedCollectionsTest.kt:33`.
    * Fixed `#86` (r15): Fixed return type extraction in `EditableExifCodecTest.kt:27`, nullable `Int?` in `WindowsRatingMathTest.kt:67`, and replaced `NEEDS_GRANT` with `REQUESTED` in the no-op state loop of `WriteGrantStateMachineTest.kt:70`.
  * All 4 PRs turned **GREEN** on CI. Merged `#86` (`10ccd62`) → `#87` (`222ed47`) → resolved additive fenced-section conflict in `MediaCatalogue.kt` / `RoomMediaCatalogue.kt` on `#88`, re-verified green CI, and merged `#88` (`f2d7f8c`) → merged `#89` (`608dfa6`).
  * Opened and merged integrator docs **PR #90** (`f99964b`, bumping `versionCode` 18 → 22, updating `CHANGELOG.md`, marking Phase 4 complete in `roadmap.md`, and adding device checklist §16).

### Turns 36 & 37 (Lines 3719–3899) — Whole-Codebase Opus Audit, Round `r19` (7 Slices) & Release Build 23 (`v0.4.1-alpha-r19`, PR #91)
* **User Prompts**: *"Use opus model and check for code improvement overall add functions and features and release build 22 if none done else 23 if done"* (Turn 36) and provided PAT `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` (Turn 37).
* **3 Read-Only Opus Audits + 7 Parallel Opus Implementation Slices (`tg-r19-quality-features` → PR #91)**:
  * **IMPL-1 (`121a58e` — P0 `media_type` Fix)**: Fixed `Favorites.kt:64` and `SearchQuery.kt:120-122` from `media_type IN (1, 2)` (which leaked audio `2` and dropped all videos `3`) to `media_type IN (1, 3)` (`MEDIA_TYPE_IMAGE`, `MEDIA_TYPE_VIDEO`), with cross-check tests against `TimelineFilter`.
  * **IMPL-2 (`VaultStore` Concurrency Mutex & Thumbnail Tail Auth)**: Closed a P0 data-loss race where concurrent `unlock()` (running `sweepStaleTempFiles`) and `moveToVault()` could delete in-flight `.tmp` blobs by adding a store-level mutex + 10-minute temp-file sweep grace window + parked-stream concurrency test; also enforced full stream read before bitmap decode in `VaultViewModel` so AES-GCM authentication tags are always verified.
  * **IMPL-3 (`23c8c71` — Metadata & Format Hardening)**: Guarded `ExifContract` GPS rational conversion against `NaN`/`Infinity`, fixed `FormatSniffer` ISO-BMFF major-brand precedence, aligned WebP editability in the format matrix, and guarded pre-Q column projections.
  * **IMPL-4 (`9c8c9e3` — Scan Indexer Isolation)**: Isolated per-row exceptions in `CaptureDateIndexer` and `LocationIndexer` so a single corrupt file cannot abort an entire scan batch.
  * **IMPL-5 (Room v4→v5 Bucket Columns & Folder Filter Chip)**: Added `bucketId` and `bucketDisplayName` to `CatalogueItemEntity` (`5.json`, identityHash derived and verified against Room 2.8.5), projected bucket columns in `AndroidMediaScanRowSource`, and wired the **Folder** filter chip + folder picker bottom sheet in `PhotosScreen` across both catalogue and MediaStore paths.
  * **Wave 2 UI Polish (`r19a`, `r19b`)**: Wired `ErrorState` in `AlbumsScreen`/`AlbumDetailScreen`, permission state in `SearchScreen`, lifecycle `ON_PAUSE` video pause in `ViewerScreen`, `Photos per row` setting honored across all grids (Albums, Search, Trash, Vault, PickedPhotos), viewer zoom reset on item deletion (`viewer swipe-lock` fix), and shared `MediaCatalogue` singleton for `MetadataDetailsScreen`.
* **CI Fix, Merge & Release Build 23**:
  * Pushed all 8 branches and opened **PR #91** (62 files, +4,178 / −591 lines, `versionCode 23`).
  * Fixed 2 compile errors caught by CI (`java.io.IOException` import in `FormatSniffer.kt:215` and explicit `MutableStateFlow<PhotosFoldersState>` type parameter in `PhotosViewModel.kt:298`).
  * CI passed green (`71872ded`) including KSP verification of hand-written `5.json`. Squash-merged **PR #91** (`93a5d0f`) and pushed annotated tag **`v0.4.1-alpha-r19`**, publishing signed **Build 23** (4.2 MB APK, SHA-256 verified).

### Turn 38 (Lines 3900–4078) — Round `r20`: The Scan-Debt Round (Combined Single-Read EXIF, Room v6 Healing Pass, Volume Hot-Plug & Vault Batch Store, PR #92)
* **User Prompt**: *"No new builds under phase 5 completion . follow up and continue building . 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"*.
* **Implementation (`tg-r20-scan-debt` → PR #92, `versionCode 24`)**:
  * **1. Combined Single-Read EXIF Indexer (`CombinedExifIndexer.kt`)**: Replaced the separate `CaptureDateIndexer` and `LocationIndexer` (which opened and parsed every image twice) with a single-open, single-parse pass that populates both capture date and GPS location and skips non-EXIF formats with zero byte reads.
  * **2. NULL-Provenance Healing Pass (Room v5→v6)**: Added `provenanceIndexedAtMillis` column to `CatalogueItemEntity` (`MIGRATION_5_6`, `6.json` with derived `identityHash = 46adb27b56f18c9ea5f4bfeb06ecb168`, verified against KSP) stamped only after all writes for a row succeed, plus `ProvenanceHealingPass` retrying bounded batches of `NULL`-stamped rows on startup until convergence.
  * **3. Volume Hot-Plug (`AndroidVolumeMonitor.kt`)**: Broadcast receiver for `ACTION_MEDIA_MOUNTED` / `UNMOUNTED` / `EJECT` / `REMOVED` (`RECEIVER_NOT_EXPORTED` on API 34+) driving `MediaScanDriver.onVolumesChanged()` to register idempotent per-volume observers and trigger coalesced rescans when SD/OTG storage is inserted.
  * **4. Vault Batch Store (`VaultStore.sealBatch`)**: Refactored multi-select `moveToVault` from N full index rewrites to a single transactional batch index commit with per-item failure isolation.
  * **5. Docs & Verification**: Flipped `docs/phase-2/screen-states.md` status table, added checklist §18, verified all JVM tests + KSP `6.json` + `lintDebug` locally, opened **PR #92**, passed CI on the first run, and squash-merged (`ae5d7f5`) with zero tags/releases.

### Turns 39, 40 & 41 (Lines 4080–4195) — Phase 5 Audit Alignment & Workstream Diff Analysis
* **User Prompts**:
  * Turn 39: Re-attached `toadGALLERY_Phase4_Compatibility_Audit_and_Phase5_Ente_AI_Plan-1.docx` (312.3 KB): *"This is the plan for your reference. Follow up"*.
  * Turn 40: *"agreed and follow up `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`"*.
  * Turn 41: *"in between i want to understand what you have done and docs says , diff and what we need to do if we are following document, then help me direction chosen outcomes and proposed plan to move next steps .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"*.
* **Sandbox Recovery & Authoritative Audit Diff**:
  * During Turns 39–40 the container shell failed to start; once recovered in Turn 41, rebuilt credentials, verified `main @ ae5d7f5` (r20), and delivered a contract-by-contract diff between the audit doc (snapshotted at `0.4.0-alpha`) and `main` (`0.4.1-alpha` build 24):
    * **Closed in `r10`–`r20` (Workstreams 0 & 1 complete)**: Media identity (`MediaIdentity`), Dates (`MediaDates`/`MediaDateResolver`), Revision tracking (`MediaRevision.diff()`), Media read boundary (`MediaReadHandle`), Canonical decode (`CanonicalImageDecoder`), Metadata breadth (`FormatSniffer`), Location (`ACCESS_MEDIA_LOCATION`), Database (`Room v6`), Incremental discovery (`MediaChangeFeed` + `MediaScanDriver` + healing pass + volume hot-plug), and Vault boundary (`EligiblePublicMedia`).
    * **Still Open (Workstreams 2–8)**: Ente Rust ML source pin + native JNI/ONNX Runtime spike, Model pack manifest/provisioning, Local ML SQLite + vector store (`ml_db`/`vecdb`), Index coordinator, People clustering UI, Magic Search (CLIP text/image), and Hardening/benchmarks.

### Turns 42 & 43 (Lines 4196–4255) — Hybrid Model-Delivery Policy & Ente Rust Crates Vendoring Strategy
* **User Prompts**:
  * Turn 42: *"for sandbox constrains i think i should test on my device by downloading by myself in app like how ente does[after install of app the models are downloaded] and then we will use ci build for model ships offline, . your thought ? i think you understand about the ml and engine and models etc of ente"*.
  * Turn 43: *"what is your plan for copying of ente code files and floders? if you need help just ask me i will provide full zip file from github for faster development. i think you are going to do that if not help me understand what your are doing"*.
* **Architectural & Licensing Decisions Locked**:
  * **Hybrid Model Provisioning (ADR-0020)**: Adopted in-app model downloading from a strict single-host allowlist (`models.ente.com`) with pinned byte sizes + SHA-256 verification and atomic staging into app-private storage (matching Ente's own runtime behavior and enabling immediate on-device testing without bloating the APK by ~239 MiB), while preserving offline pack provisioning for release builds.
  * **Ente Sparse Clone & License Verification**: Tested sparse shallow clone of `ente-io/ente` directly from the sandbox — succeeded in 4.6 MB at pinned commit `423b2d80d9ec89d45f5680cb1e1d777bac225f41`. Identified that Ente's Rust crates (`rust/crates/ml`, `rust/crates/photos`, `ente-assets`, `ente-image`, `vecdb`) are licensed under **AGPL-3.0**, and committed to recording the AGPL-3.0 provenance in `engine/NOTICE.md`, `docs/licensing-and-attribution.md`, and `ADR-0020`.

### Turns 44 & 45 (Lines 4256–4392) — Round `r21`: Vendoring Ente Rust ML Engine & Pinned Model Provisioner (PR #93)
* **User Prompts**: *"continue"* (Turn 44) and *"CONTINUE AND USE PARALLEL SUB AGENTS AND OPUS MODEL SUB AGENTS FOR BEST QUALITY OF CODE"* (Turn 45).
* **Execution & 3 Parallel Opus Verification/Completion Subagents (`tg-r21-ml-pin` → PR #93, `versionCode 25`)**:
  * Reclaimed 5.9 GB of disk space and vendored `engine/` Rust workspace (`crates/ente-ml`, `crates/ente-assets`, `crates/ente-image`, plus `clippy.toml` and `Cargo.lock`) from `ente-io/ente` @ `423b2d80d9ec89d45f5680cb1e1d777bac225f41`.
  * Built Kotlin model-delivery core in `apps/gallery/src/main/java/io/github/dukeoftoad/gallery/ml/engine/`:
    * `ModelManifest.kt`: Pinned all 11 upstream assets (5 core: `yolov5s-face-fp16.onnx` 32.3 MB, `mobilefacenet_opset15.onnx` 5.3 MB, `mobileclip_s2_image_opset18_opt.onnx` 143.1 MB, `mobileclip_s2_text_opset18_opt.onnx` 67.1 MB, `bpe_simple_vocab_16e6.txt.gz` 3.2 MB; plus 6 optional pet-detection assets).
    * `ModelVerifier.kt`: Streaming byte-size + SHA-256 verifier.
    * `ModelProvisioner.kt`: Temp staging → verification → atomic rename → `.Properties` receipt persistence with per-capability version invalidation.
  * **Opus Subagent Catches**:
    * **Agent A (Rust)**: Proved byte-identity of all 85 vendored files against upstream commit `423b2d80...` (only the documented Cargo.toml `license` line differed), vendored `clippy.toml`, and verified `cargo check --all-targets`, `cargo fmt --check`, and `cargo clippy --workspace` all exit 0.
    * **Agent B (Kotlin)**: Caught a missing import in `ModelProvisionerTest.kt` and discovered that the 6 pet-pack filenames in `ModelManifest.kt` had been invented rather than transcribed from `assets.rs` — fixed all 6 filenames to match `assets.rs` byte-for-byte and verified **837/837 JVM tests green**.
    * **Agent C (Docs & CI)**: Authored **ADR-0020**, amended **ADR-0010**, wrote `docs/phase-5/ente-pin.md` and `engine/NOTICE.md`, updated `docs/licensing-and-attribution.md`, and added the `engine` Rust job to `.github/workflows/ci.yml`.
  * Opened **PR #93** (102 files staged, `c170ad7`), passed both CI jobs (`build` + `engine`), and squash-merged (`583aaa6`, `versionCode 25`, zero tags/releases).

### Turns 46 & 47 (Lines 4393–4560) — Round `r22`: Allowlisted HTTPS Model Downloader, JNI Bridge, ORT 1.31.0 Packaging & Dogfood UI (`#94`, PR #95)
* **User Prompts**: *"Follow up `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`"* (Turn 46) and *"Follow up"* (Turn 47).
* **Fact-Finding & 3 Parallel Opus Subagents**:
  * Verified via live HTTP `HEAD` requests that all 5 core models serve directly from `https://models.ente.com/{filename}` with HTTP 200, zero redirects, and `Content-Length` matching `ModelManifest.kt` to the byte.
  * Launched 3 parallel Opus subagents (`A`: Rust `toad-ml-jni` crate; `B`: Kotlin `HttpModelDownloader`, `MlEngine` JNI facade, `AiEngineDogfoodViewModel` + Settings dogfood screen; `C`: `scripts/build-rust-android.sh`, `-PwithEngine` Gradle packaging, `engine-android` CI job, ADR-0020 revision).
* **Integration & Supply-Chain Resolution (Issue #94, PR #95, `versionCode 26`)**:
  * **INTERNET Permission Gate Protocol**: Created tracking Issue `#94`, added `android.permission.INTERNET` to `apps/gallery/internet-permission-allowlist.txt` with ADR-0020 + `#94` linkage, and extended `PrivacyPolicyTest` (JVM) and `PrivacyEgressTest` (instrumented) to enforce the single-host `models.ente.com` allowlist.
  * **ONNX Runtime 1.31.0 Android Supply Chain**: Discovered that `ort-sys 2.0.0-rc.15` with `load-dynamic` does not download a shared `.so` (and pyke's `.lzma2` archive contains only a static `.a`). Resolved by pinning Microsoft's official Maven Central AAR `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` (55.1 MB AAR containing 34.2 MB `jniLibs/arm64-v8a/libonnxruntime.so` exporting `OrtGetApiBase@@VERS_1.31.0`, 16 KB page-aligned) in `scripts/build-rust-android.sh` with dual-URL fetch + SHA-256 verification.
  * Fixed NDK pin to `28.1.13356709`, fixed `cargo-ndk 3.5.4` `-p 26` platform flag syntax, resolved AGP 9 `Provider` ban on `sourceSets.jniLibs.srcDirs`, repaired XML comment delimiters in `strings.xml`, and authored `engine/crates/toad-ml-jni/README.md` + `engine/NOTICE.md` updates.
  * Verified **855 JVM tests green**, verified 16 KB ELF segment alignment and exported JNI symbols on both `libtoad_ml_jni.so` (17.7 MB) and `libonnxruntime.so` (34.2 MB), opened **PR #95**, passed all 3 CI jobs (including the first cold `engine-android` ABI build on GitHub Actions), and squash-merged (`c1a5cb9`).

### Turns 48 & 49 (Lines 4561–4684) — Round `r23`: Vendoring `ente-vecdb` + `ente-photos-ml`, Face Indexing/Clustering JNI & Delivering 71 MB Test APK (PR #96)
* **User Prompts**: *"follow up and build debug apk for testing after completion of r23"* (Turn 48) and *"follow up"* (Turn 49).
* **3 Parallel Opus Subagents (`tg-r23-index-coordinator` → PR #96, `versionCode 27`)**:
  * **Agent A (Engine Vendoring)**: Vendored `engine/crates/ente-vecdb` (byte-identical to upstream `rust/crates/vecdb` @ `423b2d80...`, 324 upstream tests) and extracted `engine/crates/ente-photos-ml` (`db.rs`, `ml_db.rs`, `ml_store.rs`, `face_clustering.rs` from `rust/crates/photos`, with only 2 `use` lines adapted to a local `crypto::random_bytes` seam and bundled `rusqlite`, 202 upstream tests).
  * **Agent B (JNI Extension + Kotlin IndexCoordinator + Dogfood UI)**:
    * Extended `toad-ml-jni` from 4 to 10 JNI functions: `nativeDetectFaceEmbeddingsJpeg` (197-float stride per face) + 5 face-index SQLite/clustering functions (`nativeFaceIndexOpen`, `nativeFaceIndexPutFace`, `nativeFaceIndexFaceCount`, `nativeFaceIndexCluster`, `nativeFaceIndexClose`) calling upstream `face_clustering` directly without rewriting any math.
    * Built `IndexCoordinator.kt` and Settings `"Index + cluster (r23 dogfood)"` card: indexes the 12 most recent JPEGs (`EligiblePublicMedia`), runs YOLO face detection + MobileFaceNet embedding + linear incremental clustering at upstream threshold `0.24`, and displays `"N photos · M faces · K clusters"` with one-tap index deletion.
  * **Agent C (Build, CI & Docs)**: Updated CI `engine` job to run the new crate tests, bumped to `0.4.2-alpha` (`versionCode 27`), and updated `CHANGELOG.md`, `roadmap.md`, and `engine/NOTICE.md`.
* **Integration Fixes, CI Merge & Debug APK Delivery**:
  * Fixed 3 integration bugs locally: (1) illegal `--` in XML comment in `strings_r23.xml`, (2) guard order in `MlEngine.kt` so missing native library reports `LibraryMissing` before `requireFaceIndexOpen` throws `NOT_OPEN`, and (3) added `org.json:json` `testImplementation` dependency for JVM unit tests.
  * Verified byte-identity audit, `cargo check`/`fmt`/`clippy`, 526 upstream Rust tests (`324` `ente-vecdb` + `202` `ente-photos-ml`) + 11 JNI tests, and full JVM test suite.
  * Opened **PR #96**, passed all 3 CI jobs on GitHub Actions, and squash-merged (`7293610`, zero tags).
  * Built the engine-enabled debug APK locally with pinned `cargo-ndk` + NDK `28.1.13356709`: verified all 10 JNI symbols and 16 KB page alignment on both `libtoad_ml_jni.so` (20.4 MB) and `libonnxruntime.so` (34.2 MB), assembled and verified `download/toadGALLERY-0.4.2-alpha-r23-engine-debug.apk` (71 MB, `versionCode 27`, `arm64-v8a`), and delivered it as a task file artifact.

### Follow-up Session (2026-11-03) — Round `r24` merge + Round `r25`: The People UI Round (Workstream 6, branch `tg-r25-people-ui`)

* **User Prompts**: *"We are continue building the toadGALLERY PROJECT … follow up worklog.md and tasks.md file for what happened and next steps and current state, roadmap.md etc of all docs"* and *"Follow up. Stop working only after working for 1:30 … building the project upto the time as per plan with sub agents in parallel (2). Then push to github (safe way before reset of sandbox)."*
* **Session Start State**: PR #97 (`r24: the background queue round`, branch `tg-r24-background-queue`, commit `007734a`) was open with all 3 CI checks green; `origin/main` was at `7293610` (r23). PR #97 was squash-merged as `d610123`, and `tg-r25-people-ui` was cut from the updated `main`.
* **2 Parallel Opus Subagents (`tg-r25-people-ui`, `versionCode 29`)**:
  * **Agent A (Rust JNI + Kotlin engine facade)**: Extended `toad-ml-jni` from 12 to 16 externals — `nativeStoreFaceClusters` (live `face_clusters` table via upstream `cluster_id_to_face_count`, NOT the stale `cluster_summary` cache, with per-cluster cover file ids from `get_face_ids_for_cluster_ordered_by_score(id, 1)` parsed off the `"{file_id}_{ordinal}"` face-id prefix; manual `format!` JSON, `json_escape` pinned by its own test), `nativeStoreClusterFileIds` (`get_file_ids_of_cluster_id`), `nativeStoreMergeFaceClusters` (`get_face_ids_for_cluster` + upstream `force_update_cluster_ids`; self-merge rejected as `IllegalArgumentException` on BOTH sides of the bridge, mirroring the `putFace` dimension-check class) and `nativeStoreRemoveFacesFromCluster` (`get_faces_for_given_file_id` + `get_cluster_id_for_face_id` filter + upstream `remove_face_id_to_cluster_id`) — zero new SQL authored. Kotlin `MlEngine` gained the `FaceCluster` row type, the four facade methods under the same open-store state machine, and `parseFaceClusters` with the honest malformed-JSON `IllegalStateException`.
  * **Agent B (People domain + Compose UI)**: Built `ml/people` — `PeopleEngineSeam` (the domain's only engine contract; fakes in tests, `PeopleEngineAdapter` in production with a lazy `initialize → openFaceIndex` session), `PeopleStateStore` (one atomic `people-state.json` for names + hidden in the backup-excluded face-index directory, corrupt-recovers to empty, prunes stale cluster ids), the pure six-state `PeopleContentPolicy` (`MapContentPolicy` discipline: Error dominates; consent before capability; EngineMissing is the build shape; all-hidden is `Ready(empty)` ≠ `NotIndexed`), the no-cache `PeopleRepository`, and `PeopleMediaQuery` (pure 999-limit chunked `IN` selection, newest-first). Built `ui/people` — `PeopleScreen` (circular cover cards, honest state copy), `PersonDetailScreen` (multi-select remove-from-person, rename, merge-with, hide; viewer hand-offs the Album-detail way with a by-construction-honest synthesized `MediaItem`), the Albums-tab People section (off / engine-missing / not-indexed cards + person row + See all), and the Settings `peopleAlbumEnabled` consent row (ADR-0010, default OFF). Agent B also repaired 2 of its own test bugs (a missing hidden-set entry; `LongArray` reference-equality inside a `Pair`) and added `people-state.json` to `PrivacyPolicyTest`'s documented non-host-literal set.
* **Integration & Verification (this session's integrator pass)**:
  * Recovered the recycled-sandbox build environment: `GRADLE_USER_HOME=/home/z/my-project/.gradle`, `JAVA_HOME=/home/z/my-project/jdk/jdk-17.0.21+9` (system Java 21 is JRE-only), and the project-local Rust toolchain (`/home/z/my-project/.cargo` + `.rustup`).
  * Freed disk (9.9 GB rootfs was at 100%): removed the cargo incremental cache plus hash-invalidated duplicate artifacts (a `CARGO_INCREMENTAL=0` retry had spawned a parallel artifact universe), then executed the background agent's already-built r25 test binary directly.
  * Verified: **20/20 Rust tests** in `toad-ml-jni` (8 new r25 store tests), `cargo fmt --check` clean, and the **full JVM suite 927/927** (885 at r24 + 42 new: 10 `MlEngineTest` r25 tests + 32 People-package tests).
  * Bumped `versionCode 29` with the full round comment; authored `docs/phase-5/people-ui.md`; updated `CHANGELOG.md`, `roadmap.md` (r25 status section), and `tasks.md` (r25 closed; follow-ups queued: edit-preserving reclustering, unhide UI, on-device dogfood of the People surfaces).

### Follow-up Session (2026-11-03, second session) — Round `r26`: The Magic Search Round (Workstream 7, branch `tg-r26-magic-search`)

* **User Prompts**: session-carryover summary (*"We are continue building the toadGALLERY PROJECT … follow up worklog.md and tasks.md …"*), then *"Follow up"* and *"Update the progress"*.
* **Session Start State**: `origin/main` at `907f969` (worklog record of the PR #99 engine-apk workflow merge); no open PRs; Issues `#94` (permanent INTERNET-allowlist tracker) and `#14` (Phase 6 gate) open. The interrupted prior session had produced the whole r26 round through 2 parallel Opus subagents but was reset by the sandbox BEFORE its integration verification, commit or push — **3,132 lines across 27 files sat uncommitted** in the recycled working copy (9 modified + 9 new: the JNI extension, `ClipIndexQueue`/`ClipIndexWorker`, the `ml/search` domain, `MagicSearchViewModel`, the Search/Settings surfaces, `strings_r26.xml`, `docs/phase-5/magic-search.md`, and the versionCode/CHANGELOG/roadmap/tasks updates).
* **Recovery & Integration (this session's integrator pass)**:
  * Re-anchored the uncommitted r26 work onto a fresh `tg-r26-magic-search` branch cut from `origin/main` (stash-over-reset; the only main-side delta since the work's base was PR #99's workflow file, so the rebase was conflict-free).
  * Fixed the two integration defects the interrupted session never compiled: the missing `FaceIndexScheduler`/`FaceIndexWorkState` imports in `AiEngineViewModel.kt` (the r24 card's references, unresolved after the r26 sibling additions landed in the import block) and the non-exhaustive `when(note)` in `SettingsScreen.kt` (the three new SEMANTIC note branches — `SemanticDownloaded` / `SemanticDownloadFailed` / `SemanticDeleted` — rendered with the r26 strings).
  * Recovered the build environment again (JDK `17.0.21+9`, Android SDK, project-local Rust toolchain, Gradle user home); the Gradle **dependency caches were gone this time**, so a sandbox-only mirror init script (`/home/z/my-project/.gradle/init.d/mirrors.gradle.kts`, not in the repo) routed Maven resolution past Maven Central's HTTP 429 rate-limiting of the shared egress IP, and 1 GB was freed from the cargo incremental cache to fit the re-downloaded Gradle caches in the container's disk budget.
* **Verification (all green, this session)**: `cargo fmt --check` clean; `cargo clippy` clean; **32/32 Rust tests** in `toad-ml-jni` (+12 r26: semantic init/session rules, embedding round trips, the 512-dim store guards on both sides of the bridge, the search order, the fill-report parsing); the **full JVM suite 982/982** (+55: 16 `MlEngineTest` facade tests, 15 semantic-queue core tests, 24 Magic-Search domain tests); `lintDebug` green; `assembleDebug` green; and all **25 JNI symbol names cross-checked Rust↔Kotlin** byte-for-byte (the linkage correctness a JVM suite cannot prove).
* **Merge**: opened **PR #102** (`688e402`) — all 3 CI jobs green (`Vendored engine (cargo check + fmt + clippy)`, `Build + lint + test`, `Vendored engine (Android ABI + engine-enabled assemble)`) — and squash-merged as **`84aab67`** (`versionCode 30`, zero tags/releases per the Phase-5 freeze).

### Follow-up Session (2026-11-03) — Round `r26`: The People Follow-ups Round (Workstream 6 closeout, branch `tg-r26-people-followups`)

* **Scope**: the two r25 deferrals documented in `docs/phase-5/people-ui.md`, shipped together as the Workstream 6 closeout — **edit-preserving reclustering** (the cluster-id remapping overlay) and the **unhide UI for hidden persons**. 100% Kotlin-side: the vendored engine crates stay byte-identical to the r23 pin (verified: `git diff --stat engine/crates` is empty on the branch); the overlay rides the EXISTING 16-external JNI surface (`clusterFileIds` for the membership fingerprints, `mergeFaceClusters`/`removeFacesFromCluster` for the re-applied intents) — no new JNI external was needed.
* **Edit-preserving reclustering**: every clustering pass (each draining queue run, each threshold change, each model re-provision) re-rolls ALL cluster ids (upstream `new_cluster_id` is random per pass) — r25's answer was the blind load-time prune, i.e. "lose your names on every recluster". The r26 overlay keys user edits to the one identity a pass cannot re-roll — PHOTO MEMBERSHIP (face rows key on MediaStore file ids, stable since the r23 put path): `people-state.json` grows three additive books inside schema version 1 (`memberships` per edited cluster, captured at edit time; `merges` as `PeopleMerge` intents; `removals` as accumulated remove-from-person corrections), the PURE `PeopleReclusterRemap` planner maps fresh post-recluster ids onto them by overlap (deterministic: most shared files, ties by bigger cluster then smaller id; name collisions by score then old id; a live id's own name always wins), re-folds merges the pass split (majority-descendant rule, one engine merge per folded stray), re-applies removals the pass re-derived (only the files that actually came back), and refreshes fingerprints to the PREDICTED post-mutation memberships. `PeopleRepository` reconciles on every read AND every edit (a mid-window edit on a stale UI id is re-keyed through the composite mapping); a declined engine mutation aborts the save with the old state intact — self-healing on the next read, never corrupting.
* **Unhide UI**: the People screen gains a hidden-persons section — the pure `PeopleContentPolicy.hiddenPersons` mirror of the visible mapping, rendered as compact cards each with its own Unhide action (`PeopleViewModel.unhide` → `PeopleRepository.unhide`, a pure hidden-set flip — NO queue reset, names and every other edit intact; a failure is the honest one-shot snackbar, never a silent no-op). The r25 copy that pointed at the index-erasing r24 Reset as the only way back (the hide dialog, the empty-grid body, the Albums-tab all-hidden card) was re-worded in place — the Albums card now carries a "See all" action straight into the hidden section.
* **Verification**: full JVM coverage for the remap logic + unhide flow following r25's test patterns — the new `PeopleReclusterRemapTest` (15 tests: the no-op path, the four survival paths, the three honest fallbacks, determinism, canonical output), the r26 sections of `PeopleRepositoryTest` (10 new: simulated-recluster survival of names/hidden/merge/re-removal end-to-end over the fake engine, the unhide flow, the declined-re-fold abort + self-heal, mid-window edit re-keying, the edit-time book-keeping assertions), `PeopleStateStoreTest` (8 new: the overlay books' round-trip, the r25-file upgrade path, forgiving hand-edits, the pure helpers) and `PeopleContentPolicyTest` (3 new: the hidden mapping). **963 JVM tests** (+36; 927 at r25), all 927 predecessors untouched. Rust suite unchanged (526 vendored + 20 JNI; no Rust-side edits). `versionCode 31`, no device build per the Phase-5-completion directive.
### Follow-up Session (2026-11-03) — Round `r27`: The MI Organisational UX Round (branch `tg-r27-org-ux`, resumed)

* **Session Start State**: `origin/main` at `907f969` (r25 + the #99 CI workflow; `versionCode 29`, 927 JVM + 526 Rust + 20 JNI tests green). The r27 subagent (Places / Moments / Stories / collage / slideshow) died mid-round from an infrastructure timeout with its WIP complete but UNCOMMITTED in the sandbox; this session audited, completed (tests/docs/versionCode), and drove it through CI to the merge.
* **The Audit** (every WIP file read end-to-end): all five features coherent — every r27 nav destination (`PLACES`, `PLACE_DETAIL`, `COLLAGE`) exists and routes through the shell; every r27 string id resolves in `strings_r27.xml`; the vault/trash exclusion is INHERITED everywhere (the Places surfaces read the catalogue's live-rows `geotaggedRows` projection only — vault media never enter the catalogue, trashed rows leave it — and the r14 consent display gate is applied by the pure `PlacesContentPolicy`, the r16 map policy's twin); `GroupBy` gains `WEEK` safely (persisted by NAME with the forgiving `parseGroupBy`, so no stored-setting breakage; the pre-r27 `"week"` lowercase parse-to-NONE test stays true). Zero overlap with the parallel r26 Magic Search workstream (no `ui/search/*`, no `data/SearchQuery.kt`, no engine crates touched).
* **The Five Features** (all pure-core + thin-Android-seam per ADR-0016):
  * **Places** — the pure `PlacesRegionGrid` (12x6 grid, one cell per 30°, closed-form bands, first-appearance order, antimeridian-normalized means) + `PlacesRegionLabels` (coordinate-band labels, ISO-6709-style, ZERO reverse geocoding) + `PlacesContentPolicy` + `PlaceMediaHandoff`; `PlacesScreen`/`PlaceDetailScreen` sub-pages and the Albums-tab Places section.
  * **Moments** — the pure `MomentGrouper` (ISO-8601 DAY/WEEK sections, week-based-year keys); `buildTimelineRows`' WEEK branch is a thin projection (DAY/MONTH streaming untouched); counted headers via `headerCellCounts`; the `GroupBy.WEEK` setting with FlowRow chips.
  * **Stories** — the pure `StorySets` engine (36h gap clusters, min 3 members, cap 8 most-recent, zero persistence); the `StoriesShelf` reads the SAME paging snapshot the grid renders; playback IS the existing viewer with the slideshow armed.
  * **Collage** — the pure `CollageLayouts` (17 templates for 2..9 photos, exact tiling, `slotPixels` + `centerCropSource` shared by preview and exporter); the `CollageScreen` preview/picker; the Q+ MediaStore exporter on the file-ops naming/pending-row discipline.
  * **Slideshow** — the pure `SlideshowTiming` + `ViewerCrossfade`; the shell-scoped armed launch (`openSlideshow`); the viewer's toggle + in-session interval menu; crossfade alphas during a run; leaving the viewer always stops the run.
* **Tests — the round's gap, closed**: 84 new JVM tests at authoring (927 → 1011): `MomentGrouperTest`, `StorySetsTest`, `CollageLayoutsTest`, `PlacesRegionLabelsTest`, `PlacesContentPolicyTest`, `PlaceMediaHandoffTest`, `SlideshowTimingTest`, plus the WEEK-grain + `headerCellCounts` sections of `TimelineRowsTest`, the NaN/`regionKeyOf` gaps of `PlacesRegionGridTest`, and the `WEEK` case of `AppearanceTest`. `CollageHandoff`'s one-shot mechanics stay unpinnable on the plain JVM (its payload embeds `android.net.Uri` — the established `ViewerViewModel`/`PhotosFilterHandoff` split, documented in its KDoc); its 2..9 range gate is pinned by `CollageLayoutsTest` instead.
* **Build identity**: `versionCode 31 → 32` on the rebased base (the round authored at 31 before the two r26 merges landed at 30/31; every merged round keeps its own build number; `versionName` stays `0.4.2-alpha` — Phase 5 release freeze, no tags, no Releases).
* **Docs**: this worklog entry + ledger row; `tasks.md` MI-Style Organisational UX checkbox closed; `CHANGELOG.md` r27 entry; `roadmap.md` r27 status section.

### Cron Campaign Session (2026-11-03) — Round `r29`: The Issue #14 Contract-Gate Round (branch `tg-p6-contract-gate`)

* **Session Start State**: `origin/main` at `88bb21f` (r27 + docs sync; `versionCode 32`, 1011 JVM + 526 Rust + 20 JNI tests green). This is the first run of the scheduled Phases 6–8 build campaign (the append-only campaign log lives at the end of this file). Phase 5's r26/r27 rounds are merged and r28 (Workstream 8 hardening) is open as sibling PR #100 — untouched, per the no-takeover rule — so the campaign scope starts exactly where the roadmap says it must: **the Issue #14 gate**, the Gallery ↔ Edit URI contract prototype that blocks Phase 6.
* **The gate, read from the record**: Issue #14 demands a minimal 2-APK prototype proving launch-with-granted-URI → modify → export copy → Gallery observes the MediaStore change (no rescan), all three terminal states, the version-negotiation refusal path, and the original untouched — per `docs/architecture/editor-contract.md` (ADR-0002 / ADR-0007). CI cannot install two APKs on an emulator (ADR-0016), so the gate is proven in two CI-runnable layers (shared contract logic + a manifest-conformance suite) plus a device-checklist section (§19) for the physical round trip.
* **`:shared:editor-contract`** — a NEW pure-JVM Kotlin module (no Android surface at all): the frozen v1 constants (`org.toadgallery.action.EDIT`, the five launch extras, the result extras, the five error codes, `save_copy`, the metadata policies; `RESULT_ERROR = RESULT_FIRST_USER + 1` so it can never be mistaken for OK/CANCELED), `EditorIdentity` (EXACT application-ID match + the one documented `.debug` dogfood suffix; hostile prefix extensions rejected), `EditorVersionNegotiator` (additive versioning: `>= MIN(1)` is Ready with the version passed through; below-min or missing meta-data refused), `EditRequestValidator` + `EditLaunchExtrasBuilder` (version window, `content://`-only source URIs — `file://` and bare paths refused, `save_copy`-only, total metadata-policy parse, known-hint rule; typed extras — the version extra an Int per the spec table), and `EditResultParser` (all three terminal states, total: unknown codes and malformed OK replies degrade to structured `ERROR_INTERNAL`, never crashes). One truth, compiled once, consumed by BOTH apps.
* **`:apps:edit`** — the toadGALLERY Edit prototype APK (`io.github.dukeoftoad.gallery.edit`, `versionCode 1`, `0.1.0-edit-alpha`, debug builds `.debug`): exported `EditActivity` filtered on the contract action (+ DEFAULT, image/jpeg|png|webp|heic) with the `org.toadgallery.contract.EDIT = 1` meta-data; a REAL modify step (brightness + contrast per-channel LUT — brightness scale 2.55, the standard 8-bit contrast factor pivoted at 128, composed brightness-then-contrast — plus left rotation as pure grid math, a live 2048-px bounded preview with draft-slider commit-on-release semantics, and undo/redo over a bounded 64-deep snapshot stack); the NON-DESTRUCTIVE save-copy export (ADR-0011): full-resolution decode → adjust → rotate → JPEG q95 → MediaStore **INSERT of a NEW row** (`IS_PENDING` on Q+ cleared after the bytes land; a failed write CANCELS the pending row so no partial entry survives) → EXIF metadata policy through `androidx.exifinterface` (preserve copies every tag, strip_location nulls the 22-tag GPS set, strip_all writes nothing) → `RESULT_OK` with `EXTRA_OUTPUT_URI` + the applied policy echoed. The source URI is read-only by construction — never opened for write, never updated, never deleted. Validation failures answer `RESULT_ERROR` with structured codes BEFORE any UI exists; back is `RESULT_CANCELED`; decode/IO/OOM/permission failures map to `ERROR_UNSUPPORTED`/`ERROR_IO`/`ERROR_PERMISSIONS`/`ERROR_INTERNAL`. **Zero network**: no INTERNET permission, enforced by the module's OWN merged-manifest gate — stricter than the gallery's (there is no allowlist mechanism at all; the editor contract §4.5 forbids network unconditionally). The single declared permission is `WRITE_EXTERNAL_STORAGE` capped at `maxSdkVersion=28` (pre-Q inserts; Q+ own-contribution inserts need none) — requested at save time only, on devices where it applies. A manifest-conformance JVM test parses the app's own manifest and pins the whole contract surface.
* **The gallery half**: `ui/viewer/EditorLaunch.kt` (the package-visible resolution: query the contract action, exact identity match, enabled flag, version negotiation; the typed launch-intent builder — Parcelable source Uri + ClipData + the read-only grant scoped to the one URI; the third-party `ACTION_EDIT` chooser fallback for not-installed; the reply parser) + the viewer's **Edit op** (image pages only, `Icons.Filled.Create`, hidden on videos): not installed → honest message + fallback chooser; outdated → refusal + update nudge; disabled → explanatory message; ready → launch; the reply narrates Saved copy / structured error, canceled stays silent. The manifest gains the `<queries>` visibility block for both actions (API 30+ package visibility — without it resolution would always answer not-installed). **Refresh needs no rescan and no new wiring**: the saved copy is a MediaStore INSERT the existing ContentObserver-driven paging invalidation picks up — the contract's observation property, already proven by the #38 follow-up.
* **CI**: the build job gains the editor's assembleDebug (which runs the editor's stricter INTERNET gate), `lintDebug`, `testDebugUnitTest`, `:shared:editor-contract:test`, and the `edit-debug-apk` artifact upload (14-day retention, the gallery artifact's shape).
* **Tests**: ~40 contract-module JVM tests (negotiation, identity, request validation, launch extras, result parsing, the frozen result-code constants), ~35 edit-app JVM tests (LUT math incl. the hand-computed composition value, rotation grid math for all four turns + normalization, session undo/redo incl. redo-tail truncation + depth bounds, export naming/pending/failure-recovery, metadata policy decisions, manifest conformance), and 9 new gallery JVM tests (`EditorResolverTest` — the resolution rule incl. the debug-suffix and the disabled-before-version ordering); the gallery total 1011 → 1020, every predecessor untouched.
* **Docs**: `docs/phase-6/contract-gate.md` (the gate's implementation record), the r29 implementation note on `docs/architecture/editor-contract.md` (the spec is the source of truth: the three additive deltas — the `.debug` identity acceptance, the `EXTRA_METADATA_POLICY_APPLIED` result field, the JPEG-only prototype export — are recorded there), the device checklist §19 (the physical round trip + hash compare + version-refusal debug path), `tasks.md` (the #14 checkbox + the Phase 6 milestone row), `CHANGELOG.md`, `roadmap.md` Phase 6 status. No engine changes (the vendored crates stay byte-identical to the r23 pin); no new permissions in the gallery.
* **Build identity**: gallery `versionCode 32 → 33`; the editor starts its own version line at 1 (the two apps version independently per the contract; the gallery resolves the CONTRACT version from the manifest meta-data, never from versionCode).

### Cron Campaign Session (2026-11-03, extended) — Round `r30`: The Edit v1 Core Round (branch `tg-p6-edit-v1-core`, stacked on `tg-p6-contract-gate`)

* **Session context**: the maintainer's between-triggers directive (a ~2.5-hour build window, wave-committed work, one push at the end). **The predicted cron trigger fired mid-window**: a second campaign instance worked the same tree concurrently (its commits are interleaved in the branch history --- `f5ab2be`/`26e8f52` etc. beside this session's wave commits). The interleaving was reconciled in two coherence passes (`1833f3f`, `765f91b`): the branch tip is ONE coherent design, recorded here as the r30 round. The final design is the triggered instance's recipe-as-model architecture over this session's pure-math foundations --- both halves are on the record in the history.
* **The r30 state** (the merged design at the tip): **(1) the ADR-0011 recipe**: one `EditRecipe` data class IS the whole session state (geometry + tone + colour + grade + detail, range-validated at construction), serialized by `EditRecipeCodec` (additive-versioned `schemaVersion`; unknown keys ignored, out-of-range values clamped, malformed JSON a structured failure) into `EditRecipeStore` (app-private `edit-sidecars/`, SHA-256-keyed, atomic tmp+rename, corrupt-file self-heal) --- saved on every committed change, restored on relaunch, CLEARED on export (the edit resolved into the copy). **(2) the pure foundations** (this session's waves, all JVM-tested): the geometry toolset (normalized `CropRect` + aspect presets, flips, straighten on an expanded canvas with bilinear sampling and transparent fill, the quad perspective correction), the LUT stage (exposure/brightness/contrast/highlights/shadows as ONE composed 256-entry LUT, smoothstep-masked halves), the full-pixel stage (saturation/temperature/tint), the 8-band HSL (cosine-hat weights, achromatic skip, RGB↔HSL), radial vignette, 3×3 unsharp sharpen --- every zero state skipped for an EXACT identity. **(3) `EditPipeline`**: geometry-first stage order (flip → straighten → perspective → turns → crop, then tone → colour → HSL → vignette → sharpen), the identity recipe returning the input grid byte-exact, plus `EditGeometryPlan` (the shared pre-crop frame math so the crop overlay and the export can never disagree). **(4) export control**: JPEG quality slider, PNG lossless, WebP --- the honest encodable set; the platform Bitmap has NO HEIC encoder (`Bitmap.CompressFormat.HEIF` does not exist --- the coherence pass removed the HEIC arm, its probe and its fallback whole), HEIC sources save as JPEG/PNG/WebP by user choice. **(5) the editor surface**: geometry row, straighten slider, crop aspect presets over the post-geometry frame, the ten-slider adjust section (draft-commit-on-release), the eight-band HSL selector + per-band sliders, the export sheet --- all Compose/M3 (`FilterChip` under one `@OptIn`). The r29 rotate turn-1/3 mapping swap is fixed (the r29 editor tests had never run on CI; the cross-check caught it).
* **Tests**: the edit app carries the full suite at the tip --- `GeometryTest` (15), `AdjustmentsTest` (~20), `HslVignetteSharpTest` (~15), `EditRecipeTest` + `EditPipelineTest` (codec round-trips, tolerance, store atomicity/self-heal/clear, pipeline identity/order/plan agreement, ~35), `EditCoreTest` (the r29 basis, format-updated), `PipelineExportTest` (rewritten for the recipe API), `EditManifestConformanceTest` (r29, unchanged). The exact totals land with the first green CI run (the editor app's first CI compile --- the r29/r30 editor steps never ran: every prior CI run failed in the gallery job first).
* **Build identity**: the EDITOR app `versionCode 1 → 2` (`0.2.0-edit-alpha`) with the r30 comment block; the gallery is untouched by r30 (its `versionCode 33` stands from r29 --- an editor-only round versions the editor app).
* **Docs**: this entry + the ledger row (below); `tasks.md` Phase 6 remainder split; `CHANGELOG.md` the [toadGALLERY Edit] entry; `roadmap.md` Phase 6 status. No engine changes; no new permissions (org.json is platform, test-classpath only for the JVM tests).

## Pull Request & Commit Ledger

| PR # | Round / Phase | Branch / Title Summary | Merge Commit | Key Outcome & Verification |
| :--- | :--- | :--- | :--- | :--- |
| **#9** | Phase 0 & 1 | Phase 0 sign-off + Phase 1 audits, feature matrix, ADRs 0001–0007, diagrams | `42fd9981` | Squash-merged; Milestones 0 & 1 closed |
| **#15** | Phase 2 (`#10`) | Buildable `:apps:gallery` shell (`v0.1.0-alpha`, 4-tab nav) | Merged | First APK built (30 MB); Issue `#10` closed |
| **#16** | Phase 2 (v2.2) | `roadmap-v2`: Reference-only upstreams (ADR-0008), Compose shell (ADR-0009), CI (ADR-0013), `#17`/`#18`/`#19`/`#12` | `d04a257` | Squash-merged; `org.fossify:commons` removed; `v0.2.0-alpha`; CI green |
| **#23** | Phase 2 (`#20`) | `privacy/20-internet-gate`: Gradle `INTERNET` gate + `PrivacyPolicyTest` + `PrivacyEgressTest` | `e9f458b` | Merged; Phase 2 exit gate satisfied |
| **#24** | Phase 2 (`#20`) | `phase2-privacy-gate`: Duplicate local implementation of `#20` (`47c45ff`) | Closed | Closed in favor of already-merged PR #23 |
| **#26** | Phase 3 (`#25`) | Phase 3 Slice 1: `MediaStoreSource` + `PhotosScreen` 3-column grid (`v0.3.0-alpha`, code 4) | `5db89bd` | Merged; CI Run #9 green (4 JVM + 3 emulator tests) |
| **#35** | Phase 3 (`#29`) | Albums tab + `AlbumDetailScreen` + shared `ui/media/` + ADR-0014 (`v0.3.1-alpha`, code 5) | `173581c` | Merged; CI Run #11 green; Issue `#29` closed |
| **#36** | Phase 3 (`#28`) | `phase3-paging`: `WindowPagingSource` + Paging 3 timeline + ADR-0015 (`v0.3.2-alpha`, code 6) | Closed | Contained inside PR #37 (`db835dd`); `#28` auto-closed |
| **#37** | Phase 3 (`#28`, `#33`, `#34`) | `phase3-grid-ux`: Integrated Paging 3 + aspect-ratio staggered grid + video/GIF badges (`v0.3.3-alpha`, code 7) | `db835dd` | Squash-merged; 28 JVM tests; Issues `#28`, `#33`, `#34` closed |
| **#39** | CI Hardening | Same-emulator retry script for `SplitApkInstallerBase` broken-pipe install flake | `1ddd966` | Squash-merged; CI Runs #20 & #21 green |
| **#40** | Phase 3 (`#38`) | Paging 3 follow-ups: `ContentObserver` invalidation, append footer, idempotent callback (`6169a5b`) | `827a3d7` | Merged; Issue `#38` closed |
| **#41** | Phase 3 (`#13`) | Screen-state system definition and composable wiring (`6318a78`) | Merged | Merged after `TextOverflow` fix; Issue `#13` closed |
| **#42** | Phase 3 (`#27`) | Photo Picker alternative path (`PickMultipleVisualMedia`) + Settings toggle (`2f03e9f`) | `aaf010f` | Merged after rebase; Issue `#27` closed |
| **#43** | CI Hardening | Larger emulator `-partition-size`, verifier off, uninstall+trim between attempts | Merged | Merged; CI Run #29 green |
| **#44** | CI Hardening | Emulator `adb reboot` retry wrapper | Closed | Closed per maintainer directive to remove emulator tests |
| **#45** | CI / Docs | Remove emulator CI job (ADR-0016) + root `CHANGELOG.md` + `roadmap.md` v2.3 | `1a76e64` | Merged; ~4 min JVM-only CI gate active |
| **#46** | Phase 2 (`#21`, `#22`) | Drop `androidphotofilters`/`jxl-coder` in compatibility doc + update risk register (`R-14`, `R-16`–`R-18`) | Merged | Merged; Issues `#21`, `#22` closed |
| **#47** | Phase 2 (`#11`) | Design tokens: spacing, shape, typography, WCAG light+dark palettes, edge-to-edge | Merged | Merged; CI Run #48 green; Issue `#11` closed |
| **#48** | Phase 3 (`#30`) | File operations: rename, copy, move, delete, multi-select, undo, batch progress, API 29+ consent | Merged | Merged; CI Run #45 green; Issue `#30` closed |
| **#49** | Docs | `0.4.0-alpha` changelog update for `#11` / `#30` | Merged | Merged; CI Run #51 green |
| **#51** | Phase 3 (`#50`) | Full-screen photo/video viewer (`media3` ExoPlayer) + `ic_video_fallback` poster | `96f3e31` | Squash-merged; CI Run #54 green; Issue `#50` closed |
| **#52** | Lint Gate | Fix 19 `NewApi` errors in `FileOpsViewModel`/`MediaFileOps` + remove `continue-on-error` in CI | `e025357` | Merged; `lintDebug` is now a hard blocking gate |
| **#53** | Phase 3 (`#31`) | Recycle bin (`MediaStore.createTrashRequest` on API 30+, `TrashScreen`, Settings entry, size formatter) | `4cef62e` | Squash-merged; CI Run #61 green; Issue `#31` closed |
| **#54** | Phase 3 (`#32` 1/2) | Pure-JVM `VaultCrypto` (PBKDF2 + streaming AES-256-GCM) + ADR-0017 (13 JVM tests) | `02b9ca4` | Merged; CI Run #63 green |
| **#55** | Phase 3 (`#32` 2/2) | `VaultStore`, KeyStore wrapper, `VaultRepository`, `VaultViewModel` (`ON_STOP` auto-lock), `VaultScreen` | `c12156d` | Squash-merged; 39 vault tests; Issue `#32` closed |
| **#56** | Phase 3 (`#50`) | Viewer pinch-zoom/pan/double-tap gesture detector (18 JVM tests) + `coil-gif` animated GIFs | `75e841b` | Squash-merged; 142 total JVM tests; CI Run #70 green |
| **#60** | Release CI (`#59`) | Env-injected Gradle release signing + `.github/workflows/release.yml` + ADR-0018 | `e926947` | Merged; Issue `#59` closed |
| **#61** | Release CI Fix | Fix invalid job-level `${{ runner.temp }}` in `release.yml` to step-level `$RUNNER_TEMP` | Merged | Merged; cut signed pre-release `v0.4.0-alpha-r3` |
| **#67** | Phase 3 Wave 1 (`#62`–`#65`) | Search tab + Viewer ops (Info/Share/Rename/Delete) + 10-bit HEVC decoder fallback + Album file ops | Merged | Merged; 189 JVM tests; `versionCode 9`; released `r4` |
| **#68** | Phase 3 Wave 2 (`#57`, `#58`, `#66`) | Vault biometric unlock (ADR-0019) + in-memory vault thumbnails + Favorites + Newest/Oldest sorting | `d02c080` | Merged; 233 JVM tests; `versionCode 10`; released `r5` |
| **#70** | Phase 3 (`#69`) | `VaultThumbnailGate`: lock-generation token + decode job cancellation on lock + pre-publish re-check | `60b7c0f` | Squash-merged; 7 new JVM tests; Issue `#69` closed |
| **#71** | Release Prep | Bump version to `0.4.1-alpha` (`versionCode 11`) | Merged | Merged; cut aligned pre-release `v0.4.1-alpha-r7` |
| **#72** | Phase 3 Docs | Add `docs/phase-3/device-validation-checklist.md` | Merged | Merged |
| **#73** | Vault Integrity | Check `resolver.update() > 0` before vault delete + transactional two-phase `VaultStore.setup()` | `aaa9ea2` | Merged; 4 new failure-injection tests in `VaultStoreTest` |
| **#74** | `r8` (Build 12) | `DiagLog` ring buffer + StrongBox→TEE fallback + Ente Appearance settings + About/Diagnostics page | `b2be3b5` | Squash-merged; 283 JVM tests; `versionCode 12` |
| **#75** | `r8` Docs | CHANGELOG entries for `#73`/`#74` + checklist §§9–11 retargeted to `r8` | `4c12be3` | Merged; released `v0.4.1-alpha-r8` |
| **#76** | `r9` (Build 13) | Fix AndroidKeyStore caller-provided IV bug in `VaultCrypto` + fix `MediaPagingGrid` OOB crash (`Index: 60, Size: 60`) | `28e6a6a` | Merged; 290 JVM tests; `versionCode 13`; released `r9` |
| **#78** | `r10` (Phase 4) | Biometric negative-button fix + Phase 4 foundation (Identity, Eligibility, Dates, EXIF/Video, Room v1, Decode) | Merged | Merged; 457 JVM tests (+174 new); `versionCode 14` |
| **#79** | `r10` Docs | Phase 4 CHANGELOG + checklist §12 + `roadmap.md` Phase 4 status & phase-map reconciliation | Merged | Merged |
| **#80** | CI Memory Fix | Raise Gradle daemon to `-Xmx2048m -XX:MaxMetaspaceSize=768m` + 45m timeout in `release.yml` | Merged | Merged; fixed R8/KSP Metaspace crash; released `v0.4.1-alpha-r10` |
| **#81** | Crash/Audit Doc | User draft `[DRAFT] Document crash investigation and proposed fixes` updated with verification & resolution records | Merged | Marked ready-for-review & merged alongside PR #82 |
| **#82** | `r11` (Build 15) | `BIOMETRIC_STRONG` fix + vault backup exclusion + per-row revision token + Room tx + schema export + pre-1970 dates | Merged | Merged; 491 JVM tests (+34 new); `versionCode 15`; released `r11` |
| **#83** | `r12` (Build 16) | `MediaScanDriver` + `CaptureDateIndexer` (Room v1→v2) + `release.yml` tag-based asset naming fix | Merged | Merged; 522 JVM tests (+31 new); `versionCode 16`; released `r12` |
| **#84** | `r13` (Build 17) | Switch Photos timeline sort & Day/Month grouping keys to persisted Room capture dates (Room v3) | Merged | Merged; 561 JVM tests; `versionCode 17` (untagged freeze begins) |
| **#85** | `r14` (Build 18) | `MetadataDetailsScreen` (read path) + `ACCESS_MEDIA_LOCATION` permission flow & GPS display gate | `55cf8f3` | Merged; 583 JVM tests; `versionCode 18` (untagged) |
| **#86** | `r15` (Phase 4) | Editable EXIF metadata write-back (`EditableExifCodec`, `WriteGrantStateMachine`, inline Details edit UI) | `10ccd62` | Merged; 66 new JVM tests |
| **#87** | `r16` (Phase 4) | Bundled offline world map (`MapScreen`, Natural Earth GeoJSON, Room v4 GPS columns, consent-gated dots) | `222ed47` | Merged; 48 new JVM tests |
| **#88** | `r17` (Phase 4) | `TimelineFilter` engine + `PhotosFilterChips` + `DerivedCollections` across Room & MediaStore paths | `f2d7f8c` | Merged after KDoc nested-comment & conflict fixes; 34 new tests |
| **#89** | `r18` (Phase 4) | Magic-byte `FormatSniffer` + `FormatCapabilityCatalogue` + `docs/phase-4/format-support-audit.md` | `608dfa6` | Merged; 48 new JVM tests |
| **#90** | `r15`–`r18` Docs | Integrator release-prep docs (`versionCode` 18 → 22, CHANGELOG, Phase 4 complete in roadmap, checklist §16) | `f99964b` | Merged; Phase 4 complete |
| **#91** | `r19` (Build 23) | 7-slice Opus quality round: `media_type IN (1,3)` fix, vault mutex, Room v5 folder chip, error states, zoom reset | `93a5d0f` | Squash-merged; `versionCode 23`; released `v0.4.1-alpha-r19` |
| **#92** | `r20` (Build 24) | Scan-debt round: `CombinedExifIndexer` single-read pass, Room v6 healing pass, volume hot-plug, vault batch store | `ae5d7f5` | Squash-merged; `versionCode 24` (untagged freeze resumed) |
| **#93** | `r21` (Phase 5) | Vendor Ente Rust ML engine (`ente-ml`, `ente-assets`, `ente-image` @ `423b2d80`) + `ModelManifest`/`Provisioner` + ADR-0020 | `583aaa6` | Squash-merged; 837 JVM tests + CI `engine` cargo job; `versionCode 25` |
| **#95** | `r22` (Phase 5) | `HttpModelDownloader` (`models.ente.com` allowlist, `#94`) + `toad-ml-jni` + ORT 1.31.0 AAR + dogfood UI | `c1a5cb9` | Squash-merged; 855 JVM tests + `engine-android` CI job; `versionCode 26` |
| **#96** | `r23` (Phase 5) | Vendor `ente-vecdb` + `ente-photos-ml` + 10-fn JNI face index/clustering + `IndexCoordinator` + 71 MB debug APK | `7293610` | Squash-merged; 526 Rust + 11 JNI + JVM tests; `versionCode 27` |
| **#97** | `r24` (Phase 5) | `tg-r24-background-queue`: WorkManager face-index queue (Workstream 5) + store maintenance JNI pair + pure planner/processor/state core + r24 Settings card | `d610123` | Squash-merged; 885 JVM tests; `versionCode 28`; CI 3/3 green |
| **#98** | `r25` (Phase 5) | `tg-r25-people-ui`: the People UI round (Workstream 6) — 16-external JNI store, `ml/people` domain + `people-state.json`, `PeopleScreen`/`PersonDetailScreen`, ADR-0010 consent row | `68f1b35` | Squash-merged; 927 JVM tests (+42) + 20 JNI-crate Rust tests (8 new); `versionCode 29`; CI 3/3 green (one lint fix-up commit: `@AnyRes` on `PersonDetailEvent.ShowSnackbar`) |
| **#99** | CI (Phase 5 support) | `tg-engine-apk-build29`: isolated engine-apk test-build workflow --- verbatim `engine-android` job + APK identity check (`versionCode 29` / `.debug`) + r23-convention artifact staging + 14-day retention; triggers only on `tg-engine-apk-*` + dispatch | `0db54af` | Squash-merged; CI green (PR run 41567323905; engine-apk run 41557451868); 71.5 MB `arm64-v8a` debug APK verified (`libtoad_ml_jni` 19.5 MiB + `libonnxruntime` 32.6 MiB) |
| **#102** | `r26` (Phase 5) | `tg-r26-magic-search`: the Magic Search round (Workstream 7) --- 25-external JNI bridge (`nativeInitSemantic` third state + CLIP encoders + CLIP store surface over the same `ml-index.sqlite`), the r24-sibling `ClipIndexQueue`/`ClipIndexWorker`, the `ml/search` domain (six-state policy, query gate, order-preserving ranking), Search-tab Content mode + Settings SEMANTIC cards | `84aab67` | Squash-merged (recovered uncommitted prior-session work + 2 integration fixes); 982 JVM tests (+55) + 32 JNI-crate Rust tests (+12); CI 3/3 green; `versionCode 30` (untagged freeze continues) |
| **#101** | `r26` (Phase 5) | `tg-r26-people-followups`: the People follow-ups round (Workstream 6 closeout) --- edit-preserving reclustering (the pure `PeopleReclusterRemap` cluster-id remapping overlay over membership fingerprints + merge/removal re-application, all Kotlin-side over the existing 16-external JNI surface) + the unhide UI (`PeopleScreen` hidden section with per-person Unhide, no index reset) | `23a1495` | Squash-merged; CI green (PR run 41585595014 + dispatch 41585685822, both on head 17feb547); 963 JVM tests (+36); `versionCode 31`; no Rust-side changes (crates byte-identical to the r23 pin) |
| **#103** | `r27` (Phase 5) | `tg-r27-org-ux`: the MI organisational UX round — Places (offline region bucketing, zero reverse geocoding), Moments (WEEK grain + counted headers), Stories, collage creator, slideshow | `27c5e71` | Squash-merged; CI green (PR run 41592879894 + dispatch 41592872111, both on head `97e2ae7`); 1011 JVM tests (+84); `versionCode 32` |
| **#104** | `r29` (Phase 6 gate) | `tg-p6-contract-gate`: the Issue #14 contract-gate round — the pure-JVM `:shared:editor-contract` module (identity/negotiation/request/result, one truth both apps) + the `:apps:edit` toadGALLERY Edit prototype APK (contract launch, real LUT adjust + rotate session with undo/redo, non-destructive MediaStore-INSERT save-copy export with the EXIF policy, three terminal states, zero network with its own stricter INTERNET gate) + the gallery half (exact-match resolution + viewer Edit op + third-party fallback + `<queries>`; MediaStore-observation refresh, no rescan) | `pending` | PR open; CI pending (one known test fix on the head, one plugin-resolution fix); 1020 gallery JVM tests (+9) + ~40 contract + ~35 edit tests; gallery `versionCode 33`, editor `versionCode 1` |
| — | `r30` (Phase 6) | `tg-p6-edit-v1-core` (stacked on `tg-p6-contract-gate`): the Edit v1 core round — the ADR-0011 `EditRecipe` + codec + store (save-on-commit, restore-on-relaunch, clear-on-export), the geometry toolset, the full adjustment suite (composed LUT + pixel stages + 8-band HSL + vignette + sharpen), `EditPipeline` + `EditGeometryPlan`, and the honest export control (JPEG/PNG/WebP; NO HEIC — the Bitmap has no HEIC encoder) with the full editor surface. Authored across two interleaved campaign instances (the mid-window cron trigger); reconciled to one coherent tip | `pending` | Branch ready for PR (opened by the next cron run after #104 lands, per the stacked-PR note in the campaign log); editor `versionCode 2` (`0.2.0-edit-alpha`); the edit app's first CI compile lands with this PR |
## Architecture Decision Records (ADR) Registry

| ADR | Status | Title / Architectural Decision |
| :--- | :--- | :--- |
| **ADR-0001** | SUPERSEDED by ADR-0008 | Initial decision to seed from Fossify Gallery Kotlin base + port Aves `exifinterface` fork (Flutter excluded). |
| **ADR-0002** | ACCEPTED | Gallery ↔ Edit loose coupling via explicit Android URI intent contract (`ACTION_EDIT` / custom contract). |
| **ADR-0003** | ACCEPTED (Clarified) | Metadata architecture: `androidx.exifinterface` directly (Aves fork port dropped under ADR-0008). |
| **ADR-0004** | ACCEPTED (Clarified) | Project license: `GPL-3.0` voluntary choice (later joined by `AGPL-3.0` Ente ML engine in ADR-0020). |
| **ADR-0005** | SUPERSEDED by ADR-0009 | Initial v1 View-based UI / app identity decision (no Jetpack Compose). |
| **ADR-0006** | ACCEPTED | URI-first data model (`content://` URIs + MediaStore IDs, never raw filesystem paths). |
| **ADR-0007** | ACCEPTED | Editor contract v1 specification and non-destructive sidecar / video scope alignment. |
| **ADR-0008** | ACCEPTED | Reference-only upstream policy: take only features/UI/UX/designs from Fossify, Aves, and MI Gallery; zero upstream code copied from Fossify/Aves. |
| **ADR-0009** | ACCEPTED | UI toolkit: 100% native Kotlin + Jetpack Compose + Material 3 + dynamic color from scratch (`org.fossify:commons` removed). |
| **ADR-0010** | ACCEPTED (Amended in r21) | On-device AI architecture: zero cloud ML, opt-in face features disabled by default; amended by ADR-0020 for pinned model delivery. |
| **ADR-0011** | ACCEPTED | Non-destructive editing pipeline: JSON edit recipe sidecar + XMP export compatibility. |
| **ADR-0012** | ACCEPTED | Lightweight video editor scope (trim, mute, slow-mo, frame extraction, GIF/WebP export via Media3 Transformer). |
| **ADR-0013** | ACCEPTED | GitHub Actions CI (`ci.yml`) as canonical build, lint, and test verification environment. |
| **ADR-0014** | ACCEPTED (Amended in r16, r21/r22) | Multi-layer privacy enforcement: Gradle merged-manifest `INTERNET` permission gate + JVM `PrivacyPolicyTest` + instrumented `PrivacyEgressTest`. |
| **ADR-0015** | ACCEPTED | Paging 3 window-lattice pagination (`WindowPagingSource`) over MediaStore timeline queries. |
| **ADR-0016** | ACCEPTED | Removal of flaky software-emulated Android emulator tests from CI; CI runs fast JVM + lint + assemble gates (`PrivacyEgressTest` retained as manual device release gate). |
| **ADR-0017** | ACCEPTED | Hidden Vault cryptography: PBKDF2-HMAC-SHA256 PIN digest + Android KeyStore AES-256-GCM key + streaming chunked AEAD sealing + crash-safe atomic index ordering. |
| **ADR-0018** | ACCEPTED | Signed pre-release pipeline (`release.yml`): env-injected PKCS12 signing config + hard `apksigner` certificate SHA-256 digest pin gate (`8a4640e0...`). |
| **ADR-0019** | ACCEPTED (Amended in r11) | Vault biometric unlock binding: opt-in `BIOMETRIC_STRONG` (Class 3) `CryptoObject`-bound KeyStore key with mandatory `"Use PIN"` negative button and PIN fallback. |
| **ADR-0020** | ACCEPTED (Revised in r22) | Ente Rust ML engine vendoring (`AGPL-3.0`, pinned @ `423b2d80...`) + model-download-only `INTERNET` policy restricted to allowlisted `https://models.ente.com/` (Issue `#94`). |

---

## Cron Campaign Log (Phases 6–8) — append-only cross-run state

> The scheduled campaign's cross-run ledger: one numbered entry per run,
> appended (never rewritten) at the end of each run. Scope: roadmap
> Phases 6–8 (toadGALLERY Edit v1 → v2 → v3), the Issue #14 gate first.
> Ownership boundaries: Magic Search / Things belong to the sibling agent
> (Workstream 7 files untouched); Phase 5 rounds r26–r28 belong to the
> sibling orchestrator (r28 rides PR #100); device dogfood is human-only.

### Run #1 — 2026-11-03 ~17:05 IST

* **Start state**: `origin/main` at `88bb21f` (r27 merged; `versionCode 32`; 1011 JVM + 526 Rust + 20 JNI green; one open sibling PR #100 = r28 hardening, untouched).
* **Scope decision**: Phase 5 r26–r27 already merged; r28 open as the sibling's PR #100 → skipped per the no-takeover rule. The Issue #14 gate (Phase 6's required entry prototype) was next in strict order → became this run's round.
* **Round executed**: `r29` — the contract-gate round, branch `tg-p6-contract-gate`, PR #104 (base main): `:shared:editor-contract` pure-JVM module + `:apps:edit` toadGALLERY Edit prototype APK (versionCode 1) + the gallery resolution/launch/Edit-op half + CI steps for the two new modules. Gallery `versionCode 33`.
* **Tests added**: ~40 contract-module + ~35 edit-app + 9 gallery JVM tests; gallery total 1011 → 1020. No existing test touched.
* **CI runs**: (pending at write time — recorded on the merge when green.)
* **Next steps for run #2**: verify PR #104's CI (dispatch if the pull_request event stalls), squash-merge when green, record the sha here and in the ledger, close Issue #14 in the merge, then start Phase 6 proper — Edit v1 slice 1: the editor's crop/rotate/flip/straighten/perspective toolset + the non-destructive JSON sidecar recipe layer (ADR-0011) on top of this round's session/export pipeline.
