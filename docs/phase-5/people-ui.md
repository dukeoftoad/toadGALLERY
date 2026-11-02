# Phase 5 workstream 6 — the People UI & cluster management (r25 round)

The r23 round built the face-index engine surface (`toad-ml-jni`'s store
externals + `IndexCoordinator`); the r24 round turned it into the
full-library, WorkManager-backed background queue. The r25 round gives that
index its product surface: the People auto-album — an opt-in (ADR-0010,
**off by default**) grid of face clusters the user can name, merge, prune
and hide, with the photos of each person one tap away. Everything runs
on-device; face-derived data never leaves the device (ADR-0020's
`models.ente.com` download remains the feature's only network path, and it
serves the model pack, never face data).

## The JNI store surface: twelve externals become sixteen

All four r25 externals ride the SAME open-store state machine
(`nativeStoreOpen` … `nativeStoreClose`) the r23/r24 functions use, with
the same process-global store mutex and the same
`IllegalStateException`-with-the-Rust-message failure shape. Every query
is an upstream `MlDb` call verbatim — **zero new SQL authored**:

| External | Upstream call(s) | Purpose |
| :--- | :--- | :--- |
| `nativeStoreFaceClusters()` | `cluster_id_to_face_count` + `get_face_ids_for_cluster_ordered_by_score(id, 1)` | The People list: every live cluster with its face count and its cover file id |
| `nativeStoreClusterFileIds(id)` | `get_file_ids_of_cluster_id` | The photo file IDs (`MediaStore _id`, JPEGs by construction of the r23 pipeline) of one person |
| `nativeStoreMergeFaceClusters(source, target)` | `get_face_ids_for_cluster` + `force_update_cluster_ids` | Merge: every face of `source` moves to `target` in one atomic batch upsert |
| `nativeStoreRemoveFacesFromCluster(id, fileIds)` | `get_faces_for_given_file_id` + `get_cluster_id_for_face_id` + `remove_face_id_to_cluster_id` | The split primitive: the given files' faces leave the person's cluster (one atomic batch delete) |

### The live-table decision (and the honest staleness rule)

`nativeStoreFaceClusters` counts the **live `face_clusters` assignments
table** — deliberately NOT upstream's `cluster_summary` cache table. The
summary rows are written by the clustering pass
(`update_face_clusters_and_summaries`); reading them for the People UI
would show stale counts after a manual merge or exclusion until the next
recluster. Reading the live table makes manual edits reflect immediately
— at the cost of the cover lookup doing its own top-by-score read per
cluster (`get_face_ids_for_cluster_ordered_by_score(id, 1)`, the face-id
`"{file_id}_{ordinal}"` prefix parsed for the file id).

The mirror image of that rule: **the next clustering pass recomputes the
assignments**, so manual merges/exclusions persist only until the queue's
next recluster run (upstream recomputes every face's cluster from the
embedding). This is pinned by the
`the_next_cluster_pass_recomputes_manual_edits` Rust test and documented
in the facade KDoc — the r25 surface states it rather than silently
losing edits. An edit-preserving overlay (cluster-id remapping across
reclusters) is deliberately deferred (see below).

The wire JSON is hand-built (`format!`, no serde dependency, matching the
r23 cluster-summary contract):

```json
[{"cluster_id":"0","count":12,"cover_file_id":4813},
 {"cluster_id":"1","count":3,"cover_file_id":null}]
```

ordered by count descending, ties by cluster id ascending;
`cover_file_id` is JSON `null` when a cluster has no parsable cover.
Cluster ids are JSON-escaped defensively (pinned by the
`json_escape_quotes_backslashes_and_controls` test) — they are numeric
strings today, but the adapter does not assume that.

`nativeStoreMergeFaceClusters` rejects `source == target` as
`IllegalArgumentException` before any store mutation — the same
caller-input failure class as `nativeStorePutFace`'s embedding-dimension
check, mirrored on BOTH sides of the bridge (the Kotlin facade checks
first, the Rust export re-checks).

## The Kotlin domain (`ml/people`)

- **`PeopleEngineSeam`** — the domain's only engine contract (the four
  r25 operations plus `engineAvailable`). Tests bind fakes; the domain
  and UI never import `MlEngine`. `PeopleEngineAdapter` is the production
  binding: thin delegation with a lazy `initialize → openFaceIndex`
  session over the face-index directory and `close()` on ViewModel clear.
- **`PeopleStateStore`** — the user's People bookkeeping in ONE atomic
  JSON file, `people-state.json`, inside the same app-private face-index
  directory as `ml-index.sqlite` and the r24 `index-state.json`
  (backup/transfer-excluded with them): `{"names":{...},"hidden":[...]}`
  written tmp-file → NIO move, mirroring `FaceIndexStateFile`'s
  discipline. A corrupt file recovers as empty (flagged honestly, never
  swallowed). Blank names are refused at the store boundary — absence IS
  the unnamed state. Stale cluster ids (from a recluster) are pruned on
  load and saved back.
- **`PeopleContentPolicy`** — the pure six-state mapping
  (`MapContentPolicy`'s discipline): `Loading`, `ConsentOff`,
  `EngineMissing`, `NotIndexed` (consent on, engine present, ZERO RAW
  clusters — includes "the queue is still draining"), `Ready(visible)`
  and `Error(message)`. Rule order pinned by tests: **Error dominates;
  consent before capability (ConsentOff renders even if the store holds
  clusters); EngineMissing is the build-shape fact, never a disguised
  "no people yet"; all-hidden is `Ready(empty)`, distinct from
  `NotIndexed`.** The policy maps but never re-sorts — the seam's
  face-count-descending order is the contract.
- **`PeopleRepository`** — no cache (live-table semantics): every
  snapshot re-reads clusters, prunes stale ids, applies names/hidden;
  `rename` / `hide` / `merge` / `removePhotos` / `photosOf`.
- **`PeopleMediaQuery`** — the person's photos as `PersonPhoto`s via a
  chunked `IN (...)` MediaStore selection (the pure
  `MediaStoreSelection` chunks at the 999-parameter SQLite limit —
  JVM-tested), newest-first, with a dimension-column-guarded retry.

## The UI (`ui/people`)

- **`PeopleScreen`** — the full grid of person cards (circular cover
  thumbnail from the cover file's `MediaStore` content URI, name or
  "Unnamed", face count), rendering all six policy states with honest
  copy (the consent state points at the Settings toggle; the
  engine-missing state names the build shape; the empty-ready state says
  everyone is hidden).
- **`PersonDetailScreen`** — the person's photo grid (staggered, like the
  timeline) with multi-select: **Remove from person** (the split
  primitive — the selected photos' faces leave the cluster), and the
  overflow menu: **Rename**, **Merge with…** (listing the OTHER visible
  persons by name/count), **Hide this person** (the r25 way back is the
  r24 Reset face index — stated in the dialog, no unhide UI this round).
  Photo taps hand off to the existing full-screen viewer exactly the way
  Album detail does (shell-scoped `(window, index)`); the synthesized
  `MediaItem` is honest by construction (`image/jpeg`, not a video: the
  r23 pipeline indexes JPEGs only) — the rationale is pinned in the
  screen's KDoc.
- **The Albums-tab People section** — the auto-album entry point: consent
  off → one honest card; engine missing / not indexed → honest cards;
  ready → a horizontal person row + "See all" into `PeopleScreen`.
- **The Settings consent row** — `peopleAlbumEnabled` in the existing
  preferences, **default FALSE** (ADR-0010), in the AI section beside
  the r22/r24 engine rows, with on-device-only copy.

## Privacy posture

- The People feature is opt-in and OFF by default (ADR-0010); the
  consent gate renders before any cluster data is read for display.
- `people-state.json` lives in the backup/transfer-excluded face-index
  directory (the r24 exclusion covers it); names and hidden sets are
  user-authored metadata, but they sit with the face-derived data they
  annotate, and the `PrivacyPolicyTest` non-host-literal set records the
  new dotted file name (`people-state.json`) — the test's own prescribed
  mechanism for a new non-host literal.
- No new permissions, no new dependencies (Kotlin or Rust), no new
  network paths: the four externals are store-local reads/writes.

## Verification

- Rust: the crate's suite is now **20 tests** (8 new r25 store tests:
  the put→cluster→list round trip with covers, the empty-store array,
  the face-id prefix parse, JSON escaping, merge semantics
  (counts move, source empties, self-merge rejected), the
  remove-only-that-cluster drop, the not-open failure shape, and the
  recluster-recomputes-manual-edits rule); `cargo fmt --check` clean.
- Kotlin: the JVM suite is now **927 tests** (+42): the `MlEngineTest`
  r25 section (wire parsing incl. null covers and malformed JSON →
  `IllegalStateException`, the state-machine guards, self-merge
  `IllegalArgumentException` before any native call, delegation) and
  the People packages (policy 8, repository 8, state store 7, selection
  chunking 4, multi-select machine 5).
- CI: the `engine` cargo job and the JVM/lint/assemble gates run from
  the branch PR (per the maintainer directive, no device builds until
  Phase 5 completes).

## What r25 deliberately defers

- **Edit-preserving reclustering** — cluster ids are not stable across
  the queue's recluster passes; names/hidden prune on load and manual
  merges are advisory against the next pass. An overlay that remaps
  user edits onto new cluster ids (upstream solves this with
  server-synced cluster persons) is future work.
- **Unhide** — hidden persons return via the r24 "Reset face index"
  path; a hidden-list UI is future work.
- **On-device dogfood validation** — the r23 engine APK (build 27)
  remains the delivered device artifact; People surfaces ride CI until
  the Phase 5 completion directive lifts.
- **Places / Things / Magic Search** — the next workstreams (7+); the
  People surfaces are the template their rounds follow.
