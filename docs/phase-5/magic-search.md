# Phase 5 workstream 7 — Magic Search (r26 round)

**Parent plan:** `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`
(implementation-sequence table, Workstream 7 row: "CLIP-based semantic
('Magic') search — mobileCLIP image + text encoders, BPE tokenizer, vecdb
vector index, offline text queries in the Search tab").

r26 gives the Search tab its content half: **Magic Search**, offline
semantic search over an on-device CLIP index. The flow, end to end: the
user enables the `magicSearchEnabled` consent (Settings, **off by
default**, ADR-0010) → downloads the three SEMANTIC models (~213 MB —
`mobileclip_s2` image + text encoders and the BPE vocabulary — from the
one allowlisted host, ADR-0020) → starts the semantic background index
(a NEW WorkManager queue, sibling to the r24 face queue) → types a
natural-language query in the Search tab's new **Content** mode → the
CLIP text encoder embeds the query on-device → the vecdb ranks the
library's whole-photo image embeddings → ranked photos. No query text,
embedding, or thumbnail ever leaves the device.

## The JNI surface: sixteen externals become twenty-five

Agent A's engine-side half of the round extends `toad-ml-jni` with nine
externals, all riding the same discipline as the r22–r25 surface (the
store functions require the SAME open `ml-index.sqlite` store the faces
use, `nativeInitSemantic` opens the CLIP half of an already-initialized
engine session as a third open/closed state, and failures surface as
`IllegalStateException` carrying the Rust message):

| External | Purpose |
| :--- | :--- |
| `nativeInitSemantic(modelsDir)` | Loads the CLIP image/text encoders + BPE vocab; a third session state beside `nativeInit`/`nativeStoreOpen` |
| `nativeClipImageEmbeddingJpeg(jpeg)` | The whole-photo 512-float L2-normalized MobileCLIP-S2 image embedding for one JPEG |
| `nativeClipTextEmbedding(text)` | The 512-float query embedding for arbitrary text (the BPE tokenizer + text encoder) |
| `nativeStorePutClip(fileId, embedding)` | Upserts one CLIP row (512-dim guard mirrored on both sides of the bridge) |
| `nativeStoreDeleteClipsForFiles(fileIds)` | The per-file invalidation delete (chunked + transactional, the `nativeStoreDeleteFacesForFiles` twin) |
| `nativeStoreClipIndexedFileIds()` | The resumability read: which files carry CLIP rows |
| `nativeStoreClipCount()` | The index's size (the NotIndexed gate's input) |
| `nativeStoreSearchClips(query, limit)` | Top-K vecdb search; a JSON array of `{fileId, distance}` in the vecdb's own order |
| `nativeStoreFillClipIndex()` | The reconciliation pass: (re)builds the vecdb-side index from the sqlite-side rows; a JSON report (`Completed`/`AlreadyFilled`/`Superseded`, rows/indexed/skipped/resumed) |

Clip rows live in the **same `ml-index.sqlite` store as the face rows** —
one store, one reset, one backup exclusion. The Kotlin facade
(`MlEngine.kt`) wraps them with the r22 discipline: a `SemanticState`
state machine (`LibraryMissing`/`Uninitialized`/`Ready`/`Error`), the
512-float dimension guards on `putClip`/`searchClips`, and honest
exception shapes that callers never paraphrase away.

## The semantic background queue (`ml/index`, the r24 sibling)

`ClipIndexQueue.kt` + `ClipIndexWorker.kt` mirror the r24 face queue
file-for-file, changing exactly one thing per photo and one pass per
drain:

- **Per photo:** ONE whole-photo `clipImageEmbedding` call replaces
  detect + per-face `putFace` rows. No clustering exists on this side —
  CLIP vectors are searched, not grouped — so the once-per-drain
  reconciliation pass is `fillClipIndex` (the vecdb repair pass) instead
  of `clusterFaces`.
- **The same three truths** (catalogue candidates with their
  `MediaRevision`, the queue's own `clip-index-state.json` bookkeeping,
  the store's `clipIndexedFileIds` truth) and the **same invalidation
  mapping** the r24 planner pins — see `background-queue.md`'s table;
  UNCHANGED and METADATA_CHANGED skip, CONTENT_CHANGED re-indexes
  delete-first, orphans and stale bookkeeping are cleaned. HONEST CAVEAT:
  `putClip` is an UPSERT, so the delete-first pass is redundant today —
  it is kept anyway so the two queues share ONE diff→action table
  (drifting between siblings would be a correctness trap for whichever
  round unifies them; the mapping's honesty survives the engine's
  upsert semantics changing).
- **The worker's session gates, in the honest order:** engine library →
  FACE models provisioned → `initialize` → SEMANTIC models provisioned →
  `initSemantic` → `openFaceIndex`. The FACE gate is FIRST because the
  r26 engine session is face-based (see the honest limitation below).
  Each permanent shape fails once with the engine's own message — no
  retry can download a library or a model pack.
- **Its own unique WorkManager chain** (`clip-index-queue`), its own
  clip-flavored progress/output keys (the two queues' Data can never be
  interchanged), self-re-enqueue while the plan outlives its 50-photo
  batch, retry/backoff bounded at 3, and `ClipIndexScheduler`/
  `ClipIndexWorkState` observation for the Settings card.
- **Candidates** come from the r24 face worker's own `eligibleCandidates`
  filter, reused verbatim (JPEGs on the indexable external-primary
  volume, newest first) — the three-gate `EligiblePublicMedia`
  discipline: vault media are never catalogue rows while sealed, so they
  can never become candidates.

## The Kotlin domain (`ml/search`, five files)

- **`MagicSearchEngineSeam`** — the domain's only engine contract
  (availability, `semanticState`/`initSemantic`, the `openFaceIndex`
  store lifecycle the repository needs, `clipTextEmbedding` /
  `searchClips` / `clipCount`). Tests bind fakes; the domain and UI never
  import `MlEngine`. `MagicSearchEngineAdapter` is the production
  binding: the `PeopleEngineAdapter` pattern extended with
  `initSemantic` (lazy `initialize → initSemantic → openFaceIndex`, once
  per instance, `close()` on ViewModel clear).
- **`MagicSearchContentPolicy`** — the pure six-state mapping:
  `Error(message)` (dominates everything), `ConsentOff` (the
  `magicSearchEnabled` preference, default FALSE — ADR-0010's opt-in
  rule), `EngineMissing` (the build shape — no `libtoad_ml_jni.so`),
  `ModelsNotProvisioned` (either pack: FACE, because the r26 session is
  face-based, or SEMANTIC — capability before emptiness), `NotIndexed`
  (all gates pass, zero clip rows — includes "the queue is still
  draining") and `Ready`. All six states and the dominance order are
  pinned by exhaustive tests.
- **`MagicSearchQuery`** — the pure text gate: trimmed, ≥ 2 characters
  (the Search tab's own rule, shared with name search) and ≤ 200. The
  cap is round-pinned and honestly arbitrary: the BPE tokenizer accepts
  far longer text, but a hand-typed content query is a handful of words,
  and over-long queries are REFUSED, not silently truncated (truncating
  would change what the user asked for).
- **`MagicSearchRanking`** — pure: `List<ClipMatch>` → ranked file ids,
  vecdb order preserved VERBATIM (mapping, not sorter), dedupe by file id
  (first occurrence wins), limit honored AFTER the dedupe. v1 surfaces no
  distance — see the follow-ups.
- **`MagicSearchRepository`** — the no-cache orchestrator: trim → text
  embedding → store search → ranking → the chunked 999-limit `IN`
  MediaStore query (the r25 `MediaStoreSelection`, reused) → rows
  re-ordered by relevance (the ranked ids are the display order; the
  media query's newest-first order is just the transport). Typed
  outcomes: `Matches(items)`, `NoMatches` (zero matches AND
  matches-whose-photos-no-longer-resolve), `EngineFailure(message)` with
  the engine's text verbatim.

## The UI (`ui/search`)

- A **"Search by" mode row** — two chips: `Name` (the issue #62 filename
  pipeline, its behavior untouched; the type-filter chips stay
  Name-only, since the semantic index covers JPEG photos and the filters
  would lie in Content mode) and `Content` (Magic Search). The mode is a
  `SearchViewModel` concern so exactly one pipeline ever runs; the
  inactive one settles to Idle and does no work.
- **`MagicSearchViewModel`** — the sibling pipeline: the same 300ms
  debounce discipline, the same screen-driven permission gate (r19a),
  and the policy-derived gating states rendered as honest cards:
  consent off → "enable Magic search in Settings" (with an
  Open-Settings affordance, wired the People/Map consent way); engine
  missing → the build-shape card; models not provisioned → the download
  pointer naming the ~213 MB total via the manifest-derived, house-
  formatted size string; not indexed → the semantic-index pointer. The
  policy's inputs are gathered in DOMINANCE ORDER (consent → engine →
  model packs → clip count), so a consented-off device never opens an
  engine session just to learn it cannot query.
- **Results** ride the SAME shared `MediaGrid` the name results render
  (relevance order — the vecdb's own ranking), taps handing the existing
  viewer route the whole ranked list + index, exactly like name search.

## Settings

- The **`magicSearchEnabled` consent row** beside the People row
  (default FALSE, write-through, ADR-0010 — the `peopleAlbumEnabled`
  shape one round later).
- The **SEMANTIC model download card** beside the FACE one: per-asset
  presence rows for `CLIP_IMAGE`/`CLIP_TEXT`/`CLIP_VOCAB` (the
  provisioner's own disk-backed derivation), the capability state line,
  Download (~213 MB, manifest-derived) + Delete actions, honest outcome
  notes — all over `ModelProvisioner.capabilityState(SEMANTIC)` /
  `provision(SEMANTIC, onProgress)` / `invalidateCapability(SEMANTIC)`.
- The **semantic-index queue card** (the r24 face queue card, sibling
  edition): start / cancel / reset, live per-batch progress, terminal
  outcome lines including the once-per-drain reconciliation report. The
  reset's scope is stated honestly: the queue's OWN bookkeeping
  (`clip-index-state.json`) only — the stored index itself rides the r24
  "Reset face index", whose recursive directory delete erases the whole
  `ml-index.sqlite` both queues share (the r26 `MlEngine` contract
  exposes no delete-all-clips).

## Privacy posture

- **The only network the feature ever touches is the ADR-0020 model
  download** — the same allowlisted `models.ente.com` host, the same
  size + SHA-256 verified, atomically-installed path the FACE pack uses
  (provisioning SEMANTIC reuses `ModelProvisioner` verbatim; zero new
  transport code). Queries, embeddings and thumbnails NEVER touch the
  network: the query text is embedded on-device, the search runs over
  the local vecdb, and the results are local content URIs.
- **Vault media can never be indexed or searched**: the three-gate
  `EligiblePublicMedia` discipline — vault media are not MediaStore rows
  while sealed (gate 1), so they never enter the Phase 4 catalogue
  (gate 2), so they can never become queue candidates (gate 3). The
  queue's orphan pass deletes rows for media that left the listing
  (including vault-moved photos) in its own time.
- `clip-index-state.json` lives inside the r24-excluded `face-index`
  directory (cloud backup AND device transfer) — **no backup-rule change
  was needed this round**; the `PrivacyPolicyTest` non-host-literal set
  records the new dotted file name (`clip-index-state.json`), the test's
  own prescribed mechanism for a new non-host literal.
- No new permissions, no new Gradle dependencies (WorkManager 2.10.1
  landed in r24; the semantic queue reuses it), no new Rust dependencies
  (the CLIP modules are the vendored `ente-ml`'s own).

## Verification

- Rust (Agent A's engine half): the JNI crate is now **32 tests**
  (+12 r26: the semantic init/session rules, the embedding round trips,
  the store guards — 512-dim checks on both sides of the bridge, the
  search order, the fill report parsing); `cargo fmt` clean.
- Kotlin: the JVM suite is now **982 tests** (+55): the `MlEngineTest`
  r26 section (16 — Agent A's facade tests), the semantic queue core
  (15: the planner mapping, bounded batches, whole-photo cancellation +
  `NonCancellable` persistence, once-per-drain fill, the refused-putClip
  fault shape, the state file's round-trip/atomicity/corruption/version
  posture), and the Magic Search domain (24: the six-state policy and
  its dominance order, the query-gate edges, the ranking rules, and the
  repository composition over a fake seam — including the verbatim
  engine-failure shapes and the Uri-free relevance-order rule).
- CI: the `engine` cargo job and the JVM/lint/assemble gates run from
  the branch PR; no device builds until Phase 5 completes (the standing
  maintainer directive).

## Honest limitations (r26's own)

- **The engine session is FACE-based**: `initialize` loads the face
  models even for CLIP-only work, so Magic Search requires BOTH packs
  (~36 MB FACE + ~213 MB SEMANTIC). The worker's gate order and the
  policy's `ModelsNotProvisioned` input both encode this honestly; a
  semantic-only session is the named follow-up below.
- **The fill report's outcomes** (`Completed`/`AlreadyFilled`/
  `Superseded`) are the engine's own vocabulary, surfaced verbatim in
  the Settings card; `resumed` marks a pass that continued a previous
  partial fill. The card does not interpret them beyond the copy.
- **No distance display in v1**: a raw CLIP distance is not a
  percentage anyone can interpret (`similarity = 1 − distance` holds
  only for normalized vectors), so the ranking is order-only and the
  distances stay inside the engine.

## Named follow-ups

> **r39 closure note (the ente-port exit round,
> `docs/phase-5/ente-exit.md`)**: the first two follow-ups below are
> CLOSED. Semantic-only engine sessions shipped as the symmetric
> session halves (the FACE-model requirement this doc's "Honest
> limitations" section records is dropped); Things dynamic collections
> shipped over the same CLIP index with the distance-cut
> `nativeStoreSearchClipsWithin` --- which is also the mechanism the
> threshold-tuning follow-up names. The score-display follow-up stays
> open; the scan-hook wiring stays open (the shared r24 deferred item).

- **Semantic-only engine sessions** — decouple `initSemantic` from the
  face-model session so Magic Search needs only the SEMANTIC pack.
- **Things / dynamic collections** — named semantic clusters ("dogs",
  "beach", "food") over the SAME CLIP index (image-side queries), the
  natural next consumer of this round's store surface.
- **Score display** — an honest similarity figure (cosine-normalized)
  on result cells, if user testing asks for it.
- **Threshold tuning** — a distance cut for "good enough to show",
  if the top-K-only shape proves too permissive on real libraries.
- **Scan-hook wiring** — automatic semantic-index enqueue on
  scan-detected changes (the `CompositeScanIndexHook` seam, the same
  deferred r24 follow-up, now shared by both queues).
