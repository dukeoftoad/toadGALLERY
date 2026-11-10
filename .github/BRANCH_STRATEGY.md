# toadGALLERY Branch Strategy --- the Virtual Fork (formal)

**Status:** LANDED (2026-11-10, the Phase 3 round). This document is the
authoritative statement of the branch topology, the sync flow, and the
release pipelines. It formalizes the Phase 2 "Virtual Fork" strategy
(workflows re-landed on main in #167) and the Phase 3 manual release
pipelines. Supersedes the scattered Phase 2 notes; the historical record
of the Phase 2 wrong-baseline accident (PR #164 merged into the phase-1
tip, the empty revert #165) lives in the worklog (r66).

---

## 1. The topology

| branch | role | push CI (Phase 2) | release pipeline (Phase 3) |
|---|---|---|---|
| `main` | The master release line. Everything lands here last, via a human-merged PR from `unified`. | `main-release.yml` (post-merge 5-APK verification build) | `release-main.yml` (manual 5-APK master bundle) |
| `unified` | The integration fork. Every product fork lands here first; the everything-in-one Unified APK is this fork's product. | `unified-ci.yml` (Unified debug APK, -PwithEngine) | `release-unified.yml` (manual, 1 APK) |
| `gallery` | Product fork --- the gallery (Basic \| Engine). | `gallery-ci.yml` (Basic on push; dispatch adds Engine) | `release-gallery.yml` (manual, operator picks basic / engine / both) |
| `editor` | Product fork --- the companion editor. | `editor-ci.yml` (debug APK + lint + tests) | `release-editor.yml` (manual, 1 APK) |
| `ente` | Product fork --- the vendored Ente Rust engine. Ente is NOT a Kotlin module (the Phase 1 deviation): the Ente APK IS the engine-flavor gallery build (`:gallery:app:assembleEngineRelease -PwithEngine`). | `ente-ci.yml` (cargo gates + engine-flavor APK) | `release-ente.yml` (manual, 1 APK) |
| `unified-master` | ARCHIVED. The pre-Phase-1 unified line, frozen at the modularization boundary. No CI, no releases. | none | none |
| `chore/*`, `refactor/*`, `fix/*` | Short-lived working branches. They PR into the branch they modify and are deleted after merge. | n/a (PR gates) | n/a |

The five products of the family: **Basic, Engine, Editor, Ente,
Unified**. The four gallery-family applicationIds ride the flavor
suffixes (`com.toadgallery` / `com.toadgallery.engine` / ...); the editor
is `com.toadgallery.editor`; unified is `com.toadgallery.unified`. Engine
and Ente share `com.toadgallery.engine` --- the same product cut from two
different fork tips; they replace each other on install, never coexist.

## 2. The sync flow (formal)

```
gallery ──┐
editor  ──┼── (PRs, auto-merge) ──▶ unified ── (HUMAN PR ONLY) ──▶ main
ente   ──┘
```

1. **Product forks → `unified`: AUTO-SYNC via PR.** When a fork's work
   is ready, the operator opens a PR from the fork into `unified` and
   GitHub's native auto-merge completes it once the required checks pass.
   The PRs themselves are opened by humans; NO workflow creates, merges,
   or syncs these branches. A fork that is behind `unified` may merge
   `unified` back into itself before opening the PR (manual, operator's
   discretion).
2. **`unified` → `main`: HUMANS MANUALLY PR. NEVER automated.** No
   workflow may create, auto-merge, label, or sync a PR in this
   direction --- this is the Phase 2/3 hard guardrail. A `unified` →
   `main` PR merges only when `main-release.yml` (the post-merge 5-APK
   verification build) has gone green on the `unified` head.
3. **`main` → forks: backports are manual and rare.** Forks track
   `unified`, not `main`. Workflow-file changes land on `main` first
   (like this Phase 3 PR) and reach the forks by a manual backport PR or
   the operator pushing the identical commit, so every fork carries the
   same `.github` tree.

## 3. The release pipelines (Phase 3)

**Absolute control:** every release workflow is `workflow_dispatch` ONLY.
Zero automated triggers (`push`, `tags`, `schedule`) are permitted for
releases. A release exists only when a human operator dispatches a
pipeline and names the tag.

**Ref pinning:** every release workflow checks out its canonical branch
explicitly (`ref: main` / `ref: unified` / ...). The ref a workflow is
dispatched from is ignored by design --- the PR #164 wrong-baseline
accident is structurally impossible to repeat.

| workflow | dispatch on | builds | release assets | inputs |
|---|---|---|---|---|
| `release-main.yml` | anywhere (pinned to `main` + the `ente` tip) | the 5-APK master bundle: basic + engine + editor (all from `main`) + ente (from the `ente` tip) + unified | 5 signed APKs (the ONLY release assets) + Actions artifacts: 5 `.sha256` + `rel-main-mappings` (`toadGALLERY-mappings-<tag>.zip`, all five R8 mappings in one zip) | `tag_name` (required), `prerelease` (default true) |
| `release-unified.yml` | anywhere (pinned to `unified`) | `:unified:app:assembleRelease -PwithEngine` | Unified APK (the ONLY release asset) + Actions artifacts: `.sha256` + mapping.txt | `tag_name` (required), `prerelease` |
| `release-gallery.yml` | anywhere (pinned to `gallery`) | operator's choice: basic (property-less), engine (`-PwithEngine`), or both (two deliberate invocations) | chosen APK(s) (the ONLY release assets) + Actions artifacts: `.sha256` + per-APK mapping.txt | `variant` (basic/engine/both), `tag_name` (required), `prerelease` |
| `release-editor.yml` | anywhere (pinned to `editor`) | `:editor:app:assembleRelease` | Editor APK (the ONLY release asset) + Actions artifacts: `.sha256` + mapping.txt | `tag_name` (required), `prerelease` |
| `release-ente.yml` | anywhere (pinned to `ente`) | `:gallery:app:assembleEngineRelease -PwithEngine` on the `ente` tip | Ente APK (the ONLY release asset) + Actions artifacts: `.sha256` + mapping.txt | `tag_name` (required), `prerelease` |

Pipeline mechanics (identical in all five):

- **Signing (ADR-0018):** the release keystore lives only in GitHub
  Encrypted Secrets (`TG_RELEASE_KEYSTORE_BASE64`, `TG_RELEASE_KEYSTORE_PASSWORD`,
  `TG_RELEASE_KEY_ALIAS`, `TG_RELEASE_KEY_PASSWORD`) and reaches gradle
  through the `TG_*` env contract, step-level. Unsigned releases are
  impossible: every APK passes a HARD `apksigner` gate against the pinned
  signer certificate digest
  `8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494`
  before anything is published; an unsigned or debug-signed build can
  never reach a release page.
- **Packaging gates:** the basic APK must carry NEITHER engine native
  library (the negative gate); every engine-bearing APK (engine / ente /
  unified) must carry BOTH (`libtoad_ml_jni.so` + `libonnxruntime.so`).
  This is what a global `-PwithEngine` leak into a property-less
  invocation would break --- the r46 failure class, permanently gated.
- **The mapping lineage:** every release build is R8-minified
  (`isMinifyEnabled = true` on every release buildType), so every APK's
  `mapping.txt` is MANDATORY --- a missing or empty mapping fails the
  pipeline. The release page carries the APKs ONLY; mappings and
  `.sha256` checksums publish as workflow-run artifacts (the Actions
  tab): `release-main.yml` bundles all five mappings into one
  `toadGALLERY-mappings-<tag>.zip` (`rel-main-mappings`), and the
  single-APK pipelines ship theirs inside the `rel-*` staging
  artifacts. Mappings are named per product + version + round (a
  generic `mapping.txt` is ambiguous across five APKs and dozens of
  releases).
- **Two-invocation rule:** `-PwithEngine` is a GLOBAL gradle property.
  Basic-flavor builds always run in a property-less invocation, separate
  from every engine-bearing build.
- **Tag discipline:** the operator names every tag (`tag_name` is
  required); a trailing `-r<N>` drives the asset names, otherwise the run
  number is used. A pipeline refuses to publish over an existing tag or
  release (tags are never reused).
- **Prerelease default:** releases are pre-releases by default; the
  stable v1.0 cut is a human decision, never a workflow default
  (ADR-0018).
- **Green-SHA discipline (ADR-0013):** a release is only ever cut from an
  already-reviewed, already-green SHA. The release pipelines run NO lint
  or unit tests --- the per-branch CI (Phase 2) owns those gates. The
  in-pipeline gates (signature, packaging, mapping) verify the artifacts
  themselves.

## 4. Where the mappings live (build outputs)

| product | mapping.txt path |
|---|---|
| gallery basic | `gallery/app/build/outputs/mapping/basicRelease/mapping.txt` |
| gallery engine / Ente | `gallery/app/build/outputs/mapping/engineRelease/mapping.txt` |
| editor | `editor/app/build/outputs/mapping/release/mapping.txt` |
| unified | `unified/app/build/outputs/mapping/release/mapping.txt` |

## 5. Operator runbook --- cutting a release

1. Confirm the source branch is green (the fork's push CI / the
   `main-release.yml` verification run).
2. Dispatch the pipeline in the **Actions → Release (… ) → Run workflow**
   dropdown, selecting the pipeline's canonical branch (`main` for
   release-main, `unified` for release-unified, `gallery` for
   release-gallery, `editor` for release-editor, `ente` for
   release-ente) --- the workflow file must exist at the selected ref
   (each fork carries its own release file). The pinned checkout makes
   what gets BUILT deterministic regardless of the selected ref. Fill
   `tag_name` (e.g. `v0.4.5-alpha-r51`), keep `prerelease` on unless this
   is the stable v1.0 cut.
3. For the gallery fork, pick `variant`: `basic`, `engine`, or `both`.
4. The pipeline builds, gates (signature + packaging + mapping), stages,
   guards the tag, publishes the GitHub release, and lists the assets.
5. Symbolicating a crash trace: open the dispatch run in the Actions
   tab and download the matching `*-mapping.txt` from the run's
   artifacts (or the `rel-main-mappings` bundle zip from a master
   release) for that tag, and `retrace` the obfuscated stack against
   it.

## 6. Legacy and superseded workflows

- `release.yml` --- the pre-refactor (Phase 11) signed pre-release
  pipeline. Its automated `push: tags: [v*]` trigger is REMOVED in Phase
  3 (the zero-automated-triggers constraint); it is superseded by the
  five Phase 3 pipelines and left in the tree for history --- its
  `:apps:*` gradle targets no longer exist in the post-Phase-1 module
  layout, so a dispatch of it fails fast and safely.
- `main-release.yml` --- NOT a release pipeline: it is the post-merge
  5-APK verification build (push-to-main, debug APKs, no publishing).
  The Phase 3 `release-main.yml` owns actual releases.
- `reproducible-build.yml` --- verification-only (builds twice, compares
  unsigned artifacts, holds no secrets, publishes nothing); it still
  fires on `v*` tag pushes as the verifier of record. Its `:apps:*`
  paths pre-date the Phase 1 split and are tracked for a follow-up
  repair round --- it is NOT a release publisher, so it stays outside the
  Phase 3 zero-trigger rule.
- `unified-apk.yml` / `apk-build.yml` --- dispatch-only manual builders
  for test artifacts; unchanged. Actual unified releases now go through
  `release-unified.yml`.

## 7. Guardrails recap

1. Releases are manual only: `workflow_dispatch`, zero automated
   triggers.
2. Every release checkout is pinned to its canonical branch.
3. No workflow may create PRs, auto-merge, or sync branches --- the
   unified → main direction is human-only, permanently.
4. Unsigned or debug-signed APKs can never reach a release (the hard
   `apksigner` gate, the pinned ADR-0018 digest).
5. Every published APK's R8 mapping and checksum ride the run's
   Actions artifacts (the `rel-*` staging artifacts, or the master
   bundle's `rel-main-mappings` zip); a missing mapping fails the
   release. The release page itself carries the APKs only.
6. The refactor phases are over: release pipelines build existing gradle
   targets only --- no Kotlin, XML, or gradle changes ride a release PR.
7. One release key signs every APK (update-in-place across the family;
   Engine and Ente share the engine applicationId and replace each other
   on install).
