# CI workflows — quick reference

All workflows live under `.github/workflows/`. Canonical gate: a green `CI`
run is required to merge any PR (ADR-0013). Release signing lives only in
`Release` (ADR-0018).

**Organization (r49, the workflow-organization round):** FIVE files, one
job per concern — `ci.yml` (the canonical gate), `apk-build.yml` (all
opt-in test-APK builds behind ONE dispatch surface), `screenshots.yml`
(the Roborazzi record/verify harness — **suspended as a PR gate in r68**,
manual dispatch only), `release.yml` (the only signing +
publishing path), `reproducible-build.yml` (the determinism verifier).
The r49 pass also set the repo-wide least-privilege baseline: every
workflow declares an explicit `permissions:` block (`contents: read`
everywhere except `release.yml`, which needs `contents: write` to cut a
tag and publish).

**Shared toolchain (F-2, delivered 2026-11-04):** the canonical setup
(JDK 17 + Android SDK platform 36/build-tools 36 + Gradle/cargo caches;
optional Rust host-gates or Android-cross shapes) lives in the composite
action `.github/actions/toad-setup` — every workflow calls it instead of
replicating the blocks. Inputs: `rust` (`''` / `host` / `android`) and
`android` (`'true'`/`'false'`, string inputs — the universally compatible
composite convention).

---

## `apk-build.yml` — "APK builds (test artifacts)" (r49 consolidation)

ONE dispatch surface for every opt-in test-APK build. Replaces FOUR
near-duplicate pipelines (the 2026-11-04 workplan pair
`apk-build-engine.yml` + `editor-apk-build.yml`, and the branch-scoped
2026-11-03 pair `engine-apk.yml` + `modelpack-apk.yml`), whose engine
build chain was written out three times with drift between the copies
(the modelpack file staged under a hardcoded r28 artifact name and gated
versionCode >= 32 while the checkout moved to 38+).

- **Targets (dispatch input `target`):** the dispatch choices carry the
  `gallery-*` prefix; the prepare job normalizes to the short target
  names (`engine` / `modelpack` / `editor`) — the r49 shape passed the
  input through unmapped, so every gallery dispatch (including the
  default) failed the unknown-target check until r50 fixed it.
  - `gallery-engine` — the engine-enabled gallery APK (the ENGINE flavor,
    `-PwithEngine`: cargo-ndk `libtoad_ml_jni.so` + the sha256-pinned ORT
    1.31.0 `libonnxruntime.so`, arm64-v8a). Both `.so` files verified
    inside the APK before artifacts upload.
  - `gallery-modelpack` — the same + the offline model pack, the WHOLE
    pack since r50: FACE (detector + embedder) AND semantic CLIP (image +
    text + vocab) (`-PwithBundledModels`: all five assets are staged from
    models.ente.com and size+sha256-verified BEFORE packaging; the APK's
    `assets/models/` content is gated for all five too — the engine
    flavor enables the semantic index, so a FACE-only pack left CLIP
    falling through to the network, which contradicted the artifact's
    whole offline point). Debug variant only — an offline-provisioning
    dogfood build, not a release (ADR-0020); ~213 MB larger than the
    plain engine build.
  - `editor` — all editor targets in one `fail-fast: false` matrix:
    `edit-debug` (the `.debug` dogfood APK, identity-gated),
    `edit-release` (the R8/compile gate — **unsigned by design**), and
    `contract` (`:shared:editor-contract` assemble + JVM tests + the
    contract jar; always runs).
- **Branch-scoped triggers survive:** pushes to `tg-engine-apk-*` /
  `tg-modelpack-*` still build (the isolated-work-session convention) —
  the target is inferred from the branch name; anything unrecognized
  fail-fasts with the fix in the message.
- **Variants:** `gallery_variant` (`debug` default / `release` — release
  signs with the ADR-0018 `TG_*` secrets and fail-fasts on any missing
  secret BEFORE the toolchain setup spends minutes); `editor_variant`
  (`all` default / `debug` / `release`); `engine_version` (`pinned`
  default — validated against the vendored pin in
  `docs/phase-5/ente-pin.md`; anything else fail-fasts: the engine is
  vendored, not swappable).
- **Version/identity gates:** every artifact's application id +
  versionCode are verified via `aapt2` against values DERIVED from the
  checkout's own gradle file — first live assignment for the gallery
  (its gradle keeps history as comments), last for the editor (it
  appends live pairs, last-wins — the r43 catch). No hardcoded versions
  anywhere; nothing to rot when the version moves.
- **Artifacts:** `toadGALLERY-<versionName>-engine-<variant>` for the
  engine target / `toadGALLERY-<versionName>-modelpack-engine-<variant>`
  for the model pack (r50: no more `-engine-engine` doubling on the
  engine target) (+ `.sha256` + R8 `mapping.txt` when present — debug
  builds are not minified, expected warning), `toadGALLERY-Edit-<version>-debug`,
  `toadGALLERY-Edit-<version>-release-UNSIGNED` (QA only),
  `editor-contract-jar`; build log uploaded only on failure.
- **Caching:** the same toad-setup cache keys as `ci.yml` (`gradle-*`,
  `cargo-android-*`, `cargo-ndk-*`) — warm from main's CI.
- **Concurrency:** `apk-build-<ref>`, cancel-in-progress.
- **Permissions:** `contents: read` (explicit, least-privilege).
- **Cost:** dispatch-only (plus the two branch patterns): **zero CI cost
  unless dispatched**. The gallery engine build is the expensive one
  (~45 min budget); prefer dispatching it only when a device artifact is
  actually needed.

---

## Full inventory

| Workflow | Triggers | Purpose |
| --- | --- | --- |
| `ci.yml` | push/PR to `main`, dispatch | Canonical gate: engine cargo check/fmt/clippy + tests, build + lint + JVM tests, engine-ABI assemble gate (no upload). `permissions: contents: read`. |
| `apk-build.yml` | dispatch; push to `tg-engine-apk-*` / `tg-modelpack-*` | The consolidated opt-in test-APK builder: gallery-engine / gallery-modelpack / editor targets behind one input surface (see above). |
| `screenshots.yml` | **dispatch only (r68 suspension)** | Roborazzi record/verify, run MANUALLY: record = the deliberate per-phase baseline pass (gallery state cards at `gallery/core/src/test/resources/roborazzi/` + `gallery/core/roborazzi/`, editor chassis at `editor/core/src/test/resources/roborazzi/`); verify = on-demand drift check. The pull_request trigger was removed in r68 (2026-11-12): PRs are carried by the per-branch lint/assemble/unit lanes instead; re-arming is a three-line revert. |
| `reproducible-build.yml` | push `v*` tags, dispatch | Byte-reproducibility measurement: two clean `--no-build-cache` release builds compared APK-pair + mapping-pair. `permissions: contents: read`. |
| `release.yml` | push `v*` tags, dispatch | Signed pre-release publication (hard signature gate, ADR-0018). The only workflow with `contents: write`. |
