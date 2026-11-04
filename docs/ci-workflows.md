# CI workflows — quick reference

All workflows live under `.github/workflows/`. Canonical gate: a green `CI`
run is required to merge any PR (ADR-0013). Release signing lives only in
`Release` (ADR-0018). This note focuses on the two debug-APK builders added
in the 2026-11-04 workplan window (`docs/workplan.md` P4); full workflow
inventory at the bottom.

---

## The two debug-APK builders (added 2026-11-04)

### `apk-build-engine.yml` — "APK build with engine"

- **Purpose:** opt-in `:apps:gallery` APK with the vendored Ente engine
  packaged (`-PwithEngine`: cargo-ndk `libtoad_ml_jni.so` + the
  sha256-pinned ORT 1.31.0 `libonnxruntime.so`, arm64-v8a). Both `.so`
  files are verified inside the APK before artifacts upload; the APK
  identity (variant-correct application id + the checkout's own
  versionCode) is gated via `aapt2`.
- **Trigger:** `workflow_dispatch` only (no tag/PR triggers — `ci.yml`
  gates PRs/main, `release.yml` owns `v*` tags).
- **Inputs:** `build_variant` (`debug` default / `release`);
  `engine_version` (`pinned` default — the vendored pin in
  `docs/phase-5/ente-pin.md`; a commit sha matching the pin is accepted,
  anything else fail-fasts: the engine is vendored, not swappable).
- **Artifacts:** the APK + `.sha256` + R8 `mapping.txt` when present
  (debug builds are not minified — expected warning); `build-output.log`
  uploaded only on failure.
- **Caching:** same keys as `ci.yml`'s `engine-android` job
  (`cargo-android-*`, `cargo-ndk-*`, `gradle-*`) — warm from main's CI.
- **Concurrency:** `apk-build-engine-<ref>`, cancel-in-progress.
- **Secrets (by name):** none for the default `debug` variant (debug key).
  The `release` variant requires `TG_RELEASE_KEYSTORE_BASE64`,
  `TG_RELEASE_KEYSTORE_PASSWORD`, `TG_RELEASE_KEY_ALIAS`,
  `TG_RELEASE_KEY_PASSWORD` and fail-fasts with explicit messages when
  they are missing. No secret is ever echoed; no tag, no GitHub Release
  (`release.yml` stays the only signing release path, ADR-0018).

### `editor-apk-build.yml` — "Editor APK build — include all"

- **Purpose:** builds ALL editor targets in ONE matrix job
  (`strategy.fail-fast: false`): `edit-debug` (the `.debug` dogfood APK,
  identity-gated), `edit-release` (the R8/compile gate — **unsigned by
  design**, the editor declares no signing config and ADR-0018 keeps
  release signing in `release.yml`), and `contract`
  (`:shared:editor-contract` assemble + JVM tests + the contract jar).
- **Trigger:** `workflow_dispatch` only.
- **Inputs:** `build_variant` (`all` default / `debug` / `release`) —
  narrows the assemble legs; the `contract` leg always runs.
- **Artifacts:** `toadGALLERY-Edit-<version>-debug` (debug key),
  `toadGALLERY-Edit-<version>-release-UNSIGNED` (QA only),
  `editor-contract-jar`; build log per failing leg.
- **Caching:** the `gradle-*` keys shared with `ci.yml`'s build job. No
  Rust/NDK steps — the editor is pure Kotlin.
- **Concurrency:** `editor-apk-build-<ref>`, cancel-in-progress.
- **Secrets (by name):** none — both editor legs are unsigned by design.

### Reuse & cost notes

- The repo has no composite actions / reusable workflows; both files
  replicate the canonical setup blocks verbatim (same pins, same cache
  keys) and are pinned to the repo's action majors. Extraction into a
  composite action is tracked in `follow-up.md` (F-2) — do it in a
  dedicated low-risk window, not mid-campaign.
- Both workflows are `workflow_dispatch`-only: **zero CI cost unless
  dispatched**. The engine build is the expensive one (~40 min budget);
  prefer dispatching it only when a device artifact is actually needed.

---

## Full inventory

| Workflow | Triggers | Purpose |
| --- | --- | --- |
| `ci.yml` | push/PR to `main`, dispatch | Canonical gate: engine cargo check/fmt/clippy + tests, build + lint + JVM tests, engine-ABI assemble gate (no upload) |
| `engine-apk.yml` | push to `tg-engine-apk-*`, dispatch | Branch-scoped engine debug APK test build (round-convention staging) |
| `modelpack-apk.yml` | push to `tg-modelpack-*`, dispatch | Offline model-pack engine APK (`-PwithBundledModels`, staged + sha256-verified FACE pack) |
| `apk-build-engine.yml` | dispatch | **New:** generalized input-driven engine APK builder (see above) |
| `editor-apk-build.yml` | dispatch | **New:** all editor targets in one matrix (see above) |
| `release.yml` | push `v*` tags, dispatch | Signed pre-release publication (hard signature gate, ADR-0018) |
