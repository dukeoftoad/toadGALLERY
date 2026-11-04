# Workplan — Progress tracking + debug-APK CI workflows (2026-11-04 window)

**Author:** the sole-engineer window instance (post-Run #9 recovery state, `main` @ `0d021fa`).
**Protocol:** one batched push at the end; no incremental pushes; no CI polling while
work is in progress; the two new APK workflows are validated by YAML lint + static
review only and are NOT dispatched in this window.

---

## Ente port — definition (derived, no assumption needed)

The brief allows a one-paragraph assumption "if unclear". It is **clear from the repo
documents themselves**, so no assumption is required — the derivation stands on these
files, all read in full before this workplan was written:

`roadmap.md` (Phases 5 section), `docs/phase-5/ente-pin.md`, `tasks.md` (Phase 5
milestone + `r21`–`r28` rows), `README.md`, `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`,
`docs/integration-decisions.md` (ADR-0020), `engine/NOTICE.md`, and the vendored crates
under `engine/crates/ente-*`.

> **The ente port** = the porting of Ente's on-device AI stack (from upstream
> `ente/ente` at pin `423b2d80d9ec89d45f5680cb1e1d777bac225f41`, 2026-11-02) into
> toadGALLERY as the Phase 5 workstream: the vendored Rust crates (`ente-ml`,
> `ente-assets`, `ente-image`, `ente-vecdb`, `ente-photos-ml`), the net-new
> `toad-ml-jni` JNI bridge, the Kotlin model provisioner (`ModelManifest` /
> `ModelVerifier` / `ModelProvisioner` / `HttpModelDownloader` with `models.ente.com`
> as the sole allowlisted host per ADR-0020), the face indexing + clustering pipeline,
> the Magic Search semantic integration, the offline model-pack channel, and the
> pending on-device dogfood/exit gate. It is tracked here separately from the overall
> app roadmap because it has its own upstream pin, its own CI surfaces (the `engine`
> and `engine-android` CI jobs, `engine-apk.yml`, `modelpack-apk.yml`), and its own
> completion gate.

---

## Current-state assessment (evidence, verified 2026-11-04)

### 1. Ente port status: per phase

| Ente-port phase | Status | Evidence |
| --- | --- | --- |
| Source pin + provenance record | **Done** | pin `423b2d8…` recorded in `docs/phase-5/ente-pin.md`; `engine/NOTICE.md` adaptation ledger |
| Vendored crates: `ente-ml`, `ente-assets`, `ente-image` | **Done** | `r21` (PR #93); byte-identity proven across 85 files |
| JNI bridge `toad-ml-jni` + `-PwithEngine` packaging | **Done** | `r22` (PR #95); `engine-android` CI job green; Settings "AI engine (r22 dogfood)" |
| Photos-side crates `ente-vecdb` + `ente-photos-ml` | **Done** | `r23` (PR #96); 324 + 202 upstream tests vendored |
| Face indexing + clustering pipeline | **Done** | `r23` `IndexCoordinator.kt` @ threshold 0.24; Settings "Index + cluster (r23 dogfood)" |
| Sole-host model downloader (`models.ente.com`) | **Done** | `r22` `HttpModelDownloader` + INTERNET allowlist entry + egress tests |
| Magic Search (semantic/CLIP) integration | **Done** | `r26` Magic Search round `84aab67` merged |
| Offline model-pack channel | **Done** | `r28` Workstream 8; `modelpack-apk.yml`; ADR-0020 §7 |
| On-device dogfood + exit gate | **In progress** | tasks.md Phase 5: "on-device dogfood pending"; §19 device checklist rows are human-only |

Net: the ente port is **code-complete and merged on `main`; only the human-only
on-device dogfood/exit rows remain.**

### 2. Overall app status (roadmap Phases 0–11)

Phases 0–4 **done** (see tasks.md milestone table); Phase 5 **code-complete, dogfood
pending**; Phase 6 (Edit v1) **merged** (`#106` → `938effa`, r29–r32); Phase 7 (Edit v2)
**task 1 of 5 merged** (`#107` → `314bd9c`, r33 LUT + import; text/stickers/drawing/
mosaic/frames remain); Phases 8–9 **planned**; Phase 10 **planned**; Phase 11
**partially pulled forward** (`release.yml` live per ADR-0018).

### 3. Docs & reporting health

| File | State | Action in this window |
| --- | --- | --- |
| `roadmap.md` | Current through Run #9 (Phase 6 MERGED, Phase 7 note present) | Final phase: add progress-bars + new-workflow status rows |
| `tasks.md` | **Inconsistent** — stale duplicate Phase 5/6 milestone rows (two Phase 5 rows, two Phase 6 rows; the first pair predates the r28/`#106` merges) | Final phase: collapse to one authoritative row per phase; add workplan checkpoints per item |
| `follow-up.md` | **Missing** (referenced by process, never created) | Created now; updated (timestamped) in the final phase |
| `README.md` | Current; Build & CI section lists ci.yml/release.yml but not the APK test-build family | Final phase: one-paragraph "Debug APK workflows" note (or pointer to docs/ci-workflows.md) |
| `CHANGELOG.md` | Two `[0.4.2-alpha]` headings (pre-existing nit, Run #9) | Nit logged; new Unreleased entry for this window's additions |
| `docs/ci-workflows.md` | Missing | Created in final phase: the two new workflows, triggers, required secrets (names only) |
| `worklog.md` | Append-only campaign log; Run #9 nits recorded | Not rewritten (append-only convention); a new Run entry appended at window end |

---

## Workplan

Dependency notes: P1 → P2 → P3 are sequential (source of truth → transport → UI).
P4 (workflows) is independent of P2/P3. P5 verifies P2–P4. P6 finalises docs. P7 is
the single push. Progress bars phase = P2+P3; APK workflows phase = P4; final
Documentation + Flaw Review phase = P6.

### P1 — Planning artifacts + progress source of truth
- **Items:** `docs/workplan.md` (this file); `progress.json` at repo root (both
  scopes, weights, evidence pointers); `follow-up.md` created; `tasks.md` checkpoint 1.
- **Exit criteria:** workplan enumerates every phase/item with exit criteria;
  `progress.json` parses and every phase row carries a file/commit evidence pointer;
  checkpoint recorded in tasks.md. ✓ met when this file + `progress.json` are committed.

### P2 — Progress transport (sandbox live-sync service + API)
- **Items:** socket.io mini-service (`progress-service`, port 3003) that file-watches
  `progress.json` and broadcasts the full state on change and on connection; REST
  surface `GET /api/progress` (initial paint + polling fallback) and
  `PATCH /api/progress` (validated phase-status update, atomic tmp+rename write).
- **Depends on:** P1. **Exit criteria:** a change to `progress.json` is broadcast to a
  connected client in <1 s; PATCH validates scope/phase/status against the schema and
  rejects unknown ids; file writes are atomic.

### P3 — The two progress bars (dashboard UI)
- **Items:** dashboard at the app root with **two independent bar components** —
  Project Progress (overall app development) and Ente Port Progress (ente port only).
  Each computes its own percentage from its own `progress.json` scope (weights ×
  status factors: done 1.0, in-progress 0.5, pending 0), renders its own per-phase
  breakdown with status badges, last-updated timestamp, and a live-connection
  indicator; an admin control set drives PATCH updates for verification.
- **Design decisions (one line each):**
  - *Project bar location:* dashboard app root — the only runnable, verifiable UI
    surface in this environment (no Android emulator exists here; the Android app
    itself is offline-first and a live-syncing in-app bar would fight ADR-0020's
    sole-host posture).
  - *Project bar source of truth:* `progress.json` `project` scope (mirrors
    roadmap.md Phases 0–11 + tasks.md milestone table) — machine-readable, diffable
    in PRs, and consumable by both the dashboard and future in-app screens.
  - *Project bar sync:* socket.io push on file-change with a 4 s polling fallback —
    push gives sub-second updates, the fallback guarantees the 5 s SLA if the
    socket is down.
  - *Ente bar location:* same dashboard, separate card — co-location aids
    comparison while the components stay independent.
  - *Ente bar source of truth:* `progress.json` `entePort` scope (mirrors
    `docs/phase-5/ente-pin.md` + tasks.md Phase 5 rows) — same benefits, own scope.
  - *Ente bar sync:* same socket transport, own event payload scope — sharing the
    transport (not the value) is the cheapest way to keep both bars under the 5 s
    SLA without coupling their computations.
- **Independence guarantee:** the two bars never sum, average, or derive from each
  other; each subscribes to its own scope slice and recomputes from weights in its
  scope only.
- **Depends on:** P2. **Exit criteria:** both bars render; each reflects a
  `progress.json` edit in <5 s (verified live in a browser); each shows overall % +
  phase breakdown for its own scope; manual verification steps documented (§
  Verification below); sticky footer + responsive layout hold on mobile widths.

### P4 — The two new APK workflows (`.github/workflows/`)
- **Workflow A — "APK build with engine"** (`apk-build-engine.yml`): generalized,
  dispatch-only engine-enabled debug APK for `:apps:gallery` with inputs
  (`build_variant`, `engine_version` pin validation), the repo's exact engine build
  chain (cargo-ndk arm64-v8a, same cache keys as `engine-apk.yml` so main's warm
  caches apply), native-library packaging gate, APK identity gate, artifact upload
  (APK + mapping.txt when present + build log on failure), and a job summary.
  Delta vs the existing `engine-apk.yml` (complement, not duplicate): that file is
  the branch-scoped `tg-engine-apk-*` test build with the round-convention staging;
  this one is ref-agnostic, input-driven, adds the variant input + pin validation +
  mapping/log artifacts + summary, and keeps its zero-secrets debug path (ADR-0018:
  `release.yml` stays the only signing workflow; a `release` variant here fail-fasts
  on missing `TG_RELEASE_*` secrets with an explicit message).
- **Workflow B — "Editor APK build — include all"** (`editor-apk-build.yml`):
  dispatch-only, ONE job with a matrix covering all editor targets — `edit-debug`
  (`:apps:edit:assembleDebug` + identity gate + artifact), `edit-release`
  (`assembleRelease`, unsigned by ADR-0018, annotated as the R8 compile gate) and
  `contract` (`:shared:editor-contract` assemble + JVM tests + jar artifact) — with
  `fail-fast: false`, an input to narrow the variant set, and a job summary.
  Justification for a static-plus-input matrix: the editor has no product flavors
  (verified in `apps/edit/build.gradle.kts`), so "all editor targets" = both build
  types + the shared contract module.
- **Both:** reuse policy honoured — the repo has **no composite actions / reusable
  workflows** (verified), so the canonical step blocks (checkout/rust/ndk/jdk/sdk/
  gradle caches) are replicated verbatim with identical cache keys; extraction into
  a composite action is recorded as a follow-up, not done in this window (touching
  `ci.yml`'s four jobs mid-window adds merge risk for zero behavioural gain).
  Versions pinned to the repo's existing majors (`actions/checkout@v4`,
  `actions/setup-java@v5`, `actions/cache@v4`, `actions/upload-artifact@v4`,
  `dtolnay/rust-toolchain@stable`). Concurrency groups with cancel-in-progress on
  the same ref. Triggers: `workflow_dispatch` only — no tag/PR triggers because
  `ci.yml` already gates PRs/main and `release.yml` owns `v*` tags (repo pattern).
- **Depends on:** P1 only (parallel with P2/P3). **Exit criteria:** both files parse
  as YAML (pyyaml + actionlint when obtainable); static review against this spec
  passes; secrets referenced by name only; no plaintext credentials; NOT dispatched.

### P5 — Verification pass
- Dashboard: live browser session — load, socket connect, PATCH a phase via the
  admin controls, observe both bars update independently <5 s, mobile-width layout,
  footer behaviour. YAML: `python3 -c yaml.safe_load` on both workflow files;
  actionlint binary if downloadable in-sandbox, else recorded as environment nit.
- **Depends on:** P2–P4. **Exit criteria:** every P3/P4 exit criterion re-checked;
  results recorded in the final report.

### P6 — Documentation + Flaw Review (final phase)
- **Flaw pass** over: the two new workflow files, `progress.json`, this workplan,
  `follow-up.md`, `docs/ci-workflows.md`, `tasks.md`, `roadmap.md` edits, README/CHANGELOG
  edits — classified blocker / non-blocker / nit; blockers + non-blockers fixed, nits logged.
- **Docs:** `tasks.md` — collapse the stale duplicate Phase 5/6 milestone rows + final
  checkpoint; `roadmap.md` — progress-bars + workflow status rows; `docs/ci-workflows.md`
  created; `follow-up.md` final timestamped entry; `README.md` + `CHANGELOG.md` minimal
  additions; `worklog.md` — one append-only Run entry.
- **Depends on:** P5. **Exit criteria:** zero blockers open; every touched doc
  consistent with `progress.json`; the brief's per-file action table fully executed.

### P7 — Commit + single push + CI poll
- Commit in clear labelled commits; **one push** of `main` via a one-off
  credential URL (no token in `.git/config`); poll CI once, ≤20 min, then report.
- The new APK workflows are **not dispatched** (no explicit approval; CI cost rules).

---

## Verification — documented manual steps

1. **Bars render + live sync:** open the dashboard root; both cards render with
   overall % and phase breakdowns and a "Live" badge. Run
   `curl -X PATCH <dashboard>/api/progress -H 'content-type: application/json' -d '{"scope":"entePort","phaseId":"e9","status":"done"}'`
   → the Ente bar's % rises (95 → 100) within ~1 s without reload; revert with
   `status: "in-progress"`. The Project bar's value is unchanged by that PATCH —
   the independence proof.
2. **Socket-down fallback:** stop the progress-service; the dashboard shows
   "Polling" within ~4 s and a PATCH still lands within the 5 s SLA via the 4 s poll.
3. **Workflows:** `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/apk-build-engine.yml'))"`
   (+ editor file) exits 0; on the Actions tab each workflow shows a
   "Run workflow" (workflow_dispatch) entry; no run is triggered by this window.
