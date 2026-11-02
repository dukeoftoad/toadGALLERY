# Changelog

All notable changes to toadGALLERY are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
`versionName` in `apps/gallery/build.gradle.kts` (pre-1.0 alphas).

## [0.4.2-alpha] — unreleased on main (versionCode 27; per maintainer
  directive no new device builds until Phase 5 completes)

### Added

- **The photos-side persistence stack, vendored** (r23 round; the Phase 5 AI
  engine workstream's third slice, `docs/phase-5/ente-pin.md`): the engine
  workspace gains two crates extracted from `ente/ente` at the SAME pin as
  r21 (`423b2d80d9ec89d45f5680cb1e1d777bac225f41`). `engine/crates/
  ente-vecdb` is the whole upstream `rust/crates/vecdb` vendored
  byte-identical (the vector store the face index searches over);
  `engine/crates/ente-photos-ml` is a partial-crate extraction of the
  upstream `photos` crate's `db`, `ml_db`, `ml_store` and `face_clustering`
  modules, byte-identical except a two-line `use` rewrite: the modules'
  `ente_core::crypto::random_bytes` imports (exactly two call sites) are
  routed onto an engine-owned seam (net-new `src/crypto.rs`) instead of
  vendoring all of `ente-core` for one function — the seam recorded in
  `engine/NOTICE.md`. The r21 self-containment finding is resolved the way
  that round's deferral predicted: the trio's `crate::db::…` imports are
  satisfied by vendoring the host `db` module alongside them rather than by
  re-porting the trio onto a new DB seam. Provenance rows added to the
  licensing ledger §5 (both AGPL-3.0 at the pin).
- **The face-index JNI surface + coordinator** (r23 round):
  `engine/crates/toad-ml-jni` extends its r22 four-function ABI with five
  externals: `nativeDetectFaceEmbeddingsJpeg` (runs detection + embedding
  over one JPEG and returns `N × (5 + 192)` flat row-major floats — per
  face `[x1, y1, x2, y2, score, e0..e191]`, the stride-197 layout, 192-d
  MobileFaceNet embeddings over the r22 detection path) and a face-index
  store surface over the vendored `MlStore` — `nativeStoreOpen` /
  `nativeStorePutFace` / `nativeStoreFaceCount` / `nativeStoreCluster` /
  `nativeStoreClose` — with the store's sqlite backed by bundled rusqlite
  (`<dir>/ml-index.sqlite`, its own migrations), so embeddings persist
  across engine sessions and clustering runs natively; clustering returns
  a compact JSON summary (`faces`/`clusters`/descending `sizes`). Kotlin
  side, `ml/engine/MlEngine.kt` maps the stride-197 flat floats to
  `FaceEmbedding` rows behind the existing facade, and
  `ml/index/IndexCoordinator.kt` orchestrates the round trip
  (detect → embed → putFace → cluster over upstream's
  `RECOMMENDED_DISTANCE_THRESHOLD`, 0.24, quoted from the vendored
  `face_clustering`) as pure JVM-testable logic over an injectable
  `FaceIndexEngine` seam — the Settings "index + cluster (r23 dogfood)"
  slice drives it end-to-end on a device the same way the r22 slice drove
  detection.

### Changed

- **CI**: the `engine` job's check/fmt/clippy gates already cover the new
  crates automatically (workspace selectors, no crate lists), but no step
  ran the vendored tests — it now runs `cargo test -p ente-vecdb -p
  ente-photos-ml` explicitly. `engine/scripts/build-android.sh` needs NO
  change: its `cargo ndk … build -p toad-ml-jni` pulls the new crates as
  workspace dependencies of the bridge, so the `engine-android` job and the
  `-PwithEngine` packaging are exactly the r22 ones (versionCode 27; no
  new device builds per the standing maintainer directive).

## [0.4.1-alpha] — 2026-11-02 (versionCode 26; per maintainer directive no
  new device builds until Phase 5 completes)

### Added

- **The pinned-host HTTP model downloader** (r22 round; ADR-0020's r21-named
  first r22 slice, `docs/phase-5/ente-pin.md`):
  `ml/engine/HttpModelDownloader.kt` — plain `HttpURLConnection`, ZERO new
  dependencies — fetches the pinned model assets from the single allowlisted
  host `models.ente.com`, verified against the live host before the pin (all
  five core assets answer HTTP 200 directly, no redirects, exact pinned
  content-lengths). Fetch URLs are built from parts over a structurally
  guarded file name (path traversal / absolute-URL / query / fragment
  injection is rejected before any socket can exist), redirects fail closed,
  and a `Content-Length` that disagrees with the pin aborts before the first
  byte. The app's first network permission lands with it: the manifest
  declares INTERNET exactly once for this grant (ADR-0020 amends ADR-0014
  for model fetches only), bounded by the merged-manifest gate + allowlist
  protocol, the extended `PrivacyPolicyTest` (permission-set pin,
  host-literal source scan, URL surface for all eleven entries, injection
  matrix) and the instrumented `PrivacyEgressTest` device checks (INTERNET
  granted; non-allowlisted targets refused without a connection).
- **The Android engine bridge + its build plumbing** (r22 round; the second
  r21-named slice): the net-new Rust crate `engine/crates/toad-ml-jni`
  (cdylib `libtoad_ml_jni.so`, a four-function JNI surface — session
  init/info/face-detect-over-JPEG-bytes/release — behind the Kotlin
  `MlEngine`/`MlEngineJni` facade, where a missing library is a graceful
  `LibraryMissing` state, never a crash) links the vendored AGPL engine for
  Android. `engine/scripts/build-android.sh` builds it with cargo-ndk
  (arm64-v8a; `--all` adds x86_64) together with ONNX Runtime's official
  `libonnxruntime.so` (the 1.31.0 Android AAR from Maven Central,
  sha256-pinned in the script — the exact version the ort-sys rc.15 api-31
  bindings expect; load-dynamic makes the runtime library a fetched supply,
  never a cargo artifact), staging both into a build-dir jniLibs
  folder that Gradle packages ONLY under `-PwithEngine` (`buildRustEngine`
  task + jniLibs srcDir; without the property the build is byte-for-byte the
  r21 one). CI gains the `engine-android` job — Android ABI build +
  engine-enabled assembleDebug, verifying both native libraries actually
  land in the APK — and the Settings "AI engine (r22 dogfood)" section drives
  the whole path on a device: download the FACE assets with live per-asset
  presence updates, delete, and a one-JPEG face-detection self-test
  (byte-level download progress deliberately deferred).
- **The Ente ML engine, vendored at a pinned commit** (r21 round; the Phase 5
  AI engine workstream's first slice, `docs/phase-5/ente-pin.md`): the new
  root-level `engine/` Rust workspace carries `crates/ente-ml` (YOLO face
  detection + NMS, geometric alignment, MobileFaceNet embeddings, MobileCLIP
  image/text encoders, the ONNX Runtime provider policy and the model
  catalog), `crates/ente-assets` (asset download + verification + staged
  install primitives) and `crates/ente-image` (byte-oriented decode incl.
  HEIC/AVIF/JXL via the upstream git-pinned heic-decoder) — extracted from
  `ente/ente` at `423b2d80d9ec89d45f5680cb1e1d777bac225f41`, byte-identical
  except the recreated workspace root, the crate-directory renames and added
  crate `license` fields, with every adaptation recorded in
  `engine/NOTICE.md` and the provenance ledger
  (Ente is AGPL-3.0; the ledger records the one-way-compatibility consequence
  for distributed builds). CI gains an `engine` job running
  `cargo check --workspace` (plus fmt/clippy) so the vendored engine can
  never silently rot.
- **The model-delivery core** (r21 round; ADR-0020, the maintainer's
  Ente-style delivery decision): `ml/engine/ModelManifest.kt` pins ALL 11
  model assets (5 core — face detection, face embedding, CLIP image, CLIP
  text, BPE vocabulary — plus 6 pet-pack assets deliberately unprovisioned)
  with byte sizes and SHA-256 digests transcribed from the pinned Rust
  catalog, so no "latest" model can ever be fetched or silently swapped;
  `ModelVerifier` verifies expected-size-then-streaming-SHA-256 without ever
  buffering a model whole; `ModelProvisioner` implements the
  verify-then-stage-then-commit install (temp file → verify → atomic rename →
  receipt written last), per-capability readiness (Not/Partial/Ready), pin-
  change invalidation and explicit invalidate calls, with the transport
  abstracted behind `ModelDownloader` — the HTTP implementation, host
  allowlist and INTERNET permission are r22 by ADR-0020's ordering, so this
  round stays fully offline and the 9-test JVM suite runs hermetically.
- **The combined single-read EXIF index pass** (r20 round; the
  r19-named follow-up, closed): one `MediaReadHandle` open + one
  byte read + one EXIF parse per row now serves BOTH provenance
  dimensions — the capture date (through the r12 resolver path,
  unchanged) and the GPS coordinates (through the r16 snapshot
  path, unchanged) — where the r12/r16 pair of standalone indexers
  each opened and streamed the same bytes. The two standalone
  indexers are removed with it (no dead code); the evidence types,
  the pure write mappings, the eligibility gate and the consent
  gate survive untouched, and the per-dimension availability
  contracts ride on through the combined pass's JVM suite. The
  capability check now runs BEFORE the bytes are read (the r16
  order note): a PNG/GIF/video row costs no read at all, where
  the r12 source paid a wasted full-read to learn the same
  nothing.
- **The NULL-provenance healing pass** (r20 round; the r19-named
  follow-up, closed): Room v5 → v6 adds a `provenanceIndexedAtMillis`
  stamp, written as the LAST of the pass's three targeted writes
  (date, location, stamp) — a row that fails anywhere stays
  unstamped, and a bounded healing pass (100-row batches, at most
  1000 rows per app start, newest-seen-first) re-offers exactly
  those rows to the same pass until they succeed. One transient
  error during a scan can no longer strand a row's date/location
  forever; pre-r20 rows heal once and are never re-selected unless
  a real change resets them. Non-destructive `ADD COLUMN`
  migration; the v6 identityHash re-derived with the documented
  Room 2.8.5 method (v1–v5 reproduced byte-for-byte as control)
  and verified against KSP's own regenerated snapshot.
- **Volume hot-plug for the scan pipeline** (r20 round; the
  r19-named follow-up, closed): an `ACTION_MEDIA_MOUNTED` shell
  (registered with `RECEIVER_NOT_EXPORTED` on T+) hands the
  signal to the driver's new `onVolumesChanged()` — observer
  registration is now idempotent against a tracked volume set,
  so a card inserted after process start gets its observer and a
  coalesced scan pass without waiting for the next app restart.
  Unmounted volumes need no signal (the walk only covers reported
  volumes; their rows keep their live records until the volume
  returns).
- **The vault batch store** (r20 round; the r19-named follow-up,
  closed): a multi-item move-to-vault now seals the whole
  selection under ONE index read + N blob writes + ONE index
  rewrite inside the store lock, where each item used to pay the
  full read-copy-rewrite cycle. Per-item seal failures delete
  that item's blob and report FAILED alone (siblings continue);
  a whole-batch index-rewrite failure deletes every staged blob
  and rethrows, exactly like the single-item `store`. The move
  flow's two phases (seal batch, then per-item consent-checked
  deletes) are documented on the repository method, positional
  outcome pairing unchanged.
- **Folder filter + catalogue bucket dimension** (r19 round; the
  r17-named follow-up, closed):
  - The catalogue persists the folder dimension as row-sourced
    `bucketId`/`bucketDisplayName` columns (Room v4 → v5,
    non-destructive; the hand-derived v5 identityHash was re-derived
    from the Room 2.8.5 sources with the documented method and
    matches byte-for-byte, with v1–v4 reproduced as control). Unlike
    the EXIF-derived provenance columns, bucket values ride the
    upsert itself — no wipe/refill cycle, so they survive every scan.
  - The folder-filtered timeline window is served from the catalogue
    (a `bucketId = ?` clause composed in the engine's fixed order)
    and from both MediaStore legs (API 30+ Bundle-args in-query; the
    pre-30 snapshot leg fails closed with the chips row still
    reachable — the way back is never lost).
  - The Photos chips row gains a leading **Folder** chip → a picker
    sheet (folder names + live counts, honest Loading/Error/Empty
    states, "All folders" clears only the folder dimension); the
    selection survives sort and backend changes like every other
    chip dimension. The r17 "folder chip stays engine-only" note is
    retired.
- **Honest error and permission states** (r19 round):
  - Albums and AlbumDetail gain Error states with retry — the first
    live call sites of the shared ErrorState component; a provider
    hiccup no longer crashes the Albums tab (it previously escaped
    `viewModelScope.launch` uncaught, the one screen in the app with
    no error arm) and an AlbumDetail failure no longer disguises
    itself as "folder is empty".
  - The Search tab gains the Photos permission pattern
    (PermissionNeeded with Grant + Settings deep-link instead of an
    endless generic Error whose Retry can never succeed); a
    mid-query `SecurityException` (revoked while composed) folds
    into the same state.
  - The vault's keystore-handle cache seam is wired: a
    permanently-invalidated biometric key invalidates the cache and
    reloads fresh instead of wedging the unlock flow.
- **Editable metadata fields (EXIF write-back)** (r15 round; the Phase 4
  write path):
  - A pure edit-capability gate (`MediaEditCapability`: EDITABLE /
    READ_ONLY / UNSUPPORTED_FORMAT / NOT_ELIGIBLE / UNSUPPORTED_FIELD /
    CONSENT_REQUIRED) with documented precedence; the GPS field is
    consent-gated on API 29+ through the r14 machinery.
  - The write flow opens the item's content URI read-write and saves
    through `androidx.exifinterface`; the pinned 1.4.1's supported
    write set and its inability to write `Rating`/`XPKeywords` were
    verified from the sources jar — those fields render honest "later
    round" states rather than silent no-op writes. PNG is excluded
    because our own pinned reader cannot display a PNG EXIF result.
  - The API 30+ `MediaStore.createWriteRequest` escalation is a pure
    state machine (single-shot retry, stale-signal guards) behind a
    thin activity-result shell; API 29 exactly reports the honest
    read-only outcome (no dialog exists there).
  - Details view: edit affordances on supported rows, live validation
    in the editor dialog, writes off the main thread, honest
    Saving/Failed/Denied states; a write re-enters the scan pipeline's
    documented eventual-consistency contract.
- **On-device map view** (r16 round; zero network tiles by
  construction):
  - The world renders from a bundled Natural Earth 110m land outline
    (public domain, ~135 KB); a dependency-free GeoJSON parser,
    equirectangular projection and grid clustering draw it (plus photo
    dots) on a new Map tab; tapping a dot opens a location sheet → the
    existing viewer. The offline tile-pack sideload is a documented
    later-round contract (`docs/phase-4/on-device-map.md`), not a fake
    tile loader.
  - GPS persistence, Room v3 → v4 (non-destructive; the hand-written
    v4 schema JSON was verified by re-implementing Room 2.8.5's
    identityHash algorithm — it reproduces the committed v1–v3 hashes
    byte-for-byte): latitude/longitude + provenance columns, written
    by a `LocationIndexer` (a second `ScanIndexHook`, composite
    fan-out) that parses EXIF GPS through the read boundary and
    persists honest nulls while the platform redacts (API 29+
    ungranted).
  - The map's empty/consent states are honest (no coordinates yet vs
    location consent off → Settings); the grant-triggered full re-pass
    is a documented Phase 5 seam (`LocationConsentReindexPolicy`).
- **Collections + smart-filter chips** (r17 round; Aves-style, design
  reference only — no Dart port):
  - A pure filter engine (media type incl. GIFs, favorites, folder,
    date-range) with three JVM-tested projections — in-memory
    predicate, MediaStore selection args, catalogue SQL WHERE —
    applied on BOTH timeline paths (catalogue windows and legacy
    MediaStore windows), so the grid never lies during cold start.
  - Chips row on Photos (All / Photos / Videos / GIFs / Favorites,
    clear-all) and a Collections section on Albums (derived, zero
    persistence). The folder chip stays engine-only — the catalogue
    has no bucket column — and date-range awaits its picker UI; both
    are the honest subset, not lying chips.
  - Flagged (pre-existing, NOT fixed here): search/favorites build
    `media_type IN (1, 2)` but the platform reference says 2 = AUDIO
    and 3 = VIDEO.
- **Format-support audit** (r18 round; the deliverable is a cited
  matrix):
  - A magic-byte `FormatSniffer` (19 honest verdicts: JPEG/PNG/GIF/
    WebP subtypes/HEIF/AVIF/TIFF-family RAWs incl. the DNGVersion
    walk/ISO-BMFF brands/EBML, bounded reads, honest UNKNOWN) +
    `FormatSupportCatalogue` (per-family still-decode / animated /
    EXIF-read / EXIF-write / playback), and
    `docs/phase-4/format-support-audit.md` — the format matrix with
    repo cites or official-docs URLs on every platform claim, per-API
    26→36 differences, and the honest-limitations register. Adoption
    point (Phase 5): the unsupported-format surface + Details rows.
  - 196 new JVM tests across the four rounds; all four PR suites ran
    green on CI before merging.

- **Metadata Details view (read path)** (r14 round; the first
  user-facing Phase 4 deliverable):
  - A new Details screen, reachable from the viewer's info sheet
    (`Full details`), presenting: dimensions, file size, MIME type,
    capture date with the timezone semantics stated explicitly (the
    instant rendered in the file's own recorded zone, the zone
    provenance, and the winning source), camera make/model/lens, the
    full exposure triangle, and GPS coordinates in decimal + DMS.
  - Availability is never flattened: PRESENT / ABSENT ("no block") /
    UNSUPPORTED (a capability statement) / UNREADABLE (corrupt) each
    render their own honest state; IPTC/XMP render as their format
    matrix capability ("not supported for this format yet" for the
    wrappers that are a later round) --- nothing is faked.
  - The complete EXIF block sits in a collapsible tree. All parsing
    runs off the main thread; the parsed snapshot is cached per item
    for the screen's lifetime. Vault/trashed/revoked items are refused
    by the same type-level eligibility gate the scan pipeline uses ---
    an unreadable item shows the honest "can't be read" state, never a
    partial read.
- **ACCESS_MEDIA_LOCATION permission flow** (r14 round; the other
  roadmap still-open item):
  - A pure, JVM-tested permission state machine (NOT_REQUIRED on
    <29 / UNKNOWN / GRANTED / DENIED) behind a thin production impl;
    the runtime request lives in a new Settings "Location data"
    section with an honest explanation (coordinates never leave the
    device; there is no network surface to carry them).
  - GPS rows in the Details view are DISPLAY-GATED on the consent
    state (`MediaLocationDisplayRules.Default`): hidden on an
    ungranted 29+ device, shown with a grant, always shown below 29
    (where the platform never redacted anything). A hidden PRESENT
    block stays availability-PRESENT with a pointer to Settings ---
    the availability statement is never faked. A live state observer
    re-renders the open Details screen when the grant flips.
  - The manifest declares ACCESS_MEDIA_LOCATION unconditionally (there
    is no minimum-API form; pre-29 devices ignore the unknown name and
    the runtime request is gated in code); a privacy-policy test pins
    the declaration.

### Pre-existing entries

- **Timeline capture-date adoption** (r13 round; the roadmap's promised
  behavior change --- the timeline's sort/group keys now use the
  persisted capture dates):
  - The Photos timeline orders and groups by the **effective capture
    date**: the resolved EXIF capture instant when the r12 indexer
    produced one, `date_added` otherwise. Day/month group headers
    derive from the same effective instant (timezone-aware as before;
    pre-1970 negatives are legitimate and land under their own day).
  - **Catalogue-backed timeline windows**: once the first scan has
    populated the catalogue, the timeline's windowed reads come from
    the Room catalogue itself, ordered by
    `COALESCE(resolvedCaptureDateMillis, dateAddedMillis)` with a
    `localMediaKey` tiebreak (the stable-total-order discipline that
    keeps windows tiling without duplicate grid keys). While the
    catalogue is still empty (first cold start), the timeline keeps
    the previous MediaStore windows unchanged --- no first-paint
    regression, no waiting screen.
  - **Room v2 -> v3** (non-destructive migration): the catalogue now
    carries the display/shape fields a timeline cell needs ---
    `displayName`, `width`, `height`, `durationMs`, `isFavorite` ---
    projected by the scan walk as NON-REVISION ride-alongs (a shape
    drift never triggers re-indexing; the known API-29-only favorites
    staleness edge is documented in the row contract).
  - Timeline invalidation while catalogue-driven rides the
    catalogue's own change flow (one shared Room instance, held by the
    Application, so invalidation reaches every observer) --- the
    eventual-consistency contract is documented: a fresh camera shot
    appears once the scan pipeline reconciles it, because the ordering
    key itself is produced by the scan.
  - 40 new JVM tests (catalogue window ordering/tiling, source-choice
    state machine, TimelineRows effective-date grouping, comparator),
    561 total green.

### Pre-existing entries

- **Phase 4 scan driver** (r12 round; the roadmap's "scan driver that
  wires the catalogue + change feed into app start" item):
  - On app start the process now runs the full incremental discovery
    pipeline: a per-volume authoritative MediaStore walk (`IS_TRASHED`
    excluded on R+), diffed against the Room catalogue through the
    r10 reconciler (added / changed / removed -> tombstone), a
    per-volume generation-token checkpoint saved last so a crash
    mid-apply self-heals on the next pass, and ContentObserver
    registration so later changes re-scan (API 30+ rescans only when
    the live generation differs from the checkpoint; API 26-29 always
    rescans --- the observer alone cannot prove the volume unchanged).
    The decision core is pure and exhaustively JVM-tested.
  - **Capture-date adoption** (the other r12 roadmap item): every row
    a scan adds or changes gets its capture date resolved ONCE from
    EXIF evidence through the MediaReadHandle byte boundary (only
    eligible public media; vault/trashed/revoked items are skipped by
    type) and persisted onto the record's provenance fields
    (instant + timezone assumption + winning source). Metadata that
    is ABSENT vs UNREADABLE vs UNSUPPORTED persists null under its own
    name; `date_added` is never persisted as a capture date. Room
    v1 -> v2 via a hand-written non-destructive migration (three
    `ALTER TABLE ... ADD COLUMN DEFAULT NULL`); the v2 schema JSON is
    committed under `apps/gallery/schemas/`.
  - The diagnostics ring logs `phase 4 scan driver started` at wiring
    time (the W1 seam lesson: a device log showing the boot line but
    not this line pins a pipeline-construction failure exactly).
  - Deliberately no UI consumes the catalogue yet --- the timeline's
    sort/group keys switch to the persisted capture dates next round,
    after a device pass proves the pipeline on real volumes.

### Pre-existing entries

- PR #81 audit-hardening slice (the implementable findings from
  `docs/CRASH_INVESTIGATION_PLAN.md`, landed alongside the r11
  biometric fix):
  - **Vault backup exclusion** (audit A): new `res/xml/backup_rules.xml`
    (pre-31 fullBackupContent) and `res/xml/data_extraction_rules.xml`
    (31+, cloud-backup AND device-transfer) exclude `filesDir/vault`
    (`pin.bin`, `index.bin`, `blobs/`, `biometric.binding`) from backup
    --- the AndroidKeyStore key is non-exportable, so restored
    ciphertext would be permanently unopenable, and vault state must
    not leave the device (ADR-0014; debt pre-tracked in
    `docs/integration-decisions.md`). A JVM test pins the rules.
  - **Room transactions** (audit D): the tombstone and resurrection
    transitions are now single `@Transaction` composite DAO methods
    (`removeItemsAndWriteTombstones` / `upsertItemsAndClearTombstones`);
    the in-memory reference was already atomic and now documents the
    equivalence, pinned by a deterministic interleaving test.
  - **Room schema export** (audit K): `exportSchema = true` +
    `room.schemaLocation`; the v1 schema snapshot (3 entities) is
    committed under `apps/gallery/schemas/` with a review-gate README.
  - **Decode planner overflow** (audit J): the memory-budget check is
    now a multiplication-free exact inequality --- adversarial
    dimensions that would overflow Long are refused deterministically,
    negative budgets refuse everything; 6 new boundary tests.
  - **Pre-1970 capture dates** (audit F): negative epoch timestamps are
    valid instants (scanned film/historical archives); only
    UNKNOWN-source candidates are discarded; the cross-source
    disagreement gap check is overflow-safe; 6 new tests.
  - **EXIF date metadata** (audit I, EXIF half): the extractor now also
    reads DateTimeDigitized, OffsetTimeOriginal/OffsetTimeDigitized and
    SubSecTimeOriginal (offset-aware epoch math with a documented UTC
    anchor when no/malformed offset; decimal-fraction subsecond
    convention, >3 digits truncated); `ParsedExif.captureDateCandidates()`
    emits both EXIF_ORIGINAL and EXIF_DIGITIZED candidates feeding the
    already-built MediaDateResolver; XMP stays UNSUPPORTED this round.
  Verification: 491 JVM tests green (34 new), lintDebug green,
  assembleDebug + assembleRelease (R8+KSP) green.
- Phase 4 contract foundation (the audit-mandated prerequisite slice, all
  JVM-tested pure Kotlin + thin untested Android adapters per ADR-0016,
  no UI yet): the media identity/eligibility keel (`MediaIdentity`, a
  volume-aware durable local media key where path/display name are never
  identity, plus `MediaEligibility` whose only positive product is the
  `EligiblePublicMedia` wrapper --- vault, trashed, permission-revoked,
  unsupported and corrupt media are unrepresentable at future AI call
  sites); revision tracking (`MediaRevision.diff` classifying UNCHANGED /
  METADATA_CHANGED (metadata re-read only, AI outputs kept) /
  CONTENT_CHANGED (all downstream outputs invalidated)); the timezone-
  aware capture-date resolver (EXIF original > EXIF digitized > XMP >
  container > MediaStore precedence with subsecond precision and a
  sources-disagree flag); the metadata seams (`MetadataAvailability`
  distinguishing PRESENT/ABSENT/UNSUPPORTED/UNREADABLE,
  `MediaMetadataSnapshot`, `ExifMath` + `ParsedExif` assembly over a thin
  `androidx.exifinterface` reader, container parses for video
  rotation/duration/zone-aware dates, and `MetadataFormatMatrix` --- the
  audit's parser capability matrix in data, not format promises); the
  Room media catalogue (`MediaCatalogue` over Room 2.8.5/KSP with
  entities, DAO, non-destructive migrations and per-volume scan
  checkpoints; an in-memory reference implementation carries the
  semantics on the JVM); the change feed (`MediaChangeFeed`: per-volume
  MediaStore generation on API 30+, ContentObserver fallback on 26-29,
  feeding `CatalogueReconciler` --- added/changed/unchanged/removed
  classification that tombstones deletions instead of hard-deleting, and
  treats a generation bump with identical size+date as a change (superseded
  by the audit-C semantics correction in this same unreleased line: the
  per-item token is the row's own generation-modified value, not the
  volume checkpoint), while
  in-app metadata writes alone never trigger re-indexing); the
  byte-oriented read boundary (`MediaReadHandle` + `MediaAccess`,
  openable ONLY for `EligiblePublicMedia`, permission re-checked at
  open, no plaintext temp files); and the canonical decode contract
  (`CanonicalDecodePlanner` power-of-two sampling with upright swaps and
  a deterministic memory-budget refusal instead of OOM roulette, plus
  the thin `AndroidBitmapDecoder`). 174 new JVM tests pin the contracts;
  no UI consumes any of this yet --- that is deliberate (the audit's
  "contracts before Ente integration" gate).
- On-device diagnostics (#74, the device round): every vault failure that
  used to swallow into a generic snackbar now leaves a trace. A new
  dependency-free ring-buffer logger `DiagLog` (CAP 400, thread-safe,
  oldest evicted) mirrors to `android.util.Log` under the shared `ToadDiag`
  tag and keeps entries as `MM-dd HH:mm:ss.SSS L/Tag: message` lines with
  the throwable's cause chain folded into the same entry (up to 3 levels,
  loop-safe). The privacy contract (ADR-0014) is enforced by construction:
  op names, counts, ids, sizes, durations and exception class+message ONLY
  --- never PIN data, key material or vault display names; with no INTERNET
  permission, logcat is the only egress and it requires adb/physical access.
  Every `VaultViewModel` catch (setup/unlock/biometric/auto-lock/consent
  retries) and the repository's phase legs (setup, verify mismatch vs
  corrupt record, restore insert/publish, rename/copy/favorite 0-row
  outcomes) WARN under the shared `ToadVault` tag, so one failure reads as
  a two-line story in logcat; the application logs its `versionName (build
  N)` at process start so a pasted logcat is always attributable to an
  exact build. 11 JVM tests pin the ring (cap/eviction/format/cause chain).
- Appearance settings and the About page (#74, the device round): Settings
  gains an **Appearance** section --- Layout (COLLAGE aspect-aware staggered
  vs GRID uniform cards, checkmark state, Ente-style), Photos per row
  (minus/plus steppers, clamped to [2,6], live preview) and Group by
  (None/Day/Month) --- persisted through the existing settings channel and
  applied to the Photos timeline immediately; grouping renders section
  headers through the new `TimelineRows` helper without disturbing paging
  keys, and defaults (COLLAGE / 3 / None) keep today's render byte-identical.
  A new **About** screen shows the app name, version + versionCode and the
  license pointer, plus a **Diagnostics** panel: the vault state probe
  (root/pin/index/biometric-binding presence, blob count + bytes, root
  writability, keystore key presence --- read-only, nothing decrypts or
  generates, safe from any screen state) and the `DiagLog` ring with
  copy-to-clipboard and clear. 27 new JVM tests pin the appearance codecs,
  clamps and the timeline grouping math.
- Vault integrity round 2 (#73): `restore()` now treats an unconfirmed
  publish as a failure --- `resolver.update()`'s row count is checked and a
  0-row publish throws into the existing failure path (pending row deleted,
  the vault copy KEPT, ADR-0017); the same ignored-return flaw was fixed in
  `MediaFileOps.copy`. First-time `VaultStore.setup()` is transactional:
  both ciphertexts are staged as `.tmp` files and committed by rename
  (index first, pin last); any leg failing rolls everything back and
  surfaces a retryable `VaultStoreException("vault setup failed; nothing
  was configured")` --- and `isConfigured()` now requires BOTH files, with
  a self-heal pass cleaning pin-only/index-only residue from pre-#73
  installs. 4 new failure-injection tests pin each rollback shape.
- Vault biometric unlock binding (#57, ADR-0019) and decrypted vault
  thumbnails (#58): the hidden vault gains an opt-in "Unlock with biometrics"
  path backed by hardware --- a second AndroidKeyStore AES-256-GCM key
  (`toadgallery_vault_biometric`, StrongBox→TEE) generated with
  `setUserAuthenticationRequired(true)` and per-use authentication, so EVERY
  unlock prompts. Enrollment (a settings-style toggle on the unlocked vault
  screen, visible right after setup) seals a fresh random 32-byte session
  secret under a prompt-authorized `BiometricPrompt.CryptoObject` cipher into
  a new optional, strictly-parsed store file `biometric.binding`
  (`version(1) || IV(12) || ciphertext(32) || tag(16)`) --- absent file =
  the pre-#57 PIN-only behavior, byte for byte; the PIN stays the primary
  and always-available fallback, auto-lock semantics are unchanged, and a
  biometric enrollment change honestly invalidates the key: the dead binding
  is removed with a "re-enable" nudge instead of a silent break. `MainActivity`
  becomes a `FragmentActivity` (the androidx prompt requires one); the new
  `androidx.biometric` dependency declares `USE_BIOMETRIC` but not `INTERNET`,
  so the ADR-0014 offline gate is unaffected. The unlocked vault list now
  shows decrypted, size-bounded thumbnails (photos downsampled to a 256px
  edge straight off the unseal stream via `inSampleSize`; videos decrypted
  to a temp file in the private `cacheDir`, one frame extracted via
  `MediaMetadataRetriever`, temp file deleted in a `finally` immediately
  after --- no plaintext vault media ever persists on disk), decoded on
  `Dispatchers.IO` under a 3-permit semaphore with per-item `Throwable`
  catching so a bad or oversized blob degrades to the placeholder plate
  instead of crashing; all thumbnail bitmaps live in ViewModel memory only
  and clear with the session on auto-lock. 29 new JVM tests pin the binding
  envelope, the store's binding leg, the thumbnail downsample/naming policy
  and the biometric readiness decision table.
- Favorites and sorting (#66): the viewer's top bar gains a heart toggle for
  the current page — a MediaStore `IS_FAVORITE` write through the same
  consent machinery as rename/delete (Android 11+ write-consent round-trip
  for items this app didn't contribute, single-shot retry; Android 10 keeps
  the honest failure narration since `createWriteRequest` needs Android 11)
  with an undo snackbar that toggles back. Favorited cells carry a small
  heart chip in every grid (the same scrim idiom as the duration and GIF
  badges, driven by the new `MediaItem.isFavorite` mapping in all queries),
  and the Albums tab pins a distinct "Favorites" pseudo-album card with a
  live count at the top — its sentinel bucket id (−1) routes the album
  detail screen to an `IS_FAVORITE = 1` query, and the count rides the
  unified collection observer on Android 11+ (favorites and search keep
  newest-first in v1). The Photos timeline and album detail gain a sort menu
  (Newest/Oldest first, `_id`-tiebroken total order, per-session state):
  changing it swaps in a fresh Paging generation — the provider sort clause
  is parameterized through the windowed queries, the snapshot paths sort
  in memory, and the observer-invalidation pipeline stays intact. Below
  Android 10 the whole favorites surface stays hidden (write gate, same as
  the other file ops). 15 new JVM tests pin the sort mapping and the
  favorites selection/sentinel rules.
- Album detail file operations (#64): the Albums tab grid gains the full
  #30 selection machinery --- long-press selection with checkmark overlays,
  count app bar, Rename (single selection), Copy, Move (album picker that
  excludes the bucket you are in) and Delete with the established consent
  round-trip, undo snackbars and cancellable batch progress; below Android 10
  the screen stays read-only like Photos. `FileOpsViewModel` proved genuinely
  screen-agnostic (zero code changes) and is now documented as driving both
  screens. Album grids also refresh after operations: API 30+ rides the same
  unified collection `ContentObserver` the Photos timeline watches (debounced,
  generation-guarded re-query), while every level re-queries when the
  file-ops machine settles back to idle --- covering Android 10, where ops
  are enabled but no observer fires (residual undo-staleness on Q documented
  alongside the Photos one). 5 JVM tests pin the move-target rule.
- Viewer operations (#63) and decoder fallback (#65): the full-screen viewer
  gains top-bar actions for the current page --- Info (Material 3 bottom
  sheet: name, type, size, dimensions, duration, date added, content URI,
  honest "Unknown" for missing values), Share (`ACTION_SEND` with the content
  URI and a read grant), and Q+-gated Rename and Delete driving a
  viewer-scoped `FileOpsViewModel` through the same consent machinery, undo
  snackbars and trash-routing as the Photos tab; a deleted page drops from the
  pager URI-keyed (swiping during the consent dialog is safe) and an emptied
  window closes the viewer, while a rename only rewrites `DISPLAY_NAME` so
  pages stay valid by design. Video pages now build their player with
  `DefaultRenderersFactory.setEnableDecoderFallback(true)`: a hardware decoder
  that rejects the stream (device log: 10-bit HEVC `hvc1.2.4.H123.90` in MKV,
  `c2.qti.hevc.decoder` failing with `NO_EXCEEDS_CAPABILITIES`) falls back to
  alternates including the software HEVC decoder, and a playback failure now
  shows an in-page error state with Retry instead of a silent black surface.
  18 new JVM tests (info formatting, window removal semantics).
- Search tab v1 (#62): the Search tab is real. A debounced (300 ms)
  `DISPLAY_NAME LIKE` query with `%`/`_` escaped through the SQL `ESCAPE`
  clause, photo/video/all media-type filter (the timeline's `media_type`
  semantics), newest-first results capped at 1000 in v1, rendered through the
  shared aspect-aware grid with the video-duration and GIF overlays and
  opening into the full-screen viewer on tap. Filter chips, clear button,
  short-query hint, no-results and error states follow the screen-state
  system; the query builder is a pure, Android-free helper pinned by 23 JVM
  tests (escaping, type clauses, sort/limit). Advanced smart filters stay in
  Phase 5 as planned.
- Signed testing pre-releases via a dedicated Release workflow (#59,
  ADR-0018): Phase 3+ snapshots are now shippable as installable,
  updatable builds. The new `Release` workflow
  (`.github/workflows/release.yml`, separate from CI) triggers on `v*`
  tag pushes and on `workflow_dispatch` (input `tag_name`, default
  `v0.4.0-alpha-r<run_number>` computed from `versionName`, so testing
  releases can be cut ad hoc without manual tagging), runs
  `assembleRelease` — the ADR-0014 privacy gate anchors on
  `assemble<Variant>`, so release APKs are still built under the
  offline-by-construction check — signs with a dedicated PKCS12 release
  keystore (RSA-2048, alias `toadgallery-release`, 25-year validity;
  never committed, held only as GitHub Actions secrets), and publishes a
  GitHub pre-release (`prerelease: true`, never marked latest pre-1.0)
  attaching the APK, a `.sha256` checksum and the R8 `mapping.txt`. A
  hard `apksigner verify` gate pins the signer certificate SHA-256 digest
  (`8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494`),
  so an unsigned or wrongly-signed APK can never reach the publish step;
  the digest is also the user-side verification anchor for sideloaded
  builds (`apksigner verify --print-certs`). The release
  `signingConfig` is injected from `TG_KEYSTORE_FILE` /
  `TG_KEYSTORE_PASSWORD` / `TG_KEY_ALIAS` / `TG_KEY_PASSWORD` env vars
  with a graceful unsigned fallback when they are absent, so local dev
  builds are unaffected. This pulls the Phase 11 "release-signed APK
  production on tag pushes" deliverable forward for Phase 3 testing
  builds; reproducible builds and F-Droid metadata (F-Droid builds and
  re-signs its own binaries, so this identity governs only the
  project's own testing/sideload pre-releases) stay in Phase 11.
- Viewer pinch-zoom, pan and animated GIFs (#50 follow-ups): photo pages zoom
  to 5x around the pinch centroid, pan with Fit-letterbox clamping (the photo
  never leaves the screen; zooming out re-centers), and double-tap toggles
  2.5x-at-the-tap-point / reset. While a photo is zoomed the pager's swipe
  locks until the user zooms back out (documented v1 trade-off); leaving a
  page resets its zoom. A custom pointer-count-split detector
  (`ViewerGestures`) replaces stock `detectTransformGestures`, which would
  consume single-finger drags and starve the pager at 1x. GIF items animate
  in the viewer only, through a per-request `decoderFactory`
  (`ImageDecoderDecoder` on API 28+, `GifDecoder` on 26/27, via the new
  decoder-only `coil-gif` dependency — no INTERNET permission, privacy gate
  unaffected); the grid keeps static first frames (#33/#34 behavior pinned).
  18 JVM tests pin the transform math (clamping invariants, double-tap
  anchoring, reset).
- Hidden vault storage + unlock UI (#32, slice 2/2, on the ADR-0017 core): the
  vault is now a complete feature. `VaultStore` (pure JVM) keeps the sealed PIN
  record, a sealed hand-rolled index (`VaultIndex`, strict parsing, escaped
  display names) and id-named sealed blobs under app-private storage — blob
  first, index second (crash orphans are reclaimed by the unlock sweep, never a
  dangling index entry); small files go through tmp+rename atomic writes.
  `AndroidVaultKeys` supplies the non-exportable AES-256-GCM AndroidKeyStore key
  (StrongBox when the device has it, TEE fallback). Setup/unlock run PBKDF2 on
  `Dispatchers.Default`; the unlock session is in-memory only and auto-locks on
  background (`ON_STOP`, shell lifecycle observer). "Move to vault" appears in
  the Photos selection app bar once the vault is configured: bytes are streamed
  through the sealing cipher, then the originals are removed through the #30
  consent machinery (one `createDeleteRequest` per batch); restore is the
  inverse pending-insert flow, deleting the blob only after the row publishes.
  The new `vault` sub-screen (Settings entry, status line leaks nothing while
  locked) offers setup with the no-recovery trade-off stated verbatim, unlock,
  restore, and confirmed permanent delete. 39 new JVM tests pin the store
  (round-trip, tamper/truncation, orphan sweep, wipe, wrong-key rejection) and
  the index format.
- Vault crypto core (#32, ADR-0017): pure-JVM `VaultCrypto` — PBKDF2-HMAC-SHA256
  PIN digests (210k rounds, constant-time verify, wipable `CharArray` input) and
  AES-256-GCM sealing (`IV(12) || ciphertext || tag(128)`, fresh IV per seal) with
  streaming seal/open for vault media beyond heap size; key delivered through the
  `VaultKeyProvider` seam (production: non-exportable AndroidKeyStore key, later
  slice). 13 JVM tests pin round-trip, wrong-key/tamper/truncation rejection, IV
  uniqueness and a 5 MiB streaming round-trip.
- Recycle bin (#31): deletion on the Photos timeline now routes to
  MediaStore's system trash on API 30+ (`createTrashRequest` consent flow,
  one request per batch; rows stay recoverable ~30 days with system-managed
  auto-purge), while Android 10 keeps the permanent-delete path (no trash
  protocol exists below R). New `trash` sub-screen reached from a Settings
  entry (live item count + total size, `DataSizeFormats`): restore or
  delete-permanently any selection, or empty everything — each via one
  consent request, narrated with honest snackbars. `TO_TRASH` seam from #30
  is now the delete default; JVM tests pin the size formatter; lint is a
  hard CI gate (see Changed).
- Full-screen media viewer (#50 — PR #51): tapping a grid cell on the Photos
  timeline or in album detail (outside selection mode) now opens the viewer —
  black edge-to-edge canvas with hidden system bars, a `HorizontalPager` over
  the tap-time window, photos full-fit via Coil, and in-app video playback
  through media3 ExoPlayer 1.11.1 (local content URIs only; roadmap L608).
  Chrome bar with back action + "n of m" counter; honest playback-error copy
  when the device codec can't decode the file; video grid cells that fail
  frame extraction (`getFrameAtTime` NULL on some device codecs) now show an
  `ic_video_fallback` poster instead of a blank cell. Window hand-off via a
  shell-scoped `ViewerViewModel` (the #27 pattern); JVM tests pin the
  `ViewerWindow` clamping contract. Pinch-zoom/pan and animated GIF playback
  are deferred (see issue #50).
- File operations on the Photos timeline (#30 — PR #48): rename (extension
  preserved), in-place copy (` (copy)` naming with collision numbering),
  move (copy→delete with per-item results), delete (permanent v1, explicit
  confirmation); long-press multi-select with a selection app bar, album
  picker, undo snackbars for rename/copy/move, cancellable >5-item batch
  progress, and one aggregated `createWriteRequest`/`createDeleteRequest`
  consent dialog for entries the app does not own (API 29+; ops hidden on
  older APIs). MediaStore-only, URI-first (ADR-0006); `DeleteMode.TO_TRASH`
  seam reserved for the recycle bin (#31).
- Design tokens & theme (#11 — PR #47): spacing/shape/elevation/motion/
  touch-target token system, full 15-role M3 typography, explicit light+dark
  fallback palettes (MI-inspired warm grays + single green accent, WCAG AA
  pairs computed), dynamic color kept on API 31+, edge-to-edge, and the four
  state components migrated to tokens. JVM test pins the scale invariants.

### Changed

- **Video playback is lifecycle-aware** (r19 round): ON_STOP pauses
  the player (audio no longer bleeds into the background); ON_START
  resumes only if the page was playing when stopped; a page that
  stops being the current pager page pauses (the mirror of the
  zoom-reset seam PhotoPage already had).
- **Every media grid honors the "Photos per row" setting** (r19
  round): Trash (previously a hardcoded 3-column staggered grid),
  Search and the move-picker grids now consume the same appearance
  setting as Photos/AlbumDetail.
- **One revision rule in one place** (r19 round): CatalogueReconciler
  now delegates to `MediaRevision.diff` — a generation-token
  appear/disappear alone is UNCHANGED (observability, not content),
  exactly as MediaRevision always documented; the reconciler test
  that pinned the contradictory semantics is flipped to the
  authoritative contract. Practical effect: a 29→30 upgrade day no
  longer rewrites every catalogue row for a token flip.
- **The Details view rides the shared process-wide catalogue** (r19
  round): a second, per-visit RoomDatabase (a leaked SQLite
  connection that would silently miss scan writes) is gone; the
  record lookup is a keyed `canonicalUri` DAO query
  (`recordsByCanonicalUri`) instead of a full-table `recordsMap()`
  scan per Details open.
- **Vault durability hardening** (r19 round): every mutating vault
  operation serializes behind a store-level lock; the orphan sweep
  defers blobs younger than a ten-minute grace window (deferred, not
  skipped — the next sweep reclaims them); atomic writes fsync
  before rename (power-loss durability); setup() rollback removes
  staged `.tmp` residue on any staging failure.
- Catalogue revision semantics corrected (PR #81 audit C, landing in the
  same unreleased 0.4.1 line as the Phase 4 foundation that introduced
  it): the per-item revision token is the row's own
  `MediaColumns.GENERATION_MODIFIED`, never the per-volume
  `getGeneration` checkpoint (which stays the scan-checkpoint-only
  input). The reconciler test that had pinned the full-re-index-storm
  behavior (generation bump with identical size+date = changed) is
  replaced by the corrected pair: one row's per-row token bump marks
  THAT row changed, unrelated rows stay unchanged. Contract KDocs
  corrected across MediaRevision / MediaChangeFeed / MediaScanRow /
  CatalogueRecord / CatalogueReconciler.
- Lint is a hard CI gate (#52): the 19 pre-existing NewApi errors from the
  file-ops slice are resolved (documented `@Suppress("NewApi")` on the
  Q-guard invariant; `@RequiresApi(R)` on the consent builders) and
  `continue-on-error` removed from the lint step.
- CI runs compile + JVM unit tests only — no emulator-based tests (ADR-0016,
  PR #45); the instrumented privacy suite is a manual on-device release gate.
- Docs (#21, #22 — PR #46): `androidphotofilters` and `jxl-coder-glide`
  marked DROPPED per ADR-0008; risk register updated (R-10 downgraded, R-14
  closed, R-16/R-17/R-18 added).

### Fixed

- **The favorites/search `media_type` bug** (r19 round; the r17
  round flagged it, the audit confirmed the blast radius):
  `media_type IN (1, 2)` treated 2 as VIDEO — but 2 is AUDIO (3 is
  VIDEO) — so the favorites pseudo-album silently dropped every
  favorited video and could surface audio rows, and search's Videos
  chip returned audio files. Both selections now pin images+videos
  (`IN (1, 3)` / `= 3`), cross-checked against the filter engine's
  constants by JVM tests so the literals cannot drift again.
- **The vault unlock-vs-move data-loss race** (r19 round; audit P0
  from two independent audits): a locked-state move-to-vault racing
  an unlock's `sweepOrphans()` could have the sweep delete the
  in-flight blob as an "orphan", the move's index write then
  reference the deleted blob, and the original MediaStore row be
  deleted — permanent, irreversible loss. The store-level mutex
  closes every read-modify-write interleave (store vs store, delete
  vs store, wipe vs store, unlock-sweep vs move); a parked-stream
  JVM test pins the exact interleave with a blob deliberately aged
  past the grace window so only the lock can save it.
- **GPS editor NaN corruption** (r19 round): `toDoubleOrNull`
  accepts "NaN" (and "0x1p3", "1.5d"); NaN passes the latitude
  range check and the write path persists GPS (0,0) into the file's
  EXIF. The editor now accepts a strict decimal grammar + finiteness
  only; the refused-input corpus pins the exotic forms and the
  round-trip canary pins that prefill-produced scientific notation
  still re-parses.
- **Viewer swipe-lock after delete-while-zoomed** (r19 round): the
  zoom flag was remembered outside `key(window)`; deleting the
  zoomed photo cancelled (not ran) the old page's reset effect and
  the pager stayed swipe-locked until an accidental gesture. The
  flag now re-seeds with the rebuilt window.
- **Photos permission race** (r19 round): a debounced catalogue
  change from the app-start scan could publish a Ready grid of
  un-grantable thumbnails BEHIND the system permission dialog (a
  later grant then early-returned and left the broken cells); a
  denial could not cancel the in-flight generation, letting a stale
  Ready overwrite PermissionNeeded. Denials now cancel and clear;
  catalogue changes only auto-start when already Ready.
- **Scan indexer per-row isolation** (r19 round): a throwing row in
  CaptureDateIndexer/LocationIndexer aborted the whole indexing
  batch — despite the KDoc promising otherwise — leaving every
  remaining row's provenance NULL forever (later scans see the rows
  UNCHANGED and never re-offer them). Per-row try/catch makes the
  promise structural; the NULL-provenance healing pass stays a named
  follow-up.
- **Vault thumbnails authenticate the GCM tail** (r19 round):
  `BitmapFactory.decodeStream` stops at end-of-image, so the
  trailing GCM tag was never consumed and a tampered/truncated blob
  could publish a (partial) thumbnail with no authentication
  failure. The decoder now drains to EOF after the decode inside the
  same `use` block; a drain failure yields the placeholder.
- **Format sniffer major-brand rule** (r19 round): image brands were
  consulted before video families over the mixed major+compatible
  brand list, so an HEVC camcorder file (major `qt`/`isom`, compat
  `hevc`) sniffed as a HEIF still image — wrong on every axis. The
  major brand now decides the video families; compatible brands
  classify images only.
- **WebP editability drift** (r19 round): the format matrix said
  WebP `editable=false` while the r15 write spine (and its tests)
  made WebP EDITABLE — two declared truths disagreeing. The matrix
  now agrees with the gate; the heic/heif rows carry their API-28
  floor and the AVIF note its verified API-31 read floor.
- **Pre-Q projection crash guard** (r19 round): the timeline and scan
  queries project WIDTH/HEIGHT against pre-Q collections where the
  columns may not exist (MediaColumns surface from API 29); a
  rejecting provider threw out of `ContentResolver.query` with no
  catch. A one-shot reduced-projection retry now degrades honestly
  (width/height 0) instead of crashing API 26–28.
- The biometric unlock sheet still crashed --- the authenticator class,
  not the missing negative text, was the real defect (#78 follow-up,
  the r11 device finding, build 14): one androidx.biometric validation
  after r10's fix, the same tap died with `IllegalArgumentException:
  Crypto-based authentication is not supported for Class 2 (Weak)
  biometrics.` from androidx.biometric 1.1.0's
  `BiometricPrompt.authenticate(PromptInfo, CryptoObject)`. Root cause
  (verified against the library sources): the vault's prompt binds a
  CryptoObject (the auth-per-use AndroidKeyStore key, ADR-0019/#57)
  while requesting `BIOMETRIC_WEAK`, and the library rejects that
  combination unconditionally --- it tests the REQUESTED authenticator
  flags, not the device's sensor class (the error's "Class 2" wording
  names the requested class, not the test device's sensor), so the path
  crashed on EVERY device, not only Class-2-sensor devices. The prompt
  now requests `BIOMETRIC_STRONG` and the readiness probe uses
  `canAuthenticate(BIOMETRIC_STRONG)`: devices without a Class 3
  sensor/enrollment land in the existing `Unsupported` readiness ---
  PIN-only, no biometric UI --- the honest degradation, since a Class 2
  biometric cannot authorize an auth-per-use AndroidKeyStore key at
  all. The r10 "Use PIN" negative button stays (still mandatory for a
  biometric-only crypto prompt). The same honest JVM limitation as r10
  continues: the library's `build()`/TextUtils enforcement is
  device-checklist territory (r11 checklist, section 13), while the JVM
  suite pins the builder fields via reflection (negative text +
  `BIOMETRIC_STRONG`, plus a new bit-math regression test against the
  library's own `isWeakBiometricAllowed` check).
- The biometric unlock sheet no longer crashes the app (#78, the r9
  device finding, build 13): tapping "Unlock with biometrics" died with
  `IllegalArgumentException: Negative text must be set and non-empty.`
  from androidx.biometric's `PromptInfo.Builder.build()` --- a
  CryptoObject-bound `BIOMETRIC_WEAK` prompt cannot combine with
  `DEVICE_CREDENTIAL` (the Keystore handshake only authorizes a biometric
  round), so the negative button is library-mandated, and the prompt
  never set one. It now carries a "Use PIN" negative button --- the
  truthful label, since the ERROR_NEGATIVE_BUTTON path was already wired
  to the silent CANCELLED outcome that drops the user back on the PIN
  screen. The builder configuration is isolated in
  `VaultBiometrics.promptInfoBuilder` and pinned on the JVM via
  reflection (2 new tests); `build()` itself calls `android.text.TextUtils`,
  which the unit-test stub does not implement, so the library's runtime
  enforcement stays on the r10 device checklist.
- Vault setup works on real devices at last (#76, the r8 device finding):
  the r8 DiagLog capture showed setup dying at the first seal with
  `InvalidAlgorithmParameterException: Caller-provided IV not permitted` ---
  AndroidKeyStore's default randomized-encryption policy rejects
  caller-provided IVs on ENCRYPT, so `VaultCrypto`'s caller-generated IV
  (fine on the JVM software keys the tests use) made EVERY real-device
  vault setup fail since the original #32 slice. The seal init now uses
  the canonical provider-IV pattern first (`init(ENCRYPT_MODE, key)` +
  `Cipher.getIV()` --- the same pattern the biometric slice already used),
  with a caller-IV fallback for exotic providers; the ADR-0017 envelope
  and the decrypt side are byte-identical. 3 new JVM tests pin the
  negotiated-IV contract.
- The Photos timeline no longer crashes when a refresh lands mid-scroll
  (#76, the r8 crash, symbolicated via the release `mapping.txt`):
  `IndexOutOfBoundsException: Index: 60, Size: 60` --- the staggered grid
  asked cell keys for indices from a layout range one frame staler than
  the refresh-shrunk paging store, and the key lambda's
  `LazyPagingItems.get` throws in exactly that window. Key/span/content
  now read the row list and the snapshot bounds-checked
  (`timelineCellKey`, new, generic, JVM-tested against the exact device
  shape); stale intervals degrade to positional keys; the content leg
  keeps the guarded presenter read that drives Paging's append hints.
- Vault setup no longer dies on exotic StrongBox keystores (#74, the
  device bug): some OEM/custom-ROM keystores abort StrongBox key generation
  with a plain `ProviderException`/`KeyStoreException` instead of the
  documented `StrongBoxUnavailableException` --- which used to abort vault
  setup outright, surfacing as the device's "vault PIN creating failed"
  with zero diagnostics. The StrongBox leg now falls back to the TEE
  keystore on ANY `GeneralSecurityException` or `RuntimeException`
  (WARN-logged with the throwable; the expected StrongBox-unavailable case
  stays INFO). Only the TEE path itself still propagates --- a keystore
  that cannot mint a regular key is genuinely fatal. Both spellings satisfy
  ADR-0017 ("inside AndroidKeyStore"); StrongBox remains defense in depth,
  never a requirement.
- Vault thumbnails can no longer be repopulated after locking (#69): a
  thumbnail decode is a suspend call, and an auto-lock/background event
  landing mid-decode used to race the still-running batch — it returned and
  published freshly decrypted pixels into the CLEARED map of a locked
  session. The guard is two-legged, per the defect report: a monotonic
  lock-session generation token (`VaultLockGeneration`, advanced at every
  unlock/lock boundary — `enterUnlockedSession` and `onBackgrounded`) that
  each batch captures at launch, plus wholesale cancellation of the in-flight
  batch jobs on lock; and the decisive half — every bitmap must re-validate
  generation AND unlocked state IMMEDIATELY before it is published
  (`VaultThumbnailPolicy.shouldPublishThumbnail`), so a stale bitmap is
  dropped, never republished. Cross-session staleness is covered too: a
  batch orphaned from a previous unlocked session can never match the new
  generation. 7 new JVM tests pin the token semantics and the 2×2 publish
  truth table (fresh+unlocked only).

## [0.3.3-alpha] — unreleased on main (versionCode 7)

### Added

- Aspect-ratio-aware staggered media grid; video duration overlay; GIF play
  indicator (#33, #34 — PR #37).
- Paging 3 incremental timeline windows + load-state layering (#28 — PR #37).
  (The paging slice was internally tagged 0.3.2-alpha on its branch; it
  shipped as part of 0.3.3.)
- MediaStore change observation wired to paging invalidation, append
  load-state footer, idempotent permission re-entry, Ready-state test tags
  (#38 — PR #40).
- Screen-state system: Loading / Error / Unsupported-file layouts,
  permission-denied state with a Settings deep-link, per-state test tags
  (#13 — PR #41).
- System Photo Picker alternative path: Settings toggle, `ACTION_PICK_IMAGES`
  flow on API 33+, picked-photos browsing view (#27 — PR #42).

### Changed

- CI: retry + /data-exhaustion hardening rounds for the (now removed) emulator
  privacy job (PRs #39, #43) — final scope decision in ADR-0016.

## [0.3.1-alpha] — 2026-10-30

### Added

- Albums tab: folder grid, album detail view, shared media UI pieces
  (#29 — PR #35).

## [0.3.0-alpha] — 2026-10-29

### Added

- Photos tab: MediaStore-backed timeline, `READ_MEDIA_IMAGES` /
  `READ_MEDIA_VIDEO` permission flow (legacy `READ_EXTERNAL_STORAGE` capped at
  API 32), four-tab shell (Photos / Albums / Search / Settings)
  (#25 — PR #26).

## [0.2.1-alpha] — 2026-10-29

### Security

- Privacy gate, offline by construction (ADR-0014): build-time
  merged-manifest INTERNET permission gate with allowlist protocol,
  `PrivacyPolicyTest` JVM tripwires, instrumented egress suite (#20 — PR #23).

## [0.2.0-alpha] — 2026-10-29

### Changed

- v2.2 product re-frame: features/UI/UX/designs only from upstreams, Kotlin +
  Jetpack Compose, no Flutter, GitHub Actions CI; Compose-native app shell —
  Compose toolchain, NavHost migration, removal of `org.fossify:commons`
  (#16, #17, #18, #19).

## [0.1.0-alpha] — 2026-10-29

### Added

- Phase 2 exit: first product APK — app shell, Material 3 theme, offline
  posture (no INTERNET permission in the manifest), CI workflow (#15).
