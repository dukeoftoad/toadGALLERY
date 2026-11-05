# toadGALLERY --- Development Roadmap

**Status:** v2.2 --- revised 2026-10-29\
**Product vision:** See `docs/product-vision.md` for the authoritative
north-star statement. The short version: a single, private, offline-first
Android gallery that browses and organises like Mi Gallery, exposes
metadata like Aves libre, edits like Mi Gallery's editor, and respects
your data like Fossify. **Built from scratch in Kotlin + Jetpack Compose.
No code is ported from any upstream --- only features, UI/UX, and
designs. No Flutter. CI is GitHub Actions.**

---

## Change log

### v2.15 (2026-11-05, latest) --- Phase 11 complete: the release-engineering
  round r43 MERGED + the F-Droid metadata round r44 MERGED + the
  release-docs round r45 MERGED; p11 done (the project percent 91 -> 92)

- **The r43 release-engineering round MERGED (PR #121 squash `d27f3c1`,
  merge-gate runs 41996006593 + 41996006784 green at the `648d4ee`
  head)** --- the release pipeline ships BOTH apps from one commit: the
  edit app's release variant exists at last (the env-injected `TG_*`
  signing per ADR-0018's gallery convention --- same release key, same
  secrets, same unsigned fallback --- plus R8 minify + resource shrink
  over the new `proguard-rules.pro`, so BOTH apps ship a mapping.txt),
  `release.yml` builds both `assembleRelease`, hard-gates EACH APK's
  signature against the same pinned digest, and stages per-app named
  artifacts (`toadGALLERY-<version>-r<round>.apk` +
  `toadGALLERY-edit-<version>-r<round>.apk`, each with `.sha256` +
  `-mapping.txt` --- for better finding), with the Phase 11 close-out
  release body; the new `reproducible-build.yml` verifies the canonical
  recipe on every `v*` tag (two clean unsigned builds of both apps,
  `--no-build-cache`, byte compare with the honestly named verdicts
  byte-reproducible / content-reproducible / not-reproducible, report
  artifact); versionCode 37 (gallery, `0.4.5-alpha`) + 15 (edit,
  `0.4.10-edit-alpha`). The two riding catches from the failed sanity
  dispatches at `f610860`: the copyProgressJson lintVital dependency
  (EVERY `assembleRelease` was broken --- Release run 41991609209 ---
  while the debug-only CI lint never had the copy in its graph; the
  same explicit `dependsOn` the merge*Assets tasks carry, extended to
  the `*LintVitalReportModel` writers, closes it) and
  editor-apk-build.yml's `-m1` version derivation (the failed editor
  run 41991485246: the edit gradle's append-per-round convention means
  the LAST pair wins --- `tail -1` at all three derivation sites).
- **The r44 F-Droid metadata round MERGED (PR #120 squash `791d3a0`,
  merge-gate runs 41996188270 + 41996187119 green at the `0fd77e1`
  head)** --- `fdroid/metadata/io.github.dukeoftoad.gallery.yml` +
  `io.github.dukeoftoad.gallery.edit.yml` in the current fdroiddata
  new-app shape (vercode 37 / 15 pinned to the `v0.4.5-alpha-r45` tag;
  `Binaries: []` with the signed-determinism-not-claimed rationale;
  `AntiFeatures: []` argued from the gate story; the gradle-wrapper
  scanignore + the editor's ONNX-models data disposition;
  MaintainerNotes carrying the canonical recipe environment and the
  update conventions), `docs/phase-11/fdroid-metadata.md` (the
  submission merge-request process, the build-environment requirements,
  the dependency-license walk of the actual `libs.versions.toml`
  closure --- every shipped dependency Apache-2.0/MIT and
  GPL-3.0-compatible, the honest no-proprietary-dependencies
  conclusion), and `docs/phase-11/reproducible-builds.md` (the
  canonical-recipe doc; the honest status line --- the first
  measurement is the `v0.4.5-alpha-r45` push). The riding accuracy
  fix: the gallery's INTERNET story corrected to the r22/ADR-0020
  truth (ONE allowlist-gated model-fetch grant from models.ente.com;
  the edit app's no-INTERNET claim stays true via the r41 hard-strip)
  --- an F-Droid reviewer reads the merged manifest, so the overclaim
  would fail the review.
- **The r45 release-docs round MERGED (PR #122 squash `4d797ff`,
  merge-gate run 41993838209 green at the `af98800` head)** ---
  `docs/phase-11/release-notes.md` (the master notes: the first
  both-APKs release, per-APK sections with the exact artifact names +
  versionCodes, install/update/verification notes, the complete
  version-history table of every published tag plus the CI-only
  versions, the NOT-claimed section), `known-limitations.md` (the
  standing human-only directive, F-24..F-28 with citations, the
  product scope lines, the release-engineering limits),
  `permissions-and-privacy.md` (the ACTUAL manifest declarations ---
  the gallery's pinned source set + the merged-injected trio + the
  ADR-0020 model-fetch INTERNET truth, the edit app's ORT hard-strip
  --- the data-safety answers, the store-policy notes), the
  `.github/ISSUE_TEMPLATE` package (bug report with the R8-mapping
  note, feature request with the roadmap defer-list check, support
  with the inline verification steps + the vault no-recovery honesty),
  `foss-review.md` (the self-conducted review: dependency screen, the
  empty provenance ledger per ADR-0008, the AGPL engine's
  not-in-any-APK posture, the four ONNX models' licenses, the
  binary-blob scan, the GPL-3.0 obligations --- four honest findings
  recorded), and the README's release-section rewrite to the both-APK
  shape.
- **The close-out release**: the `v0.4.5-alpha-r45` tag cut from this
  truth commit --- BOTH APKs (gallery `0.4.5-alpha` versionCode 37 +
  edit `0.4.10-edit-alpha` versionCode 15, the editor's FIRST release
  artifact) published as a GitHub pre-release with per-app `.sha256` +
  per-app `mapping.txt`, per ADR-0018 (prerelease + make_latest false
  until the v1.0 cut --- a human decision that waits on the
  human-only device rows, never a workflow default).

### v2.14 (2026-11-05) --- Phase 10 complete: the r41 Edit v3
  AI round MERGED + the r42 lightweight video editor round MERGED;
  p10 done

- **The r41 Edit v3 AI round MERGED (PR #118 squash `73cfb1b`,
  merge-gate run 41978641679 GREEN after the two recorded catches)**
  --- the sub-scope A implementation, the deferred Phase 8 scope
  delivered end to end: the four features as pixel stages between
  sharpen and the regions (the tail grows once: erase -> sky -> bokeh
  -> retouch -> the regions, which stay FINAL) --- magic erase (the
  third exclusive paint mode: the painted seed -> u2netp refinement
  -> MI-GAN fill, ONE artifact pair per layer), sky replacement (the
  shared u2netp saliency + five PROJECT-AUTHORED parametric skies +
  the import chip + the cover-crop blend at opacity), bokeh
  (Depth-Anything-V2-Small int8 once, then the strength + focal
  sliders re-render from the quantized depth artifact), and the
  OPT-IN face retouch (YuNet localization + the smoothing/eye/slim
  kernels, the first consent dialog, the settings kill-switch sheet,
  the stage refusing under consent-off even with a restored sidecar).
  The four ONNX graphs ship in `assets/models` (61,613,133 bytes ---
  the provenance sheet's stated total carried a 500-byte slip, the
  pinned literal is the true sum) through ONNX Runtime 1.31.1 as the
  prebuilt-AAR seam (NNAPI-first, CPU fallback, no editor NDK
  surface); layer types 6-9 under the verbatim tolerance contract;
  the `edit-ai/` artifact store rides the sidecar discipline; the
  privacy suite extended (no host literals, no network classes, the
  ORT surface behind the facade). The two catches: the ORT AAR's own
  manifest declares INTERNET (sample-convenience packaging) ---
  hard-stripped via `tools:node="remove"`, never an allowlist, so the
  merged manifest carries no INTERNET at all; and the ORT 1.31.1 Java
  surface corrected (16 compile errors: `Result` is `Iterable` of
  entries, `get(String)` returns `Optional` --- 12 sites + the u2netp
  output-name sort --- plus two Kotlin local-visibility orderings;
  the API from the upstream Java sources, not guessed). Editor
  `versionCode 13` (`0.4.8-edit-alpha`). The r41 model latency and
  quality rows are human-only and ride the checklist window.
- **The r42 lightweight video editor round MERGED (PR #119 squash
  `f8583d3`, merge-gate run `41988201250` GREEN after the six
  recorded catches)** --- the sub-scope B implementation: the pure
  `:shared:video-edit` module (the validated `VideoEditPlan` --- the
  trim `TimeRange` matrix, the sorted non-overlapping 0.25x..2x speed
  segments with the monotonicity rule + the derived output duration
  `Σ length/factor`, the `audioTrackPresent` input fact where mute on
  a no-audio clip is the recorded `RECORDED_NO_OP` ---
  `FrameExtractPlan`, `AnimationExportPlan` with the deduplicated
  frame count + the 32 MiB byte-budget preflight, the non-destructive
  `VideoOutputPolicy` MediaStore policy, the PROJECT-AUTHORED
  `GifEncoder` + `AnimatedWebpMuxer`, the `VideoExportRunner` seam),
  the media3 1.11.1 surface (ExoPlayer preview + Transformer export
  over the SAME shader/uniform struct; `Media3VideoExportRunner` the
  only Transformer-touching file), the ADDITIVE editor-contract v2
  launch path (the optional media-kind extra, absent = image so the
  v1 wire stays byte-identical; `MediaKindLaunchTest`), and the
  `VideoEditActivity` surface (trim/mute/slow-mo/frame-extract/
  animation sheets, the nine adjustment sliders live-previewed
  through the GL effect, honest progress + cancellation, the r32 a11y
  conventions). The round-pinned caps documented not invented
  (animation target width 64..1280 px, fps 2..30, the 32 MiB
  preflight, MP4 output container, the v1 input set MP4/MOV/3GP
  H.264/HEVC + AAC); the ADR-0012 answers recorded (Transformer at
  1.11.1 supports the pipeline on minSdk 26; the hosting is the EDIT
  APK). The six catches are the delivery record's (the
  `build.gradle.kts` conflict-marker fragment, the nonexistent
  `METADATA_KEY_FRAMERATE` constant, the `MediaExtractor`
  `setDataSource` overload, the `WEBP_LOSSLESS` API-30 guard, the
  media3 `ExperimentalApi` opt-in, and the r41 privacy scan's
  host-literal catch on the KDoc `"0.50x"` speed-label example --- each
  verified against the real SDK/AOSP surface or lint's own message,
  never guessed). Editor
  `versionCode 14`; 156 JVM tests green on the local pre-push
  harness; `strings_r42.xml` in both apps (the 74-line edit file +
  the 9-line gallery file). The device matrix rows (long/4K/HDR/
  no-audio, the measured APK size delta, the airplane-mode row,
  TalkBack-over-video) are human-only.
- **Phase 10 is DONE (code-complete)**: all four PRs merged ---
  hardening (#117 `c3c21f7`), the two sub-scope contracts (#116
  `16a5001`), the Edit v3 AI (#118 `73cfb1b`), and the video editor
  (#119 `f8583d3`). `progress.json` at merge-time truth: p10 →
  done, the project percent 85 → 91 (the weights total 100; the
  dashboard's `computeScopePercent` agrees). Every benchmark/device
  result cell is human-only (the standing directive) --- the
  skeletons' cells, the AI latency/quality rows, and the video matrix
  alike ride the physical checklist. The r41 truth updates ride the
  r42 window's truth commit (the Run #16 window closed at the context
  deadline; the F-12 convention honored, just late).

### v2.13 (2026-11-05, latest) --- Phase 10 opens: the r40 hardening
  round MERGED + the two deferred sub-scope contracts MERGED

- **The r40 hardening round MERGED (PR #117 squash `c3c21f7`,
  merge-gate run 41955524067 GREEN after the two recorded catches)**,
  the harden-first order honored as the FIRST Phase 10 round: the
  accessibility hardening sweep (the one code-expressible hardening
  task --- 17 heading anchors where ZERO existed: the 15 `TopAppBar`
  titles + the editor's top bar + the Albums/Settings section headers;
  8 polite live regions on the dynamic surfaces: the three selection
  app bars, the four batch/progress dialogs, the viewer counter; the
  r39 Things-chip remove button restored to the 48dp floor; zero new
  strings --- the existing `cd_*` copy reused everywhere), the
  background-work boundedness audit B1--B9 (one fix: the blocking
  Phase 4 provider walk gains the pure `ScanWalkPolicy` cooperative
  cancellation probe every 500 rows --- a cancelled app-start scan
  stops walking mid-volume instead of completing the whole pass
  holding the scan mutex; B2--B8 verified bounded; B9 recorded as
  follow-up F-23), the source-scan conformance suite pinning
  presence/absence per the r37 lesson (11 new JVM tests, `OK (11
  tests)` on the rebuilt `/tmp/ktool` harness --- kotlinc 2.3.10; all
  23 touched `.kt` lex-checked), and the two planning deliverables as
  skeletons (`docs/phase-10/benchmark-scenarios.md` +
  `device-matrix.md` --- every result cell human-only; the Baseline
  Profiles decision deferred until the S1/S2/S6 baselines exist).
  Gallery `versionCode 36` (`0.4.4-alpha`), editor `versionCode 12`
  (`0.4.7-edit-alpha`). The r40 device rows are human-only and ride
  the checklist window. The merge-gate's two catches are in the
delivery record's correction note (a suspend call inside the probe
  lambda; the conformance suite's sibling-module path candidate).
- **The two deferred sub-scope contracts MERGED (PR #116 squash
  `16a5001`, docs-only)**: `docs/phase-10/edit-v3-ai.md` (the Phase 8 task text
  verbatim decomposed: the AI features as pixel stages between sharpen
  and the regions with commit-time-only inference + the `edit-ai/`
  derived-artifact store, four additive recipe containers --- layer
  types 6-9 ---, bundled models under the unconditional editor
  zero-INTERNET gate with ADR-0020's pin discipline as prior art, the
  opt-in face-retouch consent machinery, the privacy suite extended
  per ADR-0010 §7) and `docs/phase-10/video-editor.md` (the Phase 9
  task text verbatim: the pure export-plan model over the media3 seam,
  the ADR-0012 adjustment-boundary table, the never-modify export
  discipline; selection criteria pinned instead of model names). The
  implementation rounds follow AFTER the hardening round's PR is green
  --- the stated order.
- **The counts are executed numbers**: 11 new JVM tests; strings
  510/129 unchanged (zero new); versionCodes 35→36 / 11→12.
  `progress.json` at merge-time truth: p10 → in-progress (the r40
  evidence + the contracts), the project percent 79 → 85 (the weights
  total 100; the dashboard's `computeScopePercent` agrees).

### v2.12 (2026-11-04) --- the r39 ente-port exit round + the
  Phase 8/9 restructure

- **Phase 5, the ente port, is code-side COMPLETE** (r39, branch
  `tg-r39-ente-exit`, per the maintainer directive "complete Phase 5 ---
  the ente port"): the two named Magic Search follow-ups close --- (a)
  **semantic-only engine sessions** (the r26 round's documented
  FACE-model requirement dropped: `nativeInitSemantic` installs a
  SEMANTIC-ONLY session from scratch over the vendored runtime's own
  blank-path configuration shape, `nativeInit` upgrades such a session
  with the face half in place, face calls on a semantic-only session
  fail with a dedicated message, and the engine info line names all
  three session shapes; the Kotlin half: the adapter split into its own
  file opening `initSemantic -> openFaceIndex` with NO `initialize`,
  the semantic queue's gates dropping the FACE capability step, and
  `MagicSearchContentPolicy` renamed `SemanticContentPolicy` with the
  FACE input dropped --- ONE truth shared by Magic Search and Things);
  (b) **Things dynamic collections** (named semantic collections over
  the SAME CLIP index: the `nativeStoreSearchClipsWithin` distance-cut
  search over upstream's own `SearchParams { limit, max_distance }`,
  `things-state.json` in the face-index directory, the pure
  membership planner at the round-pinned 0.70 cosine cut, the Albums
  tab's Things section, the `things/{collectionId}` detail screen, and
  the `thingsAlbumEnabled` consent row). What remains open in Phase 5
  is HUMAN-ONLY by the standing directive: the on-device dogfood rows
  and the §13–§18 device checklist.
- **Phases 8 & 9 SKIPPED, scope folded into Phase 10** (maintainer
  directive, 2026-11-04): the Edit v3 on-device AI features (magic
  erase, sky replacement, bokeh, opt-in face retouch) and the
  lightweight video editor move INTO Phase 10 as explicitly-deferred
  sub-scopes; Phase 10 becomes "hardening + the deferred Edit v3 AI
  editing + the lightweight video editor (absorbing Phases 8–9)".
  `progress.json` follows at merge time (p8 + p9 fold into p10, weight
  12, the project percent unchanged at 79 --- the folded rows were
  pending and stay pending).

### v2.11 (2026-11-04, latest) --- the Phase 7 campaign close-out: r38
  MERGED, p7 done, the remaining-work line closed

- **Phase 7 is CODE-COMPLETE**: the r38 frames MERGED (PR #113 squash
  `003dafb`, merge-gate run 41847157146 GREEN on the first execution)
  --- every roadmap Phase 7 bullet is delivered: the LUT filter system
  with the user-import flow (#107 `314bd9c`), the text tool (#109
  `6bb17a4`), the sticker tool (#110 `980ccea`), the freehand drawing
  tool (#111 `8c466ee`), the mosaic/blur regions (#112 `6c206d9`, the
  UI-section debt fix riding #113), and the frames + the undo/redo
  extension capstone (#113 `003dafb`). The campaign's composite order
  is final: pixel pipeline (… -> sharpen -> mosaic/blur regions) ->
  stickers -> strokes -> text -> frame.
- **`progress.json` at merge-time truth**: p7 → done (tasks 1-5/5 with
  every sha); percent 73 → 79 (the weights total 100; the dashboard's
  `computeScopePercent` agrees). The ente-port scope untouched per the
  directive: the dogfood device rows remain human-only.
- **The counts are executed numbers** (the r34 lesson held all
  campaign): 131 new JVM tests across r35-r38; the edit module 241 →
  374; the strings 94 → 129; the versionCodes 7 → 11
  (`0.4.6-edit-alpha`).
- The §22-§27 device rows ride the checklist, human-only. The full
  campaign story (the pre-push harness debut, the two one-iteration
  CI slips, the r37 debt and its fix, the CI cost) is the worklog's
  Run #13.

### v2.10 (2026-11-04, later) --- the r38 frames round (authored, the
  campaign's closing); the r37 regions MERGED + the r37 debt fix

- **The r37 mosaic/blur regions MERGED** (PR #112 squash `6c206d9`;
  merge-gate CI 41843555827 caught the local-function declaration
  order, **41844536338 GREEN**). The `tasks.md` bullet moves to MERGED
  truth with this entry.
- **The r37 debt fix (recorded in the r37 delivery record's correction
  note)** --- the r37 UI section never landed in the r37 branch: the
  insertion script wrote `build.gradle.kts` but never the activity; the
  merged r37 carried the model, the recipe, the pipeline stage and the
  gesture scaffolding without its surface (`regionMode` unreachable,
  the 8 `strings_r37.xml` entries shipped unused). The section rides
  the r38 branch exactly as designed; the merge-gate CI stayed green
  throughout because it compiled what was there --- it cannot test for
  absence. The lesson joins the campaign's file-write discipline.
- **Phase 7, task 5 of 5 (its second half) --- the frames AUTHORED on
  `tg-p7-frames`** (layer type 5 of 5; the design contract
  `docs/phase-7/frames.md`, its delivery record appended): the single
  `FrameSelection` (the `LutSelection` shape: ONE mounting at a time;
  an unresolvable frameId round-trips verbatim and degrades to no
  frame), five PROJECT-AUTHORED parametric frames as pure geometry
  functions over integers (plain / inner-line / double-line / film
  with punched sprocket holes / polaroid; the thickness is the r37
  kernels' formula), the shared `FrameCompositor` LAST in the composite
  order (the mounting over everything), `ImportedFrameStore` (the
  sticker store's contract, frame-shaped; an imported frame stretches
  to the full canvas with the honest no-controls note), the look-row
  UI, and **the undo/redo extension capstone**: `UndoRedoExtensionTest`
  --- a mixed five-family session, ten commits across every family's
  controls, undo to launch, redo forward, the EXACT recipe asserted at
  every step. 32 new JVM tests (the edit module at 374; the local
  harness ran 365 pure green pre-push). Editor `versionCode 11`
  (`0.4.6-edit-alpha`); strings 122 → 129. The §27 device rows ride
  the checklist, human-only.
- `progress.json` untouched until merge per the F-12 convention.

### v2.9 (2026-11-04, later) --- the r37 mosaic/blur round (authored);
  the r36 drawing tool MERGED

- **The r36 drawing tool MERGED** (PR #111 squash `8c466ee`; merge-gate
  CI 41839665527 caught the `effectiveRecipe` `pd` name collision ---
  android-bound file, CI-compiled --- then **41840639163 GREEN**, all
  three jobs). The `tasks.md` bullet moves to MERGED truth with this
  entry.
- **Phase 7, task 5 of 5 (its first half) --- the mosaic/blur regions
  AUTHORED on `tg-p7-mosaic`** (layer type 4 of 5; the design contract
  `docs/phase-7/mosaic-regions.md`, its delivery record appended): the
  pixel-stage break from the family shape --- the painted regions
  censor the FULLY graded pixels as the pipeline's FINAL stage (after
  sharpen; `STAGE_ORDER` grew the tail), grid-relative like the
  vignette (exact in the export, approximate in the preview under an
  active crop). The additive `RegionLayers` recipe with the round-four
  stacking proof (a pre-r37 sidecar carrying r34 text + r35 stickers +
  r36 strokes loads clean; an unknown `kind` is corrupt data --- the
  skip), the pure `RegionMask` (even-odd scanline fill, strict pixel
  centres --- the triangle and the self-crossing bowtie pinned by
  exact masks) + `RegionEffects` (integer-deterministic: the mosaic
  block average over origin-aligned cells; the linear blur's
  sliding-window horizontal box over a snapshot), the second paint
  mode (mutually exclusive with Draw mode; a tap under three samples
  drops honestly), the kind chips + strength slider under the paint
  duality, and the draft region previewing AT PAINT TIME through the
  same stage the export runs. 26 new JVM tests (the edit module at
  340; the local harness ran 333 pure green pre-push, catching the
  single-arithmetic slips). Editor `versionCode 10` (`0.4.5-edit-alpha`).
  The §26 device rows ride the checklist, human-only.
- `progress.json` untouched until merge per the F-12 convention.

### v2.8 (2026-11-04, later) --- the r36 drawing-tool round (authored);
  the r35 sticker tool MERGED

- **The r35 sticker tool MERGED** (PR #110 squash `980ccea`; merge-gate
  CI run 41836972158 GREEN on the first execution --- the pre-push
  harness's debut round; all three jobs). The `tasks.md` bullet moves
  to MERGED truth with this entry.
- **Phase 7, task 4 of 5 --- the freehand drawing tool AUTHORED on
  `tg-p7-drawing`** (layer type 3 of 5; the design contract
  `docs/phase-7/drawing-tool.md`, its delivery record appended): the
  stroke-per-layer model (one gesture = one layer = one undo snapshot),
  the additive `DrawLayers` recipe with the round-three stacking proof
  (a pre-r36 sidecar carrying r34 text AND r35 stickers loads clean),
  the commit-time stride thinning to the 512 cap (first/last kept), the
  pure `DrawLayout` (the brush width, the r34 text alpha math, Chaikin
  corner-cutting round(smoothing/25) -> 0..4 passes, endpoints
  preserved), the shared `DrawCompositor` BETWEEN the sticker pass and
  the text pass (a tap is a dot), the Draw UI under the exclusive
  duality (paint mode tunes the NEXT stroke; a selected layer re-binds
  the same controls), and the ONE `sweepPendingDrafts()` helper (12
  call sites retrofitted, all additive). 27 new JVM tests (the edit
  module at 314; the local harness ran 305 pure green pre-push,
  catching two TEST-expectation slips and one parenthesised backticked
  name). Editor `versionCode 9` (`0.4.4-edit-alpha`). The §25 device
  rows ride the checklist, human-only.
- `progress.json` untouched until merge per the F-12 convention.

### v2.7 (2026-11-04, later) --- the r35 sticker-tool round (authored; the
  local pre-push harness debuts)

- **Phase 7, task 3 of 5 --- the sticker tool AUTHORED on `tg-p7-stickers`**
  (layer type 2 of 5; the design contract `docs/phase-7/stickers.md`, its
  delivery record appended): eight PROJECT-AUTHORED bundled stickers as
  IN-CODE vector shapes (`BundledStickers` --- integer coordinates in a
  0..100 design box, fills only; no image asset to pin, so the
  authorship record is `docs/phase-7/bundled-stickers.md` and the
  spot-coordinate tests carry the pin), the additive `StickerLayers`
  recipe with the image-shaped tolerance delta (a pre-r35 sidecar loads
  clean --- proven with one carrying r34 text layers, the stacking
  proof; an unresolvable `stickerId` round-trips verbatim and renders
  NOTHING; a blank stickerId is a skip alongside a blank id), the pure
  `StickerLayout` + the ONE shared `StickerCompositor` BELOW the text
  pass (scene objects under captions), `ImportedStickerStore` (PNG/WebP
  magic-before-write, verbatim bytes, derived collision-suffixed ids;
  no library-management UI yet --- the recorded F-13), the Sticker UI
  (registry chips as ADD actions, layer chips, the r34 conventions, ONE
  selected layer at a time across the families), the draft-sweep set
  extended across the families, 46 new JVM tests (the edit module at
  287), and the string recount 94 → 104. Editor `versionCode 8`
  (`0.4.3-edit-alpha`). The §24 device rows ride the checklist,
  human-only.
- **The pre-push verification debut**: this round's tests were EXECUTED
  before any push --- a local kotlinc harness (the `:apps:edit` pure
  subset, 278 tests green incl. r35's 46) caught two authored-coordinate
  errors pre-push; the r34 merge window's seven-iteration CI loop was
  exactly the failure mode this exists to prevent. The two
  android-bound files (`EditActivity.kt`, `StickerRender.kt`) remain
  CI-compiled, lex-checked locally (`tools/kotlin_lex_check.py`).
- **The r34 text-tool bullet moved to MERGED truth in `tasks.md`**
  (PR #109 `6bb17a4`, the 22→32 test-count correction riding it);
  `progress.json` untouched until merge per the F-12 convention.

### v2.6 (2026-11-04, later) --- the r34 merge window

- **Phase 7, task 2 of 5 --- the text tool MERGED** (PR #109 squash
  `6bb17a4`, merge-gate CI run 41822887363 green on head `699fd03`:
  1181 gallery + 241 editor JVM tests + the contract suite, both engine
  jobs green). The merge window iterated the never-executed r34 code to
  green in seven CI runs (the full story is the worklog's Run #12): the
  F-1 mirror's Provider-in-SourceSet configuration failure, the gallery
  test-locator paths, the editor's nine first-compile errors
  (`toggleable` is not on this foundation build's API surface --- the
  swatch uses the repo's `clickable` convention with the radio semantics
  stated explicitly), the semantics-lambda shadowing, a dotted
  backticked test name, and two wrong assertions. **PR #108 closed** by
  the merge keyword (redundant with #107's merged import flow).
- **The r34 test-count correction**: the delivery record's "22 new JVM
  tests" was a never-executed estimate --- the r34 families are **32**
  (TextLayersTest 14 + TextLayersRecipeTest 12 + BundledFontsTest 6),
  the edit module at **241** (r33's 209 + 32; corrected in place below
  and in CHANGELOG's unreleased section).

### v2.5 (2026-11-04, later) --- the r34 text tool + the F-1/F-2 follow-up window

- **Phase 7, task 2 of 5 --- the text tool** (r34, branch `tg-p7-text-tool`,
  authored-pending-merge): the OFL licensing gate FIRST (four
  verbatim-bundled SIL OFL 1.1 faces --- Fira Sans, Playfair Display,
  Caveat, Space Mono --- each `OFL.txt` beside its TTF, sha256-pinned by
  `BundledFontsTest`; `docs/phase-7/bundled-fonts.md` + the §5 ledger
  rows; the prep doc's Noto Sans candidate swapped for Fira Sans with
  the recorded reason), the additive `TextLayers` recipe (absent-key
  clean, unresolvable-fontId-degrades), the pure `TextLayout` (anchors,
  draw order, the color-alpha × opacity math, the preview≡export offset
  identity), the ONE shared `TextCompositor` after the whole pixel
  pipeline, and the full editor surface (add/layer chips, the text field
  with per-commit drafts, font chips, size/opacity/rotation sliders, the
  named swatches, drag-to-position, remove). Editor `versionCode 7`
  (`0.4.2-edit-alpha`); the edit module string count 74 → 94 (the r33
  recount debt settled); 32 new JVM tests (corrected at merge: the
  never-executed estimate said 22). The delivery record is
  `docs/phase-7/text-tool.md` §Delivery record.
- **F-1 closed:** the in-app progress screen --- the repo-root
  `progress.json` mirrored into the APK assets at build time
  (`copyProgressJson`, the model-pack srcDir pattern) and rendered by
  Settings → Development progress (the two INDEPENDENT bars + phase
  lists; a static snapshot by design --- the app never fetches progress).
  Gallery `versionCode 34`.
- **F-2 closed:** `.github/actions/toad-setup` --- the canonical CI
  toolchain setup extracted from all six workflows (rust:
  none/host/android × android true/false; identical pins and cache keys;
  −218 lines net; actionlint 1.7.12 PASS).
- **F-6 closed:** the CHANGELOG's duplicate `[0.4.2-alpha]` headings
  merged into one.

### v2.4 (2026-11-04) --- progress tracking + the debug-APK workflow family

- **Progress tracking:** `progress.json` at the repo root is now the
  machine-readable source of truth for two INDEPENDENT progress scopes ---
  `project` (roadmap Phases 0--11, currently 73%) and `entePort` (the Ente
  port workstream per `docs/phase-5/ente-pin.md`, currently 95%). The live
  dashboard (sandbox deployment) renders both bars with socket.io push + a
  4 s polling fallback (verified < 5 s); the plan, per-bar design decisions
  and manual verification steps are in `docs/workplan.md`.
- **CI:** two new dispatch-only debug-APK workflows --- `apk-build-engine.yml`
  ("APK build with engine": input-driven gallery engine build, pin
  validation, mapping/log artifacts, job summary; fail-fasts on the
  ADR-0018 `TG_RELEASE_*` secrets for a release-variant dispatch) and
  `editor-apk-build.yml` ("Editor APK build --- include all": all editor
  targets in one matrix --- `edit-debug`, unsigned `edit-release` gate,
  `contract`). Neither was dispatched in its authoring window; see
  `docs/ci-workflows.md` for triggers, inputs and required secrets.
- **Docs:** `docs/workplan.md` (this window's plan + ente-port definition),
  `follow-up.md` created (the follow-up register, F-1..F-8),
  `docs/ci-workflows.md` (workflow quick reference), `tasks.md` milestone
  table collapsed to one row per phase (the stale duplicate Phase 5/6 rows
  from the pre-merge era are gone).

### v2.3 (2026-10-30) --- Phase 3 progress tracker; CI scope narrowed (ADR-0016)

- The Phase 3 section now carries an explicit landed / in-flight progress
  note so the next stages are visible in the roadmap itself.
- ADR-0016: CI = compile + JVM unit tests only; the instrumented privacy
  suite becomes a manual on-device release gate (amends ADR-0013; moves
  ADR-0014 Layer 3 out of CI).
- Repo `CHANGELOG.md` introduced, tracking releases from 0.1.0-alpha.

### v2.2 (2026-10-29) --- features/UI/UX/designs only; GitHub Actions CI

Driven by two user directives on 2026-10-29:

1. "we are taking only features, ui/ux, designs from all apps for
   toadGALLERY." --- ADR-0008 supersedes ADR-0001; ADR-0009 supersedes
   ADR-0005. No code ports from any upstream. No `org.fossify:commons`.
   No Aves `exifinterface` fork port --- use `androidx.exifinterface`
   directly. Build toadGALLERY from scratch in Kotlin + Compose.
2. "another blocker you said about environment which we use github
   actions as solution." --- ADR-0013 added. GitHub Actions is the
   canonical build and CI environment; the local-build requirement is
   dropped.

**Structural changes (cumulative across v2.0, v2.1, v2.2):**

- **Phase 6 expanded into Phases 6, 7, 8** --- the editor is a flagship
  delivered in three layers: core adjustments, filters + annotations,
  on-device AI.
- **Phase 4 deepened** --- metadata layer covers EXIF/IPTC/XMP *write*
  (not just read), tags/keywords/ratings/colour labels/comments,
  Aves-style collections, on-device maps (no online tiles).
- **Phase 5 (organisational UX)** --- MI-style: Moments timeline,
  Stories, auto-albums (People/Places/Things, opt-in), smart semantic
  search, collage maker, slideshow with transitions.
- **Phase 9 (video editor)** --- lightweight: trim, mute, slow-mo, frame
  extraction, GIF/WebP export.
- **Phase 7 (v1) folded into Phases 2 and 3** --- trash, recycle bin,
  hidden vault, accessibility, privacy hardening baked in early.
- **Phase 8 (v1) folded into Phase 10** --- performance and
  compatibility hardening.
- **Phase 9 (v1) renumbered Phase 11** and expanded --- editor APK
  split-out, contract finalisation, F-Droid metadata, third-party FOSS
  review, signing, v1.0 cut.

**Scope changes:**

- **Removed from defer list:** "AI classification or generative
  editing." On-device AI is now in Phase 8 (ADR-0010).
- **Stays deferred:** cloud sync, accounts, social sharing, ads,
  analytics, online maps/reverse geocoding as a core dependency,
  complex RAW development, broad format promises without verified
  decoders.
- **New invariant:** the core app manifest does not declare `INTERNET`.
  Checked at build time. (Refined by ADR-0020, 2026-11-02: r22 adds
  `INTERNET` solely for the pinned model fetch; enforcement then shifts to
  the host allowlist + egress suite.)
- **New invariant (v2.2):** no code is ported from any upstream. The
  provenance ledger at `docs/licensing-and-attribution.md` §5 stayed empty
  until r21's ADR-0020 vendoring — the recorded exception.

**ADR impact:** ADR-0008 (features-only), ADR-0009 (Compose),
ADR-0010 (on-device AI), ADR-0011 (non-destructive pipeline),
ADR-0012 (video editor scope), ADR-0013 (GitHub Actions CI).

### v2.1 (2026-10-29) --- no-Flutter lock-in + Compose decision

ADR-0001 strengthened (Flutter not in toolchain); ADR-0008 added
(Compose for new UI). Both now superseded by ADR-0008/ADR-0009 in v2.2.

### v2.0 (2026-10-29) --- editor as flagship, on-device AI in scope, Aves-grade metadata, MI-style UX

The original revision driven by "make an app capable of editing like Mi
Gallery editor, Fossify-like privacy, organise, meta info like Aves
libre, other features like Mi Gallery have."

---

## 1. Product goals

1. Build **one coherent product** called toadGALLERY, not two competing
   gallery apps.
2. **Take features, UI/UX, and designs only** from Mi Gallery
   (proprietary, reference only), Fossify Gallery (GPL-3.0, reference
   only), and Aves libre (BSD-3-Clause, reference only). **No code is
   ported, adapted, or vendored from any upstream.**
3. Build toadGALLERY **from scratch in Kotlin + Jetpack Compose**.
4. Deliver a clean, fast, intuitive gallery with strong media browsing,
   deep metadata, smart search, safe file operations, and a flagship
   editor.
5. Ship **toadGALLERY Edit** as a separate APK, opened from the main
   Gallery via a versioned content-URI contract, updatable independently.
6. Keep the product private and offline-first: no mandatory account,
   cloud service, ads, analytics, or network access. No `INTERNET`
   permission in the core manifest (until r22's model-fetch exception per
   ADR-0020).
7. Protect originals by default. Non-destructive editing pipeline;
   recycle bin; PIN/biometric hidden vault.
8. Use **GitHub Actions as the canonical build and CI environment**.
9. Establish clear licensing, attribution, testing, and upstream-change
   tracking from the start.

## 2. Project principles

1. **No code ports.** toadGALLERY is built from scratch. Upstreams
   contribute features, UI/UX, and designs only.
2. **One product experience.** Consistent navigation, design,
   terminology, permissions, and media behavior across every screen.
3. **Choose architecture based on evidence.** ADRs record every
   significant decision and are superseded openly, never silently.
4. **The editor is a flagship, not a side feature.** Three dedicated
   phases (core, filters+annotations, AI) plus a video editor phase.
5. **On-device ML only.** Any AI feature runs with a model shipped in
   the APK. No cloud ML API calls anywhere, ever.
6. **Keep the editor separate.** Gallery and Edit are distinct
   installable apps with a documented, versioned, secure integration
   contract.
7. **Prefer incremental, buildable changes.** Maintain a working build
   at every phase; avoid a large, untestable rewrite.
8. **Preserve license obligations.** Record repository URLs, exact
   commits, and license obligations for every upstream that inspired a
   feature. The provenance ledger at `licensing-and-attribution.md` §5
   records any code-level adaptation (currently empty).
9. **No feature is complete until tested.** Include error handling,
   accessibility, performance, permissions, and large-library behavior.
10. **CI is the source of truth for "does it build."** A green GitHub
    Actions check is required to merge any PR.

## 3. Target repository and app structure

```text
toadGALLERY/
├── .github/
│   └── workflows/
│       └── ci.yml               # GitHub Actions: build, lint, test (ADR-0013)
├── apps/
│   ├── gallery/                 # Main toadGALLERY app (Kotlin + Compose)
│   └── edit-extension/          # Separate toadGALLERY Edit APK (Kotlin + Compose)
├── shared/                      # Gradle libraries (Kotlin + Compose)
│   ├── media-contract/          # MediaItem model, URI identity, three-date semantics
│   ├── metadata/                # androidx.exifinterface + IPTC/XMP wrappers (no Aves fork)
│   ├── design-system/           # Compose Material 3 tokens, theme
│   ├── editor-contract/         # Versioned URI contract constants
│   └── ml-models/               # On-device model wrappers (Phase 5/8)
├── docs/
│   ├── architecture/
│   │   ├── compose-migration.md # Tracker: Views -> Compose migration (ADR-0009)
│   │   ├── data-flow.md
│   │   ├── editor-contract.md   # ADR-0007 spec
│   │   └── module-diagram.md
│   ├── phase-0/                 # Historical records (baselines, sign-off)
│   ├── phase-1/                 # Source audits (reference only, no ports)
│   ├── phase-2/                 # Shell verification (View-based, to be migrated)
│   ├── product-vision.md        # Authoritative north star
│   ├── feature-matrix.md
│   ├── integration-decisions.md # ADR-0001..0013
│   └── licensing-and-attribution.md
├── gradle/
│   ├── libs.versions.toml       # Pinned: Kotlin 2.3.10, AGP 9.2.1, Compose BOM
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE                       # GPL-3.0 (voluntary choice per ADR-0004/0008)
├── README.md
└── roadmap.md                    # This file
```

### Branch and repository policy

- Upstreams are recorded by pinned commit in
  `docs/phase-0/upstream-baselines.md` for reference (which version of
  each app inspired which feature). No upstream code enters the tree.
- Short-lived implementation branches and reviewed, buildable merges.
- A green CI check is required to merge any PR.
- The product is GPL-3.0 by voluntary choice (ADR-0004 clarification).

## 4. Phased roadmap

### Phase 0 --- Align scope and establish the baselines (DONE)

**Commits:** `c01f073` (bootstrap), `42fd998` (Phase 0 exit + Phase 1
audit deliverables).\
**Status:** Bootstrap artefacts, Phase 0 sign-off, and Phase 1 source
audits all complete. See `docs/phase-0/sign-off.md`.

**Carried into v2.2 as historical reference:**

- Fossify baseline build (built successfully, ADR-0001 historical
  evidence; under ADR-0008 this is no longer the seed).
- Aves libre baseline attempt (blocker documented; under ADR-0008
  building Aves is optional, not blocking).
- Dependency license audit (the Fossify-derived dependencies are
  re-evaluated under ADR-0008; `org.fossify:commons` is to be removed).
- Android range confirmed: minSdk 26, target/compile 36.
- ADR-0001..0007 originally ACCEPTED; ADR-0001 and ADR-0005 now
  SUPERSEDED by ADR-0008 and ADR-0009 respectively.

### Phase 1 --- Source audit, integration design, and contract prototype (DONE; v2.2 re-framing)

**Commit:** `42fd998`.\
**Status:** Source audits, ADRs 0001..0007, module diagram, data-flow
doc, editor-contract spec, dependency license compatibility report ---
all complete.

**v2.2 re-framing:**

- The Phase 1 source audits (`docs/phase-1/source-audit-fossify.md`,
  `source-audit-aves.md`) are reinterpreted from "code inventory for
  porting" to "feature/UI/UX inventory for reimplementation
  inspiration." Their content is unchanged; their framing is updated.
- ADR-0001 SUPERSEDED by ADR-0008 (features-only).
- ADR-0005 SUPERSEDED by ADR-0009 (Compose).
- ADR-0010/0011/0012/0013 added.
- The dependency license compatibility report is re-evaluated under
  ADR-0008: `org.fossify:commons` and the Fossify-derived editor
  libraries (`androidphotofilters` fork, `jxl-coder-glide`) are
  candidates for replacement with our own Compose-native
  implementations.
- The editor-contract spec at `docs/architecture/editor-contract.md`
  remains valid (ADR-0007) --- the contract is between two toadGALLERY
  APKs we build ourselves.

### Phase 2 --- Product shell, visual foundation, privacy hardening, Compose toolchain, and CI (REWORK IN PROGRESS)

**Current state:** A View-based + `org.fossify:commons` shell exists at
`apps/gallery/` (commit `f57de04`, 0.1.0-alpha, 30 MB APK). Under
ADR-0008/0009 this shell is to be migrated incrementally to Compose.

**Purpose:** Establish the toadGALLERY identity, a Compose-native app
shell, the privacy invariants that constrain every later phase, and a
green CI workflow.

#### Tasks

- Add the Compose BOM + Material 3 + Activity Compose + Compose View
  interop to `apps/gallery/build.gradle.kts` (additive --- does not
  break the existing View-based shell).
- Add the Kotlin Compose compiler plugin to the root `build.gradle.kts`.
- Add `.github/workflows/ci.yml`: JDK 17 + Android SDK, `assembleDebug`,
  `lint`, `test` on every push and PR (ADR-0013).
- Replace `MainActivity.kt` and `PlaceholderFragment.kt` with a
  Compose-native `setContent { ... }` activity hosting a `NavHost` for
  the four top-level destinations (Photos, Albums, Search, Settings).
- Establish a consistent visual system inspired by Mi Gallery's clarity
  and information density without copying proprietary assets or
  branding. Material 3 + dynamic color.
- Define typography, spacing, color roles, light/dark themes, system
  bars, edge-to-edge layout, accessible touch targets --- as Compose
  design tokens in `shared/design-system/`.
- Define empty, loading, denied-permission, unsupported-file, and error
  states.
- Create `docs/architecture/compose-migration.md` listing every
  View-based screen, its target toolkit, and the phase scheduled for
  migration.
- **Privacy hardening:**
  - Confirm the core app manifest does not declare `INTERNET`. Add a
    build-time check that fails the build if `INTERNET` is added
    without an explicit allowlist entry.
  - Add a privacy test suite: launch the app under a network proxy,
    perform every core flow, assert zero outbound connections.
  - Add PIN/biometric gating for the Hidden vault (vault itself is
    populated in Phase 3).
  - Confirm no analytics, telemetry, or crash-reporting SDKs are
    pulled in transitively. With `org.fossify:commons` removed, the
    inherited `WRITE_GLOBAL_SETTINGS` and `USE_FINGERPRINT` manifest
    entries also go away.

#### Deliverables

- Installable toadGALLERY development APK (Compose-native shell).
- CI workflow green on push and PR.
- Design tokens and reusable Compose components in `shared/design-system/`.
- Navigation skeleton (`NavHost` + four destinations) and screen-state
  guidelines.
- `docs/architecture/compose-migration.md` (initial draft).
- Privacy test suite (green) and build-time `INTERNET` permission gate.

#### Exit criteria

- App launches and navigates reliably.
- Visual foundations are reusable and accessible.
- No large feature implementation is coupled to unfinished navigation.
- The Compose toolchain compiles, the Compose-native shell renders, and
  the migration tracker is committed.
- The CI workflow is green on main.
- The privacy test suite passes with zero outbound connections across
  all core flows.

### Phase 3 --- Core media access, browsing, file ops, recycle bin, hidden vault

**Purpose:** Make the Gallery useful and dependable for everyday file
operations, with safety nets (recycle bin, hidden vault) in place from
the start.

> **Progress (2026-10-31, revised after device testing of
> v0.4.0-alpha-r3):** landed on main --- Photos timeline + permission
> flow (#25), Albums tab (#29), Paging 3 incremental timeline (#28),
> aspect-aware staggered grid + video/GIF overlays (#33, #34), paging
> follow-ups: invalidation, append load-states, idempotent re-entry (#38),
> screen-state system (#13), system Photo Picker path (#27), file operations
> (#30), full-screen media viewer with in-app video playback (#50), recycle
> bin over MediaStore's system trash (#31), hidden vault: crypto core +
> sealed store + PIN setup/unlock + move-to-vault + restore + auto-lock
> (#32, ADR-0017), the deferred-from-#50 viewer follow-ups (pinch-zoom/pan,
> double-tap zoom, animated GIF decode), search tab v1: name query +
> photo/video filter into the viewer (#62), viewer operations: info sheet,
> share, rename, delete + decoder fallback for capability-exceeding video
> (#63, #65), and album-detail selection + file-ops parity with Photos
> (#64). **Phase 3 code-complete pending device validation**: after the
> reopening, wave 2 landed vault biometric unlock binding (#57,
> ADR-0019), decrypted vault thumbnails (#58) and favorites + sorting
> (#66), and the vault thumbnail lock-race guard (#69 --- a bitmap whose
> decode straddles a lock/background event can never republish into the
> cleared memory of the locked session) closed the one defect the
> real-device review surfaced in the #58 slice. The remaining exit work
> is real-device validation of the #62–#69 slices on the next pre-release
> (v0.4.1-alpha-r7; the per-slice pass/fail list is
> `docs/phase-3/device-validation-checklist.md`).
> CI runs compile + JVM tests with lint as a hard gate (ADR-0016, #52).

#### Tasks

- Implement Android MediaStore/content-URI access and modern
  photo/video permission flows. URI-first model per ADR-0006.
- Support Android's limited photo/video access where applicable; avoid
  relying on unrestricted filesystem paths.
- Build timeline/grid browsing, albums/folders, image viewer, video
  playback, favorites, sorting, multi-select, sharing --- all in
  Compose.
- Implement safe copy, move, delete, restore, and trash behavior with
  explicit confirmation.
- **Recycle bin:** app-private bin first, retained for a configurable
  window (default 30 days). Restore returns to original location where
  the MediaStore API permits. Empty-on-expiry runs as a bounded,
  cancellable WorkManager job. Document Android/provider limitations.
- **Hidden vault:** PIN/biometric-gated vault stored in app-private
  storage. Move-to-vault copies the media into vault storage and
  removes the original from the MediaStore index (where APIs permit).
  Vault storage is encrypted at rest with a key derived from the
  user's PIN/biometric.
- Handle content URIs, removable storage where supported, revoked
  access, missing files, and provider errors.
- Load thumbnails progressively; do not decode full-resolution
  originals for grid cells. (Initial image-loading lean: Coil,
  Compose-native; see ADR-0009 open questions.)
- Add cancellation and clear user feedback for long-running operations.

#### Deliverables

- Working end-to-end browsing and basic media operations in Compose.
- Recycle bin with retention and restore semantics.
- Hidden vault with PIN/biometric gating and at-rest encryption.
- Media-access and file-operation tests including failure modes.

#### Exit criteria

- Users can browse and open supported local media.
- Basic operations work on real devices and content providers.
- Permission changes and missing files do not crash the app or corrupt
  data.
- Deleted items are recoverable from the recycle bin within the
  retention window; vault items are inaccessible without PIN/biometric.

### Phase 4 --- Aves-grade metadata layer

**Purpose:** Give every media item an Aves-libre-grade Details view and
the ability to edit metadata in-place, with no network dependency. No
Aves code is ported (ADR-0008).

> **Planning input (2026-11):** the Phase 4 compatibility audit against
> the Ente Photos on-device ML implementation concluded Phase 4 is
> directionally suitable but needs contract changes (durable media
> identity/revision, a byte-oriented read boundary, incremental change
> detection, a canonical decode contract, an explicit persistence
> boundary) before any Ente-derived gallery-AI work can build on it. The
> full audit, the Phase 4 contract requirements and the standalone
> Ente-derived AI implementation plan live in
> `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`.

> **Phase map (authoritative, 2026-11-01):** the audit document above
> uses "Phase 5" as a working label for the Ente-derived gallery AI
> (People, semantic search, local indexing). This roadmap's numbering
> stays authoritative and the mapping is now fixed so no work lands
> under the wrong number: the Ente-derived AI **engine** (Rust ML port,
> model pack, ML DB/vector store, index coordinator) is the engine
> workstream *inside* this roadmap's **Phase 5** (its product surface is
> Phase 5's People/Places/Things auto-albums and Magic Search), gated on
> the Phase 4 contract items below; **Phase 8** remains the editor-AI
> scope (ADR-0010). When the audit doc says "Phase 5", read "the Phase 5
> AI-engine workstream".

#### Status — r10 round (versionCode 14): the contract foundation has landed

The audit's prerequisite contracts are implemented and JVM-tested
(174 new tests; no UI consumes them yet --- deliberately, as the
"contracts before Ente integration" gate):

- `data/media/identity` --- `MediaIdentity` (volume-aware durable local
  media key; path/display name never identity) and the type-level vault
  boundary `MediaEligibility` → `EligiblePublicMedia`.
- `data/media/revision` + `data/media/dates` --- `MediaRevision.diff`
  (UNCHANGED / METADATA_CHANGED / CONTENT_CHANGED invalidation classes)
  and the timezone-aware capture-date resolver with source precedence.
- `metadata/` --- `MetadataAvailability` (PRESENT/ABSENT/UNSUPPORTED/
  UNREADABLE), `MediaMetadataSnapshot`, `ExifMath` + `ParsedExif` over
  a thin `androidx.exifinterface` reader, video container parses, and
  `MetadataFormatMatrix` (the parser capability matrix, in data).
- `data/catalogue` --- the Room media catalogue (`MediaCatalogue` +
  Room 2.8.5/KSP, non-destructive migrations, per-volume scan
  checkpoints).
- `data/media/changes` --- `MediaChangeFeed` (per-volume generation on
  API 30+, ContentObserver fallback below) + `CatalogueReconciler`
  (tombstoning, generation-bump re-checks; in-app metadata writes alone
  never trigger re-indexing).
- `data/media/access` + `media/decode` --- `MediaReadHandle` byte
  boundary (openable only for eligible public media, no plaintext temp
  files) and `CanonicalDecodePlanner` (power-of-two sampling, upright
  swaps, deterministic memory-budget refusal).

#### Status — r12 round (versionCode 16): the scan driver is live

The pipeline now runs from app start: `MediaScanDriver` (pure,
exhaustively JVM-tested decision core) plans each pass from the
per-volume checkpoint vs the live generation token (API 30+ skip when
equal; 26-29 always rescan), `AndroidMediaScanRowSource` walks each
mounted volume (trashed rows excluded on R+), the r10 reconciler diffs
and the driver applies upserts/tombstones/checkpoints in self-healing
order, and the ContentObserver feeds follow-up passes (coalesced). The
capture-date resolver is adopted into the pipeline: every added/changed
row gets its timezone-aware capture date resolved once from EXIF
evidence (through the MediaReadHandle boundary, eligible public media
only) and persisted onto the record (Room v1 -> v2, non-destructive
migration, v2 schema committed). Still no UI consumes the catalogue.

#### Status — r13 round (versionCode 17): the timeline reads capture dates

The promised adoption landed: the Photos timeline orders and groups by
the EFFECTIVE capture date (resolved EXIF instant when the indexer
produced one, `date_added` otherwise). Once the first scan populates
the catalogue, the timeline's windowed reads are served by the
catalogue itself (`COALESCE(resolvedCaptureDateMillis,
dateAddedMillis)` + `localMediaKey` tiebreak; Room v3 adds the
display/shape ride-alongs `displayName`/`width`/`height`/`durationMs`/
`isFavorite`), invalidated by the catalogue's own change flow; while
the catalogue is empty the timeline keeps the legacy MediaStore
windows, so first paint never regresses. Per maintainer directive
(2026-11-01) rounds land on main WITHOUT release tags until Phase 5
completes; device builds come from CI.

#### Status — r14 round (versionCode 18): the Details view + location consent

Two user-facing Phase 4 items landed: the **metadata Details view**
(read path --- dimensions, size, MIME, capture date with explicit
timezone semantics, camera, exposure triangle, GPS decimal + DMS, and
the full EXIF block in a collapsible tree; every availability state
renders honestly, nothing is faked) and the **ACCESS_MEDIA_LOCATION
permission flow** (a pure JVM-tested state machine, a Settings
"Location data" section, and Details-view GPS rows display-gated on
the consent state via `MediaLocationDisplayRules.Default`).

**Named follow-ups (the honest remainder of Phase 4):** the IPTC/XMP
read+write wrappers (they build on r15's write spine), the offline
tile-pack sideload implementation for the map, and a writable path
for the keywords/rating EXIF tags. Two of the five r14-named
follow-ups are CLOSED by r19: the catalogue bucket column (and the
folder chip it unblocked) and the `media_type IN (1, 2)` search/
favorites query bug (2 = AUDIO, 3 = VIDEO — the pairs now pin
images+videos, cross-checked by JVM tests). The r19 audit round adds
new honest follow-ups: the scan NULL-provenance healing pass (per-row
isolation landed; rows whose provenance stayed null after a failed
index still never re-offer), the combined single-read EXIF extraction
pass (capture-date + GPS currently read each row twice), volume
hot-plug (observers exist only for volumes mounted at process start),
the vault batch store (one index rewrite per move batch), the docs
`screen-states.md` flips for the new Error call sites, and device
verification of the pre-Q reduced-projection fallback on an API
26–28 device.

#### Status — r15–r18 rounds (versionCode 22): the four still-open items landed

The four parallel rounds of 2026-11-02 (built as four sibling branches,
CI-validated, merged in sequence #86–#89) land the rest of Phase 4:

- **r15 — editable metadata fields (EXIF write-back)**: a pure
  edit-capability gate, the rw write flow through
  `androidx.exifinterface` (saveAttributes, the pinned 1.4.1's verified
  write set), the API 30+ `MediaStore.createWriteRequest` escalation
  state machine, and Details-view edit affordances (date/time +
  offset, GPS, copyright, comment). Platform-verified honesty: the
  pinned library physically cannot write Rating/XPKeywords — those
  render "later round" states instead of fake successes.
- **r16 — on-device map view**: a bundled Natural Earth 110m world
  outline (public domain), a dependency-free GeoJSON parser +
  equirectangular projection + grid clustering, a consent-gated Map
  tab, and GPS persistence (Room v3→v4: latitude/longitude +
  provenance, a `LocationIndexer` as a second `ScanIndexHook`,
  composite fan-out wiring). Zero network tiles ever; the tile-pack
  sideload is a documented later-round contract
  (`docs/phase-4/on-device-map.md`).
- **r17 — collections + smart-filter chips**: a pure filter engine
  projected three ways (in-memory, MediaStore selection, catalogue
  WHERE) driving the Photos chips row (All/Photos/Videos/GIFs/
  Favorites) on BOTH data paths, plus the Albums Collections section.
  The folder chip stays engine-only (no bucket column in the catalogue
  — the honest subset), date-range likewise.
- **r18 — format-support audit**: a magic-byte sniffer + capability
  catalogue (48 JVM tests, the signature-level sample corpus) and
  `docs/phase-4/format-support-audit.md` — the cited format matrix
  (every platform claim carries a repo cite or an official-docs URL).

#### Status — r20 round (versionCode 24): the scan-debt round

The maintainer directive after build 23 (2026-11-02): **no new
device builds until Phase 5 completes** — rounds land on main and
ride CI artifacts only. r20 closes four of the six r19-named
follow-ups, the data-layer debt the Phase 5 index coordinator
builds on: the **combined single-read EXIF pass** (one open + one
parse per row serving both the capture-date and GPS dimensions;
the r12/r16 standalone indexers removed, their evidence types,
pure write mappings, eligibility gate and consent gate absorbed
unchanged), the **NULL-provenance healing pass** (Room v5 → v6
`provenanceIndexedAtMillis` stamp — the LAST of the pass's three
writes; a bounded per-start pass re-offers unstamped rows until
they succeed, so one transient error can no longer strand a row's
provenance forever), **volume hot-plug** (an
`ACTION_MEDIA_MOUNTED` shell drives idempotent observer
registration + a coalesced scan for volumes mounted after process
start), and the **vault batch store** (one index rewrite per move
batch with per-item failure isolation, two-phase move flow with
positional outcome pairing). The fifth follow-up — the
`screen-states.md` flips for the r19 error/permission call sites —
is done in the same round. The sixth (device verification of the
pre-Q reduced-projection fallback on an API 26–28 device) stays
open, now folded into the eventual Phase 5 completion build's
checklist.

#### Status — r21 round (versionCode 25): the Ente engine pin + model-provisioning core

The Phase 5 AI engine workstream starts (audit workstream 2, first slice;
`docs/phase-5/ente-pin.md`): the Ente Rust ML engine is **vendored at pinned
commit `423b2d80d9ec89d45f5680cb1e1d777bac225f41`** as the new root-level
`engine/` workspace — `crates/ente-ml` (YOLO face detection + NMS, alignment,
MobileFaceNet embeddings, MobileCLIP, the model catalog), `crates/ente-assets`
(download/verify/staged-install primitives) and `crates/ente-image`
(byte-oriented decode incl. HEIC/AVIF/JXL) — byte-identical to upstream
except the recreated workspace root, the crate-directory renames and
crate-level `license` fields, all diffable and recorded in
`engine/NOTICE.md`; a new cargo check + fmt + clippy CI job guards the
workspace. Licensing is settled explicitly: Ente is **AGPL-3.0**, the ledger
(`licensing-and-attribution.md` §2/§5) records the vendoring and its
one-way-compatibility consequence for distributed builds. On the Kotlin side
the model-delivery core lands per the maintainer's delivery decision
(**ADR-0020**: models are downloaded on demand Ente-style, verified against
the pin, never shipped in the APK by default; the offline pack remains a
release-time option): `ml/engine/ModelManifest.kt` pins all 11 assets
(5 core + 6 pet, deliberately unprovisioned) with sizes + SHA-256
transcribed from the pinned `assets.rs` (all eleven name-for-name,
test-pinned); `ModelVerifier` streams size-then-hash
verification; `ModelProvisioner` does verify-then-stage-then-commit with a
receipt written last, per-capability state and invalidation — pinned by
hermetic JVM tests (transport faked; the HTTP downloader + INTERNET
permission + JNI bridge spike are r22, exactly as ADR-0020 orders). No
device build, per the Phase-5-completion directive.

#### Status — r22 round (versionCode 26): the Android engine bridge

The two r21-named r22 deliverables of ADR-0020 land, closing that round's
deferral list (`docs/phase-5/ente-pin.md`). **The HTTP model downloader**:
`ml/engine/HttpModelDownloader.kt` — plain `HttpURLConnection`, zero new
dependencies — pinned to the single allowlisted host `models.ente.com`
(verified against the live host: all five core assets answer HTTP 200
directly, no redirects, exact pinned content-lengths); fetch URLs are built
from parts over a structurally guarded file name, redirects fail closed, and
a content-length mismatch aborts before the first byte. The app's first
network permission lands with it — the manifest declares INTERNET exactly
once for the model fetch, bounded by the merged-manifest gate + allowlist
protocol and the extended privacy suites (`PrivacyPolicyTest` on the JVM:
permission-set pin, host-literal scan, URL surface for all eleven entries;
`PrivacyEgressTest` on device: the grant present, non-allowlisted targets
refused without a connection). **The JNI bridge**: the net-new AGPL crate
`engine/crates/toad-ml-jni` (cdylib `libtoad_ml_jni.so`, four external
functions behind the Kotlin `MlEngine`/`MlEngineJni` facade; a missing
library is a graceful `LibraryMissing` state, never a crash), built for
Android ABIs by `engine/scripts/build-android.sh` (cargo-ndk; upstream's
16 KB page-size link args ride via `engine/.cargo/config.toml`) together
with ONNX Runtime's official `libonnxruntime.so` (the sha256-pinned 1.31.0
Android AAR from Maven Central — the exact version the ort-sys rc.15
api-31 bindings expect; load-dynamic makes it a fetched supply, not a
cargo artifact).
Packaging is opt-in: Gradle `-PwithEngine` registers the `buildRustEngine`
task and a build-dir jniLibs srcDir (nothing in the source tree; the
default build stays byte-for-byte r21), and the new CI `engine-android`
job gates the ABI build + an engine-enabled assembleDebug, verifying both
native libraries land in the APK. The Settings "AI engine (r22 dogfood)"
section dogfoods the whole path on a device: download the FACE assets
(live per-asset presence updates; byte-level progress deliberately
deferred), delete, and a one-JPEG face-detection self-test. No device
build, per the Phase-5-completion directive.

#### Status — r23 round (versionCode 27): the indexing round

The Phase 5 AI engine workstream's third slice: the photos-side
persistence + clustering stack is **vendored at the same pin** as r21
(`docs/phase-5/ente-pin.md`) — `engine/crates/ente-vecdb` (the whole
upstream `rust/crates/vecdb`, byte-identical: the append-only HNSW vector
store) and `engine/crates/ente-photos-ml` (the upstream `photos` crate's
`db`, `ml_db`, `ml_store`, `face_clustering` modules, byte-identical
except a two-line `use` rewrite routing `ente_core::crypto::random_bytes`
onto an engine-owned seam instead of vendoring all of `ente-core` for one
function) — closing the r21 deferral exactly as its first option
described (`db` vendored alongside the trio, no DB seam invented; both
adaptations recorded in `engine/NOTICE.md`, provenance in the licensing
ledger §5, AGPL-3.0 at the pin). On top of the vendored crates the JNI
bridge extends its r22 surface with `nativeDetectFaceEmbeddingsJpeg`
(`N × (5 + 192)` flat floats per JPEG — boxes + 192-d MobileFaceNet
embeddings, stride 197) and a face-index store over the vendored
`MlStore` (`nativeStoreOpen`/`nativeStorePutFace`/`nativeStoreFaceCount`/
`nativeStoreCluster`/`nativeStoreClose`; sqlite via bundled rusqlite at
`<dir>/ml-index.sqlite`, so embeddings persist across engine sessions),
Kotlin `ml/engine/MlEngine.kt` maps the flat floats to `FaceEmbedding`
rows and `ml/index/IndexCoordinator.kt` orchestrates the round trip
(detect → putFace → cluster at upstream's `RECOMMENDED_DISTANCE_THRESHOLD`,
0.24) over an injectable `FaceIndexEngine` seam, and the Settings
"index + cluster (r23 dogfood)" slice drives it end-to-end. CI's engine
job runs the two new crates' test suites explicitly (`cargo test -p
ente-vecdb -p ente-photos-ml`; the check/fmt/clippy workspace gates cover
them automatically); `engine/scripts/build-android.sh` needs no change —
its `cargo ndk -p toad-ml-jni` build pulls the new crates as workspace
dependencies, so `-PwithEngine` packaging is exactly the r22 one. No new
ADR: the crypto seam is recorded in `engine/NOTICE.md` and the pin doc,
not duplicated into a decision record. No device build, per the
Phase-5-completion directive.

#### Status — r24 round (versionCode 28): the background queue round

Workstream 5 of the Phase 5 plan
(`docs/phase-5/background-queue.md`): the r23 12-photo dogfood slice is
expanded into the full-library, **WorkManager-backed background indexing
queue** — bounded batches, retry/resume across process death, and the
`MediaRevision.diff()` invalidation wiring the Phase 4 contract promised.
The JNI bridge's store surface gains the queue's maintenance pair
(`nativeStoreIndexedFaceFileIds` via upstream's own
`face_indexed_file_ids`, and `nativeStoreDeleteFacesForFiles` via
upstream's own chunked `delete_face_index_for_files` — no new SQL), the
Kotlin side gains the pure queue core (`FaceIndexQueuePlanner` mapping
RevisionDelta to skip / re-index-delete-first / orphan-cleanup,
`FaceIndexQueueProcessor` with whole-photo resumability and a
once-per-drain clustering pass, and the atomic `FaceIndexStateFile`
bookkeeping inside the face-index directory), and `FaceIndexWorker`
(CoroutineWorker, one unique chain, self-re-enqueue while the plan
outlives its batch, honest failure shapes) binds the real seams with
candidates from the Phase 4 Room catalogue (external-primary volume only
— the documented multi-volume boundary). The Settings card is replaced
in kind with the r24 queue card: start / cancel / reset, live progress,
terminal outcomes, and the clustering-threshold tuning control r23
flagged for this round. The round's one new dependency is
**androidx.work 2.10.1** (Apache-2.0, license-screened): its manifest
injections are trimmed by two `tools:node="remove"` markers
(ACCESS_NETWORK_STATE and FOREGROUND_SERVICE declined; WAKE_LOCK and
RECEIVE_BOOT_COMPLETED accepted and documented in
`docs/phase-2/privacy-gate.md`), the JVM privacy suite pins the removal
set exactly, and the face-index directory is excluded from cloud backup
and device transfer (face-derived data never leaves the device). 885 JVM
tests + 526 vendored Rust tests + 12 JNI tests; versionCode 28, no
device build per the Phase-5-completion directive.

#### Status — r25 round (versionCode 29): the People UI round

Workstream 6 of the Phase 5 plan
(`docs/phase-5/people-ui.md`): the r23/r24 face index gains its product
surface — the **People auto-album**, opt-in per ADR-0010 and **off by
default**. The JNI store extends from twelve to sixteen externals
(`nativeStoreFaceClusters` off the LIVE `face_clusters` table — not the
stale `cluster_summary` cache, so manual edits reflect immediately —
with per-cluster cover file ids; `nativeStoreClusterFileIds`;
`nativeStoreMergeFaceClusters` over upstream `force_update_cluster_ids`
with the self-merge `IllegalArgumentException` checked on both sides of
the bridge; `nativeStoreRemoveFacesFromCluster`, the split primitive,
over upstream `remove_face_id_to_cluster_id`; zero new SQL authored).
The new `ml/people` domain carries the `PeopleEngineSeam` (fakes in
tests, `PeopleEngineAdapter` in production), the atomic
`people-state.json` names/hidden store inside the backup-excluded
face-index directory (corrupt-recovers to empty, prunes stale cluster
ids), the pure six-state `PeopleContentPolicy` (Error dominates;
consent before capability; all-hidden is Ready(empty), distinct from
NotIndexed), the no-cache `PeopleRepository` and the chunked
999-limit `PeopleMediaQuery`. The Compose surfaces: the `PeopleScreen`
grid, `PersonDetailScreen` (rename / merge-with / remove-from-person /
hide over a multi-select photo grid, viewer hand-offs the Album-detail
way), the Albums-tab People section (honest off / engine-missing /
not-indexed cards; the person row + See all when ready) and the Settings
`peopleAlbumEnabled` consent row. Honest limitation, pinned by a Rust
test: manual cluster edits persist only until the queue's next
recluster pass (an edit-preserving overlay is future work). 927 JVM
tests (+42) + 526 vendored Rust tests + 20 JNI tests (8 new);
versionCode 29, no device build per the Phase-5-completion directive.

#### Status — r26 round (versionCode 30): the Magic Search round

Workstream 7 of the Phase 5 plan
(`docs/phase-5/magic-search.md`): offline semantic ("Magic") search
over an on-device CLIP index — the Search tab's new **Content** mode,
opt-in per ADR-0010 and **off by default**. The JNI bridge extends
from sixteen to twenty-five externals (`nativeInitSemantic` — the CLIP
half of an engine session as a third open/closed state — the CLIP
image/text encoders, and a CLIP store surface over the SAME
`ml-index.sqlite` the faces live in: put/delete-for-files/indexed-
file-ids/count, top-K vecdb search preserving the store's own order,
and the once-per-drain `fillClipIndex` reconciliation pass with its
own report). The semantic background queue is the r24 face queue's
SIBLING: the same three truths, the same `MediaRevision.diff`
mapping (delete-first kept even though `putClip` upserts — one
diff→action table across both queues), ONE whole-photo embedding per
photo, its own unique WorkManager chain with self-re-enqueue and
honest FACE-then-SEMANTIC session gates (the r26 session is
face-based — the round's documented limitation), and the atomic
`clip-index-state.json` bookkeeping inside the backup-excluded
face-index directory. The `ml/search` domain carries the
`MagicSearchEngineSeam` (+ the adapter), the pure six-state
`MagicSearchContentPolicy` (Error dominates; consent before
capability; ModelsNotProvisioned covers either pack; NotIndexed
distinct from Ready), the 2–200-character query gate (over-long
refused, never truncated), the order-preserving/dedupe/limit ranking
(no distance surfaced in v1) and the no-cache repository with typed
honest outcomes. The UI: the "Search by" mode row (Name untouched vs
Content), the debounced sibling `MagicSearchViewModel` whose policy
gates render as honest cards (consent/engine/models/not-indexed, each
pointing at its own recovery surface), results on the SAME shared
grid in the vecdb's own relevance order with the same viewer
hand-off. Settings gains the `magicSearchEnabled` consent row, the
SEMANTIC model download card (~213 MB, manifest-derived) and the
semantic queue card (start/cancel/reset — the reset's honest scope:
the queue's own bookkeeping; the stored index rides the r24 Reset
face index). Queries never touch the network — the only egress is
the ADR-0020 model download; vault media can never become candidates
(three-gate discipline). 982 JVM tests (+55) + 32 JNI tests (12
new); versionCode 30, no device build per the Phase-5-completion
directive.

#### Status — r26 round (versionCode 31): the People follow-ups round

Workstream 6 closeout — the two r25 deferrals
(`docs/phase-5/people-ui.md`) shipped together. **Edit-preserving
reclustering**: every clustering pass re-rolls ALL cluster ids
(upstream `new_cluster_id` is random per pass); the r26 overlay keys
user edits to the one identity a pass cannot re-roll — PHOTO
MEMBERSHIP (face rows key on MediaStore file ids). `people-state.json`
grows three additive books inside schema version 1 (membership
fingerprints captured at edit time, recorded `PeopleMerge` intents,
accumulated remove-from-person corrections; an r25 file loads with
them empty), the pure `PeopleReclusterRemap` planner maps fresh
post-recluster ids onto the preserved state by deterministic overlap
(most shared files; ties by bigger cluster then smaller id; name
collisions by score then old id), re-folds merges the pass split
(majority-descendant rule, one engine merge per folded stray),
re-applies removals the pass re-derived (only the files that came
back), and `PeopleRepository` executes the plan on every read AND
every edit (mid-window edits re-keyed; a declined mutation aborts the
save — self-healing, never corrupting). **The unhide UI**: the People
screen's hidden section (the pure `PeopleContentPolicy.hiddenPersons`
mapping) with a per-person Unhide action — a pure hidden-set flip, no
queue reset, names intact; the r25 reset-path copy re-worded in place.
100% Kotlin-side over the existing 16-external JNI surface — zero
Rust changes, the vendored crates byte-identical to the r23 pin. 963
JVM tests (+36); versionCode 30, no device build per the
Phase-5-completion directive.
#### Status — r27 round (versionCode 32): the MI organisational UX round

Workstream 6 / the roadmap Phase 5 organisational-UX scope, shipped as
five product surfaces over the existing catalogue (zero new permissions,
zero engine changes, zero reverse geocoding — every feature a pure
JVM-tested core plus a thin Android seam, ADR-0016):

- **Places** — the pure `PlacesRegionGrid` 12x6 coarse bucketing over the
  Room GPS coordinates (band edges land in the pole-side band; the exact
  antimeridian is ONE band from both directions), `PlacesRegionLabels`
  coordinate-band labels, the pure `PlacesContentPolicy` r14 consent
  gate, the `PlaceMediaHandoff` one-shot hand-off, the
  `PlacesScreen`/`PlaceDetailScreen` sub-pages and the Albums-tab
  Places section;
- **Moments** — the `GroupBy.WEEK` grain: the pure `MomentGrouper`'s
  ISO-week output as a thin projection in `buildTimelineRows` (the
  DAY/MONTH streaming untouched, DAY equivalence pinned), counted
  headers, the FlowRow group-by chips;
- **Stories** — the pure `StorySets` 36h-gap clusters on the Photos tab,
  playback through the existing viewer with the slideshow armed;
- **The collage creator** — the pure `CollageLayouts` (2..9 photos,
  exact tiling, `slotPixels`/`centerCropSource` shared by preview and
  exporter) + the Compose preview and the Q+ MediaStore exporter on the
  file-ops naming discipline;
- **The slideshow** — the pure `SlideshowTiming`/`ViewerCrossfade` cores,
  the shell-scoped armed launch, the in-session interval menu.

84 new JVM tests; `versionCode 32`; the Phase 5 release freeze stands
(no tags, no Releases — the r28 exit gate is the only lift).

#### Status — r28 round (versionCode 32): the Workstream 8 hardening round + the Phase 5 exit gate

The last Phase 5 workstream (`docs/phase-5/hardening.md`): tests and
records are the product. **The egress proof** — `EgressSolePathTest`
pins the negative space around the ADR-0020 grant across every surface
that could open a network path (the only quoted URL literal in
production Kotlin is the About screen's `ACTION_VIEW` repository link;
`java.net` confined to the one transport; no client libraries, no raw
sockets, no cleartext/network-security-config escape hatch; the
INTERNET allowlist pinned to exactly the one entry; the Gradle
merged-manifest gates pinned with their anchors), and
`HttpModelDownloaderRedirectTest` drives the full 301–308 fail-closed
matrix with never-follow single-request proofs. **The three-gate vault
exclusion** — one focused suite per Phase 4 audit gate: enqueue
(`FaceIndexQueueVaultExclusionTest`: the vault-move model end-to-end
over the real reference catalogue, including the orphan-delete purge of
a previously-indexed photo's face rows before any inference),
pre-byte-read (`VaultExclusionReadGateTest`: the eligibility type gate,
the permission-gate refusal, the gate-before-handle ordering, the
refuse-whole cap), pre-persistence
(`PeopleVaultExclusionPersistenceTest`: the all-vault cluster, the
stale-name prune, hide-as-display-gate, plus the face-index
directory's backup/transfer exclusion pins). **The 10k/50k benchmarks**
— `LibraryScaleBenchmarkTest` over seeded synthetic libraries
(timeline + folder windows, the Paging 3 walk, the reconciler diff with
realistic churn, the filter projection) with generous-but-meaningful
budgets; measured CI numbers recorded in the hardening doc. **The
low-memory/ABI matrix** — documented (§4) and pinned
(`EnginePackagingPolicyTest`: the withEngine/withBundledModels gates,
the default-ABI discipline, the pinned NDK + sha256-verified ORT AAR,
the 16 KB page-size link flags for all three Android targets, the SDK
envelope). **The ADR-0020 offline model pack** — the retained second
half of the delivery decision: `BundledModelAssets` +
`BundledFirstModelDownloader` (bundled-first, the shared name guard,
the provisioner's unchanged verify-then-commit discipline), the
`-PwithBundledModels` build-dir assets gate, and the branch-scoped
additive `modelpack-apk.yml` workflow (pinned FACE pack staged +
verified at build time, gated APK, 14-day artifact; no tags, no
Releases). 53 new JVM tests (927 → **980**); versionCode 32, no device
build needed (tests + records; the modelpack artifact is producible on
a `tg-modelpack-*` branch whenever wanted).

**Phase 5 exit gate:** all Workstream 8 items green and merged —
**Phase 5 release freeze lifted; the next release may proceed.** The
honest state: **code-complete, on-device dogfood pending** (the
maintainer checklist items in `tasks.md` remain open, explicitly NOT
claimed by the lift), and the parallel `r26` (People follow-ups),
`r27` (organisational UX) and Magic Search rounds land under their own
records — the lift claims Workstream 8's scope only. versionName stays
`0.4.2-alpha`; the next release tags the whole line (codes 27–32).

#### Status — r19 round (versionCode 23): the code-improvement round

The maintainer-directed whole-codebase audit (2026-11-02): three
read-only audits (data, UI, metadata/vault) consolidated into seven
implementation slices on six sibling branches, merged as one round.
The headlines: the r17-flagged `media_type IN (1, 2)` favorites/search
bug is FIXED (audio was leaking in while every favorited video
dropped out); the vault's unlock-vs-move index race is closed by a
store-level mutex + a ten-minute sweep grace window (a parked-stream
JVM test pins the interleave with a blob aged past grace so only the
lock can save it); the catalogue persists the folder dimension (Room
v4 → v5, non-destructive; the v5 identityHash re-derived from the
Room 2.8.5 sources with the documented method, v1–v4 reproduced as
control) and the Photos **folder chip is surfaced** on both paths via
a picker sheet — the r17 "engine-only" note is retired; the Albums
screens gain honest Error states (the shared ErrorState component's
first live call sites, closing the app's one crash-class exception
path); Search gains the Photos permission pattern; video playback is
lifecycle-aware (ON_STOP pauses); every grid honors the columns
setting; the Details view rides the shared catalogue instance with a
keyed `canonicalUri` lookup; the reconciler delegates to
`MediaRevision.diff` (one rule, one place — a token flip alone is
UNCHANGED); the GPS editor refuses NaN/hex input; the sniffer decides
videos by major brand; and the format matrix agrees with the r15
write spine again. Per maintainer directive (2026-11-02) releases
resume: build 23 ships r13–r19.

#### Tasks

- Build a metadata service with a clear boundary between metadata
  extraction and UI. All parsing off the main thread; results cached
  only where justified.
- Use `androidx.exifinterface` (Apache-2.0, AndroidX) for EXIF
  read+write. No Aves fork port.
- Write our own IPTC and XMP read+write wrappers around the same
  primitives (net-new code).
- Present available metadata in a structured Details view: dimensions,
  file size, MIME type, capture date (with timezone semantics stated
  explicitly), camera make/model/lens, full exposure triangle, GPS
  coordinates with an on-device map pin, and the complete
  EXIF/IPTC/XMP block in a collapsible tree.
- **Editable metadata fields:** date/time, GPS, copyright,
  keywords/tags, rating, colour label, comment. Writes go back to the
  file via `androidx.exifinterface` (and our IPTC/XMP wrappers), not
  to a sidecar database.
- Distinguish unavailable metadata from metadata that is genuinely
  absent.
- **On-device map view:** no online tile fetch. The map renders with a
  bundled minimal renderer; users can sideload an offline tile pack
  for richer cartography. Document this clearly.
- Reimplement Aves-style collections and smart-filter chips in Compose
  (design reference only --- no Dart port).
- Audit support for HEIC/HEIF, AVIF, RAW, animated formats, and
  unusual video formats; state actual device/library limitations.

#### Deliverables

- Metadata Details view with read and edit (Compose).
- `androidx.exifinterface` integration in `shared/metadata/`.
- IPTC/XMP read+write wrappers (net-new Kotlin).
- On-device map view with offline-tile sideload documentation.
- Collections and smart-filter chips.
- Format-support matrix and tested sample corpus.

#### Exit criteria

- Metadata extraction does not block scrolling or viewer interaction.
- Common metadata fields are editable and written back to the file.
- Unsupported/corrupt files fail gracefully.
- Zero outbound network connections during any metadata flow.

### Phase 5 --- MI-style organisational UX

**Purpose:** Make large libraries easy to explore with Mi Gallery-grade
organisational affordances, all on-device.

**Status (r39): the ente-port code side is COMPLETE.** All the code
items this roadmap and `tasks.md` name for Phase 5 are merged: the
Ente-derived engine workstream (r21–r28: vendored crates at the
`423b2d8` pin, the JNI bridge, the model provisioner with
`models.ente.com` as the sole allowlisted host, the face
indexing/clustering pipeline, Magic Search, the offline model-pack
channel, the hardening round) plus the r39 ente-port exit round (the
semantic-only engine session and the Things dynamic collections ---
`docs/phase-5/ente-exit.md`). What remains open is HUMAN-ONLY by the
standing directive: the on-device dogfood validation (tasks.md §3) and
the device-checklist §13–§18 rows.

#### Tasks

- **Moments timeline:** group the main Photos view by date with a
  per-day header and a horizontal "Moments" strip of recent highlights.
- **Auto-albums (opt-in):** People, Places, Things. Each is generated
  by an on-device ML index that runs only after the user explicitly
  turns it on (ADR-0010). The engine workstream behind these is the
  Ente-derived AI port defined in
  `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md` (see the
  phase-map note under Phase 4); it builds on the Phase 4 contracts
  landed in the r10 round.
  - **People:** on-device face clustering. No face data ever leaves the
    device. The user can name clusters, merge/split them, and exclude
    specific photos from a cluster.
  - **Places:** derived from GPS metadata already extracted in Phase 4.
    No reverse geocoding network call; labels use coarse on-device
    region buckets derived from coordinates.
  - **Things:** on-device object/scene tagging. Model shipped in APK
    (superseded for the Ente engine workstream by ADR-0020: models are
    fetched on demand at first enablement; the offline pack is a
    release-time alternative).
- **Smart search:** compose filters for filename, date range, media
  type, album/folder, favorites, metadata fields, tags, colour labels,
  and people clusters. Add an on-device semantic search bar that
  matches by content (e.g. "beach at sunset") using the Things index.
- **Stories:** short auto-generated highlight reels per day/event,
  playable as a slideshow with transitions. Generation is on-device
  and runs only on demand.
- **Collage maker:** pick 2--9 photos, choose a layout, export as a
  single image. No templates imported from proprietary sources.
- **Slideshow with transitions:** standard slideshow with a small set
  of transitions (fade, slide, zoom). No proprietary transition code.
- Add tests for missing dates, duplicate names, unusual characters, and
  large result sets.
- Define timezone and date semantics for capture date versus file date.
- Avoid rescanning the entire library on every search or app launch.

#### Deliverables

- Moments timeline, Auto-albums (opt-in), Smart search, Stories,
  Collage maker, Slideshow --- all in Compose.
- Documented indexing and cache invalidation behavior.
- Search/query tests and performance measurements.
- On-device ML model packaging for People and Things, with model
  attribution and license (ADR-0010; delivery per ADR-0020 — on-demand
  fetch by default, offline pack as release-time alternative).

#### Exit criteria

- Filters combine predictably and can be cleared easily.
- Search remains responsive on representative large libraries (10k and
  50k items).
- Indexes stay consistent when media changes or permissions are
  revoked.
- Auto-albums and semantic search run with zero outbound network
  connections.

### Phase 6 --- toadGALLERY Edit v1: core adjustments + non-destructive pipeline

**Status (r29):** The **Issue #14 gate is landed** --- the versioned
Gallery <-> Edit contract is code (`shared/editor-contract/`, pure JVM),
the **toadGALLERY Edit prototype APK** exists (`apps/edit/`,
`io.github.dukeoftoad.gallery.edit`, versionCode 1: contract launch, a
real brightness/contrast LUT + rotation session with undo/redo, the
non-destructive MediaStore-INSERT save-copy export with the EXIF
metadata policy, the three terminal states, zero network with its own
stricter INTERNET gate), and the gallery resolves/launches it from the
viewer's Edit op with the third-party fallback and the
MediaStore-observation refresh (`docs/phase-6/contract-gate.md`). The
adjustment-suite expansion below (the remaining parameters, the
perspective tool, the JSON sidecar recipe layer) builds on this round's
session/export pipeline.

**Status (r30):** The **Edit v1 core is built** on the r29 gate
(`tg-p6-edit-v1-core`, stacked): the ADR-0011 `EditRecipe` + codec +
atomic sidecar store (save-on-commit, restore-on-relaunch,
clear-on-export), the geometry toolset (crop rect + aspect presets,
flips, straighten on an expanded canvas, quad perspective), the full
adjustment suite (composed LUT, pixel stages, 8-band HSL, vignette,
unsharp), `EditPipeline` + `EditGeometryPlan` (preview/export agree by
construction), and the honest export control (JPEG/PNG/WebP — NO HEIC:
the platform Bitmap has no HEIC encoder). The remaining Phase 6 exit
items: the per-export metadata-policy choice UI, the on-device
large-file/low-storage/process-death matrix, and the physical
round-trip hash-compare (checklist §19).

**Status (r31): PHASE 6 IS CODE-SIDE COMPLETE.** The two code-side exit
items are built (`tg-p6-edit-v1-exit`, stacked on the r30 branch): the
**per-export metadata-policy choice** — the launch request's policy is
the privacy FLOOR (contract §4.4 "respected exactly": the export sheet
may strip more for one copy, never less; pure `ExportPolicyChoice`,
floor-blocked sheet chips, the honesty line tracking the pending
choice, the recipe/sidecar independence pinned by test) — and the
**export preflight** (the 48 MP large-file decode guard refusing before
any work, the generous byte model + 1.5×-headroom storage check over
`StorageManager` — advisory, `ERROR_IO` keeps the frozen v1 contract
surface — and the documented process-death policy: Q+ pending rows are
invisible and MediaStore-reclaimed with the sidecar restoring the
session, pre-Q leaves the user-removable partial row). The **applied
policy is now narrated** (contract §5's honesty UI): additive
`SavedCopy.removesLocation` / `stripsAllMetadata` triggers in
`:shared:editor-contract` choose the gallery's saved-copy snackbar
("location removed" / "metadata stripped" / plain), with a pre-r29
editor's missing echo making no claim. The physical half of the exit —
the §20 export robustness matrix (policy×GPS verification, the 48 MP
guard, the low-storage preflight, process death on Q+ and pre-Q) plus
the §19 round trip — is checklist-ready and **human-only**; the
CI-gated merge chain (#104 → #105 → #106) closes the rounds. Editor at
`versionCode 3` (`0.3.0-edit-alpha`).

**Status (r32): hardened.** The maintainer's follow-up window ran a
contained hardening round on the same `tg-p6-edit-v1-exit` branch (riding
PR #106, ledger row #107): the **accessibility sweep** over the whole
editor surface — every slider (the 10 adjustments, straighten, export
quality, the three HSL band controls) announces its name + live value via
`semantics`, the HSL rows speak "BAND: CONTROL", and the flip toggles are
proper FilterChips with 48dp targets — the **straighten live-drag fix**
(the thumb and preview follow the drag; the old path burned a per-tick
re-render for an unchanged preview), and the tested pure
`CropRect.fromPixelRect` inverse replacing the crop-preset UI's inline
division. 5 new JVM tests (GeometryTest 19 → 24); editor `versionCode 4`
(`0.3.1-edit-alpha`); the on-device TalkBack pass rides checklist §21.
Phase 6 remains code-side complete; the exit still rides the §19/§20/§21
device rows + the merge chain.

**Status (r31+r32, MERGED):** the Edit v1 exit round is on main ---
the per-export metadata-policy choice (the request policy as the
privacy floor), the export preflight (the 48 MP decode guard, the
storage-headroom check, the process-death policy), the applied-policy
honesty narration, and the r32 hardening pass (the a11y sweep, the
straighten live-drag fix, the pure `CropRect.fromPixelRect` inverse)
landed as PR #106 squash `938effa` (CI 41779955612 green on the union
head `9336412`; editor `versionCode 4`). **Phase 6 is code-side
complete**; the exit declaration rides the human-only §19/§20 device
rows.

**Purpose:** Ship the first version of the editor APK with the
adjustment suite and the non-destructive pipeline that all later editor
phases build on.

#### Tasks

- Create the separate installable app ID and release artifact for
  **toadGALLERY Edit** (Compose-native).
- Finalize the versioned intent/content-URI contract from ADR-0007 /
  `docs/architecture/editor-contract.md`.
- Grant only the URI permissions required; never depend on arbitrary
  absolute paths.
- Define behavior when the extension is not installed, is outdated, or
  cannot open a format.
- **Non-destructive pipeline (ADR-0011):** edits stored as sidecar JSON
  or XMP, never overwriting the original bytes until the user
  explicitly exports. The pipeline is the foundation for Phases 7 and
  8.
- **Crop / rotate / flip / straighten / perspective.**
- **Adjustment suite:** brightness, contrast, saturation, exposure,
  temperature, tint, highlight, shadow, vignette, sharpness,
  per-channel HSL (Hue/Saturation/Luminance per colour channel).
- **Undo/redo stack** with reasonable depth and clear affordance.
- **Export with quality and format control** (JPEG quality, PNG, HEIC
  where device supports, original format preserved where possible).
- **Metadata preserve / strip / rewrite on export.** Never silently
  strip GPS/EXIF. Default is preserve; strip is an explicit user
  choice per export.
- Default to **Save copy**. Offer replace-original only when the
  operation can be performed safely and the user explicitly chooses it.
- Test large files, cancellation, low storage, process death, and
  round-trip return to Gallery.

#### Deliverables

- Separate toadGALLERY Edit APK (v1, Compose-native).
- Finalized editor integration contract.
- Non-destructive edit pipeline.
- End-to-end Gallery to Edit to Gallery tests.
- Export/original-preservation policy.

#### Exit criteria

- Gallery can detect and launch the extension safely.
- The editor can export a result and the Gallery refreshes correctly.
- Original files are not silently overwritten or lost.
- Missing extension and failed edits have clear recovery paths.
- The adjustment suite covers every parameter listed above with
  undo/redo and a non-destructive preview.

### Phase 7 --- toadGALLERY Edit v2: filters and annotations

**Status (FINAL): tasks 1-5 of 5 MERGED --- PHASE 7 CODE-COMPLETE (the LUT `.cube` filter system
with the user-import flow (PR #107 squash `314bd9c`, CI 41781166155
green on the rebased head `ceb731b`; editor `versionCode 6`) and the
text tool (PR #109 squash `6bb17a4`, merge-gate run 41822887363 green
on head `699fd03`; editor `versionCode 7`) and the sticker tool (PR
#110 squash `980ccea`, merge-gate run 41836972158 green on the first
execution --- the pre-push harness's debut; editor `versionCode 8`)
--- plus the mosaic/blur regions (MERGED as PR #112 `6c206d9`, the
UI-section debt fix riding #113) and the frames + the undo/redo
capstone (MERGED as PR #113 `003dafb`)**: the pure
engine (the Adobe `.cube` parser with its honest subset rules, the
allocation-free trilinear sampler, the alpha-preserving intensity
blend), the recipe's additive `LutSelection` (an unresolvable id
round-trips verbatim and degrades to no-look --- the tolerance
contract), pipeline stage 10 between the HSL bands and the spatial
effects, **five project-authored bundled looks** generated by the
committed deterministic generator (`tools/gen_bundled_luts.py`; no
third-party LUT data --- the licensing record is
`docs/phase-7/bundled-luts.md`), the Filters chip row + intensity
slider over the ONE shared look cache (preview ≡ export), and --- the
window extension --- **the user-import `.cube` flow**
(`ImportedLutStore`: parse-before-write, verbatim bytes, ids derived
and collision-suffixed so an import can never shadow the bundled set;
the SAF import chip, the merged registry chip row, the remove-look
affordance). 51 new JVM tests (the edit module at 209).

**The text tool (task 2) is MERGED** (PR #109 squash `6bb17a4`, r34
round 2026-11-04, merge-gate run 41822887363 green on head `699fd03`):
the OFL gate (four verbatim-bundled SIL OFL 1.1 faces +
per-font ledger rows + `docs/phase-7/bundled-fonts.md`), the additive
`TextLayers` recipe, the pure `TextLayout`, the shared `TextCompositor`
(preview ≡ export), and the full editor surface --- editor
`versionCode 7` (`0.4.2-edit-alpha`), 32 new JVM tests (the edit module
at 241), the module string count 74 → 94. The delivery record:
`docs/phase-7/text-tool.md`. PR #108 (the redundant `tg-p7-lut-import`
PR) closed with the round's merge (the `Closes #108` keyword in PR
#109's description). **The sticker tool (task 3) is MERGED** (PR #110
squash `980ccea`, r35 round 2026-11-04, merge-gate run 41836972158
green on the first execution): eight project-authored IN-CODE vector
shapes (no image asset, no §5 row --- `docs/phase-7/bundled-stickers.md`
is the authorship record), the additive `StickerLayers` recipe with the
image-shaped tolerance delta, the shared `StickerCompositor` BELOW the
text pass, `ImportedStickerStore` (PNG/WebP magic-before-write), the
Sticker UI with ONE selected layer at a time across the families,
46 new JVM tests (the edit module at 287), the string count 94 → 104,
editor `versionCode 8` (`0.4.3-edit-alpha`). **The drawing tool (task 4)
is AUTHORED on `tg-p7-drawing`** (r36 round 2026-11-04, the design
contract `docs/phase-7/drawing-tool.md`): the stroke-per-layer model
(one gesture = one layer = one undo snapshot), the additive `DrawLayers`
recipe with the round-three stacking proof, the commit-time stride
thinning to the 512 cap, the pure `DrawLayout` (Chaikin corner-cutting
round(smoothing/25) → 0..4 passes), the shared `DrawCompositor` between
the sticker pass and the text pass, the Draw UI under the exclusive
duality, and the ONE `sweepPendingDrafts()` helper across the families;
27 new JVM tests (the edit module at 314), the string count 104 → 114,
editor `versionCode 9` (`0.4.4-edit-alpha`). **The mosaic/blur regions
(task 5, first half) are AUTHORED on `tg-p7-mosaic`** (r37 round
2026-11-04, the design contract `docs/phase-7/mosaic-regions.md`): the
pixel-stage break --- the painted regions censor the fully graded pixels
as the pipeline's final stage (after sharpen), grid-relative like the
vignette; the additive `RegionLayers` recipe (the round-four stacking
proof; the unknown-kind skip), the pure `RegionMask` (even-odd scanline
fill, strict pixel centres) + `RegionEffects` (integer-deterministic
mosaic block average + sliding-window linear blur), the second paint
mode (mutually exclusive with Draw mode), and the draft region
previewing at paint time through the same stage the export runs;
26 new JVM tests (the edit module at 340), the string count 114 → 122,
editor `versionCode 10` (`0.4.5-edit-alpha`) --- MERGED as PR #112
`6c206d9` (merge-gate CI 41843555827 caught the local-function
declaration order; 41844536338 green), with the r37 UI section's debt
fix riding the r38 branch (the recorded correction: the r37 insertion
script never wrote the activity back; the merged r37 carried everything
but the surface). **The frames (task 5, second half) are MERGED** (PR #113 `003dafb`;
the design contract `docs/phase-7/frames.md`): the single
`FrameSelection` (the LutSelection shape), five project-authored
parametric frames as pure geometry functions (plain / inner-line /
double-line / film with punched sprocket holes / polaroid), the shared
`FrameCompositor` LAST in the composite order, `ImportedFrameStore`
(PNG/WebP magic-before-write; an imported frame stretches to the full
canvas with the honest no-controls note), the look-row UI, and the
undo/redo extension capstone (`UndoRedoExtensionTest`: a mixed
five-family session walked exactly, undo to launch, redo forward);
32 new JVM tests (the edit module at 374), the string count 122 → 129,
editor `versionCode 11` (`0.4.6-edit-alpha`). **Phase 7's code-side
work is COMPLETE**; the §22-§27 device rows ride the checklist,
human-only.

**Purpose:** Add the creative layer on top of the Phase 6 pipeline:
filters, text, stickers, drawing, mosaic, frames.

#### Tasks

- **LUT-based filter system** with an intensity slider. No copyrighted
  LUTs bundled. The user can import their own `.cube` files; bundled
  defaults are CC0 or project-authored.
- **Text tool:** font, colour, style, curve path, opacity, shadow.
  Bundle a small set of OFL-licensed fonts.
- **Stickers:** small bundled set (CC0 or project-authored); user can
  import their own PNG/WebP stickers.
- **Freehand drawing and brush:** colour, size, opacity, smoothing.
- **Mosaic and linear blur regions:** user paints a region, the editor
  applies a mosaic or blur to that region only.
- **Frames and borders:** small bundled set; user can import their own.
- All of the above are non-destructive layers in the Phase 6 pipeline,
  re-editable until export.
- Undo/redo extended to cover the new layer types.

#### Deliverables

- Filter, text, sticker, drawing, mosaic, and frame features in the
  editor (Compose).
- Bundled asset set with documented licenses (CC0 / OFL / project).
- Updated end-to-end tests.

#### Exit criteria

- Every Phase 7 feature is non-destructive and re-editable.
- No bundled asset has an incompatible license.
- Round-trip with the Gallery still works after a multi-layer edit.

### Phase 8 --- toadGALLERY Edit v3: on-device AI features

**Status: SKIPPED (2026-11-04 maintainer directive)** --- the whole
scope below is folded into Phase 10 as the explicitly-deferred "Edit
v3 on-device AI editing" sub-scope; this section stays for the record
(the task text is unchanged under Phase 10).

**Purpose:** Deliver the Mi Gallery wow-factor (magic erase, sky
replacement, bokeh, beauty) with the strict on-device-only policy
governed by ADR-0010.

#### Tasks

- **Magic erase / object removal:** user paints a region, an on-device
  segmentation model identifies the object, an inpainting model fills
  the region. Both models ship in the APK.
- **Sky replacement:** user picks a region identified as sky (or the
  model auto-detects), applies a replacement sky from a small bundled
  CC0 set or a user-supplied image.
- **Bokeh / portrait blur:** depth estimation model ships in the APK;
  user adjusts blur strength and focal point.
- **Face retouch (opt-in, with explicit consent dialog the first time
  it is used):** skin smoothing, brighten eyes, slim face. Disabled by
  default. The consent dialog explains what the model does, that it
  runs on-device, and that no face data leaves the device. The user
  can permanently disable the feature in Settings.
- All AI features are non-destructive layers in the Phase 6 pipeline.
- Privacy test suite extended to assert zero outbound connections
  during any AI feature.

#### Deliverables

- Magic erase, sky replacement, bokeh, face retouch (opt-in) in the
  editor (Compose).
- On-device model packaging with documented licenses and sources.
- Extended privacy test suite (green).

#### Exit criteria

- Every AI feature runs with zero outbound network connections.
- Every AI feature is non-destructive and re-editable.
- Face retouch requires explicit opt-in and can be permanently
  disabled.
- Model licenses are compatible with the product's GPL-3.0 mix and are
  documented in the attribution package.

### Phase 9 --- Lightweight video editor

**Status: SKIPPED (2026-11-04 maintainer directive)** --- the whole
scope below is folded into Phase 10 as the explicitly-deferred
"lightweight video editor" sub-scope; this section stays for the
record (the task text is unchanged under Phase 10).

**Purpose:** Add Mi Gallery-grade lightweight video editing: trim,
mute, slow-mo, frame extraction, GIF/WebP export. Not a multi-track
NLE. Bounded by ADR-0012.

#### Tasks

- **Trim:** set in/out points, export the trimmed segment.
- **Mute:** strip the audio track.
- **Slow-mo:** apply a speed factor (0.25x--2x) to the whole clip or a
  selected region.
- **Frame extraction:** export a single frame as JPEG/PNG at a
  user-chosen timestamp.
- **GIF/WebP export:** short animation from an in/out range, with size
  and frame-rate control.
- **Light colour adjust:** reuse the Phase 6 adjustment pipeline on
  video frames via media3 where the performance budget allows.
  Document which adjustments are real-time and which require an export
  pass.
- Non-destructive where feasible: the original video is never modified;
  the editor exports a new file.
- Test on long videos (10+ minutes), 4K clips, HDR clips where device
  supports, and clips with no audio track.

#### Deliverables

- Video editor integrated into the toadGALLERY Edit APK (or as a
  sibling feature gate, depending on APK size budget --- decision
  deferred to Phase 9 design per ADR-0012).
- Reusable adjustment-pipeline-on-video code in `shared/`.
- Updated end-to-end tests.

#### Exit criteria

- Trim, mute, slow-mo, frame extraction, and GIF/WebP export all work
  on representative test clips.
- No original video is ever modified.
- Performance on long / 4K / HDR clips is documented and within an
  agreed budget.

### Phase 10 --- Hardening + the deferred Edit v3 AI editing + the lightweight video editor (absorbing Phases 8–9)

**Status:** DONE (code-complete) --- all three parts MERGED: the
hardening round r40 (PR #117 squash `c3c21f7`, merge-gate 41955524067
green after the two recorded catches, with the two sub-scope design
contracts MERGED alongside as PR #116 squash `16a5001`, docs-only),
the Edit v3 AI sub-scope r41 (PR #118 squash `73cfb1b`, merge-gate
41978641679 green after the two recorded catches), and the lightweight
video editor sub-scope r42 (PR #119 squash `f8583d3`, merge-gate
`41988201250` green after the six recorded catches). Since the
2026-11-04 maintainer directive this phase carried the SKIPPED Phases
8 and 9 scopes as its two explicitly-deferred sub-scopes (below), and
both are delivered in the stated order. Every benchmark/device result
cell stays human-only (the standing directive): the skeletons' cells,
the AI latency/quality rows, and the video long/4K/HDR matrix ride
the physical device checklist.

**Purpose:** Validate real-world behavior before release --- and, since
the 2026-11-04 directive folded Phases 8–9 here, deliver the deferred
Edit v3 on-device AI features and the lightweight video editor inside
the same hardening-first phase.

#### Deferred sub-scope A (folded from Phase 8): Edit v3 on-device AI editing

The full Phase 8 task text applies verbatim (magic erase, sky
replacement, bokeh, opt-in face retouch; ADR-0010's on-device-only
policy; non-destructive layers in the Phase 6 pipeline; the privacy
suite extended to assert zero outbound connections during any AI
feature). Served by the engine workstream's model-provisioning
discipline (ADR-0020).

#### Deferred sub-scope B (folded from Phase 9): the lightweight video editor

The full Phase 9 task text applies verbatim (trim, mute, slow-mo,
frame extraction, GIF/WebP export; ADR-0012's minimal-dependency
rule).

#### Tasks (the original Phase 10 hardening scope)

#### Tasks

- Measure cold launch, first usable content, scroll smoothness, memory,
  thumbnail cache behavior, search latency, metadata extraction, and
  editor open-to-first-preview time.
- Test libraries of approximately 10,000 and 50,000 items, with
  realistic media mixtures.
- Profile startup and scrolling before optimizing; consider Baseline
  Profiles if beneficial.
- Test low/mid-range devices, Android 16, older supported Android
  versions, large images, long videos, corrupt files, and partial
  media permissions.
- Verify that background work is bounded, cancellable, and does not
  wake the device unnecessarily.
- Test upgrades, database migrations, editor version mismatch, and
  both apps installed/uninstalled in different orders.
- **Accessibility hardening:** TalkBack labels, focus order, scalable
  text, contrast checks, and appropriately sized touch targets across
  every screen, including the editor.

#### Suggested initial targets

- First usable gallery content within about 2 seconds on a defined
  mid-range test device.
- No full-library rescan on every launch.
- Smooth thumbnail scrolling under a documented representative test set.
- Search on indexed fields feels immediate on the target library size.
- No full-resolution decode for ordinary grid thumbnails.
- Editor opens a 12 MP photo to first preview within about 1 second on
  the same device.

#### Deliverables

- Repeatable benchmark scenarios and baseline results.
- Device/API compatibility matrix.
- Accessibility test report.
- Prioritized performance fixes.

#### Exit criteria

- No known critical data-loss, permission, or stability issue remains.
- Performance is measured against documented devices and sample
  libraries.
- Unsupported formats and platform constraints are documented.
- Core flows are usable with accessibility features.

### Phase 11 --- Release readiness

**Status:** DONE (code-side + docs-side) --- the Phase 11 campaign closed
in three merged rounds: the release-engineering round r43 (PR #121
squash `d27f3c1`, merge-gate runs 41996006593/41996006784 green after
the two riding catches), the F-Droid metadata round r44 (PR #120 squash
`791d3a0`, gates 41996188270/41996187119 green), and the release-docs
round r45 (PR #122 squash `4d797ff`, gate 41993838209 green), with the
`v0.4.5-alpha-r45` close-out release cut from the truth commit (BOTH
APKs --- the gallery `0.4.5-alpha` versionCode 37 and the edit app's
FIRST release artifact `0.4.10-edit-alpha` versionCode 15 --- each with
its `.sha256` and its per-app `mapping.txt`). What remains is
deliberately human: the v1.0 stable cut (a maintainer decision that
waits on the human-only device rows), the F-Droid submission merge
request itself (the metadata is prepared and pinned, `fdroid/metadata/`),
and every on-device verification row (the standing directive).

**Purpose:** Prepare the two apps for maintainable distribution.

#### Tasks

- Review all source and dependency licenses, notices, attribution, and
  source-distribution obligations.
- Configure separate app IDs, versioning, signing, build variants, and
  release artifacts.
- Set up automated builds, unit tests, lint/static checks, and
  installation/integration smoke tests (extending the Phase 2 CI
  workflow).
- Verify clean install, upgrade, rollback/recovery where feasible, and
  independent editor updates.
- Prepare release notes, known limitations, privacy documentation, and
  support/issue templates.
- Confirm app-store policy and permission declarations for the intended
  distribution channel.
- **F-Droid metadata:** prepare the F-Droid build metadata, verify the
  build is reproducible (CI workflow is the canonical recipe per
  ADR-0013), and confirm no proprietary dependencies.
- **Third-party FOSS review:** commission or self-conduct a review of
  the final source tree for license compliance, attributions, and any
  accidentally included proprietary code.

#### Deliverables

- Release-ready Gallery APK and Edit APK.
- Reproducible build instructions and release checklist.
- License/attribution package and documented known limitations.
- F-Droid metadata ready for submission.
- CI workflow extended with release-signed APK production on tag
  pushes. **Pulled forward and delivered early (2026-10-31, issue #59,
  ADR-0018):** the dedicated `Release` workflow signs testing APKs with
  the project release key and publishes GitHub pre-releases (APK +
  `.sha256` checksum + R8 `mapping.txt`), hard-gated by an
  `apksigner verify` pin on the signer certificate digest. **The
  Phase 11 remainder DELIVERED (r43-r45, 2026-11-05):** the
  reproducible-build verification (`reproducible-build.yml`, the double
  clean unsigned build + the honestly named verdicts, first measurement
  on the `v0.4.5-alpha-r45` push) and the F-Droid metadata
  (`fdroid/metadata/` for both apps + the submission package at
  `docs/phase-11/fdroid-metadata.md`), plus the release-notes /
  known-limitations / permissions-privacy / support-template / FOSS
  review package (`docs/phase-11/` + `.github/ISSUE_TEMPLATE/`) and the
  both-APK release pipeline extension (the edit app's release variant +
  per-app mapping.txt artifacts).

#### Exit criteria

- Both artifacts build reproducibly and pass the release test suite.
- License and distribution obligations are reviewed.
- Gallery and editor can be released and updated independently.
- F-Droid metadata is in shape for submission.

## 5. Cross-phase quality gates

Every phase must maintain these conditions:

- **Build:** CI is green. The build does not compile, or the blocker is
  documented and isolated.
- **Tests:** add tests for new behavior and regressions.
- **Privacy:** core gallery functionality remains offline-first. The
  build-time `INTERNET` permission gate stays green. The privacy test
  suite stays green.
- **Data safety:** originals and user files are protected from
  accidental destructive operations. Non-destructive pipelines stay
  non-destructive.
- **Permissions:** test full access, limited access, denial, and
  revocation where supported.
- **Performance:** no heavy file I/O, metadata parsing, or
  full-resolution decoding on the UI thread.
- **Licensing:** record source origin and license for copied/adapted
  code and dependencies. The provenance ledger at
  `licensing-and-attribution.md` §5 records any code-level adaptation
  (currently empty per ADR-0008). AI model sources and licenses are
  recorded.
- **Accessibility:** new screens ship with TalkBack labels, focus
  order, scalable text, and adequate touch targets.
- **Documentation:** update architecture and feature decisions when
  implementation choices change.

## 6. Initial must-have scope (v1.0)

### First usable release (v1.0)

- Photo/video timeline and viewer (Phase 3).
- Albums/folders, favorites, recycle bin, hidden vault (Phase 3).
- Multi-select, sharing, basic file operations (Phase 3).
- Aves-grade metadata details for supported formats, with edit for
  common fields and on-device maps (Phase 4).
- Smart search with date/type/album/favorite/name/metadata/people/tags
  filters, plus on-device semantic search (Phase 5).
- Moments timeline, opt-in auto-albums, Stories, collage maker,
  slideshow (Phase 5).
- **toadGALLERY Edit v1+v2+v3:** crop/rotate/straighten/perspective,
  full adjustment suite, filters, text, stickers, drawing, mosaic,
  frames, magic erase, sky replacement, bokeh, opt-in face retouch
  (Phases 6, 7, 8).
- Lightweight video editor: trim, mute, slow-mo, frame extraction,
  GIF/WebP export (Phase 9).
- Separate toadGALLERY Edit APK with a reliable launch/export round
  trip (Phases 1, 6).
- Android permission, privacy, accessibility, and performance checks
  (Phases 2, 10).
- F-Droid-ready, reproducible build, license/attribution package
  (Phase 11).

### Defer until after v1.0

- Cloud sync, accounts, social sharing, ads, analytics.
- Always-on background indexing.
- Online maps/reverse geocoding as a core dependency (offline tile
  sideload remains the only map path).
- Complex RAW development or professional-grade editing.
- Multi-track video editing, video transitions, video titles, video
  colour grading beyond what the photo adjustment pipeline exposes.
- Broad format promises without verified decoder support.

### Explicitly NOT deferred (changed from v1)

- **On-device AI editing features** (magic erase, sky replacement,
  bokeh, face retouch) --- now in Phase 8, governed by ADR-0010's
  strict on-device-only policy.
- **On-device people clustering and object/scene tagging** --- now in
  Phase 5, opt-in.

## 7. First implementation sprint (v2.2)

1. Apply ADR-0008/0009/0013 to the repo: supersede ADR-0001/0005, add
   Compose toolchain, add CI workflow, mark stale docs.
2. Migrate the Phase 2 app shell from View-based + commons to
   Compose-native + Material 3.
3. Get CI green on the Compose-native shell.
4. Land the Phase 3 core media access in Compose.
5. Land the Phase 4 metadata layer with `androidx.exifinterface`.
6. Begin Phase 5 organisational UX (Moments, smart search).
7. Begin Phase 6 editor scaffolding against the ADR-0007 contract.

## 8. Definition of success

toadGALLERY v1.0 succeeds when it feels like one thoughtfully designed
gallery rather than a collection of merged upstream features: browsing
is fast, search and metadata are useful, file operations are safe, the
product respects privacy, the editor rivals Mi Gallery's editor in
everyday capability, and every feature --- including AI --- runs
entirely on the user's device with zero network traffic. The product is
built from scratch in Kotlin + Jetpack Compose; no code is ported from
any upstream; CI on GitHub Actions is green at every release.

**Planning note:** This roadmap is a proposal that has been revised
three times (v1 to v2.0, v2.0 to v2.1, v2.1 to v2.2) based on user
directives. Exact architecture, package/module layout, supported
formats, SDK levels, performance thresholds, and release scope should
be confirmed through Phase 1 evidence and ongoing ADRs rather than
assumed in advance.
