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

---

## Branch identity card

| | |
|---|---|
| **Role** | Primary integration branch. Every product-lane contribution (`gallery`, `editor`, `ente` syncs, feature branches) lands here first. |
| **Release channel** | **alpha / beta** --- `release-unified.yml` cuts the four-product pre-release bundle from this branch's tip. |
| **Push CI** | `unified-ci.yml` --- the four-product integration gate (below). |
| **Flows to** | `main`, via an approved, human-reviewed **stable promotion PR** (never automated). |
| **Receives from** | `gallery`, `editor`, `ente`, feature branches --- via reviewed PRs. |

The product family integrated here:

| product | applicationId | engine `.so` | built by the gate |
|---|---|---|---|
| Basic Gallery | `com.toadgallery` | none | `:gallery:app:assembleBasicDebug` |
| Gallery Engine | `com.toadgallery.engine` | both (gate) | `:gallery:app:assembleEngineDebug -PwithEngine` |
| Editor | `com.toadgallery.editor` | none | `:editor:app:assembleDebug` + lint + tests + `:shared:editor-contract:test` |
| Unified | `com.toadgallery.unified` | both (gate) | `:unified:app:assembleDebug -PwithEngine` |

A successful Unified APK build alone does **not** prove the family still
builds --- that is why this branch's CI validates all four products, every
push.

## CI on this branch

[`unified-ci.yml`](.github/workflows/unified-ci.yml) triggers on pushes to
`unified` and PRs targeting `unified`, path-filtered over `gallery/**`,
`editor/**`, `unified/**`, `engine/**`, `shared/**`, `models/**`, the gradle
files, and the workflow tree. Jobs:

- **engine-host** --- cargo check + fmt + clippy + tests for the vendored
  Rust engine (`engine/`).
- **gallery-basic** --- Basic Gallery build, lint, unit tests (plus the
  Engine flavor's JVM tests).
- **gallery-engine** --- the engine-flavor gallery debug APK (Rust cross-built
  for Android via cargo-ndk).
- **editor** --- Editor build, lint, unit tests, and the shared
  editor-contract module tests.
- **unified** --- the all-in-one APK, engine-enabled.

The branch also carries the repo-wide dispatch surfaces (`screenshots.yml`,
`unified-apk.yml`, `codeql.yml`, `repo-lint.yml`,
`reproducible-build.yml`, `change-approval.yml`) and the other lanes' CI
files --- each is branch-scoped and stays dormant outside its own lane.

## Releases from this branch

`release-unified.yml` (dispatch-only, checkout pinned to `ref: unified`)
builds and publishes the **four-product alpha/beta bundle** as a GitHub
pre-release:

- `toadGALLERY-<v>-r<N>.apk` --- Basic Gallery
- `toadGALLERY-engine-<v>-r<N>.apk` --- Gallery Engine
- `toadGALLERY-editor-<v>-r<N>.apk` --- the standalone Editor
- `toadGALLERY-unified-<v>-r<N>.apk` --- the all-in-one app

The release page carries the signed APKs **only**; each APK's `.sha256` and
R8 `mapping.txt` ride the dispatch run's Actions artifacts (14-day
retention). Every APK passes the hard `apksigner` gate against the pinned
release-certificate digest (ADR-0018). Branch-local product releases are
**retired** --- the gallery/editor/ente lanes no longer ship their own
releases; alpha/beta bundles originate here and stable releases from `main`.

## Working on this branch

```bash
./gradlew :unified:app:assembleDebug -PwithEngine        # the all-in-one app
./gradlew :gallery:app:assembleBasicDebug                 # focused gallery
./gradlew :gallery:app:assembleEngineDebug -PwithEngine   # engine gallery
./gradlew :editor:app:assembleDebug                       # standalone editor
```

JDK 17, Android SDK (`platforms;android-36`), and --- for engine-bearing
builds --- the Rust toolchain with `cargo-ndk`. See the
[`main` README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md)
for prerequisites and the branch strategy
([`.github/BRANCH_STRATEGY.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/.github/BRANCH_STRATEGY.md))
for the full topology, sync flow, and operator runbook.

**Integrating a lane:** PR from the lane into `unified`; native auto-merge is
acceptable once the integration gate passes. **Promoting to stable:** walk
the definition of done (PROJECT_ARCHITECTURE.md §9), open the human PR
`unified` → `main`, let `ci.yml` validate the exact candidate SHA, merge,
then cut the stable release from `main`.
