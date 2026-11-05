# Phase 10 — the hardening round (r40): the a11y sweep + the boundedness
audit + the benchmark/device skeletons

**Status:** AUTHORED on `tg-p10-hardening` (2026-11-05, the r40 round; the
Phase 10 status is HARDENING-ROUND-IN-PROGRESS, not done --- see the
delivery record at the bottom). This doc is the design contract the round
implements. r40 is the FIRST round of Phase 10 and it delivers the
"HARDEN FIRST" half only: the deferred Edit v3 AI sub-scope and the
lightweight video editor sub-scope have their own design contracts
(`docs/phase-10/edit-v3-ai.md`, `docs/phase-10/video-editor.md`, authored
by the parallel design windows) and are NOT started here, per the
roadmap's explicit order.

---

## Purpose

The roadmap's Phase 10 original task list names the hardening work: measure
the performance targets, test the big libraries, verify the background work
is bounded, and --- the one task that is code-expressible without a device
--- **accessibility hardening across every screen, including the editor**.
This round delivers the code-side hardening and the two planning
deliverables the roadmap lists (the repeatable benchmark scenarios; the
device/API compatibility matrix) as skeletons the on-device rounds fill in.
The measurable rows stay human-only: this sandbox has no Android SDK, no
emulator, no TalkBack, and the repo's standing directive says the
§-numbered device rows are never claimed by a code round.

## Scope

**In scope (r40):**

1. The accessibility hardening sweep over every screen of both apps
   (gallery `ui/` tree incl. the settings sub-pages, viewer, the editor
   surface in `apps/edit`), fixing the gaps found with minimal,
   conventional edits.
2. The background-work boundedness audit over the Phase 4/5 machinery
   (scan driver + row source, the index queues + workers, the model
   provisioner, the vault thumbnail decoder), fixing what the audit finds.
3. The three docs: this contract, `benchmark-scenarios.md`,
   `device-matrix.md`.
4. JVM tests for the round's pure logic (the conformance suite + the walk
   policy), executed by the rebuilt pre-push harness.

**Deliberately out of scope (recorded):**

- The Edit v3 AI editing sub-scope and the video editor sub-scope (the
  other two Phase 10 rounds; their contracts are separate files this
  round must not touch).
- Every dogfood / device-checklist row (§1--§28 of
  `docs/phase-3/device-validation-checklist.md` and the ente-port dogfood
  rows): HUMAN-ONLY per the standing directive --- never edited by this
  round, never claimed.
- `progress.json` (the F-12 convention: merge-time truth only).
- CI workflows (no `.github/` changes this round).
- Baseline Profiles: the roadmap says "consider ... if beneficial" --- the
  profiling that would justify them is a device-only act, so the decision
  is deferred with the benchmark rows (recorded in
  `benchmark-scenarios.md`).
- The measured performance numbers: every result cell in the two new
  skeletons stays "human-only" --- nothing is invented.

## The design decisions worth making now

1. **HARDEN FIRST is an ordering constraint, not a phase label.** r40
   touches no editor feature, no AI surface, no video code. The two
   deferred sub-scopes begin only after the hardening round's PR is green
   --- the repo's stated order, and the reason this round exists as the
   first Phase 10 PR.

2. **The heading convention: every primary screen title is a TalkBack
   heading.** The sweep found ZERO `heading()` semantics in the entire
   gallery tree (and the editor) before r40: a TalkBack user navigating by
   headings --- the standard way to hop between screens --- had no anchors
   at all. The fix is one modifier per title:
   `Modifier.semantics { heading() }`, applied to every `TopAppBar` title
   and the two screens whose titles are plain `Text`s in scroll columns
   (Settings, whose page title + its eight section headers all become
   headings; the Albums tab, whose four section headers --- People, Places,
   Things, Collections --- become headings; the Albums tab has no app bar of
   its own, the shell's nav bar is the screen identity). The editor's
   top-bar title joins (the editor's per-control a11y was already at the
   r32 standard --- §21 of the device checklist --- the heading anchor was
   the one gap). 17 surfaces total, all real code changes.

3. **The live-region convention: POLITE, never assertive, on the dynamic
   surfaces.** The sweep found zero live regions. The surfaces where state
   changes under the user without a focus change are exactly: the three
   selection app bars (Photos, album detail, person detail --- the count
   changes as cells are tapped while focus stays on the grid), the four
   batch-progress dialogs (the file-ops move/trash/delete dialogs on both
   Photos and album-detail sides, the vault busy dialog --- done/total
   updates), and the viewer's position counter ("n of m", which changes on
   every swipe while focus rests on the pager). All get
   `semantics { liveRegion = LiveRegionMode.Polite }` on the text that
   carries the number --- polite because none of them is urgent enough to
   interrupt speech.

4. **The touch-target floor is DesignTokens' 48dp, and the enforcement is
   the component's own `minimumInteractiveComponentSize` --- an explicit
   size override under 48dp is a bug.** The sweep found exactly one
   violation: the r39 Things collection chip's remove `IconButton` carried
   `Modifier.size(32.dp)`, which clamps the measured node BELOW the floor
   the repo mandates (`ToadTouchTarget.minimum = 48.dp`, pinned by
   `DesignTokensTest`). The fix removes the shrink: the IconButton
   enforces its own 48dp minimum again. The decorative sub-48dp glyphs
   (the favorite badge at 13dp, the selection check circles at 22dp) are
   NOT touch targets --- the surrounding cell is the target --- and stay.
   The justified `contentDescription = null` set stays too: every one of
   the 29 decorative nulls the sweep inspected is either a state glyph
   whose text sibling carries the meaning or an icon inside an already
   labeled control; no actionable icon anywhere in either app is blank
   (the r32 editor discipline held).

5. **The sweep's executable half is a source-scan conformance suite, in
   the repo's established pure-JVM style.** Compose semantics wiring cannot
   execute in the JVM harness (no android.runtime), but the wiring is text,
   and the r37 lesson ("CI compiles what's there --- it cannot test for
   absence") is exactly what a source-scan test closes.
   `AccessibilityWiringTest` (the `PrivacyPolicyTest` moduleFile pattern)
   pins: heading() on each of the 16 gallery screen files + the editor;
   the polite live region on each dynamic-surface file; the ABSENCE of a
   size shrink directly above the Things chip remove button's testTag
   (local pin --- other `.size(` occurrences are different surfaces); and
   the DesignTokens floor's existence. A future screen without the
   convention fails THIS suite, not a code review.

6. **The boundedness fix is a cooperative cancellation probe in the one
   loop that could not stop.** The audit (next section) found the
   machinery disciplined overall --- bounded batches, `ensureActive`
   checks, NonCancellable persistence, no app-set wakelock --- but the
   Phase 4 authoritative walk is a BLOCKING `while (cursor.moveToNext())`
   loop over potentially 50,000 provider rows inside
   `withContext(Dispatchers.Default)`: coroutine cancellation cannot
   interrupt it, so a cancelled app-start scan walked the full volume to
   completion before the driver's between-volumes checks ever ran, holding
   the scan mutex the whole time. The fix: `ScanWalkPolicy` (pure, tested)
   decides WHEN to probe --- every 500 rows --- the driver supplies its own
   coroutine context's `isActive` as the probe, and a positive probe
   throws `CancellationException`, the one exception the driver's
   per-volume catch rethrows untouched. No partial catalogue write: the
   walk dies before `upsertAll`, the old checkpoint survives, the next
   pass re-heals idempotently (the driver's own crash-mid-apply contract).
   The interface's new parameter defaults to a no-op probe, so the change
   stays source-compatible.

## The boundedness audit (finding -> verdict -> action)

| # | Component | Finding | Verdict / action |
| :--- | :--- | :--- | :--- |
| B1 | `MediaScanDriver` + `AndroidMediaScanRowSource` (the Phase 4 walk) | The cursor walk is a blocking loop with NO cancellation check; cancellation waited for the full-volume walk; the scan mutex was held throughout | **FIXED**: `ScanWalkPolicy.cancellationDue` every 500 rows + the driver's `isActive` probe; `CancellationException` propagates the documented way (`MediaScanRowSource.kt` walk loop; `MediaScanDriver.rescanVolume`) |
| B2 | `FaceIndexQueueProcessor` / `ClipIndexQueueProcessor` (the r24/r26 queues) | Bounded batches (`DEFAULT_BATCH_SIZE = 50`), `ensureActive()` between photos, `NonCancellable` persistence after every batch | BOUNDED --- no change (`FaceIndexQueue.kt:279-330`, `ClipIndexQueue.kt:336-393`) |
| B3 | `FaceIndexWorker` / `ClipIndexWorker` (WorkManager) | `CoroutineWorker` (WorkManager's own wake + cancellation discipline), unique-work chains with `ExistingWorkPolicy.REPLACE`, `MAX_RUN_ATTEMPTS = 3` retry cap | BOUNDED --- no change (`FaceIndexWorker.kt:64-170`, `ClipIndexWorker.kt:72-197`) |
| B4 | `ProvenanceHealingPass` | Batch budget `MAX_BATCHES_PER_PASS = 10` x `HEALING_BATCH_ROWS = 100`, one pass per app start | BOUNDED --- no change (`ProvenanceHealingStore.kt:97-143`) |
| B5 | `VaultThumbnailDecoder` | Downsampled decode (`inSampleSize` via `VaultThumbnailPolicy`), the tail-drain loop reads a bounded vault blob to EOF inside the `use` block (the authentication contract) | BOUNDED --- no change (`VaultThumbnailDecoder.kt:101-160`) |
| B6 | `HttpModelDownloader` + `ModelProvisioner` + `ModelVerifier` | Connect/read timeouts, redirect fail-closed, pinned content-length, streaming SHA-256, no retry loop | BOUNDED --- no change (`HttpModelDownloader.kt:64-99`, `ModelVerifier.kt:41-60`) |
| B7 | The media queries (`PeopleMediaQuery`/`MagicSearchMediaQuery`) | The 999-bound-variable chunking, the CLIP search bounded by the index count + the distance cut | BOUNDED --- no change |
| B8 | Wake behavior | The app sets NO wakelock of its own; the merged manifest's WAKE_LOCK rides androidx.work's library grant and is consumed by CoroutineWorker's own discipline (the r24 test pins the accepted pair) | CLEAN --- no change |
| B9 | `MediaScanDriver.rescanVolume`'s `catalogue.recordsMap()` | The reconciler loads the WHOLE catalogue into memory once per rescanned volume, then filters by volume --- an accepted one-time cost on the documented design, but a 50k-library memory-bounds watch item | RECORDED, NOT FIXED: a per-volume read path is a catalogue API change, not hardening; filed as follow-up F-23 |

## Tests the round owes (JVM, gallery module)

- `ScanWalkPolicyTest` — the probe period constant; never due at/below
  zero; due exactly on every 500th row; the probe COUNT of a realistic
  walk (2,000 rows -> 4 probes; 50,000 -> 100; 0 -> 0).
- `AccessibilityWiringTest` — the source-scan conformance suite (decision
  5): the heading convention across the 16 gallery surfaces + the editor;
  the live-region convention on the dynamic surfaces; the chip
  touch-target fix stays fixed; the DesignTokens floor exists. (There is
  NO strings-wiring parse test: no pure precedent for one exists in the
  repo, and the round adds no new strings to parse.)

## Device checklist rows (r40's own, HUMAN-ONLY, ride the checklist later)

The rows below are the r40 half of the accessibility hardening: the CODE
asserts the wiring; only a device confirms the ANNOUNCEMENTS and the
focus behavior. They are recorded here, human-only --- NOT appended to the
checklist by this round (the checklist is never edited by a code round;
they ride with the next docs/merge window, like §22--§27 did):

1. With TalkBack on, navigate by headings on every tab: Photos, Search,
   Albums, Trash, Settings --- the screen title (and on Albums, the four
   section headers; on Settings, the eight section headers) reads as a
   heading, and heading navigation hops between them in order.
2. Enter selection mode on the Photos tab and tap five cells: the count
   in the app bar announces each change (polite, non-interrupting); the
   same on album detail and person detail.
3. Run a batch move/copy from the selection bar: the progress dialog's
   "n of m" updates announce politely; the vault busy dialog does the
   same while restoring.
4. Open the viewer and swipe three times: "n of m" announces each swipe.
5. On the Albums tab's Things row, focus a collection chip's remove
   button: the touch target measures >= 48dp (edge taps land), and the
   announce is the existing `cd_things_remove` ("Remove collection").
6. The editor: heading navigation lands on the top-bar title; the r32
   slider/chip announcements still behave (the §21 rows re-run).

## No licensing gate, no new dependencies

Semantics wiring, a pure policy object, two JVM test files, three docs.
No third-party asset, no new artifact, no manifest change, no permission
change, no new strings (the fixes wire existing text).

---

## Delivery record (r40, 2026-11-05, branch `tg-p10-hardening`)

The round landed every decision above, with these executed facts:

1. **The heading sweep — as designed (decision 2).** 17 surfaces carry
   `semantics { heading() }` after the round: the 15 `TopAppBar` screen
   titles (Photos, Trash, Details, Vault, Places, Place detail, People,
   Person detail, Things detail, Album detail, Picked photos, Collage,
   About, Development progress + the editor's top bar) and the 2
   column-title screens (Settings: the page title + its 8 section
   headers; Albums: the 4 section headers). Zero `heading()` existed
   before the round.
2. **The live-region sweep — as designed (decision 3).** 8 dynamic
   surfaces carry the polite live region: the 3 selection app bars, the
   4 batch/progress dialogs (2 file-ops + 1 vault-move + 1 vault
   busy-restore/delete), and the viewer counter. Zero existed before.
3. **The touch-target fix — as designed (decision 4).** The r39 chip's
   `.size(32.dp)` remove-button shrink removed
   (`AlbumsScreen.kt`, ThingsCollectionChip); the decorative glyph set
   audited and left alone (every one of the 29 `contentDescription =
   null` occurrences across 18 files re-inspected; all justified).
4. **The boundedness audit — the table above, executed as written.** One
   fix (B1, the `ScanWalkPolicy` probe), eight verified-bounded
   components, one recorded watch item (B9, filed as F-23).
5. **The pre-push harness — REBUILT** (the Run #13 spirit; the old
   `/tmp/ktool` was gone): kotlinc **2.3.10** (the JetBrains release zip;
   java 21.0.13), junit **4.13.2** + hamcrest **1.3** from Maven Central
   (junit fetched via the Google maven-central mirror after a 429 from
   repo1; SHA-256 verified), `/tmp/ktool/verify.sh` compiling the round's
   PURE subset (`ScanWalkPolicy` + both test files), running it via
   JUnitCore, and lex-checking all 23 touched `.kt` files. Two harness
   iterations before green: the runtime classpath initially missed
   `kotlin-stdlib.jar`, and the conformance suite's source resolution
   needed the repo-root `-Duser.dir` under the sandbox JVM.
6. **Tests — the two owed families, all JVM, EXECUTED**: `ScanWalkPolicyTest`
   (4) + `AccessibilityWiringTest` (7) = **11 new tests, OK (11 tests)**
   under the harness before push. The a11y wiring itself is
   android-bound Compose and is exercised by the conformance suite +
   the merge-gate CI compile (the r37 discipline: the suite pins
   PRESENCE/ABSENCE in the tree, CI compiles what is there).
7. **Strings — zero new.** The sweep reused the existing `cd_*` copy
   everywhere (the fixes are semantics wiring on text that already
   exists); no `strings_r40.xml` is shipped (an empty per-round file
   would be a lying artifact). Module counts: gallery 510 -> 510, edit
   129 -> 129.
8. **Versioning — both touched apps bump** (the "app(s) you actually
   touch" rule): gallery versionCode 35 -> 36 (`0.4.3-alpha` ->
   `0.4.4-alpha`), editor versionCode 11 -> 12 (`0.4.6-edit-alpha` ->
   `0.4.7-edit-alpha`).
9. **The docs — the three deliverables**: this contract;
   `benchmark-scenarios.md` (the roadmap's target scenarios as
   human-executable steps, every result field human-only);
   `device-matrix.md` (the API x device-class skeleton, every result
   cell human-only).

*The r40 device rows (§"Device checklist rows" above) are human-only.
`progress.json` stays untouched until merge per the F-12 convention.*
