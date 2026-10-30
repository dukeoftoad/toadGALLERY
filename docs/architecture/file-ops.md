# File Operations (issue #30)

**Status:** Shipped (Phase 3, issue #30) · **Scope:** rename / copy / move / delete on media items, Photos tab first · **Governed by:** ADR-0006 (URI-first), ADR-0014 (offline by construction), ADR-0015 (Paging/invalidation)

Design notes for the file-ops slice: `data/MediaFileOps.kt` (ContentResolver
shell), `data/FileOpNaming.kt` (pure logic), `ui/photos/FileOpsViewModel.kt`
(state machine), `ui/photos/PhotosScreen.kt` (selection UI), plus the
selection overlay in `ui/media/MediaPagingGrid.kt`.

## 1. API matrix

| | Android 10+ (API 29+) | Android 8–9 (API 26–28) |
|---|---|---|
| File-ops UI | shown (long-press → selection) | **hidden entirely** |
| Rename (update) | provider `update`; `RecoverableSecurityException` → `createWriteRequest` consent | blocked: app holds READ only, no recovery protocol exists |
| Copy (insert) | scoped-storage media contribution — **no permission, no consent**; `IS_PENDING` + `RELATIVE_PATH` | blocked: insert needs `WRITE_EXTERNAL_STORAGE` under legacy storage, which the app does not request |
| Move | copy + delete, delete phase consent-recoverable | blocked (delete blocked) |
| Delete | provider `delete`; consent via `createDeleteRequest` | blocked |
| Post-op refresh | API 30+: ContentObserver → paging invalidation (#38) | n/a |

Below Android 10 the app is read-only **by design**: it never requests
`WRITE_EXTERNAL_STORAGE`, and Android 9- has no consent-request API to make a
write recoverable. The ops layer (`MediaFileOps`) is `@RequiresApi(Q)` and the
ViewModel is inert below Q, so there is no half-working path — long-press on
the grid simply does nothing. This mirrors the codebase's existing
honest-failure posture (screen-states.md §4): never present a control that
cannot keep its promise.

## 2. Decisions

- **URI-first, always.** Every op is a `ContentResolver` call on a content
  URI (`insert`/`update`/`delete`, `openInputStream`/`openOutputStream`).
  `RELATIVE_PATH` is a MediaStore *column*, not a raw path; the album picker
  resolves a bucket's relative path by querying one row of that bucket.
- **Copy is insert + byte-stream, never `File` I/O.** One MediaStore insert
  (`DISPLAY_NAME` from pure collision logic, source mimetype, `IS_PENDING=1`,
  optional `RELATIVE_PATH`), stream bytes, then `IS_PENDING=0` publishes the
  row. A mid-copy failure deletes the app-owned pending row so no
  half-written orphan leaks.
- **In-place copy** (no target album) reuses the source's own
  `RELATIVE_PATH` — the "Copy" action duplicates the item in its folder
  ("name (copy).ext").
- **Move = copy, then delete — strictly sequential per item.** A failed copy
  changes nothing. A failed delete after a successful copy yields
  `Partial`: **the copy is never lost**; it stays in the target album and the
  undo/consumers see both anchors (`createdUri` + `sourceAlbum`).
- **Permanent delete in v1.** The confirmation dialog says so verbatim
  ("no recycle bin yet — this can't be undone"). Delete has no undo;
  rename/copy/move snackbars do (see §4).
- **Consent is aggregate + single retry round.** The first pass attempts
  ops plainly; consent-blocked URIs are collected and ONE
  `createWriteRequest`/`createDeleteRequest` covers the whole batch (one
  system dialog, never N). On `RESULT_OK` only the remaining work re-runs —
  a consent-blocked move deletes just the originals (copies already exist).
  A still-blocked retry is reported as an honest failure rather than
  looping dialogs.
- **No hand-refresh.** Ops rely on the #38 ContentObserver →
  `PagingSource.invalidate()` pipeline (API 30+; see §5 for the 29 gap).
- **Album data comes from the existing `MediaStoreSource.queryAlbums()**
  (issue #29's query) — no second album pipeline. The picker re-queries on
  every open so counts stay honest after moves.
- **Batch UX.** Long-press enters selection; the app bar counts (plurals);
  Rename is single-selection-only; batches over 5 items show the cancellable
  progress dialog ("Moving 3 of 12…") driven by the ops layer's
  `(completed, total)` progress callback. Cancel keeps the honest prefix
  (per-item results already delivered); cancellation is never swallowed —
  the partial summary is published and the `CancellationException` rethrown.

## 3. The `DeleteMode.TO_TRASH` seam (for #31, recycle bin)

`DeleteMode` (pure enum) ships `PERMANENT` (v1 behavior) and `TO_TRASH`,
which `MediaFileOps.delete` **refuses** (`check` fails fast) — no caller can
silently trade trash semantics for permanent deletion. When #31 lands:

- `delete(mode = TO_TRASH)` becomes an `update` setting `IS_TRASH = 1`
  (API 29+; scoped storage owns the trash flag), not a `delete`;
- the consent flow reuses `createWriteRequest` (trashing is a write);
- the UI confirmation copy changes ("moves to the recycle bin, 30 days");
- the trash view/bucket becomes queryable (`IS_TRASH = 1` selection) and
  restore/empty actions slot in beside these ops unchanged.

The pure layer (`FileOpOutcome`, `summarizeBatch`) already models outcomes
that trash will need (success/consent/failure per item); only the
delete path branches.

## 4. Undo semantics (and their honest limits)

| Op | Undo | What actually happens |
|---|---|---|
| Rename | rename back to old name | exact inverse (extension strip/restore is symmetric) |
| Copy | delete the created copies | app-owned rows — no consent needed |
| Move | move the created copy back to the source album | **new** provider row in the source album; scoped storage cannot resurrect a deleted row id, so the restored item is content-identical but has a new `_id`/URI |
| Delete | none | permanent in v1 |

A failed undo reports `file_ops_undo_failed` — never a silent no-op.

## 5. Limitations (tracked, not hidden)

- **API 29 post-op staleness:** the timeline observer (#38) is API 30+; on
  Android 10 the grid refreshes on next tab re-entry (documented snapshot
  behavior since #28). All ops still applied correctly.
- **Cancelled move can leave both entries:** if Cancel lands between a
  move's copy and delete, the item exists in both albums. The completion
  snackbar reports what landed; manual cleanup is possible (the copy is
  app-owned, so deleting it needs no consent). Making cancel atomic per
  item is a #31-hardening candidate.
- **Consent retry is single-round:** a provider that still refuses after a
  granted request is reported as failure (bounded, no dialog loop).
- **Name-collision probing is racy by nature:** another process can take a
  name between the probe and the insert; MediaStore's own dedupe (it
  appends " (1)" on collision) is the backstop, and a hard collision
  surfaces as `INSERT_FAILED`.
- **Removable storage:** v1 addresses the primary external volume only
  (`MediaStore.VOLUME_EXTERNAL` buckets); SD-card volumes need per-volume
  handling (insert with the volume's collection URI) — deferred with the
  broader removable-storage slice.
- **Partial batch summaries:** deletes blocked by consent mid-batch surface
  after the consent round; a user-declined consent reports both what
  succeeded (with undo where applicable) and the decline.

## 6. Test tags (instrumented, manual runs — ADR-0016)

`file_ops_app_bar` (selection top bar), `file_ops_actions`,
`file_ops_action_rename|copy|move|delete`, `file_ops_cell_check` (grid
overlay), `file_ops_dialog_rename` / `file_ops_dialog_rename_field`,
`file_ops_dialog_delete`, `file_ops_dialog_albums` + `file_ops_album_row`,
`file_ops_dialog_albums_root`, `file_ops_progress`. The plain grid keeps its
existing `photos_state_grid` tag in both modes.
