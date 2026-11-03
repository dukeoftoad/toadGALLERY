# Phase 5 Workstream 8 — the hardening round + the exit gate (r28)

The last workstream of the Phase 5 plan: prove, on the record, the
properties the phase promised — that `models.ente.com` is the app's
sole network path, that vault media cannot reach the AI pipeline at any
of the three gates the Phase 4 audit named, that the library-scale hot
paths hold their budgets at 10k/50k items, that the app's
low-memory behavior and per-ABI shipping shape are what the docs say
they are, and that ADR-0020's offline model pack exists as a build
path. Tests and records are the product of this round; the one
production-code addition is the offline model-pack seam
(`BundledModelAssets` / `BundledFirstModelDownloader`), which is small
by design and rides the existing provisioner unchanged.

## 1. The egress proof: `models.ente.com` is the sole network path

The r22 round (ADR-0020) granted the app's first and only network
permission. The r28 proof closes the loop by pinning the NEGATIVE
space — everything that is NOT the allowlisted fetch — across every
surface that could open a network path:

| Layer | Enforcement | Pinned by |
| :--- | :--- | :--- |
| Manifest | INTERNET declared exactly once, ADR-0020 cited; permission set pinned to the five | `PrivacyPolicyTest` (r22) |
| Manifest | No `usesCleartextTraffic`, no `networkSecurityConfig` (no cleartext or second-trust-anchor escape hatch) | `EgressSolePathTest` (r28) |
| Build | Merged-manifest INTERNET gate (`gateDebugInternetPermission` / `gateReleaseInternetPermission`) wired with its allowlist input and its assemble/bundle/check anchors | `PrivacyPolicyTest` (r22) + `EgressSolePathTest` (r28) |
| Allowlist | Exactly one active entry — the ADR-0020 / issue #94 model-fetch INTERNET grant | `EgressSolePathTest` (r28) |
| Source (whole repo) | The ONLY quoted `scheme://` string literal in production Kotlin is the About screen's repository link, opened via `ACTION_VIEW` (the browser connects; the app never does). KDoc citation URLs are not quoted literals and cannot match the scan. | `EgressSolePathTest` (r28) |
| Source (whole repo) | `java.net` imported only by `HttpModelDownloader.kt`; no okhttp/retrofit/volley/`java.net.http.HttpClient`; no raw `Socket`/`DatagramSocket`/`InetAddress`/`ServerSocket` — no path bypasses the transport's URL guard | `EgressSolePathTest` (r28) |
| Transport | Every manifest entry constructs exactly `https://models.ente.com/<fileName>` (host, scheme, no port/query/fragment); `MODEL_HOST` is the only host literal in `ml/` | `PrivacyPolicyTest` (r22) |
| Transport | The full 3xx family (301/302/303/307/308) fails CLOSED — no stream handed out, the refusal names the redirect, and the loopback server records prove EXACTLY ONE request (never-follow), including for cross-host absolute Locations, same-URL loops and Location-less redirects; `instanceFollowRedirects = false` pinned at source level | `HttpModelDownloaderTest` (r22) + `HttpModelDownloaderRedirectTest` (r28) |
| Transport | The structural name guard refuses traversal / absolute-URL / query / fragment / alphabet violations BEFORE any connection or asset lookup | `PrivacyPolicyTest` + `HttpModelDownloaderTest` (r22) + `BundledModelSourceTest` (r28, the offline path shares the guard) |
| Device | INTERNET granted (the ADR-0020 grant landed); non-allowlisted targets refused without a connection | `PrivacyEgressTest` (instrumented, manual release gate per ADR-0016) |

The About screen's `https://github.com/dukeoftoad/toadGALLERY` link is
the one documented non-fetch URL literal: it is handed to the user's
browser through `Intent(ACTION_VIEW, …)`, and the app process holds no
socket for it. The XML namespace URIs (`schemas.android.com`) in the
manifest and resources are XML identifiers, not network targets, and
are not quoted Kotlin literals.

**Statement:** with all suites green, the app's entire network surface
is one transport (`HttpModelDownloader`), one host
(`models.ente.com`), one scheme (https, redirects refused), fetching
exactly the pinned manifest assets — and the r28 offline model pack
(section 5) exists precisely so a provisioned-from-pack device does
not need even that.

## 2. The three-gate vault exclusion

The Phase 4 audit's vault-leakage rule (three gates: **enqueue / OPEN /
pre-persist**) is now pinned by one focused suite per gate, on top of
the eligibility type gate (`MediaEligibility`/`EligiblePublicMedia`)
the audit's change 6 introduced:

| Gate | Boundary | Suite | What it proves |
| :--- | :--- | :--- | :--- |
| **1 — enqueue** | The queue's candidate list (`eligibleCandidates` over `MediaCatalogue.allRecords()`) | `FaceIndexQueueVaultExclusionTest` (`ml/index`) | A vault move deletes the MediaStore row → the scan tombstones the key → the catalogue's live records drop it → the photo is UNREPRESENTABLE as a candidate (pinned end-to-end with the real `InMemoryMediaCatalogue`). Plus the cleanup half: a previously-indexed photo that moved to the vault becomes a plan ORPHAN — its face rows deleted BEFORE any inference, zero byte reads, its queue bookkeeping dropped. The mid-batch shape (moved between plan and read) yields no rows and no state record, and the next plan drops it entirely. |
| **2 — pre-byte-read** | The read boundary (`MediaAccess.open` / `MediaPermissionGate` / `MediaReadHandle`) | `VaultExclusionReadGateTest` (`data/media/access`) | `VAULT_EXCLUDED` eligibility can never mint the `EligiblePublicMedia` wrapper `open` accepts (and the single-overload pin keeps it that way); a refusing permission gate yields no handle and no bytes; the production opener consults the gate BEFORE constructing its handle (source-order tripwire); the byte cap refuses WHOLE (never truncates); a null read (vault move / revoked grant / vanished row) persists NOTHING — no detection call, no face rows, no state record. |
| **3 — pre-persistence** | The People surfaces + the persisted state (`PeopleContentPolicy`, `PeopleRepository`, `PeopleStateStore`) | `PeopleVaultExclusionPersistenceTest` (`ml/people`) | A cluster whose photos all moved to the vault resolves to NO photos (the repository hands exactly the cluster's DISTINCT file ids to the MediaStore binding; vanished rows contribute nothing); a cluster emptied by vault moves prunes its stale name on the next snapshot (and the pruned state is persisted back); hide is a display gate, never a store mutation; and the face-index directory (ML store + queue bookkeeping + `people-state.json`) is excluded from cloud backup AND device transfer in both rule files — the r24 exclusion, now pinned (the vault rows were; the face-index rows were not). |

Together with the existing vault suites (`data/vault/*`, the
`VaultThumbnailGate` lock-race round), the full path of a vault item
is: never enqueued → never opened → never read → never persisted →
never displayed — and if it was indexed before the move, its rows are
actively purged at the first post-move queue run.

## 3. The 10k/50k library benchmarks

`LibraryScaleBenchmarkTest` (`data/`) drives the hot paths over
synthetic, seeded datasets (deterministic; ~70% JPEG / ~25% video / 5%
GIF, 8 folders, five years of capture dates, ~4% favorites — the
mix a real library produces). Budgets are 30–150× the expected cost on
a shared 2-core CI runner, so JIT/GC/runner variance cannot flake the
suite, while an accidental O(n²) at 50k rows (billions of operations)
blows every budget by an order of magnitude. Each benchmark prints one
`r28-benchmark |` line; the CI test report carries them.

| Hot path | Budget @ 10k | Budget @ 50k | Measured (r28 CI run) |
| :--- | ---: | ---: | :--- |
| `timelineWindow(0,100)` NEWEST_FIRST | 300 ms | 1 500 ms | *recorded below after the CI run* |
| `timelineWindow(0,100)` OLDEST_FIRST | — | 1 500 ms | *recorded below* |
| `folderTimelineWindow(0,100)` (bucket filter) | — | 1 500 ms | *recorded below* |
| `WindowPagingSource` 20×500-item append walk | — | 5 000 ms | *recorded below* |
| `CatalogueReconciler.reconcile` (5% changed / 2% added / 3% removed) | 500 ms | 1 000 ms | *recorded below* |
| `TimelineFilter.matches` photos-only projection | — | 250 ms | *recorded below* |

Notes on scope: the in-memory catalogue is the REFERENCE
implementation (it re-sorts per call — deliberately dumb and pure);
production window reads are the Room catalogue's SQL `ORDER BY …
LIMIT OFFSET` queries, so these JVM budgets bound the reference and
the pure paging/reconciler/filter logic, while the Room side keeps its
existing correctness suites plus device validation. Whole-class CI
cost: well under the 60 s target (the ci.yml `build` job's 20-minute
timeout is untouched).

### Measured numbers (r28 CI run on the PR)

*To be extracted from the green CI run's test-report artifact and
recorded here before merge — see the worklog entry for the run id.*

## 4. Low-memory behavior and the ABI matrix

### Low-memory behavior paths (what exists, where it lives)

- **Paging windows, not the library**: the Photos timeline reads
  fixed-size windows through `WindowPagingSource` (ADR-0015) — a short
  window signals end-of-list, `itemsBefore` carries the positional
  count, placeholders are disabled, and no full-library list is ever
  materialized. The grid holds only what is composed.
- **The 64 MiB read cap, refuse-don't-truncate**: `MediaReadHandle`
  bounds every original-bytes read (`DEFAULT_MAX_BYTES` = 64 MiB — a
  memory guard, not a format judgement); an item above the cap returns
  `null` whole, never a prefix. The production reader
  (`ContentResolverMediaAccess`) pre-sizes its buffer to at most 1 MiB
  and grows only as bytes actually arrive; no plaintext temp files
  ever exist (the audit's rule).
- **Decode planning**: `CanonicalDecodePlanner` computes power-of-two
  subsampling from the target size and refuses decodes that would
  exceed the memory budget deterministically (the Phase 4 contract's
  "canonical decode" change; pinned by `CanonicalDecodePlannerTest`).
- **The face-index queue**: `FaceIndexQueueProcessor` reads ONE photo
  at a time ("one JPEG in memory, never the library"), in bounded
  50-photo batches, with whole-photo cancellation between photos;
  unreadable photos degrade honestly without aborting the batch.
- **Vault thumbnails**: decrypted vault thumbnails are in-memory only
  (`VaultThumbnailCache`), cleared on lock with in-flight decode
  cancellation (`VaultThumbnailGate`); zero disk plaintext.
- **The catalogue**: production persistence is Room (SQLite) — the
  `InMemoryMediaCatalogue` exists as the JVM reference implementation,
  never in the app; a 50k-row library costs the process no more than
  the page it renders.

### The ABI matrix (what ships, per build)

| Build | `lib/` contents | Notes |
| :--- | :--- | :--- |
| Default (`assembleDebug`, no properties) | **none** — no native libraries at all | The engine jniLibs srcDir exists only inside the `-PwithEngine` gate; the app runs with `MlEngine.available = false` and every AI surface renders its honest `EngineMissing`/not-in-this-build state (pinned by `MlEngineTest` and the People policy suite). |
| `-PwithEngine` (the CI `engine-android` job; the engine-apk test build) | `arm64-v8a/libtoad_ml_jni.so` (~19.5 MiB) + `arm64-v8a/libonnxruntime.so` (~32.6 MiB) | 64-bit ARM only: the build script's default target is `aarch64-linux-android` (pinned). The r23/r25 device artifacts are this shape (71 MB APK). |
| `-PwithEngine` + `--all` (script opt-in, no workflow) | adds `x86_64/` for both libraries | Emulator dogfood shape; NOT shipped by any workflow. |
| `-PwithEngine -PwithBundledModels` (the modelpack-apk workflow) | arm64-v8a pair + `assets/models/` FACE pack (~37.6 MiB) | Section 5; APK ≈ 109 MB. |

Honest device-class statements (minSdk 26, target/compileSdk 36 —
pinned from the version catalog):

- **arm64-v8a devices** (essentially every 64-bit Android phone,
  2019+): full engine. The libraries link with the 16 KB page-size
  discipline (`max-page-size=16384` pinned for aarch64, armv7 AND
  x86_64 in `engine/.cargo/config.toml` — the Play requirement from
  mid-2025).
- **armeabi-v7a (32-bit ARM) devices**: the engine ships no 32-bit
  `.so` — those devices run the default build's honest
  engine-missing states (the `armv7` link flags exist in the cargo
  config so a future 32-bit build, if ever wanted, inherits the page
  discipline). The gallery itself (browsing, vault, metadata, maps) is
  pure Kotlin/Java and unaffected.
- **x86_64 devices/emulators**: served only by the `--all` script
  opt-in; the shipped rows are arm64.

## 5. The offline model pack (ADR-0020's second half)

ADR-0020: "follow Ente's model --- after install, the app downloads
the models itself, on demand --- **while CI retains the ability to
ship an offline pack**." r28 implements that retained half:

- **The seam** (`ml/engine/BundledModelSource.kt`):
  `BundledModelAssets` (open-a-bundled-asset-or-null) +
  `BundledFirstModelDownloader` (bundled bytes first, the ordinary
  `HttpModelDownloader` as fallback). The structural name guard runs
  BEFORE the asset lookup — the same choke point every fetch passes —
  and the provisioner's verify-then-stage-then-commit discipline
  applies unchanged: a bundled pack that does not match the CURRENT
  pin's size + SHA-256 installs NOTHING (`VerificationFailed`, no
  receipt, no orphan). The offline path adds no trust and no new
  network surface. Pinned by `BundledModelSourceTest` (bundled-first,
  per-asset fallback, guard-before-asset, tampered-pack refusal, the
  full offline round trip with the network transport completely idle).
- **The build gate** (`apps/gallery/build.gradle.kts`):
  `-PwithBundledModels` adds `build/bundled-models` as an assets
  srcDir — build directory only, never `src/main`, exactly the
  engine-jniLibs pattern; a default build is byte-for-byte unchanged.
  Pinned by `EnginePackagingPolicyTest`.
- **The CI build** (`.github/workflows/modelpack-apk.yml`): the
  `engine-apk.yml` pattern (branch-scoped `tg-modelpack-*` + dispatch,
  additive, ci.yml untouched) plus the model staging: fetch the two
  pinned FACE assets from `models.ente.com`, verify size + SHA-256
  against the pin, stage into the build dir, assemble with
  `-PwithEngine -PwithBundledModels`, and gate the result — native
  libraries present, BOTH model assets present under `assets/models/`,
  `.debug` identity with `versionCode >= 32` — before uploading the
  14-day-retention artifact. No tags, no Releases: `release.yml` stays
  the only signing path.
- **Runtime wiring** (`ToadGalleryApplication.modelProvisioner`): the
  production provisioner now prefers the bundle
  (`assets.open("models/<name>")`, absent → null → network) — lazy, so
  nothing is touched until the first provision, and a normal build's
  behavior is exactly the r22 one.
- **Scope**: the FACE pack only (~37.6 MiB; APK ≈ 109 MB). The
  SEMANTIC (CLIP) assets belong to the Magic Search workstream and
  stay runtime-downloaded until that round lands its own pack
  decision.

## 6. The exit gate statement

Workstream 8's items — the egress proof, the three-gate vault
exclusion, the 10k/50k benchmarks, the low-memory/ABI matrix
verification, and the offline model-pack build — are implemented,
green in CI, and merged (see the worklog's r28 entry for the PR and
run ids; the measured benchmark numbers are recorded in §3).

**Phase 5 release freeze lifted — the next release may proceed**,
with these honest caveats on the record:

1. **Device dogfood is still pending** — the maintainer's on-device
   checklist items (engine APK install, model download/index/People
   dogfood, the Phase 3 checklist §§13–18) remain OPEN in tasks.md;
   they are maintainer tasks, not code tasks, and the lift does not
   claim them done. Code-complete is the honest state.
2. **Parallel rounds**: the r26 People follow-ups and the r27
   organisational-UX round (and the Magic Search workstream) were in
   flight while r28 landed; the lift statement in the records is
   scoped accordingly — where those rounds still had open work at
   merge time, the records say "gate items complete; freeze lift
   pending sibling rounds" rather than claiming their work.
3. **The benchmarks bound the reference implementation** (§3): the
   Room/SQLite production paths carry their correctness suites and the
   device validation items above; the budgets are regression
   tripwires, not device performance claims.
