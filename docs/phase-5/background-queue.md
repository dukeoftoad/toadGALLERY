# Phase 5 workstream 5 — the background face-index queue (r24 round)

**Parent plan:** `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`
(implementation-sequence table, Workstream 5 row: "Index coordinator —
WorkManager queue, bounded concurrency, retries, resumability, progress,
invalidation").

r24 expands the r23 12-photo dogfood slice into the full-library,
WorkManager-backed indexing queue. This document records the design
decisions the round made — they bind later rounds (People UI, Magic
Search) that will grow the same queue pattern.

## The three truths one queue run consults

Every `FaceIndexWorker` run derives its plan from three persisted truths,
never from a live process:

1. **Candidates** — the Phase 4 Room catalogue's live JPEG records on the
   indexable volume (newest-first by `dateAddedMillis`), each carrying its
   observed `MediaRevision` (size, dateModified, generation token,
   metadata-write counter). The catalogue is the AI pipeline's listing
   source exactly as the Phase 4 audit intended; MediaStore itself is
   never walked by the queue.
2. **Queue bookkeeping** — `filesDir/face-index/index-state.json`, the
   atomic state file recording, per processed photo, the revision
   OBSERVED AT INDEX TIME. This is the half that makes re-planning
   incremental: a fresh `MediaRevision.diff` against a current candidate
   decides skip vs re-index.
3. **Store truth** — the ML store's own indexed-file set (the r24 JNI
   read over upstream's `MlDb::face_indexed_file_ids`, filtered to the
   vendored pin's `FACE_ML_VERSION`), used for orphan cleanup and for
   re-indexing rows the queue has no record for (the r23 dogfood
   leftovers — re-run once, delete-first).

## The invalidation mapping (the Phase 4 contract, verbatim)

`FaceIndexQueuePlanner` maps `RevisionDelta` to queue work exactly as
`RevisionDelta`'s own KDoc defines:

| Delta | Queue action |
| --- | --- |
| `UNCHANGED` | skip — embeddings stay valid |
| `METADATA_CHANGED` | skip — an app-side metadata write never burns model inference (`invalidatesAiOutputs` is false by construction) |
| `CONTENT_CHANGED` | re-index DELETE-FIRST, then insert (the r23 `putFace` ordinals append; stale rows must go first) |
| no state record | index (delete-first if the store holds rows) |
| store rows, no candidate | orphan: delete the rows (media deleted / trashed / vault-moved) |
| state record, no candidate | drop the bookkeeping with the next save |

## The execution contract (`FaceIndexQueueProcessor`)

- **Bounded concurrency** = one photo at a time: the JNI engine serializes
  inference by its own contract, so a single serialized worker IS the
  bound; bytes are read one photo at a time (one JPEG in memory, never
  the library — an improvement over the r23 card's all-photos-at-once).
- **Bounded batch** = at most 50 photos per worker run; the run
  re-enqueues itself under the single unique WorkManager name while the
  plan outlives its batch, so the chain survives process death through
  WorkManager's own persistence (Room-backed) — and cancels cleanly with
  the unique name.
- **Whole-photo resumability**: cancellation is honored BETWEEN photos
  (the r23 rule), the state gathered so far is persisted under
  `NonCancellable` on every exit path, and a killed run's partial faces
  (always whole photos) are re-derived delete-first next run — the queue
  is idempotent by construction, so a lost batch is a delay, never a
  correctness event.
- **Clustering exactly once per drain**: the vendored recluster pass
  runs only when a batch drains a non-empty plan — never mid-queue
  (partial clusters would churn on every batch) and never on an empty
  plan (a no-op run must not burn inference).
- **Honest failures**: missing engine library, unprovisioned models or a
  store-open failure are permanent failures carrying the engine's own
  message; mid-run engine faults retry with WorkManager backoff, bounded
  at 3 attempts; unreadable photos skip without a state record and are
  retried next run (visible in the card's outcome line).

## The state file (`FaceIndexStateFile`)

One versioned JSON file INSIDE the app-private face-index directory —
the same directory whose recursive delete resets the ML store, so
bookkeeping can never outlive the rows it describes. Writes are
temp-then-atomic-move (NIO `ATOMIC_MOVE` + `REPLACE_EXISTING`, plain-move
fallback for layouts without atomic rename); a crash between batches
loses at most the current batch's records, which the delete-first
re-index keeps correct. Reads are FORGIVING: missing, corrupt or
wrong-version files degrade to "nothing processed yet" — never a thrown
parse error into the worker — the same never-trust-stored-bytes posture
as the Appearance settings' parse.

## Volume boundary

The faces table keys on the MediaStore `_id` of the **external primary**
volume only (`external_primary`; `external` pre-API-29): a removable
volume's ids would collide with primary ids in the `(file_id, face_id)`
keys. `INDEXABLE_VOLUME_NAMES` pins the boundary; multi-volume indexing
waits for the hardening workstream's volume-aware reconciliation (audit
item: "volume-aware identity + revision token + reconciliation").

## Privacy posture

- **No new network surface**: WorkManager's `ACCESS_NETWORK_STATE` and
  `FOREGROUND_SERVICE` manifest injections are stripped with
  `tools:node="remove"` markers (verified against the published AAR);
  `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` are accepted and documented in
  `docs/phase-2/privacy-gate.md`'s r24 note. The JVM suite pins both
  directions.
- **Face-derived data never leaves the device**: the whole `face-index`
  directory is excluded from cloud backup AND device transfer
  (`backup_rules.xml`, `data_extraction_rules.xml`) — the ADR-0020
  manifest note's "embeddings and indexes never cross the network",
  extended to backup/transfer.
- **Vault boundary** unchanged: vault media never appear in the catalogue
  (they are not MediaStore rows while sealed), so the queue's candidate
  source is public-media-only by construction.

## What r24 deliberately defers

- People UI (Workstream 6) — the queue populates clusters; naming,
  merge/split/ignore and the People grid are the next round's surface.
- Scan-hook wiring — the queue is started from the Settings card;
  automatic enqueue on scan-detected changes rides the People round (the
  `CompositeScanIndexHook` seam is the intended trigger point).
- The threshold is a tuning control (0.10–0.40, upstream 0.24 default),
  persisted per device; the r23 dogfood evaluation of 0.24 continues on
  real libraries now that full-library runs exist.
