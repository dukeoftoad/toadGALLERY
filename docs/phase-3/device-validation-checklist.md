# Phase 3 device-validation checklist — v0.4.1-alpha (r12)

**Status:** pending device validation (the Phase 3 exit work + the r11
fix; see `roadmap.md`'s Phase 3 progress note).\
**Target build:** `toadGALLERY-0.4.1-alpha-r12.apk` from the
`v0.4.1-alpha-r12` pre-release (versionCode 16). r11 fixed the
authenticator class (`BIOMETRIC_STRONG` end to end; section 13); r12
makes the Phase 4 pipeline live at app start for the first time (the
scan driver + capture-date adoption, section 14) — still with no UI
consuming the catalogue, so the pipeline's device proof is the
diagnostics ring and the no-new-FATAL scan. Sections 1–11 carry over
from r8/r9 (8 from r7, 3 from r8, 1 fixed in r9's round); section 12
is the r10 smoke pass; section 13 is the r11 pass.\
**Reference device:** the Xiaomi device used for the 2026-10-30/31 and
11-01 logcat reviews (`c2.qti.hevc.decoder` hardware; 10-bit HEVC Main10
sample in MKV; `getFrameAtTime` NULL-frame samples; the build-11/12
"vault PIN creating failed", the r8 `Index: 60, Size: 60` crash and
the r9 `Negative text must be set` biometric crash); the build-14
validation ran on the user's Redmi veux (Android 13), which reproduced
the same crash one validation later.\
**Method:** install over the previous build, run each section, note
pass/fail + a logcat snippet for anything unexpected. For vault/diagnostic
sections also capture the in-app log: `adb logcat -s ToadDiag ToadVault`
(or copy the ring from Settings → About → Diagnostics).
Sections 1–8 map 1:1 to the Phase 3 wave-1/wave-2 issues (#62–#69).

---

## 1. Search tab v1 (#62)

- [ ] Search tab opens with the prompt state (no results rendered for an
      empty query; nothing scans MediaStore until text is typed).
- [ ] Typing a filename substring (try mixed CASE, and a query containing
      `%` or `_`) returns the expected photos AND videos, newest first.
- [ ] Photo/Video filter chips narrow correctly; "All" shows both.
- [ ] Tapping a result opens the full-screen viewer at that item, and
      paging left/right from inside the viewer walks the result set.
- [ ] Clearing the query returns to the prompt state; a nonsense query
      shows the no-results state.

## 2. Viewer operations (#63)

- [ ] Info action in the viewer top bar opens the details sheet
      (name/type/size/dimensions/duration/dates/album) for the CURRENT
      page; dismissed by outside tap; closes on page swipe.
- [ ] Share from the viewer hands the file to the system share sheet
      (verify a real target receives a playable/viewable item).
- [ ] Rename from the viewer updates the item name everywhere (grid,
      info sheet, MediaStore) and stays renamed after an app restart.
- [ ] Delete from the viewer routes the item to the system trash
      (consent dialog where the platform requires it) and the grid
      invalidates.

## 3. Decoder fallback + honest errors (#65) — the 10-bit HEVC case

- [ ] The 10-bit HEVC (Main10) MKV that failed on `v0.4.0-alpha-r3`
      (`format_supported=NO_EXCEEDS_CAPABILITIES`, CodecException in
      `queueInputBuffer`) now **plays in-app**: ExoPlayer falls back to
      the software `c2.android.hevc.decoder` (first frames may take a
      beat longer to start — acceptable; note the stall duration).
- [ ] Its grid thumbnail still renders the fallback plate if the
      platform retriever cannot extract a frame — plate + duration chip,
      never a blank cell.
- [ ] A genuinely undecodable file (corrupt container) shows the honest
      playback-error copy, not a frozen black surface, and the viewer
      stays interactive (back, swipe).

## 4. Album detail parity (#64)

- [ ] Long-press in an album starts selection with the same count/actions
      bar as Photos; rename (single), copy, move, delete, move-to-vault
      all work from an album's grid exactly as from the timeline.
- [ ] A delete from album detail lands in Settings → Recycle bin and
      restores to the same album.

## 5. Vault biometric unlock (#57, ADR-0019; the r10 fix)

> **r11 addendum — re-run this round on build 15 with updated
> expectations.** r10 fixed the prompt's missing negative text, but the
> same tap still crashed on build 14 (the authenticator class itself,
> not the negative button); r11 fixes that. On build 15: (1) the
> biometric sheet must APPEAR (no crash) with the **Use PIN** negative
> button; (2) a successful fingerprint opens the vault; (3) **Use PIN**
> returns to the PIN screen; (4) the enroll toggle round likewise; (5)
> IF the device's fingerprint is not Class 3, the biometric affordances
> are absent and PIN unlock works — that is the intended degradation,
> not a failure. Section 13 is the build-15 pass record.

- [ ] **The r10 regression check first (build 13 crashed here):** with
      biometrics enrolled and the binding ON, tapping "Unlock with
      biometrics" shows the biometric sheet — NOT a crash. The sheet
      carries a **Use PIN** negative button (new in r10; required by
      androidx.biometric because the CryptoObject-bound BIOMETRIC_WEAK
      prompt has no device-credential fallback).
- [ ] With biometrics enrolled, the vault setup offers the opt-in
      (default OFF); enabling it prompts the hardware keyguard and
      succeeds — the ENROLL sheet also shows the Use PIN button and
      does not crash.
- [ ] Locked vault shows "Unlock with biometrics"; a successful prompt
      opens the same unlocked session a correct PIN does.
- [ ] The prompt's Use PIN button returns to the PIN screen silently
      (no error snackbar; the CANCELLED path is quiet by design).
- [ ] Adding a new fingerprint in system settings invalidates the
      binding on next biometric attempt: the app removes the dead
      binding and shows the re-enable nudge (PIN still works).

## 6. Vault thumbnails (#58) + the lock-race guard (#69) — the owner's defect scenario

- [ ] Unlocked vault list shows decrypted thumbnails (photos AND videos),
      size-bounded; unsupported/corrupt blobs degrade to the placeholder
      plate without a crash.
- [ ] **The race scenario**: with the vault unlocked and a large vault
      (enough entries that thumbnails keep decoding for a few seconds),
      press Home (background the app) IMMEDIATELY after unlock while
      thumbnails are still filling in, then `adb shell dumpsys meminfo
      io.github.dukeoftoad.gallery` / re-open the app: thumbnails must be
      GONE (locked state), and nothing may render or re-populate them
      while locked. Re-open should show the PIN/biometric gate, not the
      previous thumbnails.
- [ ] Long-press Home / split-screen or any ON_STOP path reproduces the
      same wholesale clear.
- [ ] Restore + delete from the vault prune/add thumbnails correctly
      within the same session.

## 7. Favorites + sorting (#66)

- [ ] The heart toggle in the viewer persists a favorite across restarts
      (verify in a second gallery app if handy).
- [ ] The sort menu (newest/oldest/name) re-orders the timeline and the
      choice sticks; the favorites filter shows exactly the flagged
      items.

## 8. Regression pass (prior rounds on this build)

- [ ] Timeline paging still invalidates after external adds/removes
      (camera shot while app open appears; deletes from another app
      disappear).
- [ ] Recycle bin restore/delete/empty still behaves (r3-verified flows).
- [ ] Privacy gate: `adb logcat` shows zero network attempts during all
      of the above (the APK holds no INTERNET permission — verify once
      with `aapt dump badging` or an install-time check if convenient).
- [ ] No `IndexOutOfBoundsException` in logcat while scrolling the
      timeline during/after a refresh (the r8 `Index: 60, Size: 60`
      crash; try: scroll deep, then change sort or take a photo with
      another app while toadGALLERY is open, keep scrolling).
- [ ] No new ANRs/crashes in logcat during the whole pass.

## 9. Vault setup failure scenario + integrity round 2 (#73/#74/#76) — the build-11/12 defect report

- [ ] On the SAME device where builds 11/12 showed "vault PIN creating
      failed": vault setup now SUCCEEDS (the seal init uses the
      provider-IV pattern AndroidKeyStore requires; the r8 DiagLog
      capture pinned the caller-IV rejection as the root cause).
- [ ] If it still fails: Settings → About → Diagnostics shows the exact
      failure (op + exception chain) under the vault state probe, and
      `adb logcat -s ToadDiag ToadVault` carries the two-line story —
      paste that ring into the issue instead of a bare "failed".
- [ ] Diagnostics state probe matches disk truth: NotConfigured before
      first setup (pin/index absent), Configured after (both present,
      blob count 0), counts grow with moves and shrink with restores.
- [ ] Setup survives an app restart mid-flow only at completed points:
      killing the app DURING setup (if you can catch it) leaves either a
      clean NotConfigured (rollback held) or a working Configured — never
      a half-configured vault (the pre-#73 pin-only residue).
- [ ] If the device carried pin-only residue from a pre-#73 build: first
      launch of r8 self-heals it and setup works (check the ToadVault INFO
      line for the sweep).

## 10. On-device diagnostics (#74)

- [ ] Process start line in logcat: `adb logcat -s ToadDiag` shows the
      `versionName (build 12)` line at cold start — attribution anchor.
- [ ] Settings → About → Diagnostics: ring renders entries oldest-first,
      Copy puts the full ring on the clipboard (paste somewhere to
      verify), Clear empties it.
- [ ] Ring privacy: nothing in the ring or logcat ever contains the PIN
      value, key material, or vault entry display names — op names,
      counts, ids, exception classes only.

## 11. Appearance settings (#74)

- [ ] Settings → Appearance: Layout COLLAGE↔GRID switches the Photos
      timeline immediately; COLLAGE (default) is byte-identical to r7's
      render; GRID shows uniform cells.
- [ ] Photos per row steppers clamp at 2 and 6 and apply live.
- [ ] Group by Day/Month renders full-line section headers before each
      group's first cell (correct boundaries across month/year edges);
      None (default) shows none.
- [ ] Choices survive an app restart.

## 12. r10 smoke pass — the Phase 4 foundation is invisible by design

The r10 round also lands the Phase 4 contract foundation (media
identity/eligibility, revision + date resolution, metadata seams, the
Room media catalogue, the MediaStore change feed, the read boundary,
the canonical decode plan). None of it has UI or a scan driver yet, so
there is nothing feature-shaped to probe — this section is a pure
regression smoke pass.

- [ ] App starts, grid loads, scrolling is smooth, no new
      `AndroidRuntime` FATAL lines in logcat across the whole session
      (the new code must be inert until a consumer wires it up).
- [ ] About → Diagnostics: the vault state probe and the log ring still
      work; the build line reads `0.4.1-alpha (build 16)` (r12 bump).
- [ ] The r9 vault behaviors still hold on this build: setup commits
      (`ToadVault: setup: committed`), unlock with PIN works, the
      auto-lock timer fires, and section 5's biometric round passes.
- [ ] No unexpected process restarts while browsing/editing/moving
      items (the Phase 4 code adds zero startup hooks on purpose).

## 13. r11 pass — the authenticator-class fix on build 15

r10's "Use PIN" fix moved the same crash one validation later: build 14
still died on the same tap, now with
`IllegalArgumentException: Crypto-based authentication is not supported
for Class 2 (Weak) biometrics.` — the library tests the REQUESTED
authenticator flags (the CryptoObject-bound `BIOMETRIC_WEAK` prompt),
not the device's sensor class, so the path crashed on every device. r11
requests `BIOMETRIC_STRONG` end to end. Re-run the section-5 biometric
round (with the r11 addendum) on build 15, in whichever branch the
device falls into, plus the PIN regressions.

### The section-5 biometric round, re-run on build 15

Class 3 branch (device has a Class 3 fingerprint sensor and
enrollment):

- [ ] With biometrics enrolled and the binding ON, tapping "Unlock with
      biometrics" shows the biometric sheet — NOT a crash — and the
      sheet carries the **Use PIN** negative button.
- [ ] A successful fingerprint opens the same unlocked session a
      correct PIN does.
- [ ] The sheet's Use PIN button returns to the PIN screen silently
      (the CANCELLED path is quiet by design).
- [ ] The enroll toggle round likewise: with the vault unlocked,
      enabling "Unlock with biometrics" prompts the hardware keyguard,
      succeeds, and that sheet also shows Use PIN and does not crash.

Class 2 branch (no Class 3 sensor, or no Class 3-enrolled fingerprint):

- [ ] The vault shows NO biometric affordances (no "Unlock with
      biometrics" affordance; the enroll toggle is absent/disabled):
      the readiness probe now uses `canAuthenticate(BIOMETRIC_STRONG)`,
      so the device lands in the `Unsupported` readiness.
- [ ] PIN unlock works exactly as before — that degradation is the
      intended behavior, not a failure (a Class 2 biometric cannot
      authorize an auth-per-use AndroidKeyStore key at all).

### Vault PIN regression (unchanged by r11)

- [ ] Vault setup commits (`ToadVault: setup: committed`); unlock with
      a correct PIN opens the session and a wrong PIN is rejected.
- [ ] Auto-lock on background still fires; the locked vault requires
      the PIN (or, on the Class 3 branch, biometrics) again.

### No-new-FATAL scan — with the stale-buffer guidance

- [ ] No `AndroidRuntime` FATAL lines attributable to build 15 across
      the whole session. **Stale-buffer guidance:** the previous device
      log may still contain FATAL entries from older builds until the
      device is rebooted — the build under test's crashes are
      identified by the per-frame `r8-map-id` hash (all frames of one
      APK share one id; compare against the current build's), so old
      map-ids do NOT count as current-build findings.

## 14. r12 pass — the scan pipeline's first live run (build 16)

r12 wires the Phase 4 scan driver into app start: per-volume MediaStore
walks, reconciler diffs against the Room catalogue, generation-token
checkpoints, ContentObserver rescans, tombstoning — plus the
capture-date indexer that reads EXIF evidence once per added/changed
row and persists the resolved instant onto the record. Still NO UI
consumes the catalogue, so the device proof is diagnostics + stability:

- [ ] Cold start succeeds and the grid loads normally; no new
      `AndroidRuntime` FATAL lines attributable to build 16 (same
      stale-buffer `r8-map-id` guidance as section 13).
- [ ] About → Diagnostics ring contains BOTH boot lines: the W1 build
      line and `ToadApp: phase 4 scan driver started (catalogue +
      change feed + capture-date indexer wired)`. A boot line without
      the pipeline line pins a construction failure to the wiring seam.
- [ ] First run on a populated library: within a few seconds the
      pipeline performs its initial full scan. There is no
      catalogue-browsing UI yet, so the observable proxy is the ABSENCE
      of scan-related FATALs/ANRs and no main-thread jank at startup
      (the pipeline runs on Dispatchers.Default by design).
- [ ] Add a photo (camera or file copy) while the app is alive, then
      background and foreground the app (or wait for the observer):
      the process stays alive and stable — the observer-driven rescan
      path is exercised without a visible surface.
- [ ] Delete a photo, repeat the foreground cycle: stable, no FATAL
      (the tombstone path is exercised).
- [ ] Reboot the device and repeat a cold start: the second pass must
      be CHEAP (same generation token → skip decision), observable as
      a normal-speed startup with no scan-related log spam.
- [ ] Room migration: upgrading from the r11 build (versionCode 15,
      catalogue DB v1) to this build keeps the app healthy — install
      r12 OVER r11 (do not uninstall), start once, run this section;
      the v1→v2 migration must not lose data or crash (non-destructive
      policy, `MIGRATION_1_2`).

## 15. r13 pass — the timeline reads capture dates (build 17, UNRELEASED)

Per the maintainer directive (2026-11-01) no release tags are cut until
Phase 5 completes; build 17 rides CI from main (or a local
`assembleDebug`/`assembleRelease`). When it reaches the reference
device, verify:

- [ ] First cold start on a fresh install: grid paints immediately
      (the MediaStore fallback path while the first scan populates the
      catalogue) --- no blank timeline waiting on the scan.
- [ ] After the first scan completes (seconds on a populated library),
      leave and re-enter Photos: the timeline is now catalogue-driven;
      scrolling is smooth with no duplicate/missing items at window
      boundaries.
- [ ] An item whose EXIF capture date differs from its import date
      (e.g. a scanned or re-saved photo) now sorts/groups under its
      CAPTURE day, not the import day (enable Settings → Appearance →
      Group by → Day to see headers).
- [ ] With Group by → Day, headers agree with the effective dates;
      a pre-1970 scan (if available) lands under its own day header.
- [ ] Camera shot while browsing: appears after the scan reconciles it
      (typically seconds --- the documented eventual consistency), and
      the grid does not jump or duplicate.
- [ ] Favorites badge still shows (the ride-along column round-trips),
      video duration chips show, COLLAGE aspect layout still correct.
- [ ] Room v2 → v3 upgrade over an r12 install is clean (install build
      17 over build 16, do not uninstall).
- [ ] No new `AndroidRuntime` FATAL lines attributable to build 17
      (stale-buffer `r8-map-id` guidance as section 13).

## 16. r15–r18 pass — the Phase 4 completion quadruple round (build 22, UNRELEASED)

Four parallel rounds landed as one build: r15 metadata editing, r16 the
on-device map, r17 filter chips, r18 the format-audit engine. Per the
maintainer directive no release tags are cut until Phase 5 completes;
build 22 rides CI from main. When it reaches the reference device,
verify:

- [ ] Upgrade path: install build 22 OVER the r13/r14 build (do not
      uninstall) — Room v3 → v4 (the three location columns) must
      migrate cleanly (non-destructive `MIGRATION_3_4`).
- [ ] r15: open Details on a JPEG, edit the capture date (and offset),
      save — the row re-renders with the new value; after the scan
      pipeline reconciles (seconds), the timeline re-sorts the item to
      the edited day (the documented eventual consistency).
- [ ] r15: editing a file the app cannot write (another app's media on
      Android 10+) shows the honest read-only / needs-grant states — on
      API 30+ the system "allow editing" dialog appears and a grant
      retries the write once; a denial never loops the dialog.
- [ ] r15: keywords/rating rows show their "later round" states (the
      pinned exifinterface cannot write those tags) — never a fake
      success.
- [ ] r16: the Map tab shows the bundled world outline fully offline
      (no network tile fetch exists in the app). With location consent
      granted, geotagged photos appear as dots after the scan indexes
      them; tapping a dot lists that location's items and opens the
      viewer.
- [ ] r16: with consent OFF (API 29+) the map shows the honest
      consent-off state pointing at Settings; dots for items first
      scanned while ungranted appear only after their next content
      change (the documented re-index limitation, surfaced as the
      pending footnote).
- [ ] r17: the Photos chips row filters the timeline on both paths —
      toggle Photos/Videos/GIFs/Favorites shortly after a cold start
      (MediaStore path) and again once warm (catalogue path); the
      filtered empty state keeps the chips visible; clear-all restores
      the full timeline; the Albums Collections cards hand off
      pre-filtered.
- [ ] r18: no visible surface by design (engine + audit document); the
      Details MIME row still renders and no new FATALs occur.
- [ ] No new `AndroidRuntime` FATAL lines attributable to build 22
      (stale-buffer `r8-map-id` guidance as section 13).

## 17. r19 pass — the code-improvement round (build 23, the release resumption)

Build 23 releases the r13–r19 backlog per the maintainer directive of
2026-11-02 (releases resume). The r19 code-improvement round was
driven by a full three-part static audit; this pass exercises its
user-visible outcomes on the reference device.

- [ ] **Favorites shows videos** (the `media_type` fix): favorite one
      VIDEO and one photo, then open Albums → Favorites — both appear;
      no audio rows leak in (the old selection treated 2 = AUDIO as
      VIDEO and dropped every favorited video).
- [ ] **Search type chips**: the Videos chip returns only videos; All
      returns images+videos and never audio.
- [ ] **Folder chip + picker (the r19 feature)**: cold start (MediaStore
      path) — the leading Folder chip opens the picker sheet with
      folder names + counts; picking a folder narrows the timeline;
      "All folders" clears it. Repeat once warm (catalogue path) — the
      same filter must serve from the catalogue (Room v5 bucket
      columns). The selection survives a sort change and a
      backend flip.
- [ ] **Folder sheet honesty**: revoke media permission while on
      another tab, then open the folder sheet → honest error + retry
      (never a silent empty list, never a crash).
- [ ] **Albums error state**: with permission revoked, visit the
      Albums tab → Error + retry renders (no crash, no eternal
      spinner); re-grant → retry reloads. Album detail under the same
      conditions shows Error (not "folder is empty").
- [ ] **Viewer zoom-lock fix**: zoom a photo, delete it from the top
      bar → the rebuilt pager swipes immediately (previously the swipe
      stayed locked until an accidental gesture).
- [ ] **Video lifecycle**: play a video, background the app → audio
      stops; return → playback resumes (only if it was playing);
      swipe to the next page mid-play → the previous page's audio
      stops.
- [ ] **Columns everywhere**: change Settings → Appearance → "Photos
      per row" → the Trash, Search and move-picker grids honor it
      (previously hardcoded 3).
- [ ] **Vault move-vs-unlock (the P0 race)**: start a move-to-vault of
      a large video, then unlock the vault while the move runs → no
      data loss, no dangling index entry, no orphan deletion of the
      in-flight blob (the store-level mutex + sweep grace). Verify the
      moved item still opens and the original is gone only after the
      move completes.
- [ ] **Details view on an old item**: open Details for an item scanned
      pre-r19 → renders normally (the shared catalogue instance + the
      keyed `canonicalUri` lookup replaced the per-visit DB + full
      table scan).
- [ ] **GPS editor honesty** (r15 follow-up): enter `NaN` or `0x1p3` in
      the GPS editor → refused (previously it wrote GPS 0,0 into the
      file).
- [ ] No new `AndroidRuntime` FATAL lines attributable to build 23
      (stale-buffer `r8-map-id` guidance as section 13).

---

**Sign-off:** record results per section; any fail gets a logcat snippet
and a new issue filed against milestone **Phase 3** (#4) — that milestone
stays open until this checklist passes on the reference device.

## 18. r20 pass — the scan-debt round (versionCode 24; NO device build —
       per maintainer directive, no builds until Phase 5 completes)

This round ships as CI APK artifacts from main only (see the roadmap's
r20 status note). The items below are the device checks for whenever
the next installable build happens (the Phase 5 completion build);
they are written now so the round's behavior changes carry their
verification plan with them.

- [ ] **Room v6 upgrade (the healing stamp)**: install the next build
      over a build-23 install → the app opens normally (no
      identity-hash crash; the migration is one ADD COLUMN), and the
      first launch's diagnostics show a `provenance healing pass
      offered N rows` line while pre-r20 rows converge (a library
      scanned entirely before r20 offers up to 1000 rows per start
      until drained).
- [ ] **Healing convergence**: force-stop mid-heal (or let one happen)
      → the next start continues from where it stopped; a row whose
      indexing repeatedly fails is re-offered every start (bounded),
      never crashes the pass, and never blocks the scan.
- [ ] **Combined pass on-device**: an EXIF-carrying JPEG gains BOTH
      its timeline date and its map dot from one launch (the map
      after the ACCESS_MEDIA_LOCATION grant heals ungranted rows via
      the stamp reset on their next change); a PNG/video item costs
      no EXIF read at all (capability-first).
- [ ] **Volume hot-plug**: with the app running, insert an SD card
      with media → the new volume's items appear without an app
      restart (the mount broadcast registers the observer + fires a
      coalesced scan); ejecting the card leaves its items intact in
      the catalogue until it returns (no mass tombstone).
- [ ] **Vault batch move**: select 10+ items → move to vault → the
      whole batch seals in one pass (one index rewrite; materially
      faster than the per-item cycle on large batches), per-item
      failures narrate alone, and a consent-blocked delete still
      offers the existing retry path for exactly the blocked items.

---

## 19. r29 pass — the Gallery ↔ Edit contract round trip (Issue #14 gate)

**Build target:** the next gallery build (versionCode 33) PLUS the
`:apps:edit` prototype APK (`io.github.dukeoftoad.gallery.edit(.debug)`,
versionCode 1) — the gate is the first round that needs BOTH APKs
installed from the same commit. Dogfooding sections 13–18 stays as
written; this section is the contract round trip itself.

- [ ] **Launch with the grant**: viewer → open an image → the Edit
      (pencil-on-paper) op → toadGALLERY Edit opens showing the photo
      (the read-only grant scoped to that one URI; no permission dialog
      on Q+ for the gallery side).
- [ ] **Modify honestly**: drag brightness/contrast (live preview;
      release commits ONE undo step), rotate left, undo/redo walk the
      committed history, back then re-enter shows a fresh session
      (nothing was persisted mid-edit).
- [ ] **Save copy**: Save copy → the editor closes with RESULT_OK → the
      gallery's viewer/snackbar narrates the saved copy → the timeline
      shows the new `-edit.jpg` WITHOUT any manual rescan (MediaStore
      observation) → the original is byte-identical (hash compare before
      and after the round trip).
- [ ] **Metadata honesty**: with `preserve` (the v1 default) the copy
      keeps the source's EXIF; the editor's always-visible policy line
      never claims a strip that did not happen.
- [ ] **Cancel**: mid-edit back → the gallery narrates nothing, no new
      MediaStore entry appears, the original is untouched.
- [ ] **Structured failure**: point the launch at an undecodable file
      (e.g. a RAW renamed to .jpg via a debug intent) → RESULT_ERROR
      with `ERROR_UNSUPPORTED`, the gallery narrates the failure, no
      partial entry exists.
- [ ] **Not-installed fallback**: uninstall the editor → the Edit op
      narrates the honest message and offers the third-party chooser;
      choosing a third-party editor still works (read-only grant rides
      the chooser).
- [ ] **Version refusal**: (debug path) lower the editor's meta-data to
      0 in a local build → the gallery refuses the launch with the
      outdated narration — never a launch.
- [ ] **Zero egress during AI-free editing**: the editor runs with no
      network capability at all (its merged manifest carries no INTERNET;
      the editor privacy gate proves it at build time).

---

## 20. r31 pass — the export robustness matrix (Phase 6 exit items)

**Build target:** the gallery build from §19 PLUS the editor APK at the
r31 tip (versionCode 3) — the per-export metadata-policy choice (r31
wave 1) and the export preflight (r31 wave 2) are the code-side half of
the Phase 6 exit items; this section is the physical half. The JVM
decisions behind every row are pinned in `ExportPolicyTest` /
`ExportPreflightTest`; only the on-device behavior needs a human.

- [ ] **Policy choice surface**: Save copy → the sheet shows "Metadata
      for this copy" with the three policy chips; the launch request's
      policy (`preserve` from the gallery) is preselected; the note
      captions state the copy-only scope (the original is never
      modified).
- [ ] **Opt-in location strip (GPS verification)**: pick a photo WITH
      GPS tags → export with "metadata preserved, location removed" →
      the copy carries NO GPS tags (any EXIF inspector), every other
      EXIF tag survives, the ORIGINAL still has its GPS tags
      (byte-identical hash), and the gallery's narration reports the
      location removal (the applied-policy echo).
- [ ] **Opt-in full strip**: export with "metadata stripped" → the copy
      carries no EXIF at all; the original is untouched.
- [ ] **Privacy floor (debug path)**: launch the editor with the
      request's policy set to `strip_location` (debug intent) → the
      "metadata preserved" chip is DISABLED with the floor caption ("The
      gallery requested at least: …"); no combination of taps can make
      the applied policy strip less than the request.
- [ ] **Honesty line tracks the pending choice**: with the sheet open on
      an upgrade choice, the always-visible policy line narrates the
      CHOSEN policy; closing the sheet reverts it to the request's.
- [ ] **Large-file guard**: open a >48 MP source (e.g. a 108 MP
      capture) → Save copy → RESULT_ERROR `ERROR_UNSUPPORTED` with the
      too-large narration, no crash, no partial MediaStore entry; a
      12 MP control source exports normally on the same device.
- [ ] **Low-storage preflight**: (dev setup) fill storage until the
      allocatable bytes drop under the estimate's 1.5× headroom → export
      → RESULT_ERROR `ERROR_IO` with the free-storage narration BEFORE
      any insert happens (no pending row, no partial entry); free space
      → the same edit exports successfully.
- [ ] **Process death mid-export (Q+)**: start an export of a large
      image and kill the editor mid-write (`adb shell am kill <pkg>` or
      swipe-away) → no visible partial entry ever appears (the pending
      row is invisible to every app and MediaStore reclaims it); open
      the source for edit again → the session restores losslessly from
      the sidecar (the recipe survived; the sidecar clears only after a
      successful export).
- [ ] **Process death pre-Q (API 26–28 device)**: the same kill may
      leave a visible partial row — record it in the round report: the
      editor holds no delete permission by design, so the row is the
      user's to remove (the acceptance is the DOCUMENTED behavior, not
      cleanup).
- [ ] **Distinct failure narrations**: the three failure classes
      (unsupported / storage / I-O) each narrate their own honest
      message through the gallery — never a generic "something failed".

## 21. r32 pass — the editor accessibility sweep (versionCode 4, UNRELEASED)

The r32 hardening round's device half: the code asserts the semantics
(`contentDescription` + `stateDescription` on every slider); the device
confirms the ANNOUNCEMENTS, their live updates, and the touch targets.

- [ ] **The adjustment slider announcements**: with TalkBack on, focus
      each of the 10 adjustment sliders (Exposure … Sharpness) — each
      must announce its NAME and its live value, and the spoken value
      must update as the thumb moves (scrub with the volume keys).
- [ ] **The HSL band rows**: select a band (e.g. Red) — the three rows
      must announce "Red: Hue" / "Red: Saturation" / "Red: Luminance";
      switching the band must change the announced prefix.
- [ ] **The flip chips**: both flip toggles announce their label with
      the on/off text AND the selected/not-selected state; each touch
      target is at least 48dp (tap accuracy at the chip edges).
- [ ] **The straighten live drag**: dragging straighten moves the thumb
      and updates the preview DURING the drag (not only on release);
      the label's degree value follows.
- [ ] **The export quality slider**: announces "Export quality" + the
      number, updating live as the value changes.
- [ ] **The crop presets still crop correctly after the pure-inverse
      refactor**: each preset (1:1, 4:3, 3:4, 16:9, 9:16) produces the
      expected output aspect on export (the §19 round trip re-run with
      one preset applied covers this end to end).

## 22. r33 pass — the LUT filter system (versionCode 5, UNRELEASED)

The Phase 7 opener's device half: the code pins the engine (24 parser/
sampler tests), the recipe tolerance (8 tests) and the assets (9
conformance tests with hand-verified probes); the device confirms the
EXPERIENCE — the looks render, the intensity is honest, and the preview
matches the export.

- [ ] **The Filters section renders**: between the Adjust sliders and
      the Colour channels (HSL) section there is a Filters row with a
      None chip and five look chips (Warm Film, Cool Chrome, Silver
      Mono, Matte Fade, Vivid Punch), horizontally scrollable.
- [ ] **Each look applies**: tap each look — the preview must visibly
      change per the look's character (Warm Film warms and lifts,
      Cool Chrome cools and deepens, Silver Mono goes monochrome,
      Matte Fade desaturates with lifted blacks, Vivid Punch
      saturates); None returns to the un-looked image EXACTLY.
- [ ] **The intensity slider is honest**: with a look selected, drag
      the intensity — the preview follows the drag live (r32 pattern);
      at 0% the image equals the un-looked image; at 100% it equals
      the full look; ~50% sits between (squint-compare against the
      two ends).
- [ ] **Preview ≡ export**: pick a look at ~70% intensity plus one
      adjustment (e.g. brightness +20), export a copy, and compare the
      saved image against the on-screen preview — same look, same
      grade (the shared-cache + one-pipeline construction).
- [ ] **The look survives the session**: with a look selected, force-
      stop the editor and relaunch the same source from the gallery —
      the sidecar restores the session with the look chip selected and
      the intensity where it was (ADR-0011).
- [ ] **The look rides the whole pipeline**: apply a look PLUS a crop
      plus a rotation, export, and confirm the saved copy carries the
      look over the cropped/rotated frame (stage 10 runs after the
      geometry and the colour stages).
- [ ] **Undo/redo covers the look**: select a look, undo — the look
      clears; redo — it returns with its intensity (the look is one
      recipe snapshot like every other edit).
