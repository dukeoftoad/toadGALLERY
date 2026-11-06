# r50 Audit Verification Round & r51 Fix Plan

**Date:** 2026-11-06
**Verified tree:** `main` @ `fa954fa` (r50: #132 `c1d368d` + #133 `fa954fa`)
**Audited tree:** `main` @ `7e9114b` (r49)
**Companion work-order:** [`2026-11-05-backend-and-ente-audit.md`](2026-11-05-backend-and-ente-audit.md) — the 79-finding audit this round verifies (lands verbatim via this PR; supersedes draft #131).

**Method (evidence, not guesswork):** the baseline→HEAD delta is exactly **72 files** (`git diff --name-only 7e9114b..fa954fa`). 63 findings cite only files **outside** that delta → byte-identical to the audited tree → **open by definition** (no verification needed). The **16 findings** citing delta files were re-verified line-by-line at HEAD by three parallel verification tracks (CI/build, ML pipeline, privacy/licensing), each verdict backed by `file:line@HEAD` quotes and the actual #132/#133 diff hunks. No device runs, no cargo/gradle execution (ADR-0013).

---

## 1. Scoreboard

**79 findings → 2 FIXED · 6 PARTIALLY FIXED · 71 OPEN.**

### The 7 high-severity findings at HEAD

| # | ID | Title (short) | Verdict |
| --- | --- | --- | --- |
| 1 | C-1 | modelpack workflow corrupted SHA-256 | **FIXED** (by #132) |
| 2 | E-1 | null cursor → mass tombstone | **OPEN** |
| 3 | E-2 | `IN (:keys)` > 999 host params (API ≤ 30) | **OPEN** |
| 4 | B-1 | JNI `put*` exports can unwind → SIGABRT | **OPEN** |
| 5 | C-2 | UI teardown kills live index queue | **PARTIAL** (mitigated by #132 F-38) |
| 6 | C-3 | reset races `NonCancellable` state save | **PARTIAL** (mitigated by #132 F-38) |
| 7 | E-3 | provenance healing starves on failing rows | **OPEN** |

High-severity net: **1 fully fixed, 2 mitigated, 4 untouched.**

---

## 2. Verified findings (the 16 delta-touched) — verdicts, evidence, residuals

### 2.1 CI / build config

**C-1 [High] — FIXED.** `apk-build.yml:305-307` now pins `0763fc33f54e138476194da95987e133b3e976075a6b1d3e1b2caedb251b1a36` for `mobilefacenet_portable_static_b1.onnx` — byte-identical to `ModelManifest.kt:69` and `ente-ml/src/assets.rs:258` (the corrupted `…95e87…` literal is gone). **All five** modelpack assets (name+size+SHA-256) verified programmatically against both truth sources — all match. *Residual:* pins are still hand-transcribed (workflow comment admits it); no automated cross-check exists, so a future transcription typo is caught only by a failing modelpack CI build.

**F-2 [Medium] — OPEN.** Signing steps untouched by #132/#133: direct `${{ secrets.TG_RELEASE_KEYSTORE_BASE64 }}` interpolation into `run:` (`apk-build.yml:327`, `release.yml:164`) and unquoted `<<EOF` heredocs writing secrets into `GITHUB_ENV` (`apk-build.yml:341-346`, `release.yml:185-190`), where a password containing `$` is silently corrupted and the values persist for every subsequent step. `release.yml` is byte-identical to the audited tree.

**F-8 [Medium] — OPEN (doc-only mitigation).** #132 rewrote the fdroid Description and added a MaintainerNotes recipe for flavor-qualified builds, but: newest Build entry is still versionCode 37 @ `v0.4.5-alpha-r45` with the unflavored output path; `CurrentVersionCode: 37` vs code `38` (gallery) and `15` vs `16` (edit, file unchanged). `AutoUpdateMode: Version` still derives from the stale flavorless recipe.

**F-12 [Low] — PARTIAL.** `engine/scripts/build-android.sh:90-104` now prefers pinned `28.1.13356709` over newest-wins (policy test updated). *Residual:* unchanged `toad-setup/action.yml:95` still exports the **newest** `ANDROID_NDK_HOME`, which `build-android.sh:91` honors **over** the pin — so ci.yml/apk-build.yml engine builds still compile `libtoad_ml_jni.so` with whatever the runner image ships. Reproducibility measurement still covers only basic+edit (2 of 5 APKs; `reproducible-build.yml:22-26` honest about it, `release.yml:484-485` less so).

**F-14 [Low] — OPEN.** Both aapt2 resolutions newest-wins (`apk-build.yml:413`, `:561`) vs the pinned `build-tools;36.0.0` toad-setup installs; no release-stage `uses-permission` check on any APK. (Adjacent r50 fact, not this fix: `build.gradle.kts:811` now hard-fails INTERNET for `basic*` variants.)

### 2.2 Kotlin ML pipeline

**C-2 [High] — PARTIAL.** #132 added `EngineSessionGuard` (new file) — a WorkManager-liveness veto that now guards **all five** UI teardown sites: `PeopleViewModel:184`, `PersonDetailViewModel:321`, `MagicSearchViewModel:341`, `AlbumsViewModel:388` (all `if (!EngineSessionGuard.anyIndexChainLive(...)) engineAdapter.close()`), and the self-test's `finally` release (`AiEngineViewModel:494`). The adapters' `close()` bodies themselves are unchanged (still `engine.closeFaceIndex(); engine.release()`) — defense is caller-side only. A teardown that slips through burns one retry (the worker's batch-fault catch → `Result.retry()` re-initializes). *Residual:* TOCTOU window between the guard's query and the release; reset-timeout path proceeds with teardown anyway.

**C-3 [High] — PARTIAL.** `resetFaceIndex` (`AiEngineViewModel:430-443`) now cancels **both** chains, `awaitIndexChainsIdle` (≤15 s) before `deleteRecursively()`; `resetSemanticIndex` awaits the CLIP chain and skips session close while any chain is live. *Residual:* the await-timeout path deletes anyway ("deleting the index dir anyway (r48 shape)"); the queues' `finally { withContext(NonCancellable) { stateStore.save(...) } }` blocks are unchanged and `FaceIndexStateFile.save` still `mkdirs()` — no generation token, no store-existence check, so the resurrect-mechanism replays on the timeout path.

**C-4 [Medium] — OPEN.** `MlEngine.kt` byte-unchanged: `sessionOpen/faceIndexOpen/semanticReady` are per-instance `private var`s (lines 301–308), `@Synchronized` = per-instance monitors, workers still construct `MlEngine()` per run. The guard mitigates the harm but the facade/native desync root shape is intact.

**C-5 [Medium] — OPEN.** F-39's terminal-skip covers **only** `bytes == null` (byte-read failure, revoked grant, deleted row, >64 MiB refusal). The engine calls in both processors (`FaceIndexQueue.kt:337-341`, `ClipIndexQueue.kt:390-398`) have **no per-photo catch**: a decodable-but-corrupt JPEG that makes `detectFaceEmbeddings`/`clipImageEmbedding` throw aborts the batch → 3 attempts → run fails → re-plans the same poison photo at the head → replays every restart. Exactly as audited.

**C-9 [Low] — OPEN.** `reIndexKeys` still computed by both planners, still consumed by neither executor (delete-first is unconditional). Dead field retained.

**C-13 [Low] — OPEN.** `grep setRequires|Constraints(` → zero hits. Both queues still plain unconstrained background work (documented r24 posture).

**E-8 [Medium] — PARTIAL.** (d) **fixed by #132**: both ML read seams now refuse (never truncate) items > 64 MiB (`MAX_INDEXABLE_ITEM_BYTES`, matching `MediaReadHandle.DEFAULT_MAX_BYTES`). (a) vault exclusion was already baseline (gate 1, catalogue-driven; #132 only re-pinned its test for F-39). *Residual:* (b) no `IS_TRASHED` re-check — an item trashed after the last scan stays a live catalogue record and gets indexed during the staleness window; (c) reads still bypass `MediaAccess`/`EligiblePublicMedia`/`MediaReadPermissionGate` (raw `resolver.openInputStream`).

### 2.3 Privacy / licensing / compliance

**F-1 [Medium] — OPEN.** Zero SHA pins repo-wide (grep-proven). All 9 third-party action refs mutable: `actions/checkout@v4`, `actions/upload-artifact@v4`, `actions/cache@v4`, `actions/setup-java@v5`, `softprops/action-gh-release@v2` (inside the `contents: write` signing/publish job — highest risk), `dtolnay/rust-toolchain@stable` (floating branch, contradicts the pinned-toolchain claim in `docs/phase-11/reproducible-builds.md` §2), `github/codeql-action/init@v3`, `github/codeql-action/analyze@v3` (both **new in #133**, still tag-pinned), `docker://rhysd/actionlint:1.7.7` (tag, not digest).

**F-4 [Medium] — PARTIAL.** #132 made the gate **stronger than audited for basic**: any `basic*` merged manifest with INTERNET hard-fails (`build.gradle.kts:811`), plus a `tools:node="remove"` overlay manifest and honest docs (`privacy-gate.md:175-188`). *Residual:* engine/semantic/face variants keep the r22 warn-only posture (allowlist always matches — the main manifest declares INTERNET for ADR-0020 model downloads); no merged-vs-source comparison or entry counting anywhere; allowlist header still carries the blanket "hard build failure" phrasing.

**F-6 [Medium] — PARTIAL.** `about_credits_body` fixed: Ente now "the vendored on-device AI engine" (was "planned"). *Residual:* zero attribution strings for the four bundled ONNX models (U-2-Net/MI-GAN/Depth-Anything/YuNet, MIT/Apache-2.0); **the edit app — where all four models live — has no About surface at all**; the licensing ledger's "rides the About screen" promise (`licensing-and-attribution.md:112-113`, unchanged) points at a nonexistent surface.

**F-13 [Low] — FIXED.** `proguard-rules.pro` is now a 3-line comment recording the Fossify removal (ADR-0008).

---

## 3. Open-by-delta findings (63, grouped)

These cite only files byte-identical to `7e9114b` — open by definition, listed with the audit's own one-line titles:

- **Track A** (vendored Ente crates, pin-constrained): A-1 tar-bomb bound · A-2 unbounded decode RAM · A-3 ghost vectors on SQL failure · A-4 non-atomic publish · A-5 no download deadline · A-6 TOCTOU verify→install · A-7 Windows seek_write race · A-8 weak JXL magic · A-9 eprintln in library code · A-10 heic fork + duplicate moxcms · A-11 negative user_version panic · A-12 person_average NaN · A-13 silent 20k row cap · A-14 OFFSET pagination skip/dup · A-15 get_all_clip_vectors RAM blowup
- **Track B** (ente-ml/vecdb/JNI): B-1 put* no catch_unwind (**high**) · B-2 negative limit → usize::MAX · B-3 nativeRelease leaks CLIP text session · B-4 extern "C" vs "system" · B-5 hardcoded row_len 16 · B-6 unbounded BPE cache · B-7 put_face N-row read for ordinal · B-8 fsync-per-upsert + snapshot on caller thread · B-9 .lock sidecar litter · B-10 size-only native validation
- **Track C** (Kotlin ML): C-4 per-instance facade (**root shape**) · C-5 poison photo wedge · C-6 non-atomic receipt + no provision sync · C-7 no vault/trash filter at ML read path · C-8 editor ORT OOM crash + session leak · C-9 dead reIndexKeys · C-10 Things membership full-index JNI read · C-11 manifest hex-charset + uncapped dims · C-12 no disk-space preflight · C-13 unconstrained workers
- **Track D** (vault/crypto): D-1 no PIN throttling + KDF budget · D-2 no FLAG_SECURE · D-3 blob not fsynced before index commit · D-4 sealBatch holds all streams · D-5 biometric keygen fallback asymmetry · D-7 EXIF in-place rewrite · D-8 initEncrypt IV fallback edge · D-9 delete-then-rename window · D-10 stale "no INTERNET" KDocs ×6 · D-11 plaintext video temp survives crash · D-12 copy insert-collection MIME confusion · D-13 GPS persists after consent revoke
- **Track E** (catalogue/scan): E-1 null cursor mass tombstone (**high**) · E-2 999-param wedge (**high**) · E-3 healing starvation (**high**) · E-4 EXIF under scan mutex · E-5 volume IPC on main · E-6 no secondary indices · E-7 full-catalogue materialization · E-9 tombstone purge never wired · E-10 hand-written identity hashes · E-11 null-mime video misclassification · E-12 trash sort tiebreak · E-13 changes() over-emission · E-14 snapshot double-walk · E-15 clampLatitude(NaN)
- **Track F** (CI/compliance): F-1 action pins · F-2 secret hygiene · F-3 wrapper integrity · F-5 no AGPL text · F-7 catalogue in cloud backup · F-8 fdroid stale · F-9 repro-doc paths · F-10 no tag-push CI gate · F-11 no dependency-verification/Dependabot · F-14 aapt2 drift + no permission gate

---

## 4. The r51 fix plan — waves, branches, isolation

Approved by the maintainer 2026-11-06. Each wave item is one branch + one PR off `main`@`fa954fa`, with **disjoint file sets** (isolation map below) so any merge order works and no two PRs can conflict. Solo-owner exemption in `change-approval.yml` covers the workflow/engine-sensitive branches. CI is the compile judge (ADR-0013); local proofs are `tools/kotlin_lex_check.py` + actionlint 1.7.7 + shellcheck 0.10.0 + XML/YAML validation.

### Wave R51-1 — the four highs + the poison wedge

| ID | Branch | Scope (findings) | Files (HARD scope) |
| --- | --- | --- | --- |
| 1a | `fix/r51-1a-scan-integrity` | E-1, E-2, E-3 | `data/media/changes/{MediaScanRowSource,MediaScanDriver,ProvenanceHealingStore}.kt`, `data/catalogue/room/CatalogueDao.kt` (+ RoomMediaCatalogue wrappers if the `@Transaction` sites live there) + JVM tests |
| 1b | `fix/r51-1b-jni-unwind-guard` | B-1, B-2 | `engine/crates/toad-ml-jni/src/lib.rs` (+ `store.rs` read-only reference) — net-new toadGALLERY code, unconstrained by the Ente pin |
| 1c | `fix/r51-1c-queue-poison-skip` | C-5, C-9, C-3 backstop | `ml/index/{FaceIndexQueue,ClipIndexQueue}.kt` + their tests only |
| 1d | `fix/r51-1d-engine-shared-state` | C-4 | `ml/engine/MlEngine.kt` + one new JVM test — single-file diff by construction (public API unchanged) |

**Construction intents** (details live in each PR):
- **1a/E-1:** treat a `null` MediaStore cursor as a walk failure (throw like the cancellation path so the per-volume catch isolates it; no tombstone/checkpoint lands) + guard `removedKeys` against walk-row count. **E-2:** chunk all `IN (:keys)` lists ≤ 900 per statement inside the `@Transaction` wrappers (SQLite < 3.32 = 999 host params, minSdk 26). **E-3:** in-run re-offer detection (stop when a batch repeats) + an out-of-Room failure-marker state file (the repo's atomic JSON discipline) so the healing selection rotates past poisoned rows — **no Room schema change** (E-10 risk deliberately avoided).
- **1b/B-1:** wrap `nativeStorePutFace`/`nativeStorePutClip` in the existing `call_or_throw` (catch_unwind) helper, sentinel `0`/`JNI_FALSE`, mirroring `nativeStoreMergeFaceClusters`. **B-2:** reject `limit <= 0` at the Rust boundary in both search paths, mirroring the `max_distance` guard.
- **1c/C-5:** per-photo catch around the engine calls in both processors → count + F-39-style terminal-skip record (photo held out until revision changes) → batch completes past poison photos. **C-9:** remove the dead `reIndexKeys` field + document delete-first as unconditional. **C-3 backstop:** planner store-existence check — a missing engine store with live state records plans re-index instead of UNCHANGED-skip (self-heals the reset-resurrect).
- **1d/C-4:** move the facade flags behind shared (companion-object) state + one shared monitor so every `MlEngine` instance observes native truth; keep the class instantiable and the public API byte-compatible (workers/adapters/ViewModels unchanged).

### Wave R51-2 — vault, CI, compliance

| ID | Branch | Scope | Files (HARD scope) |
| --- | --- | --- | --- |
| 2a | `fix/r51-2a-vault-durability` | D-3, D-5, D-9 | `data/vault/{VaultStore,AndroidVaultBiometricKeys}.kt` + tests |
| 2b | `fix/r51-2b-vault-screen-security` | D-1 (throttle-only), D-2, D-11 | `ui/vault/VaultViewModel.kt`, `data/vault/VaultThumbnailDecoder.kt` (+ policy), `ToadGalleryApplication.kt`, the FLAG_SECURE site + JVM tests |
| 2c | `ci/r51-2c-supply-chain` | F-1, F-2, F-3, F-12 residual, F-14 | `.github/workflows/{release,apk-build,codeql,repo-lint}.yml`, `.github/actions/toad-setup/action.yml`, `gradle/wrapper/gradle-wrapper.properties` |
| 2d | `fix/r51-2d-compliance-artifacts` | F-5, F-7, F-8, F-9 | `engine/LICENSE` (new), `engine/NOTICE.md`, `res/xml/{backup_rules,data_extraction_rules}.xml`, `fdroid/metadata/*.yml` ×2, `docs/phase-11/reproducible-builds.md`, `docs/licensing-and-attribution.md` |

**Construction intents:** **2a:** `fd.sync()` on blob writes before the index commit; mirror the media key's StrongBox→TEE fallback (`GeneralSecurityException`/`RuntimeException`) in the biometric key seam; drop the delete-then-rename `atomicWrite` fallback (fail loudly, fsynced `.tmp` survives). **2b:** persisted failed-attempt counter + exponential backoff on unlock (reset on success) — **no `PIN_ITERATIONS` change** (decision-gated, §5); `FLAG_SECURE` + recents-screenshot off while Unlocked; `cleanStaleTempFiles()` wired at app start. **2c:** SHA-pin all 9 third-party refs (version-commented) + a concrete Rust toolchain; `<<'EOF'`/step-env secret hygiene in both signing pipelines; `distributionSha256Sum` (gradle-9.5.1-bin.zip) + wrapper-validation-action in toad-setup; toad-setup NDK export prefers the pin; aapt2 from `build-tools/36.0.0` + release-stage `uses-permission` gates. **2d:** canonical AGPL-3.0 text at `engine/LICENSE` + NOTICE/licensing-doc references + per-artifact license offer; catalogue DB excluded from cloud-backup/device-transfer rules; fdroid Build entry (versionCode 38, flavor-qualified task/output, future tag documented) + CurrentVersion refresh for both apps; repro-doc updated to flavor-qualified tasks/paths.

### Isolation map (conflict-freedom proof)

No file appears in two branches: 1a owns `data/media/changes/*` + `data/catalogue/room/CatalogueDao.kt`; 1b owns `engine/crates/toad-ml-jni/*`; 1c owns `ml/index/*Queue*.kt` + its tests; 1d owns `ml/engine/MlEngine.kt`; 2a owns `data/vault/{VaultStore,AndroidVaultBiometricKeys}.kt`; 2b owns `ui/vault/*` + `VaultThumbnail*` + `ToadGalleryApplication.kt`; 2c owns `.github/**` + wrapper properties; 2d owns `engine/LICENSE`, `engine/NOTICE.md`, `res/xml/*`, `fdroid/**`, `docs/phase-11/*`, licensing doc; this PR owns `docs/audit/*`. Merge in any order.

### Wave R51-3 — prioritization-gated (not dispatched yet)

3a read-path eligibility (E-8 residual b/c: `MediaAccess` routing + `IS_TRASHED` re-check) · 3b About/licenses UI (F-6 residual + F-4 engine-family merged-vs-source gate) · 3c scan perf (E-4, E-5, E-7 + E-12/E-14) · 3d robustness sweep (E-9, E-13, E-15, C-6, C-10, C-11, C-12, C-13, D-7, D-12, D-13, B-7, B-9).

---

## 5. Decision-gated items (maintainer call required before work)

1. **D-1 `PIN_ITERATIONS` raise** — (a) store iterations per-record (backward-compatible; **recommended**), (b) raise + force re-enrollment, or (c) throttle-only (implemented in 2b; iterations unchanged at 210k).
2. **E-6 × E-10 (Room indices + hand-written identity hashes)** — highest-risk change in the repo; requires a KSP schema-regeneration CI leg **first**; its own round.
3. **Track A / vendored-crate fixes (A-1…A-15, B-3…B-10)** — Ente pin policy: adaptation-ledger + NOTICE.md updates or upstream; only bridge-side unconstrained items (B-3 bridge half, B-7) scheduled (R51-3d).
4. **C-8 (apps/edit ORT OOM/session leak)** — fold into the dedicated edit-app sweep (follow-up F-49), not a gallery round.

---

## 6. Process notes

- All wave branches cut from `main`@`fa954fa` before any sibling merges — rebase-free by construction; if the maintainer merges some waves first, the rest still apply cleanly (disjoint files).
- Each wave PR body carries: findings addressed, per-file construction, acceptance outcomes, isolation statement, honest limits (ADR-0013: no local Android SDK; CI is the compile judge).
- #131 is closed as superseded by this PR (the work-order lands here verbatim + this verification round).
