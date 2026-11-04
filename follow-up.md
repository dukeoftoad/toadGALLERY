# Follow-up log

**Purpose:** the running follow-up register for decisions, debts and deferred items
that must not get lost between work windows. Append-only; newest entries last.
Created 2026-11-04 (the file was referenced by process but never existed — docs-gap
fix recorded in `docs/workplan.md` §3).

---

## 2026-11-04 — workplan window (progress tracking + debug-APK workflows)

- **F-1 (2026-11-04, open)** — In-app Android progress screen. The two progress bars
  ship in this window as a live dashboard + committed `progress.json`; a native
  Compose screen (bundled `progress.json` snapshot read from assets, a Settings
  entry, two `LinearProgressIndicator`s + phase lists) is deliberately deferred: no
  emulator/SDK exists in this sandbox, so the code could not be compiled or verified
  before the single expensive push. Design sketch: mirror `progress.json` into
  `apps/gallery/src/main/assets/progress/progress.json` at build time, parse with
  kotlinx.serialization, render in the existing Settings surface pattern used by the
  r22/r23 dogfood cards.
- **F-2 (2026-11-04, open)** — Composite-action extraction. The repo now has FIVE
  workflows repeating the checkout/rust/ndk/jdk/sdk/gradle setup blocks verbatim
  (ci.yml ×3 jobs, engine-apk.yml, modelpack-apk.yml, + the two new ones). Extract a
  `.github/actions/toad-setup` composite action (inputs: rust-targets, ndk-resolve)
  in a dedicated low-risk window; do NOT fold it into ci.yml mid-campaign.
- **F-3 (2026-11-04, open, carried from worklog Run #9)** — PR #108 (`tg-p7-lut-import`)
  is RED and redundant with #107's import flow; its actor should close or reconcile.
- **F-4 (2026-11-04, open, carried)** — The `~74 → ~90` editor string recount debt
  (r34 text-tool prep) still rides the branch docs.
- **F-5 (2026-11-04, open, carried)** — `ci.yml` has no `paths-ignore` for docs-only
  pushes, and the build job's 20-minute timeout headroom shrinks as editor layers
  land (Run #9 nit; watch at the text-tool round).
- **F-6 (2026-11-04, open)** — CHANGELOG carries two `[0.4.2-alpha]` headings
  (pre-existing); merge them in the next release-entry edit.
- **F-7 (2026-11-04, open)** — `tasks.md` milestone table contains stale duplicate
  Phase 5/6 rows (scheduled for collapse in this window's final phase; if it still
  reads duplicated, the final phase did not complete).
- **F-8 (2026-11-04, open)** — The dashboard's `PATCH /api/progress` is a local
  maintenance surface in the sandbox deployment; the canonical way to update
  `progress.json` inside the repo remains a normal commit (PR review applies). If a
  GitHub-side updater is ever wanted, gate it behind workflow_dispatch + PR, never
  direct pushes.

## 2026-11-04 — final phase (Documentation + Flaw Review) closure

- **F-7 CLOSED (2026-11-04)** — `tasks.md` milestone table collapsed to one
  authoritative row per phase; the stale duplicate Phase 5/6 rows are gone and
  the surviving Phase 5 row now states the merged truth (code-complete / exit
  gate landed, r28 merged, release freeze lifted, dogfood pending).
- **F-1 (open, design frozen)** — the in-app Compose progress screen remains the
  next-window item; `progress.json` is its ready-made input (mirror into assets
  at build time; parse + two `LinearProgressIndicator`s; Settings-entry pattern).
- **F-2 (open)** — composite-action extraction now spans SIX workflows; same
  guidance (dedicated low-risk window; do not touch ci.yml mid-campaign).
- **F-9 (new, open)** — the dashboard's socket transport runs in the sandbox
  deployment only (progress-service :3003 file-watching the repo checkout). If
  the dashboard is ever deployed permanently, re-point `PROGRESS_FILE` and put
  the PATCH surface behind auth.
- **Flaw review verdict (this window's surfaces: the two workflow files,
  `progress.json`, `docs/workplan.md`, `follow-up.md`, `tasks.md` edits,
  `roadmap.md`/`README.md`/`CHANGELOG.md` edits, `docs/ci-workflows.md`)**:
  blockers 0; non-blockers fixed in this phase (the tasks.md stale rows F-7;
  the Phase 5 status-label understatement); nits logged, no action (the
  CHANGELOG double `[0.4.2-alpha]` headings = pre-existing F-6; worklog
  append-only residue per Run #9; `ci.yml` paths-ignore/timeout headroom =
  F-5; dashboard `PATCH` unauthenticated = acceptable sandbox-only, tracked
  as F-9).
- **F-10 (found + fixed 2026-11-04, one open action for the maintainer)** — the
  `0d021fa` "Mask PAT tokens" pass missed 8 unmasked `ghp_…` occurrences in
  `worklog.md` (one `ghp_Mn3l…`, seven `ghp_6tT1…`); all are now replaced with
  `[REDACTED:github_token]` in HEAD. **Both token families verified DEAD via the
  API (HTTP 401)** — revocation already effective, so no active leak remains.
  Residual nit: the dead tokens remain in git HISTORY (a history rewrite is not
  warranted for revoked credentials; noted for the record only). Lesson: masking
  passes must scan with a full-file regex, not by eyeballing previews.

## 2026-11-04 (later) — the r34 text-tool + F-1/F-2 window

- **F-1 CLOSED (2026-11-04)** — the in-app Compose progress screen shipped
  on `tg-p7-text-tool`: `ProgressSnapshot` (the pure org.json parse — the
  kotlinx.serialization idea from the original sketch is dropped for the
  zero-new-deps rule), the `copyProgressJson` build task mirroring the
  committed repo-root `progress.json` into the APK assets (the model-pack
  srcDir pattern; every mergeAssets task depends on the copy), and the
  Settings → Development progress screen (two independent bars + phase
  lists + the build-time-snapshot honesty line). Gallery `versionCode 34`.
- **F-2 CLOSED (2026-11-04)** — `.github/actions/toad-setup` extracted and
  adopted by ALL SIX workflows (ci.yml ×3 jobs, engine-apk.yml,
  modelpack-apk.yml, apk-build-engine.yml, editor-apk-build.yml,
  release.yml); identical pins and cache keys; −218 lines net; actionlint
  1.7.12 PASS. The executor-level verification is the merge-gate CI run.
- **F-3 CLOSED (2026-11-04)** — PR #108 (`tg-p7-lut-import`, redundant
  with #107's merged import flow) closes via the `Closes #108` keyword on
  the r34 branch's merge commit (the maintainer sanctioned the closure;
  no API credentials in this window — the keyword rides the merge).
- **F-4 CLOSED (2026-11-04)** — the ~74 → ~90 string recount debt settled
  by r34: `strings_r34.xml` adds 20 strings; the edit module count is
  **94** (46 + 16 + 12 + 20), recorded in `docs/phase-7/text-tool.md`
  §Delivery record (the delta vs the ~90 estimate: the eight named
  swatches minus the four registry-resident font names).
- **F-6 CLOSED (2026-11-04)** — CHANGELOG's duplicate `[0.4.2-alpha]`
  headings merged into one (codes 27–34 noted).
- **F-11 (new, open)** — the r34 text-tool position control is a drag
  gesture only; it is not TalkBack-reachable in v1 (no position slider).
  The a11y position control rides a later round with the other
  gesture-based interactions. Recorded in `docs/phase-7/text-tool.md`
  §Delivery record.
- **F-12 (new, open, carried context)** — the r34 round is
  authored-pending-merge: `tg-p7-text-tool` needs push → PR (the PR
  description should repeat `Closes #108`) → the merge-gate CI dispatch
  at the head → merge. `progress.json` deliberately NOT updated for r34
  (it records MERGED truth; update at merge time).
