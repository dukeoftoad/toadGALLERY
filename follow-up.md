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

## The r46 extension window (2026-11-05) --- the APK matrix + the contract fix + Roborazzi

The maintainer's r46 directive, executed with parallel implementation
agents and a lead merge window: the semantic index and the face index ship
as SEPARATE installable builds (like the edit app), the basic APK loses
the engine library AND every engine UI surface, engine-library-included
builds exist, the both-APKs "outdated" linking bug is fixed at its roots,
and Roborazzi lands.

- **PR #123 (`235b786`) --- the contract-discovery hardening.** The field
  symptom ("showing edit apk is outdated for this gallery contract" with
  both current APKs installed) closed at its true roots: the resolver
  query now carries `GET_META_DATA`; the declared version reads through
  the new pure `EditorVersionMetadata` precedence (activity-level marker,
  then the re-fetched activity bundle, then the editor's NEW
  application-level marker); a zero-hit wildcard-mime query retries the
  editor's own concrete mime filters (the `image/*` intent never matches a
  specific-mime filter); a package-probe fallback resolves from
  `getPackageInfo`. Edit versionCode 16 (`0.4.11-edit-alpha`). One
  merge-gate catch: the concrete-mime KDoc carried the wildcard marker
  inside the comment and Kotlin's NESTED block comments ran it to EOF
  (run 42014018173) --- one KDoc line fixed.
- **PR #124 (`dcc85f0`) --- the gallery APK matrix.** One flavor dimension
  ("engine"): `basic` (canonical app ID, `WITH_ENGINE=false`, the whole
  engine UI gated out through the pure `BuildShape`: the Albums
  People/Things sections, the Search content mode, the Settings AI
  section, the People/Things routes with an unavailable card, defensive
  AiEngineViewModel early returns) + `engine` (`.engine`, both indices) +
  `semantic` (`.semantic`) + `face` (`.face`). versionCode 38; per-flavor
  launcher labels. Workflows flavor-qualified; `release.yml` builds
  basic+edit property-less and the engine family with `-PwithEngine` in
  TWO invocations (a global `-PwithEngine` can never leak into basic),
  verifies all FIVE signatures against the pinned cert, enforces the
  engine-library NEGATIVE gate (basic carries NEITHER native library ---
  the maintainer's exact complaint, now CI-enforced; engine/semantic/face
  carry BOTH), stages five APKs + sha256s + five per-APK mapping.txt
  files. One merge-gate catch: `---` inside the three flavor XML comments
  (the XML spec forbids `--` in comments) --- dash-free rewrites.
- **PR #125 (`4b86a59`) --- the Roborazzi screenshot pipeline.** Robolectric
  4.17 + Roborazzi 1.77.0, NATIVE graphics; three deterministic captures
  (ErrorState / LoadingState / UnsupportedFileState --- the param-light
  public screen-state cards); `screenshots.yml` records PNGs into the
  `roborazzi-screenshots` artifact (its first run GREEN). The gradle
  plugin deliberately ABSENT: 1.26.0 does not exist (the first catch) and
  1.77.0 --- while it applies --- drags a newer AGP variant API onto the
  script classpath that hard-errors the module's pre-existing
  `srcDir(Any)` call sites (the second). SEVEN catches recorded across
  the branch: the nonexistent plugin version; the plugin-classpath
  `srcDir(Any)` breakage; the `systemProperty[key]` map-vs-method syntax;
  `robolectric 4.17.0` not existing (the line is literally `4.17`); the
  capture API being `captureRoboImage()` in the `com.github.takahirom`
  package (the Kotlin API package predates the maven-group rename); the
  `onRoot` extension import; one rebase-resolution stray brace. Each
  fixed in its own honest commit on the branch.
- **The r46 release**: tag `v0.4.5-alpha-r46` cut from the truth commit;
  `release.yml` publishes FIVE APKs (basic / engine / semantic / face /
  edit-16) each with `.sha256`, plus FIVE per-APK mapping.txt files; the
  reproducible-build workflow measures basic+edit as before; the
  release-notes doc restructured (r46 sections on top, r45 kept verbatim
  as history).
- **F-33 (open)** --- the engine-flavor reproducibility measurement: the
  reproducible-build workflow verifies basic+edit only; a follow-up
  should measure the engine/semantic/face release pairs (with
  `-PwithEngine`) once the cargo-ndk toolchain determinism is trusted on
  runners.
- **F-34 (open)** --- Roborazzi baselines: download the
  `roborazzi-screenshots` artifact from the first green record run,
  commit the PNGs under `src/test/resources` (or the recorded output
  dir), then activate `verifyRoborazziBasicDebug` in `screenshots.yml`
  (the recipe is docs/phase-11/roborazzi.md section 5).
- **F-35 (open, HUMAN-ONLY)** --- the device rows for the r46 window: the
  five-APK coexistence install matrix, the gallery-to-edit round trip
  with BOTH r46 APKs (the contract-fix field verification), the basic
  APK's engine-UI-free walkthrough, and the engine-family consent flows.
- **F-36 (open)** --- the gallery manifest `<queries>` element declares
  the contract action with `image/*` only; add
  `<package android:name="io.github.dukeoftoad.gallery.edit"/>` (+ the
  specific/video mimes) so the resolver's probe is visibility-proof on
  API 30+ regardless of query shape.
- **F-37 (open)** --- the F-Droid metadata refresh: `fdroid/metadata/`
  describes the single gallery build; r46's engine-family release APKs
  now package the vendored AGPL engine (the r45-era "no distributed APK
  contains the vendored AGPL engine" claim is superseded), so the
  metadata + `docs/phase-11/fdroid-metadata.md` need the r46 truth before
  any submission (F-29 stays human-only).
- **Note** --- `progress.json` at merge-time truth with this window:
  p11 evidence gains the r46 rounds, `updatedAt` filled with a real
  timestamp (the `{{R42_MERGE_TS}}` template placeholder finally
  replaced), the percent STAYS 92 (p11 was already done; weights
  validated = 100). The entePort scope is untouched (the e9 dogfood rows
  stay human-only; that scope's percent stays 95). The
  `v0.4.5-alpha-r46` tag is cut FROM this truth commit so the shipped
  APKs' embedded progress snapshot reports the r46 truth.

## The r47 window (2026-11-06) --- the Roborazzi pipeline goes plugin-first (toadFILES parity)

Branch `tg-r47-a-roborazzi`, task-1 of the r47 split. The r46-era
"deliberately ABSENT" Roborazzi gradle plugin verdict was re-examined at
the artifact level and did not survive: the published 1.77.0 plugin's
runtime graph (POM + gradle module metadata) carries NO AGP at all, every
AGP class its bytecode references exists in this repo's AGP 9.2.1
gradle-api jar, and the sibling toadFILES repo runs this exact plugin
version green on a newer AGP line (9.5.0 / Gradle 9.8.2). Landed:

- `gradle/libs.versions.toml`: the `roborazzi` PLUGIN alias
  (`io.github.takahirom.roborazzi`, version.ref `roborazzi` = 1.77.0) is
  declared; `apps/gallery/build.gradle.kts` applies it and adds a
  `roborazzi { }` extension block pointing the capture output dir at
  `apps/gallery/src/test/resources/roborazzi` (the committed-baseline
  location; the build-dir default can never hold committed goldens).
- The three `srcDir` call sites in `apps/gallery/build.gradle.kts` (the
  withEngine jniLibs dir, the copyProgressJson generated assets dir, the
  withBundledModels assets dir) were re-audited and STAY on the explicit
  plain-File form (`...get().asFile`) --- the form AGP 9.2.1 accepts at
  RUNTIME; the audit comments at all three sites now record the r47
  verification.
- `.github/workflows/screenshots.yml`: mode input (record/verify, default
  verify), a committed-baseline count probe (`git ls-files`), a record
  step (`recordRoborazziBasicDebug`) that also fires whenever zero
  baselines are committed, a verify gate step
  (`verifyRoborazziBasicDebug`) that runs once baselines exist, a LOUD
  honest-skip step (step log + `$GITHUB_STEP_SUMMARY` + `::notice::`,
  never continue-on-error) while they do not, and the artifact renamed
  `roborazzi-baselines`. A PLAIN unit-test run no longer records ---
  plugin-first parity with toadFILES.
- The three screenshot tests + `docs/phase-11/roborazzi.md` updated to
  the plugin-first truth (r46 history kept honest in sections 2 and 7).

- **F-34 (PROGRESSED, still open)** --- the r47 round wires the gate:
  the verify step exists in the workflow and activates itself the moment
  PNGs are committed under `apps/gallery/src/test/resources/roborazzi/`.
  What remains is exactly the human step the original F-34 text called
  for: download the (renamed) `roborazzi-baselines` artifact from a green
  record run, unzip at the repo root, commit the PNGs (the recipe is
  docs/phase-11/roborazzi.md section 5). No workflow edit is needed to
  activate the gate.

## The r47 window, part 2 (2026-11-05) --- the device-screenshot evidence round

The maintainer re-attached the four field screenshots from the r46
`toadGALLERY Engine` build (plus a fresh credential for the push/PR the
r46-era sandbox lacked). What they decoded to, and what shipped:

- **The viewer screenshot** (two identical pencil icons for Edit/Rename +
  the "7 of 120" counter wrapped one character per line down the edge):
  both are ALREADY closed by the r47 task-3 viewer round (the labeled
  overflow menu disambiguates Edit/Rename/Delete by text; the counter is
  single-line + ellipsis). No further action.
- **The video-editor screenshot** (the M3 baseline PURPLE accents): the
  r47 task-4 log item ("the edit app's baseline color scheme") was
  promoted to a fix this round --- the new self-contained `EditorTheme`
  (toad-green dark palette, deliberately duplicated from the gallery's
  issue-#11 tokens; no shared module, the APK-matrix separation keeps
  the edit app a zero-dependency sibling; deliberately NOT dynamic
  color, the editor is fixed-dark over the canvas by design) now wraps
  both `setContent` surfaces.
- **The Settings screenshot** ("Indexing failed: indexing failed" +
  "Semantic indexing failed: semantic indexing failed"): the circular
  text is the workers' FALLBACK literals, which fire only when a worker
  reaches FAILED with EMPTY output data --- an uncaught throw escaping
  `doWork`. Two escape seams found and closed: (a) `openFaceIndex` does
  not only return false, the JNI `nativeStoreOpen` THROWS
  `IllegalStateException` with the Rust message past the worker's
  false-guard (ONE failing `ml-index.sqlite` open breaks BOTH queues
  --- exactly the field evidence of both cards failing identically);
  (b) the setup-phase calls (catalogue read, state-file load, the
  r24/r26 JNI indexed-list reads) had no catch at all. Both workers now
  carry a seam-named store-open catch + a whole-body retry-then-fail-
  with-cause catch-all (CancellationException rethrown), and the
  fallback literals now say "worker ended without a message (uncaught
  termination)".
- **F-38 (new, open)** --- the REAL native cause of the device's store
  failure is still unknown from here (no device, no SDK): the r47 build
  surfaces it by name on the Settings card ("face index store did not
  open: \<engine cause\>" or "uncaught \<class\>: \<message\>"). The
  next dogfood run of the r47 APKs reports it; diagnose from that text
  (leading candidate: the device's `ml-index.sqlite` left in a state
  the vendored migrations reject --- possibly from an earlier r2x
  engine build; the honest Reset path may also need a store-level
  clear, which is a design call once the message is in hand).
- **F-39 (new, open)** --- the r47 branch also carries the Roborazzi
  plugin application whose script-classpath compile is proven only on
  CI (the r46 catches documented in docs/phase-11/roborazzi.md); the
  first `screenshots.yml` run on the PR is the verdict. If the AGP
  variant-API clash resurfaces, the documented escape hatch
  (`android.sourceset.disallowProvider=false`) is set only on evidence.
- **The Photos screenshot** showed a healthy grid (the stray vertical
  "1 2 0" tile is the user's own earlier viewer screenshot displayed as
  content --- not an app flaw); no action.

---

## The r48 window (2026-11-06) --- the README screenshots + the workflow-truth pass + the indexing root causes (branch `tg-r48-screenshots-workflows`, PR #128)

- **F-34 (CLOSED this window)** --- Roborazzi baselines + the verify gate,
  the r47 half plus this round's: the README suite's explicit-path captures
  land at `apps/gallery/roborazzi/` (CWD-relative; .gitignore excludes
  neither dir), the workflow's baseline detection / record pass / artifact
  now cover BOTH capture locations, and the PNGs from this round's green
  record run commit as the baselines + the README's `docs/screenshots/`
  copies --- from that commit the `verifyRoborazziBasicDebug` gate is live.
- **F-38 (open)** --- the remaining half of the concurrent-session engine
  fix: the UI adapters (`PeopleEngineAdapter.close()`,
  `MagicSearchEngineAdapter.close()`) and the Settings self-test still call
  `engine.closeFaceIndex()` + `engine.release()`, which tears down the
  PROCESS-GLOBAL engine while an indexing queue may be mid-batch (r48
  removed the WORKERS' half of the mutual kill; the r47 catches surface the
  cause). Make the teardown conditional on no live queue chain
  (`getWorkInfosForUniqueWork` state) or reference-counted.
- **F-39 (open)** --- permanently-unreadable candidates (DRM rows, cloud
  stubs) return null forever and "stay queued" by design, so clustering /
  the fill pass only ever run at drain --- one such row keeps
  `moreRemaining` true indefinitely: "indexing never finishes" with NO
  failure message. Bounded retry or a terminal-skip state for repeat
  offenders (both queues).
- **Note (the indexing investigation's residual)** --- the r48 static review
  (agent-verified: all 26 JNI symbols match; all five model pins verified
  live against models.ente.com by content-length AND sha256; flavor gates
  conjunctive and correct) fixed the three concrete defects (the
  process-global teardown, the throwing readBytes seam, the REPLACE
  self-cancel) on top of the r47 honesty catches, and added the diagnostics
  ring lines. What it could NOT verify without a device: the Failed-card
  message text after the colon (distinguishes the fixed defects from
  "models not installed" / ABI absence --- arm64-v8a only). The first
  dogfood run after this merge answers it from the DiagLog ring.
 (r48: the README screenshots land (Roborazzi, the toadFILES pattern), the workflow-truth pass, and the 'indexing are failing' fixes)

---

## The r49 window (2026-11-06) --- the workflow organization round (branch `tg-r49-workflow-consolidation`, PR pending)

- **The consolidation (this round's whole scope)** --- the four opt-in APK
  test builders (`apk-build-engine.yml`, `engine-apk.yml`,
  `modelpack-apk.yml`, `editor-apk-build.yml`) become ONE dispatch
  surface, `.github/workflows/apk-build.yml`: a `prepare` job resolves
  the `gallery-engine` / `gallery-modelpack` / `editor` target + variants
  from dispatch inputs or the surviving `tg-engine-apk-*` /
  `tg-modelpack-*` branch-push convention (unknown branch names fail-fast
  with the fix in the message), then a gallery job (the full engine-cross
  toolchain) and an editor matrix (cheap pure-Android) build with every
  documented gate intact. Eight workflow files → five, one concern each.
- **The rot removed in the move** --- the model-pack builder's hardcoded
  `toadGALLERY-0.4.2-alpha-r28-modelpack-engine-debug.apk` staging name
  and `versionCode >= 32` identity gate (both stale since r46/r48 moved
  the checkout to 0.4.5-alpha / versionCode 38) are replaced by the
  checkout-derived naming + exact-versionCode gate the newer builders
  already used; the derivation-rule split (gallery first-live-assignment,
  editor last-wins) is now written down per file instead of living in
  release.yml's comment only.
- **Least-privilege baseline (new, enforced)** --- every workflow declares
  explicit `permissions:`; `contents: read` everywhere except
  `release.yml` (`contents: write`, the only signing/publishing path).
  Before r49, `ci.yml` and the four builders silently inherited the repo
  default token scope.
- **F-2 note (the follow-up trail)** --- `docs/ci-workflows.md`'s stale
  "the repo has no composite actions / reusable workflows" line (false
  since F-2 delivered `.github/actions/toad-setup`) is gone in the r49
  rewrite; the F-2 extraction itself stands delivered, no new item.
- **No new follow-up opened** --- the consolidation is complete as of
  this branch; the honest gap is below, not a work item.
- **Honest limit** --- the consolidated builder is proven by
  actionlint 1.7.7 (clean on all five workflows) + the ci.yml gate on
  this PR, but the three dispatch paths (gallery-engine release variant,
  gallery-modelpack, editor legs) have NOT been dispatched for real yet;
  the first real dispatch is the verdict, exactly like the r48 honest
  limits note. The `tg-engine-apk-*` / `tg-modelpack-*` push triggers are
  exercised the next time a dogfood branch is cut.
 (r49: the workflows organized --- four near-duplicate APK builders become one `apk-build.yml` dispatch surface, the least-privilege permissions baseline, and the living docs rewritten to the five-file truth)

---

## The r50 window (2026-11-06) --- the UI/UX polish round (branch `tg-r50-uiux-polish`, PR #130)

- **The audits first** --- three parallel read-only agents over the basic +
  engine flavors: (1) the core screens (photos/albums/search/map/settings/
  trash/places/picker/collage + the shared media components), (2) the
  viewer + the engine-gated surfaces (People, Magic Search, the Settings
  AI section, the queue state cards), (3) the cross-cutting sweep (strings,
  theme, tokens, a11y, motion). ~45 verified findings, file:line each; the
  batch above lands the high-value half, and the honest leftovers below.
- **F-40 (new, open)** --- the viewer's remaining polish halves: no
  chrome auto-hide timer (the top bar persists until tapped; gallery
  conventions fade after ~3-4s), video position not persisted across
  viewer exit (long videos restart at 0:00 on re-entry), and the zoom/pan
  clamps give no overscroll feedback. All behavior changes; none a defect.
- **F-41 (new, open)** --- the live indexing signal outside Settings:
  the People/Magic-Search empty cards say "start it from Settings" but
  cannot see a run in flight (the queue states live in the AI ViewModel);
  an "indexing N photos now" line + a deep link to the AI section (not the
  Settings root) is the designed shape. The r50 NotIndexed action button
  is the first half.
- **F-42 (new, open)** --- the identity remediation decision: 40
  direct-to-main commits (2026-10-30 to 2026-11-05) are authored by the
  sandbox identity (`Z User <z@container>`); every squash-merged round
  (r46 onward) is correctly `dukeoftoad <indvilla097@gmail.com>`. A
  history rewrite would orphan every release tag (the tags point INTO the
  affected range) and invalidate every SHA the ledger records --- the
  honest default is the recorded decision, not a rewrite; future commits
  carry the correct identity. The maintainer decides if a rewrite is ever
  wanted. (Both r50 campaigns reached this verdict independently; the
  second campaign's F-numbered entry is folded into this one.)
- **Honest limit** --- no local Android SDK (ADR-0013): CI is the only
  compile judge; the polish batch's Kotlin proofs are the repo lexer
  check + XML validation, and the Roborazzi goldens were re-recorded at
  the fix tip (green record run 42087285759) before the verify gate
  re-locked.

## The r50 window, second campaign (2026-11-06) --- the dual-flavor audit + F-38/F-39 + the r49 dispatch-bug catch (branch `tg-r50-uiux-audit`, rebased onto `tg-r50-uiux-polish`)

- **The recon** --- three more parallel audits (identity sweep, basic
  flavor, engine flavor) plus a VLM pass over the maintainer's four
  device screenshots. The identity sweep verdict matches F-42 above
  (the working tree is 100% dukeoftoad, zero replacements needed). The
  screenshot pass found the device build is PRE-r47, which focused the
  polish on what remained live.
- **F-43 (2026-11-06, open)** — Model-pin changes never re-index. The
  queue planners skip any photo whose recorded revision diffs UNCHANGED,
  and the state files carry no model-version token, so after a
  ModelManifest re-pin + re-download every state record still matches
  and the queue plans ZERO work — People/Magic Search keep serving
  old-model embeddings (mixed-version if some files re-index via
  CONTENT_CHANGED). Fix shape: stamp the state records with the engine's
  current model version and treat a version mismatch as re-index; both
  queues + a JVM test. (Deliberately deferred — correctness debt, not a
  crash.)
- **F-44 (2026-11-06, open)** — The ML queues are still manual-only:
  nothing hooks `ScanIndexHook` to the face/clip schedulers, so photos
  taken after a drain stay unindexed until the user re-opens Settings →
  Start. The Application's KDoc now states this honestly. Wiring the
  feed needs a battery/permission-timing pass (WorkManager constraints,
  maybe a foreground service — see F-45) and is its own round.
- **F-45 (2026-11-06, open)** — Long index chains run bare: no
  foreground service, no ORT thread/arena config (XNNPACK+CPU defaults
  on all big cores), so a full-library index (50/batch → 1000+ chained
  WorkManager runs) is invisible and Doze-deferrable mid-chain. The
  FOREGROUND_SERVICE permission removal is a documented decision;
  revisit with F-44.
- **F-46 (2026-11-06, open)** — `ci.yml` never compiles the
  semantic/face flavors (basic + engine only); a flavor-specific break
  surfaces only at release.yml tag time. A cheap
  `compileSemanticDebugKotlin`/`compileFaceDebugKotlin` leg (no
  packaging, no -PwithEngine needed) would close the gap.
- **F-47 (2026-11-06, open)** — A pre-r46 install upgraded in place to
  basic keeps orphaned engine data (`filesDir/models`, `face-index/`)
  with no UI to remove it (every management card is BuildShape-gated
  out), and persisted WorkManager chains re-run and fail terminally but
  invisibly. Needs a product decision (auto-cleanup vs a one-time
  "leftover data" notice) before any code.
- **F-48 (2026-11-06, open)** — CI pins the NDK (r50: preferred, not
  just fallback) but NOT the Rust toolchain (`dtolnay/rust-toolchain@stable`
  in `.github/actions/toad-setup` — unpinned rustc under edition-2024 +
  deny-level lints). A runner-image rustc bump breaks the engine job in
  CI only. Pin like the NDK when a version is agreed.
- **F-49 (2026-11-06, open)** — The edit app (apps/edit) has its own
  inset/slider polish debt (the maintainer's editor screenshots: content
  under the status bar, the trim sliders' mid-track divider reads
  broken). Out of the r50 scope (basic/engine gallery audit only);
  the edit app needs its own sweep.
- **F-50 (2026-11-06, closed within the window)** — The second
  campaign's sandbox was RECYCLED mid-round (the PAT and the local
  clone were wiped; the work continued on a fresh public clone and the
  push/PR/cleanup were blocked). RESOLVED: the maintainer restored the
  PAT; the branch is pushed (rebased onto the first campaign's green
  tip), and the remaining 34 failed + 9 cancelled Actions runs were
  deleted (the Actions history now holds zero failed and zero
  cancelled runs).
- **Honest limits** — no Android SDK in the sandbox (ADR-0013): the
  Kotlin proofs are the repo lexer check on all touched files +
  actionlint 1.7.7 + XML/YAML validation; CI is the compile judge. The
  four device screenshots analyzed for the polish pass are from a
  PRE-r47 build — the counter stacking, the duplicate pencil buttons
  and the "Indexing failed: indexing failed" tautology they show were
  already fixed on main by r47/r48; this round fixed what remained.

## The r51 windows (2026-11-06) --- the audit-verification campaign + the completion round + the merge (PRs #135–#142)

The r50 audit's fix plan (`docs/audit/2026-11-06-r50-audit-verification-and-fix-plan.md`,
PR #134) EXECUTED in full: eight disjoint fix/ci PRs (#135–#142) off
`main`@`fa954fa`, verified in two parallel waves under ADR-0013 (the
repo lexer gate + real CI as the compile judge), the two
wiring-dormancy PARTIALs completed pre-merge, then squash-merged in
order #135→#142 with the maintainer's PAT. Merged main @ `4c995bc`:
CI + CodeQL green, and the merged tree proved byte-identical to the
local merge rehearsal (zero-conflict proof).

**Per-PR disposition (the wave verdicts + the merge SHAs):**

- **#135 `95f13a1`** — B-1/B-2 (JNI `put*` catch_unwind + positive-limit guard): FIXED. Note: B-2's layer choice (boundary guard at the JNI seam) deliberate; no JNI test harness exists in-repo; residuals plan-gated (R51-3d).
- **#136 `92f56cd`** — C-4 (MlEngine facade state behind process-global shared state): FIXED. Liveness/API notes belong to the C-2/C-3 residual round, not this fix.
- **#137 `e8752e6`** — E-1/E-2/E-3 (scan integrity): FIXED, including E-3's completion commit `4d39207` (the failure-marker store wired into the production healing pass — the rotation is no longer dormant).
- **#138 `2cc45ac`** — C-5/C-9/C-3-backstop: FIXED, including C-3's completion commit `d1ccc62` (both workers pass `stateStoreFile` to `plan()` — the reset-resurrect self-heal is production-active).
- **#139 `042d34b`** — F-5/F-7/F-8/F-9 (compliance artifacts): FIXED, including F-8's doc follow-up `725c2d3` (fdroid-metadata.md caught up to the ymls: vercode 38/16 pre-staged @ `v0.4.5-alpha-r50`, the r45 tag measured byte-reproducible).
- **#140 `c2e3c9a`** — F-1/F-2/F-3/F-12/F-14 (supply chain): FIXED (APPROVE-FOR-MERGE). Notes recorded, not defects: the release permission gate executes at first real dispatch; the action pin TARGETS were not re-verifiable offline.
- **#141 `2b92196`** — D-3/D-5/D-9 (vault durability): FIXED. Note: the atomicWrite fallback removal changes the rename-failure exception type (caught by the existing `Exception` handlers; documented).
- **#142 `4c995bc`** — D-1(throttle)/D-2/D-11 (vault screen security): FIXED as scoped. The biometric path bypasses PIN throttling and a forward clock-jump can shorten a lockout — both are D-1 ROOT-FIX territory (see below).

**Still open (deliberately, none new):**

- **D-1 root fix** — fix-plan §5.1 decision-gated (per-record stored iterations recommended vs raise-and-re-enroll vs the shipped throttle-only); the maintainer's call, unchanged at 210k.
- **R51-3 waves 3a–3d** — prioritization-gated, not dispatched (read-path eligibility, About/licenses UI, scan perf, robustness sweep).
- **C-2/C-3 residuals** — the guard's TOCTOU window and the await-timeout delete-anyway path (the self-heal compensating control is now production-active).
- **PR #134** — the audit + verification record itself, open for the maintainer's merge.

**Meta-result: zero new F-numbers.** Both verification waves found NO
new defects beyond the audited findings — the 24 finding-touches
landed as 22 FIXED + 2 PARTIAL (C-3 self-heal active, D-1
throttle-only), fixing the fix-plan scoreboard to 24 FIXED · 6
PARTIALLY FIXED · 49 OPEN (§7 of the fix plan carries the delta).

**Honest limits** — ADR-0013 as always: no local Android SDK/Rust
toolchain; the proofs are the lexer check on every touched Kotlin
file (29 in the merged tree, all green), actionlint/XML/YAML
validation on the workflow PRs, and CI green at every head (34 wave
runs + 12 completion-round runs + CI/CodeQL on merged main). The
sandbox was recycled twice mid-campaign; the clone was rebuilt from
the public remote each time.

---

## 2026-11-06 — r52 window (the video editor UI/UX redesign + the mute+volume bundle)

- **F-40 (2026-11-06, open)** — Video text/effects pipeline. The
  approved design includes a Text & Effects tab; the video pipeline
  has no text/sticker/filter capability (ADR-0012 §2 excludes titles)
  and the approved bundle's navigation resolution shipped real tool
  tabs instead. IF the maintainer expands scope, the honest path is
  media3's `OverlayEffect` (bitmap overlays previewed via the same
  effect chain, export-realized by Transformer) + a font/color/
  placement model shaped like the photo editor's `TextLayers`; a
  follow-up design contract is owed before any code. No fake UI ships
  in the meantime.
- **F-41 (2026-11-06, open)** — Video-session undo/redo. The mock's
  header shows undo/redo; the redesign deferred them (session-undo
  semantics over the committed/draft model is its own design: what
  commits a history step, how drafts interact, the export-busy edge).
  The session state is one data class, so a snapshot stack is cheap
  when the semantics are decided.
- **F-42 (2026-11-06, open)** — Audio waveform strip. The mock shows a
  waveform under the main filmstrip; no honest cheap decode path
  exists (full audio decode via MediaExtractor for one decoration).
  Options recorded for later: decode-once with progress + cache keyed
  by media identity, or drop permanently. The filmstrip thumbnails are
  real and shipped.
- **F-43 (2026-11-06, open)** — Volume realization verification. The
  export path rides `EditedMediaItem.Builder.setVolume` (media3
  1.11.1). CI compiles it; the AUDIBLE gain and the >1f boost behavior
  on device codecs are device-matrix rows (human-only per the
  standing rule). If lint flags the `@FloatRange` shape on a 0f gain,
  the recorded fix is a one-line gate change (strip-audio already
  skips it).
- **Carried** — D-1 root fix (§5.1 decision), R51-3 waves 3a–3d, PR
  #134, PAT revocation (advised again after this round's push).

## 2026-11-07 — r53 window (the photo editor's design-language round: the EditorTheme violet swap + the chrome restyle)

- **F-51 (2026-11-07, open)** — The photo-editor IA unification
  question. The r53 round restyles the photo editor's chrome but
  keeps its long-scroll sectioned page and the commit-on-release
  draft semantics verbatim, while the video editor (r52) runs the
  tab-screen chassis with the Reset|Apply flow over the
  committed/draft session. One design language now, two information
  architectures — deliberate for this round (no photo mockups exist;
  every design image is a video screen, and the long-scroll IA is
  the battle-tested r30–r41 contract), but the per-tool-screen
  question for the photo editor is a maintainer decision owed BEFORE
  any restructure. No fake redesign ships in the meantime.
- **F-52 (2026-11-07, open)** — The derived violet neutrals are
  documented derivations, not design-approved tokens. `EditorTheme`'s
  ten pinned tokens ride the maintainer-approved r52 approval; the
  six derived roles in the KDoc (primaryContainer #2E2347,
  onPrimaryContainer #EDE7FA, inversePrimary #7C5CE0,
  tertiaryContainer #3A2D55, onTertiaryContainer #E6DFF7,
  outlineVariant #2E2740) are this round's documented derivations.
  If the maintainer pins exact values later, the theme file is the
  single edit point.
- **Carried** — D-1 root fix (§5.1 decision), R51-3 waves 3a–3d, PR
  #134, the r52 video follow-ups (the text/effects pipeline,
  video-session undo/redo, the waveform strip, the volume device
  rows), PAT revocation (advised once this round's push lands).

## 2026-11-07 — r54 window (the photo editor's re-chassis: the video editor's tab-bar architecture on the photo surface)

- **F-53 (2026-11-07, open)** — The AI tab's granularity. r54 puts
  the four r41 AI tools (magic erase, sky, bokeh, retouch) behind
  ONE AI tab — they share the job machinery (the hoisted
  progress/cancel line, runAiCommit, the notice slot) and one tab
  keeps the bar at ten; four separate tabs would make it thirteen.
  If the maintainer prefers four top-level AI tabs, the when-branch
  split is mechanical (the branch boundaries already exist).
- **F-54 (2026-11-07, open)** — The VIDEO editor still lacks the
  BackHandler (r54 added it to the photo tool screens; on the video
  surface, system back on a tool screen exits the whole editor).
  Port the one-liner when the video surface is next touched.
- **F-51 (2026-11-07, CLOSED by r54)** — The photo-editor IA
  question is answered: the maintainer's "Of course same design for
  photo editor as like video Editor. So do implement now" is the
  affirmative decision r53 waited for; r54 shipped the re-chassis.
- **Carried** — D-1 root fix (§5.1 decision), R51-3 waves 3a–3d, PR
  #134, the r52 video follow-ups (F-40 the text/effects pipeline,
  F-41 video-session undo/redo, F-42 the waveform strip, F-43 the
  volume device rows), F-52 (pin the derived violet neutrals), PAT
  revocation (advised again after this round's push).

## 2026-11-07 — r55 window (the one-launcher unification round: the alias consolidation + the Settings "Installed components" section)

- **F-55 (2026-11-07, open)** — The BETA naming decision. The
  maintainer's directive says "build the app for beta release"; r55
  keeps `versionName 0.4.5-alpha` (gallery versionCode 40) because a
  versionName change is a maintainer-owned product decision (the
  ADR-0018 rule: the stable cut is human-made; the same taste governs
  a beta label). When the maintainer names the beta cut, the edit is
  one line in each app's gradle file + the tag
  (`v<galleryVersionName>-r<round>` convention).
- **F-56 (2026-11-07, open)** — Deeper AI/ML unification. The
  directive's "Then like that we will do for Ai/ml, rust, kotlin,
  edit gallery app, etc" sketches the next unification layer beyond
  the Components section: candidates include surfacing the Rust
  engine version + ABI + the ONNX runtime version in the components
  rows (a BuildConfig/BuildShape-driven detail line), and an
  in-basic "toadAI" discovery surface that deep-links the engine
  build's consent + model cards instead of only opening the app.
  Needs a maintainer taste call before any build-shape rework.
- **F-57 (2026-11-07, open)** — The components probe freshness. The
  section probes in a `remember` (re-probed per Settings entry, no
  lifecycle observer): an install/uninstall that happens WHILE the
  user stares at the page does not refresh the rows until they leave
  and return. Deliberate for r55 (five cheap reads, zero state). If
  device feedback wants live updates, the fix is a
  `registerPackageInstall/removeReceiver`-fed state line — a small,
  contained change.
- **Carried** — D-1 root fix (§5.1 decision), R51-3 waves 3a–3d, PR
  #134, the r52 video follow-ups (F-40 the text/effects pipeline,
  F-41 video-session undo/redo, F-42 the waveform strip, F-43 the
  volume device rows), F-52 (pin the derived violet neutrals),
  F-53 (AI tab granularity), F-54 (video BackHandler port), PAT
  revocation (advised again after this round's push).

## 2026-11-08 — r56 window (the face-index directory fix: SQLITE_CANTOPEN on fresh install + after Reset)

- **F-58 (2026-11-08, open)** — The post-Reset re-index UX. r56 fixes
  the open so the next Start works after Reset, but the Reset-then-
  Start path still hits a UX wrinkle: `resetFaceIndex`'s
  `deleteRecursively()` runs on a directory the helper ALSO touches
  (so the dir is recreated empty before the next Start's open);
  however, the CLIP queue's `clip-index-state.json` is also gone (the
  face Reset deletes the WHOLE shared dir), so a face-only Reset
  silently resets the CLIP bookkeeping too. The r50 F-38 comment
  flagged this (the dir is shared); r56 does not change the scope,
  but if the maintainer wants a face-only reset that preserves the
  CLIP index, the reset needs to delete `ml-index.sqlite` + the
  face state file selectively, not the whole directory. Defer to a
  maintainer taste call.
- **F-59 (2026-11-08, open)** — The Rust regression test
  (`store::tests::store_open_creates_missing_directory`) could not be
  run locally (the sandbox has 3.9 GiB RAM, no swap; the workspace's
  `ort` + `jxl-oxide` + `nalgebra` deps OOM-Kill the release build).
  CI will verify on push. If CI is green the test is good; if not,
  the next iteration trims the test's transitive build cost (the test
  is correct, but the workspace is heavy).
- **Carried** — D-1 root fix (§5.1 decision), R51-3 waves 3a–3d, PR
  #134, the r52 video follow-ups (F-40 the text/effects pipeline,
  F-41 video-session undo/redo, F-42 the waveform strip, F-43 the
  volume device rows), F-52 (pin the derived violet neutrals),
  F-53 (AI tab granularity), F-54 (video BackHandler port),
  F-55 (beta naming), F-56 (deeper AI/ML unification), F-57
  (components probe freshness), PAT revocation (advised again after
  this round's push).

- **F-58 (2026-11-07, open)** — The edit app's craft round, part two: the
  remaining structural debt the r56 round deliberately left. (a) The ten
  tool screens still live inline in `EditActivity`'s `when` — the r56
  round extracted only the CHROME (PhotoChrome.kt) and the shared controls
  (PhotoControls.kt) because those compose in isolation; each tool branch
  reads ~40 closure locals, so extraction wants a `PhotoPanelHost` seam
  and is its own round. (b) The Draw/Region/AI panels and the crop
  sliders still use the bare r32 label-over-Slider pairs — the
  `LabeledSlider` conversion covered the Adjust suite, the looks
  intensity, the frames width, the text/sticker triples and the export
  quality. (c) The press-and-hold compare is gesture-only — TalkBack has
  no press-and-hold; the a11y fix is a semantics custom action
  ("show original") with a timed hold. (d) `FontPreviewChip` rides
  `AndroidView(TextView)` because compose-ui 1.7 has no public
  android.graphics.Typeface bridge; if a later BOM ships one, swap the
  chip to a pure Compose `Text` and delete the interop.

## 2026-11-07 — the r57 panel seam (F-58a closure)

- **F-58a CLOSED (2026-11-07, this round)** — the ten tool panels are out of
  `EditActivity`: one `PhotoPanelHost` seam (the explicit state-hoisting
  contract, value + `on...Change` pairs, nothing captured) dispatching to
  ten extracted panel composables grouped by tool family
  (`PhotoPanelGeometry/Tone/Media/Layers/Paint/Ai.kt`), with the r34 swatch
  palette + `ColorSwatch` moved verbatim into the control kit
  (`PhotoControls.kt`) and the branch-private helpers (`bandNameRes`,
  `lookNameRes`, `withBand`) moved into the panel files that read them,
  and the two spec constants the chassis still routes
  (`ADJUST_SLIDERS`, `HSL_BANDS`) widened private -> internal. The bodies
  moved verbatim; the only edits inside them are the closure-state
  assignments becoming setter calls (120 rewrites, each in the round's
  audit log). Verification: `:apps:edit` compile + lint + the full JVM
  suite (459/459) + the committed Roborazzi chassis baselines re-render
  bit-identical (the zero-drift gate did its job — a refactor round's
  proof is that the pixels did not move).
- **F-58 (b/c/d, open, unchanged)** — the Draw/Region/AI/crop slider
  conversions (b), the compare's TalkBack custom action (c) and the
  font-chip API upgrade path (d) are now EASIER: the panels compose in
  isolation, so (b) is a per-file `LabeledSlider` swap with a per-panel
  screenshot pin, and (c) is one custom-semantics-action on the canvas
  seam. (d) unchanged (waiting on the compose BOM).
- **Carried**: F-52/F-53/F-54, PR #134, PAT revocation (advised after
  every round; the PAT in the 2026-11-06/07 chats should be treated as
  burned the moment the round lands).

## 2026-11-08 — r58 window (F-58b: the per-panel LabeledSlider conversions + the Crop/Draw/Regions screenshot pins)

- **F-58b-2 (2026-11-08, open)** — The AiPanel per-panel screenshot pin.
  The 6 AI slider conversions (sky-opacity, bokeh-strength, bokeh-focal,
  retouch-smoothing, retouch-eyes, retouch-slim) landed in this round,
  but the panel's per-panel Roborazzi capture could not --- `AiPanel`'s
  first parameter is `activity: EditActivity`, so it cannot compose
  standalone in a `createComposeRule` test without a real Activity
  fixture. The fix is to extract the three `activity.aiStore.contains`
  reads (and any other activity-only reads the panel makes) behind an
  explicit parameter, the same state-hoisting pattern the r57 seam used
  for every other chassis dependency. A focused follow-up round
  (`edit/r58b-2-ai-panel-seam` or similar) does the extraction + the
  screenshot pin.
- **F-58c (2026-11-08, open, carried from r56)** — The compare gesture's
  TalkBack custom action (press-and-hold is not switch-accessible). Now
  the next editor follow-up after this round and F-58b-2.
- **F-58d (2026-11-08, open, carried from r56)** — The font-chip API
  upgrade path (waiting on a compose BOM that ships the
  android.graphics Typeface bridge).
- **Carried**: F-52/F-53/F-54, PR #134, PAT revocation (advised after
  every round).

## 2026-11-08 — r59 window (the unified app + the debug tools + the four-PR merge)

- **F-60 (2026-11-08, open)** — The unified app's release promotion: the
  unified flavor ships via unified-apk.yml (manual, artifact) only; a
  tagged release requires the F-14 gate to gain the unified allowlist
  (gallery-engine's set + edit's set + the unified DYNAMIC_RECEIVER
  permission) and release.yml to build a sixth APK. Maintainer taste call.
- **F-61 (2026-11-08, open)** — Components-row honesty on the unified
  build: the Installed-components probe reports the edit app by its
  standalone id, so the unified build's edit row reads "not installed"
  even though the editor is embedded; the row could special-case self.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, PAT
  revocation (advised again).

## 2026-11-08 — r60 window (the device-feedback round: progress bars + the honest face self-test + the screenshot flaws)

- **F-62 (2026-11-08, open)** — The self-test's per-photo detail: the
  r60 multi-photo scan reports aggregate faces/ms and the first hit;
  per-photo timings (and which of the 12 were skipped as unreadable)
  would make the zero case even more auditable on a device.
- **F-63 (2026-11-08, open)** — The Installed-components page's intro
  copy ("ships as separate installable apps") is the family's truth but
  reads oddly ON the unified build, where the editor row narrates an
  embedded-in-process editor; the copy could branch on the unified
  flavor like the self row's flavor copy does.
- **F-64 (2026-11-08, open)** — The People tab's empty state says
  "they appear after the background face index runs" even when the
  queue ALREADY drained with zero faces found (a screenshots-only
  library); the empty state could read the queue's terminal state and
  say "no faces found in the indexed photos" instead — the same honesty
  split the r60 self-test gained.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, F-60,
  F-61, PAT revocation (advised again).

## 2026-11-08 — r61 window (the stable-signing round: the ADR-0018 release contract wired, the public CI debug key, the hard signature gates)

- **F-65 (2026-11-08, open)** — versionCode monotonicity is a manual
  per-round bump in `apps/gallery/build.gradle.kts` (41 as of r61). A
  missed bump ships a unified APK that cannot install over the previous
  one even with a stable signer. The unified dispatches could instead
  derive versionCode from `GITHUB_RUN_NUMBER` (offset above 41) via a
  `-PciVersionCode` property, keeping the manual value as the floor.
- **F-66 (2026-11-08, open)** — the unified app still has NO tag-driven
  release path of its own: unified-apk.yml is manual-dispatch only, and
  the release.yml F-14 permission gate lacks the unified allowlist entry
  (the unified-apk.yml header already names the required permission
  set). If the maintainer promotes the unified app to a tagged release,
  that allowlist entry must land first.
- **F-67 (2026-11-08, open)** — apk-build.yml's flavor debug artifacts
  still sign with the ephemeral runner-local debug keystore (the same
  uninstall-every-time pain if ever dogfooded); the r61 ciDebug wiring
  is deliberately scoped to unified-apk.yml only. A later round could
  set TG_DEBUG_KEYSTORE_FILE there too.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, F-60,
  F-61, F-62, F-63, F-64, PAT revocation (advised again).

## 2026-11-08 — r62 window (the Settings craft: segmented tabs, the Stories scroll-away + toggle, Storage dashboard, Privacy center, Material You toggle, Models group)

- **F-68 (2026-11-08, open)** — The Appearance extras the r62 round cut
  for scope: a date-format selector (the timeline's group labels) and a
  video-autoplay toggle (the viewer); both need cross-surface plumbing
  beyond the Appearance tab and deserve their own round.
- **F-69 (2026-11-08, open)** — The accent picker beyond the r62
  Material You toggle: a explicit "toad green / wallpaper / mono"
  tri-state would need a small palette registry in Theme.kt and WCAG
  re-verification per seed (the #3E9B4F seed's own 3.5:1 lesson is the
  cautionary tale).
- **F-70 (2026-11-08, open)** — The Storage dashboard's rows could
  deep-link to their management surfaces (models row -> the AI tab's
  pack cards, vault row -> the vault screen) instead of narrating where
  the controls live.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, F-60,
  F-61, F-62, F-63, F-64, F-65, F-66, F-67, PAT revocation (advised
  again).

## 2026-11-08 — r63 window (the editor craft: the fix-first round, violet retires to toad-green, the Ente tune-rail, trim-on-filmstrip)

- **F-71 (2026-11-08, recorded, still open pending the device check)** —
  the on-device editor chrome offset: the audit's P1 showed the photo
  header floating ~26-30% down (≈630px of black above it) while the code
  composes it as the FIRST child of the fillMaxSize root Column
  (EditActivity.kt, the r63a evidence comment carries the full hunt).
  The r63a root-cause round exonerated the composition tree: no center
  arrangement, exactly one inset payment (.systemBarsPadding() at the
  root), nothing composed above the header, the unified APK launches
  this exact activity (apps/gallery/src/unified/AndroidManifest.xml),
  no scroll parent --- and the cross-editor control (the video editor,
  same device, same root pattern) rendered TOP-anchored. Verdict: a
  device-side window state at capture time (a mid-launch
  window-transition frame or a compat/windowed bottom-anchored
  placement), not producible by this tree. A JVM pin
  (EditHeaderPinningTest.headerIsTheTopMostElementInTheProductionRootShape)
  asserts boundsInRoot.top == 0 so a real regression has a signature.
  REMAINS OPEN until the vc43 device check shows the header pinned top
  in a steady-state capture; if it reproduces, the next step is a
  bugreport + window-state dump, not a layout patch.
- **F-72 (2026-11-08, open)** — the photo canvas pinch-zoom +
  double-tap-fit. Explicitly cut from r63b's scope (the approved item 8
  ledger plan); the chassis works without it. Needs the gesture layer
  around the canvas Box (scale/pan state vs the pipeline preview) and a
  decide on how it interacts with the compare chip's hold gesture.
- **F-73 (2026-11-08, open)** — the Regions→Draw merge candidate (one
  canvas-paint tab with two modes). Needs its own consent-free UX pass
  (the Regions consent surface must not silently disappear); not bundled
  into r63 by design.
- **F-74 (2026-11-08, FIXED this round)** — the unified editor was never
  in the APK. The maintainer's fresh-install round ("uninstall the old
  APKs, install only the unified r63 build") hit the Edit tap doing
  nothing, and the dex probe on the shipped vc42/vc43 artifacts proved
  ZERO `io/github/dukeoftoad/gallery/edit` strings in every unified dex
  since r59: the legacy `.java.srcDirs(...)` call silently stopped feeding
  the AGP 9 built-in Kotlin compiler while the same legacy API on
  res/assets kept merging (resources shipped, classes never did). Older
  devices never noticed because the separately-installed edit APK answered
  the contract. Fixed in three layers: the operative one registers
  `../edit/src/main/kotlin` on the unified source set's KOTLIN directory
  set (the offline probe proved AGP 9.2.1 built-in Kotlin seeds its
  compile task from the per-source-set kotlin set --- main compiles only
  because src/main/kotlin is its DEFAULT seed; the first attempt used the
  java directory set and CI run #11's own embedded-code gate caught the
  miss: assemble green, classes still absent), unified-apk.yml hard-gates
  the embedded editor classes + the engine JNI library before upload
  (scanning every classes*.dex --- the editor sources push the build
  multi-dex), and the packaging-discipline guard covers `Unified`.
  versionCode 44 rides the fix. Round 4: the registration is now made at
  BOTH layers --- the sourceSets kotlin directory set AND the Variant API
  (`variant.sources.kotlin.addStaticSourceDirectory(...)` for ../edit and
  src/unified/kotlin, the compile task's direct documented input; the
  method name pinned by javap of gradle-api-9.2.1), and r59's
  EditorRAlias R-seam is public. Rounds 5-6: the alias seam turned out to
  be a dead end --- the round-5 diagnostic artifact (the generated R jars
  captured from the failing build) proves the compile R.jar carries all
  277 editor entries under io.github.dukeoftoad.gallery.R$string, no
  edit-package R exists on any classpath, and yet K2 refuses to resolve
  the editor sources' bare R references through the source-level
  typealias. The seam is now a GENERATED source class instead:
  generateEditRCompat produces io/github/dukeoftoad/gallery/edit/R.java
  from the unified variant's compile symbol list (build/intermediates/
  compile_symbol_list), delegating every resource field to
  io.github.dukeoftoad.gallery.R --- a source CLASS wins resolution
  unambiguously, the delegation keeps runtime values correct (non-final
  fields, the same non-final contract the runtime R replacement relies
  on), and apps/edit stays untouched.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, F-60,
  F-61, F-62, F-63, F-64, F-65, F-66, F-67, F-68, F-69 (the
  EditorPalette registry the r63b re-pin landed IS its plug-in point),
  F-70, PAT revocation (advised again).

## 2026-11-09 — r64 window (the Settings re-craft: modern card UX, the theme preference, the dev surfaces retire)

- **r64 (2026-11-09, FIXED this round)** — The maintainer's post-vc44
  directive: "All are working..! Now polish, fix the flaws, make the app
  modern, remove development progress in setting page, add more features
  to setting tab with ux redesign, scrollable, more features in ui(hand
  written) with ux." Landed on `feat/r64-settings-modern` (branched from
  unified-app @ 2fc6e74): (1) the Settings page becomes a modern carded
  page --- the segmented tabs PINNED above a scrollable body (the switcher
  stays reachable however far the content scrolls; the scroll resets on
  every switch), every section a rounded group card of icon-badged rows
  (SettingsNavRow / SettingsToggleRow, hand-written on the house tokens;
  three hand-drawn badge glyphs in res/drawable: theme contrast circle,
  stories ring+play, picker photo-frame --- the bin/vault rows reuse
  ic_trash/ic_vault and the Material slots ride the core icon set);
  (2) a REAL new preference: ThemeMode (System/Light/Dark, persisted
  under "theme_mode", resolved at the shell theme call so the whole app
  re-themes live --- default SYSTEM = the pre-r64 render); (3) the F-1
  "Development progress" surface is REMOVED in full (screen, route,
  entry, strings, the ProgressSnapshot parser + its test, and the
  build-time progress.json asset mirror with its merge/lintVital wiring)
  --- the product UI carries no dev-dashboard bytes; (4) the r55
  "Installed components" family probe is REMOVED (in the unified build
  the maintainer ships, it read "Not installed" for Engine, Semantic,
  Face and Edit --- capabilities embedded all along; the About card's
  BuildShape-derived build line replaces it with the honest version +
  features summary); (5) the SYSTEM tab's recycle-bin and vault sections
  fold into the Storage card as its first rows (the duplicate vault/trash
  measurement rows merge away; a locked vault still leaks no count or
  size); (6) the video editor's Adjust chips drop the "Name: value"
  repeat (name-only chips; the header carries the live value --- the
  device screenshot's "Contr..." half-cut chip). versionCode 45.

## 2026-11-09 — r65 window (the r63-proposal cross-verification + F-72 lands)

- **Cross-verification (2026-11-09, done)** --- the maintainer's directive:
  "cross verify with r63 editor redesign proposal html. if current state
  of repo is generic and flaws as like attached document shows - fix it".
  Every spec item in the approved proposal (the §3 hex table, the §4
  photo chrome + panels, the §5 video chassis) audited against the
  shipped r63/vc44-45 sources: ALL landed (the audit table is ledgered
  in the CHANGELOG's r65 section). The only deferred item was the
  proposal's own F-72 escape hatch; F-73 (Regions to Draw merge) stays
  open per the proposal's own rule (its own consent-free UX pass, never
  bundled).
- **F-72 (2026-11-09, FIXED this round)** --- the photo editor's MAIN
  canvas zooms: pinch 1x-4x (the proposal's P4 line verbatim),
  two-finger pan while zoomed, double-tap-to-fit. CanvasZoomMath (pure,
  JVM-pinned by CanvasZoomMathTest: bounds / centroid-kept / overflow
  parking / exact re-center / ceiling absorption) + a custom TWO-pointer
  transform loop in EditActivity (deliberately not
  detectTransformGestures, whose one-finger consumption would cancel the
  compare press-hold the moment a held finger moved), a graphicsLayer
  pose layer wrapping the preview + crop overlay together (the compare
  chip/badge stay chrome, outside the pose), clipToBounds so the zoomed
  canvas never paints over the chrome, reset on every tool entry (the
  ten tools keep their unzoomed coordinate space), inert while
  exporting. versionCode 46.
- **Carried**: F-52/F-53/F-54, PR #134, F-58b-2, F-58c, F-58d, F-60,
  F-61, F-62, F-63, F-64, F-65, F-66, F-67, F-68, F-69, F-70, F-73
  (Regions to Draw merge --- its own UX pass), PAT revocation (advised
  again).

## 2026-11-12 — the r68 suspension window (the Roborazzi PR gates rest; the unit lane widens)

- **F-75 (2026-11-12, open) — the instrumented E2E lane.** With Roborazzi's
  PR gate suspended (manual dispatch only, `screenshots.yml`) the editor's
  gates are lint debug + assemble + the FULL `:editor:core` unit suite
  (widened this round from the r67 Lilypad\* filter). The third leg the
  founder named --- E2E --- has no lane yet: the repo's only instrumented
  test is gallery's `PrivacyEgressTest` and NO workflow runs
  `connectedDebugAndroidTest` anywhere. The follow-up: an emulator-job
  workflow (reactive-android/emulator runner or an actions/setup-java +
  avd-manager shape) running the editor app's instrumented suite on the
  editor branch, sized to the 20-minute budget the other jobs keep. Do NOT
  bolt it onto editor-ci.yml --- a separate `editor-e2e.yml` keeps the fast
  lane fast.
- **F-76 (2026-11-12, open) — re-arming decision.** The Roborazzi PR gate
  suspension is reversible by design (re-add the `pull_request:` block to
  `screenshots.yml`). Revisit when the Lilypad phases land and the UI
  cadence slows: the harness, baselines, and plugin wiring all survive.

## 2026-11-12 — the r69 window (Phase 3 starts: the timeline foundation)

- **F-77 (2026-11-12, open) — the multi-clip surfaces.** The
  `ClipTimeline` model (`shared/video-edit`, validator + math +
  single-clip-equivalence pins) is landed and JVM-gated, but every
  surface above it is still single-clip: the strip renders the session's
  one source, the runner builds one clipped item per speed run of one
  URI, and the editor contract (`EditRequest`) carries one `sourceUri`.
  The follow-up chain: the in-editor clip picker (add / remove /
  reorder), the contract v3 extra (a clip list or a picker round-trip),
  `Media3VideoExportRunner` growing one `EditedMediaItem` per
  `ClipTimelineEntry` (the per-item `Effects` structurally supports
  per-clip looks already), and the ExoPlayer preview becoming a playlist
  of clipped items so per-clip speed previews honestly.
- **F-78 (2026-11-12, open) — window-aware thumbnail decode.** The r69
  strip draws the whole-clip even decode at TRUE source positions (the
  honest-sparse rule): correct but sparse when zoomed. The R2-budget
  follow-up: decode frames for the visible window on zoom settle (LRU
  cache keyed by source + timestamp bucket), so a zoomed timeline fills
  with real frames instead of gaps.

## 2026-11-12 — the r70 window (the multi-clip export realization)

- **F-77 progress (2026-11-12, updated) — the multi-clip surfaces.** The
  export half of the chain LANDED this round: the seam grew a sealed
  `VideoExportJob` (the v1 `VideoExportRequest` + the new
  `ClipExportRequest`), the coordinator validates both, and
  `Media3VideoExportRunner` resolves either into one flat spec list ---
  the v1 plan path byte-identical to the r42-r66 shape (the ClipExportTest
  equivalence pin holds it there), the timeline path one spec per entry
  with per-entry strip decisions. The whole pure module is now verified
  locally before push (standalone kotlinc + JUnit --- 133 tests green),
  a first for the campaign. REMAINING: the in-editor clip picker, the
  contract v3 extra, and the ExoPlayer playlist preview (per-clip speed
  previewing honestly).
- **F-79 (2026-11-12, open) — the toolchain note.** The local kotlinc
  recipe (kotlinc 2.3.10 + junit-4.13.2 + hamcrest-1.3, compile the pure
  module's two source trees together, run JUnitCore) verified the shared
  module without Gradle or the Android SDK. Worth a script under tools/
  when a second pure module wants it; not done this round to keep the
  diff on the feature.

## 2026-11-12 — the r71 window (Phase 2's declared follow-up lands)

- **The per-channel curve composition (FIXED this round).** Phase 2's
  documented pending item --- "the channels' composition is the declared
  follow-up" --- is landed: `LutBuilder.buildChannels` composes
  master-then-channel per channel table (`redLut[v] =
  redCurve(masterCurve(base[v]))`, the five frozen sliders feeding the
  chain first), `PixelOps.applyChannelLuts` applies the three tables in
  one pass, and `EditPipeline` routes to the three-table path only when
  `ToneCurves.hasChannelCurves` (an honest curve, not a degenerate
  list). The r68 master-only path stays byte-frozen; the no-channel
  equivalence and the three-copies-of-one-table equivalence are pinned
  in `LilypadChannelCurveTest`. No migration: an r68 sidecar that
  carried channel curves as forward state renders its promised colour
  today, pinned by test. Local verification per the F-79 recipe:
  kotlinc 2.3.10 + JUnit (+ org.json + the contract enums), **119
  tests green** before the push.
- **F-79 progress (2026-11-12, updated) — the toolchain note.** The
  recipe now covers the editor/core pure subset too (the tone chain's
  dependency closure: Adjustments, ToneCurves, the curve/keystone
  maths, EditRecipe's codec, EditPipeline, Geometry, PixelAdjuster,
  AiRender + EditAiStore + BundledSkies, the layer families, CubeLut,
  ExportPlan, plus the contract enums). Two additions beyond r70's
  shape: the `org.json` jar on the classpath (the codec's only
  non-Kotlin dependency) and the Kotlin stdlib at RUNTIME (JUnitCore
  needs `kotlin.NoWhenBranchMatchedException` etc. on the java -cp).
  Still worth a tools/ script when a third consumer appears.
