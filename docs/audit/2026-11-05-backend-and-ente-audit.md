# Backend & Ente-Engine Audit (r50 round)

**Date:** 2026-11-05
**Audited tree:** `main` @ `7e9114b` (r49: "the workflow organization round")
**Method:** static review only (no device runs, no cargo/gradle execution). Six parallel audit tracks over the backend surfaces: the vendored Ente Rust engine (`engine/`), the Kotlin ML pipeline, the vault + file-ops layer, the Room catalogue / scan pipeline, and the CI / build / privacy / licensing infrastructure. Every finding below cites `file:line` evidence read from the audited tree.
**Scope of this document:** bugs, flaws, and refinements. No code changes are proposed here — this is the work-order document; fixes land as their own commits.

---

## 1. Executive summary

**79 unique findings: 0 critical, 7 high, 31 medium, 41 low** (one duplicate report across tracks deduplicated: the provenance-healing starvation flagged independently by tracks D and E is listed once as E-3).

The strongest surfaces: the model-download path (`HttpModelDownloader` + `ModelProvisioner` + `ModelManifest` — single-host HTTPS, fail-closed redirects, streaming SHA-256 gating an atomic verify→stage→commit install, manifest literals verified byte-identical against the vendored Rust catalog), the vault crypto design (AES-256-GCM, provider-random IVs, non-exportable Keystore keys, enrollment invalidation, crash-orderings, orphan sweep), the `ente-vecdb` persistence stack (CRC-verified on load, fsync discipline, atomic snapshot swaps, flock with inode revalidation), and the workflow least-privilege posture (explicit `permissions:` everywhere, no `pull_request_target`, sha256-pinned model fetches).

The weakest surfaces: **concurrency around the process-global JNI engine** (per-instance `MlEngine` state + UI-side teardown racing the index queues — the root shape of three findings), **two silent data-loss / wedge shapes in the scan pipeline** (null-cursor mass tombstone, >999-key `IN` DELETE on API ≤ 30), **the JNI bridge's two `put*` exports that can unwind across FFI**, and **supply-chain hygiene in CI** (mutable-tag action pins in the signing pipeline, unverified Gradle wrapper).

### The 7 high-severity findings, ranked by suggested fix order

| # | ID | Title | Area |
| --- | --- | --- | --- |
| 1 | C-1 | Model-pack workflow pins a **corrupted SHA-256** for `mobilefacenet_portable_static_b1.onnx` — the offline modelpack build can never succeed | CI |
| 2 | E-1 | Null MediaStore cursor reconciles as "empty volume" → **mass tombstone** + checkpoint stamped over a failed walk | Data |
| 3 | E-2 | `IN (:keys)` DELETEs exceed SQLite's 999 host-parameter limit on API ≤ 30 → scan pipeline **permanently wedged** after mass changes | Data |
| 4 | B-1 | `nativeStorePutFace`/`nativeStorePutClip` are the only JNI exports **without `catch_unwind`** — a panic aborts the process | Engine/JNI |
| 5 | C-2 | UI surfaces tear down the process-global native engine **while the background index queues run** | ML |
| 6 | C-3 | Settings "Reset face index" **races** the queue's `NonCancellable` state save — stale bookkeeping resurrects against a deleted store | ML |
| 7 | E-3 | Provenance healing pass deterministically **starves on never-stamped rows** (re-reads the same bytes every app start) | Data |

---

## 2. Track A — Vendored Ente crates: `ente-assets`, `ente-image`, `ente-photos-ml`

15 findings (0 high, 3 medium, 12 low). Note: these crates are vendored per the upstream pin (`docs/phase-5/ente-pin.md`, commit `423b2d8`); fixes here must respect the pin policy (adaptation checklist + NOTICE.md update) or be upstreamed.

**A-1 [Medium] [security] No extracted-size or entry-count bound on tar.gz extraction (tar-bomb → disk exhaustion)**
- File: `engine/crates/ente-assets/src/archive.rs` (lines 24–64)
- Evidence: `let mut buffer = vec![0; 1024 * 1024];` then per-entry `output.write_all(&buffer[..count])` with no cumulative budget.
- Problem: Extraction tracks no total byte budget, no entry count, and no per-file cap; a (validly-checksummed) archive can expand until the disk fills. Mitigating factors: the archive's SHA-256 is verified against the manifest before extraction (`download.rs:669–678`), so exploitation requires control of the asset manifest (compromised CDN/config), and staging is removed on failure. Still a missing defense-in-depth bound.
- Fix: Accumulate decompressed bytes across entries and abort with `Error::Validation` above a configured cap (manifest-declared or fixed), and cap entry count.
- Confidence: HIGH

**A-2 [Medium] [performance] Full-frame decode holds up to ~512 MB per image in RAM; no bounded path for JPEG/HEIC/AVIF/JXL**
- File: `engine/crates/ente-image/src/decode.rs` (lines 38–39, 97–118, 192–217)
- Evidence: `const MAX_DECODE_ALLOC_BYTES: u64 = 512 * 1024 * 1024;` and `DynamicImage::from_decoder(decoder)` full materialization; only PNG has a streaming downsampler (`png.rs`).
- Problem: The main decode path materializes the whole image (then `into_rgb8()` plus the ICC transform copy) — up to 512 MB per image, with the full buffer kept alive by `DecodedImage.rgb`. JPEG/HEIC/AVIF/JXL always decode at full resolution even when the caller only needs ML input. Significant memory pressure on Android under concurrent processing.
- Fix: Add bounded/scaled decode for the other formats (JPEG DCT scaling, HEIC downsampling) or lower the alloc cap for the ML pipeline.
- Confidence: HIGH

**A-3 [Medium] [bug] Index vectors are written before SQL rows; SQL failure leaves ghost vectors with no stale marking**
- File: `engine/crates/ente-photos-ml/src/ml_store/writes.rs` (lines 119–139)
- Evidence: `match self.index_write(index, |vecdb| vecdb.bulk_add(...)) { Ok(()) => { write_rows()?; ... } }` — SQL failure happens after the index already has the vectors.
- Problem: In `upsert`, the vector index is updated first and the SQLite rows second. If `write_rows()` fails (disk full, DB error), the index retains vectors for rows that don't exist — searches return keys with no backing data — and no path marks the index stale (contrast: an index failure correctly marks stale and purges; `delete_clip` uses the safer SQL-first ordering). Persists until a forced refill.
- Fix: Write SQL first, then add to the index (index failure marks stale and is self-healing via refill), or wrap so a SQL failure triggers `mark_stale` + purge.
- Confidence: HIGH

**A-4 [Low] [bug] Non-atomic publish: destination deleted before rename, no fsync — crash window loses the asset**
- Files: `engine/crates/ente-assets/src/store.rs` (lines 474–482; 469–471), `download.rs` (lines 675–678)
- Evidence: `remove_path(destination)?; fs::rename(source, destination)?;`
- Problem: The live asset is removed before the staged one is renamed in; no fsync of file data or directory before rename. Crash/power-loss in the window leaves the asset absent or partially durable; recovery costs a re-download. Same pattern in `download_file_single`.
- Fix: fsync staged files before rename; prefer an atomic swap (rename-to-backup + rename-in) or document the accepted re-download cost.
- Confidence: HIGH

**A-5 [Low] [robustness] No overall download deadline; per-read idle timeout allows indefinite trickle**
- File: `engine/crates/ente-assets/src/download.rs` (lines 19–20, 262–268)
- Evidence: `const READ_TIMEOUT: Duration = Duration::from_secs(30);` on `Client::builder().read_timeout(...)`.
- Problem: `read_timeout` bounds a single read, not the transfer; a server that dribbles one byte every ~29 s keeps a model download open indefinitely. Cancellation is user-driven only. `estimated_download_size` also does sequential HEAD probes (minor latency).
- Fix: Track an overall per-file deadline (or minimum-throughput check across `PROGRESS_INTERVAL` windows) and abort with a retryable network error.
- Confidence: HIGH

**A-6 [Low] [security] TOCTOU between checksum verification and install**
- File: `engine/crates/ente-assets/src/download.rs` (lines 669–678)
- Evidence: `check_sha256(&tmp_path, ...)` then later `fs::rename(&tmp_path, destination)?;`
- Problem: SHA-256 is computed on the tmp file, then renamed later; a process/user with write access to the asset directory could swap the file between verify and rename. Local-attacker-only on Android (app-private storage), hence low.
- Fix: Open the tmp file once, keep the handle, hash and rename via the same fd, or verify after rename at the destination path.
- Confidence: MEDIUM

**A-7 [Low] [bug] Windows `write_all_at` uses shared file-pointer `seek_write` from concurrent range tasks**
- File: `engine/crates/ente-assets/src/download.rs` (lines 1590–1607)
- Evidence: `#[cfg(windows)]` path uses `file.seek_write(bytes, offset)` from up to 4 concurrent tasks on the same `&File`.
- Problem: `seek_write` moves the shared file pointer; concurrent calls can race and corrupt downloaded ranges. Android target unaffected (uses positional `write_at`). Latent data-corruption bug if the crate is ever built for Windows.
- Fix: On Windows use a per-write `try_clone()`d handle or `FileExt::write_at` instead of `seek_write`.
- Confidence: MEDIUM

**A-8 [Low] [security] JXL format-detection hook registered with a 2-byte magic**
- File: `engine/crates/ente-image/src/decode.rs` (lines 349–357)
- Evidence: `image::hooks::register_format_detection_hook("jxl".into(), &[0xFF, 0x0A], None);`
- Problem: `FF 0A` is a very weak signature; binaries starting with those bytes get routed to the JXL decoder and fail with confusing errors (no corruption risk).
- Fix: Prefer the 12-byte container magic; if the bare-codestream hook is needed, validate cheaply inside the hook before committing to the decoder.
- Confidence: HIGH

**A-9 [Low] [refinement] `eprintln!` diagnostics in library code instead of the `log` facade**
- Files: `engine/crates/ente-image/src/decode.rs` (133, 197, 467–497), `color_management.rs` (57, 84, 94–97)
- Evidence: `eprintln!("[ml][decode] failed to read embedded ICC profile: {err}");`
- Problem: `ente-image` logs to stderr while `ente-photos-ml` uses the `log` crate; stderr is lost/noisy on device and unfilterable, and error context is flattened into strings in several places.
- Fix: Route through `log::warn!`/`log::error!` for logcat integration.
- Confidence: HIGH

**A-10 [Low] [robustness] Dependency pins: git-pinned heic fork + duplicate moxcms versions**
- Files: `engine/crates/ente-image/Cargo.toml` (7–9), `engine/Cargo.lock` (moxcms 0.8.1 via `image` vs 0.9.1 direct)
- Problem: The HEIC decoder is a git fork pinned to one rev — supply-chain risk mitigated by the pin, but it handles the most hostile input with far less review than a crates.io release. `image 0.25.10` pulls moxcms 0.8.1 while `ente-image` uses 0.9.1 — two color-management stacks shipped. Largely an upstream-Ente design choice inherited by the pin.
- Fix: Track the fork for upstream-able fixes; align the direct moxcms version with image's.
- Confidence: HIGH (facts); risk MEDIUM

**A-11 [Low] [bug] Negative `user_version` panics during migration instead of erroring**
- File: `engine/crates/ente-photos-ml/src/ml_db/migrations.rs` (lines 8–16)
- Evidence: `for script in &scripts[current as usize..]` after a downgrade check that doesn't reject negatives.
- Problem: A corrupt DB with negative `user_version` wraps the cast and panics with an out-of-range slice. Local-file corruption only, but a panic in library code on a reachable input.
- Fix: Reject `current < 0` with `Error::Invalid("corrupt schema version")`.
- Confidence: HIGH

**A-12 [Low] [bug] `person_average` can divide by zero and propagate NaN distances**
- File: `engine/crates/ente-photos-ml/src/ml_store/face_distances.rs` (lines 170–183)
- Evidence: `let face_count: i64 = centroids.iter().map(|(_, count)| count).sum();` with no zero guard; weights become NaN.
- Problem: If every cluster count is 0 (possible since `cluster_summary_update` is public), the weighted average is NaN and every distance becomes NaN, silently treated as "never close" downstream.
- Fix: If `face_count <= 0`, return `Ok(None)` like the empty case.
- Confidence: MEDIUM

**A-13 [Low] [bug] `get_face_embedding_rows_for_faces` silently caps results at 20 000 rows**
- File: `engine/crates/ente-photos-ml/src/ml_db/queries/faces.rs` (64, 373–403)
- Evidence: `const FACE_EMBEDDING_MAX_ROWS: usize = 20000; ... if remaining_limit == 0 { break; }`
- Problem: Faces beyond the cap are silently dropped; `recluster_faces` feeds this query, so reclustering a larger selection quietly leaves the remainder in old clusters with no error or report.
- Fix: Return the cap as part of the result (or error when exceeded), or page explicitly.
- Confidence: HIGH

**A-14 [Low] [bug] OFFSET-based pagination in `get_face_info_for_clustering` can skip/duplicate faces mid-run**
- File: `engine/crates/ente-photos-ml/src/ml_db/queries/faces.rs` (288–338)
- Evidence: `ORDER BY face_id DESC LIMIT ? OFFSET ?` with `offset += batch_size`.
- Problem: Concurrent face inserts shift rows across page boundaries between pages (DESC order puts new high ids first); long clustering runs can re-read or skip rows. Bounded impact, but the fill pipeline elsewhere (`fill.rs`) deliberately uses keyset cursors — this query does not.
- Fix: Use keyset pagination (`WHERE face_id < ? ORDER BY face_id DESC LIMIT ?`).
- Confidence: HIGH

**A-15 [Low] [performance] `get_all_clip_vectors` materializes every CLIP embedding into RAM**
- File: `engine/crates/ente-photos-ml/src/ml_db/queries/clip.rs` (35–46)
- Evidence: `self.read_all("SELECT file_id, embedding FROM clip", (), pair)?` — ≈2 KB per row, 1–2 GB at 500k–1M files.
- Problem: Exported API with no limit/paging; paged variants (`get_clip_rows_page` / `get_clip_rows_before`) exist right below and are used by the fill path.
- Fix: Expose only paged/keyset variants for bulk consumers, or stream via an iterator.
- Confidence: HIGH

**Verified-clean in track A (checked, no findings):** no SQL injection — every `ml_db` query binds parameters; zip-slip properly handled (`archive.rs:36–46` rejects `ParentDir | RootDir | Prefix`, link/special entries, never creates symlinks); published-asset symlinks rejected via `symlink_metadata`; size+SHA-256 enforced before install; `If-Range`/`Content-Range` resume validation; `unsafe_code` denied workspace-wide with `expect_used`/`unwrap_used` linted and every remaining `expect` carrying a reason; migrations run in one immediate transaction with downgrade refusal; `moxcms` ICC handling, PNG bounded downsampler, and the `vector_encoding` decoder are bounds-checked, depth-limited, and allocation-capped. The crypto seam (`crypto.rs`) contains only `random_bytes` — no keys/nonces/KDFs, so no nonce-reuse/KDF surface exists; the panic-on-RNG-failure policy is documented upstream semantics.

---

## 3. Track B — `ente-ml`, `ente-vecdb`, `toad-ml-jni`

10 findings (1 high, 2 medium, 7 low). `toad-ml-jni` is **net-new toadGALLERY code** (not part of the upstream pin) — fixes there are unconstrained.

**B-1 [High] [FFI-safety] `nativeStorePutFace` and `nativeStorePutClip` are the only JNI exports without `catch_unwind` — a panic aborts the whole process**
- File: `engine/crates/toad-ml-jni/src/lib.rs` (340–390, 837–888); contract at 54–64; `README.md` 46–50
- Evidence: 24 `#[unsafe(no_mangle)]` exports; 22 route through `call_or_throw` (`catch_unwind(AssertUnwindSafe(...))`, lib.rs:106). The two exceptions call the store directly: `match store::put_face(file_id, &values) { ... }` with no unwind guard.
- Problem: The workspace sets no `panic = "abort"`, so panics unwind; unwinding through an `extern "C"` boundary **aborts the process** — any panic inside `put_face`/`put_clip`'s call tree (rusqlite, MlStore, vecdb, allocation) kills the app with SIGABRT instead of surfacing the documented `IllegalStateException`. The README's "never unwind across JNI" contract is violated.
- Fix: Wrap both bodies in `call_or_throw` (sentinel `0` / `JNI_FALSE`), keeping the `IllegalArgumentException` legs for the argument reads, exactly as `nativeStoreMergeFaceClusters` does.
- Confidence: HIGH

**B-2 [Medium] [input-validation] Negative search `limit` is cast to `usize::MAX`, silently becoming an unbounded top-K search**
- Files: `engine/crates/toad-ml-jni/src/lib.rs` (1009, 1058) → `src/store.rs` (527, 599) → `ente-vecdb/src/graph.rs` (822–847, 860–907)
- Evidence: `limit: Some(limit as usize)` — `(−1 as usize) == usize::MAX`; contrast the deliberate `max_distance.is_finite() || max_distance < 0.0` guard in the same file ("defense-in-depth ... Kotlin's `require` is not the trust boundary").
- Problem: A negative `jint` limit flows through to vecdb where `brute_force` pushes every live vector into the heap and returns the **entire index** as JSON under the store mutex. Not memory-unsafe, but an unbounded result/latency path inconsistent with the crate's own boundary-guard doctrine.
- Fix: Reject `limit <= 0` at the Rust boundary in `search_clips`/`search_clips_within`, mirroring the `max_distance` guard.
- Confidence: HIGH (mechanism), MEDIUM (exploitability)

**B-3 [Medium] [memory/lifetime] `nativeRelease` does not free the loaded CLIP **text** session — it survives in the process-global ONNX runtime**
- Files: `engine/crates/toad-ml-jni/src/engine.rs` (395–402) → `ente-ml/src/indexing.rs` (71–73) → `runtime.rs` (156–160) + `models.rs` (32–42)
- Evidence: `release_ml_runtime()` iterates `Model::INDEXING`, which excludes `Model::ClipText`; the text session is built lazily and stays in `GLOBAL_RUNTIME`.
- Problem: After `nativeRelease` — documented as "frees the engine" — the quantized MobileCLIP-S2 text encoder (tens of MB resident) remains loaded until process death. `nativeInit`/`nativeInitSemantic` after a release keeps reusing the stale resident session. Likely intentional upstream residency policy colliding with the net-new JNI release contract.
- Fix: Give ente-ml a release path that also unloads `Model::ClipText` (or unload it explicitly from the bridge's `release()`), or document the residency in the JNI contract.
- Confidence: HIGH on code behavior; MEDIUM on intent

**B-4 [Low] [FFI] JNI exports use `extern "C"` instead of the JNI-conventional `extern "system"`**
- File: `engine/crates/toad-ml-jni/src/lib.rs` (all 24 exports)
- Problem: Equivalent on Android/arm64 today; wrong calling convention on a hypothetical Win32 build. Portability/spec hygiene.
- Fix: Switch the 24 exports to `extern "system"`.
- Confidence: HIGH (fact), LOW (impact)

**B-5 [Low] [robustness] Face-detector postprocess hardcodes `row_len = 16` and ignores the returned output shape**
- File: `engine/crates/ente-ml/src/face/detect.rs` (28–36, 52–63); contrast `pet/detect.rs` (84–110)
- Problem: Indexing stays in-bounds today, but a model-catalog swap to a detector variant with a different row layout would silently misalign (garbage boxes above the 0.5 gate) instead of failing loudly. The pet detector in the same codebase validates `row_len` against the tensor's last shape dimension — the asymmetry is the gap.
- Fix: Cross-check `output_shape`'s last dimension against the hardcoded 16 (or derive it, as the pet path does).
- Confidence: MEDIUM

**B-6 [Low] [memory] Clip tokenizer's per-token BPE cache grows unboundedly for the process lifetime**
- File: `engine/crates/ente-ml/src/clip/tokenizer.rs` (31, 188–191, 249–251)
- Evidence: `static TOKENIZER_STATE: Lazy<Mutex<Option<TokenizerState>>>` with `cache: HashMap<String, String>` and no eviction.
- Problem: Textbook unbounded growth on a user-influenced path in a long-lived process (entries are small; growth slow).
- Fix: Cap the cache (evict/clear at N entries) or drop it — BPE on ≤77 tokens per query is cheap.
- Confidence: HIGH

**B-7 [Low] [perf] `put_face` reads every existing face row (with full 192-float embeddings) just to compute the next ordinal**
- File: `engine/crates/toad-ml-jni/src/store.rs` (144–150)
- Evidence: `get_faces_for_given_file_id(file_id).map_err(...)?.len() as i64 + 1` — inside the global store mutex.
- Problem: A file with N faces costs N × ~1.6 KB of SQLite decode per insert.
- Fix: Add/use a count-only query (or cache the ordinal per open file handle).
- Confidence: HIGH

**B-8 [Low] [perf/crash-consistency tradeoff] Every vector add/remove fsyncs the log; periodic snapshots serialize the whole graph on the caller's thread**
- Files: `engine/crates/ente-vecdb/src/log.rs` (231–233, 284–288); `store.rs` (26–27, 1233–1237, 1241–1257)
- Problem: Crash consistency is *correct*, but on flash each upsert costs an fsync and every 5000th mutation blocks the indexing caller for a whole-graph snapshot write — under the JNI store mutex.
- Fix: Batch upserts through `bulk_add`; consider moving snapshot writes off the calling thread.
- Confidence: HIGH (mechanism), LOW (severity at realistic sizes)

**B-9 [Low] [hygiene] ente-vecdb's `.lock` sidecar is never removed by `reset()`, `delete()`, or `purge()`**
- Files: `engine/crates/ente-vecdb/src/lock.rs` (49–53); `store.rs` (883–923, 1831–1836)
- Problem: Permanent zero-byte litter next to deleted stores. No stale-lock/PID-reuse hazard (flock on the open file with inode revalidation — verified).
- Fix: Remove the sidecar in `remove_data_files` while holding the writer lock.
- Confidence: HIGH

**B-10 [Low] [model-loading] Native model validation pins exact byte size only; integrity rests entirely on the Kotlin installer's SHA-256**
- File: `engine/crates/toad-ml-jni/src/engine.rs` (26–62, 669–695)
- Problem: A file corrupted/swapped after install passes the native gate; the backstop is ONNX's parse failure (fails closed). The pinned catalog hashes already exist in Rust (`assets.rs:243–332`) and could be re-checked at init (cache the verdict per path+mtime).
- Confidence: HIGH (fact), LOW (severity)

**Verified-clean in track B (checked, no findings):** NMS/IoU math correct (`total_cmp` NaN-safe score sort, degenerate clamping, negative-intersection guard); persistence integrity (CRC verified on every record/header/snapshot load; torn tails handled; compaction staged→fsync→rename); flock sidecar + inode revalidation with mutex-poisoning recovery deliberately at every layer; JNI hygiene (no global refs, arrays copied without critical sections, consistent engine→store lock order, no `AttachCurrentThread` needed); ONNX session sharing serialized per model with bounded provider-retry; crafted-geometry OOM handled via `checked_length`/`checked_mul`/`try_reserve_exact`.

---

## 4. Track C — Kotlin ML pipeline (model provisioning, index queues, people/search, editor AI)

13 findings (3 high, 5 medium, 5 low).

**C-1 [High] [CI pipeline] Model-pack workflow pins a corrupted SHA-256 for the face-embedding model — the offline modelpack build can never succeed**
- File: `.github/workflows/apk-build.yml` (283–287)
- Evidence: workflow pins `0763fc33f54e138476194da95e87e133b3e976075a6b1d3e1b2caedb251b1a36`; `ModelManifest.kt:69` and `engine/crates/ente-ml/src/assets.rs:258` both pin `0763fc33f54e138476194da95987e133b3e976075a6b1d3e1b2caedb251b1a36` (byte-diff at index 25).
- Problem: `fetch_model` verifies the download against the workflow literal (`[ "$got_sha" = "$sha" ] || exit 1`), so every `GALLERY_TARGET == modelpack` build hard-fails on `mobilefacenet_portable_static_b1.onnx` — the `-PwithBundledModels` offline release channel (ADR-0020 §7) is broken. Fail-closed at least: a tampered asset can never ride into an APK because of this check.
- Fix: Correct the literal to the manifest/Rust value; better, have the workflow read the pins from `ModelManifest.kt` or a generated file instead of hand-transcribing.
- Confidence: HIGH

**C-2 [High] [concurrency] UI surfaces tear down the process-global native engine while the background index queues run**
- Files: `apps/gallery/.../ml/people/PeopleEngineAdapter.kt` (89–95), `ml/search/MagicSearchEngineAdapter.kt` (97–103), `ui/settings/AiEngineViewModel.kt` (441)
- Evidence: `fun close() { … engine.closeFaceIndex(); engine.release() … }` called from owning ViewModels' `onCleared`; self-test `finally { engine.release() }`.
- Problem: `release()`/`closeFaceIndex()` are process-global; closing the People/Search screen (or running the Settings self-test) while a face/clip WorkManager chain is mid-batch makes the queue's next `putFace`/`clipImageEmbedding` throw `IllegalStateException("engine is not initialized")`, burning all 3 retries and failing the run. `FaceIndexWorker.kt:220–236` documents that r48-F1 removed this exact bug from the workers but left it alive here; tracked as follow-up F-38.
- Fix: Drop `release()` from adapter `close()`/self-test (leave idempotent re-opens), or gate teardown on "no chain running" via the WorkManager unique-name state.
- Confidence: HIGH

**C-3 [High] [data consistency] Settings "Reset face index" races the queue's `NonCancellable` state save — stale bookkeeping resurrects against a deleted store**
- Files: `apps/gallery/.../ui/settings/AiEngineViewModel.kt` (386–400) + `ml/index/ClipIndexQueue.kt` (380–386), `ml/index/FaceIndexQueue.kt` (317–323)
- Evidence: Reset cancels only the FACE chain, then `faceIndexDir().deleteRecursively()` — while an in-flight CLIP batch (own unique name, never cancelled) keeps writing rows into the now-unlinked `ml-index.sqlite`, and the processor's `finally { withContext(NonCancellable) { stateStore.save(...) } }` re-creates the deleted state file.
- Problem: The planner diffs the resurrected state records as UNCHANGED → skips everything → Magic Search/Things permanently show `NotIndexed` until each photo's revision changes. The cancelled FACE worker's own save can land after the delete the same way (WorkManager cancel is asynchronous).
- Fix: Cancel BOTH chains and wait for terminal state (or include a queue-generation token / store-existence check in the planner) before `deleteRecursively`.
- Confidence: HIGH mechanism, MEDIUM occurrence

**C-4 [Medium] [JNI lifecycle] `MlEngine` facade state is per-instance while the JNI engine is process-global**
- Files: `apps/gallery/.../ml/engine/MlEngine.kt` (301–308, 341–352, 387–400); workers `FaceIndexWorker.kt:84`, `ClipIndexWorker.kt:96`
- Evidence: `private var sessionOpen/faceIndexOpen/semanticReady` per instance; both workers construct `val engine = MlEngine()` per run while adapters/self-test own further instances.
- Problem: Flags desynchronize from native truth: instance A's `release()` leaves instance B reporting `State.Ready` while native calls throw. The `@Synchronized` guards are per-instance. This is the root shape behind C-2/C-3, documented piecemeal in four KDocs but never centralized.
- Fix: Make the facade a process-wide singleton (or move open/close/ready flags behind a shared static).
- Confidence: HIGH

**C-5 [Medium] [robustness] A single poison photo wedges the index queue at the same head-of-queue item forever**
- Files: `apps/gallery/.../ml/index/FaceIndexQueue.kt` (293–316), `ml/index/ClipIndexQueue.kt` (349–379); workers 191–199 / 214–222
- Evidence: Only byte-read failures are skipped; an engine fault (`clipImageEmbedding`/`detectFaceEmbeddings` throwing for a decodable-but-corrupt JPEG) aborts the batch → `Result.retry()` → same plan → same photo first → 3 attempts → run fails; every re-start repeats.
- Problem: r48 (F2) fixed the `openInputStream` throw path but not the inference-failure path; one corrupt-but-readable JPEG permanently blocks both queues.
- Fix: Catch per-photo engine faults inside the processor loop and count them like `unreadableThisRun`, or track consecutive engine failures per file id and skip after N.
- Confidence: MEDIUM

**C-6 [Medium] [network/provisioning] Install receipt is written non-atomically and `ModelProvisioner` has no internal synchronization**
- File: `apps/gallery/.../ml/engine/ModelProvisioner.kt` (156–181, 183–195)
- Evidence: `receiptFile.outputStream().use { props.store(it, …) }` — no tmp+rename, in contrast to every JSON state store in the repo (all `ATOMIC_MOVE`); `installOne` stages into a fixed shared temp path with no lock.
- Problem: A crash mid-`props.store` truncates the receipt; `installed()` then returns `emptyMap()` and the app silently re-downloads up to ~213 MB. Concurrent `provision()` (today guarded only by the ViewModel's `busy` flag) interleaves on the same `.part` file.
- Fix: Write the receipt via the tmp-then-`ATOMIC_MOVE` discipline; synchronize `provision`/`installOne` or assert the single-flight contract in the class.
- Confidence: HIGH (code), impact LOW–MEDIUM

**C-7 [Medium] [privacy/consistency] People/Magic-Search/Things media lookups apply no vault/trash/hidden filter at query time — they lean entirely on MediaStore truth**
- Files: `apps/gallery/.../ml/search/MagicSearchMediaQuery.kt` (91–96), `ml/people/PeopleMediaQuery.kt` (148–154), `ml/search/MagicSearchRepository.kt` (137–153)
- Evidence: `resolver.query(..., "_id IN (?,?,…)")` with no `IS_TRASHED`/vault/hidden condition; `PeopleContentPolicy` filters only user-hidden *clusters*, not vault-locked *media*.
- Problem: The vault contract deletes the MediaStore row on vault move, so sealed items are structurally unrepresentable — but during the deletion/rescan window (e.g. pending `createDeleteRequest` on R+, MediaStore lag) a vaulted or trashed item's face/CLIP rows still resolve and can render in a person grid or search result. No defense-in-depth filter at the read path the way there is at the write path.
- Fix: Add `IS_TRASHED != 1` (Q+) and re-check the catalogue's live/tombstoned records (or a vault-id set) before rendering ML-sourced media.
- Confidence: MEDIUM (transient window; steady-state verified by tests)

**C-8 [Medium] [memory/crash] Editor ONNX runtime catches only `RuntimeException` — `OutOfMemoryError` crashes the commit; sessions are never closed**
- File: `apps/edit/.../edit/EditAiOrtRuntime.kt` (158, 226, 260, 307; session cache 41–48, 84–95)
- Evidence: `catch (_: RuntimeException) { AiRun.Unavailable(...) }` on every run path; `inpaintFill` feeds full-size uint8 + mask tensors at the FULL working grid (no cap); `saliencySession`/`inpaintSession`/`depthSession`/`facesSession` are never `close()`d.
- Problem: Tensor allocation for a large working image (50 MP → ~150 MB + mask) can throw `OutOfMemoryError` — an `Error`, not a `RuntimeException` — escaping the dedicated-degradation contract and crashing the editor at commit time. Four ORT sessions (~60 MB of graphs) leak per activity instance.
- Fix: Catch `OutOfMemoryError` (or `Throwable` except cancellation) → `RUNTIME_BROKEN`; cap the inpaint working grid before tensor creation; add a `close()` tied to the activity lifecycle.
- Confidence: HIGH (code), occurrence MEDIUM

**C-9 [Low] [dead data] `ClipIndexPlan.reIndexKeys` / `FaceIndexPlan.reIndexKeys` are computed but never consumed by the executors**
- Files: `ml/index/ClipIndexQueue.kt` (177–180, 195–198, 356–361), `ml/index/FaceIndexQueue.kt` (160–163, 300–303)
- Problem: Planners add keys under `CONTENT_CHANGED`, but both processors unconditionally delete-first every candidate. Harmless today (delete-first is a superset) but documents an action set the executor ignores.
- Fix: Drive the per-photo delete from `reIndexKeys ∪ store-row-truth` or remove the field and document delete-first as unconditional.
- Confidence: HIGH

**C-10 [Low] [perf/DoS] Things membership read serializes the whole CLIP index across JNI on every read**
- Files: `ml/things/ThingsRepository.kt` (197–205); `ml/things/ThingsMembership.kt` (39–59)
- Evidence: `val bound = engine.clipCount().toInt().coerceAtLeast(1); val matches = engine.searchClipsWithin(embedding, maxDistance, bound)` — limit is the full row count; result crosses JNI as JSON; repository holds no cache.
- Problem: One membership read builds/parses a multi-MB JSON string on the calling thread at 100k rows; recomputed on every screen entry.
- Fix: Bound the Things search with a sane constant limit (the distance cut still decides membership).
- Confidence: HIGH (code), impact scales with library size

**C-11 [Low] [manifest integrity] `EditAiManifest` never checks hex-charset; corrupt artifact files can OOM the forgiving-read contract**
- Files: `apps/edit/.../EditAiManifest.kt` (33–37), `EditAiStore.kt` (255–258, 330–337, 377–394)
- Evidence: `require(sha256.length == 64)` with no hex check; `FillPatch.fromBytes` does `IntArray(w * h)` from file-declared dims, catching only `RuntimeException`/`IOException`.
- Problem: A corrupt/hostile artifact declaring huge dims can trigger `OutOfMemoryError` (not caught) — contradicting the "forgiving reads, never a crash" KDoc. Low-risk (APK-signed assets, app-private files).
- Fix: Validate `[0-9a-f]{64}` in the `init`; clamp declared dims (reject `w*h` > 64 M) before allocation.
- Confidence: HIGH

**C-12 [Low] [robustness] No disk-space preflight before multi-hundred-MB model downloads**
- Files: `ModelProvisioner.kt` (144–181), `HttpModelDownloader.kt` (whole file)
- Problem: On a nearly-full disk the SEMANTIC pack (~213 MB) fails mid-copy as a generic wrapped `IOException`, leaving no actionable copy for the user; integrity is unaffected (verify-then-commit genuinely blocking — confirmed).
- Fix: Check free space ≥ pinned `sizeBytes` + headroom in `provision()` and fail with a dedicated "not enough storage" message.
- Confidence: HIGH

**C-13 [Low] [scheduling] Index queues run unconstrained plain background work**
- Files: `FaceIndexWorker.kt` (361–381), `ClipIndexWorker.kt` (343–357); `AndroidManifest.xml` (51–53)
- Evidence: `OneTimeWorkRequestBuilder<…>().build()` with no `setRequiresBatteryNotLow`/storage constraints; WorkManager `FOREGROUND_SERVICE` explicitly stripped (documented r24 scope).
- Problem: 50-photo batches over 143 MB-CLIP/32 MB-face sessions run whenever WorkManager allows, including low battery, without user-visible notification. Resumability prevents corruption — battery/UX only.
- Fix: Add `setRequiresBatteryNotLow(true)` (+ optionally storage-not-low), or plan the foreground-notification round named in the manifest comment.
- Confidence: HIGH

**Verified-clean in track C (checked, no findings):** `ModelManifest.kt` matches the vendored Rust catalog 11/11 on name+size+SHA-256 (byte-diffed); `HttpModelDownloader` — HTTPS structurally pinned, host built behind `requireSafeModelFileName` (rejects `/`, `\`, `..`, `://`, `?`, `#`, non-catalog alphabet before any socket), redirects fail closed, content-length pre-check, 15s/60s timeouts, streaming SHA-256 gates rename+receipt (TOCTOU-safe); INTERNET-permission cross-check consistent with `.github` expectations; Retouch consent enforced at both render and commit paths; all four state stores use atomic tmp+`ATOMIC_MOVE` writes with honest corrupt-file flags; People recluster remap well-fenced.

---

## 5. Track D — Vault, crypto, and file-ops backend

13 findings (0 high, 7 medium, 6 low).

**D-1 [Medium] [security] No PIN attempt throttling or lockout; KDF budget below current guidance with 4-digit minimum**
- Files: `apps/gallery/.../ui/vault/VaultViewModel.kt` (381–403, 933–939); `data/vault/VaultCrypto.kt` (50–51)
- Evidence: `attemptUnlock` has no attempt counter/backoff; `const val PIN_ITERATIONS = 210_000`; `const val MIN_PIN_LENGTH = 4`.
- Problem: Unlimited wrong-PIN retries with only PBKDF2 cost as a rate limiter; 4-character PIN (~10⁴ space); 210k rounds is the 2021-era OWASP figure (~600k for SHA-256 now). On a mid-range phone an online brute-force of a 4-digit PIN is feasible in hours-to-days with no lockout.
- Fix: Per-session exponential backoff / failed-attempt counter (persisted sealed alongside the PIN record); raise `PIN_ITERATIONS` toward the current OWASP budget; consider `MIN_PIN_LENGTH` 6.
- Confidence: HIGH (absence verified); exploitability MEDIUM

**D-2 [Medium] [security] No FLAG_SECURE / recents-screenshot protection on vault surfaces**
- Files: `AndroidManifest.xml`; repo-wide grep for `FLAG_SECURE` / `setRecentsScreenshotEnabled` returns zero hits.
- Problem: The unlocked vault renders decrypted thumbnails in-process; the OS recents app-switcher screenshot is a plaintext snapshot persisting in system UI storage while the vault auto-locks — for a feature whose design goal is "plaintext never persists outside memory."
- Fix: Set `FLAG_SECURE` while `VaultUiState.Unlocked` (or app-wide); disable the recents screenshot.
- Confidence: HIGH (absence verified)

**D-3 [Medium] [robustness] Vault blob writes are not fsynced before the fsynced index commit references them**
- File: `apps/gallery/.../data/vault/VaultStore.kt` (423–433, 533–535 vs 742–758)
- Evidence: `FileOutputStream(blob).use { output -> VaultCrypto.encryptTo(counting, output, keys.key()) }` — no `fd.sync()`; contrast `atomicWrite` which does `it.fd.sync()` before rename.
- Problem: `store()`/`storeBatch()` commit the durable index referencing a blob whose data blocks were only close()-flushed. Power loss in the window → durable index points at a short/zero-backed blob → item permanently fails GCM authentication — and during a move the original MediaStore row is already deleted, so the item is unrecoverable.
- Fix: `FileOutputStream.getFD().sync()` (or `FileChannel.force(true)`) after the blob copy, before the index `atomicWrite`.
- Confidence: HIGH (missing sync verified); impact MEDIUM

**D-4 [Medium] [robustness] `sealBatch` holds every selected item's source stream open for the whole batch**
- File: `apps/gallery/.../data/vault/VaultRepository.kt` (488–541)
- Evidence: `opened += Opened(index..., stream = stream)` in a loop, then `store.storeBatch(opened.map { ... bytes = o.stream ... })`, closing all in `finally`.
- Problem: All N MediaStore provider pipes stay open while `storeBatch` seals sequentially — a large selection risks FD exhaustion (`too many open files`) or provider-side timeouts that turn into per-item `FAILED` outcomes for items that were perfectly readable.
- Fix: Feed the batch lazily (open each stream inside the seal iteration), or chunk the selection into bounded sub-batches (e.g. 32).
- Confidence: MEDIUM

**D-5 [Medium] [robustness] Biometric key generation lacks the StrongBox→TEE fallback its sibling got**
- Files: `data/vault/AndroidVaultBiometricKeys.kt` (126–137) vs `AndroidVaultKeys.kt` (115–146)
- Evidence: Biometric `generate()` catches only `StrongBoxUnavailableException`; the media key's W1 hardening adds `catch (e: GeneralSecurityException)` and `catch (e: RuntimeException)` for OEM keystores that abort StrongBox generation with a plain `ProviderException`.
- Problem: The exact OEM failure mode documented for `AndroidVaultKeys` is unhandled in `AndroidVaultBiometricKeys` — on such devices biometric enrollment fails raw instead of falling back to the TEE. Inconsistency between two near-identical seams.
- Fix: Mirror the media key's broad fallback (catch `GeneralSecurityException`/`RuntimeException`, WARN-log, fall through to `generateKeystoreKey()`).
- Confidence: HIGH (code asymmetry verified)

**D-6 [Medium] [robustness] Provenance healing pass can starve older rows behind persistently failing newest rows** — *duplicate of E-3; see Track E for the full report (rated High there).*
- Files: `data/media/changes/ProvenanceHealingStore.kt` (109–122); `CatalogueDao.kt` (`rowsNeedingProvenanceHealing` … `ORDER BY lastSeenAtMillis DESC ... LIMIT :limit`)

**D-7 [Medium] [bug] EXIF metadata edit rewrites the original in place with no safe-write/backup**
- File: `apps/gallery/.../metadata/edit/MediaMetadataWriter.kt` (161–174, 197–206)
- Evidence: `resolver.openFileDescriptor(uri, MODE_READ_WRITE)?.use { pfd -> val exif = ExifInterface(pfd.fileDescriptor); ...; exif.saveAttributes() ...` — the androidx fd-backed path rebuilds the image and copies it back through the same fd, non-atomically.
- Problem: A crash, process death, or mid-write `IOException` (disk full) during the save leaves the original photo truncated/corrupt; no app-level copy exists, so the pre-edit image is unrecoverable.
- Fix: Stage the edit against a full copy (pending MediaStore row, publish on success — the pattern `MediaFileOps.copy` and `VaultRepository.restore` already use), or snapshot the original bytes to cache before the in-place save.
- Confidence: MEDIUM (androidx fd-backed in-place behavior per pinned 1.4.1 sources)

**D-8 [Low] [bug] `initEncrypt`'s caller-IV fallback re-inits a Keystore cipher that already refused parameter-less IVs**
- File: `data/vault/VaultCrypto.kt` (99–111)
- Problem: If a randomized-encryption-required provider succeeds at parameter-less init but reports a non-12-byte IV, the code falls through and re-inits the same cipher with a caller IV — which that provider rejects, now uncaught. In practice AndroidKeyStore always returns a 12-byte GCM IV, so this is an edge.
- Fix: Wrap the fallback re-init in its own try/catch (or re-init a fresh `Cipher` and let the failure propagate with context).
- Confidence: MEDIUM

**D-9 [Low] [robustness] `atomicWrite`'s delete-then-rename fallback opens a vault-without-index window**
- File: `data/vault/VaultStore.kt` (749–754)
- Evidence: `if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { throw ... } }`
- Problem: If the first replace-rename fails, the current index is deleted before the retry — a crash between the two leaves the vault with no `index.bin` at all. On Linux/Android rename always replaces, so this is dead code on the production platform, but a data-loss hazard if the assumption ever breaks.
- Fix: Drop the fallback (fail loudly; the fsynced `.tmp` survives), or rename the old index to `.bak` before deleting.
- Confidence: HIGH on code; LOW on reachability

**D-10 [Low] [security] Vault privacy claims rest on a permission that now exists**
- Files: `VaultCrypto.kt` (44–46), `AndroidVaultKeys.kt` (59–61), `VaultStore.kt` (131–133), `AndroidVaultBiometricKeys.kt` (50–53), `VaultViewModel.kt` (218–219), `ContentResolverMediaAccess.kt` (30–31) vs `AndroidManifest.xml` (14–26)
- Evidence: Six load-bearing privacy arguments assert "no INTERNET permission exists" while the manifest declares INTERNET (r22, model downloads).
- Problem: The documented threat model and the code disagree since r22; the residual guarantee is the build-time `InternetPermissionGateTask` allowlist, which none of these files mention.
- Fix: Update the KDocs to cite the gated single-host INTERNET contract (or centralize the claim in one ADR reference).
- Confidence: HIGH

**D-11 [Low] [security] Plaintext video thumbnail temp file survives an in-decode crash until the next unlock**
- Files: `data/vault/VaultThumbnailDecoder.kt` (159–189); `VaultThumbnailPolicy.kt` (35–39)
- Evidence: Full video decrypted into `cacheDir/vault-thumb-<id>.tmp` with `finally { temp.delete() }`; sweep only "on the next unlock".
- Problem: Process death mid-extract leaves the full plaintext file on disk indefinitely — reclaimed only by the next unlock's sweep (not run at app start). Private-cache placement bounds exposure, but the "no plaintext on disk" hard rule has a crash window measured in potentially days.
- Fix: Run `cleanStaleTempFiles()` once at app start; consider decrypting only the first MBs needed for `OPTION_CLOSEST_SYNC` frame extraction.
- Confidence: HIGH on behavior; MEDIUM on severity

**D-12 [Low] [bug] Copy insert-collection inferred from URI path segments — MIME/collection confusion for non-media URIs**
- File: `data/MediaFileOps.kt` (536–541)
- Evidence: `insertCollectionFor` checks `pathSegments.any { it.equals("video", ...) }` else defaults to Images.
- Problem: A video row from the Files/Downloads collection (`content://media/external/file/<id>`) has no `video` segment, so its duplicate is inserted into the Images collection with a `video/*` MIME — a row-type/MIME mismatch downstream timeline/filter queries key on. The vault's restore avoids this via the tracked `isVideo` flag; file-ops copy does not.
- Fix: Derive the collection from the source row's `MIME_TYPE` prefix (already queried in `querySourceDetails`), URI-segment check as tiebreak.
- Confidence: MEDIUM

**D-13 [Low] [security] EXIF GPS coordinates indexed while consent was granted persist after consent revocation**
- Files: `data/media/location/LocationEvidence.kt` (130–149); `LocationConsentReindexPolicy.kt` (62–88)
- Problem: Display is correctly gated everywhere (Details/Map/Places/Albums), but after revoking `ACCESS_MEDIA_LOCATION` previously indexed coordinates remain at rest in the catalogue DB — only wiped if the row's content changes. The grant→repass transition is documented; revoke→nothing is not.
- Fix: On grant→revoke, run one targeted pass setting `EXIF_GPS` rows to `MEDIA_LOCATION_UNGRANTED` (mirror of the documented `REPASS_USEFUL`).
- Confidence: HIGH on behavior; MEDIUM on defect-vs-design

**Verified-clean in track D (checked, no findings):** AES-256-GCM with provider-generated random IVs; non-exportable Keystore keys with StrongBox attempt + TEE fallback on the media key; correctly split media key (no auth binding) vs auth-per-use biometric binding key with enrollment invalidation ON; strict crash-orderings (blob-then-index, index-then-blob-delete); transactional setup; orphan sweep with cross-instance grace window; issue #69 thumbnail-publish race demonstrably fixed on main (monotonic lock generation + batch cancellation + immediate pre-publish re-check, JVM-pinned incl. sweep-vs-move and concurrent-store tests); rename rejects separators; moves keep the copy on partial failure; GPS display-gated everywhere.

---

## 6. Track E — Room catalogue + scan pipeline + paging

15 findings (3 high, 6 medium, 6 low).

**E-1 [High] [bug] Null MediaStore cursor silently reconciles as "empty volume" → mass tombstone + checkpoint stamped over a failed walk**
- Files: `data/media/changes/MediaScanRowSource.kt` (279–285); `MediaScanDriver.kt` (441–459)
- Evidence: `queried?.use { cursor -> ... }` — a `null` cursor (documented "query fails for any reason") is skipped silently; the driver then does `catalogue.tombstone(reconciliation.removedKeys, scannedAt)` and `catalogue.saveScanCheckpoint(volume, liveToken, scannedAt)` unconditionally.
- Problem: A transient provider failure yields an empty row list → the reconciler classifies every live record of that volume as REMOVED → the driver tombstones the whole library. The checkpoint is saved with the unchanged generation token, so `ScanDriverPolicy.decide` returns SKIP on every later pass — the catalogue stays empty (timeline blank) until media changes bump the generation. Downstream, `FaceIndexQueuePlanner` orphans and deletes all engine rows, destroying index work.
- Fix: Treat a `null` cursor as a walk failure (throw like the cancellation path does, so the per-volume catch isolates it and no tombstone/checkpoint lands); optionally guard `removedKeys` size against the walk row count.
- Confidence: HIGH

**E-2 [High] [bug] `IN (:keys)` DELETEs exceed SQLite's 999 host-parameter limit on API ≤ 30, wedging the scan pipeline after mass changes**
- Files: `data/catalogue/room/CatalogueDao.kt` (133–134, 201–202, 177–184, 216–223); driven from `MediaScanDriver.kt` (455–458)
- Evidence: `@Query("DELETE FROM catalogue_items WHERE localMediaKey IN (:localMediaKeys)")` inside `@Transaction` wrappers; driver calls `catalogue.tombstone(reconciliation.removedKeys, scannedAt)` with the unbounded list; minSdk 26.
- Problem: Android 8–11 ship SQLite < 3.32 where `SQLITE_MAX_VARIABLE_NUMBER` defaults to 999; Room expands the list into one bind parameter per key. Deleting/trashing >999 items in one scan window (emptying a large trash, the documented OS-upgrade re-sight) throws "too many SQL variables"; the transaction rolls back (upserts included), the checkpoint is never saved, and every subsequent pass repeats the full walk and fails identically — permanently wedged on those devices.
- Fix: Chunk the key lists (e.g. ≤900 per statement) inside the `@Transaction` wrapper methods.
- Confidence: HIGH

**E-3 [High] [bug] Provenance healing pass wedges on permanently-failing rows: no-progress re-selection starves the queue and re-reads the same bytes every app start**
- Files: `data/media/changes/ProvenanceHealingStore.kt` (109–122); `CombinedExifIndexer.kt` (149–156, 196); `CatalogueDao.kt` (413–419)
- Evidence: `heal()` re-selects `WHERE provenanceIndexedAtMillis IS NULL ORDER BY lastSeenAtMillis DESC ... LIMIT :limit`; rows that fail deterministically (corrupt EXIF throwing past the IO catch, malformed-key rows skipped by the gate) keep a NULL stamp and a constant `lastSeenAtMillis`, so the same head rows are re-selected: within one pass the loop re-offers the same batch up to 10×, and across app starts the pass spends its entire 1000-row budget on the same poisoned block — rows behind it never heal, and the same bytes are re-read each start.
- Fix: Track re-offered keys inside `heal()` and stop when a batch repeats; persist a per-row failure/attempt marker so the selection rotates past permanently failing rows.
- Confidence: HIGH

**E-4 [Medium] [performance] Whole-library EXIF indexing runs synchronously under the scan mutex, foreground-only, with no WorkManager/battery constraint**
- Files: `MediaScanDriver.kt` (455–480); `CombinedExifIndexer.kt` (126–141); `MediaReadHandle.kt` (73)
- Problem: The first-scan index pass reads every added/changed item's bytes (up to 64 MiB buffered per row) while holding the scan mutex — a large library pins the mutex and a `Dispatchers.Default` worker for minutes-to-hours; process death mid-pass loses the work (healed only 1000 rows/start).
- Fix: Move the index-hook stage off the scan mutex onto its own bounded scope/queue (WorkManager or persistent queue), or chunk the hook invocation per batch.
- Confidence: HIGH

**E-5 [Medium] [bug] Volume hot-plug runs MediaStore IPC on the main thread (ANR risk)**
- Files: `data/media/changes/AndroidVolumeMonitor.kt` (64–96); `MediaScanDriver.kt` (286–300, 320–323)
- Evidence: The receiver's `onReceive` (no handler → main thread) → `registerObservers()` → `MediaStore.getExternalVolumeNames(context)` (binder IPC) plus `registerContentObserver` calls — exactly when MediaProvider is busiest (just mounted a volume).
- Fix: Hop `onVolumesChanged()` (or just `registerObservers()`) into `scanScope.launch` before touching the provider.
- Confidence: HIGH

**E-6 [Medium] [performance] No secondary indices on `catalogue_items` — every common query is a full table scan**
- Files: `CatalogueItemEntity.kt` (50–53); `CatalogueDao.kt` (339–348, 359–360, 384–385, 413–419); schema `6.json` (`"indices": None`)
- Problem: Per-Details-open `recordsByCanonicalUri`, map/places geotagged projection, healing selection, consent reindex count, and folder-filtered windows are all O(n) scans with a sort at 50k-item libraries; several run per user interaction or per scan pass.
- Fix: Add `@Index` on `canonicalUri`, `bucketId`, `provenanceIndexedAtMillis` + a v6→v7 migration with `CREATE INDEX` statements.
- Confidence: HIGH

**E-7 [Medium] [performance] Every scan pass materializes the full live catalogue and the full volume row set in memory**
- Files: `MediaScanDriver.kt` (441–445); `MediaScanRowSource.kt` (126–127, 327–404)
- Evidence: `catalogue.recordsMap().filterKeys { ... volumeName == volume }` on every pass (including observer-triggered), plus an unbounded cursor loop building `ArrayList<MediaScanRow>`.
- Problem: O(library) memory and deserialization per scan blip; a 50k library holds two full copies per pass, compounding the mutex-hold time of E-4.
- Fix: Scope the read in SQL (volume-name column or key-prefix `LIKE`) and stream the walk/reconcile per cursor chunk.
- Confidence: HIGH

**E-8 [Medium] [bug] ML index queues bypass the eligibility boundary: no trash/eligibility re-check, no permission gate, uncapped byte reads**
- Files: `ml/index/FaceIndexWorker.kt` (149–177, 307–327; same `eligibleCandidates` reused by `ClipIndexWorker`)
- Evidence: Candidates filtered only by `mimeType == image/jpeg` + volume; `resolver.openInputStream(Uri.parse(candidate.uri))?.use { it.readBytes() }`.
- Problem: `MediaEligibility.kt` states vault/trashed/revoked media "CANNOT be passed to an indexing call by accident; the compiler enforces" via `EligiblePublicMedia` — the workers never use that type, `MediaAccess`, or `MediaReadPermissionGate`. An item trashed after the last scan stays a live record until the next scan tombstones it; during that window the queues read and index its bytes. `readBytes()` has no size cap (the `MediaReadHandle` 64 MiB guard), so one pathological JPEG can OOM the worker.
- Fix: Route candidates through the catalogue→eligibility gate (`EligiblePublicMedia` + trash-state re-check) and read through `MediaAccess` so cap and gate 2 apply.
- Confidence: HIGH

**E-9 [Medium] [bug] Tombstone retention purge is never wired — tombstones grow unbounded and nothing consumes them**
- Files: `data/catalogue/MediaCatalogue.kt` (24–33, 207); `RoomMediaCatalogue.kt` (183–185)
- Evidence: Interface KDoc promises aging-out via `purgeTombstonesOlderThan`; repo-wide grep finds zero call sites for it (and none for `tombstonedKeys`) outside their definitions.
- Problem: Every deletion permanently adds a tombstone row — the opposite of the documented retention policy; the "AI engines observe deaths" contract has no consumer (engine stores only get cleaned via workers' orphan-sweep).
- Fix: Run a bounded purge at scan completion (cutoff = scannedAt − retention window) inside the driver; expose `tombstonedKeys()` to the ML queues.
- Confidence: HIGH

**E-10 [Medium] [robustness] Hand-written Room schema snapshots + self-reimplemented identity hash**
- Files: `data/catalogue/room/MediaCatalogueDatabase.kt` (62–77); `apps/gallery/schemas/.../{4,5,6}.json`
- Evidence: "The v4 schema snapshot (4.json) is hand-written in this round (no local KSP available); its identityHash was computed with Room 2.8.5's own algorithm ... RELEASE GATE, non-negotiable: a wrong identityHash bricks every upgraded install at DB open."
- Problem: Shipped snapshots were produced by a re-implementation of Room's internal hash rather than the compiler; the sole protection is a CI diff the repo cannot currently prove ran. A mismatch is a hard open-time `IllegalStateException` on every upgraded install. (The non-destructive migration policy itself is correct and well-implemented; this is the residual risk in it.)
- Fix: Regenerate all snapshots with the real KSP build and commit the compiler-produced identity hashes before release; add a CI step that fails on snapshot drift.
- Confidence: MEDIUM

**E-11 [Low] [bug] Catalogue media-type filter classifies null-mime videos as photos (cross-path disagreement)**
- Files: `MediaScanRowSource.kt` (354–358); `filters/TimelineFilterCatalogue.kt` (100–107); `TimelineFilterMediaStore.kt` (114–123)
- Evidence: Scan writes `mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"`; catalogue "Videos" chip is `mimeType LIKE 'video/%'` while MediaStore path filters `media_type = 3`.
- Problem: A null-MIME video row is persisted as octet-stream → the catalogue-backed timeline's Videos chip excludes it (and Photos includes it) while the MediaStore-backed path counts it as a video — the same filter answers differently per backend.
- Fix: Persist `media_type` in the catalogue (or map the Files-walk fallback to `video/*`/`image/*` from `MEDIA_TYPE`).
- Confidence: HIGH

**E-12 [Low] [bug] Trash list sort lacks the `_id` tiebreak the file's own discipline mandates**
- File: `data/MediaStoreSource.kt` (455–458)
- Evidence: `items.sortedByDescending { it.dateAddedSec }` where every other read path uses `date_added DESC, _id DESC`.
- Fix: Reuse `TimelineSort.NEWEST_FIRST.sortItems(items, { it.dateAddedSec }, { it.id })`.
- Confidence: HIGH

**E-13 [Low] [bug] Room `changes()` diverges from the documented emission contract (over-emission + small subscription race)**
- Files: `CatalogueDao.kt` (113–126); `RoomMediaCatalogue.kt` (142)
- Evidence: Observed query is `SELECT COUNT(*) FROM catalogue_items` — InvalidationTracker fires on ANY table write, including location writes and healing stamps (up to ~1000 per app start), beyond the documented emission set; `drop(1)` swallows a mutation landing between subscription and initial delivery.
- Fix: Document the extended emission set on the interface (payload is Unit, benign); consider keying the flow on a mutation counter instead of `drop(1)`.
- Confidence: HIGH

**E-14 [Low] [robustness] Pre-R snapshot memo has a benign double-walk race and never refreshes within a generation**
- File: `ui/photos/PhotosViewModel.kt` (729–750)
- Evidence: `var snapshot: List<MediaItem>? = null` captured by the paging-source lambda; two concurrent window loads can both observe null and each walk the entire library.
- Fix: Memoize under a `Mutex` (or compute the snapshot eagerly at generation build).
- Confidence: HIGH

**E-15 [Low] [robustness] `clampLatitude(NaN)` passes NaN through, relying on downstream guards**
- Files: `data/geo/Equirectangular.kt` (94–95, 130–131); `places/PlacesRegionGrid.kt` (164–166)
- Evidence: `latitude.coerceIn(-90.0, 90.0)` — `coerceIn`'s comparisons are false for NaN, so NaN survives; downstream `isNaN()` checks exist everywhere.
- Fix: Add `if (latitude.isNaN()) return 0.0` (or a require) in `clampLatitude` to make the clamp total.
- Confidence: HIGH (mechanism), LOW (reachability)

**Verified-clean in track E (checked, no findings):** paired tombstone/upsert transitions genuinely atomic (`@Transaction` wrappers matching the in-memory reference); `@Upsert` instead of conflict-REPLACE; explicit non-destructive migrations; reconciler/scan-policy/paging math pure and JVM-test-pinned; no `GlobalScope`/`runBlocking`/main-dispatcher work in the data packages; timeline total-order (effective instant + key tiebreak) correctly transitive; `WindowPagingSource` window lattice/refresh-key math sound.

---

## 7. Track F — CI/CD, build config, privacy gates, F-Droid, licensing

14 findings (0 high, 9 medium, 5 low).

**F-1 [Medium] [security] Third-party GitHub Actions pinned to mutable tags/refs, not commit SHAs — including in the signing/publishing pipeline**
- Files: `.github/workflows/release.yml` (86, 397, 414), `.github/actions/toad-setup/action.yml` (37, 43, 55, 75, 106, 134), `ci.yml` (56, 152)
- Evidence: `softprops/action-gh-release@v2` (job holds `permissions: contents: write` + signing secrets); `dtolnay/rust-toolchain@stable`; `actions/checkout@v4`, `actions/cache@v4`, etc.
- Problem: None of the third-party actions are SHA-pinned, so a compromised/renamed upstream tag executes attacker code inside workflows — release.yml runs with `contents: write`, decodes the release keystore and publishes. `@stable` also floats the Rust toolchain, weakening the pinned-toolchain claim in `docs/phase-11/reproducible-builds.md` §2.
- Fix: Pin every third-party action to a full commit SHA (version-commented); pin the toolchain to a concrete ref.
- Confidence: HIGH

**F-2 [Medium] [security] Release keystore/secret handling: unquoted heredoc writes signing secrets into GITHUB_ENV; decode step interpolates a secret directly into `run:`**
- Files: `release.yml` (162–169, 179–190), `apk-build.yml` (293–301, 303–315)
- Evidence: `echo "${{ secrets.TG_RELEASE_KEYSTORE_BASE64 }}" | base64 --decode > ...`; `cat >> "$GITHUB_ENV" <<EOF ... EOF` (unquoted).
- Problem: An unquoted heredoc shell-expands secret values at write time: a keystore password containing `$` is silently corrupted; `$(` executes as command substitution. Secrets exported via GITHUB_ENV persist for every subsequent step of the job (gradle, aapt, the publish step's node process); direct `${{ secrets.* }}` script interpolation is the pattern the neighboring "Export signing environment" step explicitly tries to avoid.
- Fix: Use `<<'EOF'`; pass secrets as step-level `env:` into the single gradle invocation rather than job-persisted GITHUB_ENV; decode via `printf '%s' "$SECRET" | base64 -d` with a step env var.
- Confidence: HIGH

**F-3 [Medium] [security] Gradle wrapper integrity unverified: no `distributionSha256Sum`, no wrapper-validation step, committed wrapper jar scanignored**
- Files: `gradle/wrapper/gradle-wrapper.properties` (1–10), `fdroid/metadata/io.github.dukeoftoad.gallery.yml` (71–72)
- Problem: The wrapper jar is a committed binary no CI step validates; the distribution zip is downloaded without a pinned checksum — a tampered wrapper executes arbitrary code in every privileged build (including release.yml with signing secrets). `validateDistributionUrl` validates the URL only.
- Fix: Add `distributionSha256Sum`; add a gradle-wrapper-validation step to toad-setup.
- Confidence: HIGH

**F-4 [Medium] [privacy-gate] Gallery INTERNET gate is defanged by its own allowlist entry — it can no longer hard-fail, while its header/docs still claim it does**
- Files: `apps/gallery/build.gradle.kts` (748–757), `internet-permission-allowlist.txt` (7–10, 28)
- Evidence: The gate warn-logs when the merged manifest matches the allowlist; the allowlist carries exactly `android.permission.INTERNET` (r22); the file header and `docs/phase-2/privacy-gate.md` still promise a "hard build failure on merger injection."
- Problem: Since r22 any dependency-injected INTERNET matches the allowlist and only warns — the documented guarantee no longer exists. Remaining real guards: the source-manifest pin tests (PrivacyPolicyTest) and the edit app's allowlist-free gate.
- Fix: Compare the merged manifest against the source manifest (fail when INTERNET appears in merged but not in the app's own manifest, or count entries); update the header/privacy-gate doc to post-r22 semantics.
- Confidence: HIGH

**F-5 [Medium] [compliance] No AGPL-3.0 license text anywhere in the repository despite vendoring AGPL-3.0 Ente code**
- Files: `LICENSE` (root = GPL-3.0 only), `engine/NOTICE.md` (4), `engine/Cargo.toml` (20)
- Problem: The vendored crates are conveyed only with pointers — the AGPL-3.0 text itself is absent. AGPL §4/§6 notice obligations are met only descriptively; any redistribution of `engine/` ships the grant without the license text.
- Fix: Commit the canonical AGPL-3.0 text (e.g. `engine/LICENSE` or `COPYING.AGPL-3.0`) and reference it from NOTICE.md; state the effective per-artifact license offer (GPL-3.0 without engine, AGPL-3.0 for engine builds) in release notes/docs.
- Confidence: HIGH

**F-6 [Medium] [compliance] Bundled third-party model attribution missing from the shipped app; About credits misdescribe the vendored Ente engine as "planned" inspiration**
- Files: `apps/gallery/src/main/res/values/strings.xml` (358), `docs/licensing-and-attribution.md` (112–116, 69–72)
- Evidence: `about_credits_body` = "Inspired by Aves (metadata reference) and Ente (planned on-device AI)..."; the ledger says the U-2-Net/MI-GAN/Depth-Anything/YuNet models' MIT attribution "rides the About screen" — but no such attribution ships.
- Problem: The About screen contains no attribution or license text for the four bundled ONNX models (MIT's requirement explicitly acknowledged in the ledger but not implemented) and describes Ente as "planned" although `engine/` is vendored AGPL-3.0 code — inaccurate credit that also misleads F-Droid reviewers.
- Fix: Add a credits/licenses surface listing Ente as vendored AGPL-3.0 engine code with its pin, and the four models' origins + Apache-2.0/MIT texts; mirror in the edit app.
- Confidence: HIGH

**F-7 [Medium] [security] GPS-bearing Room catalogue is included in cloud backup and device transfer, contradicting the manifest's own "coordinates never leave the device" claim**
- Files: `res/xml/backup_rules.xml` (20–23), `res/xml/data_extraction_rules.xml` (19–28), `AndroidManifest.xml` (33–38), `CatalogueItemEntity.kt` (115–142)
- Evidence: Rules exclude only `vault` and `face-index`; the catalogue database (browsing history + EXIF-derived GPS) lives in `databases/`, which auto-backup and dataExtractionRules include by default.
- Problem: Photo location history leaves the device via Google cloud backup/device-to-device transfer — contradicting the manifest comment ("the coordinates never leave the device... ADR-0014") and the r16 location-consent design.
- Fix: Exclude the catalogue database from cloud-backup and device-transfer rules, or add an explicit ADR note justifying backup of coordinates.
- Confidence: HIGH

**F-8 [Medium] [bug] F-Droid gallery metadata is stale and its build recipe is broken by the r46 flavor matrix**
- File: `fdroid/metadata/io.github.dukeoftoad.gallery.yml` (64–72, 133–136)
- Evidence: Last Build entry is versionCode 37 at `v0.4.5-alpha-r45` with `output: .../apk/release/gallery-release-unsigned.apk`; `CurrentVersionCode: 37` vs `versionCode = 38` in `apps/gallery/build.gradle.kts:409`.
- Problem: r46 introduced the four-flavor matrix, so the unflavored output path no longer exists (workflows use `apk/basic/release/`); a next F-Droid build at HEAD fails output verification, and `AutoUpdateMode: Version` would derive a build from the stale recipe. The edit file's `CurrentVersion` is likewise one round behind.
- Fix: Add a new Build entry (versionCode 38, flavor-qualified output/task) and refresh CurrentVersion/CurrentVersionCode for both files.
- Confidence: HIGH

**F-9 [Medium] [robustness] The third-party reproducible-build verification recipe in the docs references tasks/paths removed by the r46 flavor matrix**
- File: `docs/phase-11/reproducible-builds.md` (63–71, 163–191)
- Evidence: Doc says `$ ./gradlew :apps:gallery:assembleRelease` and `apk/release/...` while the workflow correctly uses `assembleBasicRelease` and `apk/basic/release/` since r46.
- Problem: The doc is the canonical third-party/F-Droid verification recipe it advertises (§5); following it verbatim at HEAD fails.
- Fix: Update to the flavor-qualified tasks/paths and note the engine-family exclusion explicitly in §1/§4.
- Confidence: HIGH

**F-10 [Low] [security] Release pipeline has no CI-green or identity gate on tag push, and advertises an un-waited reproducibility verdict**
- File: `release.yml` (50–59, 117–157, 483–485)
- Problem: Any `v*` tag push publishes signed APKs regardless of the tag's CI status; release.yml never verifies the APK's package/versionCode/versionName against the checkout (apk-build.yml does via aapt2 for test builds); the release is published while the reproducible-build workflow races on the same tag, its verdict never consulted.
- Fix: Add a job-gated check (CI conclusion for the SHA, or an aapt2 badging identity gate) and link/gate the reproducible-build run before publish.
- Confidence: HIGH

**F-11 [Low] [security] No dependency-integrity/update machinery: no Gradle dependency verification, no Dependabot/Renovate, unused jitpack repo enabled**
- Files: `settings.gradle.kts` (14), `gradle/` (no `verification-metadata.xml`), `.github/` (no dependabot.yml)
- Problem: No checksum/signature verification of artifacts, no automated CVE/update scanning, and an unused extra repository widens resolution surface. No known-CVE versions identifiable statically in the pinned set — hygiene gap rather than active defect.
- Fix: Remove the jitpack repo (or scope it); run `--write-verification-metadata` and commit it; enable Dependabot/Renovate for gradle + GitHub Actions.
- Confidence: HIGH

**F-12 [Low] [robustness] Three of five published release APKs (engine/semantic/face) have no reproducibility measurement; engine `.so` NDK resolution is unpinned**
- Files: `reproducible-build.yml` (22–26), `toad-setup/action.yml` (90–101), `engine/scripts/build-android.sh` (87–101, 126–130), `release.yml` (210–211)
- Evidence: `NDK_HOME="$(ls -d "$SDK_ROOT"/ndk/* ... | sort -V | tail -1)"` — newest preinstalled NDK wins.
- Problem: The engine-family APKs ship without any determinism measurement, and their `libtoad_ml_jni.so` is cross-compiled with whatever NDK `ubuntu-latest` ships that day — not reproducible across time by construction. Exclusion honestly documented, but the release body's blanket reproducibility link covers only 2 of 5 artifacts.
- Fix: Extend reproducible-build to the engine flavors (pinning the NDK first), or qualify the release-body line to the basic+edit pair.
- Confidence: HIGH

**F-13 [Low] [refinement] Dead ProGuard keep rule for the removed Fossify dependency**
- File: `apps/gallery/proguard-rules.pro` (1–2)
- Evidence: `-keep class org.fossify.** { *; }` while `libs.versions.toml:141` states Fossify removed per ADR-0008.
- Problem: The rule keeps nothing and misdocuments the release build's shrinking posture — a reviewer will believe Fossify is still shipped.
- Fix: Delete the rule (or replace with a comment recording the removal).
- Confidence: HIGH

**F-14 [Low] [refinement] aapt2 resolved as "newest build-tools" instead of the pinned 36.0.0; release-time aapt check covers identity only, not permissions**
- Files: `apk-build.yml` (374–376, 514–516), `release.yml` (232–336)
- Evidence: `AAPT2="$(find "$SDK_ROOT/build-tools" -name aapt2 | sort -V | tail -1)"` while toad-setup installs exactly `build-tools;36.0.0`.
- Problem: Verification steps run whatever newest build-tools the runner image carries (silent toolchain drift); no workflow re-checks the final APK's permission set — the INTERNET guarantee at release time rests solely on the merged-manifest gate task, which for the gallery is warn-only (see F-4).
- Fix: Resolve aapt2 from the pinned `build-tools/36.0.0/` dir (glob fallback); add a release-stage `aapt2 dump badging | grep uses-permission` check for all five APKs.
- Confidence: HIGH

**Verified-clean in track F (checked, no findings):** explicit least-privilege `permissions:` in every workflow; no `pull_request_target`; untrusted inputs routed through env-var indirection rather than script interpolation; sha256-pinned model/ONNX-Runtime fetches; hard cert-fingerprint signature gate + packaging negative gate in release.yml; honest per-release reproducibility claim with a real verdict taxonomy; real merged-manifest gates in both apps; egress tests on an emulator; licensing documentation standard (pin, diffable adaptation ledger, one-way AGPL compatibility analysis) — the gaps are artifacts (F-5/F-6) and rot (F-8/F-9), not documentation honesty.

---

## 8. Cross-cutting observations

1. **The r48 concurrency fixes were scoped to the workers, not their callers.** F1 removed worker-side engine teardown; the UI-side teardown (C-2) and the reset-vs-queue race (C-3) remain. The `MlEngine` facade's per-instance state over a process-global native singleton (C-4) is the root shape — centralizing it would close C-2 and most of C-3's blast radius structurally.
2. **Two silent data-loss shapes live in the same scan flow** (E-1 null cursor + E-2 999-param limit): both turn a transient failure into permanent catalog loss or a permanent wedge, and both are cheap fixes (throw on null; chunk the lists).
3. **The "no INTERNET" privacy narrative needs a post-r22 pass** (D-10 across six files, F-4 gate semantics, F-7 backup of GPS data). The guarantee is now the allowlist + gate, not absence; docs/KDocs should say so consistently.
4. **The pin discipline is real but the artifact layer lags** (F-5 no AGPL text, F-6 no in-app attribution, F-8/F-9 F-Droid + reproducibility-doc rot). Small fixes, worth doing before the next public release round.

## 9. Suggested fix order (next work round)

1. **C-1** — one-line SHA fix unblocks the offline modelpack channel.
2. **E-1 + E-2** — null-cursor guard + chunked `IN` lists (both silent data-loss/wedge shapes).
3. **B-1** — `call_or_throw` on the two `put*` JNI exports (honors the crate's own contract; ~10 lines).
4. **C-2/C-3/C-4** — centralize engine lifecycle (singleton facade; drop UI-side `release()`; reset waits for both chains).
5. **D-3 + D-5** — blob fsync + biometric-keygen fallback parity (small, high-value durability/robustness).
6. **D-1 + D-2** — PIN throttling + FLAG_SECURE while unlocked.
7. **E-3** — healing-pass no-progress break + per-row failure marker (dedupes D-6).
8. **F-1/F-2/F-3** — SHA-pin actions, quoted heredocs/step-env secrets, wrapper validation.
9. **F-4/F-5/F-6/F-7** — gate semantics + AGPL text + model attribution + catalogue backup exclusion.
10. **E-4..E-10, C-5..C-8, A-1..A-3** — the scale/lifecycle/perf mediums, in whatever order the next round prioritizes.

---

*End of audit document. All findings cite the audited tree `main@7e9114b`; line numbers may drift as fixes land.*
