# Phase 4 compatibility audit & Ente-derived AI phase plan

**Status:** planning input (audit of the proposed Phase 4 metadata layer
against the Ente Photos on-device ML implementation; no repository code
changed by the audit itself).\
**Working scope naming:** per the owner's explicit working designation,
"Phase 5" below means the **Ente-derived on-device gallery AI** work
(People, semantic search, local indexing) --- NOT the repository
`roadmap.md`'s current Phase 5 (MI-style organisational UX). Reconciling
the roadmap numbering (and the ADR-0010 application phases) is a required
**decision before any Phase 5 implementation starts**; this document does
not silently rewrite the roadmap.\
**Audit result:** Phase 4 is directionally suitable but needs contract
changes before Ente AI integration.

---

## Executive decision

The proposed Phase 4 is directionally suitable but NOT sufficient as
currently specified to serve as the foundation for a faithful Ente-derived
AI implementation. The metadata feature list is useful; the missing pieces
are a durable media identity/revision contract, a byte-oriented media read
boundary, incremental change detection, a canonical decode contract, and
an explicit persistence boundary.

The efficient direction is not to invent an AI system. Reuse Ente's actual
Rust ML engine, model catalog, ONNX inference pipeline, face processing,
clustering, ML database and vector-search design. Replace only the
Flutter host / Flutter-Rust-Bridge integration with a Kotlin-native bridge
and remove Ente account, server-sync and cloud-specific behavior.

Phase 4 should be strengthened first, but this document also defines the
Ente-derived AI phase as a standalone implementation plan so Phase 5 work
is not blocked by ambiguity.

## Scope and naming discrepancy

- `roadmap.md` (main) labels Phase 4 as the Aves-inspired metadata layer,
  Phase 5 as MI-style organisational UX, Phase 8 as editor AI features.
- The earlier conversation and prior architecture draft use "Phase 5" to
  mean the Ente-derived gallery AI work (People, semantic search, local
  indexing). These are not the same scope.
- The AI scope here is **gallery discovery AI**, not the separate editor
  AI features currently described under roadmap Phase 8.

## Repository audit baseline

Repository inspected: `dukeoftoad/toadGALLERY`, default branch `main`.
The tree includes the Kotlin/Compose app, `MediaStoreSource`, `MediaItem`,
`VaultRepository`/`VaultStore`, `roadmap.md`, ADRs and the Aves source
audit.

- `MediaItem.kt` is a small data class: MediaStore id, content Uri, MIME
  type, `dateAddedSec`, `isVideo`, width, height, durationMs, isFavorite.
  `MediaStoreSource` reads MediaStore image/video collections and uses
  URI-first access. It does not yet provide the full Phase 4 metadata
  catalogue, Room-backed media catalogue, generation-based incremental
  sync, or an AI-oriented media read contract.
- The Gradle version catalogue has AndroidX ExifInterface, Paging,
  Compose, Coil and Media3; **no Room or ONNX Runtime dependency** is
  declared.
- The existing Vault is an app-private encrypted blob store with a sealed
  index and AndroidKeyStore-backed crypto. Vault media is separate from
  the public MediaStore gallery path.
- The roadmap Phase 4 proposal specifies EXIF read/write through
  `androidx.exifinterface`, custom IPTC/XMP wrappers, details view,
  editable date/GPS/copyright/keywords/rating/colour label/comment,
  offline map and format support audit. ADR-0006 states URI-first identity
  and a three-date model and says Room is the persistence layer. The
  current `MediaItem` implementation has not yet caught up to that
  contract.

## Ente implementation findings

- Ente's public ML architecture describes a central client-side ML service
  coordinating indexing, clustering and user interactions. The image
  pipeline fetches/decrypts or reads an image, decodes and preprocesses
  it, runs ONNX models locally, persists face and image embeddings, and
  compares text embeddings with image embeddings for semantic search.
  Ente also syncs encrypted indexes and person entities across devices;
  that synchronization is **not** part of toadGALLERY's local-only scope.
- The current Ente mobile application is Flutter/Dart with a substantial
  Rust core. The Dart `MLComputer` and ML indexing-isolate code
  orchestrate work; Flutter-Rust-Bridge exposes Rust APIs. The Rust core
  is reusable independently of the Flutter UI, but the generated Dart
  bridge and app-specific service locator, accounts, remote index sync,
  file-download orchestration and app database integration are not
  drop-in Kotlin components.
- Ente's Rust indexing API exposes `AnalyzeImageRequest` with `file_id`,
  `image_path`, `run_faces`, `run_clip`, `run_pets` and `assets_dir`. The
  underlying Rust engine supports `ImageSource::Bytes` as well as
  `ImageSource::Path`. For toadGALLERY, adapt the bridge to accept bytes
  or a safe descriptor-backed reader rather than passing a filesystem path
  through the Kotlin domain layer.
- Ente's Rust ML code includes YOLO face detection with non-maximum
  suppression, geometric face alignment, MobileFaceNet face embeddings and
  quality metrics, and its linear incremental face clustering algorithm.
  The semantic-search path uses MobileCLIP image and text encoders, a BPE
  vocabulary/tokenizer, cosine similarity and a vector database. The
  current Ente schema stores faces, face clusters, cluster-person
  relations, cluster summaries, user feedback, CLIP embeddings, text
  embedding cache and ML store metadata.
- Ente's Android Rust build config uses ONNX Runtime with
  Android-specific features including dynamic loading, copied dylibs,
  XNNPACK and WebGPU. This is not equivalent to adding a generic ONNX Java
  dependency and calling it done: ABI packaging, ORT native libraries,
  model compatibility, execution-provider behavior and the Kotlin/native
  boundary need a real build spike.

## Aves-informed Phase 4 assessment

- Aves is a strong reference for the data model and metadata behavior, not
  a direct code dependency. Its entry model is URI/content-ID based, uses
  multiple date sources with timezone-aware catalog dates, progressively
  enriches metadata, and caches metadata by content identity with
  invalidation on content change.
- Aves's native Android metadata implementation is materially broader than
  `androidx.exifinterface` alone: a metadata facade, EXIF helpers,
  metadata-extractor integrations, IPTC/XMP handling, MediaMetadataRetriever
  helpers, MP4/QuickTime parsing, SVG and multi-page handling,
  spherical/panorama/GeoTIFF support, and MediaStore generation/change
  tracking. Phase 4 should define a parser capability matrix and adapter
  strategy rather than assume EXIF read/write plus handwritten IPTC/XMP
  covers the Aves reference.
- Aves's MediaStore handler exposes changed URIs and generation by volume
  on Android R+. Useful pattern for incremental catalogue maintenance. For
  API 26-29, Phase 4 needs a ContentObserver plus periodic/triggered
  reconciliation fallback; on newer Android versions use per-volume
  generation and modified-generation APIs where available.
- Aves's FAQ explains that media items can have EXIF original/digitized
  dates, file creation/modified dates, XMP dates and video stream dates,
  and that metadata should be preferred over weak filesystem timestamps.
  Phase 4 should preserve date source and timezone semantics rather than
  flattening everything into one date.

## Phase 4 compatibility matrix

| Area | Proposed/current state | Ente requirement | Verdict / Phase 4 change |
| --- | --- | --- | --- |
| Media identity | URI + MediaStore `_id` in ADR-0006; current `MediaItem` has id + Uri | Stable file key for face rows, CLIP vectors, clustering and incremental indexing | PARTIAL --- add volumeName + canonical URI + durable local media key; never use display name/path |
| Dates | ADR-0006 three-date idea; current model only `dateAddedSec` | Creation/capture time used for chronological clustering; robust Moments and media ordering | GAP --- store added, modified, capture date, source tag, timezone offset and confidence |
| Revision tracking | Not explicit in Phase 4 deliverables | Detect replacement/edit/metadata change and re-run relevant models | GAP --- revision token: per-row MediaStore generation-modified + size + dateModified + metadata revision (the per-VOLUME generation is the scan checkpoint only, never a per-item revision input); content hash only where justified |
| Media bytes | `MediaStoreSource` is URI-first; no dedicated AI read contract | Decode full-resolution pixels with deterministic preprocessing | GAP --- `MediaReadHandle` opens original bytes/descriptor; re-check permission; support bytes bridge |
| Image normalization | Not explicitly specified | Ente detector and encoders expect specific tensor shape, orientation, channel order and normalization | GAP --- define canonical upright RGB decode contract and test pixel parity |
| Metadata breadth | EXIF + new IPTC/XMP wrappers; details view | Dates, GPS, dimensions/orientation, video timestamps and reliable media classification | PARTIAL --- add video/container metadata and parser capability matrix; distinguish absent/unsupported/corrupt |
| Location | GPS read/write and offline map planned; `ACCESS_MEDIA_LOCATION` deferred | Places can derive locally from coordinates; no reverse geocoding required | PARTIAL --- explicitly add `ACCESS_MEDIA_LOCATION` flow and coordinate privacy handling |
| Database | ADR says Room; no Room dependency in current catalog | Media catalogue plus Ente ML DB/vector store with migrations | GAP --- land Room catalogue; keep Ente ML DB/vector store as separate engine-owned stores |
| Incremental discovery | No explicit generation contract in current Phase 4 task list | Skip already indexed revisions; handle deletes, updates and permission changes | GAP --- generation feed + ContentObserver + reconciliation + tombstones |
| Vault boundary | Vault encrypted blobs are app-private and outside MediaStore | No Vault bytes, faces, thumbnails or embeddings enter ML | GOOD BASE --- add compile-time/type-level eligibility and three enforcement gates |
| Runtime/model assets | ADR-0010 says bundled in APK, no runtime downloads; Ente currently downloads model assets | Models available locally and verified before inference | POLICY CONFLICT --- choose bundled/offline model pack or explicitly revise network policy for model-only download |
| Execution runtime | No ONNX/Rust dependency currently | Ente Rust ML + ONNX Runtime native provider stack | GAP --- native build spike, ABI matrix, ORT provider validation and Kotlin bridge |
| Video | MediaStore duration and Media3 viewer; no AI frame contract | Ente image analysis API is image-oriented | OUT OF SCOPE for initial port --- do not silently index videos; define keyframe sampling as later extension |

## Required Phase 4 contract changes

Phase 4 must provide the following stable contract before AI indexing
begins. These are architectural requirements, not a mandate to build every
Details UI feature before the first AI vertical slice.

1. **MediaIdentity**: volumeName, MediaStore `_id`, canonical content Uri,
   optional page/embedded-item identity. URI remains the access handle;
   path is never a primary key.
2. **MediaRevision**: sizeBytes, dateModifiedMillis, the per-row MediaStore
   generation-modified token when supported (never the per-volume generation
   checkpoint), metadataWriteRevision, and optional lightweight
   content fingerprint. A changed revision invalidates only affected AI
   outputs.
3. **MediaDates**: dateAdded, dateModified, captureDate,
   captureDateSource, timezoneOffsetMinutes, subsecond precision, and a
   documented precedence/fallback resolver for EXIF/XMP/QuickTime/
   MediaStore.
4. **MediaMetadataSnapshot**: MIME/format, width/height, orientation/flip,
   file size, EXIF/IPTC/XMP summaries, camera/lens/exposure, GPS and
   metadata availability state (present / absent / unsupported /
   unreadable).
5. **MediaReadHandle**: suspend `openOriginalBytes()` or
   descriptor-backed read with explicit cancellation and permission
   checks. Avoid full-file buffering and avoid plaintext temp files.
6. **CanonicalImageDecoder**: upright orientation, defined RGB channel
   order, colour-space behavior, maximum decode dimensions, memory
   budget, and deterministic handling of malformed images.
7. **MediaEligibility**: explicit `PUBLIC_INDEXABLE`, `VAULT_EXCLUDED`,
   `TRASHED`, `PERMISSION_REVOKED`, `UNSUPPORTED`, `CORRUPT` states. No
   generic URI-only API should let callers accidentally pass a Vault item.
8. **MediaChangeFeed**: MediaStore generation tracking by volume on
   supported Android versions; ContentObserver and reconciliation
   fallback on older versions; delete/revoke tombstones.
9. **Metadata write invalidation**: after EXIF/IPTC/XMP write, refresh
   metadata, increment revision and schedule only the required re-index
   operations.
10. **Room catalogue**: persist the media identity, revision, metadata
    cache state and scan checkpoints. Do not store face/CLIP vectors
    redundantly in Room if Ente's native ML DB/vector store is retained.

Recommended Phase 4 package boundaries: `data/media/identity`,
`data/media/access`, `data/media/revision`, `data/media/changes`,
`metadata/exif`, `metadata/iptc`, `metadata/xmp`, `metadata/video`,
`metadata/cache`, `metadata/format`, and `data/catalogue` (Room). The AI
phase should depend on interfaces, not on `MediaStoreSource` internals.

## Phase 4 decisions required before sign-off

1. Add `ACCESS_MEDIA_LOCATION` to the Phase 4 permission design and
   document when original GPS is available. Permission revocation must
   invalidate location-derived Places data without corrupting other
   indexes.
2. Decide how MediaStore volume identity and removable-storage changes
   affect local media keys.
3. Define the date resolver and timezone semantics before Moments or
   chronological clustering is implemented.
4. Select and pin the metadata parser set for EXIF, IPTC, XMP,
   QuickTime/MP4, and other target formats; maintain a capability matrix
   rather than promising every format.
5. Add Room and migration policy consistent with ADR-0006; no destructive
   fallback for user metadata.
6. Specify decode behavior for HEIC/HEIF, AVIF, RAW, GIF, WebP, TIFF, SVG
   and video frame sources. Phase 5 should index only supported still
   images in its first vertical slice.
7. Define backup/data-extraction behavior for private catalogue and AI
   indexes. Vault data and face embeddings should not be unintentionally
   included in device/cloud backup.
8. Set library-scale performance targets and test partial media
   permissions, removable volumes, revoked access and external edits.

## Ente reuse map --- exact implementation to port/adapt

| Ente component | Upstream source area | Reuse strategy in toadGALLERY |
| --- | --- | --- |
| ML inference core | `rust/crates/ml/src/indexing.rs`; `rust/crates/ml/src/face/*`; `rust/crates/ml/src/clip/*` | Port/adapt Rust crate as the inference engine; preserve preprocessing, thresholds, model input/output contracts and tests |
| ONNX runtime | `rust/crates/ml/src/onnx.rs`; `rust/crates/ml/src/onnx/providers.rs`; `rust/crates/ml/Cargo.toml` | Reuse ORT integration and provider policy; adapt Android build/packaging and benchmark CPU/XNNPACK/WebGPU on supported devices |
| Face detection | `rust/crates/ml/src/face/detect.rs` | Reuse YOLO face detection and NMS exactly; do not replace with ML Kit |
| Face alignment + quality | `rust/crates/ml/src/face/align.rs` | Reuse similarity transform, five-landmark alignment, blur/pose metrics and preprocessing |
| Face embeddings | `rust/crates/ml/src/face/embed.rs` | Reuse MobileFaceNet ONNX model, normalization and embedding behavior |
| Face clustering | `rust/crates/photos/src/face_clustering/linear.rs` and related modules | Reuse Ente's linear incremental clustering, threshold/quality logic and feedback semantics |
| ML database | `rust/crates/photos/src/ml_db/*` | Reuse schema/migrations/query logic as an engine-owned SQLite DB; replace Ente account/sync fields where they are not needed |
| Vector store | `rust/crates/photos/src/ml_store/*` and `ente-vecdb` | Reuse vector indexing, fill-state/recovery and similarity search; keep fully local |
| Model catalog/assets | `rust/crates/ml/src/assets.rs` and `models.rs`; `rust/crates/assets` | Reuse exact model catalog, checksums, staged installation and versioning; replace network delivery policy |
| Kotlin host integration | `mobile/apps/photos/lib/services/machine_learning/*`; `rust/bindings/frb/photos/src/api/*` | Do not port Dart/Flutter UI. Reimplement orchestration in Kotlin WorkManager/coroutines and expose Rust with JNI or UniFFI |
| Semantic search | `mobile/apps/photos/lib/services/machine_learning/semantic_search/*`; `rust/crates/ml/src/clip/*` | Reuse MobileCLIP text/image embeddings, tokenizer and cosine/vector search; map Ente file IDs to toad media keys |
| Cloud sync/account layer | Ente services, account, remote index/person sync | Exclude completely: no account, server API, encrypted index sync or remote person entity dependency |

## Model inventory and delivery implications

The Ente main-branch Rust model catalog (`assets.rs`) currently identifies
the following primary assets. These names, sizes and SHA-256 values must
be pinned to the exact chosen Ente commit before implementation; do not
download "latest" model names dynamically or silently swap weights.

| Capability | Asset file | Size (bytes) | SHA-256 |
| --- | --- | --- | --- |
| Face detection | `yolov5s_face_640_640_static_b1.onnx` | 32,355,091 | `e047647409403d52696035ecd445792173e50d7fbdcccac97b958a585db9aa3d` |
| Face embedding | `mobilefacenet_portable_static_b1.onnx` | 5,278,803 | `0763fc33f54e138476194da95987e133b3e976075a6b1d3e1b2caedb251b1a36` |
| CLIP image | `mobileclip_s2_image_gelu_opset20.onnx` | 143,057,352 | `205a430af825e501c5138e5bb9abea942482a7a4fd4a680e98e47cf0830dce7e` |
| CLIP text | `mobileclip_s2_text_opset18_quant.onnx` | 67,144,712 | `d92f33dfcff83077fc2e0d3414250710efbb51795dfd89767bdbefb5fdc47322` |
| CLIP vocabulary | `bpe_simple_vocab_16e6.txt` | 3,194,984 | `67603cfda2e032ad77b5f8808af37789d590db664b26df8705d2bf8b3c553fc8` |

The primary face + semantic-search set is approximately 251,030,942 bytes
(~239.4 MiB). Optional Ente pet recognition adds further models (roughly
another 47 MB in the current catalog); Ente's user-facing FAQ describes
model downloads as around 300 MB depending on enabled features. Actual
installed size must be measured after packaging and compression.

**Policy mismatch:** ADR-0010 currently says models ship in the APK and
forbids runtime downloads; ADR-0014 enforces no INTERNET permission.
Ente's current asset manager downloads model assets from
`models.ente.com` and verifies size/SHA-256 before staged installation;
the inference itself is local. Two valid product choices:

- **(A)** keep ADR-0010/0014 and fetch model files during the release
  build, package them or provide an offline model pack; or
- **(B)** explicitly supersede the no-INTERNET policy to allow model-only
  downloads, with a strict host allowlist, no media upload endpoints, and
  egress tests.

The implementation must not silently choose (B) while claiming the
existing "cannot access network" guarantee.

**Recommended development default:** vendor the pinned model manifest and
automate acquisition/verification in CI, then ship a local model pack (APK
/ app bundle or user-imported pack) so inference remains fully offline. If
the owner explicitly prefers first-run downloads, record that as an ADR
change before adding the network permission.

## Local-only adaptation rules

1. Remove Ente login/account dependencies, remote API clients, server
   index synchronization, person-entity sync and cloud file download
   paths.
2. Keep Ente's local ML SQLite schema, migrations, face rows, cluster
   relations, user feedback and vector search semantics wherever
   practical.
3. Map Ente's integer `file_id` to a stable toadGALLERY local media key.
   Do not store raw path as identity.
4. Use a Kotlin-native bridge: a narrow JNI/UniFFI facade with typed
   requests/results and byte/descriptor input; do not expose the whole
   Ente Rust API surface to the UI.
5. Use WorkManager for durable indexing jobs and Kotlin coroutines for
   bounded work; preserve cancellation, resumability, progress and error
   classification.
6. Separate model availability from inference readiness. Verify every
   file's SHA-256 and expected size before session creation; install
   atomically and keep a compatible rollback path.
7. Persist per-capability model/index versions. A face-model update
   invalidates face embeddings/clusters; a CLIP model update invalidates
   image embeddings and the associated vector index.
8. Make Vault exclusion a type/API invariant: only `EligiblePublicMedia`
   can be submitted to AI. Check eligibility at enqueue, immediately
   before reading bytes, and before persisting results.
9. Delete AI rows, vector entries, cached embeddings and queued jobs when
   a media item is deleted, trashed, permission-revoked or becomes
   ineligible.
10. Do not index video frames in the initial port; add a separately
    designed video keyframe policy later.

## People, Places, Things and search scope

- **People:** Ente face detector -> face alignment -> MobileFaceNet
  embedding -> quality metrics -> linear incremental clustering -> user
  merge/split/ignore/exclude corrections. Face indexing should be opt-in
  and disabled by default, consistent with the existing ADR-0010 consent
  direction.
- **Places:** no AI model needed. Derive local place groupings from
  Phase 4 GPS metadata and optional user-defined place labels. No reverse
  geocoding or network request. If location permission is unavailable, the
  feature remains unavailable without blocking face or semantic indexing.
- **Things / Magic Search:** Ente's CLIP image/text embedding pipeline
  supports natural-language content search (objects, scenes, colours,
  activities and more). This is not the same as producing a fixed list of
  object labels for every image. Use CLIP query results to power semantic
  search and dynamic "Things" collections. If the product requires
  persistent object tags or offline label taxonomies, that is an
  additional feature beyond the core Ente port.
- **Pets:** Ente's current catalog includes optional pet face/body
  detection and species-specific embeddings. Treat as an optional
  extension after People and CLIP search; do not include the extra model
  pack in the first delivery unless explicitly desired.
- **Stories, collages and slideshows** are product/UX features, not part
  of the Ente ML core. They may consume dates, location and search
  results but should not be coupled to the native inference engine.

## Proposed implementation sequence

| Workstream | Deliverable | Exit gate |
| --- | --- | --- |
| 0. Scope reconciliation | Confirm "Phase 5" means gallery AI; reconcile roadmap phase numbering and ADR-0010 wording | One authoritative phase map; no contradictory roadmap/ADR statements |
| 1. Phase 4 contract | MediaIdentity, MediaRevision, MediaDates, MediaReadHandle, MediaEligibility, canonical decoder, Room catalogue, change feed | Contract tests pass; no Ente dependency on MediaStore internals |
| 2. Ente source pin + native spike | Pin Ente commit; build Rust ML crate for Android ABIs; minimal Kotlin bridge; one image analyzed from byte input | Face + CLIP inference runs on physical Android device; no Flutter runtime |
| 3. Model pack | Pinned model catalog, SHA verification, atomic provisioning, app-private model directory, update/rollback | All required models verify; install interrupted/retried safely; no untrusted model path |
| 4. Local persistence | Port/adapt Ente ML DB + vector store; map local media keys; migration/version strategy | Face rows and CLIP vectors persist and reopen; no cloud tables or sync jobs |
| 5. Index coordinator | WorkManager queue, bounded concurrency, retries, resumability, progress, invalidation | Large library indexes incrementally; process death and permission revoke recover cleanly |
| 6. People | Face clustering, person labels, merge/split/ignore and photo exclusions | Golden test corpus + correction tests + Vault exclusion tests pass |
| 7. Magic Search / Things | Text encoder, tokenizer, vector search, result ranking and UI | Queries run offline and return expected golden-corpus results |
| 8. Privacy/performance hardening | Egress tests, Vault boundary tests, 10k/50k library benchmarks, low-memory and ABI matrix | No media/index/query egress; memory/latency targets met |

### Phase 4 acceptance gates specifically for Phase 5

1. Stable media key survives process restart, sorting changes and metadata
   cache refresh; volume changes do not collide.
2. EXIF/XMP/QuickTime date precedence is deterministic and
   timezone-aware; missing, malformed and unsupported metadata are
   distinct states.
3. External file edit, MediaStore generation change and in-app metadata
   write update MediaRevision and invalidate only affected indexes.
4. MediaReadHandle can supply original bytes without exposing a raw
   filesystem path and without requiring full-file memory buffering.
5. The image decoder produces the expected orientation and RGB tensor on
   JPEG, HEIC/HEIF, PNG, WebP and supported RAW test samples; unsupported
   formats are skipped with a reason.
6. `ACCESS_MEDIA_LOCATION` behavior is tested separately from ordinary
   media permission; revocation removes GPS-derived Places data.
7. Vault entries cannot be represented as `EligiblePublicMedia`; tests
   prove no enqueue, decode, persistence or search result can include
   Vault media.
8. Room catalogue migration and Ente ML DB/vector migrations are
   non-destructive and independently versioned.
9. 10k and 50k library scans are incremental; no full-library metadata
   parse or AI re-index occurs on every app launch.

## Risks and decisions

| Risk | Impact | Mitigation / decision |
| --- | --- | --- |
| Phase numbering conflict | Wrong work may be implemented under Phase 5 | Reconcile roadmap and ADR before coding; this document labels gallery AI as Phase 5 working scope |
| Ente Rust stack build complexity | Native ABI/ORT build may consume more time than Kotlin layer | Run a minimal Android JNI/UniFFI spike before large port; reuse crate tests |
| Flutter bridge assumptions | Dart host cannot be reused in Compose | Reuse Rust core; replace bridge and orchestration only |
| Model size ~239 MiB core set | APK/app install size and storage pressure | Measure compressed and installed sizes; optional offline model pack; capability toggles |
| Runtime model-download policy conflict | No-INTERNET guarantee conflicts with in-app model download | Choose bundled/offline pack or supersede ADR with model-only download policy |
| MediaStore ID churn / removable volumes | Wrong AI results attached to different media | Volume-aware identity + revision token + reconciliation |
| Metadata write modifies media revision | Stale embedding or false duplicate indexing | Refresh metadata and increment revision after every successful write |
| Vault leakage | Sensitive content appears in People/Search | Type-level eligibility + three enforcement gates + negative tests |
| Video indexing assumption | Ente image pipeline may not handle video as expected | Still-image-only first release; separate keyframe design later |
| CLIP "Things" semantics | CLIP is semantic retrieval, not fixed labels | Name feature Magic Search / dynamic Things; add classifier only if fixed tags are required |

## Source map and references

- toadGALLERY `roadmap.md` (Phase 4/5/8 scope and current numbering):
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/roadmap.md>
- toadGALLERY `MediaItem.kt`:
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/data/MediaItem.kt>
- toadGALLERY `MediaStoreSource.kt`:
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/data/MediaStoreSource.kt>
- toadGALLERY `VaultRepository.kt`:
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/data/vault/VaultRepository.kt>
- toadGALLERY ADRs:
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/integration-decisions.md>
- toadGALLERY Aves audit:
  <https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/phase-1/source-audit-aves.md>
- Aves metadata implementation:
  <https://github.com/deckerst/aves/tree/develop/android/app/src/main/kotlin/deckers/thibault/aves/metadata>
- Aves MediaStore generation handler:
  <https://github.com/deckerst/aves/blob/develop/android/app/src/main/kotlin/deckers/thibault/aves/channel/calls/MediaStoreHandler.kt>
- Ente Rust ML indexing API:
  <https://github.com/ente/ente/blob/main/rust/bindings/frb/photos/src/api/ml_indexing_api.rs>
- Ente Rust ML inference:
  <https://github.com/ente/ente/tree/main/rust/crates/ml/src>
- Ente face clustering:
  <https://github.com/ente/ente/tree/main/rust/crates/photos/src/face_clustering>
- Ente ML DB:
  <https://github.com/ente/ente/tree/main/rust/crates/photos/src/ml_db>
- Ente vector store:
  <https://github.com/ente/ente/tree/main/rust/crates/photos/src/ml_store>
- Ente model catalog:
  <https://github.com/ente/ente/blob/main/rust/crates/ml/src/assets.rs>
- Ente ML architecture: <https://ente.photos/ml/>
- Ente ML product docs:
  <https://github.com/ente/ente/blob/main/docs/docs/photos/features/search-and-discovery/machine-learning.md>
- Ente model download FAQ:
  <https://ente.photos/help/photos/faq/search-and-discovery>
- Aves date semantics FAQ: <https://github.com/deckerst/aves/wiki/FAQ>
- Android MediaStore docs:
  <https://developer.android.com/training/data-storage/shared/media>
- Android WorkManager long-running work:
  <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running>
- ONNX Runtime Android docs:
  <https://onnxruntime.ai/docs/tutorials/mobile/>

**Audit snapshot note:** Ente's model filenames, sizes and hashes were
read from its current Rust asset catalog during this audit. Pin a
specific Ente commit SHA in the implementation branch and regenerate the
model manifest from that pinned source before acquiring binaries. The
model binaries themselves are not included in this planning document; it
carries the model manifest and upstream source locations.
