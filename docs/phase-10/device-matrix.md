# Phase 10 — the device/API compatibility matrix (the r40 skeleton; every
# result cell is HUMAN-ONLY)

**Status:** SKELETON (r40, 2026-11-05, branch `tg-p10-hardening`). This
doc is the roadmap Phase 10 deliverable "device/API compatibility
matrix" — the STRUCTURE is the r40 round's code-side half; the results
are the dogfood half and are never claimed by a code round. Nothing in
this file invents a result.

---

## Purpose

The roadmap's hardening task list: *test low/mid-range devices, Android
16, older supported Android versions, large images, long videos,
corrupt files, and partial media permissions*, plus *test upgrades,
database migrations, editor version mismatch, and both apps
installed/uninstalled in different orders*. This file is the matrix
those tests fill. The supported API window is the build's own pins:
minSdk 26 (Android 8.0), target/compileSdk 36 (Android 16).

## The matrix

Rows: the API ladder inside the supported window, by device class.
Columns: the scenario families from the task list. Every cell is empty
by design — a cell is filled ONLY by a dogfood round (the checklist
convention: `pass` / `fail + finding ref` / `n/a (reasoned)`), never by
a code round.

| API (Android) | device class | install + clean launch | upgrade + Room migration | editor version mismatch | both-apps install/uninstall orders | large images | long videos (10+ min) | 4K / HDR clips | corrupt files | partial media permissions | vault + biometrics | a11y (TalkBack + heading nav) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 26 (8.0) | low | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | n/a pre-30 (full-permission model) | HUMAN-ONLY | HUMAN-ONLY |
| 26 (8.0) | mid | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | n/a pre-30 (full-permission model) | HUMAN-ONLY | HUMAN-ONLY |
| 30 (11) | low | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY (the §349 allow-editing dialog row's family) | HUMAN-ONLY | HUMAN-ONLY |
| 30 (11) | mid | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 33 (13) | mid | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 34 (14) | mid | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 35 (15) | mid | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 36 (16) | mid (BM-1) | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 36 (16) | high | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |
| 36 (16) | tablet | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY | HUMAN-ONLY |

Notes on the columns (what each cell's run means):

1. **install + clean launch** — first-run: permissions flow, the
   privacy gate's visible posture, timeline populates (S1 of
   `benchmark-scenarios.md` runs here).
2. **upgrade + Room migration** — the previous release installed over
   by in-place update; the migration steps run; the catalogue survives
   (the Phase 4 migration ladder is the thing under test).
3. **editor version mismatch** — a gallery build opens an edit sidecar
   recipe written by a NEWER editor build (and the reverse): the
   tolerance contract's device half (unknown keys degrade honestly, no
   crash).
4. **both-apps install/uninstall orders** — gallery and editor in each
   install order, each uninstall order, editor-removed-while-roundtrip
   sidecar present (the Phase 6 independence rule's device half).
5. **large images** — 50+ MP and panorama-class decodes: grid, viewer,
   editor open.
6. **long videos** — 10+ minute playback/trim-scout behavior; the
   Phase 9-bound video rows join here when that sub-scope lands.
7. **4K / HDR clips** — where the device supports HDR, playback +
   thumbnail + metadata extraction.
8. **corrupt files** — truncated JPEG, bad-EXIF, zero-byte, renamed
   extension: the honest-degradation surfaces, never a crash.
9. **partial media permissions** — API 30+: the "allow editing" grant
   dialog family; pre-30 rows are reasoned n/a (full-permission model).
10. **vault + biometrics** — the r9/r11/r12 biometric rows' family;
    lock/unlock under each authenticator class the device offers.
11. **a11y** — the r40 sweep's device half: heading navigation lands in
    order on every screen, the polite live regions announce the
    selection/progress counters, the 48dp touch targets receive edge
    taps (the r40 contract's own human-only rows, §"Device checklist
    rows").

## The rules the matrix obeys

1. **A code round never fills a cell.** The matrix is filled by the
   dogfood windows, results citing the run that produced them.
2. **A failed cell cites the finding**, which lands in the follow-up
   register; the cell is re-filled when the fix round's device row
   passes.
3. **The ladder is the build's own pins** — if minSdk/targetSdk ever
   move, the matrix's rows move with them in the same round (a matrix
   row outside the supported window is a lie).
4. **The device-class column is honest about BM-1** — the benchmark
   device is a mid-class API 36 row; the "defined mid-range test
   device" of the roadmap targets and this matrix's BM-1 row are the
   same device instance once the dogfood window declares it.
