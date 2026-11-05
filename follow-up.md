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

## 2026-11-04 (latest) — the r34 merge window (F-12 executed)

- **F-12 CLOSED (2026-11-04)** — the merge sequence executed with fresh
  maintainer credentials: `tg-p7-text-tool` pushed (the six authored
  commits + five CI-fix commits from the gate iterations), **PR #109**
  opened (the description carries `Closes #108`; based on post-#107 main
  `7fba75c`, no rebase), the merge-gate CI iterated to green (**run
  41822887363** on head `699fd03`; the seven-run first-execution story
  is the worklog's Run #12), **squash-merged as `6bb17a4`**, and
  **PR #108 auto-closed** by the keyword (state=closed, merged=false —
  redundant with #107's merged import flow, as sanctioned).
  `progress.json` p7 updated at merge time as designed (tasks 1-2/5,
  evidence with both merge shas).
- **F-3 mechanism note** — the closure keyword rode PR #109's
  description (not a merge-commit message); same effect: GitHub closes
  the referenced PR when the closing PR merges to the default branch.
- **Correction to the r34 delivery record** — "22 new JVM tests" was a
  never-executed estimate; the r34 families are **32** (14 + 12 + 6)
  and the edit module sits at **241** (r33's 209 + 32). Corrected in
  roadmap v2.6, the Phase 7 status text, CHANGELOG's unreleased
  section, and Run #12.
- **F-5 note** — the docs-only post-merge push triggers the full ci.yml
  run (no paths-ignore exists yet); the merge-triggered run was
  superseded under the ci-main concurrency group (the merge tree ≡
  `699fd03`'s, already validated green). The standing guidance stays:
  add a docs-only paths-ignore in a dedicated low-risk window.

## 2026-11-04 (latest) — the r35 sticker-tool round (authored on `tg-p7-stickers`)

- **F-13 (new, open)** — the imported-sticker library has no management
  affordance yet: `ImportedStickerStore.delete` exists and is JVM-tested,
  but no UI surfaces it, so removing a bad import requires app-data
  clearing or a hand-edit. The affordance is additive (a
  "remove from library" action near the registry chips, mirroring the
  imported-looks remove); land it with the frames round or a dedicated
  small window, NOT mid-round.
- **F-14 (new, open, rides F-11)** — the position drag remains
  TalkBack-unreachable (the r34 F-11 family); the r35 round inherited
  the gap for sticker layers and extended the gesture across both
  families. The gesture-a11y family (position sliders or a11y-aware
  drag) still owes one round across ALL layer families.
- **Note** — the r34 text-tool bullet moved to MERGED truth in
  `tasks.md`/roadmap v2.7 (PR #109 `6bb17a4`); `progress.json` stays
  untouched until the r35 merge per the F-12 convention.

## 2026-11-04 (latest) — the r36 drawing-tool round (authored on `tg-p7-drawing`)

- **F-15 (new, open)** — per-point stroke editing and stroke
  repositioning are deliberately out of scope in v1 (the points ARE the
  position; editing them is a vertex-editor family of its own). A later
  round owes the family decision: drag-a-vertex, or delete-and-repaint.
- **F-16 (new, open, rides F-11/F-14)** — the paint gesture inherits the
  gesture-a11y gap (the position-drag family); the §25 device rows
  eyeball painting under TalkBack in the meantime.
- **Note** — the r35 bullet moved to MERGED truth in tasks.md/roadmap
  v2.8 (PR #110 `980ccea`); `progress.json` stays untouched until the
  r36 merge per the F-12 convention.

## 2026-11-04 (latest) — the r37 mosaic/blur round (authored on `tg-p7-mosaic`)

- **F-17 (new, open)** — the linear blur runs horizontally only (the
  roadmap names "linear blur"; v1 takes the canonical direction) and
  the Gaussian-blur kind is the recorded candidate; an angle slider
  and/or a third kind ride a later window with their own pinned grids.
- **F-18 (new, open)** — feathered/soft mask edges: v1 is a hard
  binary mask; the family decision (a blur-gradient alpha at the
  boundary) owes its own round.
- **Note** — the r36 bullet moved to MERGED truth in tasks.md/roadmap
  v2.9 (PR #111 `8c466ee`); `progress.json` stays untouched until the
  r37 merge per the F-12 convention.

## 2026-11-04 (latest) — the r38 frames round (authored on `tg-p7-frames`,
the campaign's closing)

- **F-19 (new, open)** — frame stacking stays out of scope by design
  (one mounting at a time); if a later round wants stackable frames it
  is a model change (a `frameLayers` container), not a UI toggle ---
  decide it in a dedicated round.
- **F-20 (new, open)** — the campaign's file-write discipline lesson:
  the r37 insertion script asserted, printed "ok", and never wrote the
  activity back (the r37 UI section existed only in a Python variable;
  CI stayed green because it compiled what was there). Standing rule
  for every future script that mutates a file: write THAT file back
  within the same script, and grep the diff before committing.
- **Note** — the r37 bullet moved to MERGED truth in tasks.md/roadmap
  v2.10 (PR #112 `6c206d9`), carrying the r37 debt fix; `progress.json`
  stays untouched until the r38 merge per the F-12 convention.

## 2026-11-04 (latest) — the Phase 7 campaign close-out (Run #13)

- **The §22-§27 device rows** (one section per round: LUT §22, text
  §23, stickers §24, drawing §25, mosaic/blur §26, frames §27) are all
  human-only --- the standing directive holds: no round claims them,
  no doc marks them done, and the ente-port scope is untouched.
- **F-13/F-17/F-18/F-19 carry** (the library-management affordance for
  imported stickers/frames; the blur angle + Gaussian candidate; the
  feathered mask; the stackable-frames model decision).
- **The campaign summary** is the worklog's Run #13; the counts are
  executed numbers (131 new JVM tests; the edit module 241 -> 374; the
  strings 94 -> 129; the versionCodes 7 -> 11).

## 2026-11-04 (latest) — the r39 ente-port exit round (the Phase 5
completion, branch `tg-r39-ente-exit`)

- **The directive**: "complete Phase 5 — the ente port" (corrected from
  "task 5"; the user's numbering), plus the roadmap restructure:
  Phases 8 & 9 skipped and folded into Phase 10. The ente-port dogfood
  device rows remain human-only (the standing directive).
- **F-21 (new, open)** — the Things membership cut is round-pinned at
  cosine distance 0.70 (similarity >= 0.30), honestly arbitrary (the
  MagicSearchQuery 200-char-cap convention); per-library tuning rides
  a later window if the on-device dogfood asks for it. The planner
  takes its cut as a parameter, so tuning is a caller change, never a
  semantics change.
- **F-22 (new, open)** — the Albums-tab Things section renders every
  collection chip in one LazyRow (no "See all" browse screen); when
  collections grow past a row's worth, the browse screen is a UI-round
  follow-up. The create dialog and chips are not yet TalkBack-audited
  to the r32 editor standard --- the §13 device rows' family.
- **Note** — the r26 named follow-ups "semantic-only engine sessions"
  and "Things dynamic collections" are CLOSED (the r39 round); the
  "score display" and "scan-hook wiring" follow-ups stay open
  (`docs/phase-5/magic-search.md`'s r39 closure note).
- **Note** — `progress.json` deliberately NOT updated for r39 (the
  F-12 convention: it records MERGED truth; the p5 evidence + the
  p8/p9/p10 consolidation update at merge time).

## 2026-11-05 — the Phase 10 hardening round r40 (MERGED: PR #117 squash `c3c21f7`; the contracts PR #116 squash `16a5001`)

- **The directive**: "Move to next phase works, use sub agents in
  parallel" --- Phase 10 begins (harden first), and the sandbox's live
  progress dashboard was restored + fixed in the same window (a sandbox
  reset had wiped its page; the repo surfaces are unaffected).
- **F-23 (new, open)** — the boundedness audit's B9 watch item:
  `MediaScanDriver.rescanVolume` loads the WHOLE catalogue
  (`catalogue.recordsMap()`) into memory once per rescanned volume
  before filtering by volume --- an accepted one-time cost on the
  documented design, but a 50k-library memory-bounds watch item. The
  fix shape (a per-volume read path) is a catalogue API change, not
  hardening; it rides a later Phase 10 window with the benchmark
  evidence in hand (`docs/phase-10/benchmark-scenarios.md` S1/S3).
- **The two planning deliverables are skeletons**: `benchmark-scenarios.md`
  and `device-matrix.md` carry structure + definitions only; every
  result cell is human-only (the standing directive). The Baseline
  Profiles decision is deferred until the S1/S2/S6 baselines exist.
- **The r40 a11y device rows are human-only** and ride the checklist
  with the next docs/merge window (the §22--§27 pattern).

## 2026-11-05 — the r41 Edit v3 AI round (MERGED: PR #118 squash `73cfb1b`; merge-gate run 41978641679 green)

- **The directive**: "Complete the remaining stages under phase 10"
  (the stated order: the Edit v3 AI round, then the video editor)
  executed end to end on branch `tg-p10-ai`,
  `docs/phase-10/edit-v3-ai.md` in full; the same window opened
  the r42 video-editor round before the context deadline closed it
  (the worklog's Run #16 records the close honestly).
- **F-24 (new, open)** — the r41 fill-patch resolution bound: the
  magic-erase inpaint patch is bounded by the session's working
  resolution, so an erased region on a very large export is softer
  than its surroundings. The full-resolution export-time re-inference
  pass is the recorded follow-up (its cost named alongside in the
  contract: a second full decode + minutes of inference); it rides a
  later round with the device latency rows in hand.
- **F-25 (new, open)** — the sky mask is the shared u2netp saliency's
  above-the-subject region, not a portrait-grade sky segmentation; a
  dedicated sky-segmentation model is the recorded follow-up if the
  device rows show mask bleed on portrait subjects.
- **F-26 (new, open)** — v1 retouch is ONE global selection over the
  detected faces (the three task-named controls: smoothing, eye
  brighten, face slim); per-face tuning is the recorded follow-up and
  owes its own round (a UI + model decision, not a toggle).
- **The r41 device rows are human-only** (the standing directive): the
  per-feature end-to-end rows, the latency numbers against the
  round-documented budget, the airplane-mode flow + the instrumented
  egress probe, and the About-screen attribution surfacing; the yunet
  per-tensor scaling is recorded UNVERIFIED against the FaceDetectorYN
  C++ source and is flagged as the rows' first quality suspect.
- **Note** — the r41 merge-time truth updates did NOT happen in the
  authoring window (the context deadline closed it mid-r42); they ride
  the r42 window's truth commit --- the F-12 convention
  (`progress.json` records MERGED truth only) honored, just late.

## 2026-11-05 — the r42 lightweight video editor round + the Phase 10 close-out (MERGED: PR #119 squash `f8583d3`; merge-gate run `41988201250` green)

- **The directive**: "Complete the remaining stages under phase 10"
  --- the fifth catch (commit `dcf553e`: the media3 ExperimentalApi
  opt-in, lint's own message naming the marker class, taken verbatim)
  and the sixth catch (commit `7744ea4`: the r41 privacy scan's
  host-literal gate catches the KDoc `"0.50x"` speed-label example;
  the scan's own documented protocol --- the dotted NON-host literal
  joins the deliberate set with the reason recorded, and the scan
  re-verified locally, all three tests green, BEFORE the push) fixed,
  PR #119 merged, and the Phase 10 close-out (p10 → done, the p7
  precedent); the truth docs were drafted in parallel by a read-only
  sub-agent while the merge-gate ran.
- **F-27 (new, open)** — video geometry (crop/rotate) is out of scope
  for the video editor v1 (not in the Phase 9 task text, not in the
  ADR-0012 set) and is the recorded follow-up; a later round owes the
  family decision with its own pinned grids, mirroring the photo
  editor's geometry round.
- **F-28 (new, open)** — the video editor's session state is transient
  by design: no persisted video recipe sidecar ships in v1 (the photo
  sidecar machinery is deliberately not promised for video), so
  re-editability across sessions is the recorded follow-up; the
  non-destructive rule itself is fully in force (every export is a new
  MediaStore entry; no replace-original path exists).
- **The r42 device rows are human-only** (the standing directive): the
  long-video (10+ min) / 4K / HDR / no-audio matrix, the measured APK
  size delta for the media3 addition (the hosting decision's recorded
  number), the Transformer-on-real-codecs behavior, the airplane-mode
  row, and the TalkBack-over-video row; the long/4K/HDR performance
  budget has NO number invented --- the rows own it.
- **Note** — `progress.json` at merge-time truth with this window's
  truth commit: p10 → done (the r40 + contracts + r41 + r42 evidence
  with every sha and run), the project percent 85 → 91 (weights
  validated = 100); the r41 updates ride the SAME commit (the Run #16
  window closed early; the F-12 convention honored, just late). The
  entePort scope is untouched (the e9 dogfood rows stay human-only).

## 2026-11-05 (latest) — the Phase 11 close-out campaign (r43+r44+r45,
## MERGED: PR #121 squash `d27f3c1` / PR #120 squash `791d3a0` / PR #122
## squash `4d797ff`; gates 41996006593 + 41996188270 + 41993838209 green)

- **The directive**: "Do the finale phase 11 with parallel agents work.
  Act as lead role and release tag with all versions and note for apk
  files and release mapping.txt file for better finding of project" ---
  three parallel sub-agent rounds (release engineering, F-Droid
  metadata, release docs) with the lead gating, merging, and cutting
  the close-out release.
- **F-29 (new, open)** — the F-Droid submission itself is a HUMAN step:
  the metadata is prepared and pinned (`fdroid/metadata/` for both
  apps, vercode 37/15 at the `v0.4.5-alpha-r45` tag) but the fdroiddata
  new-app merge request has not been opened; the submission process and
  the verification loop (`fdroid checkupdates`/`lint`/`scanner`/`build`)
  are documented in `docs/phase-11/fdroid-metadata.md`.
- **F-30 (CLOSED, 2026-11-05)** — the first measurement is IN and it
  is the strongest verdict: the `v0.4.5-alpha-r45` tag push measured
  **BYTE-REPRODUCIBLE** (run 42002437535; the identical-tree pre-flight
  run 42000127417 at the same SHA measured the same) --- all four pairs
  (both unsigned release APKs + both mapping.txt files) byte-identical
  across two clean `--no-build-cache` builds. The honest status line in
  `docs/phase-11/reproducible-builds.md` §4 now records the measured
  fact; every future `v*` tag re-measures it.
- **F-31 (new, open)** — the edit app's release variant is minified for
  the FIRST time in r43 (`proguard-rules.pro` is deliberately minimal:
  the ORT JNI keep + the two documented `-dontwarn` shims); any R8
  catch from the release run (a runtime crash on a stripped class
  would surface on a testing device, not in CI) gets recorded and fixed
  per the merge-gate convention --- a rule lands only with the failure
  that justifies it.
- **F-32 (new, open)** — the v1.0 stable cut is a HUMAN decision: the
  release workflow hard-codes `prerelease: true` + `make_latest: false`
  per ADR-0018 ("until v1.0, no testing build is presented as the
  repository's latest release"), and the flip to a stable release waits
  on the human-only device rows (the standing directive), a superseding
  ADR, and the versionName decision (0.4.x-alpha -> 1.0.0).
- **The r43 riding catches (recorded)**: (1) `copyProgressJson` → the
  `*LintVitalReportModel` dependency --- EVERY `assembleRelease` was
  broken at main (the failed Release run 41991609209) while the
  debug-only CI lint never had the copy in its graph, which is why
  ci.yml stayed green as the release path rotted; (2)
  editor-apk-build.yml's `-m1` version derivation vs the edit gradle's
  last-wins convention (the failed editor run 41991485246). Both fixed
  on the r43 branch before the merge.
- **The r44 riding accuracy fix (recorded)**: the gallery's INTERNET
  story in the F-Droid metadata said "no INTERNET permission at all" —
  the r22/ADR-0020 truth is ONE allowlist-gated model-fetch grant from
  models.ente.com; an F-Droid reviewer reads the merged manifest, so
  the overclaim would fail the review. Corrected before the merge; the
  edit app's no-INTERNET claim stays true (the r41 hard-strip).
- **Note** — `progress.json` at merge-time truth with this window's
  truth commit: p11 → done (the r43+r44+r45 evidence with every sha and
  run), the project percent 91 → 92 (weights validated = 100). The
  entePort scope is untouched (the e9 dogfood rows stay human-only;
  that scope's percent stays 95). The close-out release tag
  `v0.4.5-alpha-r45` is cut FROM this truth commit so the shipped
  gallery APK's embedded progress snapshot (the copyProgressJson
  mirror) reports the closed-out truth.

- **The release-run catch (recorded, 2026-11-05)** --- the first
  `v0.4.5-alpha-r45` tag push (at the truth commit `cfaed66`) failed
  BOTH the Release run (41997801764) and the Reproducible build run
  (41997801776) at `:apps:gallery:lintVitalAnalyzeRelease` --- the
  SECOND member of the lintVital family carrying the same implicit
  progressAssets dependency the r43 riding fix had closed for
  `generateReleaseLintVitalReportModel` only (the first fix's suffix
  match was too narrow; Gradle's validation caught the analyze task the
  model writer hid behind). Fixed by broadening the explicit
  `dependsOn(copyProgressJson)` to the whole `*LintVital*` family (any
  future member covered by construction); nothing published from the
  failed runs, so the tag was honestly re-cut at the fix commit ---
  no release ever existed at the broken SHA.
