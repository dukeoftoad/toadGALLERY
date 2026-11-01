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

---

**Sign-off:** record results per section; any fail gets a logcat snippet
and a new issue filed against milestone **Phase 3** (#4) — that milestone
stays open until this checklist passes on the reference device.
