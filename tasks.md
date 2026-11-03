# toadGALLERY — Comprehensive Master Tasks & Issue Tracker

**Repository**: `https://github.com/dukeoftoad/toadGALLERY`  
**Source Material**: Extracted strictly from the raw conversation transcript (`New File.txt`, lines 1–4684). All issue numbers (`#1`–`#96`), PR numbers, milestones, rounds (`r1`–`r29`), and status states reflect exact transcript records without invention.

## 1. Milestone Overview (v2.2 / Phase 4–5 Reconciled Structure)

| Milestone | Title / Scope | Transcript Status |
| :--- | :--- | :--- |
| **Phase 0** | Project bootstrap, repo setup, upstream baseline pins & builds | **Closed** (Turn 2, PR #9) |
| **Phase 1** | Upstream source audits, dependency/license screen, feature matrix, ADRs, editor contract spec | **Closed** (Turn 2, PR #9) |
| **Phase 2** | Product shell, visual foundation, privacy hardening, Compose toolchain, and CI | **Closed / Exited** (Turn 9/10, PRs #15, #16, #23, #46, #47) |
| **Phase 3 (M4)** | Core media access, browsing, file ops, recycle bin, hidden vault (16/16 issues closed) | **Code-complete / 16 of 16 issues closed** (Reopened in Turn 21 for device validation; all 16 issues closed by PR #70) |
| **Phase 4** | Aves-grade metadata layer & AI media-contract foundation (`r10`–`r20`) | **Complete** (Turn 35 PR #90 + Turn 38 PR #92) |
| **Phase 5** | Ente-derived on-device Gallery AI (`r21`–`r29`) + MI-style organisational UX | **In Progress** (`r21`–`r27` merged on `main` --- the `r26` People follow-ups `23a1495` and Magic Search `84aab67` rounds plus the `r27` organisational UX round `27c5e71`; Workstream 8 hardening `r28` in review as PR `#100`; on-device dogfood pending) |
| **Phase 6** | toadGALLERY Edit v1: core adjustments + non-destructive pipeline (gated by `#14`) | **Gate Landed** (Issue `#14`'s contract prototype delivered in r29 --- `:shared:editor-contract` + the `:apps:edit` prototype APK + the gallery launch path; the v1 adjustment suite rounds follow) |
| **Phase 7** | toadGALLERY Edit v2: LUT filters, text, stickers, drawing, mosaic, frames | **In Progress** (r33 the LUT filter system + r34 the user-import `.cube` flow on stacked branches; text/stickers/drawing/mosaic/frames pending; the merge chain r29–r33 in flight this run) |
| **Phase 8** | toadGALLERY Edit v3: on-device AI editing (magic erase, sky replacement, bokeh, opt-in face retouch) | **Planned** |
| **Phase 9** | Lightweight video editor (trim, mute, slow-mo, frame extraction, GIF/WebP export) | **Planned** |
| **Phase 10** | Performance, compatibility, accessibility, reliability hardening | **Planned** |
| **Phase 11** | Release readiness (signing pipeline pulled forward in `#59` / ADR-0018) | **Partially Pulled Forward** (`release.yml` live) |

## 2. GitHub Issue & PR Task Tracker (`#1`–`#96`)

### Phase 0 & Phase 1 Tasks (`#1`–`#9`)
* [x] **Issue #1**: Fossify Gallery `v1.14.1` (`e9812299`) baseline build verification — Built `gallery-30-foss-debug.apk` (66 MB) in 2m31s after JVM heap tuning. *(Closed in Turn 2)*
* [x] **Issue #2**: Aves `v1.15.8+182` (`4f940ae`) libre flavor baseline build — Diagnosed `compileSdk 37` and `pubspec.yaml` flavor generator blockers; documented container disk exhaustion (`9.9 GB`); downgraded to optional-build under ADR-0001/ADR-0008. *(Closed in Turn 5)*
* [x] **Issues #3–#8**: Phase 0/1 documentation & audit backlog:
  * [x] Record upstream baselines (`docs/phase-0/upstream-baselines.md`)
  * [x] Author build notes & risk register (`R-01`–`R-18`)
  * [x] Complete file-level Fossify Gallery source audit (`docs/phase-1/source-audit-fossify.md`)
  * [x] Complete file-level Aves source audit (`docs/phase-1/source-audit-aves.md`)
  * [x] Complete dependency & license compatibility screen (`docs/phase-1/dependency-license-compatibility.md`)
  * [x] Author audited feature matrix (`docs/feature-matrix.md`), ADRs 0001–0007 (`docs/integration-decisions.md`), module/data-flow diagrams (`docs/architecture/module-diagram.md`), and Gallery↔Edit contract spec (`docs/phase-1/contract-prototype-spec.md`). *(Delivered in **PR #9**, `42fd9981`, Turn 2)*

### Phase 2 Tasks — App Shell, Compose Migration, Design Tokens & Privacy Gate (`#10`–`#24`)
* [x] **Issue #10**: Buildable `:apps:gallery` product shell (`v0.1.0-alpha`, 4-tab navigation, zero dangerous permissions) — Delivered in **PR #15** (Turn 2).
* [x] **Issue #11**: Visual foundation & Material 3 design tokens (spacing, shape, typography, WCAG-checked light+dark palettes, dynamic color, edge-to-edge) — Seeded in **PR #16**, completed and closed in **PR #47** (Turn 16).
* [x] **Issue #12**: Top-level 4-tab navigation graph (`NavHost`: Photos, Albums, Search, Settings with single top-level back stack and state restore) — Delivered and closed in **PR #16** (`d04a257`, Turn 6).
* [x] **Issue #13**: Screen-state system (`Loading`, `Empty`, `PermissionNeeded`, `ErrorState` composables and semantics) — Seeded in **PR #16**, triaged to Phase 3, completed and closed in **PR #41** (Turn 15), and flipped to `IMPLEMENTED` in `docs/phase-2/screen-states.md` in **PR #92** (`r20`, Turn 38).
* [x] **Issue #14**: Gallery ↔ Edit URI intent contract prototype (two-component round-trip proof of ADR-0002 / ADR-0007) — Reassigned as the Phase 6 entry gate (Turns 9, 19, 47). **Delivered in the r29 round** (PR #104): the pure-JVM `:shared:editor-contract` module, the `:apps:edit` toadGALLERY Edit prototype APK (contract launch → real LUT modify → non-destructive MediaStore-INSERT save-copy export, three terminal states, version-negotiation refusal, zero network) and the gallery-side resolution/launch/Edit-op with the `<queries>` visibility; the physical round trip + hash compare ride the device checklist §19.
* [x] **Issue #17**: Jetpack Compose toolchain setup & CI verification (BOM `2024.12.01`, Kotlin `2.3.10` compiler plugin, Material 3) — Delivered and closed in **PR #16** (`d04a257`, Turn 6).
* [x] **Issue #18**: Migrate `MainActivity` from `BaseSimpleActivity` View shell to `ComponentActivity` + `setContent { ToadGalleryApp() }` — Delivered and closed in **PR #16** (`d04a257`, Turn 6).
* [x] **Issue #19**: Remove `org.fossify:commons` and all legacy View dependencies (`appcompat`, `fragment`, `constraintlayout`, `viewBinding`) per ADR-0008 — Delivered and closed in **PR #16** (`d04a257`, Turn 6).
* [x] **Issue #20**: Privacy test suite + build-time `INTERNET` permission merged-manifest gate (`gateDebugInternetPermission` / `gateReleaseInternetPermission`, `PrivacyPolicyTest`, `PrivacyEgressTest`, ADR-0014) — Delivered and closed in **PR #23** (`e9f458b`, Turn 9).
* [x] **Issue #21**: Mark `androidphotofilters` and `jxl-coder-glide` as DROPPED in `docs/phase-1/dependency-license-compatibility.md` per ADR-0008 — Delivered and closed in **PR #46** (Turn 16).
* [x] **Issue #22**: Update risk register (`R-14` closed; `R-16`, `R-17`, `R-18` added) — Delivered and closed in **PR #46** (Turn 16).

### Phase 3 Tasks — Core Media Browsing, Operations, Trash, Vault & Viewer (`#25`–`#76`)
* [x] **Issue #25**: Phase 3 Slice 1 — MediaStore photo timeline (`MediaItem`, `MediaStoreSource`, `PhotosViewModel`, `PhotosScreen` 3-column grid, runtime media permission flow) — Delivered and closed in **PR #26** (`5db89bd`, Turn 9).
* [x] **Issue #27**: Photo Picker alternative limited-access path (`PickMultipleVisualMedia`, persistent URI grants, `PickedPhotosScreen`, Settings toggle) — Delivered and closed in **PR #42** (`aaf010f`, Turn 15).
* [x] **Issue #28**: Paging 3 timeline pagination (`WindowPagingSource`, native API 30+ window query, lattice-snapped `getRefreshKey`, ADR-0015) — Delivered and closed in **PR #37** (`db835dd`, Turn 12).
* [x] **Issue #29**: Albums tab (`queryAlbums` by `BUCKET_ID`, 2-column folder cards) & `AlbumDetailScreen` (`queryBucket`) + shared `ui/media/` layer — Delivered and closed in **PR #35** (`173581c`, Turn 10).
* [x] **Issue #30**: Media file operations (rename, copy, move, delete, multi-select, undo snackbars, batch progress, API 29+ consent dialogs) — Delivered and closed in **PR #48** (Turn 16).
* [x] **Issue #31**: Recycle bin (API 30+ `MediaStore.createTrashRequest`, `TrashScreen`, restore / permanent delete / empty trash, Settings summary row, byte-size formatter) — Delivered and closed in **PR #53** (`4cef62e`, Turn 18).
* [x] **Issue #32**: PIN/biometric Hidden Vault:
  * [x] Slice 1/2: Pure-JVM `VaultCrypto` (PBKDF2 + streaming AES-256-GCM) + ADR-0017 — Delivered in **PR #54** (`02b9ca4`, Turn 18).
  * [x] Slice 2/2: `VaultStore`, `AndroidVaultKeys`, `VaultRepository`, `VaultViewModel` (`ON_STOP` auto-lock), `VaultScreen` — Delivered and closed `#32` in **PR #55** (`c12156d`, Turn 19).
* [x] **Issue #33**: Video duration & GIF badge overlays + `ToadGalleryApplication` `VideoFrameDecoder` registration — Delivered and closed in **PR #37** (`db835dd`, Turn 12).
* [x] **Issue #34**: Aspect-ratio staggered media grid (`LazyVerticalStaggeredGrid`) — Delivered and closed in **PR #37** (`db835dd`, Turn 12).
* [x] **Issue #38**: Paging 3 review follow-ups (`ContentObserver` invalidation, append-state footer, idempotent `onPermissionGranted`) — Delivered and closed in **PR #40** (`827a3d7`, Turn 14).
* [x] **Issue #50**: Full-screen photo/video viewer (`ViewerScreen`, `ViewerWindowState`, `media3` `ExoPlayer`, `ic_video_fallback` poster) + pinch-zoom/pan/double-tap & animated GIFs (`coil-gif`) — Delivered and closed in **PR #51** (`96f3e31`, Turn 17) and **PR #56** (`75e841b`, Turn 19).
* [x] **Issue #57**: Vault opt-in biometric unlock binding (`androidx.biometric`, `setUserAuthenticationRequired` KeyStore key, PIN fallback, ADR-0019) — Delivered and closed in **PR #68** (`d02c080`, Turn 21); hardened in **PR #78** (`"Use PIN"` negative button) and **PR #82** (`BIOMETRIC_STRONG`).
* [x] **Issue #58**: Decrypted in-memory vault thumbnails (`VaultThumbnailCache` / `VaultThumbnailPolicy`, cleared on lock, zero disk plaintext) — Delivered and closed in **PR #68** (`d02c080`, Turn 21).
* [x] **Issue #59**: Signed pre-release APK pipeline (`release.yml`, env-injected PKCS12 keystore secrets, hard `apksigner` digest gate, ADR-0018) — Delivered and closed in **PR #60** (`e926947`) & **PR #61** (Turn 20).
* [x] **Issue #62**: Timeline filename Search tab (`SearchScreen`, `SearchViewModel`, `SearchQuery` with `All`/`Photos`/`Videos` chips) — Delivered and closed in **PR #67** (Turn 21).
* [x] **Issue #63**: Viewer single-item operations (File Info modal sheet, Share via `ACTION_SEND`, Rename, Delete) — Delivered and closed in **PR #67** (Turn 21).
* [x] **Issue #64**: 10-bit HEVC (`Main 10`) video playback software decoder fallback (`setEnableDecoderFallback(true)`) + retry UI — Delivered and closed in **PR #67** (Turn 21).
* [x] **Issue #65**: Album detail multi-select file operations parity with album-grid refresh — Delivered and closed in **PR #67** (Turn 21).
* [x] **Issue #66**: Favorites (`createFavoriteRequest` toggle, grid heart badges, pinned Favorites album) & user-facing sort order (`Newest` / `Oldest`) — Delivered and closed in **PR #68** (`d02c080`, Turn 21).
* [x] **Issue #69**: Fix vault thumbnail lock race (`VaultThumbnailGate` generation token + in-flight decode `Job` cancellation + pre-publish re-check) — Delivered and closed in **PR #70** (`60b7c0f`, Turn 23).
* [x] **Device & Integrity Hardening Rounds (`PR #73`–`PR #76`)**:
  * [x] **PR #73**: Verify `resolver.update() > 0` before deleting vault copy on restore + two-phase transactional `VaultStore.setup()` with self-heal (Turn 24).
  * [x] **PR #74 / #75 (`r8`, build 12)**: `DiagLog` ring buffer + StrongBox→TEE fallback + Ente-style Appearance settings (Grid/Collage, 2–6 columns, None/Day/Month grouping) + About & Diagnostics screen (Turn 26).
  * [x] **PR #76 (`r9`, build 13)**: Fix AndroidKeyStore `Caller-provided IV not permitted` in `VaultCrypto` + fix `MediaPagingGrid` `Index: 60, Size: 60` crash via `timelineCellKey` snapshot-safe reads (Turn 27).

### Phase 4 Tasks — Aves-Grade Metadata Layer & Media Contracts (`r10`–`r20`, PRs #78–#92)
* [x] **Round `r10` (PRs #78, #79, #80 — Build 14)**:
  * [x] Biometric negative-button crash fix (`setNegativeButtonText("Use PIN")`).
  * [x] Volume-aware `MediaIdentity`, `MediaEligibility`, and type-level `EligiblePublicMedia` vault gate.
  * [x] `MediaRevision.diff()` invalidation classes and timezone-aware `MediaDateResolver`.
  * [x] `ExifContract`, ISO-BMFF/MP4 video metadata parser, and format capability matrix.
  * [x] Room `2.8.5` + KSP `2.3.10` `MediaCatalogue` (schema v1), tombstoning `CatalogueReconciler`, and `MediaChangeFeed`.
  * [x] `MediaReadHandle` byte boundary and `CanonicalImageDecoder` subsample/memory-budget planner.
  * [x] Raise Gradle JVM memory envelope to `-Xmx2048m -XX:MaxMetaspaceSize=768m` in **PR #80**.
* [x] **Round `r11` (PRs #81, #82 — Build 15)**:
  * [x] Resolve all findings in draft crash/audit **PR #81**.
  * [x] Switch vault biometric prompt & readiness check to `BIOMETRIC_STRONG` (Finding 1/L).
  * [x] Exclude `vault/` from cloud/device-transfer backups via XML backup rules (Audit A).
  * [x] Fix per-row `generationModified` revision token comparison (Audit C), add Room `@Transaction` wrappers (Audit D), export Room schema `1.json` (Audit K), guard decode overflow (Audit J), support pre-1970 capture dates (Audit F), and extract digitized/offset/subsecond EXIF tags (Audit I).
* [x] **Round `r12` (PR #83 — Build 16)**:
  * [x] `MediaScanDriver` + `AndroidMediaPermissionGate` wired on app startup.
  * [x] `CaptureDateIndexer` + Room v1→v2 migration (`2.json`) persisting resolved EXIF capture dates.
  * [x] Fix `release.yml` to derive asset round suffix from pushed `v*-r<N>` tag.
* [x] **Round `r13` (PR #84 — Build 17)**:
  * [x] Switch Photos timeline sort and Day/Month grouping keys to persisted Room capture dates (Room v3).
* [x] **Round `r14` (PR #85 — Build 18)**:
  * [x] `MetadataDetailsScreen` (dimensions, size, capture date + provenance badge, camera, exposure triangle, GPS, raw EXIF tree).
  * [x] `ACCESS_MEDIA_LOCATION` permission state machine, Settings section, and GPS display gate.
* [x] **Round `r15` (PR #86)**:
  * [x] Editable EXIF metadata write-back (`EditableExifCodec`, Android 10+ `WriteGrantStateMachine`, inline Details edit UI, honest `"later round"` state for `Rating`/`XPKeywords`).
* [x] **Round `r16` (PR #87)**:
  * [x] Bundled offline world map (`MapScreen`, Natural Earth 110m GeoJSON, Room v4 GPS columns via `LocationIndexer`, consent-gated dots).
* [x] **Round `r17` (PR #88)**:
  * [x] `TimelineFilter` combinator engine, `PhotosFilterChips` bar, and `DerivedCollections` across Room & MediaStore paths.
* [x] **Round `r18` (PR #89) & Integrator Docs (PR #90 — Build 22)**:
  * [x] Magic-byte `FormatSniffer`, `FormatCapabilityCatalogue`, `docs/phase-4/format-support-audit.md`, and Phase 4 completion docs (`versionCode 22`).
* [x] **Round `r19` (PR #91 — Build 23, Tagged `v0.4.1-alpha-r19`)**:
  * [x] Fix `media_type IN (1, 3)` bug in `Favorites.kt` and `SearchQuery.kt`.
  * [x] Close `VaultStore` unlock-vs-move temp-sweep race with store mutex + 10-min grace window + full-stream GCM tag verification on thumbnails.
  * [x] Add Room v4→v5 bucket columns (`5.json`) + Photos **Folder** filter chip and picker sheet.
  * [x] Add `ErrorState` wiring in Albums, permission state in Search, lifecycle video pause in Viewer, `Photos per row` across all grids, and viewer zoom reset on delete.
* [x] **Round `r20` (PR #92 — Build 24)**:
  * [x] `CombinedExifIndexer` single-open, single-parse date + GPS pass.
  * [x] Room v5→v6 (`6.json`) `provenanceIndexedAtMillis` column + startup `ProvenanceHealingPass`.
  * [x] `AndroidVolumeMonitor` (`ACTION_MEDIA_MOUNTED` hot-plug observer registration + rescan).
  * [x] `VaultStore.sealBatch` single-index-write batch move to vault.

### Phase 5 Tasks — Ente On-Device AI Port & Organisational UX (`r21`–`r29`, Issue `#94`)
* [x] **Round `r21` (PR #93 — Build 25)**:
  * [x] Vendor Ente Rust ML workspace (`crates/ente-ml`, `crates/ente-assets`, `crates/ente-image`, `clippy.toml`, `Cargo.lock`) pinned @ `423b2d80d9ec89d45f5680cb1e1d777bac225f41` (`AGPL-3.0`, proven byte-identical across 85 files).
  * [x] Author Kotlin `ModelManifest.kt` (all 11 assets matching `assets.rs`), `ModelVerifier.kt`, `ModelProvisioner.kt`, **ADR-0020**, `engine/NOTICE.md`, `docs/phase-5/ente-pin.md`, and CI `engine` cargo job (837 JVM tests).
* [x] **Issue #94 & Round `r22` (PR #95 — Build 26)**:
  * [x] Authorize `android.permission.INTERNET` in `apps/gallery/internet-permission-allowlist.txt` under ADR-0020 + tracking Issue `#94` (open as permanent tracking record for the allowlist entry).
  * [x] Build `HttpModelDownloader` (allowlisted `https://models.ente.com/`, fail-closed redirects, zero new deps) + JVM/instrumented egress gate assertions.
  * [x] Build `engine/crates/toad-ml-jni` crate + `scripts/build-rust-android.sh` (pinning NDK `28.1.13356709` + Microsoft `onnxruntime-android:1.31.0` AAR, 16 KB page-aligned) + `-PwithEngine` Gradle packaging + `engine-android` CI job + Settings `"AI engine (r22 dogfood)"` screen (855 JVM tests).
* [x] **Round `r23` (PR #96 — Build 27 + Test APK Delivered)**:
  * [x] Vendor `engine/crates/ente-vecdb` (324 upstream tests) and `engine/crates/ente-photos-ml` (`db`, `ml_db`, `ml_store`, `face_clustering` with bundled `rusqlite`, 202 upstream tests).
  * [x] Extend `toad-ml-jni` to 10 JNI functions (`nativeDetectFaceEmbeddingsJpeg` + 5 face-index SQLite/clustering functions).
  * [x] Build `IndexCoordinator.kt` + Settings `"Index + cluster (r23 dogfood)"` card (indexes 12 recent JPEGs, runs face detection + embedding + clustering at threshold `0.24`).
  * [x] Build and deliver `download/toadGALLERY-0.4.2-alpha-r23-engine-debug.apk` (71 MB, `versionCode 27`, `arm64-v8a` with `libtoad_ml_jni.so` + `libonnxruntime.so`).
* [x] **Round `r24` (Build 28) — The Background Queue Round (Workstream 5)**:
  * [x] Extend `toad-ml-jni` from 10 to 12 JNI functions: `nativeStoreIndexedFaceFileIds` (upstream `MlDb::face_indexed_file_ids` at the pin's `FACE_ML_VERSION`) + `nativeStoreDeleteFacesForFiles` (upstream's chunked, transactional `delete_face_index_for_files`) — zero new SQL authored; 12 JNI store tests.
  * [x] Build the pure queue core: `FaceIndexQueuePlanner` (`MediaRevision.diff` → skip / re-index-delete-first / orphan-cleanup mapping), `FaceIndexQueueProcessor` (bounded 50-photo batches, whole-photo cancellation, `NonCancellable` state persistence, once-per-drain clustering) and the atomic `FaceIndexStateFile` inside the face-index directory; 885 JVM tests total (+30).
  * [x] Add `androidx.work:work-runtime-ktx` 2.10.1 (Apache-2.0, license-screened) + `FaceIndexWorker` (CoroutineWorker, unique-name chain with self-re-enqueue, retry/backoff ≤ 3, honest failure shapes) + `FaceIndexScheduler` (`getWorkInfosForUniqueWorkFlow` observation).
  * [x] Trim WorkManager's merged-manifest injections via two `tools:node="remove"` markers (`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` declined; `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` accepted + documented) with the privacy suite pinning both directions; exclude the `face-index` directory from cloud backup and device transfer.
  * [x] Replace the Settings r23 card with the r24 queue card: start / cancel / reset, live per-batch progress, terminal outcome lines, and the clustering-threshold tuning control (0.10–0.40, upstream 0.24 default, persisted).
  * [x] Author `docs/phase-5/background-queue.md` + the privacy-gate r24 note + the dependency-license row; `versionCode 28`, no device build per the Phase-5-completion directive. Squash-merged as **PR #97** (`d610123`).
* [x] **Round `r25` (Build 29) — The People UI Round (Workstream 6)**:
  * [x] Extend `toad-ml-jni` from 12 to 16 JNI functions: `nativeStoreFaceClusters` (live `face_clusters` table + per-cluster cover file id), `nativeStoreClusterFileIds`, `nativeStoreMergeFaceClusters` (upstream `force_update_cluster_ids`; self-merge rejected as `IllegalArgumentException` on both sides of the bridge) and `nativeStoreRemoveFacesFromCluster` (the split primitive, upstream `remove_face_id_to_cluster_id`) — zero new SQL authored; 20 JNI-crate Rust tests (8 new).
  * [x] Build the Kotlin `ml/people` domain: `PeopleEngineSeam` (+ availability), the atomic `people-state.json` names/hidden store in the face-index directory (corrupt-recovers, prunes stale ids), the pure six-state `PeopleContentPolicy`, the no-cache `PeopleRepository`, `PeopleEngineAdapter` and the chunked 999-limit `PeopleMediaQuery`; 927 JVM tests total (+42).
  * [x] Build the Compose surfaces: `PeopleScreen` grid, `PersonDetailScreen` (rename / merge-with / remove-from-person / hide, viewer hand-offs the Album-detail way), the Albums-tab People section with honest off/engine-missing/not-indexed cards, and the Settings `peopleAlbumEnabled` consent row (ADR-0010 opt-in, OFF by default).
  * [x] Author `docs/phase-5/people-ui.md` + the `PrivacyPolicyTest` non-host-literal addition (`people-state.json`); `versionCode 29`, no device build per the Phase-5-completion directive.
* [x] **Round `r26` (Build 30) — The Magic Search Round (Workstream 7)**:
  * [x] Extend `toad-ml-jni` from 16 to 25 JNI functions (Agent A's engine half): `nativeInitSemantic` (the CLIP half of an engine session, a third open/closed state), the CLIP encoders (`nativeClipImageEmbeddingJpeg` / `nativeClipTextEmbedding`) and the CLIP store surface over the SAME `ml-index.sqlite` (put with the 512-dim guard on both sides of the bridge, delete-for-files, indexed-file-ids, count, top-K vecdb search preserving the store's own order, the once-per-drain `fillClipIndex` reconciliation report); 32 JNI-crate Rust tests (12 new).
  * [x] Build the semantic background queue as the r24 face queue's sibling: `ClipIndexQueue` (same three truths, same `MediaRevision.diff` mapping — delete-first kept even though `putClip` upserts; ONE whole-photo embedding per photo; whole-photo cancellation with `NonCancellable` persistence; fill exactly once at drain; the atomic `clip-index-state.json` in the backup-excluded face-index directory) + `ClipIndexWorker` (its own unique WorkManager chain, honest FACE-then-SEMANTIC session gates, self-re-enqueue, bounded retries).
  * [x] Build the Kotlin `ml/search` domain: `MagicSearchEngineSeam` (+ the `PeopleEngineAdapter`-pattern adapter extended with `initSemantic`), the pure six-state `MagicSearchContentPolicy`, the 2–200-character query gate, the order-preserving/dedupe/limit ranking, the no-cache `MagicSearchRepository` and the chunked 999-limit `MagicSearchMediaQuery`; 982 JVM tests total (+55).
  * [x] Build the Compose surfaces: the Search tab's "Search by" mode row (Name untouched vs Content), the debounced sibling `MagicSearchViewModel` with the policy's honest gating cards (consent / engine-missing / models ~213 MB / not-indexed, each with its own recovery affordance), results on the shared grid in the vecdb's own relevance order; plus the Settings `magicSearchEnabled` consent row (ADR-0010 opt-in, OFF by default), the SEMANTIC model download card and the semantic queue card (start/cancel/reset with the honest reset scope).
  * [x] Author `docs/phase-5/magic-search.md` + the `PrivacyPolicyTest` non-host-literal addition (`clip-index-state.json`; no backup-rule change needed — the state file rides the r24 face-index exclusion); `versionCode 30`, no device build per the Phase-5-completion directive.

## 3. Remaining / Queued Tasks & Next Steps (Post-`r26`)

### Immediate Device Validation & Phase 5 AI Workstreams (`r27`–`r29`)
* [ ] **On-Device Dogfood Validation (engine APK at build 30; build 27 APK already delivered as `toadGALLERY-0.4.2-alpha-r23-engine-debug.apk`)**:
  * [ ] Install an engine-enabled build on the Xiaomi test device (`2201116PI`, Android 36).
  * [ ] Open `Settings → AI engine (r22 dogfood)` and download the FACE model pack (~36 MiB from `models.ente.com`).
  * [ ] Open `Settings → Start background index (r24 dogfood)`, let the queue drain the library, verify live progress + the drain outcome line, exercise Cancel (resume) and Reset, and evaluate the clustering threshold through the r24 tuning control on a real library.
  * [ ] Dogfood the r25/r26 People surfaces on-device: enable `Settings → People albums`, browse the People grid + person details, rename/merge/remove-from-person/hide, unhide from the People screen's hidden section, and verify the honest off/engine-missing/not-indexed states.
  * [ ] Dogfood the r26 Magic Search surfaces on-device: enable `Settings → Magic search`, download the SEMANTIC pack (~213 MB), start the semantic index, then search the Content mode with natural-language queries and verify the honest consent/engine/models/not-indexed states.
  * [ ] Run pending device validation checklist sections (`docs/phase-3/device-validation-checklist.md` §§13–18, including Room v4→v6 over-install migrations and pre-Q fallback check).
* [x] **People UI Follow-ups (deferred from r25, documented in `docs/phase-5/people-ui.md`; shipped as the `r26` round, branch `tg-r26-people-followups`)**:
  * [x] Edit-preserving reclustering (cluster-id remapping overlay so names/hidden/merges survive the queue's recluster passes) — the additive `people-state.json` books (membership fingerprints, merge intents, removal records), the pure `PeopleReclusterRemap` planner and the repository's reconcile-on-read-and-edit execution, all Kotlin-side over the existing 16-external JNI surface (the vendored crates stay byte-identical to the r23 pin).
  * [x] Unhide UI for hidden persons (r25 way back is the r24 Reset face index) — the People screen's hidden section (`PeopleContentPolicy.hiddenPersons`) with a per-person Unhide action, no queue reset; the r25 reset-path copy re-worded in place.
* [ ] **Magic Search Follow-ups (deferred from r26, documented in `docs/phase-5/magic-search.md`)**:
  * [x] MobileCLIP image embedding indexing (`mobileclip_s2_image_gelu_opset20.onnx`), BPE tokenizer (`bpe_simple_vocab_16e6.txt`), text encoder (`mobileclip_s2_text_opset18_quant.onnx`) and offline semantic search in the Search tab — DONE in r26 over `ente-vecdb` (the r26 JNI surface + queue + domain + UI above).
  * [ ] Semantic-only engine sessions (drop the FACE-model requirement the r26 session carries).
  * [ ] Things dynamic collections + smart-filter integration (named semantic clusters over the SAME CLIP index, image-side queries).
* [x] **MI-Style Organisational UX (Roadmap Phase 5 / Reconciled Phase 6)**:
  * [x] Places (offline coarse region bucketing over Room v4 GPS coordinates, zero reverse-geocoding network calls), Moments timeline, Stories, collage creator, and slideshow. (r27, PR #103: the pure `PlacesRegionGrid`/`PlacesRegionLabels`/`PlacesContentPolicy` + Places screens and the Albums-tab section; the pure `MomentGrouper` with the WEEK grain + counted headers; the pure `StorySets` + Stories shelf; the pure `CollageLayouts` + collage creator with the Q+ exporter; the pure `SlideshowTiming`/`ViewerCrossfade` + the viewer slideshow. 84 new JVM tests; `versionCode 32`.)
* [ ] **Phase 5 Hardening & Exit Gate (Workstream 8)**:
  * [ ] Egress tests proving `models.ente.com` is the sole network path, three-gate Vault exclusion tests (at enqueue, pre-byte-read, pre-persistence), 10k/50k library benchmarks, low-memory/ABI matrix verification, and optional offline model-pack CI release build → lifts Phase 5 release freeze.

### Downstream Roadmap Phases (Phases 6–11)
* [x] **Issue #14 (Phase 6 Gate)**: Implement the Gallery ↔ Edit URI contract prototype (`docs/phase-1/contract-prototype-spec.md`) before starting Phase 6 — **DONE in r29** (PR #104; `docs/phase-6/contract-gate.md`): the `:shared:editor-contract` pure-JVM module (identity match, version negotiation, request validation, result parsing — one truth for both apps), the `:apps:edit` toadGALLERY Edit prototype APK (`io.github.dukeoftoad.gallery.edit`, versionCode 1, zero network with its own merged-manifest INTERNET gate, non-destructive MediaStore-INSERT save-copy export with the EXIF metadata policy, three terminal states, manifest-conformance suite), and the gallery half (exact-match editor resolution, version refusal, third-party fallback, the viewer Edit op, `<queries>`; refresh via MediaStore observation, no rescan). The physical round trip + original-untouched hash compare join the device checklist (§19).
* [ ] **Phase 6 remainder (Edit v1)**:
  * [x] **The r30 core round** (branch `tg-p6-edit-v1-core`, stacked on the r29 gate): the ADR-0011 `EditRecipe` + codec + store (save-on-commit, restore-on-relaunch, clear-on-export), the geometry toolset (crop rect + aspect presets, flips, straighten expanded-canvas, quad perspective), the full adjustment suite (composed LUT: exposure/brightness/contrast/highlights/shadows; pixel stages: saturation/temperature/tint; 8-band HSL; vignette; 3×3 unsharp), `EditPipeline` + `EditGeometryPlan`, honest export control (JPEG/PNG/WebP — NO HEIC, the Bitmap has no HEIC encoder) and the full editor surface. Editor `versionCode 2`.
  * [x] The remaining Phase 6 exit items — **CODE-SIDE COMPLETE in r31** (branch `tg-p6-edit-v1-exit`, stacked; editor `versionCode 3`): the per-export metadata policy CHOICE (the launch request's policy as the privacy FLOOR per contract §4.4 — the export sheet's chip selector may strip more, never less; pure `ExportPolicyChoice` + the honesty line tracking the pending choice + the recipe/sidecar independence pinned by test), the export preflight (the 48 MP large-file decode guard, the 1.5×-headroom storage check over `StorageManager` riding `ERROR_IO` so the frozen v1 contract surface stays untouched, the documented process-death policy), and the applied-policy honesty narration (additive `SavedCopy.removesLocation`/`stripsAllMetadata` contract triggers; the gallery's saved-copy snackbar narrates "location removed" / "metadata stripped"). The DEVICE half remains human-only: the §19 round trip + the new §20 export robustness matrix (policy×GPS verification, the guard, low storage, process death on Q+ and pre-Q, distinct failure narrations) — the end-to-end Gallery→Edit→Gallery matrix on a physical device.
  * [x] The Phase 6 hardening pass — **r32** (same branch `tg-p6-edit-v1-exit`, riding PR #106; editor `versionCode 4`, `0.3.1-edit-alpha`): the accessibility sweep over the whole editor surface (every slider — the 10 adjustments, straighten, export quality, the three HSL band controls — announces its name + live value via `semantics` contentDescription/stateDescription; the HSL rows speak "BAND: CONTROL" so the selected band is always spoken; the flip toggles are proper FilterChips with 48dp targets and selected-state announcements), the straighten live-drag fix (the thumb and preview now follow the drag like every other slider, dropping the wasted per-tick re-render), and the tested pure `CropRect.fromPixelRect` inverse replacing the crop-preset UI's inline division (5 new JVM tests, GeometryTest 19 → 24). The on-device TalkBack pass rides the new checklist §21.
* [ ] **Phase 7 (Edit v2)** — OPENED in r33:
  * [x] **The r33 LUT filter system** (branch `tg-p7-lut-engine`, stacked on `tg-p6-edit-v1-exit`; editor `versionCode 5`, `0.4.0-edit-alpha`): the pure `.cube` engine (the parser with its honest subset rules — `LUT_1D_SIZE` refused, `RANGE_*` accepted-and-ignored, degenerate domains rejected, N³ finite R-fastest triplets; the allocation-free trilinear sampler; the alpha-preserving intensity blend), the recipe's additive `LutSelection` (absent key = every pre-r33 sidecar loads clean; an unresolvable id round-trips verbatim and degrades to no-look at apply time — the tolerance contract), pipeline stage 10 between the HSL bands and the spatial effects, five PROJECT-AUTHORED bundled looks from the committed deterministic generator (`tools/gen_bundled_luts.py`; the licensing record `docs/phase-7/bundled-luts.md`; 17³ grids ≈ 650 KB), the `BundledLuts` registry + stream-injected loader, and the Filters chip row + intensity slider (r32 a11y conventions) over the ONE shared look cache so preview ≡ export. 41 new JVM tests (edit module 158 → 199); the on-device pass rides checklist §22.
  * [x] **The r34 user-import `.cube` flow** (branch `tg-p7-lut-import`, stacked on `tg-p7-lut-engine`; editor `versionCode 6`, `0.5.0-edit-alpha`): the SAF document picker (no permission, no manifest entry) over the wave-7 store's parse-BEFORE-write import (a rejected import leaves the store byte-identical), the ONE merged session registry (pure `LookRegistry.merge` --- bundled first, imports id-sorted, the defensive bundled-precedence pin, unmodifiable publication) at BOTH resolve sites so preview ≡ export including user looks, the user-look chips carrying their parsed titles, the remove affordance (the sidecar keeps the id and degrades to no-look per the tolerance contract), and the inline import/remove outcome line (polite live region). 8 new JVM tests (edit module 199 → 207); the on-device pass rides checklist §23.
  * [ ] Then the text tool (OFL fonts), stickers, freehand drawing, mosaic/blur regions, frames — each a round; undo/redo extended across the new layer types.
* [ ] **Phase 8–11**: `Edit v3` (on-device AI magic erase, sky replacement, bokeh, opt-in face retouch), Phase 9 lightweight video editor, Phase 10 performance/accessibility hardening, and Phase 11 v1.0 release readiness.
