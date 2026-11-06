# Changelog

All notable changes to toadGALLERY are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
`versionName` in `apps/gallery/build.gradle.kts` (pre-1.0 alphas).

## [Unreleased] — the r50 round (versionCode 38 unchanged; gallery `0.4.5-alpha`)

### Fixed

- **[engine] F-38 closed — the UI-side engine teardown can no longer kill
  a running index queue.** r48 removed the WORKERS' half of the
  process-global engine mutual-kill; r50 removes the remaining UI half:
  the People / Person-Detail / Magic-Search / Albums `onCleared()`
  adapter closes, the Settings self-test's `finally { release() }`, and
  the two reset buttons all tore the shared native session out from
  under a live face/clip chain (`IllegalStateException` → retry ×3 →
  the terminal Failed card — the remaining "indexing are failing"
  candidate). A new `EngineSessionGuard` (ml/index) checks both unique
  WorkManager chains: teardown now runs only when neither is live, and
  the resets first cancel BOTH chains and await their termination
  (bounded, DiagLog-mirrored) before deleting the shared `face-index`
  directory. A skipped teardown leaks nothing — every engine user
  re-opens the session lazily.
- **[engine] F-39 closed — permanently unreadable photos can no longer
  keep the chains re-enqueueing forever.** A photo whose bytes cannot be
  read (deleted row, revoked grant, DRM/cloud stub) used to stay
  state-less, so it re-entered EVERY plan and the worker re-enqueued
  itself at every batch boundary with zero progress — "indexing never
  finishes" with no failure message, battery drain and log churn. Both
  queues now write a TERMINAL-SKIP state record (`unreadable: true`, an
  additive optional version-1 JSON field): the photo is held out until
  its revision CHANGES (a genuinely changed file gets a fresh read), is
  counted separately in the plan/outcome (`skippedUnreadable`, surfaced
  in the drained card line and the diagnostics ring), and never
  produces face/clip rows. The JVM suites pin the new contract.
- **[engine] The ML read seam gains the 64 MiB refuse-don't-truncate
  cap** (the `MediaReadHandle.DEFAULT_MAX_BYTES` posture,
  docs/phase-5/hardening.md): one pathological multi-hundred-MB file was
  an OOM vector on low-RAM devices; an over-cap item now joins the
  terminal-skip path instead.
- **[ci] The r49 `apk-build.yml` dispatch bug: every gallery dispatch
  failed in `prepare`.** The dispatch choices carry the `gallery-*`
  prefix (`gallery-engine` / `gallery-modelpack`) but the resolver
  matched the SHORT names (`engine` / `modelpack`), so the DEFAULT
  dispatch fell to the unknown-target error — only the branch-push
  paths and the editor dispatch ever worked (the r49 honest-limits note
  had flagged the unverified dispatch paths). The prepare job now
  strips the prefix once, loudly.
- **[ci] The model-pack build is now actually offline: the WHOLE pack.**
  The r28-era step staged the FACE pair only, but the assembled
  engine-flavor APK enables the SEMANTIC index too — its CLIP assets
  (image + text + vocab, ~213 MB) fell through `BundledModelSource` to
  the network, contradicting the artifact's "never opens a socket"
  purpose. All five assets are now fetched, sha256-verified, staged and
  gate-checked — and a transcription typo in the r49
  `mobilefacenet_portable_static_b1.onnx` pin (which would have failed
  the fetch gate) was caught by re-verifying every pin against
  ModelManifest.kt, the vendored `assets.rs`, AND the real download.
- **[ci] The engine target's artifact name loses the accidental
  `-engine-engine` doubling** (`toadGALLERY-<version>-engine-debug.apk`;
  the modelpack keeps its explicit `modelpack-engine` shape).

### Changed

- **[gallery] The UI/UX polish round (three parallel audits over the basic
  and engine flavors, then the fix batch):** the viewer's video-chrome trap
  (a photo tap that hid the chrome left the next VIDEO page with no
  reachable back/info/overflow at all --- the settled video page now forces
  the chrome back on), a video mute toggle in the chrome bar (drawable
  glyphs, no extended-icons dependency; rendered only on video pages), the
  rename dialogs' missing keyboard focus + IME Done (viewer + person
  detail), selectable info-sheet values, the Settings AI section's honest
  state combos (Start gated on capability Ready + no live run; Failed
  lines in the error color with an inline Retry; a busy spinner during
  provisioning; delete-models confirmations), the People NotIndexed card's
  missing action, the inert picked-photos grid (now opens the viewer), the
  snackbar-over-selection-actions overlap on Photos + album detail (the
  undo snackbar physically covered Rename/Copy/Move/Delete and ate their
  taps), the internal "Phase 3" milestone badge deleted from every shared
  state surface, the sort menu's misleading hamburger glyph swapped to the
  overflow idiom, the search tab's keyboard no longer popping over returned
  results, the Things collection chip's whole-chip tap target + ellipsis,
  the place cards' decode-failure plate, the Albums tab's missing title,
  the vault-move confirm's destructive color, and the model-pack's
  hardcoded FACE size left as-is (the manifest-derived semantic size stays
  the pattern to extend).
- **[gallery] The a11y batch:** TalkBack click labels + long-press labels
  on every media cell (the long-press selection entry was undiscoverable),
  whole-row toggleable semantics on every Settings switch row, Role.Button
  on the details screen's expandable/editable rows, the append footer's
  silent spinner now narrates (`state_append_loading` was written for it
  and never referenced), and the four byte-identical selection-check badge
  copies consolidated into ONE shared composable (the trash copy had
  drifted: no dim check while unselected and no `file_ops_cell_check` test
  tag --- restored via the shared one).
- **[build] The flavor ↔ property binding is now enforced by Gradle, not
  workflow discipline.** `assembleBasic* -PwithEngine` (which silently
  packed the .so files into basic) and `assembleEngine*` WITHOUT the
  property (an honest-but-wrong LibraryMissing APK) now fail fast at
  task-graph time — only for tasks that actually PACKAGE a gallery APK,
  so the cross-flavor test/lint/record tasks CI runs stay
  property-free. `-PwithBundledModels` without `-PwithEngine` fails
  too.
- **[basic] The basic flavor now holds NO network permission at all.**
  A flavor manifest overlay strips `INTERNET` (the only consumer — the
  ADR-0020 model fetch — is unreachable in basic by BuildShape), and
  the r50-hardened privacy gate FAILS any `basic*` merged manifest that
  carries it, allowlist or not; the engine family keeps the r22
  allowlisted posture. The basic About screen states the stronger
  truth via its own flavor string; the F-Droid description copy follows.
- **[ui] The viewer's overflow rows gain distinct leading icons** — the
  text-only menu made Edit and Rename read as the same action (the
  maintainer's device report): Edit keeps the pencil, Rename draws a
  dedicated `ic_rename` (pencil + baseline bar, core-set only — no
  extended-icons dependency), Delete pairs its error-tinted text with
  the trash. Test tags unchanged.
- **[ui] The Settings AI card polishes (the second campaign's pass, on
  top of the honest-state combos above):** the destructive actions
  (model deletes, both index resets) render as outlined error-toned
  buttons instead of the same filled style as the start actions (the
  delete confirmations ride them); the "r22/r24/r26 dogfood"
  build-codename jargon is gone from the user-facing labels; the
  drained lines report the unreadable (terminally skipped) count;
  re-entering Settings no longer erases the last outcome/self-test
  cards; and the engine's own identity line (capability + model labels
  + ORT bindings version) is surfaced under the library status
  (`engineInfo()` previously had zero call sites).
- **[ui] The ENGINE flavor's launcher label returns to "toadGALLERY
  Engine"** (the r46 shape; the r47 plain-label directive is superseded
  by the dual-flavor audit finding: basic and engine install side by
  side on the dogfood devices, and two launcher entries with the same
  name AND the same shared icon are indistinguishable — semantic/face
  keep their suffixed labels, so the four-flavor matrix is consistent
  again).
- **[build] The NDK pin is now PREFERRED over any newer NDK the runner
  image ships** (single-sourced as `PINNED_NDK_VERSION` in
  engine/scripts/build-android.sh), instead of the pin being only the
  no-NDK-at-all fallback while every build silently rode the newest
  runner NDK.
- **[fdroid] The metadata describes the flavor world honestly:** the
  F-Droid package is the BASIC build (no network permission, no AI
  engine), and MaintainerNotes records the r46+ recipe
  (`assembleBasicRelease` + the flavor-qualified output path) for every
  future Builds entry — the r45 entry keeps the flavorless shape that
  was correct at its pinned commit.

### Removed

- The stale `org.fossify.**` proguard keep rule (the dependency left per
  ADR-0008; a keep rule for an absent dependency is a no-op that only
  rots), stale KDoc claims (AboutScreen's "no INTERNET permission",
  the Application's composite-hook wiring note), the Search empty
  state's stale "smart search arrives in roadmap Phase 5" promise, and
  the unused `state_settings_empty_*` pair.

### Housekeeping

- **235 failed/cancelled workflow runs deleted from the Actions history**
  (the parallel campaign's 137 failed + 55 cancelled, then this pass's
  34 failed + 9 cancelled stragglers; the history now holds ZERO failed
  and ZERO cancelled runs). The identity audit (both campaigns,
  independently): the repo tree is fully `dukeoftoad` — every
  author/maintainer/email/copyright/namespace pattern swept clean (the
  other GitHub URLs are upstream attributions); the 40 older
  direct-to-main commits authored by the sandbox identity are recorded
  in follow-up.md F-46 (a history rewrite would orphan every release
  tag and invalidate every recorded SHA; commits from this round on
  carry the correct identity).

## [Unreleased] — the r49 round (versionCode 38 unchanged; gallery `0.4.5-alpha`)

### Changed

- **[ci] The workflow set reorganized: eight files → five, one concern
  each.** The FOUR opt-in APK test builders (`apk-build-engine.yml`,
  `engine-apk.yml`, `modelpack-apk.yml`, `editor-apk-build.yml`) whose
  engine build chain was written out three times with drift between the
  copies are consolidated into ONE dispatch surface,
  [`apk-build.yml`](.github/workflows/apk-build.yml) ("APK builds (test
  artifacts)"): the `gallery-engine` / `gallery-modelpack` / `editor`
  targets behind one `target` input, a `prepare` job that resolves the
  target + variants (dispatch inputs OR the surviving branch-scoped
  `tg-engine-apk-*` / `tg-modelpack-*` push convention, inferred from the
  branch name, unknown names fail-fasting) and two build jobs that keep
  their own toolchain budgets (the gallery keeps the full engine-cross
  Rust shape, the editor stays cheap pure-Android). The branch-scoped
  dogfood triggers and every documented gate survive the move: the
  vendored-pin validation, the native-library gate, the model-pack
  assets gate, the aapt2 identity gates, the R8-mapping staging, the
  fail-fast ADR-0018 secret checks (now BEFORE the toolchain setup
  spends minutes), and the build-log-on-failure uploads.
- **[ci] The rot the consolidation removed:** the model-pack build no
  longer stages under a hardcoded `toadGALLERY-0.4.2-alpha-r28-...` name
  nor gates `versionCode >= 32` — every gallery artifact is named and
  identity-gated from the checkout's own derived version
  (`toadGALLERY-<versionName>-<target>-engine-<variant>.apk`, exact
  versionCode), so nothing hardcodes a version that the next round
  invalidates. The derivation rules are documented per file (first live
  assignment for the gallery, whose gradle keeps history as comments;
  last for the editor, which appends live pairs — the r43 catch).
- **[ci] Least-privilege baseline:** every workflow now declares an
  explicit `permissions:` block — `contents: read` everywhere except
  `release.yml` (the only `contents: write`, for tag cutting +
  publishing). `ci.yml` gains the block it had silently inherited from
  the repo default.
- **[docs] The living references updated to the five-file truth:**
  `docs/ci-workflows.md` rewritten (the r49 organization note, the
  consolidated builder's contract, the corrected inventory — including
  the stale "the repo has no composite actions" line, false since F-2
  delivered `toad-setup`) and the README's Debug-APK-builds section now
  points at the one builder instead of four files.

## [Unreleased] — the r48 round (versionCode 38 unchanged; gallery `0.4.5-alpha`)

### Added

- **[gallery] The README screenshot suite (the toadFILES
  `TabScreenshotsTest` pattern)**: `ReadmeScreenshotsTest` hosts the REAL
  app shell (`MainActivity` + `ToadGalleryApp`) under Robolectric over a
  seeded media world --- a `FakeMediaProvider` registered under the
  `media` authority serving twelve fixed rows, real JPEG bytes on the
  Robolectric filesystem decoded by the app's own Coil pipeline, and the
  same rows pre-upserted into the Room catalogue --- and walks the REAL
  bottom navigation for five captures (`01-photos` .. `05-settings`).
  The README gains the Screenshots section (the toadFILES table layout)
  with the regeneration recipe, and the committed PNGs under
  `docs/screenshots/` come from this round's record run's artifact. Two
  Robolectric behaviors the repo had never exercised are pinned in the
  test's comments: runtime permission requests never resolve on the JVM
  (ShadowActivity delegates to the REAL Activity code whose
  `startActivityForResult` has no system UI to answer it --- the grant
  is delivered by hand through the shadow-recorded request), and Coil's
  background decodes need a wall-clock settle before capture (compose
  idling does not track them).
- **[gallery] `screenshots.yml` learns the second capture location**: the
  baseline detection (now counting BOTH the r47 plugin-outputDir state
  cards at `src/test/resources/roborazzi` AND the README suite's
  CWD-relative explicit-path captures at `apps/gallery/roborazzi`), the
  record pass and the `roborazzi-baselines` artifact all cover both
  dirs --- both are verify baselines once committed, and F-34 closes
  with this round's committed PNGs.
- **[gallery] Indexing diagnostics (the ml ring finally speaks)**: both
  queue workers mirror every session-gate failure, every retry (with
  attempt count and cause) and every batch outcome into the
  diagnostics ring (`DiagLog`), and the r47 uncaught-fault catches gain
  the same ring lines --- the "indexing fails, no logs" dogfood shape is
  no longer possible.
- **[docs] `docs/ci-workflows.md`'s full-inventory table gains its
  missing `screenshots.yml` and `reproducible-build.yml` rows.

### Fixed

- **[gallery] The concurrent-indexing engine teardown (the "indexing are
  failing" root cause)**: the native ML engine and its store are
  PROCESS-GLOBAL singletons, but both queue workers tore them down in a
  `finally` at every batch boundary --- the face and semantic chains run
  CONCURRENTLY, so whichever finished a batch first ripped the engine
  out from under the other with `IllegalStateException("engine is not
  initialized")`, failing BOTH queues within minutes (the r47 honesty
  round surfaced this cause class; this round removes the cause). The
  per-run teardown is removed (both init calls are idempotent per half,
  so the next run reuses or re-opens the resident engine). The UI
  adapters' close()-while-indexing teardown remains, recorded as F-38.
- **[gallery] The unreadable-row queue kill**: the workers' `readBytes`
  seam threw (`FileNotFoundException` / `SecurityException` /
  `IOException`) for stale or locked MediaStore rows instead of
  returning null as the queue's TESTED "unreadable -> skip -> stays
  queued" contract promises --- and candidates run newest-first, so a
  just-deleted photo was the likeliest FIRST victim, burning all
  retries and failing the whole queue. The seam is now
  `runCatching { ... }.getOrNull()`, restoring the tested contract.
- **[gallery] The self-cancelling re-enqueue**: both queue schedulers
  used `ExistingWorkPolicy.REPLACE` for the worker's own successor
  enqueue --- REPLACE deletes the pending unique work, INCLUDING THE RUN
  DOING THE ENQUEUEING, turning every batch boundary into a
  cancel-vs-successor race. Both now use `APPEND_OR_REPLACE`.
- **[docs] The stale "Phase 6 prototype" CI label**: `ci.yml`'s editor
  build step carried "(Phase 6 prototype)" (and the settings.gradle.kts
  comment "Edit prototype APK") since r29 --- the editor shipped as a
  release deliverable in the both-APK close-out; the labels now say
  what the gate builds. The README's build commands were equally stale
  (the r46 flavor matrix removed the unflavored `assembleDebug`-family
  task names --- now `assembleBasicDebug` et al., with the flavor
  note), the repository-layout tree named a nonexistent
  `edit-extension/` directory and phantom `shared/` modules, and the
  opt-in-builder list was missing `modelpack-apk.yml`.

(The r47 round --- the plugin-first Roborazzi parity, the engine label,
the UI/UX sweeps, the indexing honesty catches --- is recorded verbatim
in its own Unreleased section below; r48 builds on it.)

## [Unreleased] — the r47 round (versionCode 38 unchanged; gallery `0.4.5-alpha`)

### Added

- **[gallery] The indexing-failure honesty fix (both AI queues)** (the
  2026-11-05 device-screenshot round): the engine build's Settings cards
  reported the circular "Indexing failed: indexing failed" /
  "Semantic indexing failed: semantic indexing failed" — the cause-less
  FALLBACK literals, which could only mean the workers reached FAILED
  with EMPTY output data (an uncaught throw escaping `doWork`). Root
  cause of the escape found at two seams: (a) `MlEngine.openFaceIndex`
  does not only return false — the JNI `nativeStoreOpen` THROWS
  `IllegalStateException` with the Rust message and the worker's
  false-return guard never saw it (one failing `ml-index.sqlite` open
  breaks BOTH queues, exactly the field evidence); (b) the setup-phase
  calls (the catalogue read, the state-file load, the r24/r26 JNI
  indexed-list reads) had no catch at all. Both workers now carry a
  seam-named catch on the store open ("face index store did not open:
  \<engine cause\>") plus a whole-body catch-all with the same
  retry-then-fail-with-cause shape the mid-run guard already had
  (`uncaught \<class\>: \<message\>`), CancellationException rethrown;
  the fallback literals now say "worker ended without a message
  (uncaught termination)" instead of parroting the failure label. The
  next dogfood run surfaces the real native cause on the card itself.
- **[gallery] The Roborazzi pipeline goes plugin-first (toadFILES
  parity)** (branch `tg-r47-a-roborazzi`): the
  `io.github.takahirom.roborazzi` gradle plugin (1.77.0) is now applied
  to `:apps:gallery`, matching the toadFILES repo's setup — the r46-era
  `srcDir(Any)` script-classpath breakage was re-verified as already
  rewritten into the AGP-9-safe plain-`File` form, so the r46
  "deliberately absent" escape hatch is retired. Capture output is
  pinned to `src/test/resources/roborazzi` (the committed-baseline
  location). `screenshots.yml` gains a `mode` input (record/verify,
  default verify): with no committed baselines it records and uploads
  the `roborazzi-baselines` artifact with a loud honest-skip notice;
  once baselines are committed the `verifyRoborazziBasicDebug` gate
  self-activates — no workflow edit needed (F-34 progressed).

### Changed

- **[gallery] The engine flavor's launcher label now reads
  "toadGALLERY"** (branch `tg-r47-b-label`): the
  full-engine build drops the "toadGALLERY Engine" suffix — the
  `.engine` applicationIdSuffix already keeps it a separate install
  beside the basic/semantic/face builds, so the user-visible name is
  the canonical "toadGALLERY" for the engine build too. The
  semantic/face flavors keep their suffixed labels (unify the same
  way on request); no applicationId, versionCode, or build-config
  change.
- **[gallery] The UI/UX flaw round (gallery core)** (branch
  `tg-r47-c-uiux-gallery`): the viewer's top bar no longer pushes
  Rename/Delete off-screen on 360dp phones (Edit/Rename/Delete moved
  behind an overflow menu; all test tags preserved); the viewer counter
  no longer wraps to a vertical pile; the collage pending-slot
  placeholder reads `surfaceVariant` instead of a hardcoded light gray
  (dark-mode flash fixed); album covers get an error painter
  (`ColorPainter(surfaceVariant)`) so corrupt covers are honest, not
  blank; the vault setup/unlock bodies consume the IME inset
  (`imePadding()`) so the keyboard no longer covers the PIN UI; the
  trash confirm dialog's confirm button joins the error-colored
  destructive idiom; collage template cards use `ToadShapes.small`.
- **[edit] The editor family theme (the purple is gone)** (the
  2026-11-05 device-screenshot round): both editor surfaces rendered
  with the Material 3 BASELINE PURPLE scheme — their `setContent`
  wrapped content in a bare `MaterialTheme {}` with no colorScheme, so
  the library default leaked through every accent role (sliders, chips,
  the Save-copy pill), reading as a different app family next to the
  toad-green gallery. The new self-contained `EditorTheme` applies the
  gallery's toad-green dark palette (the issue-#11 token values,
  deliberately duplicated — the edit app stays a zero-dependency
  sibling of the gallery, so no shared theme module). Deliberately NOT
  dynamic-color: the editor is a fixed-dark surface over the photo
  canvas by design, and a wallpaper-derived scheme could re-introduce
  any hue (purple included); the family accent is always the toad green
  here.
- **[edit] The UI/UX flaw round (edit app)** (branch
  `tg-r47-d-uiux-edit`): both editor surfaces gain edge-to-edge inset
  handling (`enableEdgeToEdge` + statusBars/navigationBars padding) —
  targetSdk 36 enforced edge-to-edge on API 35+, which previously drew
  the top bar under the status bar; status-bar icons now stay light
  over the always-dark canvas; the color swatch tap target rises to the
  48dp floor; the HSL band chips freeze during export like every other
  control. Logged for a later round: mid-export cancel on the photo
  surface (needs export-runner plumbing), the rotate icon (needs
  material-icons-extended), the edit app's baseline color scheme.

## [0.4.3-alpha] — unreleased (versionCode 35; gallery `0.4.3-alpha`)

### Added

- **[gallery] The ente-port exit round (r39): Phase 5's last two
  code-side items** (branch `tg-r39-ente-exit`, `docs/phase-5/ente-exit.md`):
  - **Semantic-only engine sessions** — the r26 Magic Search round's
    documented FACE-model requirement is dropped: the JNI bridge's
    `nativeInitSemantic` installs a SEMANTIC-ONLY session from scratch
    (the vendored runtime's own blank-path configuration shape),
    `nativeInit` upgrades such a session with the face half in place,
    face calls on a semantic-only session fail with a dedicated
    honest message, and the engine info line names all three session
    shapes. The Kotlin half: the adapter (now its own file, the
    PeopleEngineSeam/Adapter convention) opens `initSemantic ->
    openFaceIndex` with no `initialize`, the semantic background
    queue's gates drop the FACE capability step, and the six-state
    policy is renamed `SemanticContentPolicy` (shared by Magic Search
    and Things) with the FACE input removed. Magic Search and the
    semantic queue now run on the SEMANTIC pack alone (~213 MB, no
    ~36 MB FACE requirement).
  - **Things dynamic collections** — named semantic collections
    ("Dogs", "Seaside") over the SAME on-device CLIP index Magic
    Search queries: a new distance-cut engine search
    (`nativeStoreSearchClipsWithin`, upstream's own vecdb
    `max_distance` plumbed through the bridge), the pure membership
    planner at a round-pinned 0.70 cosine cut, `things-state.json` in
    the backup-excluded face-index directory (the atomic
    people-state.json discipline), the Albums tab's Things section
    (the People/Places sibling with the shared policy's honest cards,
    the create dialog, per-chip remove), the `things/{collectionId}`
    detail screen (ranked members, the shared viewer hand-off,
    rename/edit-query/delete), and the `thingsAlbumEnabled` consent
    row (default OFF, ADR-0010). Zero new permissions, zero new
    dependencies, zero new network paths.

### Changed

- **Roadmap restructure (maintainer directive, 2026-11-04)**: Phases 8
  (Edit v3 on-device AI editing) and 9 (lightweight video editor) are
  SKIPPED and their scopes fold into Phase 10 (hardening + the two
  deferred sub-scopes). See `roadmap.md` v2.12.

## [0.4.2-alpha] — unreleased (versionCodes 27–34; per maintainer
  directive no new device builds until Phase 5 completes — **the Phase 5
  release freeze is lifted as of the r28 exit gate**; the next release
  may proceed. F-6 closed 2026-11-04: the two duplicate 0.4.2-alpha
  headings from the r29-era addition pattern are merged into this one)

### Added

- **[toadGALLERY Edit] The frames — the single mounting, five
  project-authored parametric frames, the shared compositor last; plus
  the undo/redo extension capstone** (r38 round, Phase 7 layer type 5
  of 5, the campaign's closing; branch `tg-p7-frames`): one frame at a
  time over the photo (a frame is a mounting, not a stackable), chosen
  from five authored parametric frames (Plain, Inner line, Double line,
  Film with punched sprocket holes, Polaroid) as pure integer geometry
  (the thickness is the r37 kernels' formula `max(1, widthPct ×
  minEdge / 1000)`; the width slider 1..100, the colour swatches the
  r34 palette) or imported as PNG/WebP (`ImportedFrameStore`:
  magic-before-write, verbatim bytes, collision-free ids; an imported
  frame stretches to the full output canvas with the honest
  no-controls note). The recipe's `frame` is the `lut` object's
  tolerance shape: a pre-r38 sidecar loads clean (proven with one
  carrying ALL FOUR earlier containers, the round-five stacking proof);
  an unresolvable frameId round-trips verbatim and degrades to no
  frame. The `FrameCompositor` draws LAST (the mounting over
  everything). The undo/redo extension is now test-pinned:
  `UndoRedoExtensionTest` walks a mixed five-family session (ten
  commits, undo to launch, redo forward, the exact recipe at every
  step). 32 new JVM tests (the edit module at 374; the local harness
  ran 365 pure green pre-push); the module string count 122 → 129;
  editor `versionCode 11` (`0.4.6-edit-alpha`). This round also lands
  the r37 UI-section debt fix (the r37 insertion script never wrote the
  activity back; the merged r37 carried everything but the surface ---
  the recorded correction in the r37 delivery record). The on-device
  pass rides checklist §27; the delivery record is
  `docs/phase-7/frames.md`.

- **[toadGALLERY Edit] The mosaic/blur regions — the pixel-stage break,
  the additive RegionLayers recipe, the final pipeline stage** (r37
  round, Phase 7 layer type 4 of 5, branch `tg-p7-mosaic`): paint a
  region over the photo; the region gets a mosaic (block average,
  origin-aligned cells) or a linear blur (horizontal box window) at a
  strength — applied to the FULLY graded pixels as the pipeline's FINAL
  stage, after sharpen (no downstream stage re-processes the censored
  output). Grid-relative like the vignette: exact in the export (where
  the stage grid IS the output canvas), approximate in the preview
  under an active crop — the r30 characteristic, stated honestly. The
  recipe's `RegionLayers` is additive like the family contract: a
  pre-r37 sidecar loads clean — proven with one carrying r34 text +
  r35 stickers + r36 strokes, the round-four stacking proof; an unknown
  `kind` is corrupt data and the layer is skipped, never a crash;
  points tolerate like the stroke family's (2-element arrays,
  malformed entries dropped, survivors clamped, the count capped at
  512, thinned at commit by the same stride rule). The pure
  `RegionMask` is an even-odd scanline fill (pixel centres strictly
  inside; the triangle and the self-crossing bowtie pinned by exact
  masks, the bowtie's pinch empty); `RegionEffects` is
  integer-deterministic (the mosaic's per-cell reads-before-writes; the
  blur's sliding window over a snapshot, pinned equivalent to the
  per-pixel loop). The UI: the second paint mode (mutually exclusive
  with Draw mode), the kind chips + strength slider under the paint
  duality, the draft region previewing AT PAINT TIME through the same
  stage the export runs, and the selection unification extended to the
  fourth family. 26 new JVM tests (the edit module at 340; the local
  harness ran 333 pure green pre-push); the module string count
  114 → 122; editor `versionCode 10` (`0.4.5-edit-alpha`). The
  on-device pass rides checklist §26; the delivery record is
  `docs/phase-7/mosaic-regions.md`.

- **[toadGALLERY Edit] The freehand drawing tool — stroke-per-layer, the
  additive DrawLayers recipe, the shared compositing pass between
  stickers and text** (r36 round, Phase 7 layer type 3 of 5, branch
  `tg-p7-drawing`): freehand strokes over the photo — one gesture = one
  layer = one undo snapshot, per-layer colour (the r34 swatch palette),
  width (1..100, percent of the output-canvas min-edge), opacity, and
  smoothing (`round(pct/25)` → 0..4 Chaikin corner-cutting passes at
  place time, endpoints preserved — exactly test-pinned; a tap paints a
  dot of the brush width). Points are fractions of the output canvas,
  thinned at commit to the 512-point cap by uniform stride (first/last
  always kept, deterministic). The recipe's `DrawLayers` is additive
  like the family contract: a pre-r36 sidecar loads clean — proven with
  one carrying r34 text AND r35 stickers, the round-three stacking
  proof; malformed point entries are dropped, survivors clamp, a layer
  whose points all fail is skipped. Strokes composite in OUTPUT space,
  BETWEEN the sticker pass and the text pass (annotation over the scene
  objects, captions above the annotation), through the ONE shared
  `DrawCompositor` the preview and the export both call. The UI: the
  Draw-mode chip, stroke layer chips, the brush controls under the
  exclusive duality (paint mode tunes the NEXT stroke; a selected layer
  re-binds the same controls), the paint gesture on the preview, and
  the ONE `sweepPendingDrafts()` helper across the families (12 call
  sites retrofitted). 27 new JVM tests (the edit module at 314; the
  local harness ran 305 pure green pre-push, catching two
  TEST-expectation slips and one parenthesised backticked JVM name);
  the module string count 104 → 114; editor `versionCode 9`
  (`0.4.4-edit-alpha`). The on-device pass rides checklist §25; the
  delivery record is `docs/phase-7/drawing-tool.md`.

- **[toadGALLERY Edit] The sticker tool — eight project-authored
  in-code vector shapes, the additive StickerLayers recipe, the shared
  compositing pass below text** (r35 round, Phase 7 layer type 2 of 5,
  branch `tg-p7-stickers`): sticker layers over the photo — per-layer
  sticker id, size (percent of the canvas min-edge), opacity, rotation,
  position (the r34 drag gesture). The bundled set is AUTHORED CODE, not
  image assets: eight shapes (Heart, Star, Smiley, Speech bubble,
  Lightning, Flower, Location pin, Crown) as integer-coordinate op
  lists in a 0..100 design box, fills only (`BundledStickers`; the
  authorship record `docs/phase-7/bundled-stickers.md`; no §5 ledger
  row because no file was copied from anywhere — the spot-coordinate
  tests pin the authored trigonometry). The recipe's `StickerLayers` is
  additive like the family contract: a pre-r35 sidecar loads clean
  (proven with one carrying r34 text layers), an unresolvable
  `stickerId` round-trips verbatim and renders NOTHING — there is no
  default sticker to degrade to; a blank stickerId is a skip alongside a
  blank id. Stickers composite in OUTPUT space, BELOW the text pass
  (scene objects under captions), through the ONE shared
  `StickerCompositor` the preview and the export both call (vectors from
  the op list scaled into the size box; imported bitmaps FIT inside it
  preserving aspect). `ImportedStickerStore`: the SAF PNG/WebP import
  with magic-before-write, verbatim bytes under the magic's extension, a
  2 MB cap, derived collision-suffixed ids that can never shadow the
  bundled set; the directory listing is the index. The UI: the registry
  chip row as ADD actions, layer chips, the r32/r34 a11y conventions,
  remove, drag-to-position, and ONE selected layer at a time across the
  families (selecting a sticker clears the text selection and vice
  versa; the draft-sweep set extended across the families). 46 new JVM
  tests across StickerLayersTest, StickerLayersRecipeTest,
  BundledStickersTest, ImportedStickerStoreTest (executed green pre-push
  by the new local kotlinc harness — the first round in the campaign
  with tests run before CI); the module string count 94 → 104; editor
  `versionCode 8` (`0.4.3-edit-alpha`). The on-device pass rides
  checklist §24; the delivery record is `docs/phase-7/stickers.md`.

- **[toadGALLERY Edit] The text tool — OFL fonts, the additive TextLayers
  recipe, the shared compositing pass** (r34 round, Phase 7 layer type 1
  of 5, branch `tg-p7-text-tool`): styled text layers over the photo —
  per-layer text, font (four verbatim-bundled SIL OFL 1.1 faces: Fira
  Sans, Playfair Display, Caveat, Space Mono — each `OFL.txt` beside its
  TTF, sha256-pinned by `BundledFontsTest`, provenance in
  `docs/phase-7/bundled-fonts.md` + the §5 ledger; the prep doc's Noto
  Sans candidate swapped for Fira Sans: Noto's only unmodified full form
  is a 2.0 MB variable over the round's whole budget and the CSS API
  serves latin subsets), size (percent of the canvas min-edge), color
  (eight named swatches), opacity, rotation, position (drag on the
  preview, 48dp grab radius). The recipe's `TextLayers` is additive like
  r33's `LutSelection`: a pre-r34 sidecar loads clean, an unresolvable
  fontId round-trips verbatim and degrades to the default face at
  render. Text renders LAST — a compositing pass after the whole pixel
  pipeline (geometry → … → sharpen), anchors as fractions of the OUTPUT
  canvas, preview and export through the ONE `TextCompositor` (preview ≡
  export by construction, test-pinned). Undo/redo rides the existing
  snapshot stack with per-commit granularity (sliders release, the text
  field commits on Done/switch/add/remove/export sweeps — never per
  keystroke). 32 new JVM tests across TextLayersTest,
  TextLayersRecipeTest, BundledFontsTest; the module string count 74 → 94
  (the r33 recount debt settled); editor `versionCode 7`
  (`0.4.2-edit-alpha`). The on-device pass rides checklist §23; the
  delivery record is `docs/phase-7/text-tool.md`.

- **The in-app progress screen** (F-1, follow-up 2026-11-04): the
  repo-root `progress.json` — the same source of truth the live sandbox
  dashboard reads — is mirrored into the APK assets at build time
  (`copyProgressJson`; the model-pack srcDir pattern) and rendered by
  the new Settings → Development progress screen: the two INDEPENDENT
  scopes (overall project, Ente port workstream) as
  `LinearProgressIndicator`s with their own stored percents + phase
  lists + per-scope source-of-truth lines + the honest
  build-time-snapshot note (the app never fetches progress — zero
  uninvited egress). Pure `ProgressSnapshot` parse (org.json, no new
  dependencies) + 7 JVM tests. Gallery `versionCode 34`.

- **The toad-setup composite action** (F-2, follow-up 2026-11-04): the
  toolchain setup blocks that `ci.yml` (×3 jobs), `engine-apk.yml`,
  `modelpack-apk.yml`, `apk-build-engine.yml`, `editor-apk-build.yml`
  and `release.yml` replicated verbatim now live in ONE place —
  `.github/actions/toad-setup` (rust: none/host/android × android:
  true/false; identical pins and cache keys, so main's warm caches
  restore unchanged; −218 lines net). actionlint 1.7.12 PASS on all
  six workflows + the composite.

- **Progress-tracking infrastructure + the dispatch-only debug-APK builders**
  (2026-11-04 workplan window, `docs/workplan.md`): the committed
  `progress.json` (independent `project` 73% / `entePort` 95% scopes),
  `docs/ci-workflows.md` (workflow quick reference), `follow-up.md` (the
  follow-up register), and two new workflows --- `apk-build-engine.yml`
  ("APK build with engine": input-driven engine APK with pin validation,
  mapping/log artifacts, fail-fast secrets guards) and
  `editor-apk-build.yml` ("Editor APK build --- include all": all editor
  targets in one matrix). Both are `workflow_dispatch`-only and were
  validated by `actionlint` + static review only --- not dispatched.
  Docs only; no app code changed in this window.

- **The Phase 5 hardening round + exit gate** (r28 round; Workstream 8,
  `docs/phase-5/hardening.md`): the proof workstream that lifts the
  Phase 5 release freeze. **The egress proof** — `EgressSolePathTest`
  pins `https://models.ente.com/` as the app's SOLE network path by
  scanning the whole production tree for every surface that could open
  one: the only quoted URL literal is the About screen's
  `ACTION_VIEW` repository link (the browser connects, the app never
  does), `java.net` imports are confined to `HttpModelDownloader.kt`, no
  network client library and no raw socket/DNS API exists, the manifest
  carries no cleartext or network-security-config escape hatch, the
  INTERNET allowlist is pinned to exactly the one ADR-0020 entry, and
  the Gradle merged-manifest gates
  (`gateDebugInternetPermission`/`gateReleaseInternetPermission`) stay
  wired with their allowlist input and assemble/bundle/check anchors.
  `HttpModelDownloaderRedirectTest` drives the full 301/302/303/307/308
  fail-closed matrix with never-follow single-request proofs
  (cross-host absolute Locations, same-URL loops, Location-less
  redirects). **The three-gate vault exclusion** — one focused suite per
  Phase 4 audit gate: `FaceIndexQueueVaultExclusionTest` (enqueue: the
  vault-move model end-to-end over the real in-memory reference
  catalogue — a moved photo is unrepresentable as a candidate, and a
  previously-indexed one's face rows are orphan-DELETED before any
  inference), `VaultExclusionReadGateTest` (pre-byte-read: the
  `EligiblePublicMedia` type gate, the read-time permission re-check,
  the gate-before-handle ordering, the refuse-whole 64 MiB cap) and
  `PeopleVaultExclusionPersistenceTest` (pre-persistence: the all-vault
  cluster resolves to no photos, stale names prune, hide never mutates
  the store, and the face-index directory's backup/transfer exclusion
  is pinned in both rule files). **The 10k/50k library benchmarks** —
  `LibraryScaleBenchmarkTest` over seeded synthetic libraries: timeline
  and folder window reads, the `WindowPagingSource` walk, the
  `CatalogueReconciler` diff with realistic churn and the filter
  projection, with generous-but-meaningful time budgets (30–150×
  expected cost; an O(n²) at 50k rows fails every budget) and the
  measured CI numbers recorded in the hardening doc. **The low-memory /
  ABI matrix** — documented in `docs/phase-5/hardening.md` §4 (paging
  windows, the 64 MiB refuse-whole cap, decode planning, one-JPEG-at-a-
  time queue batches, in-memory vault thumbnails, Room-not-RAM
  catalogue; the per-build ABI matrix with the honest 32-bit-device
  statement) and pinned by `EnginePackagingPolicyTest` (the
  withEngine/withBundledModels build gates, the default arm64-v8a
  discipline, the pinned NDK + sha256-verified ONNX Runtime AAR, the
  16 KB page-size link flags for all three Android targets, the SDK
  envelope). **The ADR-0020 offline model pack** (the retained second
  half of the delivery decision): `BundledModelAssets` +
  `BundledFirstModelDownloader` — bundled model bytes first, the
  ordinary HTTP transport as fallback, the shared structural name guard
  before the asset lookup, and the provisioner's
  verify-then-stage-then-commit discipline unchanged (a tampered pack
  fails the SHA-256 pin and installs nothing) — wired into the app's
  provisioner, gated behind the `-PwithBundledModels` Gradle property
  (build-dir assets srcDir; a default build is byte-for-byte
  unchanged), and built by the new branch-scoped additive
  `modelpack-apk.yml` workflow (the `engine-apk.yml` pattern:
  `tg-modelpack-*` branches, the pinned FACE pack fetched from
  `models.ente.com` and size+SHA-256 verified at build time, gates on
  the native libraries, the `assets/models/` presence and the APK
  identity, a 14-day-retention artifact; no tags, no GitHub Releases).
  53 new JVM tests (927 → 980); versionCode 32; versionName stays
  0.4.2-alpha (the freeze lift is a records update; the next release
  tags the whole 0.4.2-alpha line, codes 27–34).

- **[toadGALLERY Edit] The LUT `.cube` filter system** (r33 round,
  Phase 7 opener, branch `tg-p7-lut-engine`): a Filters section with
  five project-authored looks — Warm Film, Cool Chrome, Silver Mono,
  Matte Fade, Vivid Punch — each generated by the committed
  deterministic generator (`tools/gen_bundled_luts.py`; no third-party
  LUT data, the licensing record is `docs/phase-7/bundled-luts.md`)
  and an intensity slider that previews live and commits on release.
  Under it: the pure Adobe `.cube` parser (honest subset — `LUT_1D_SIZE`
  refused, `RANGE_*` accepted-and-ignored, degenerate domains rejected,
  exactly N³ finite R-fastest triplets), an allocation-free trilinear
  sampler, an alpha-preserving intensity blend, the recipe's additive
  `LutSelection` (a pre-r33 sidecar loads clean; an unresolvable look
  id round-trips verbatim and degrades to no-look, never a crash),
  pipeline stage 10 between the HSL bands and the spatial effects, and
  the ONE shared look cache so the preview and the export are
  byte-identical by construction. **The user-import flow completes the
  feature**: an "Import look…" chip (SAF document picker — the
  zero-network editor reads local files only) whose picks are validated
  before anything is written, stored verbatim with derived
  collision-free ids that can never shadow the bundled set, shown in
  the merged chip row, and removable with a per-import "Remove look"
  affordance. Editor `versionCode 6` (`0.4.1-edit-alpha`); 51 new JVM
  tests (the edit module at 209); the on-device pass rides checklist
  §22.
- **[toadGALLERY Edit] The accessibility + hardening pass** (r32 round,
  riding the r31 exit branch `tg-p6-edit-v1-exit`): every editor slider —
  the 10 adjustment sliders, straighten, export quality and the three HSL
  band controls — now announces its NAME and live VALUE to TalkBack
  (semantics `contentDescription` + `stateDescription`; the HSL rows
  speak "BAND: CONTROL" so the selected band is always spoken); the flip
  toggles are proper FilterChips (48dp touch targets, state highlights,
  selected/not-selected announcements) instead of a text label
  overflowing an IconButton and a sub-48dp clickable text; the straighten
  slider follows the drag live (thumb + preview, like every other
  slider, dropping a wasted per-tick re-render) and displays fractional
  sidecar values exactly; and the crop presets commit through the new
  tested pure inverse `CropRect.fromPixelRect` (5 JVM tests). Editor
  `versionCode 4` (`0.3.1-edit-alpha`).
- **[toadGALLERY Edit] The Edit v1 exit** (r31 round, the Phase 6
  code-side completion on the r30 core): the **per-export
  metadata-policy choice** — the launch request's policy is the privacy
  floor (contract §4.4 "respected exactly"): the export sheet gains a
  "Metadata for this copy" chip selector that may strip MORE for one
  saved copy (an explicit user opt-in) but can never strip LESS than
  the gallery requested, with the floor-blocked options disabled and
  captioned; the choice never rides the recipe/sidecar (pinned by
  test); the always-visible honesty line tracks the pending choice.
  **The export preflight**: a 48 MP large-file decode guard that
  refuses `ERROR_UNSUPPORTED` before any work (the pipeline holds the
  pixels twice), a generous per-format/quality byte model against
  `StorageManager` allocatable bytes with a 1.5× headroom (advisory;
  the failure rides `ERROR_IO` so the frozen v1 contract error-code
  set stays untouched), and the documented process-death policy (Q+
  pending rows are invisible and MediaStore-reclaimed, the sidecar
  restores the session; pre-Q may leave a user-removable partial row).
  **The applied-policy honesty narration** (gallery side): additive
  `SavedCopy.removesLocation` / `stripsAllMetadata` triggers in
  `:shared:editor-contract` choose the saved-copy snackbar — "location
  removed" / "metadata stripped" / plain — so the result echo the
  editor already sends is finally shown (contract §5), a pre-r29
  editor's missing echo making no claim. **Also fixed**: invalid
  interior `---` inside the editor's `strings.xml` comments (strict
  XML forbids `--` in comments — a latent break for the edit app's
  first CI compile). 25 new JVM tests (9 policy + 12 preflight + 4
  contract); the editor versions independently: `versionCode 3`,
  `0.3.0-edit-alpha`. The physical exit half (§19 round trip + the new
  §20 export robustness matrix) is checklist-ready, human-only.

- **[toadGALLERY Edit] The Edit v1 core** (r30 round, the Phase 6
  continuation on the r29 gate): the non-destructive pipeline's full v1
  state. **The ADR-0011 recipe**: one `EditRecipe` data class carries the
  whole session (geometry + tone + colour + grade + detail,
  range-validated), serialized by an additive-versioned codec (unknown
  keys ignored, out-of-range clamped, malformed JSON a structured failure)
  into an app-private, SHA-256-keyed, atomic-write sidecar store — saved
  on every committed change, restored on relaunch, cleared on export.
  **The pure stages** (all JVM-tested, zero states exact): the geometry
  toolset (normalized crop rect + aspect presets, flips, straighten on an
  expanded canvas with bilinear sampling and transparent fill, the quad
  perspective correction), the composed LUT (exposure, brightness,
  contrast, smoothstep-masked highlights/shadows), the full-pixel stage
  (saturation, temperature, tint), eight-band per-channel HSL
  (cosine-hat weights, achromatic pixels never drift), radial vignette,
  and a 3×3 unsharp mask. **`EditPipeline`** runs them in one documented
  order (preview and export agree by construction) with
  `EditGeometryPlan` sharing the pre-crop frame math. **Honest export
  control**: JPEG (quality slider), PNG (lossless), WebP — the encodable
  set; the platform Bitmap has NO HEIC encoder, so HEIC is never offered
  (decode-only sources save in the user's chosen format). The r29 rotate
  quarter-turn mapping swap (turn-1/turn-3) is fixed. The editor app
  versions independently: `versionCode 2`, `0.2.0-edit-alpha`.

- **The Gallery ↔ Edit contract gate** (r29 round, the Phase 6 entry gate
  — Issue #14 closed): the versioned editor integration contract
  (ADR-0002 / ADR-0007) became code in three pieces. **(1)**
  `:shared:editor-contract`, a new pure-JVM module both apps consume —
  the frozen v1 constants, the exact editor identity match (release ID +
  the documented `.debug` dogfood suffix), the additive version
  negotiation, the shared request validator (`content://`-only source
  URIs, `save_copy`-only v1, total metadata-policy parse) and the total
  result parser (all three terminal states; unknown codes degrade to
  structured `ERROR_INTERNAL`, never a crash). **(2)**
  [toadGALLERY Edit] `:apps:edit`, the separate installable editor
  prototype APK (`io.github.dukeoftoad.gallery.edit`, its own version
  line starting at `0.1.0-edit-alpha` / versionCode 1): the exported
  contract activity (JPEG/PNG/WebP/HEIC), a REAL brightness + contrast
  LUT adjust with left rotation, live bounded preview, draft sliders and
  a 64-deep undo/redo stack, and the non-destructive save-copy export —
  MediaStore INSERT of a NEW row only (IS_PENDING on Q+, cancelled on
  failure so no partial entry survives), the EXIF metadata policy
  (preserve / strip location / strip all) through
  `androidx.exifinterface`, and the source URI read-only by
  construction. Zero network, enforced by the editor's own
  merged-manifest INTERNET gate — stricter than the gallery's (no
  allowlist mechanism exists in the editor; contract §4.5). **(3)** The
  gallery half: exact-match editor resolution with version refusal and
  the disabled state, the viewer's new Edit op (image pages), the
  third-party ACTION_EDIT fallback chooser for the not-installed state,
  the `<queries>` visibility block, and refresh via MediaStore
  observation — no rescan. CI gains the editor's assemble/lint/tests +
  the contract module's JVM suite + the `edit-debug-apk` artifact; a
  manifest-conformance suite pins the editor's contract surface on the
  JVM; the physical round trip + original-untouched hash compare join
  the device checklist (§19).

- **The MI organisational UX surfaces** (r27 round: Places, Moments,
  Stories, the collage creator, the slideshow): five product surfaces
  over the existing Room catalogue — zero new permissions, zero engine
  changes, zero reverse geocoding, every feature a pure JVM-tested core
  plus a thin Android seam. **Places**: offline coarse region bucketing
  over the catalogue's persisted GPS coordinates — the pure
  `PlacesRegionGrid` (12x6, one cell per 30°, closed-form deterministic
  bands with pole-side band edges, first-appearance newest-first region
  order, antimeridian-normalized means, the exact ±180 meridian ONE band
  from both directions) with `PlacesRegionLabels` coordinate-band labels
  (ISO-6709-style pure math; no place name is ever invented, fetched or
  guessed), the pure `PlacesContentPolicy` (the r14 consent display gate
  decides FIRST — consent off shows the consent state even over
  persisted rows — then the honest empty-with-footnote, then Ready), the
  `PlaceMediaHandoff` one-shot tap hand-off (nav argument as the durable
  fallback), the `PlacesScreen` region grid and `PlaceDetailScreen`
  media grid (viewer hand-offs the map-sheet way), and the Albums-tab
  Places section. Vault/trash exclusion is inherited: the surfaces read
  the catalogue's live-rows `geotaggedRows` projection only. **Moments**:
  the `GroupBy.WEEK` grain of the grouped timeline — the pure
  `MomentGrouper` (ISO-8601 weeks, Monday-anchored, week-based-year keys
  so a 2 January can be week 53 of the year before) with
  `buildTimelineRows`' WEEK branch as a thin projection (the DAY/MONTH
  streaming implementations untouched; DAY equivalence pinned by tests),
  counted headers via `headerCellCounts`, and the FlowRow group-by chips.
  **Stories**: the `StoriesShelf` on the Photos tab — recent time
  clusters the pure `StorySets` engine derives from the SAME paging
  snapshot the grid renders (36h gap rule, min 3 members, cap 8
  most-recent, zero persistence); playback IS the existing viewer with
  the slideshow armed. **The collage creator**: the pure `CollageLayouts`
  (17 grid-variant templates for 2..9 photos, exact tiling pinned
  test-by-template; `slotPixels`' single rounding rule and
  `centerCropSource`'s center-crop math shared by the Compose preview
  and the exporter, so what the preview shows is what the export saves),
  the `CollageScreen` template picker, and the Q+ MediaStore exporter
  riding the file-ops naming/pending-row discipline ("Collage.jpg",
  "Collage (2).jpg", …; a failed export deletes its pending row).
  **The slideshow**: the pure `SlideshowTiming` (3/5/10-second catalog,
  nearest-snap with ties to the shorter option, wrap-around advance) and
  `ViewerCrossfade` cores, the shell-scoped armed launch (Stories shelf,
  the Photos header action, the album detail action), the viewer's
  start/stop toggle plus the in-session interval menu, and crossfade
  page alphas while a run is active; leaving the viewer always stops the
  run. 84 new JVM tests (963 → 1047 on the rebased base): the
  `MomentGrouperTest` / `StorySetsTest` / `CollageLayoutsTest` /
  `PlacesRegionLabelsTest` / `PlacesContentPolicyTest` /
  `PlaceMediaHandoffTest` / `SlideshowTimingTest` files, the WEEK-grain +
  `headerCellCounts` sections of `TimelineRowsTest`, and the WEEK cases
  of `PlacesRegionGridTest` / `AppearanceTest`.

- **Magic Search**- **Magic Search** (r26 round; Workstream 7 of the Phase 5 plan,
  `docs/phase-5/magic-search.md`): offline semantic search over an
  on-device CLIP index — the Search tab's new **Content** mode. The JNI
  bridge extends from sixteen to twenty-five externals (`nativeInitSemantic`
  — the CLIP half of an engine session as a third open/closed state —
  the CLIP image/text encoders, and a CLIP store surface over the SAME
  `ml-index.sqlite` the faces live in: put (512-dim guard on both sides
  of the bridge), delete-for-files, indexed-file-ids, count, top-K
  vecdb search in the vecdb's own order, and the once-per-drain
  `fillClipIndex` reconciliation pass with its own report — 12 new JNI
  Rust tests). Kotlin side, the semantic background queue as the r24
  face queue's SIBLING: `ClipIndexQueue` (the same three truths, the
  same `MediaRevision.diff` mapping — with the honest caveat that the
  delete-first pass is kept even though `putClip` upserts, so the two
  queues share one diff→action table; ONE whole-photo embedding per
  photo; whole-photo cancellation with `NonCancellable` persistence;
  fill exactly once at drain) and `ClipIndexWorker` (its own unique
  WorkManager chain, FACE-then-SEMANTIC session gates in the honest
  order, self-re-enqueue, bounded retries, honest permanent failures).
  The `ml/search` domain: `MagicSearchEngineSeam` (+ the
  `PeopleEngineAdapter`-pattern production adapter extended with
  `initSemantic`), the pure six-state `MagicSearchContentPolicy`
  (Error dominates; consent before capability; EngineMissing is the
  build shape; ModelsNotProvisioned covers EITHER pack — the r26
  session is face-based, honestly; NotIndexed distinct from Ready),
  the pure query gate (2–200 trimmed characters; over-long queries are
  refused, never truncated), the pure order-preserving/dedupe/limit
  ranking (no distance surfaced in v1), the no-cache repository with
  typed honest outcomes, and the chunked 999-limit MediaStore query.
  The UI: the "Search by" mode row (Name untouched vs Content), the
  debounced sibling `MagicSearchViewModel` whose policy gates render as
  honest cards (consent → Settings toggle with affordance; engine →
  build shape; models → download pointer naming the manifest-derived
  ~213 MB; not indexed → queue pointer), results on the SAME shared
  grid with the same viewer hand-off. Settings: the `magicSearchEnabled`
  consent row (default FALSE, ADR-0010), the SEMANTIC model download
  card (per-asset presence, Download/Delete) and the semantic queue
  card (start/cancel/reset — reset erases the queue's own
  `clip-index-state.json`; the stored index rides the r24 Reset face
  index, stated honestly). Queries never touch the network; the only
  egress is the ADR-0020 model download; vault media can never become
  candidates (three-gate discipline). Rust suite 32 JNI tests (+12);
  JVM suite 982 tests (+55).
- **Edit-preserving reclustering — the cluster-id remapping overlay**
  (r26 round; the People follow-ups, Workstream 6 closeout): user
  edits now SURVIVE the face index's re-clustering passes instead of
  being pruned away. Every clustering pass (each draining
  background-queue run, each threshold change, each model
  re-provision) re-rolls ALL cluster ids (upstream `new_cluster_id` is
  random per pass); the overlay keys edits to the one identity a pass
  cannot re-roll — photo membership (face rows key on MediaStore file
  ids, stable since the r23 put path). `people-state.json` grows three
  additive books inside schema version 1 (an r25 file loads with them
  empty — the upgrade loses nothing): per-edited-cluster membership
  fingerprints (captured at edit time over the same
  `nativeStoreClusterFileIds` read), recorded merge intents
  (`PeopleMerge`: target + the post-merge membership) and accumulated
  remove-from-person corrections. The pure `PeopleReclusterRemap`
  planner maps fresh post-recluster ids onto the preserved state by
  overlap — deterministic (most shared files; ties by bigger cluster,
  then smaller id; name collisions by score then old id; a live id's
  own name always wins), re-folds a merge the pass split back into one
  cluster (majority-descendant rule), re-applies a removal the pass
  re-derived (only the files that actually came back) and refreshes
  the fingerprints to the predicted post-mutation memberships.
  `PeopleRepository` runs the reconcile on every read AND every edit
  (a mid-window edit on a stale UI id is re-keyed through the
  composite mapping); a declined engine mutation aborts the save with
  the old state intact — the next read replans (self-healing, never
  corrupting). Edits without a fingerprint (made before the r26
  upgrade) still drop via the r25 prune — the documented fallback, not
  a silent loss. 100% Kotlin-side over the EXISTING 16-external JNI
  surface: zero Rust changes, the vendored crates stay byte-identical
  to the r23 pin.
- **The unhide surface** (r26 round): the People screen gains a
  hidden-persons section — the pure
  `PeopleContentPolicy.hiddenPersons` mirror of the visible mapping —
  with a per-person Unhide action (`PeopleRepository.unhide`: a pure
  hidden-set flip; no queue reset, names and every other edit intact).
  An unhide failure is an honest one-shot snackbar, never a silent
  no-op. The r25 copy that named the index-erasing "Reset face index"
  as the only way back from a hide (the Person-detail hide dialog, the
  People screen's everyone-hidden body, the Albums tab's all-hidden
  card) was re-worded in place — the Albums card now carries a "See
  all" action straight into the hidden section. JVM suite 963 tests
  (+36: the new `PeopleReclusterRemapTest`, the r26 sections of
  `PeopleRepositoryTest`/`PeopleStateStoreTest`/
  `PeopleContentPolicyTest`).
- **The People auto-album** (r25 round; Workstream 6 of the Phase 5 plan,
  `docs/phase-5/people-ui.md`): the r23/r24 face index gains its product
  surface — an opt-in (ADR-0010, **off by default**) People grid. The
  JNI store extends from twelve to sixteen externals, all riding the
  same open-store state machine and all upstream queries verbatim (zero
  new SQL authored): `nativeStoreFaceClusters` (the live cluster list
  off the LIVE `face_clusters` table — deliberately not upstream's
  `cluster_summary` cache, so manual edits reflect immediately — with
  per-cluster cover file ids from the top-scored face),
  `nativeStoreClusterFileIds`, `nativeStoreMergeFaceClusters` (upstream
  `force_update_cluster_ids`; `source == target` rejected as
  `IllegalArgumentException` on BOTH sides of the bridge) and
  `nativeStoreRemoveFacesFromCluster` (the split primitive, over
  upstream `remove_face_id_to_cluster_id`). Kotlin side, the new
  `ml/people` domain: `PeopleEngineSeam` (the four operations +
  availability; fakes in tests, `PeopleEngineAdapter` in production),
  `PeopleStateStore` (names + hidden in one atomic
  `people-state.json` inside the backup-excluded face-index directory;
  corrupt-recovers to empty; prunes stale cluster ids on load), the pure
  six-state `PeopleContentPolicy` (Error dominates; consent before
  capability; EngineMissing is the build-shape fact; all-hidden is
  `Ready(empty)`, distinct from `NotIndexed`), `PeopleRepository`
  (no-cache live-table reads) and `PeopleMediaQuery` (chunked 999-limit
  `IN` MediaStore selection, newest-first). The Compose surfaces:
  `PeopleScreen` (circular cover cards, name or "Unnamed", honest
  state copy), `PersonDetailScreen` (the person's photo grid with
  multi-select Remove-from-person, Rename, Merge-with and Hide actions;
  taps hand off to the existing viewer the Album-detail way), the
  Albums-tab People section (honest cards when off / engine-missing /
  not-indexed; the person row + "See all" when ready) and the Settings
  `peopleAlbumEnabled` consent row (default FALSE). Honest limitation,
  pinned by a Rust test and the facade KDoc: manual cluster edits
  persist only until the queue's next recluster pass recomputes
  assignments. Rust suite 20 tests (8 new); JVM suite 927 tests (+42).

- **The background face-index queue** (r24 round; Workstream 5 of the
  Phase 5 plan, `docs/phase-5/background-queue.md`): the r23 12-photo
  dogfood slice is expanded into the full-library, WorkManager-backed
  indexing queue. The JNI bridge's store surface gains the maintenance
  pair the queue drives — `nativeStoreIndexedFaceFileIds` (which files
  currently carry face rows, via upstream's own
  `MlDb::face_indexed_file_ids` filtered to the vendored pin's
  `FACE_ML_VERSION`) and `nativeStoreDeleteFacesForFiles` (the per-file
  invalidation delete, upstream's own chunked + transactional
  `MlDb::delete_face_index_for_files`; no new SQL authored) — taking the
  ABI from ten to twelve externals. Kotlin side, the pure queue core:
  `FaceIndexQueuePlanner` derives each run's plan from three truths (the
  catalogue's eligible JPEG candidates with their `MediaRevision`, the
  queue's own recorded states, the store's indexed-file set) using the
  Phase 4 `RevisionDelta` invalidation contract verbatim — UNCHANGED and
  METADATA_CHANGED photos skip (a metadata write never burns model
  inference), CONTENT_CHANGED photos re-index DELETE-FIRST (the r23
  `putFace` ordinals append, so stale rows must go before fresh ones),
  files that left the listing are orphan-deleted, and state records
  without photos are dropped; `FaceIndexQueueProcessor` executes one
  bounded batch per run over the same `FaceIndexEngine` seam (orphan
  deletes first, then at most 50 photos — bytes read one photo at a
  time, never the library — with whole-photo cancellation, state
  persisted under `NonCancellable` on every exit path, and the
  clustering pass exactly once at queue drain, never mid-queue);
  `FaceIndexStateFile` persists the queue's bookkeeping as one
  versioned, atomically-moved JSON file INSIDE the app-private
  face-index directory (reset erases store + bookkeeping together;
  a corrupt file degrades to "nothing processed" — the delete-first
  re-index keeps that correct, just slower). `FaceIndexWorker`
  (CoroutineWorker) binds the real seams — candidates from the Phase 4
  Room catalogue (external-primary volume only, the documented
  multi-volume boundary), bytes through the content resolver, the
  `MlEngine` facade over the JNI store — reports live progress via
  `setProgress`, re-enqueues itself under one unique WorkManager name
  while the plan outlives its batch (process-death resumability through
  WorkManager's own persistence), retries mid-run engine faults with
  backoff (bounded), and fails honestly with the engine's own message
  for permanent shapes (missing library, unprovisioned models). The
  Settings card is replaced in kind: the r23 manual "index + cluster"
  button becomes the r24 queue card — start / cancel / reset, live
  per-batch progress, terminal outcome lines, and the clustering
  distance-threshold tuning control the r23 round flagged for this
  round (0.10–0.40 window, upstream's 0.24 default, persisted in the
  `toad_settings` preferences).
- **WorkManager (androidx.work 2.10.1, Apache-2.0)** — the queue's
  scheduling + process-death persistence layer, the round's one new
  dependency (license-screened in the Phase 1 compatibility doc). Its
  manifest injects four permissions into the MERGED manifest; two are
  accepted (WAKE_LOCK, RECEIVE_BOOT_COMPLETED — neither is a network
  permission; documented in `docs/phase-2/privacy-gate.md`'s r24 note)
  and two are DECLINED via `tools:node="remove"` merger markers in the
  source manifest (ACCESS_NETWORK_STATE — the queue never uses
  network-constrained work, so the connectivity observer is dead
  weight; FOREGROUND_SERVICE — plain background workers only). The
  JVM privacy suite pins both directions: the declared-set test filters
  removal markers out of the pinned-five grant set, and a new dedicated
  test pins the removal set EXACTLY. The face-index directory
  (`filesDir/face-index` — embeddings, cluster assignments, queue
  bookkeeping) is now excluded from cloud backup AND device transfer
  (`backup_rules.xml` / `data_extraction_rules.xml`): the ADR-0020
  manifest note's "embeddings and indexes never cross the network"
  extended to backup/transfer — face-derived data does not leave the
  device.

- **The photos-side persistence stack, vendored** (r23 round; the Phase 5 AI
  engine workstream's third slice, `docs/phase-5/ente-pin.md`): the engine
  workspace gains two crates extracted from `ente/ente` at the SAME pin as
  r21 (`423b2d80d9ec89d45f5680cb1e1d777bac225f41`). `engine/crates/
  ente-vecdb` is the whole upstream `rust/crates/vecdb` vendored
  byte-identical (the vector store the face index searches over);
  `engine/crates/ente-photos-ml` is a partial-crate extraction of the
  upstream `photos` crate's `db`, `ml_db`, `ml_store` and `face_clustering`
  modules, byte-identical except a two-line `use` rewrite: the modules'
  `ente_core::crypto::random_bytes` imports (exactly two call sites) are
  routed onto an engine-owned seam (net-new `src/crypto.rs`) instead of
  vendoring all of `ente-core` for one function — the seam recorded in
  `engine/NOTICE.md`. The r21 self-containment finding is resolved the way
  that round's deferral predicted: the trio's `crate::db::…` imports are
  satisfied by vendoring the host `db` module alongside them rather than by
  re-porting the trio onto a new DB seam. Provenance rows added to the
  licensing ledger §5 (both AGPL-3.0 at the pin).
- **The face-index JNI surface + coordinator** (r23 round):
  `engine/crates/toad-ml-jni` extends its r22 four-function ABI with five
  externals: `nativeDetectFaceEmbeddingsJpeg` (runs detection + embedding
  over one JPEG and returns `N × (5 + 192)` flat row-major floats — per
  face `[x1, y1, x2, y2, score, e0..e191]`, the stride-197 layout, 192-d
  MobileFaceNet embeddings over the r22 detection path) and a face-index
  store surface over the vendored `MlStore` — `nativeStoreOpen` /
  `nativeStorePutFace` / `nativeStoreFaceCount` / `nativeStoreCluster` /
  `nativeStoreClose` — with the store's sqlite backed by bundled rusqlite
  (`<dir>/ml-index.sqlite`, its own migrations), so embeddings persist
  across engine sessions and clustering runs natively; clustering returns
  a compact JSON summary (`faces`/`clusters`/descending `sizes`). Kotlin
  side, `ml/engine/MlEngine.kt` maps the stride-197 flat floats to
  `FaceEmbedding` rows behind the existing facade, and
  `ml/index/IndexCoordinator.kt` orchestrates the round trip
  (detect → embed → putFace → cluster over upstream's
  `RECOMMENDED_DISTANCE_THRESHOLD`, 0.24, quoted from the vendored
  `face_clustering`) as pure JVM-testable logic over an injectable
  `FaceIndexEngine` seam — the Settings "index + cluster (r23 dogfood)"
  slice drives it end-to-end on a device the same way the r22 slice drove
  detection.

### Changed

- **CI**: the `engine` job's check/fmt/clippy gates cover the JNI
  changes automatically (workspace selectors); `cargo test -p
  toad-ml-jni` (12 tests, store-side only — no model files needed) ran
  locally this round and is a natural future CI addition once the
  engine job grows a dedicated step for it. The Kotlin suite grows from
  855 to 885 JVM tests (the planner's invalidation mapping, the
  processor's batch/cancellation/resume contract, the state file's
  round-trip/atomicity/corruption posture, the facade's two new store
  functions, and the two new privacy pins). `versionCode` 27 → 28; no
  packaging change (`-PwithEngine` is exactly the r22/r23 one).
- **CI (r23)**: the `engine` job's check/fmt/clippy gates already cover the new
  crates automatically (workspace selectors, no crate lists), but no step
  ran the vendored tests — it now runs `cargo test -p ente-vecdb -p
  ente-photos-ml` explicitly. `engine/scripts/build-android.sh` needs NO
  change: its `cargo ndk … build -p toad-ml-jni` pulls the new crates as
  workspace dependencies of the bridge, so the `engine-android` job and the
  `-PwithEngine` packaging are exactly the r22 ones (versionCode 27; no
  new device builds per the standing maintainer directive).

## [0.4.1-alpha] — 2026-11-02 (versionCode 26; per maintainer directive no
  new device builds until Phase 5 completes)

### Added

- **The pinned-host HTTP model downloader** (r22 round; ADR-0020's r21-named
  first r22 slice, `docs/phase-5/ente-pin.md`):
  `ml/engine/HttpModelDownloader.kt` — plain `HttpURLConnection`, ZERO new
  dependencies — fetches the pinned model assets from the single allowlisted
  host `models.ente.com`, verified against the live host before the pin (all
  five core assets answer HTTP 200 directly, no redirects, exact pinned
  content-lengths). Fetch URLs are built from parts over a structurally
  guarded file name (path traversal / absolute-URL / query / fragment
  injection is rejected before any socket can exist), redirects fail closed,
  and a `Content-Length` that disagrees with the pin aborts before the first
  byte. The app's first network permission lands with it: the manifest
  declares INTERNET exactly once for this grant (ADR-0020 amends ADR-0014
  for model fetches only), bounded by the merged-manifest gate + allowlist
  protocol, the extended `PrivacyPolicyTest` (permission-set pin,
  host-literal source scan, URL surface for all eleven entries, injection
  matrix) and the instrumented `PrivacyEgressTest` device checks (INTERNET
  granted; non-allowlisted targets refused without a connection).
- **The Android engine bridge + its build plumbing** (r22 round; the second
  r21-named slice): the net-new Rust crate `engine/crates/toad-ml-jni`
  (cdylib `libtoad_ml_jni.so`, a four-function JNI surface — session
  init/info/face-detect-over-JPEG-bytes/release — behind the Kotlin
  `MlEngine`/`MlEngineJni` facade, where a missing library is a graceful
  `LibraryMissing` state, never a crash) links the vendored AGPL engine for
  Android. `engine/scripts/build-android.sh` builds it with cargo-ndk
  (arm64-v8a; `--all` adds x86_64) together with ONNX Runtime's official
  `libonnxruntime.so` (the 1.31.0 Android AAR from Maven Central,
  sha256-pinned in the script — the exact version the ort-sys rc.15 api-31
  bindings expect; load-dynamic makes the runtime library a fetched supply,
  never a cargo artifact), staging both into a build-dir jniLibs
  folder that Gradle packages ONLY under `-PwithEngine` (`buildRustEngine`
  task + jniLibs srcDir; without the property the build is byte-for-byte the
  r21 one). CI gains the `engine-android` job — Android ABI build +
  engine-enabled assembleDebug, verifying both native libraries actually
  land in the APK — and the Settings "AI engine (r22 dogfood)" section drives
  the whole path on a device: download the FACE assets with live per-asset
  presence updates, delete, and a one-JPEG face-detection self-test
  (byte-level download progress deliberately deferred).
- **The Ente ML engine, vendored at a pinned commit** (r21 round; the Phase 5
  AI engine workstream's first slice, `docs/phase-5/ente-pin.md`): the new
  root-level `engine/` Rust workspace carries `crates/ente-ml` (YOLO face
  detection + NMS, geometric alignment, MobileFaceNet embeddings, MobileCLIP
  image/text encoders, the ONNX Runtime provider policy and the model
  catalog), `crates/ente-assets` (asset download + verification + staged
  install primitives) and `crates/ente-image` (byte-oriented decode incl.
  HEIC/AVIF/JXL via the upstream git-pinned heic-decoder) — extracted from
  `ente/ente` at `423b2d80d9ec89d45f5680cb1e1d777bac225f41`, byte-identical
  except the recreated workspace root, the crate-directory renames and added
  crate `license` fields, with every adaptation recorded in
  `engine/NOTICE.md` and the provenance ledger
  (Ente is AGPL-3.0; the ledger records the one-way-compatibility consequence
  for distributed builds). CI gains an `engine` job running
  `cargo check --workspace` (plus fmt/clippy) so the vendored engine can
  never silently rot.
- **The model-delivery core** (r21 round; ADR-0020, the maintainer's
  Ente-style delivery decision): `ml/engine/ModelManifest.kt` pins ALL 11
  model assets (5 core — face detection, face embedding, CLIP image, CLIP
  text, BPE vocabulary — plus 6 pet-pack assets deliberately unprovisioned)
  with byte sizes and SHA-256 digests transcribed from the pinned Rust
  catalog, so no "latest" model can ever be fetched or silently swapped;
  `ModelVerifier` verifies expected-size-then-streaming-SHA-256 without ever
  buffering a model whole; `ModelProvisioner` implements the
  verify-then-stage-then-commit install (temp file → verify → atomic rename →
  receipt written last), per-capability readiness (Not/Partial/Ready), pin-
  change invalidation and explicit invalidate calls, with the transport
  abstracted behind `ModelDownloader` — the HTTP implementation, host
  allowlist and INTERNET permission are r22 by ADR-0020's ordering, so this
  round stays fully offline and the 9-test JVM suite runs hermetically.
- **The combined single-read EXIF index pass** (r20 round; the
  r19-named follow-up, closed): one `MediaReadHandle` open + one
  byte read + one EXIF parse per row now serves BOTH provenance
  dimensions — the capture date (through the r12 resolver path,
  unchanged) and the GPS coordinates (through the r16 snapshot
  path, unchanged) — where the r12/r16 pair of standalone indexers
  each opened and streamed the same bytes. The two standalone
  indexers are removed with it (no dead code); the evidence types,
  the pure write mappings, the eligibility gate and the consent
  gate survive untouched, and the per-dimension availability
  contracts ride on through the combined pass's JVM suite. The
  capability check now runs BEFORE the bytes are read (the r16
  order note): a PNG/GIF/video row costs no read at all, where
  the r12 source paid a wasted full-read to learn the same
  nothing.
- **The NULL-provenance healing pass** (r20 round; the r19-named
  follow-up, closed): Room v5 → v6 adds a `provenanceIndexedAtMillis`
  stamp, written as the LAST of the pass's three targeted writes
  (date, location, stamp) — a row that fails anywhere stays
  unstamped, and a bounded healing pass (100-row batches, at most
  1000 rows per app start, newest-seen-first) re-offers exactly
  those rows to the same pass until they succeed. One transient
  error during a scan can no longer strand a row's date/location
  forever; pre-r20 rows heal once and are never re-selected unless
  a real change resets them. Non-destructive `ADD COLUMN`
  migration; the v6 identityHash re-derived with the documented
  Room 2.8.5 method (v1–v5 reproduced byte-for-byte as control)
  and verified against KSP's own regenerated snapshot.
- **Volume hot-plug for the scan pipeline** (r20 round; the
  r19-named follow-up, closed): an `ACTION_MEDIA_MOUNTED` shell
  (registered with `RECEIVER_NOT_EXPORTED` on T+) hands the
  signal to the driver's new `onVolumesChanged()` — observer
  registration is now idempotent against a tracked volume set,
  so a card inserted after process start gets its observer and a
  coalesced scan pass without waiting for the next app restart.
  Unmounted volumes need no signal (the walk only covers reported
  volumes; their rows keep their live records until the volume
  returns).
- **The vault batch store** (r20 round; the r19-named follow-up,
  closed): a multi-item move-to-vault now seals the whole
  selection under ONE index read + N blob writes + ONE index
  rewrite inside the store lock, where each item used to pay the
  full read-copy-rewrite cycle. Per-item seal failures delete
  that item's blob and report FAILED alone (siblings continue);
  a whole-batch index-rewrite failure deletes every staged blob
  and rethrows, exactly like the single-item `store`. The move
  flow's two phases (seal batch, then per-item consent-checked
  deletes) are documented on the repository method, positional
  outcome pairing unchanged.
- **Folder filter + catalogue bucket dimension** (r19 round; the
  r17-named follow-up, closed):
  - The catalogue persists the folder dimension as row-sourced
    `bucketId`/`bucketDisplayName` columns (Room v4 → v5,
    non-destructive; the hand-derived v5 identityHash was re-derived
    from the Room 2.8.5 sources with the documented method and
    matches byte-for-byte, with v1–v4 reproduced as control). Unlike
    the EXIF-derived provenance columns, bucket values ride the
    upsert itself — no wipe/refill cycle, so they survive every scan.
  - The folder-filtered timeline window is served from the catalogue
    (a `bucketId = ?` clause composed in the engine's fixed order)
    and from both MediaStore legs (API 30+ Bundle-args in-query; the
    pre-30 snapshot leg fails closed with the chips row still
    reachable — the way back is never lost).
  - The Photos chips row gains a leading **Folder** chip → a picker
    sheet (folder names + live counts, honest Loading/Error/Empty
    states, "All folders" clears only the folder dimension); the
    selection survives sort and backend changes like every other
    chip dimension. The r17 "folder chip stays engine-only" note is
    retired.
- **Honest error and permission states** (r19 round):
  - Albums and AlbumDetail gain Error states with retry — the first
    live call sites of the shared ErrorState component; a provider
    hiccup no longer crashes the Albums tab (it previously escaped
    `viewModelScope.launch` uncaught, the one screen in the app with
    no error arm) and an AlbumDetail failure no longer disguises
    itself as "folder is empty".
  - The Search tab gains the Photos permission pattern
    (PermissionNeeded with Grant + Settings deep-link instead of an
    endless generic Error whose Retry can never succeed); a
    mid-query `SecurityException` (revoked while composed) folds
    into the same state.
  - The vault's keystore-handle cache seam is wired: a
    permanently-invalidated biometric key invalidates the cache and
    reloads fresh instead of wedging the unlock flow.
- **Editable metadata fields (EXIF write-back)** (r15 round; the Phase 4
  write path):
  - A pure edit-capability gate (`MediaEditCapability`: EDITABLE /
    READ_ONLY / UNSUPPORTED_FORMAT / NOT_ELIGIBLE / UNSUPPORTED_FIELD /
    CONSENT_REQUIRED) with documented precedence; the GPS field is
    consent-gated on API 29+ through the r14 machinery.
  - The write flow opens the item's content URI read-write and saves
    through `androidx.exifinterface`; the pinned 1.4.1's supported
    write set and its inability to write `Rating`/`XPKeywords` were
    verified from the sources jar — those fields render honest "later
    round" states rather than silent no-op writes. PNG is excluded
    because our own pinned reader cannot display a PNG EXIF result.
  - The API 30+ `MediaStore.createWriteRequest` escalation is a pure
    state machine (single-shot retry, stale-signal guards) behind a
    thin activity-result shell; API 29 exactly reports the honest
    read-only outcome (no dialog exists there).
  - Details view: edit affordances on supported rows, live validation
    in the editor dialog, writes off the main thread, honest
    Saving/Failed/Denied states; a write re-enters the scan pipeline's
    documented eventual-consistency contract.
- **On-device map view** (r16 round; zero network tiles by
  construction):
  - The world renders from a bundled Natural Earth 110m land outline
    (public domain, ~135 KB); a dependency-free GeoJSON parser,
    equirectangular projection and grid clustering draw it (plus photo
    dots) on a new Map tab; tapping a dot opens a location sheet → the
    existing viewer. The offline tile-pack sideload is a documented
    later-round contract (`docs/phase-4/on-device-map.md`), not a fake
    tile loader.
  - GPS persistence, Room v3 → v4 (non-destructive; the hand-written
    v4 schema JSON was verified by re-implementing Room 2.8.5's
    identityHash algorithm — it reproduces the committed v1–v3 hashes
    byte-for-byte): latitude/longitude + provenance columns, written
    by a `LocationIndexer` (a second `ScanIndexHook`, composite
    fan-out) that parses EXIF GPS through the read boundary and
    persists honest nulls while the platform redacts (API 29+
    ungranted).
  - The map's empty/consent states are honest (no coordinates yet vs
    location consent off → Settings); the grant-triggered full re-pass
    is a documented Phase 5 seam (`LocationConsentReindexPolicy`).
- **Collections + smart-filter chips** (r17 round; Aves-style, design
  reference only — no Dart port):
  - A pure filter engine (media type incl. GIFs, favorites, folder,
    date-range) with three JVM-tested projections — in-memory
    predicate, MediaStore selection args, catalogue SQL WHERE —
    applied on BOTH timeline paths (catalogue windows and legacy
    MediaStore windows), so the grid never lies during cold start.
  - Chips row on Photos (All / Photos / Videos / GIFs / Favorites,
    clear-all) and a Collections section on Albums (derived, zero
    persistence). The folder chip stays engine-only — the catalogue
    has no bucket column — and date-range awaits its picker UI; both
    are the honest subset, not lying chips.
  - Flagged (pre-existing, NOT fixed here): search/favorites build
    `media_type IN (1, 2)` but the platform reference says 2 = AUDIO
    and 3 = VIDEO.
- **Format-support audit** (r18 round; the deliverable is a cited
  matrix):
  - A magic-byte `FormatSniffer` (19 honest verdicts: JPEG/PNG/GIF/
    WebP subtypes/HEIF/AVIF/TIFF-family RAWs incl. the DNGVersion
    walk/ISO-BMFF brands/EBML, bounded reads, honest UNKNOWN) +
    `FormatSupportCatalogue` (per-family still-decode / animated /
    EXIF-read / EXIF-write / playback), and
    `docs/phase-4/format-support-audit.md` — the format matrix with
    repo cites or official-docs URLs on every platform claim, per-API
    26→36 differences, and the honest-limitations register. Adoption
    point (Phase 5): the unsupported-format surface + Details rows.
  - 196 new JVM tests across the four rounds; all four PR suites ran
    green on CI before merging.

- **Metadata Details view (read path)** (r14 round; the first
  user-facing Phase 4 deliverable):
  - A new Details screen, reachable from the viewer's info sheet
    (`Full details`), presenting: dimensions, file size, MIME type,
    capture date with the timezone semantics stated explicitly (the
    instant rendered in the file's own recorded zone, the zone
    provenance, and the winning source), camera make/model/lens, the
    full exposure triangle, and GPS coordinates in decimal + DMS.
  - Availability is never flattened: PRESENT / ABSENT ("no block") /
    UNSUPPORTED (a capability statement) / UNREADABLE (corrupt) each
    render their own honest state; IPTC/XMP render as their format
    matrix capability ("not supported for this format yet" for the
    wrappers that are a later round) --- nothing is faked.
  - The complete EXIF block sits in a collapsible tree. All parsing
    runs off the main thread; the parsed snapshot is cached per item
    for the screen's lifetime. Vault/trashed/revoked items are refused
    by the same type-level eligibility gate the scan pipeline uses ---
    an unreadable item shows the honest "can't be read" state, never a
    partial read.
- **ACCESS_MEDIA_LOCATION permission flow** (r14 round; the other
  roadmap still-open item):
  - A pure, JVM-tested permission state machine (NOT_REQUIRED on
    <29 / UNKNOWN / GRANTED / DENIED) behind a thin production impl;
    the runtime request lives in a new Settings "Location data"
    section with an honest explanation (coordinates never leave the
    device; there is no network surface to carry them).
  - GPS rows in the Details view are DISPLAY-GATED on the consent
    state (`MediaLocationDisplayRules.Default`): hidden on an
    ungranted 29+ device, shown with a grant, always shown below 29
    (where the platform never redacted anything). A hidden PRESENT
    block stays availability-PRESENT with a pointer to Settings ---
    the availability statement is never faked. A live state observer
    re-renders the open Details screen when the grant flips.
  - The manifest declares ACCESS_MEDIA_LOCATION unconditionally (there
    is no minimum-API form; pre-29 devices ignore the unknown name and
    the runtime request is gated in code); a privacy-policy test pins
    the declaration.

### Pre-existing entries

- **Timeline capture-date adoption** (r13 round; the roadmap's promised
  behavior change --- the timeline's sort/group keys now use the
  persisted capture dates):
  - The Photos timeline orders and groups by the **effective capture
    date**: the resolved EXIF capture instant when the r12 indexer
    produced one, `date_added` otherwise. Day/month group headers
    derive from the same effective instant (timezone-aware as before;
    pre-1970 negatives are legitimate and land under their own day).
  - **Catalogue-backed timeline windows**: once the first scan has
    populated the catalogue, the timeline's windowed reads come from
    the Room catalogue itself, ordered by
    `COALESCE(resolvedCaptureDateMillis, dateAddedMillis)` with a
    `localMediaKey` tiebreak (the stable-total-order discipline that
    keeps windows tiling without duplicate grid keys). While the
    catalogue is still empty (first cold start), the timeline keeps
    the previous MediaStore windows unchanged --- no first-paint
    regression, no waiting screen.
  - **Room v2 -> v3** (non-destructive migration): the catalogue now
    carries the display/shape fields a timeline cell needs ---
    `displayName`, `width`, `height`, `durationMs`, `isFavorite` ---
    projected by the scan walk as NON-REVISION ride-alongs (a shape
    drift never triggers re-indexing; the known API-29-only favorites
    staleness edge is documented in the row contract).
  - Timeline invalidation while catalogue-driven rides the
    catalogue's own change flow (one shared Room instance, held by the
    Application, so invalidation reaches every observer) --- the
    eventual-consistency contract is documented: a fresh camera shot
    appears once the scan pipeline reconciles it, because the ordering
    key itself is produced by the scan.
  - 40 new JVM tests (catalogue window ordering/tiling, source-choice
    state machine, TimelineRows effective-date grouping, comparator),
    561 total green.

### Pre-existing entries

- **Phase 4 scan driver** (r12 round; the roadmap's "scan driver that
  wires the catalogue + change feed into app start" item):
  - On app start the process now runs the full incremental discovery
    pipeline: a per-volume authoritative MediaStore walk (`IS_TRASHED`
    excluded on R+), diffed against the Room catalogue through the
    r10 reconciler (added / changed / removed -> tombstone), a
    per-volume generation-token checkpoint saved last so a crash
    mid-apply self-heals on the next pass, and ContentObserver
    registration so later changes re-scan (API 30+ rescans only when
    the live generation differs from the checkpoint; API 26-29 always
    rescans --- the observer alone cannot prove the volume unchanged).
    The decision core is pure and exhaustively JVM-tested.
  - **Capture-date adoption** (the other r12 roadmap item): every row
    a scan adds or changes gets its capture date resolved ONCE from
    EXIF evidence through the MediaReadHandle byte boundary (only
    eligible public media; vault/trashed/revoked items are skipped by
    type) and persisted onto the record's provenance fields
    (instant + timezone assumption + winning source). Metadata that
    is ABSENT vs UNREADABLE vs UNSUPPORTED persists null under its own
    name; `date_added` is never persisted as a capture date. Room
    v1 -> v2 via a hand-written non-destructive migration (three
    `ALTER TABLE ... ADD COLUMN DEFAULT NULL`); the v2 schema JSON is
    committed under `apps/gallery/schemas/`.
  - The diagnostics ring logs `phase 4 scan driver started` at wiring
    time (the W1 seam lesson: a device log showing the boot line but
    not this line pins a pipeline-construction failure exactly).
  - Deliberately no UI consumes the catalogue yet --- the timeline's
    sort/group keys switch to the persisted capture dates next round,
    after a device pass proves the pipeline on real volumes.

### Pre-existing entries

- PR #81 audit-hardening slice (the implementable findings from
  `docs/CRASH_INVESTIGATION_PLAN.md`, landed alongside the r11
  biometric fix):
  - **Vault backup exclusion** (audit A): new `res/xml/backup_rules.xml`
    (pre-31 fullBackupContent) and `res/xml/data_extraction_rules.xml`
    (31+, cloud-backup AND device-transfer) exclude `filesDir/vault`
    (`pin.bin`, `index.bin`, `blobs/`, `biometric.binding`) from backup
    --- the AndroidKeyStore key is non-exportable, so restored
    ciphertext would be permanently unopenable, and vault state must
    not leave the device (ADR-0014; debt pre-tracked in
    `docs/integration-decisions.md`). A JVM test pins the rules.
  - **Room transactions** (audit D): the tombstone and resurrection
    transitions are now single `@Transaction` composite DAO methods
    (`removeItemsAndWriteTombstones` / `upsertItemsAndClearTombstones`);
    the in-memory reference was already atomic and now documents the
    equivalence, pinned by a deterministic interleaving test.
  - **Room schema export** (audit K): `exportSchema = true` +
    `room.schemaLocation`; the v1 schema snapshot (3 entities) is
    committed under `apps/gallery/schemas/` with a review-gate README.
  - **Decode planner overflow** (audit J): the memory-budget check is
    now a multiplication-free exact inequality --- adversarial
    dimensions that would overflow Long are refused deterministically,
    negative budgets refuse everything; 6 new boundary tests.
  - **Pre-1970 capture dates** (audit F): negative epoch timestamps are
    valid instants (scanned film/historical archives); only
    UNKNOWN-source candidates are discarded; the cross-source
    disagreement gap check is overflow-safe; 6 new tests.
  - **EXIF date metadata** (audit I, EXIF half): the extractor now also
    reads DateTimeDigitized, OffsetTimeOriginal/OffsetTimeDigitized and
    SubSecTimeOriginal (offset-aware epoch math with a documented UTC
    anchor when no/malformed offset; decimal-fraction subsecond
    convention, >3 digits truncated); `ParsedExif.captureDateCandidates()`
    emits both EXIF_ORIGINAL and EXIF_DIGITIZED candidates feeding the
    already-built MediaDateResolver; XMP stays UNSUPPORTED this round.
  Verification: 491 JVM tests green (34 new), lintDebug green,
  assembleDebug + assembleRelease (R8+KSP) green.
- Phase 4 contract foundation (the audit-mandated prerequisite slice, all
  JVM-tested pure Kotlin + thin untested Android adapters per ADR-0016,
  no UI yet): the media identity/eligibility keel (`MediaIdentity`, a
  volume-aware durable local media key where path/display name are never
  identity, plus `MediaEligibility` whose only positive product is the
  `EligiblePublicMedia` wrapper --- vault, trashed, permission-revoked,
  unsupported and corrupt media are unrepresentable at future AI call
  sites); revision tracking (`MediaRevision.diff` classifying UNCHANGED /
  METADATA_CHANGED (metadata re-read only, AI outputs kept) /
  CONTENT_CHANGED (all downstream outputs invalidated)); the timezone-
  aware capture-date resolver (EXIF original > EXIF digitized > XMP >
  container > MediaStore precedence with subsecond precision and a
  sources-disagree flag); the metadata seams (`MetadataAvailability`
  distinguishing PRESENT/ABSENT/UNSUPPORTED/UNREADABLE,
  `MediaMetadataSnapshot`, `ExifMath` + `ParsedExif` assembly over a thin
  `androidx.exifinterface` reader, container parses for video
  rotation/duration/zone-aware dates, and `MetadataFormatMatrix` --- the
  audit's parser capability matrix in data, not format promises); the
  Room media catalogue (`MediaCatalogue` over Room 2.8.5/KSP with
  entities, DAO, non-destructive migrations and per-volume scan
  checkpoints; an in-memory reference implementation carries the
  semantics on the JVM); the change feed (`MediaChangeFeed`: per-volume
  MediaStore generation on API 30+, ContentObserver fallback on 26-29,
  feeding `CatalogueReconciler` --- added/changed/unchanged/removed
  classification that tombstones deletions instead of hard-deleting, and
  treats a generation bump with identical size+date as a change (superseded
  by the audit-C semantics correction in this same unreleased line: the
  per-item token is the row's own generation-modified value, not the
  volume checkpoint), while
  in-app metadata writes alone never trigger re-indexing); the
  byte-oriented read boundary (`MediaReadHandle` + `MediaAccess`,
  openable ONLY for `EligiblePublicMedia`, permission re-checked at
  open, no plaintext temp files); and the canonical decode contract
  (`CanonicalDecodePlanner` power-of-two sampling with upright swaps and
  a deterministic memory-budget refusal instead of OOM roulette, plus
  the thin `AndroidBitmapDecoder`). 174 new JVM tests pin the contracts;
  no UI consumes any of this yet --- that is deliberate (the audit's
  "contracts before Ente integration" gate).
- On-device diagnostics (#74, the device round): every vault failure that
  used to swallow into a generic snackbar now leaves a trace. A new
  dependency-free ring-buffer logger `DiagLog` (CAP 400, thread-safe,
  oldest evicted) mirrors to `android.util.Log` under the shared `ToadDiag`
  tag and keeps entries as `MM-dd HH:mm:ss.SSS L/Tag: message` lines with
  the throwable's cause chain folded into the same entry (up to 3 levels,
  loop-safe). The privacy contract (ADR-0014) is enforced by construction:
  op names, counts, ids, sizes, durations and exception class+message ONLY
  --- never PIN data, key material or vault display names; with no INTERNET
  permission, logcat is the only egress and it requires adb/physical access.
  Every `VaultViewModel` catch (setup/unlock/biometric/auto-lock/consent
  retries) and the repository's phase legs (setup, verify mismatch vs
  corrupt record, restore insert/publish, rename/copy/favorite 0-row
  outcomes) WARN under the shared `ToadVault` tag, so one failure reads as
  a two-line story in logcat; the application logs its `versionName (build
  N)` at process start so a pasted logcat is always attributable to an
  exact build. 11 JVM tests pin the ring (cap/eviction/format/cause chain).
- Appearance settings and the About page (#74, the device round): Settings
  gains an **Appearance** section --- Layout (COLLAGE aspect-aware staggered
  vs GRID uniform cards, checkmark state, Ente-style), Photos per row
  (minus/plus steppers, clamped to [2,6], live preview) and Group by
  (None/Day/Month) --- persisted through the existing settings channel and
  applied to the Photos timeline immediately; grouping renders section
  headers through the new `TimelineRows` helper without disturbing paging
  keys, and defaults (COLLAGE / 3 / None) keep today's render byte-identical.
  A new **About** screen shows the app name, version + versionCode and the
  license pointer, plus a **Diagnostics** panel: the vault state probe
  (root/pin/index/biometric-binding presence, blob count + bytes, root
  writability, keystore key presence --- read-only, nothing decrypts or
  generates, safe from any screen state) and the `DiagLog` ring with
  copy-to-clipboard and clear. 27 new JVM tests pin the appearance codecs,
  clamps and the timeline grouping math.
- Vault integrity round 2 (#73): `restore()` now treats an unconfirmed
  publish as a failure --- `resolver.update()`'s row count is checked and a
  0-row publish throws into the existing failure path (pending row deleted,
  the vault copy KEPT, ADR-0017); the same ignored-return flaw was fixed in
  `MediaFileOps.copy`. First-time `VaultStore.setup()` is transactional:
  both ciphertexts are staged as `.tmp` files and committed by rename
  (index first, pin last); any leg failing rolls everything back and
  surfaces a retryable `VaultStoreException("vault setup failed; nothing
  was configured")` --- and `isConfigured()` now requires BOTH files, with
  a self-heal pass cleaning pin-only/index-only residue from pre-#73
  installs. 4 new failure-injection tests pin each rollback shape.
- Vault biometric unlock binding (#57, ADR-0019) and decrypted vault
  thumbnails (#58): the hidden vault gains an opt-in "Unlock with biometrics"
  path backed by hardware --- a second AndroidKeyStore AES-256-GCM key
  (`toadgallery_vault_biometric`, StrongBox→TEE) generated with
  `setUserAuthenticationRequired(true)` and per-use authentication, so EVERY
  unlock prompts. Enrollment (a settings-style toggle on the unlocked vault
  screen, visible right after setup) seals a fresh random 32-byte session
  secret under a prompt-authorized `BiometricPrompt.CryptoObject` cipher into
  a new optional, strictly-parsed store file `biometric.binding`
  (`version(1) || IV(12) || ciphertext(32) || tag(16)`) --- absent file =
  the pre-#57 PIN-only behavior, byte for byte; the PIN stays the primary
  and always-available fallback, auto-lock semantics are unchanged, and a
  biometric enrollment change honestly invalidates the key: the dead binding
  is removed with a "re-enable" nudge instead of a silent break. `MainActivity`
  becomes a `FragmentActivity` (the androidx prompt requires one); the new
  `androidx.biometric` dependency declares `USE_BIOMETRIC` but not `INTERNET`,
  so the ADR-0014 offline gate is unaffected. The unlocked vault list now
  shows decrypted, size-bounded thumbnails (photos downsampled to a 256px
  edge straight off the unseal stream via `inSampleSize`; videos decrypted
  to a temp file in the private `cacheDir`, one frame extracted via
  `MediaMetadataRetriever`, temp file deleted in a `finally` immediately
  after --- no plaintext vault media ever persists on disk), decoded on
  `Dispatchers.IO` under a 3-permit semaphore with per-item `Throwable`
  catching so a bad or oversized blob degrades to the placeholder plate
  instead of crashing; all thumbnail bitmaps live in ViewModel memory only
  and clear with the session on auto-lock. 29 new JVM tests pin the binding
  envelope, the store's binding leg, the thumbnail downsample/naming policy
  and the biometric readiness decision table.
- Favorites and sorting (#66): the viewer's top bar gains a heart toggle for
  the current page — a MediaStore `IS_FAVORITE` write through the same
  consent machinery as rename/delete (Android 11+ write-consent round-trip
  for items this app didn't contribute, single-shot retry; Android 10 keeps
  the honest failure narration since `createWriteRequest` needs Android 11)
  with an undo snackbar that toggles back. Favorited cells carry a small
  heart chip in every grid (the same scrim idiom as the duration and GIF
  badges, driven by the new `MediaItem.isFavorite` mapping in all queries),
  and the Albums tab pins a distinct "Favorites" pseudo-album card with a
  live count at the top — its sentinel bucket id (−1) routes the album
  detail screen to an `IS_FAVORITE = 1` query, and the count rides the
  unified collection observer on Android 11+ (favorites and search keep
  newest-first in v1). The Photos timeline and album detail gain a sort menu
  (Newest/Oldest first, `_id`-tiebroken total order, per-session state):
  changing it swaps in a fresh Paging generation — the provider sort clause
  is parameterized through the windowed queries, the snapshot paths sort
  in memory, and the observer-invalidation pipeline stays intact. Below
  Android 10 the whole favorites surface stays hidden (write gate, same as
  the other file ops). 15 new JVM tests pin the sort mapping and the
  favorites selection/sentinel rules.
- Album detail file operations (#64): the Albums tab grid gains the full
  #30 selection machinery --- long-press selection with checkmark overlays,
  count app bar, Rename (single selection), Copy, Move (album picker that
  excludes the bucket you are in) and Delete with the established consent
  round-trip, undo snackbars and cancellable batch progress; below Android 10
  the screen stays read-only like Photos. `FileOpsViewModel` proved genuinely
  screen-agnostic (zero code changes) and is now documented as driving both
  screens. Album grids also refresh after operations: API 30+ rides the same
  unified collection `ContentObserver` the Photos timeline watches (debounced,
  generation-guarded re-query), while every level re-queries when the
  file-ops machine settles back to idle --- covering Android 10, where ops
  are enabled but no observer fires (residual undo-staleness on Q documented
  alongside the Photos one). 5 JVM tests pin the move-target rule.
- Viewer operations (#63) and decoder fallback (#65): the full-screen viewer
  gains top-bar actions for the current page --- Info (Material 3 bottom
  sheet: name, type, size, dimensions, duration, date added, content URI,
  honest "Unknown" for missing values), Share (`ACTION_SEND` with the content
  URI and a read grant), and Q+-gated Rename and Delete driving a
  viewer-scoped `FileOpsViewModel` through the same consent machinery, undo
  snackbars and trash-routing as the Photos tab; a deleted page drops from the
  pager URI-keyed (swiping during the consent dialog is safe) and an emptied
  window closes the viewer, while a rename only rewrites `DISPLAY_NAME` so
  pages stay valid by design. Video pages now build their player with
  `DefaultRenderersFactory.setEnableDecoderFallback(true)`: a hardware decoder
  that rejects the stream (device log: 10-bit HEVC `hvc1.2.4.H123.90` in MKV,
  `c2.qti.hevc.decoder` failing with `NO_EXCEEDS_CAPABILITIES`) falls back to
  alternates including the software HEVC decoder, and a playback failure now
  shows an in-page error state with Retry instead of a silent black surface.
  18 new JVM tests (info formatting, window removal semantics).
- Search tab v1 (#62): the Search tab is real. A debounced (300 ms)
  `DISPLAY_NAME LIKE` query with `%`/`_` escaped through the SQL `ESCAPE`
  clause, photo/video/all media-type filter (the timeline's `media_type`
  semantics), newest-first results capped at 1000 in v1, rendered through the
  shared aspect-aware grid with the video-duration and GIF overlays and
  opening into the full-screen viewer on tap. Filter chips, clear button,
  short-query hint, no-results and error states follow the screen-state
  system; the query builder is a pure, Android-free helper pinned by 23 JVM
  tests (escaping, type clauses, sort/limit). Advanced smart filters stay in
  Phase 5 as planned.
- Signed testing pre-releases via a dedicated Release workflow (#59,
  ADR-0018): Phase 3+ snapshots are now shippable as installable,
  updatable builds. The new `Release` workflow
  (`.github/workflows/release.yml`, separate from CI) triggers on `v*`
  tag pushes and on `workflow_dispatch` (input `tag_name`, default
  `v0.4.0-alpha-r<run_number>` computed from `versionName`, so testing
  releases can be cut ad hoc without manual tagging), runs
  `assembleRelease` — the ADR-0014 privacy gate anchors on
  `assemble<Variant>`, so release APKs are still built under the
  offline-by-construction check — signs with a dedicated PKCS12 release
  keystore (RSA-2048, alias `toadgallery-release`, 25-year validity;
  never committed, held only as GitHub Actions secrets), and publishes a
  GitHub pre-release (`prerelease: true`, never marked latest pre-1.0)
  attaching the APK, a `.sha256` checksum and the R8 `mapping.txt`. A
  hard `apksigner verify` gate pins the signer certificate SHA-256 digest
  (`8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494`),
  so an unsigned or wrongly-signed APK can never reach the publish step;
  the digest is also the user-side verification anchor for sideloaded
  builds (`apksigner verify --print-certs`). The release
  `signingConfig` is injected from `TG_KEYSTORE_FILE` /
  `TG_KEYSTORE_PASSWORD` / `TG_KEY_ALIAS` / `TG_KEY_PASSWORD` env vars
  with a graceful unsigned fallback when they are absent, so local dev
  builds are unaffected. This pulls the Phase 11 "release-signed APK
  production on tag pushes" deliverable forward for Phase 3 testing
  builds; reproducible builds and F-Droid metadata (F-Droid builds and
  re-signs its own binaries, so this identity governs only the
  project's own testing/sideload pre-releases) stay in Phase 11.
- Viewer pinch-zoom, pan and animated GIFs (#50 follow-ups): photo pages zoom
  to 5x around the pinch centroid, pan with Fit-letterbox clamping (the photo
  never leaves the screen; zooming out re-centers), and double-tap toggles
  2.5x-at-the-tap-point / reset. While a photo is zoomed the pager's swipe
  locks until the user zooms back out (documented v1 trade-off); leaving a
  page resets its zoom. A custom pointer-count-split detector
  (`ViewerGestures`) replaces stock `detectTransformGestures`, which would
  consume single-finger drags and starve the pager at 1x. GIF items animate
  in the viewer only, through a per-request `decoderFactory`
  (`ImageDecoderDecoder` on API 28+, `GifDecoder` on 26/27, via the new
  decoder-only `coil-gif` dependency — no INTERNET permission, privacy gate
  unaffected); the grid keeps static first frames (#33/#34 behavior pinned).
  18 JVM tests pin the transform math (clamping invariants, double-tap
  anchoring, reset).
- Hidden vault storage + unlock UI (#32, slice 2/2, on the ADR-0017 core): the
  vault is now a complete feature. `VaultStore` (pure JVM) keeps the sealed PIN
  record, a sealed hand-rolled index (`VaultIndex`, strict parsing, escaped
  display names) and id-named sealed blobs under app-private storage — blob
  first, index second (crash orphans are reclaimed by the unlock sweep, never a
  dangling index entry); small files go through tmp+rename atomic writes.
  `AndroidVaultKeys` supplies the non-exportable AES-256-GCM AndroidKeyStore key
  (StrongBox when the device has it, TEE fallback). Setup/unlock run PBKDF2 on
  `Dispatchers.Default`; the unlock session is in-memory only and auto-locks on
  background (`ON_STOP`, shell lifecycle observer). "Move to vault" appears in
  the Photos selection app bar once the vault is configured: bytes are streamed
  through the sealing cipher, then the originals are removed through the #30
  consent machinery (one `createDeleteRequest` per batch); restore is the
  inverse pending-insert flow, deleting the blob only after the row publishes.
  The new `vault` sub-screen (Settings entry, status line leaks nothing while
  locked) offers setup with the no-recovery trade-off stated verbatim, unlock,
  restore, and confirmed permanent delete. 39 new JVM tests pin the store
  (round-trip, tamper/truncation, orphan sweep, wipe, wrong-key rejection) and
  the index format.
- Vault crypto core (#32, ADR-0017): pure-JVM `VaultCrypto` — PBKDF2-HMAC-SHA256
  PIN digests (210k rounds, constant-time verify, wipable `CharArray` input) and
  AES-256-GCM sealing (`IV(12) || ciphertext || tag(128)`, fresh IV per seal) with
  streaming seal/open for vault media beyond heap size; key delivered through the
  `VaultKeyProvider` seam (production: non-exportable AndroidKeyStore key, later
  slice). 13 JVM tests pin round-trip, wrong-key/tamper/truncation rejection, IV
  uniqueness and a 5 MiB streaming round-trip.
- Recycle bin (#31): deletion on the Photos timeline now routes to
  MediaStore's system trash on API 30+ (`createTrashRequest` consent flow,
  one request per batch; rows stay recoverable ~30 days with system-managed
  auto-purge), while Android 10 keeps the permanent-delete path (no trash
  protocol exists below R). New `trash` sub-screen reached from a Settings
  entry (live item count + total size, `DataSizeFormats`): restore or
  delete-permanently any selection, or empty everything — each via one
  consent request, narrated with honest snackbars. `TO_TRASH` seam from #30
  is now the delete default; JVM tests pin the size formatter; lint is a
  hard CI gate (see Changed).
- Full-screen media viewer (#50 — PR #51): tapping a grid cell on the Photos
  timeline or in album detail (outside selection mode) now opens the viewer —
  black edge-to-edge canvas with hidden system bars, a `HorizontalPager` over
  the tap-time window, photos full-fit via Coil, and in-app video playback
  through media3 ExoPlayer 1.11.1 (local content URIs only; roadmap L608).
  Chrome bar with back action + "n of m" counter; honest playback-error copy
  when the device codec can't decode the file; video grid cells that fail
  frame extraction (`getFrameAtTime` NULL on some device codecs) now show an
  `ic_video_fallback` poster instead of a blank cell. Window hand-off via a
  shell-scoped `ViewerViewModel` (the #27 pattern); JVM tests pin the
  `ViewerWindow` clamping contract. Pinch-zoom/pan and animated GIF playback
  are deferred (see issue #50).
- File operations on the Photos timeline (#30 — PR #48): rename (extension
  preserved), in-place copy (` (copy)` naming with collision numbering),
  move (copy→delete with per-item results), delete (permanent v1, explicit
  confirmation); long-press multi-select with a selection app bar, album
  picker, undo snackbars for rename/copy/move, cancellable >5-item batch
  progress, and one aggregated `createWriteRequest`/`createDeleteRequest`
  consent dialog for entries the app does not own (API 29+; ops hidden on
  older APIs). MediaStore-only, URI-first (ADR-0006); `DeleteMode.TO_TRASH`
  seam reserved for the recycle bin (#31).
- Design tokens & theme (#11 — PR #47): spacing/shape/elevation/motion/
  touch-target token system, full 15-role M3 typography, explicit light+dark
  fallback palettes (MI-inspired warm grays + single green accent, WCAG AA
  pairs computed), dynamic color kept on API 31+, edge-to-edge, and the four
  state components migrated to tokens. JVM test pins the scale invariants.

### Changed

- **Video playback is lifecycle-aware** (r19 round): ON_STOP pauses
  the player (audio no longer bleeds into the background); ON_START
  resumes only if the page was playing when stopped; a page that
  stops being the current pager page pauses (the mirror of the
  zoom-reset seam PhotoPage already had).
- **Every media grid honors the "Photos per row" setting** (r19
  round): Trash (previously a hardcoded 3-column staggered grid),
  Search and the move-picker grids now consume the same appearance
  setting as Photos/AlbumDetail.
- **One revision rule in one place** (r19 round): CatalogueReconciler
  now delegates to `MediaRevision.diff` — a generation-token
  appear/disappear alone is UNCHANGED (observability, not content),
  exactly as MediaRevision always documented; the reconciler test
  that pinned the contradictory semantics is flipped to the
  authoritative contract. Practical effect: a 29→30 upgrade day no
  longer rewrites every catalogue row for a token flip.
- **The Details view rides the shared process-wide catalogue** (r19
  round): a second, per-visit RoomDatabase (a leaked SQLite
  connection that would silently miss scan writes) is gone; the
  record lookup is a keyed `canonicalUri` DAO query
  (`recordsByCanonicalUri`) instead of a full-table `recordsMap()`
  scan per Details open.
- **Vault durability hardening** (r19 round): every mutating vault
  operation serializes behind a store-level lock; the orphan sweep
  defers blobs younger than a ten-minute grace window (deferred, not
  skipped — the next sweep reclaims them); atomic writes fsync
  before rename (power-loss durability); setup() rollback removes
  staged `.tmp` residue on any staging failure.
- Catalogue revision semantics corrected (PR #81 audit C, landing in the
  same unreleased 0.4.1 line as the Phase 4 foundation that introduced
  it): the per-item revision token is the row's own
  `MediaColumns.GENERATION_MODIFIED`, never the per-volume
  `getGeneration` checkpoint (which stays the scan-checkpoint-only
  input). The reconciler test that had pinned the full-re-index-storm
  behavior (generation bump with identical size+date = changed) is
  replaced by the corrected pair: one row's per-row token bump marks
  THAT row changed, unrelated rows stay unchanged. Contract KDocs
  corrected across MediaRevision / MediaChangeFeed / MediaScanRow /
  CatalogueRecord / CatalogueReconciler.
- Lint is a hard CI gate (#52): the 19 pre-existing NewApi errors from the
  file-ops slice are resolved (documented `@Suppress("NewApi")` on the
  Q-guard invariant; `@RequiresApi(R)` on the consent builders) and
  `continue-on-error` removed from the lint step.
- CI runs compile + JVM unit tests only — no emulator-based tests (ADR-0016,
  PR #45); the instrumented privacy suite is a manual on-device release gate.
- Docs (#21, #22 — PR #46): `androidphotofilters` and `jxl-coder-glide`
  marked DROPPED per ADR-0008; risk register updated (R-10 downgraded, R-14
  closed, R-16/R-17/R-18 added).

### Fixed

- **The favorites/search `media_type` bug** (r19 round; the r17
  round flagged it, the audit confirmed the blast radius):
  `media_type IN (1, 2)` treated 2 as VIDEO — but 2 is AUDIO (3 is
  VIDEO) — so the favorites pseudo-album silently dropped every
  favorited video and could surface audio rows, and search's Videos
  chip returned audio files. Both selections now pin images+videos
  (`IN (1, 3)` / `= 3`), cross-checked against the filter engine's
  constants by JVM tests so the literals cannot drift again.
- **The vault unlock-vs-move data-loss race** (r19 round; audit P0
  from two independent audits): a locked-state move-to-vault racing
  an unlock's `sweepOrphans()` could have the sweep delete the
  in-flight blob as an "orphan", the move's index write then
  reference the deleted blob, and the original MediaStore row be
  deleted — permanent, irreversible loss. The store-level mutex
  closes every read-modify-write interleave (store vs store, delete
  vs store, wipe vs store, unlock-sweep vs move); a parked-stream
  JVM test pins the exact interleave with a blob deliberately aged
  past the grace window so only the lock can save it.
- **GPS editor NaN corruption** (r19 round): `toDoubleOrNull`
  accepts "NaN" (and "0x1p3", "1.5d"); NaN passes the latitude
  range check and the write path persists GPS (0,0) into the file's
  EXIF. The editor now accepts a strict decimal grammar + finiteness
  only; the refused-input corpus pins the exotic forms and the
  round-trip canary pins that prefill-produced scientific notation
  still re-parses.
- **Viewer swipe-lock after delete-while-zoomed** (r19 round): the
  zoom flag was remembered outside `key(window)`; deleting the
  zoomed photo cancelled (not ran) the old page's reset effect and
  the pager stayed swipe-locked until an accidental gesture. The
  flag now re-seeds with the rebuilt window.
- **Photos permission race** (r19 round): a debounced catalogue
  change from the app-start scan could publish a Ready grid of
  un-grantable thumbnails BEHIND the system permission dialog (a
  later grant then early-returned and left the broken cells); a
  denial could not cancel the in-flight generation, letting a stale
  Ready overwrite PermissionNeeded. Denials now cancel and clear;
  catalogue changes only auto-start when already Ready.
- **Scan indexer per-row isolation** (r19 round): a throwing row in
  CaptureDateIndexer/LocationIndexer aborted the whole indexing
  batch — despite the KDoc promising otherwise — leaving every
  remaining row's provenance NULL forever (later scans see the rows
  UNCHANGED and never re-offer them). Per-row try/catch makes the
  promise structural; the NULL-provenance healing pass stays a named
  follow-up.
- **Vault thumbnails authenticate the GCM tail** (r19 round):
  `BitmapFactory.decodeStream` stops at end-of-image, so the
  trailing GCM tag was never consumed and a tampered/truncated blob
  could publish a (partial) thumbnail with no authentication
  failure. The decoder now drains to EOF after the decode inside the
  same `use` block; a drain failure yields the placeholder.
- **Format sniffer major-brand rule** (r19 round): image brands were
  consulted before video families over the mixed major+compatible
  brand list, so an HEVC camcorder file (major `qt`/`isom`, compat
  `hevc`) sniffed as a HEIF still image — wrong on every axis. The
  major brand now decides the video families; compatible brands
  classify images only.
- **WebP editability drift** (r19 round): the format matrix said
  WebP `editable=false` while the r15 write spine (and its tests)
  made WebP EDITABLE — two declared truths disagreeing. The matrix
  now agrees with the gate; the heic/heif rows carry their API-28
  floor and the AVIF note its verified API-31 read floor.
- **Pre-Q projection crash guard** (r19 round): the timeline and scan
  queries project WIDTH/HEIGHT against pre-Q collections where the
  columns may not exist (MediaColumns surface from API 29); a
  rejecting provider threw out of `ContentResolver.query` with no
  catch. A one-shot reduced-projection retry now degrades honestly
  (width/height 0) instead of crashing API 26–28.
- The biometric unlock sheet still crashed --- the authenticator class,
  not the missing negative text, was the real defect (#78 follow-up,
  the r11 device finding, build 14): one androidx.biometric validation
  after r10's fix, the same tap died with `IllegalArgumentException:
  Crypto-based authentication is not supported for Class 2 (Weak)
  biometrics.` from androidx.biometric 1.1.0's
  `BiometricPrompt.authenticate(PromptInfo, CryptoObject)`. Root cause
  (verified against the library sources): the vault's prompt binds a
  CryptoObject (the auth-per-use AndroidKeyStore key, ADR-0019/#57)
  while requesting `BIOMETRIC_WEAK`, and the library rejects that
  combination unconditionally --- it tests the REQUESTED authenticator
  flags, not the device's sensor class (the error's "Class 2" wording
  names the requested class, not the test device's sensor), so the path
  crashed on EVERY device, not only Class-2-sensor devices. The prompt
  now requests `BIOMETRIC_STRONG` and the readiness probe uses
  `canAuthenticate(BIOMETRIC_STRONG)`: devices without a Class 3
  sensor/enrollment land in the existing `Unsupported` readiness ---
  PIN-only, no biometric UI --- the honest degradation, since a Class 2
  biometric cannot authorize an auth-per-use AndroidKeyStore key at
  all. The r10 "Use PIN" negative button stays (still mandatory for a
  biometric-only crypto prompt). The same honest JVM limitation as r10
  continues: the library's `build()`/TextUtils enforcement is
  device-checklist territory (r11 checklist, section 13), while the JVM
  suite pins the builder fields via reflection (negative text +
  `BIOMETRIC_STRONG`, plus a new bit-math regression test against the
  library's own `isWeakBiometricAllowed` check).
- The biometric unlock sheet no longer crashes the app (#78, the r9
  device finding, build 13): tapping "Unlock with biometrics" died with
  `IllegalArgumentException: Negative text must be set and non-empty.`
  from androidx.biometric's `PromptInfo.Builder.build()` --- a
  CryptoObject-bound `BIOMETRIC_WEAK` prompt cannot combine with
  `DEVICE_CREDENTIAL` (the Keystore handshake only authorizes a biometric
  round), so the negative button is library-mandated, and the prompt
  never set one. It now carries a "Use PIN" negative button --- the
  truthful label, since the ERROR_NEGATIVE_BUTTON path was already wired
  to the silent CANCELLED outcome that drops the user back on the PIN
  screen. The builder configuration is isolated in
  `VaultBiometrics.promptInfoBuilder` and pinned on the JVM via
  reflection (2 new tests); `build()` itself calls `android.text.TextUtils`,
  which the unit-test stub does not implement, so the library's runtime
  enforcement stays on the r10 device checklist.
- Vault setup works on real devices at last (#76, the r8 device finding):
  the r8 DiagLog capture showed setup dying at the first seal with
  `InvalidAlgorithmParameterException: Caller-provided IV not permitted` ---
  AndroidKeyStore's default randomized-encryption policy rejects
  caller-provided IVs on ENCRYPT, so `VaultCrypto`'s caller-generated IV
  (fine on the JVM software keys the tests use) made EVERY real-device
  vault setup fail since the original #32 slice. The seal init now uses
  the canonical provider-IV pattern first (`init(ENCRYPT_MODE, key)` +
  `Cipher.getIV()` --- the same pattern the biometric slice already used),
  with a caller-IV fallback for exotic providers; the ADR-0017 envelope
  and the decrypt side are byte-identical. 3 new JVM tests pin the
  negotiated-IV contract.
- The Photos timeline no longer crashes when a refresh lands mid-scroll
  (#76, the r8 crash, symbolicated via the release `mapping.txt`):
  `IndexOutOfBoundsException: Index: 60, Size: 60` --- the staggered grid
  asked cell keys for indices from a layout range one frame staler than
  the refresh-shrunk paging store, and the key lambda's
  `LazyPagingItems.get` throws in exactly that window. Key/span/content
  now read the row list and the snapshot bounds-checked
  (`timelineCellKey`, new, generic, JVM-tested against the exact device
  shape); stale intervals degrade to positional keys; the content leg
  keeps the guarded presenter read that drives Paging's append hints.
- Vault setup no longer dies on exotic StrongBox keystores (#74, the
  device bug): some OEM/custom-ROM keystores abort StrongBox key generation
  with a plain `ProviderException`/`KeyStoreException` instead of the
  documented `StrongBoxUnavailableException` --- which used to abort vault
  setup outright, surfacing as the device's "vault PIN creating failed"
  with zero diagnostics. The StrongBox leg now falls back to the TEE
  keystore on ANY `GeneralSecurityException` or `RuntimeException`
  (WARN-logged with the throwable; the expected StrongBox-unavailable case
  stays INFO). Only the TEE path itself still propagates --- a keystore
  that cannot mint a regular key is genuinely fatal. Both spellings satisfy
  ADR-0017 ("inside AndroidKeyStore"); StrongBox remains defense in depth,
  never a requirement.
- Vault thumbnails can no longer be repopulated after locking (#69): a
  thumbnail decode is a suspend call, and an auto-lock/background event
  landing mid-decode used to race the still-running batch — it returned and
  published freshly decrypted pixels into the CLEARED map of a locked
  session. The guard is two-legged, per the defect report: a monotonic
  lock-session generation token (`VaultLockGeneration`, advanced at every
  unlock/lock boundary — `enterUnlockedSession` and `onBackgrounded`) that
  each batch captures at launch, plus wholesale cancellation of the in-flight
  batch jobs on lock; and the decisive half — every bitmap must re-validate
  generation AND unlocked state IMMEDIATELY before it is published
  (`VaultThumbnailPolicy.shouldPublishThumbnail`), so a stale bitmap is
  dropped, never republished. Cross-session staleness is covered too: a
  batch orphaned from a previous unlocked session can never match the new
  generation. 7 new JVM tests pin the token semantics and the 2×2 publish
  truth table (fresh+unlocked only).

## [0.3.3-alpha] — unreleased on main (versionCode 7)

### Added

- Aspect-ratio-aware staggered media grid; video duration overlay; GIF play
  indicator (#33, #34 — PR #37).
- Paging 3 incremental timeline windows + load-state layering (#28 — PR #37).
  (The paging slice was internally tagged 0.3.2-alpha on its branch; it
  shipped as part of 0.3.3.)
- MediaStore change observation wired to paging invalidation, append
  load-state footer, idempotent permission re-entry, Ready-state test tags
  (#38 — PR #40).
- Screen-state system: Loading / Error / Unsupported-file layouts,
  permission-denied state with a Settings deep-link, per-state test tags
  (#13 — PR #41).
- System Photo Picker alternative path: Settings toggle, `ACTION_PICK_IMAGES`
  flow on API 33+, picked-photos browsing view (#27 — PR #42).

### Changed

- CI: retry + /data-exhaustion hardening rounds for the (now removed) emulator
  privacy job (PRs #39, #43) — final scope decision in ADR-0016.

## [0.3.1-alpha] — 2026-10-30

### Added

- Albums tab: folder grid, album detail view, shared media UI pieces
  (#29 — PR #35).

## [0.3.0-alpha] — 2026-10-29

### Added

- Photos tab: MediaStore-backed timeline, `READ_MEDIA_IMAGES` /
  `READ_MEDIA_VIDEO` permission flow (legacy `READ_EXTERNAL_STORAGE` capped at
  API 32), four-tab shell (Photos / Albums / Search / Settings)
  (#25 — PR #26).

## [0.2.1-alpha] — 2026-10-29

### Security

- Privacy gate, offline by construction (ADR-0014): build-time
  merged-manifest INTERNET permission gate with allowlist protocol,
  `PrivacyPolicyTest` JVM tripwires, instrumented egress suite (#20 — PR #23).

## [0.2.0-alpha] — 2026-10-29

### Changed

- v2.2 product re-frame: features/UI/UX/designs only from upstreams, Kotlin +
  Jetpack Compose, no Flutter, GitHub Actions CI; Compose-native app shell —
  Compose toolchain, NavHost migration, removal of `org.fossify:commons`
  (#16, #17, #18, #19).

## [0.1.0-alpha] — 2026-10-29

### Added

- Phase 2 exit: first product APK — app shell, Material 3 theme, offline
  posture (no INTERNET permission in the manifest), CI workflow (#15).
