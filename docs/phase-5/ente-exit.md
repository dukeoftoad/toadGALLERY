# Phase 5 workstreams 9+10 — the ente-port exit round (r39)

**Parent plan:** `docs/phase-5/magic-search.md` §Named follow-ups (the two
open code-side items), `tasks.md` §Remaining (the Magic Search follow-ups
block), and the 2026-11-04 maintainer directive: **complete Phase 5 — the
ente port** (the on-device dogfood device rows stay human-only, untouched
by this round).

r39 closes the ente port's two named code-side follow-ups. After this
round, every item the repo's own documents name as Phase 5 code work is
merged; what remains open are the §13–§18 device-checklist rows and the
Phase 5 dogfood rows, all human-only by the standing directive.

## Part A — semantic-only engine sessions

**The r26 limitation, verbatim** (`docs/phase-5/magic-search.md`): "The
engine session is FACE-based: `initialize` loads the face models even for
CLIP-only work, so Magic Search requires BOTH packs (~36 MB FACE + ~213 MB
SEMANTIC). … a semantic-only session is the named follow-up below."

**The r39 answer — the symmetric session halves.** The vendored runtime
already supports it: `ente_ml::runtime` configures ONLY the models whose
`ModelPaths` slots are non-empty (`configure_requested_models` skips blank
paths; `validate_request_model_paths` requires exactly the paths the
request flags select), so a session whose `ModelPaths` carries the three
CLIP files and BLANK face paths is a first-class shape upstream. The
limitation lived in the r26 BRIDGE, which required a face-initialized
engine before `nativeInitSemantic` would run.

The r39 bridge makes the two halves symmetric:

- **`nativeInitSemantic` with no engine** installs a SEMANTIC-ONLY session
  (CLIP paths filled, face paths blank) after the same validation +
  warm-up discipline (`run_clip: true`, `run_faces: false` — the existing
  semantic warm-up, unchanged). A warm-up failure releases the runtime and
  leaves NO engine installed (there was no previous state to restore).
- **`nativeInit` on a semantic-only session** upgrades it in place with
  the face half (the mirror image of the r26 `init_semantic` upgrade:
  validate the two face files, re-register the COMBINED paths, face
  warm-up; on failure, release + re-register the previous semantic-only
  paths — the state is untouched, face calls keep failing with the honest
  not-installed message).
- **Face calls on a semantic-only session** fail with a dedicated
  `FACE_MODELS_NOT_INSTALLED` message ("face models are not installed in
  this engine session; call nativeInit(modelsDir) first") — checked at the
  boundary, never surfacing as upstream's generic missing-path error.
- **The engine info line** names the session's shape honestly: three
  labels — `yolov5s-face,mobilefacenet` (face-only), `mobileclip-s2`
  (semantic-only), and the combined one (both halves).
- `nativeRelease` semantics are unchanged: it tears the WHOLE engine,
  whichever halves are installed.

**Kotlin half.** The facade's `initSemantic` KDoc drops its "requires
initialize first" claim (the native layer owns the session shape; the
facade's `SemanticState` machine is unchanged). The production
`MagicSearchEngineAdapter` (split into its own file, the
`PeopleEngineSeam`/`PeopleEngineAdapter` convention) opens its session as
`initSemantic → openFaceIndex` — NO `initialize` call — so the Search tab's
Content mode runs on the SEMANTIC pack alone. The semantic background
queue (`ClipIndexWorker`) drops its FACE capability gate and its
`initialize` step the same way; its honest gate order becomes engine
library → SEMANTIC provisioned → `initSemantic` → `openFaceIndex`.

**The policy rename.** `MagicSearchContentPolicy` loses its
`faceModelsReady` input (the honest decoupling) and is renamed
`SemanticContentPolicy` (`SemanticContentState`) — ONE truth for the six
semantic states, consumed by BOTH Magic Search and the Things surfaces
(Part B). The dominance order is unchanged: Error → ConsentOff →
EngineMissing → ModelsNotProvisioned (SEMANTIC only, now) → NotIndexed →
Ready.

## Part B — Things dynamic collections

**The follow-up, verbatim:** "Things / dynamic collections — named
semantic clusters ('dogs', 'beach', 'food') over the SAME CLIP index
(image-side queries), the natural next consumer of this round's store
surface."

**The model.** A Things collection is a NAME plus a QUERY — "Dogs" over
"dogs", or "Seaside" over "a beach at sunset with waves". Nothing else
persists: membership is DERIVED on every read from the live CLIP index
(the DerivedCollections doctrine — a collection is a named view, never a
stored set; the r26 queue's rows are the only index and they already
refresh themselves). The store is `things-state.json` in the app-private
face-index directory (the `people-state.json` discipline: atomic
tmp-then-move, forgiving parse with the honest corrupt-file flag, schema
version 1, erased together with the index by the Settings reset).

**The membership rule.** The collection's query is embedded by the CLIP
text encoder and run against the SAME CLIP index Magic Search searches —
an image-side query — through a NEW engine call that cuts by distance
inside the vecdb: `nativeStoreSearchClipsWithin(query, maxDistance,
limit)`, upstream's own `SearchParams { limit, max_distance }` plumbed
through the bridge (the vecdb has supported the cut since it was vendored;
the r26 bridge simply never passed it). The membership threshold is
**round-pinned at cosine distance ≤ 0.70** (similarity ≥ 0.30 for the
L2-normalized MobileCLIP-S2 vectors) — honestly arbitrary, the same
convention as Magic Search's 200-character query cap, and recorded as a
tuning follow-up rather than defended as calibrated.

**The engine call also serves the r26 threshold-tuning follow-up's
mechanism** (a distance cut for "good enough to show") without changing
Magic Search's own order-only v1 behavior.

**The surface (v1, deliberately small).**
- The Albums tab gains a **Things section** — the People/Places sibling
  (third full-span item in the MI-style auto-album stack): the SAME
  `SemanticContentPolicy` honest cards (consent off / engine missing /
  models not provisioned / not indexed), the collection chips, a
  Create action (name + optional query dialog), and a per-chip Remove.
- A **collection detail sub-screen** (`things/{collectionId}`, the
  person-detail pattern): the member grid in relevance order (the
  vecdb's own), taps handing the shared viewer the ranked list,
  plus Rename, Edit query, and Delete.
- The **`thingsAlbumEnabled` consent row** in Settings (the
  `peopleAlbumEnabled` shape, default FALSE, ADR-0010). Things rides the
  SAME SEMANTIC model pack and the SAME semantic index as Magic Search —
  no new download card, no new queue; the copy says so.
- **Smart-filter integration** is delivered as the DerivedCollections
  doctrine applied to a semantic dimension: the collections live in the
  Albums smart stack beside People and Places, and each is a LIVE derived
  view over the one CLIP index. The `TimelineFilter` engine is
  deliberately untouched: its three projections (in-memory, MediaStore
  SQL, Room SQL) serve only dimensions with persisted columns, and a
  CLIP membership has none — routing it through that engine would be the
  lying-chip shape the honest-subset rule exists to prevent.

## Verification

- **Rust:** the JNI crate's tests grow to cover the three info-line
  variants, the semantic-only/face-upgrade state shapes, the
  face-call guard, and `search_clips_within`'s distance cut + boundary
  rejections (the existing real-MlStore tempdir pattern). The vendored
  crates stay byte-identical (all changes live in `toad-ml-jni`).
- **Kotlin (JVM):** `MlEngineTest` (the semantic-only session state
  machine + the within-search parse), `SemanticContentPolicyTest` (the
  rename + the FACE-input removal), the Things domain suite
  (state/store round-trip + corruption + the pure mutation helpers,
  membership dedupe/order, the repository composition over a fake seam).
  The android-bound surfaces (the adapter, the worker, the Albums
  section, the detail screen) ride CI per ADR-0016 + the pre-push
  lex/balance checks.
- **Privacy:** `things-state.json` joins the `PrivacyPolicyTest`
  non-host-literal set (the test's own prescribed mechanism); it lives
  inside the already-backup-excluded face-index directory, so NO
  backup-rule change rides this round (the `clip-index-state.json`
  precedent). Zero new permissions, zero new Gradle dependencies, zero
  new network paths (ADR-0020's sole host serves the models; queries and
  memberships never leave the device).

## Honest limitations (r39's own)

- The 0.70 membership cut is round-pinned, not device-calibrated; the
  tuning follow-up (F-21) owns per-library adjustment if dogfooding
  asks for it.
- Collection membership recomputes on every read (no caching): a large
  index makes opening a collection an engine round trip — acceptable at
  v1 because the CLIP search is a single vecdb query, but a
  materialized-membership cache is the named escape hatch.
- The Albums section renders every collection chip (no "See all"
  browse screen); when collections grow past a row's worth, the browse
  screen is a UI-round follow-up (F-22).
- The create dialog and the collection chips are not yet
  TalkBack-audited to the r32 editor standard (the §13 device rows'
  family); the string copy is plain and labelled, and the audit rides
  the device checklist window.
