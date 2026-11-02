# toad-ml-jni — the toadGALLERY JNI bridge over the vendored Ente engine

Net-new toadGALLERY code (r22, ADR-0020) — **not vendored from upstream**.
License: AGPL-3.0 (it links the AGPL-3.0 vendored crates into one binary;
see `docs/licensing-and-attribution.md` §2/§5 and `../NOTICE.md`).

## What this is

A four-function JNI surface (`src/lib.rs`) over ente-ml's face pipeline
(`src/engine.rs` wires ente-ml's `init_ml_runtime` / `analyze_image` flow,
mirroring the usage of ente-ml's own integration tests). Kotlin counterpart:
`MlEngineJni` in `apps/gallery/.../ml/engine/`. Symbols:

| JNI symbol (Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_) | Semantics |
| --- | --- |
| `nativeInit(modelsDir): Boolean` | init ONNX Runtime + load BOTH face models (detector + embedder) from the provisioned dir; idempotent; `false` leaves no partial state |
| `nativeEngineInfo(): String?` | one-line diagnostics (`ort=… providers=… models=…`); `null` while uninitialized |
| `nativeDetectFacesJpeg(bytes): FloatArray` | decode + detect + NMS; returns `N*5` floats `[x1,y1,x2,y2,score, …]` in SOURCE-image pixels, empty = no faces; `IllegalStateException` on not-initialized/failure |
| `nativeRelease(): Boolean` | frees the engine; idempotent |

Every entry point is `catch_unwind`-guarded (panics surface as
`IllegalStateException`, never unwind across JNI); the engine lives in a
static `Mutex` — inference is serialized.

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
