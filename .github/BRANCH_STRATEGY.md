# toadGALLERY Branch Strategy --- the Virtual Fork

**Status:** REALIGNED (2026-11-11) with
[`docs/PROJECT_ARCHITECTURE.md`](../docs/PROJECT_ARCHITECTURE.md), the
authoritative project-level source of truth for product boundaries, branch
topology, release channels, model distribution, and Actions responsibilities.
This document is the **operator runbook + pipeline inventory** that implements
that policy. Where the two ever disagree, PROJECT_ARCHITECTURE.md wins.

**What changed in the 2026-11-10 realignment:** the family is now **four
products** (Basic Gallery, Gallery Engine, Editor, Unified --- Ente is an
upstream integration lane, *not* a product); branch-local release pipelines on
the product lanes are **retired** (releases originate from `unified` for
alpha/beta and from `main` for stable only); `main` is the **stable release
line**, never the everyday integration destination.

---

## 1. The topology

| branch | role | push CI | release pipeline |
|---|---|---|---|
| `main` | **Stable release line.** Receives approved stable promotions from `unified` via human-reviewed PR. | `main-release.yml` --- post-merge four-product verification (debug builds, no publishing) | `release-main.yml` --- the four-product **stable** bundle (non-prerelease) |
| `unified` | **Primary integration branch.** Every product lane lands here first; hosts the alpha/beta lifecycle. | `unified-ci.yml` --- the four-product integration gate (see §4) | `release-unified.yml` --- the four-product **alpha/beta** bundle (always `--prerelease`) |
| `gallery` | Gallery product lane (Basic + Engine). | `gallery-ci.yml` --- basic + engine debug APKs, lint/tests | **retired** (`release-gallery.yml` deleted 2026-11-10; releases originate from `unified`/`main`) |
| `editor` | Standalone Editor product lane. | `editor-ci.yml` --- assemble + lint + tests + `:shared:editor-contract:test` | **retired** (`release-editor.yml` deleted 2026-11-10) |
| `ente` | **Upstream Ente synchronization lane only** --- an upstream source lane, not a fifth product. | `ente-ci.yml` --- cargo gates + engine-flavor APK validation | **retired** (`release-ente.yml` deleted 2026-11-10; Ente ships no toadGALLERY release) |
| `unified-master` | ARCHIVED. The pre-modularization unified line (old `apps/` layout), frozen at the Phase 1 boundary. | none | none |
| `feat/*`, `fix/*`, `refactor/*`, `docs/*` | Short-lived working branches. They PR into the lane they modify and are deleted after merge. | n/a (PR gates) | n/a |

The **four products** and their identities:

| product | applicationId | built from | engine `.so` | models |
|---|---|---|---|---|
| Basic Gallery | `com.toadgallery` | `:gallery:app:assembleBasicRelease` (property-less) | none (negative gate) | none |
| Gallery Engine | `com.toadgallery.engine` | `:gallery:app:assembleEngineRelease -PwithEngine` | `libtoad_ml_jni.so` + `libonnxruntime.so` (both required) | per product requirements |
| Editor | `com.toadgallery.editor` | `:editor:app:assembleRelease` | none | bundled non-Ente ONNX per policy |
| Unified | `com.toadgallery.unified` | `:unified:app:assembleRelease -PwithEngine` | both required (gate) | all applicable non-Ente models |

Ente model weights are **never bundled** --- they are downloaded on demand
(ADR-0020 allowlist); non-Ente ONNX models may bundle per product requirements
(PROJECT_ARCHITECTURE.md §6).

## 2. The sync flow

```text
gallery ─────┐
editor ──────┤
ente sync ───┼── reviewed PR ──▶ unified ── approved stable promotion ──▶ main
features ────┘        │                         │
                 alpha / beta              stable releases
              (release-unified.yml)     (release-main.yml)
```

1. **Product lanes → `unified`: reviewed PRs.** When a lane's work is ready,
   the operator opens a PR into `unified`; GitHub native auto-merge may
   complete it once the required checks pass. The PRs themselves are opened by
   humans --- NO workflow creates, merges, or syncs these branches. A lane
   that is behind `unified` may merge `unified` back into itself first
   (manual, operator's discretion).
2. **`unified` → `main`: HUMANS PROMOTE, DELIBERATELY. NEVER automated.** No
   workflow may create, auto-merge, label, or sync a PR in this direction.
   Promotion is a **release decision**, not a CI event: a candidate is
   eligible only per the definition of done (PROJECT_ARCHITECTURE.md §9 ---
   four-product CI green on the exact SHA, tests + engine checks green,
   packaging per §6, alpha/beta feedback considered, versions/notes/provenance
   accurate, explicit maintainer approval). The promotion PR must pass
   `ci.yml` (the four-product gate) **before** merge; `main-release.yml` on
   the push is the post-merge safety net, not a substitute.
3. **`main` → lanes: backports are manual and rare.** Lanes track `unified`,
   not `main`. Workflow-file and docs changes land on `main` first and reach
   the lanes through the normal flow (or an explicit operator backport).
4. **`ente` → `unified`: selective upstream integration.** Occasional
   (roughly annual, or when a meaningful upstream change warrants it). Review
   upstream changes selectively --- never blind-import. Validate
   compatibility, licensing/provenance, and project-specific behavior on the
   `ente` lane first; contribute into `unified` when useful.

## 3. The release channels

**Absolute control:** both release workflows are `workflow_dispatch` ONLY ---
zero automated triggers. A release exists only when a human dispatches the
pipeline and names the tag.

**Ref pinning:** each release workflow checks out its canonical branch
explicitly (`ref: main` / `ref: unified`). The ref a run is dispatched from is
ignored by design.

| workflow | pinned source | builds | release page assets | Actions run artifacts (14-day retention) |
|---|---|---|---|---|
| `release-unified.yml` | `unified` | Basic + Engine (gallery), Editor, Unified --- all from the `unified` tip | `toadGALLERY-<v>-r<N>.apk`, `toadGALLERY-engine-<v>-r<N>.apk`, `toadGALLERY-editor-<ev>-r<N>.apk`, `toadGALLERY-unified-<v>-r<N>.apk` (marked **pre-release**) | per-APK `.sha256` + per-APK R8 `mapping.txt` |
| `release-main.yml` | `main` | the same four products from the `main` tip | the same four APKs, published as a **regular (non-prerelease) stable** release | per-APK `.sha256` + per-APK R8 `mapping.txt` |

Inputs: `tag_name` (required; a trailing `-r<N>` drives asset file names,
otherwise the run number is used); channel behavior is fixed by source branch.

Pipeline mechanics (identical in both):

- **Signing (ADR-0018):** the release keystore lives only in GitHub Encrypted
  Secrets (`TG_RELEASE_KEYSTORE_BASE64`, `TG_RELEASE_KEYSTORE_PASSWORD`,
  `TG_RELEASE_KEY_ALIAS`, `TG_RELEASE_KEY_PASSWORD`) and reaches gradle
  through the `TG_*` env contract, step-level. Every APK passes a HARD
  `apksigner` gate against the pinned signer digest
  `8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494` ---
  an unsigned or debug-signed build can never reach a release page.
- **Packaging gates:** the Basic APK must carry NEITHER engine native library
  (the negative gate); every engine-bearing APK (Engine / Unified) must carry
  BOTH (`libtoad_ml_jni.so` + `libonnxruntime.so`). Model-asset
  presence/absence is checked per PROJECT_ARCHITECTURE.md §6.
- **The mapping lineage:** every release build is R8-minified, so every APK's
  `mapping.txt` is MANDATORY --- a missing or empty mapping fails the
  pipeline. The release page carries the APKs **only** (slim assets);
  checksums and mappings ride the dispatch run's artifacts.
- **Two-invocation rule preserved:** `-PwithEngine` is a global gradle
  property (the r46 lesson) --- Basic always builds in a property-less
  invocation, separate from the Engine/Unified invocation.

## 4. The CI inventory (canonical set)

| workflow | triggers | what it proves |
|---|---|---|
| `unified-ci.yml` (on `unified`) | push + PRs → `unified`, path-filtered over `gallery/**`, `editor/**`, `unified/**`, `engine/**`, `shared/**`, `models/**`, gradle + workflow files | **the four-product integration gate**: engine-host (cargo check/fmt/clippy/tests), gallery-basic (build/lint/tests), gallery-engine (engine debug assemble), editor (build/lint/tests + contract), unified (engine-enabled debug assemble) |
| `ci.yml` (on `main`) | PRs → `main` (the promotion gate) + dispatch | all four products on the exact candidate SHA: Basic build/lint/tests, Editor build/lint/tests + contract, Engine debug assemble via cargo-ndk, Unified debug assemble |
| `main-release.yml` (on `main`) | push → `main` + dispatch | post-merge stable verification: the four debug products re-assemble on the promoted tip |
| `gallery-ci.yml` (on `gallery`) | push → `gallery` (paths incl. `gallery/**`, `engine/**`, `shared/**`, gradle) | Basic + Engine debug APKs build on the lane |
| `editor-ci.yml` (on `editor`) | push → `editor` (paths incl. `editor/**`, `shared/**`, gradle) | Editor assemble + lint + tests + contract on the lane |
| `ente-ci.yml` (on `ente`) | push → `ente` (paths incl. `engine/**`, `gallery/**`, gradle) | cargo gates + the engine-flavor gallery debug APK on the lane |
| `codeql.yml` | push/PR (main), weekly schedule | security analysis |
| `repo-lint.yml` | push/PR on `.github/workflows/**` + `.github/actions/**` | actionlint + shellcheck on workflow changes |
| `screenshots.yml` | dispatch | Roborazzi record / verify against committed baselines |
| `unified-apk.yml` | dispatch | manual dogfood builds of the Unified app |
| `reproducible-build.yml` | `v*` tag pushes + dispatch | double-build comparison of unsigned artifacts (verification only) |
| `change-approval.yml` | PR/review events | the review-policy gate |

## 5. Operator runbook

**Integrate a lane into `unified`:**
1. Confirm the lane's push CI is green on the tip.
2. Open a PR `<lane>` → `unified`; let `unified-ci.yml` run on the PR.
3. Merge (native auto-merge acceptable once checks pass).
4. Verify the next `unified` push run of `unified-ci.yml` (the merged state).

**Cut an alpha/beta:**
1. Confirm `unified-ci.yml` is green on the `unified` tip being tagged.
2. Actions → **Release (unified --- four-product alpha/beta bundle)** → Run
   workflow (any ref; the checkout is pinned to `unified`). Fill `tag_name`
   (e.g. `v0.4.5-alpha-r66`); a trailing `-r<N>` drives asset names.
3. The pipeline builds, gates (signature + packaging + mapping), stages,
   guards the tag, publishes the pre-release, lists assets.
4. Fetch checksums/mappings from the run's artifacts (14-day retention).

**Promote stable (`unified` → `main`):**
1. Walk the definition of done (PROJECT_ARCHITECTURE.md §9) on the candidate
   SHA; resolve release-blocking alpha/beta feedback.
2. Open a human PR `unified` → `main`; `ci.yml` must pass on the exact
   candidate before merge.
3. After merge, confirm `main-release.yml` (the push run) is green.
4. Cut the stable release: Actions → **Release (main --- four-product stable
   bundle)** → Run workflow, `tag_name` e.g. `v1.0.0`; published
   non-prerelease.

**Symbolicate a crash trace:** open the release dispatch run in the Actions
tab, download the matching `*-mapping.txt` from the run artifacts for that
tag, and `retrace` the obfuscated stack against it.

## 6. Legacy, dormant, and pending-cleanup items

- `release-editor.yml` / `release-ente.yml` / `release-gallery.yml` on
  `main` --- **retired pipelines, pending deletion.** Their home lanes
  deleted them (2026-11-10); the copies on `main` remain until the next
  promotion or an explicit cleanup commit. They are dispatch-only relics ---
  do not dispatch them.
- `release.yml` / `apk-build.yml` --- the pre-restructure release and test-APK
  builders. Deleted from `main` (2026-11-10); the lanes still carry dispatch
  relics of `apk-build.yml`/`release.yml`/`unified-apk.yml`-era copies, which
  will age out through the normal flow. Do not dispatch them.
- `reproducible-build.yml` --- verification-only; still references the old
  `:apps:*` module paths and needs a repair round (tracked).
- `codeql.yml` / `repo-lint.yml` carry a cosmetic `branches: ain]` typo in
  their `on:` blocks since r50 --- tolerated by the Actions parser, flagged
  for a hygiene pass.
- `docs/ci-workflows.md` --- predates the 2026-11-10 restructure; treat
  §4 above as current until it is refreshed.
- `unified-master` --- archived, frozen; kept for history. No CI, no
  releases, do not develop here.

## 7. Guardrails recap

1. Releases are manual only: `workflow_dispatch`, zero automated triggers.
2. Every release checkout is pinned to its canonical branch
   (`unified` = alpha/beta, `main` = stable --- no other source).
3. No workflow may create PRs, auto-merge, or sync branches --- the
   `unified` → `main` promotion is human-only, permanently, and is a release
   decision, not a CI event.
4. Unsigned or debug-signed APKs can never reach a release (the hard
   `apksigner` gate, the pinned ADR-0018 digest).
5. Every published APK's R8 mapping and checksum ride the run's Actions
   artifacts (14-day retention); a missing mapping fails the release. The
   release page carries the APKs only.
6. Release pipelines build existing gradle targets only --- no Kotlin, XML,
   or gradle changes ride a release workflow change.
7. One release key signs every APK (update-in-place across the family).
8. The family is exactly four products; Ente integration must never grow a
   fifth release lane.
