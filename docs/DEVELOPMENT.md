# Development

The development, build, test, CI, and release document for the
standalone toadGALLERY Edit repository. Quickstart:
[README.md](../README.md); design: [ARCHITECTURE.md](ARCHITECTURE.md).

The repo builds exactly one APK from four Gradle modules
(`settings.gradle.kts`):

| Module | Purpose |
|---|---|
| `:editor:app` | The standalone Edit APK shell: applicationId, versioning, signing config, the permission gate |
| `:editor:core` | The editor library: photo chassis, tune rail, video editor, export pipeline, AI stages, bundled LUT assets |
| `:shared:editor-contract` | Pure-JVM Gallery-to-Edit contract (identity, version negotiation, request/result parsing) |
| `:shared:video-edit` | Pure-JVM video export plans (trim, speed, mute, frames, animation) |

## 1. Prerequisites and toolchain

Everything is pinned; do not upgrade a pin opportunistically.

- **JDK 17** (Temurin in CI); Kotlin and Java compilation both target
  17 (`app-build-javaVersion` / `app-build-kotlinJVMTarget`).
- **Android SDK**: `platforms;android-36` + `build-tools;36.0.0`.
  `minSdk` 26 (Android 8.0+), `targetSdk`/`compileSdk` 36.
- **AGP 9.2.1 and Kotlin 2.3.10**, pinned in `gradle/libs.versions.toml`
  (KSP rides the same pin; the Compose compiler plugin ships with
  Kotlin). AGP 9's built-in Kotlin reports an unpublished
  `compose-group-mapping` version, so both Android modules pin that
  artifact to 2.3.10 in their build scripts; leave those blocks in
  place.
- **Gradle 9.5.1** via the committed wrapper
  (`gradle/wrapper/gradle-wrapper.properties`); the distribution zip
  is checksum-pinned (`distributionSha256Sum`) and CI validates the
  wrapper jar before any Gradle use.
- **No Rust or native toolchain.** The gallery-era native engine is gone.
  All AI inference runs through the prebuilt
  `com.microsoft.onnxruntime:onnxruntime-android` AAR (1.31.1) from
  Maven Central; the ONNX models are data files downloaded at runtime
  (Section 4), never compiled locally.

Two other pins matter daily: Robolectric 4.17 / Roborazzi 1.77.0
(screenshot tests; Robolectric fetches android-all jars from Maven
Central on first test run) and the Compose BOM 2024.12.01.

## 2. Building

```bash
./gradlew :editor:app:assembleDebug      # debug APK (.debug applicationId suffix)
./gradlew :editor:app:assembleRelease    # R8-minified release; unsigned without signing env
./gradlew :editor:app:lintDebug          # lint is a hard CI gate
```

- Outputs: debug APK `editor/app/build/outputs/apk/debug/`; release
  APK `editor/app/build/outputs/apk/release/*-unsigned.apk` with the
  R8 mapping at `editor/app/build/outputs/mapping/release/mapping.txt`.
- The application id is `com.toadgallery.editor` (debug builds get
  `.debug`); `versionCode`/`versionName` live only in
  `editor/app/build.gradle.kts`, the single source the CI identity
  gates derive from.

### Release signing

The release `signingConfig` is wired only when the `TG_*` environment
is present; absent env means the unsigned APK is the correct output:

```bash
TG_KEYSTORE_FILE=/path/to/keystore \
TG_KEYSTORE_PASSWORD=... \
TG_KEY_ALIAS=... \
TG_KEY_PASSWORD=... \
./gradlew :editor:app:assembleRelease
```

The release keystore is **not in the repository**. It exists only as
GitHub Encrypted Secrets (`TG_RELEASE_KEYSTORE_BASE64`,
`TG_RELEASE_KEYSTORE_PASSWORD`, `TG_RELEASE_KEY_ALIAS`,
`TG_RELEASE_KEY_PASSWORD`), decoded by the release workflow into
runner-local storage; the signer certificate SHA-256 is pinned in the
release workflow so an unsigned or debug-signed APK can never be
published. Losing the keystore loses the sideload update channel.

Under `tools/`: `ci-signing/ci-debug.p12` is a public-by-design debug
keystore (PKCS#12, alias `ci-debug`, passwords `android`) kept so
CI-produced debug builds share one stable identity — it grants no
trust, and the `.gitignore` guard on `*.jks`/`*.keystore` protects the
release key and deliberately does not match it. `gen_bundled_luts.py`
generates the bundled `.cube` LUTs in `editor/core/src/main/assets/luts/`;
`kotlin_lex_check.py` is a Kotlin-source lint helper.

## 3. Testing

```bash
./gradlew :editor:app:testDebugUnitTest            # app-shell JVM tests
./gradlew :editor:core:testDebugUnitTest           # the full editor suite
./gradlew :shared:editor-contract:test             # pure-JVM contract tests
./gradlew :shared:video-edit:test                  # pure-JVM export-plan tests
```

The PR gate (`ci.yml`) runs the app and contract suites; the
editor-branch lane (`editor-ci.yml`) additionally runs the full
`:editor:core` suite and `:shared:video-edit`.

### Screenshot tests (Roborazzi)

`editor/core` renders real Compose UI under Robolectric and captures
PNG baselines. The Roborazzi Gradle plugin pins the capture output to
the committed location `editor/core/src/test/resources/roborazzi/` —
the recorded artifact and the committed baseline are the same path, no
copy step. Tests pin Robolectric's NATIVE graphics mode and sdk 34 and
use fully-qualified-name baseline file names; a plain unit-test run
never records (capture is a no-op outside the Roborazzi task graph).

```bash
./gradlew :editor:core:recordRoborazziDebug    # capture fresh baselines
./gradlew :editor:core:verifyRoborazziDebug    # fail on pixel drift
./gradlew :editor:core:compareRoborazziDebug   # write diff overlays for triage
```

- **Re-record policy.** Baselines are committed files, not build
  outputs: record, review the PNGs, and commit them at the pinned
  path. Re-record deliberately after an intentional UI change;
  unexpected verify failures are regressions until proven otherwise.
  The `verify` task only bites once baselines exist; with zero
  committed PNGs the CI step skips loudly instead of passing vacuously.
  `screenshots.yml` is manual-dispatch only (`mode=record` or
  `mode=verify`); day-to-day PRs are carried by assemble, lint, and
  the unit suites, and re-arming the screenshot PR gate is a small
  workflow change.

## 4. Privacy and permission gates

The app's merged manifest may declare **exactly one permission**:
`android.permission.INTERNET`. Enforcement is two-layered.

1. **Build-time gate.** `EditPermissionGateTask` in
   `editor/app/build.gradle.kts` reads the merged manifest of every
   variant and fails the build unless INTERNET is present and nothing
   else is. It anchors on `assemble*`, `bundle*`, and `check`, so no
   verified artifact can bypass it. Run it directly with:

   ```bash
   ./gradlew :editor:app:gateDebugPermissionSet
   ```

   If a dependency injects an extra permission, find it with
   `./gradlew :editor:app:dependencies`.

2. **Release-time re-check.** The release workflow runs `aapt2 dump
   badging` on the final signed APK and refuses to publish if any
   uses-permission falls outside its allowlist (INTERNET plus the
   documented library merges: media3's `WAKE_LOCK`,
   `ACCESS_NETWORK_STATE`, legacy `READ_EXTERNAL_STORAGE`; the pre-Q
   `WRITE_EXTERNAL_STORAGE`; androidx.core's per-app
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` guard). An extra
   permission is a privacy regression; a missing one only breaks a
   feature.

### Why INTERNET exists: the on-demand AI models

The four ONNX models are **not bundled** in the APK or the repo. They
download on demand, at most once, into app-private storage
(`filesDir/models/`) the first time the corresponding AI tool is used:

- An explicit consent prompt names the missing models and their total
  size; nothing downloads before the user confirms, and refusal or a
  failed download leaves the feature degraded.
- Downloads come from the single pinned base
  `https://github.com/dukeoftoad/toadGALLERY/releases/download/onnx-models-v1`
  (`EditAiManifest.DOWNLOAD_BASE`). No other host, no analytics, no
  telemetry.
- Each file streams to a `.part` file, is verified against the pinned
  byte size and SHA-256, and is renamed into place atomically; a
  failed download leaves nothing behind. Presence and size are
  re-checked before every inference. Downloader: `EditAiModelStore.kt`;
  pins: `EditAiManifest.kt`.

| Model file | Bytes | Feature |
|---|---|---|
| `erase-segmentation-u2netp.onnx` | 4,574,861 | magic erase + sky saliency |
| `erase-inpaint-migan.onnx` | 29,546,882 | magic-erase fill |
| `bokeh-depth-dav2-small-int8.onnx` | 27,258,801 | bokeh depth |
| `retouch-faces-yunet.onnx` | 232,589 | face-retouch localization |

Total: 61,613,133 bytes, pinned as the `TOTAL_DOWNLOAD_BYTES` literal
and asserted as the four-size sum by `EditAiManifestTest`.

### Updating the model pins

When re-hosting the models (new tag or new file bytes):

1. Download the new assets and recompute `sha256sum <file>.onnx` plus
   the exact byte size.
2. Edit `EditAiManifest.kt`: update each `ModelFile`'s `bytes` and
   `sha256`, and `DOWNLOAD_TAG`/`DOWNLOAD_BASE` if the hosting release
   changes. Keep the lowercase 64-hex digest form.
3. Update the byte-budget literal in `EditAiManifestTest` (currently
   `61_613_133`); the sum-of-parts test fails until both agree.
4. The test suite verifies catalog consistency only (order, store
   paths, URL shape, digest well-formedness, byte budget) because the
   model bytes are not committed; digests are enforced on device at
   download time. Sanity-check each pin by hashing the exact file
   published at the pinned URL.

## 5. CI

All workflows live under `.github/workflows/` and build through the
shared composite action `.github/actions/toad-setup` (wrapper
validation, Temurin JDK 17, SDK platform 36 + build-tools 36.0.0,
Gradle cache). Every workflow declares least-privilege
`permissions: contents: read` except the release workflow; a green
`ci.yml` run gates every merge, and release signing never runs in PR
context.

| Workflow | Triggers | Purpose |
|---|---|---|
| `ci.yml` | PRs to `main`, dispatch | Canonical PR gate: debug APK build, lint, app + contract unit tests; uploads reports. |
| `editor-ci.yml` | Push to `editor` branch (editor/shared/gradle paths), dispatch | Branch lane: debug APK, lint, app + contract + `:shared:video-edit` tests, plus the full `:editor:core` suite. |
| `apk-build.yml` | Dispatch | Opt-in test artifacts in a fail-fast matrix: `edit-debug` (aapt2 identity gate), `edit-release` (unsigned R8 gate, QA only), `contract` (assemble + tests + jar). |
| `release.yml` | Dispatch | Only signing/publishing path: signed pre-release APK, hard signature gate, aapt2 permission re-check, APK + `.sha256` + R8 mapping. Holds `contents: write`. |
| `reproducible-build.yml` | Push of `v*` tags, dispatch | Builds the unsigned release APK twice from clean (`--no-build-cache`) and byte-compares APK and mapping; uploads the verdict. |
| `screenshots.yml` | Dispatch (`record`/`verify`) | Roborazzi baseline capture and drift gate for `editor/core`. |
| `codeql.yml` | Push/PR to `main`, weekly schedule, dispatch | CodeQL analysis over `java-kotlin` (autobuild) and `actions` workflow files. |
| `change-approval.yml` | PRs to `main` | Non-author approval required when sensitive paths (workflows, actions, Gradle files) change; skipped for the repo owner (solo maintainer). |
| `repo-lint.yml` | Push/PR to `editor` (`.github/**`), dispatch | Whitespace check plus actionlint over the workflow YAML (digest-pinned image). |

## 6. Releases

### Reproducible builds

CI is the canonical build environment: every workflow builds with the
pinned toolchain through `toad-setup`, so a local build with the same
JDK/SDK/wrapper matches CI's output. Verified on every `v*` tag: the
unsigned release APK and the R8 `mapping.txt` are rebuilt twice, from
clean and with `--no-build-cache` (cached task outputs cannot fake a
pass), and byte-compared. Verdicts: BYTE-REPRODUCIBLE,
CONTENT-REPRODUCIBLE (bytes differ only in zip metadata; mappings
must still byte-match), NOT-REPRODUCIBLE. The comparison target is
the **unsigned** artifact — signed-APK determinism is not claimed,
because signing embeds signature blocks and timestamps; this is also
why F-Droid verification compares against a from-source unsigned
build.

To verify independently:

```bash
git clone https://github.com/dukeoftoad/toadGALLERY.git
cd toadGALLERY && git checkout <release-tag>
# toolchain: JDK 17 + Android SDK platform 36 + build-tools 36.0.0
./gradlew :editor:app:assembleRelease
cp editor/app/build/outputs/apk/release/*-unsigned.apk /tmp/rb1.apk
cp editor/app/build/outputs/mapping/release/mapping.txt /tmp/rb1-map.txt
./gradlew clean && ./gradlew :editor:app:assembleRelease
cmp /tmp/rb1.apk editor/app/build/outputs/apk/release/*-unsigned.apk
cmp /tmp/rb1-map.txt editor/app/build/outputs/mapping/release/mapping.txt
```

On an APK byte mismatch, unzip both and `diff -r`: identical contents
means zip-metadata variance, not a failure. A mapping mismatch is
always a real finding (R8 is deterministic).

### Versioning and tagging

- `versionCode` bumps once per release; `versionName` follows the
  `0.4.x-edit-alpha` scheme. Both live in `editor/app/build.gradle.kts`
  only.
- Releases are cut deliberately: dispatch `release.yml` on the
  reviewed SHA, with an explicit tag or an empty tag input to compute
  the default `v<versionName>-r<run_number>` (for example
  `v0.4.14-edit-alpha-r72`) at the built SHA. The workflow creates the
  tag; a release is never published from an unreviewed tree.
- It publishes the APK (`toadGALLERY-edit-<version>-r<round>.apk`) with
  its `.sha256` and the R8 mapping, always as a prerelease with
  `make_latest: false` until the v1.0 stable cut (a human decision).
- The reproducibility measurement triggers on `v*` tag pushes; for a
  tag created inside the release run, dispatch `reproducible-build.yml`
  against the tag ref and record the verdict in the release notes.

## 7. F-Droid

The live metadata is `fdroid/metadata/io.github.dukeoftoad.gallery.edit.yml`
for package `io.github.dukeoftoad.gallery.edit` (repo
`https://github.com/dukeoftoad/toadGALLERY.git`, `RepoType: git`,
`License: GPL-3.0`).

- **Build from source.** `gradle: yes` with output glob
  `editor/app/build/outputs/apk/release/*-unsigned.apk`; the only
  `scanignore` is the standard `gradle/wrapper/gradle-wrapper.jar`. The
  build environment is exactly the CI recipe (JDK 17, SDK platform 36,
  build-tools 36.0.0, wrapper Gradle 9.5.1) — no sudo, no NDK, no
  native toolchain. F-Droid builds unsigned and re-signs, hence
  `Binaries: []` and the unsigned reproducibility target.
- **Network disclosure.** The Description and MaintainerNotes disclose
  the on-demand AI model downloads from the single pinned host with
  SHA-256 pins; no other network use exists, and `AntiFeatures: []`
  holds — no ads, tracking, or nonfree-net surface.
- **Update policy.** `AutoUpdateMode: None`; `UpdateCheckMode: Tags`
  with the pattern `v([0-9.]+-alpha)-r[0-9]+`. Build entries are
  curated by hand: each release adds one entry pairing the editor's
  `versionCode`/`versionName` with the release tag's commit. Submit or
  update the fdroiddata merge request only after the tag exists —
  `fdroid build` needs the commit to resolve.
- **Refresh before submission.** The committed metadata predates the
  editor-only split: it carries the pre-split package id, repo URL,
  and a newest Build entry of `0.4.11-edit-alpha` (versionCode 16),
  while the current tree builds `com.toadgallery.editor` at versionCode
  19 / `0.4.14-edit-alpha`. Refresh the package fields, source URL, and
  Build entries as part of the submission work.

## 8. Known limitations

Editor-relevant items only; all recorded, scheduled work.

- **Pre-releases only until v1.0.** The stable cut waits on the
  human-only device-validation rows; no performance or compatibility
  number is device-verified yet.
- **Device validation is a manual, human-only practice.** Install over
  the previous build on a real device, walk the checklist sections,
  record pass/fail with a logcat snippet for anything unexpected;
  performance and on-device claims are decided by a person on
  hardware, never asserted from code. Standing editor rows: the
  contract round trip, the export robustness matrix (metadata policy
  chips, GPS strip, >48 MP guard, low storage, process death), the
  TalkBack sweep, per-tool rows (LUT import/remove, frames, text,
  stickers, drawing, mosaic/blur), AI latency/quality/airplane-mode
  behavior, and the long-video / 4K / HDR matrix.
- **Magic-erase quality bound.** The inpaint fill patch is bounded by
  the session's working resolution, so an erased region on a very
  large export can come out softer than its surroundings;
  full-resolution export-time re-inference is the recorded follow-up.
- **Sky replacement mask.** Sky masking uses the u2netp saliency's
  above-the-subject region, not a portrait-grade sky segmentation;
  mask bleed on portrait subjects is a known follow-up.
- **Face retouch is one global selection.** Smoothing/eye-brighten/
  face slim apply across all detected faces at once; per-face tuning
  is a separate round.
- **Video editor scope.** No video crop/rotate in v1; the session
  state is transient (no persisted video recipe sidecar), so video
  edits are not re-editable across sessions. The non-destructive rule
  holds: every export is a new MediaStore insert, no replace-original
  path.
- **Accessibility remainder.** Layer positioning (text, stickers,
  drawing) is a drag gesture that is not TalkBack-reachable; the
  family-wide fix is scheduled. Imported stickers have no remove
  affordance; strokes have no per-point editing; linear blur is
  horizontal-only; mosaic/blur regions use hard mask edges; only one
  frame mounts at a time.
- **Distribution posture.** No cloud sync, accounts, ads, or analytics,
  by design. Distribution is GitHub pre-releases plus F-Droid
  metadata; there is no Play Store pipeline. The signing identity is
  single: losing the release keystore makes sideload updates over the
  old identity impossible; users must uninstall and reinstall.
