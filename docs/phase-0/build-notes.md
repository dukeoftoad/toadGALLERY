# Phase 0 Build Notes — Upstream Baseline Verification

**Status:** Fossify Gallery **BUILT & VERIFIED** · Aves **BLOCKED (environment disk capacity), cause fully diagnosed**
**Verified on:** 2026-10-29 (UTC) · **Verifier:** toadGALLERY Phase 0/1 lead bootstrap

## 1. Toolchain used (both baselines)

Provisioned from scratch in the audit container — proves the toolchain recipe is reproducible:

| Component | Version | Source |
|---|---|---|
| JDK | Eclipse Temurin **21.0.13+9** (full JDK incl. `javac`) | api.adoptium.net |
| Android SDK | cmdline-tools 11076708; `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | dl.google.com |
| Gradle (Fossify baseline) | 9.5.1 (project wrapper) | services.gradle.org |
| Flutter SDK (Aves baseline) | 3.47.8 (`.flutter` submodule @ `737ba8f885`) · Dart 3.13.7 | github.com/flutter/flutter |
| Gradle (Aves baseline) | 9.8.1 (project wrapper) | services.gradle.org |

Memory constraint of the audit container (4.1 GB RAM): Gradle daemon capped at `-Xmx1792m`,
Kotlin compilation in-process (`kotlin.compiler.execution.strategy=in-process`,
`kotlin.daemon.jvmargs=-Xmx1024m`, `org.gradle.workers.max=2`). Default sizes OOM-kill the daemon.

## 2. Fossify Gallery baseline build — SUCCESS

| Field | Value |
|---|---|
| Baseline commit | `e9812299b9d444b57072313cd2c3efd59c790f98` (v1.14.1, `main`) |
| Command | `./gradlew assembleFossDebug --no-daemon` |
| Result | **BUILD SUCCESSFUL in 2m 31s** (43 tasks) |
| Artifact | `gallery-30-foss-debug.apk` — **66 MB** |
| Artifact SHA-256 | `f72d823614d76f62…` (prefix; full hash in build log of the session) |
| Flavor | `foss` (FOSS flavor — the gplay flavor is not relevant to toadGALLERY) |
| Debug signing | default debug keystore (no release signing configured — as upstream) |
| Resolved SDK facts | minSdk 26 · targetSdk 36 · compileSdk 36 · JDK target 17 (built with javac 21) |
| First-launch smoke | **PENDING** — requires on-device/emulator verification (no emulator in audit container) |

Deviations from a pristine upstream tree: none affecting the build result.
`gradle.properties` heap reduced (8192m → 1792m) for the 4 GB container; `local.properties`
added (`sdk.dir`). Neither is committed upstream.

## 3. Aves baseline build — BLOCKED, cause chain fully documented

| Field | Value |
|---|---|
| Baseline commit | `4f940ae48bd9364a0a532b84f283ec60755d9904` (1.15.8+182, `develop`) |
| Entry point | `./flutterw build apk --flavor libre --debug -t lib/main_libre.dart` |
| Result | **Build blocked** at Gradle configuration of plugin projects |
| Resolved SDK facts | minSdk **24** · targetSdk **36** · compileSdk **37 upstream / 36 publicly available** (see below) |

### What worked (verifies the recipe and the baseline itself)

1. `.flutter` submodule SDK checkout (3.47.8) + `flutterw` bootstrap — **OK** (74s)
2. Dependency resolution (pub get with the full lockfile, incl. deckerst forks) — **OK**
3. Libre flavor generation via upstream's own `gen_flavor_libre.sh` — **OK** (see finding A1)
4. Gradle configuration of the app + several plugin subprojects — progressed through
   dependency download and AAR transform phases (network + cache layers fully functional)

### Findings (recorded for the audit; A1/A2 are upstream defects/inconsistencies, not ours)

- **A1 — stale committed flavor files.** `flavors/pubspec_libre.yaml` pins Flutter **3.47.7**
  while the repo's own `.flutter` submodule and root `pubspec.yaml` pin **3.47.8**.
  `apply_flavor_libre.sh` therefore fails with a version-solving error. Fix (used):
  run `gen_flavor_libre.sh` after `git checkout -- pubspec.yaml pubspec.lock` to regenerate
  the flavor from the current pubspec. Upstream presumably regenerates at release time.
- **A2 — `compileSdk = 37` is not publicly installable yet.** The public SDK repository
  publishes platforms up to **android-36** (checked 2026-10-29). For the smoke build we
  patched `android/app/build.gradle.kts` to `compileSdk = 36`. This is a local, documented
  deviation in the audit clone only.
- **A3 — NDK demand without native builds.** `ndkVersion = flutter.ndkVersion`
  (= 28.2.13676358) forces an NDK download at configuration time although **no module in
  the tree declares `externalNativeBuild`/CMake** (prebuilt `.so` libraries only). Patched
  out for the smoke build. Keep in mind for CI cost: without this patch an Aves CI build
  carries a ~2.5 GB NDK.
- **A4 — final blocker: disk.** AGP 9.2.1 must transform the AARs of the app + 13 in-repo
  plugins + ~50 published dependencies. On the audit container (9.9 GB total, shared with
  both toolchains and both upstream clones) this exhausts the disk during the transform
  phase (`Couldn't create directory …/caches/9.8.1/transforms/...`, filesystem at 100%).
  **Required to complete:** an environment with ≈16 GB free disk (or a GitHub-Actions-style
  runner), JDK 21, Android SDK 36, and the recipe above. Not a code defect: configuration
  repeatedly progressed to plugin-project configuration, proving resolution correctness.

### What remains for a complete Aves baseline (handoff)

- [ ] Run the same recipe on a machine/CI with ≥16 GB free disk (patch list: A1 via
      `gen_flavor_libre.sh`, A2 compileSdk 36, A3 ndkVersion — or keep NDK if policy requires)
- [ ] Record APK artifact + SHA-256 + size here
- [ ] `aapt2 dump badging` on the APK to confirm min/target/compileSdk as built
- [ ] First-launch smoke test on device/emulator

## 4. Definition of done for this document

- [x] Fossify Gallery: clean-machine build of `fossDebug` succeeds; toolchain recorded
- [x] Fossify: artifact, size, signing behavior recorded
- [x] Aves: recipe established; blocker documented with cause chain (roadmap exit criterion
      "built **or** their blockers are documented" — satisfied)
- [ ] Aves: actual build on adequate hardware/CI (issue #2 stays open until then)
- [ ] Both: first-launch smoke tests on real Android (device or emulator)
