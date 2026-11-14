# toadGALLERY --- `gallery` (the Gallery product lane)
# toadGALLERY --- `unified` (the integration & alpha/beta branch)

**toadGALLERY** is a private, offline-first Android gallery family built from
scratch in Kotlin + Jetpack Compose (inspired by Mi Gallery, Aves libre, and
Fossify Gallery --- features and UX only, no upstream code, ADR-0008). The
full project story, screenshots, and goals live in the
[`main` README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md);
the authoritative policy is
[`docs/PROJECT_ARCHITECTURE.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/PROJECT_ARCHITECTURE.md).

**This branch is the primary integration branch of the virtual fork** ---
where the whole four-product family comes together.

The two invocations are deliberately separate: `-PwithEngine` is a global
gradle property, and the Basic APK must never inherit it (the release
pipelines enforce this with a hard negative packaging gate --- the Basic APK
must carry **neither** `libtoad_ml_jni.so` nor `libonnxruntime.so`, while
Engine must carry **both**).

The branch also carries the repo-wide dispatch surfaces and the other lanes'
CI files --- each is branch-scoped and stays dormant outside its own lane.

## What lives here

- `gallery/app` --- the gallery application module (flavors: `basic` /
  `engine` over one "engine" dimension; app IDs `com.toadgallery` /
  `com.toadgallery.engine`).
- `gallery/core` --- the gallery Kotlin/Compose core: media access, catalogue,
  metadata layer, search (incl. Magic Search), People/Places/Things, map,
  vault, trash, collage --- plus the Roborazzi screenshot baselines.
- `engine/` --- the vendored Rust engine (shared with the other lanes through
  the normal flow; cargo-gated).
- `shared/` --- the editor-contract (the versioned Gallery ↔ Edit handoff)
  and the video-edit plan module.

The current version line is `0.4.5-alpha` (versionCode 46).

## Working on this branch

JDK 17 + Android SDK (`platforms;android-36`); the Rust toolchain with
`cargo-ndk` for engine-bearing builds. Lint and tests:

```bash
./gradlew :gallery:app:lintBasicDebug
./gradlew :gallery:app:testBasicDebugUnitTest :gallery:app:testEngineDebugUnitTest
```

**When the lane's work is ready:** confirm `gallery-ci.yml` is green on the
tip, then open a PR into `unified` --- the four-product integration gate
validates the whole family there. Backports from `main` are manual and rare;
this lane tracks `unified`, not `main`. See
[`.github/BRANCH_STRATEGY.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/.github/BRANCH_STRATEGY.md)
for the full topology and operator runbook.
