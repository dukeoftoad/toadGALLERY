# toad-ml-jni — the toadGALLERY JNI bridge over the vendored Ente engine

Net-new toadGALLERY code (r22, ADR-0020) — **not vendored from upstream**.
License: AGPL-3.0 (it links the AGPL-3.0 vendored crates into one binary;
see `docs/licensing-and-attribution.md` §2/§5 and `../NOTICE.md`).

## What this is

A JNI surface (`src/lib.rs`) over the vendored Ente ML stack:
`src/engine.rs` wires ente-ml's `init_ml_runtime` / `analyze_image` /
`run_clip_text` flow (mirroring the usage of ente-ml's own integration
tests); `src/store.rs` routes the face-index + CLIP vector-index store
entry points through `ente-photos-ml`'s own `MlDb` / `MlStore` functions
verbatim (no SQL of our own). Kotlin counterpart: `MlEngineJni` in
`apps/gallery/.../ml/engine/`. Symbols (round-tagged; the r22 rows are
the original four):

| JNI symbol (Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_) | Semantics |
| --- | --- |
| `nativeInit(modelsDir): Boolean` | init ONNX Runtime + load BOTH face models (detector + embedder) from the provisioned dir; idempotent; `false` leaves no partial state |
| `nativeEngineInfo(): String?` | one-line diagnostics (`ort=… providers=… models=…`); `null` while uninitialized; `models=yolov5s-face,mobilefacenet` face-only, `models=…,mobileclip-s2` once semantic (r26) |
| `nativeDetectFacesJpeg(bytes): FloatArray` | decode + detect + NMS; returns `N*5` floats `[x1,y1,x2,y2,score, …]` in SOURCE-image pixels, empty = no faces; `IllegalStateException` on not-initialized/failure |
| `nativeRelease(): Boolean` | frees the engine (and the store, and the semantic half); idempotent |
| `nativeDetectFaceEmbeddingsJpeg(bytes): FloatArray` (r23) | the face pipeline plus the 192-float MobileFaceNet embedding, flat stride 197; a non-192 runtime dim is an `IllegalStateException` |
| `nativeStoreOpen(dir): Boolean` (r23) | open the face-index store at `<dir>/ml-index.sqlite` (own migrations); idempotent |
| `nativeStorePutFace(fileId, embedding): Long` (r23) | insert one face row; a wrong dim throws `IllegalArgumentException` before any store access |
| `nativeStoreFaceCount(): Long` (r23) | total face rows (upstream's quality-filtered count) |
| `nativeStoreCluster(threshold): String` (r23) | upstream's full recluster; compact JSON summary `{"faces":N,"clusters":M,"sizes":[…]}` |
| `nativeStoreIndexedFaceFileIds(): LongArray` (r24) | files carrying current-version face rows (the resumability read) |
| `nativeStoreDeleteFacesForFiles(fileIds): Boolean` (r24) | per-file invalidation delete (rows + assignments); empty array is a no-op |
| `nativeStoreFaceClusters(): String` (r25) | the live cluster list for the People UI (JSON array, count DESC); counts read from the LIVE assignments table |
| `nativeStoreClusterFileIds(clusterId): LongArray` (r25) | a cluster's current file ids (upstream's own order) |
| `nativeStoreMergeFaceClusters(source, target): Boolean` (r25) | merge two people; `source==target` throws `IllegalArgumentException` before any mutation |
| `nativeStoreRemoveFacesFromCluster(clusterId, fileIds): Boolean` (r25) | remove a photo from a person (the split primitive); face rows stay |
| `nativeInitSemantic(modelsDir): Boolean` (r26) | register + warm up the pinned MobileCLIP-S2 image/text encoders + BPE vocab ON TOP of the face engine; idempotent; a failed warm-up restores a consistent FACE-ONLY engine |
| `nativeClipImageEmbeddingJpeg(bytes): FloatArray` (r26) | the 512-float L2-normalized CLIP image embedding; a non-512 runtime dim is an `IllegalStateException` |
| `nativeClipTextEmbedding(text): FloatArray` (r26) | the 512-float CLIP text embedding (upstream's `run_clip_text` + BPE vocab); same boundary dim check |
| `nativeStorePutClip(fileId, embedding): Boolean` (r26) | upsert one CLIP row (SQLite + vecdb together); a wrong dim throws `IllegalArgumentException` before any store access |
| `nativeStoreDeleteClipsForFiles(fileIds): Boolean` (r26) | per-file CLIP invalidation delete (rows + vecdb keys); empty array is a no-op |
| `nativeStoreClipIndexedFileIds(): LongArray` (r26) | files carrying current-version CLIP rows, sorted ascending (the Magic-Search resumability read) |
| `nativeStoreClipCount(): Long` (r26) | total CLIP rows |
| `nativeStoreSearchClips(query, limit): String` (r26) | top-K vector search over the CLIP index; JSON `[{"file_id":N,"distance":F},…]` in the vecdb's own order, never re-sorted |
| `nativeStoreFillClipIndex(): String` (r26) | upstream's non-forced index fill; JSON report `{"outcome":"Completed"\|"AlreadyFilled"\|"Superseded","rows":N,"indexed":M,"skipped":K,"resumed":bool}` |
| `nativeStoreClose(): Boolean` (r23) | close the store (flush the vecdb handles); idempotent |

Every entry point is `catch_unwind`-guarded (panics surface as
`IllegalStateException`, never unwind across JNI); the engine lives in a
static `Mutex` — inference (face AND semantic) is serialized; the store
has its own static `Mutex` — every store operation (face AND clip) is
serialized.

r26 contract notes:

* The semantic session stacks on a live engine session:
  `nativeInitSemantic` without `nativeInit` is an `IllegalStateException`,
  and `nativeRelease` tears the semantic half with the engine (a later
  `nativeInit` + `nativeInitSemantic` rebuilds it).
* The CLIP store functions ride the SAME open-store state machine as the
  face functions (clip rows live in the same `ml-index.sqlite`); every
  one of them throws `IllegalStateException` before any store access
  when the store is closed.
* The r26 JSON payloads (`nativeStoreSearchClips`,
  `nativeStoreFillClipIndex`) are hand-rolled with `format!` + the r25
  `json_escape` discipline — no serde dependency anywhere in this crate.

## ONNX Runtime dylib discovery (load-dynamic)

The vendored crates link `ort` 2.0.0-rc.15 with `load-dynamic` on Android:
no ORT code is compiled into `libtoad_ml_jni.so`; `libonnxruntime.so` is
dlopen'd at runtime. Discovery order:

1. **Android (the only supported target)**: `dlopen("libonnxruntime.so")`
   by soname — the dynamic linker resolves it from the app's native
   library directory, where `engine/scripts/build-android.sh` + Gradle
   `-PwithEngine` package it next to `libtoad_ml_jni.so` (APK
   `lib/arm64-v8a/`). No path configuration is needed.
2. `ORT_DYLIB_PATH` (ort's documented override) as the non-Android /
   diagnostics fallback.

The packaged `libonnxruntime.so` is Microsoft's official 1.31.0 Android
build (the exact version the rc.15 `api-31` bindings expect), fetched and
sha256-pinned by `engine/scripts/build-android.sh` — see that script.

## Building for Android

```
bash engine/scripts/build-android.sh          # arm64-v8a
bash engine/scripts/build-android.sh --all    # + x86_64
./gradlew :apps:gallery:assembleDebug -PwithEngine
```

Upstream's Android rustflags (16 KB page-size link args) ride via
`engine/.cargo/config.toml` — see `../NOTICE.md` for provenance.
