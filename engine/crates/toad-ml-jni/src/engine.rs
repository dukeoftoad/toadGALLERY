//! Engine state and the ente-ml adapter logic behind the JNI surface.
//!
//! This is a thin adapter over the vendored engine's public API
//! (`ente_ml::indexing`): it owns no ONNX sessions of its own and follows the
//! init/inference flow of ente-ml's own integration tests
//! (`ente-ml/tests/support/ml_indexing.rs`) — `init_ml_runtime` /
//! `analyze_image` / `release_ml_runtime`. r26 adds the semantic (CLIP)
//! half over the same flow: `run_clip` in `analyze_image` and upstream's
//! standalone `run_clip_text`.

use std::{
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, MutexGuard, OnceLock, PoisonError},
};

use ente_image::image_compression::{EncodedImageFormat, encode_rgb};
use ente_ml::{
    ModelPaths,
    indexing::{
        AnalyzeImageRequest, AnalyzeImageResult, ImageSource, RunClipTextRequest, analyze_image,
        init_ml_runtime, release_ml_runtime, run_clip_text,
    },
};

/// Pinned face-detector model: file name and exact byte size from the
/// vendored ente-ml model catalog (`crates/ente-ml/src/assets.rs`,
/// `ModelAssetSpec::FaceDetection`) at the vendored commit. The Kotlin layer
/// (`ModelManifest`) pins the same file including its SHA-256.
pub(crate) const FACE_DETECTOR_FILE_NAME: &str = "yolov5s_face_640_640_static_b1.onnx";
pub(crate) const FACE_DETECTOR_SIZE_BYTES: u64 = 32_355_091;

/// Pinned face-embedding model, same catalog (`ModelAssetSpec::FaceEmbedding`).
///
/// The embedder is registered with the runtime even though the spike only
/// *returns* detections: ente-ml's `analyze_image` face pipeline runs
/// alignment + embedding for every detected face, so a working embedder file
/// is required for detection results to be produced at all.
pub(crate) const FACE_EMBEDDER_FILE_NAME: &str = "mobilefacenet_portable_static_b1.onnx";
pub(crate) const FACE_EMBEDDER_SIZE_BYTES: u64 = 5_278_803;

/// Pinned CLIP image-encoder model, same catalog
/// (`ModelAssetSpec::ClipImage`): the MobileCLIP-S2 GELU image tower whose
/// 512-float L2-normalized output is the r26 image embedding. The largest
/// asset in the catalog (~137 MiB).
pub(crate) const CLIP_IMAGE_FILE_NAME: &str = "mobileclip_s2_image_gelu_opset20.onnx";
pub(crate) const CLIP_IMAGE_SIZE_BYTES: u64 = 143_057_352;

/// Pinned CLIP text-encoder model, same catalog
/// (`ModelAssetSpec::ClipText`, first file): the quantized MobileCLIP-S2
/// text tower behind the r26 text embedding (the query side of Magic
/// Search).
pub(crate) const CLIP_TEXT_FILE_NAME: &str = "mobileclip_s2_text_opset18_quant.onnx";
pub(crate) const CLIP_TEXT_SIZE_BYTES: u64 = 67_144_712;

/// Pinned CLIP BPE vocabulary, same catalog (`ModelAssetSpec::ClipText`,
/// second file): the tokenizer the text encoder consumes. Upstream's
/// `ModelPaths` has no vocab slot — `RunClipTextRequest` carries the vocab
/// path explicitly — so [`init_semantic`] validates it here and the engine
/// state remembers it.
pub(crate) const CLIP_VOCAB_FILE_NAME: &str = "bpe_simple_vocab_16e6.txt";
pub(crate) const CLIP_VOCAB_SIZE_BYTES: u64 = 3_194_984;

/// Short model labels used by the engine info line.
const FACE_MODELS_LABEL: &str = "yolov5s-face,mobilefacenet";

/// Short model labels used by the engine info line once the semantic half
/// is installed on a face session (r26): the face pair plus the
/// MobileCLIP-S2 pair.
const SEMANTIC_MODELS_LABEL: &str = "yolov5s-face,mobilefacenet,mobileclip-s2";

/// Short model labels for a SEMANTIC-ONLY session (r39): the MobileCLIP-S2
/// pair alone, the honest shape of an engine whose face halves were never
/// installed (see [`init_semantic`]).
const SEMANTIC_ONLY_MODELS_LABEL: &str = "mobileclip-s2";

/// The `ort` crate version pinned by the workspace (`=2.0.0-rc.15`) — the
/// Rust bindings that dlopen ONNX Runtime. Reported for diagnostics; see
/// `README.md` for the dylib discovery order.
const ORT_BINDINGS_VERSION: &str = "2.0.0-rc.15";

/// ente-ml's default execution-provider plan for this platform under the
/// engine's default configuration (WebGPU stays disabled unless explicitly
/// enabled via `set_ml_execution_config`): Android attempts XNNPACK with CPU
/// fallback, other platforms run CPU.
#[cfg(target_os = "android")]
const PROVIDERS_LABEL: &str = "cpu,xnnpack";
#[cfg(not(target_os = "android"))]
const PROVIDERS_LABEL: &str = "cpu";

/// `AnalyzeImageRequest.file_id` for this bridge. The ente-ml face pipeline
/// only uses it to derive face-id strings, which this bridge discards; the
/// constant keeps the request well-formed.
const FILE_ID: i64 = 0;

/// Message used when detection or release runs before a successful init.
const NOT_INITIALIZED: &str = "engine is not initialized; call nativeInit(modelsDir) first";

/// Message used when a semantic call runs before a successful
/// [`init_semantic`].
const SEMANTIC_NOT_INITIALIZED: &str =
    "semantic models are not initialized; call nativeInitSemantic(modelsDir) first";

/// Message used when a FACE call runs on a semantic-only session (r39):
/// the engine exists, its semantic half may even be ready, but the face
/// models were never installed. The dedicated message beats upstream's
/// generic missing-path error at the boundary, so the caller learns the
/// recovery (install/init the face half) instead of a broken-contract
/// shape that is actually the session's honest shape.
const FACE_MODELS_NOT_INSTALLED: &str =
    "face models are not installed in this engine session; call nativeInit(modelsDir) first";

/// The face-embedding dimension on the r23 wire: upstream MobileFaceNet
/// output (the vendored clustering's `CLUSTER_CENTROID_DIMENSIONS`).
pub(crate) const FACE_EMBEDDING_DIM: usize = 192;

/// The CLIP embedding dimension on the r26 wire: upstream MobileCLIP-S2
/// output (the vendored store's `CLIP_EMBEDDING_DIMENSIONS`).
pub(crate) const CLIP_EMBEDDING_DIM: usize = 512;

/// The r23 embeddings wire stride: 5 box floats + [`FACE_EMBEDDING_DIM`].
pub(crate) const EMBEDDING_STRIDE: usize = 5 + FACE_EMBEDDING_DIM;

/// Warm-up image geometry: a tiny featureless gray image is enough to force
/// the full decode → YOLO preprocessing → detector inference path.
const WARMUP_WIDTH: u32 = 8;
const WARMUP_HEIGHT: u32 = 8;
const WARMUP_GRAY_LEVEL: u8 = 128;
const WARMUP_JPEG_QUALITY: u8 = 75;

/// The initialized engine. Once installed, either half may arrive first
/// and the other upgrades it in place (r39's symmetric sessions): the r26
/// order (`init` then `init_semantic`) still works, and so does the r39
/// order (`init_semantic` first — a SEMANTIC-ONLY session whose face paths
/// are blank — then `init` adding the face half). Both upgrades mutate the
/// state in place — the combined `ModelPaths`, the vocab path, the
/// semantic-ready flag and the info line — always under the engine lock.
struct EngineState {
    model_paths: ModelPaths,
    /// The validated BPE vocab path (r26): set by [`init_semantic`], absent
    /// in a face-only engine. Upstream's `ModelPaths` has no vocab slot
    /// (`RunClipTextRequest` carries the vocab path explicitly), so the
    /// semantic half remembers it here.
    clip_vocab_path: Option<String>,
    semantic_ready: bool,
    info: String,
}

impl EngineState {
    /// Whether the FACE half is installed: the vendored runtime configures
    /// a model slot iff its path is non-blank, so the detector path IS the
    /// truth (a semantic-only session carries blank face paths by
    /// construction; a face session always carries a non-blank one —
    /// [`init`] validates the file before installing it).
    fn faces_ready(&self) -> bool {
        !self.model_paths.face_detection.trim().is_empty()
    }
}

fn engine_cell() -> &'static Mutex<Option<EngineState>> {
    static ENGINE: OnceLock<Mutex<Option<EngineState>>> = OnceLock::new();
    ENGINE.get_or_init(|| Mutex::new(None))
}

/// Locks the engine state. The lock is held for the whole of init, detection
/// and release, serializing inference across calling threads. A panic caught
/// at the JNI boundary can poison the lock; the guarded state is a plain
/// `Option` that stays consistent, so recovery is safe (the same recovery
/// ente-ml's own global runtime uses).
fn lock() -> MutexGuard<'static, Option<EngineState>> {
    engine_cell().lock().unwrap_or_else(PoisonError::into_inner)
}

/// Initializes the FACE half of the engine against `models_dir`.
///
/// Both pinned face model files must exist with their exact catalog byte
/// sizes. Both are registered with ente-ml's global runtime via
/// `init_ml_runtime`; a warm-up `analyze_image` call then forces the dynamic
/// ONNX Runtime load and builds + runs the detector session, so init fails
/// fast (and leaves no engine installed) when the runtime cannot come up.
///
/// Idempotent per half (r39): an engine whose face half is already
/// installed succeeds without reloading anything. A SEMANTIC-ONLY session
/// (installed by [`init_semantic`], r39) is UPGRADED in place — the mirror
/// image of the r26 `init_semantic` upgrade: the face files are validated,
/// the COMBINED face + CLIP paths are re-registered (the semantic sessions
/// survive it, same paths), and the face warm-up runs; on warm-up failure
/// the runtime is released and the PREVIOUS semantic-only paths
/// re-registered, leaving the state untouched (face calls keep failing with
/// [`FACE_MODELS_NOT_INSTALLED`], semantic calls keep working) — the exact
/// recovery discipline the r26 upgrade pins, with the halves swapped.
pub(crate) fn init(models_dir: &str) -> Result<(), String> {
    let mut engine = lock();
    if let Some(state) = engine.as_ref() {
        if state.faces_ready() {
            return Ok(());
        }
    }

    let models_dir = Path::new(models_dir);
    let detector_path = validate_model_file(
        models_dir,
        FACE_DETECTOR_FILE_NAME,
        FACE_DETECTOR_SIZE_BYTES,
    )?;
    let embedder_path = validate_model_file(
        models_dir,
        FACE_EMBEDDER_FILE_NAME,
        FACE_EMBEDDER_SIZE_BYTES,
    )?;

    // The r39 upgrade case (semantic-only session present): the combined
    // paths keep the CLIP halves the state already carries. In the fresh
    // case the previous paths are the all-blank default, so the combined
    // paths are the face-only ones — one code path, both truths.
    let previous_paths = engine
        .as_ref()
        .map(|state| state.model_paths.clone())
        .unwrap_or_default();
    let model_paths = ModelPaths {
        face_detection: detector_path.to_string_lossy().into_owned(),
        face_embedding: embedder_path.to_string_lossy().into_owned(),
        ..previous_paths.clone()
    };

    // Registers the face models with ente-ml's process-global runtime (and,
    // in the r39 upgrade case, re-registers the CLIP halves with them — the
    // same paths, so the already-built sessions survive). The ONNX sessions
    // themselves load lazily on first use.
    init_ml_runtime(model_paths.clone());

    // Warm-up: forces the dynamic ONNX Runtime load (see README.md for the
    // discovery order), parses + optimizes the pinned detector graph, and
    // runs one inference over an inert image. The embedder session is built
    // lazily by ente-ml on the first detected face; its file was validated
    // above (and its SHA-256 by the Kotlin layer at install time).
    let warmup_jpeg = warmup_jpeg().map_err(|error| format!("encode warm-up image: {error}"))?;
    if let Err(error) = analyze_image(warmup_request(&model_paths, warmup_jpeg)) {
        release_ml_runtime();
        // Only an upgrade has a previous registration to restore; the fresh
        // case leaves the runtime entirely unregistered (the pre-r39
        // behavior — nothing was installed before this call).
        if engine.is_some() {
            init_ml_runtime(previous_paths);
        }
        return Err(format!("engine warm-up failed: {error}"));
    }

    // The tail is split on `engine.is_some()` BEFORE any mutable borrow:
    // the upgrade path mutates in place under one short-lived borrow, the
    // fresh path installs a whole new state with NO borrow outstanding
    // (a `match engine.as_mut()` with a `*engine = …` in the None arm is
    // the classic double-borrow error --- the scrutinee's borrow outlives
    // the arm).
    if engine.is_some() {
        // The r39 upgrade: the face halves join the state's paths; the
        // semantic half's flags (vocab path, semantic_ready) are untouched,
        // and the info line names both halves.
        if let Some(state) = engine.as_mut() {
            state.model_paths = model_paths;
            state.info = format_engine_info(true, state.semantic_ready);
        }
    } else {
        *engine = Some(EngineState {
            model_paths,
            clip_vocab_path: None,
            semantic_ready: false,
            info: format_engine_info(true, false),
        });
    }
    Ok(())
}

/// Initializes the semantic (CLIP) half against `models_dir` — on an
/// already-initialized engine (r26) or FROM SCRATCH as a SEMANTIC-ONLY
/// session (r39, the round that drops the FACE-model requirement).
///
/// Idempotent per half: an engine whose semantic half is ready succeeds
/// without reloading anything. The three pinned files (CLIP image encoder,
/// CLIP text encoder, BPE vocab) must exist with their exact catalog byte
/// sizes. Then:
///
/// - **On a face-only engine (the r26 shape):** the combined face + CLIP
///   `ModelPaths` is registered with ente-ml's global runtime (replacing
///   the face-only registration — the face sessions survive it, same
///   paths) and a warm-up `analyze_image` call with `run_clip: true`
///   builds + runs the CLIP image session over the same inert JPEG the
///   face warm-up uses. Failure recovery, honestly: a failed warm-up
///   tears the whole runtime down (`release_ml_runtime`) and re-registers
///   the previous face-only paths, leaving the engine in a consistent
///   FACE-ONLY state — semantic calls keep failing with
///   [`SEMANTIC_NOT_INITIALIZED`] and the face sessions rebuild lazily on
///   the next face call. The `EngineState` itself is untouched on that
///   leg, so `semantic_ready` stays `false` and the info line stays the
///   face-only one.
/// - **With NO engine installed (the r39 shape):** a SEMANTIC-ONLY
///   `ModelPaths` (the three CLIP paths filled, the face paths blank —
///   the vendored runtime configures exactly the slots whose paths are
///   non-empty, so this is a first-class upstream session shape) is
///   registered and the same CLIP warm-up runs. A failed warm-up releases
///   the runtime and leaves NO engine installed — there was no previous
///   state to restore. Face calls on the installed session fail with the
///   dedicated [`FACE_MODELS_NOT_INSTALLED`] message until an `init`
///   upgrades the session with the face half.
///
/// A validation failure (missing or wrong-size model file) fires BEFORE
/// any registration and leaves the runtime entirely untouched, in both
/// shapes.
pub(crate) fn init_semantic(models_dir: &str) -> Result<(), String> {
    let mut engine = lock();
    if let Some(state) = engine.as_ref() {
        if state.semantic_ready {
            return Ok(());
        }
    }
    let previous_paths = engine
        .as_ref()
        .map(|state| state.model_paths.clone())
        .unwrap_or_default();

    let models_dir = Path::new(models_dir);
    let clip_image_path =
        validate_model_file(models_dir, CLIP_IMAGE_FILE_NAME, CLIP_IMAGE_SIZE_BYTES)?;
    let clip_text_path =
        validate_model_file(models_dir, CLIP_TEXT_FILE_NAME, CLIP_TEXT_SIZE_BYTES)?;
    let vocab_path = validate_model_file(models_dir, CLIP_VOCAB_FILE_NAME, CLIP_VOCAB_SIZE_BYTES)?;

    // The combined paths keep whatever face halves the state already
    // carries: the r26 upgrade fills CLIP over the face paths, the r39
    // fresh case fills CLIP over the all-blank default (a semantic-only
    // session) — one code path, both truths.
    let combined = ModelPaths {
        clip_image: clip_image_path.to_string_lossy().into_owned(),
        clip_text: clip_text_path.to_string_lossy().into_owned(),
        ..previous_paths.clone()
    };

    // Registers the runtime with the combined paths. Existing sessions
    // survive the re-registration (same paths); the CLIP image session is
    // configured here and loads below.
    init_ml_runtime(combined.clone());

    // Warm-up: forces the CLIP image session to build + run over an
    // inert image (ente-ml's CLIP preprocessing rescales it to the
    // encoder's input size), so a broken semantic runtime fails HERE
    // instead of on the first real embedding. On failure the recovery
    // above restores the previous registration (face-only paths, or none
    // at all in the r39 fresh case).
    let warmup_jpeg = warmup_jpeg().map_err(|error| format!("encode warm-up image: {error}"))?;
    if let Err(error) = analyze_image(semantic_warmup_request(&combined, warmup_jpeg)) {
        release_ml_runtime();
        if engine.is_some() {
            init_ml_runtime(previous_paths);
        }
        return Err(format!("semantic warm-up failed: {error}"));
    }

    // The tail is split on `engine.is_some()` BEFORE any mutable borrow
    // (the `init` tail's discipline: the upgrade mutates in place under
    // one short-lived borrow; the fresh install has no borrow outstanding).
    if engine.is_some() {
        // The r26 upgrade: the CLIP halves join the face paths; the vocab
        // path is remembered and the info line names both halves.
        if let Some(state) = engine.as_mut() {
            state.model_paths = combined;
            state.clip_vocab_path = Some(vocab_path.to_string_lossy().into_owned());
            state.semantic_ready = true;
            state.info = format_engine_info(state.faces_ready(), true);
        }
    } else {
        // The r39 fresh case: the SEMANTIC-ONLY session (blank face
        // paths by construction — `previous_paths` was the default).
        *engine = Some(EngineState {
            model_paths: combined,
            clip_vocab_path: Some(vocab_path.to_string_lossy().into_owned()),
            semantic_ready: true,
            info: format_engine_info(false, true),
        });
    }
    Ok(())
}

/// The engine info line, or `None` while uninitialized.
pub(crate) fn engine_info() -> Option<String> {
    lock().as_ref().map(|state| state.info.clone())
}

/// Releases the engine. Returns `true` when an engine existed and was freed,
/// `false` when none did; the engine always ends up uninitialized.
///
/// The whole `EngineState` is taken, so the r26 semantic half resets with
/// it: after a release, semantic calls fail with [`NOT_INITIALIZED`] (the
/// engine check dominates — there is no engine left to be semantic) until
/// a fresh `init` and/or `init_semantic` (either order, r39).
pub(crate) fn release() -> bool {
    let mut engine = lock();
    if engine.take().is_none() {
        return false;
    }
    release_ml_runtime();
    true
}

/// Runs face detection over JPEG bytes. Returns the flat
/// `[x1, y1, x2, y2, score, …]` buffer produced by
/// [`detections_to_source_pixel_buffer`].
///
/// Requires the FACE half (r39): on a semantic-only session this is the
/// [`FACE_MODELS_NOT_INSTALLED`] error, checked at the boundary before
/// any request is built.
pub(crate) fn detect_faces_jpeg(jpeg_bytes: Vec<u8>) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
    if !state.faces_ready() {
        return Err(FACE_MODELS_NOT_INSTALLED.to_string());
    }
    let result = analyze_image(AnalyzeImageRequest {
        file_id: FILE_ID,
        source: ImageSource::Bytes(jpeg_bytes),
        run_faces: true,
        run_clip: false,
        run_pets: false,
        generate_face_crops: false,
        model_paths: state.model_paths.clone(),
    })
    .map_err(|error| format!("face detection failed: {error}"))?;
    Ok(detections_to_source_pixel_buffer(&result))
}

/// Flattens the ente-ml face results into the JNI wire format
/// `[x1, y1, x2, y2, score, …]`.
///
/// ente-ml reports `box_xyxy` normalized to `[0, 1]` relative to the decoded
/// source image (see `ente-ml/src/preprocess.rs`, `YoloInput::correct_box`);
/// the boxes are scaled here by `decoded_image_size` into source-image pixel
/// coordinates. The score is the upstream detection confidence (already in
/// `[0, 1]`; the detector only emits faces above its 0.5 threshold) and is
/// passed through unchanged.
pub(crate) fn detections_to_source_pixel_buffer(result: &AnalyzeImageResult) -> Vec<f32> {
    let Some(faces) = result.faces.as_ref() else {
        return Vec::new();
    };
    let width = result.decoded_image_size.width as f32;
    let height = result.decoded_image_size.height as f32;
    let mut buffer = Vec::with_capacity(faces.len() * 5);
    for face in faces {
        let [x_min, y_min, x_max, y_max] = face.detection.box_xyxy;
        buffer.extend_from_slice(&[
            x_min * width,
            y_min * height,
            x_max * width,
            y_max * height,
            face.detection.score,
        ]);
    }
    buffer
}

/// Runs face detection + embedding over JPEG bytes. Returns the flat
/// `[x1, y1, x2, y2, score, e0..e191, …]` buffer produced by
/// [`embeddings_to_source_pixel_buffer`].
///
/// Requires the FACE half (r39): on a semantic-only session this is the
/// [`FACE_MODELS_NOT_INSTALLED`] error, checked at the boundary before
/// any request is built.
pub(crate) fn detect_face_embeddings_jpeg(jpeg_bytes: Vec<u8>) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
    if !state.faces_ready() {
        return Err(FACE_MODELS_NOT_INSTALLED.to_string());
    }
    let result = analyze_image(AnalyzeImageRequest {
        file_id: FILE_ID,
        source: ImageSource::Bytes(jpeg_bytes),
        run_faces: true,
        run_clip: false,
        run_pets: false,
        generate_face_crops: false,
        model_paths: state.model_paths.clone(),
    })
    .map_err(|error| format!("face embedding failed: {error}"))?;
    embeddings_to_source_pixel_buffer(&result)
}

/// Flattens the ente-ml face results into the r23 JNI wire format
/// `[x1, y1, x2, y2, score, e0..e191, …]` (row-major, stride
/// [`EMBEDDING_STRIDE`]), same box/score mapping as
/// [`detections_to_source_pixel_buffer`].
///
/// The embedding dimension is validated HERE, at the Rust boundary: a
/// runtime model whose output dim differs from [`FACE_EMBEDDING_DIM`] is a
/// broken engine contract and surfaces as an error (Kotlin throws
/// `IllegalStateException`), never as silently corrupt stride math.
pub(crate) fn embeddings_to_source_pixel_buffer(
    result: &AnalyzeImageResult,
) -> Result<Vec<f32>, String> {
    let Some(faces) = result.faces.as_ref() else {
        return Ok(Vec::new());
    };
    let width = result.decoded_image_size.width as f32;
    let height = result.decoded_image_size.height as f32;
    let mut buffer = Vec::with_capacity(faces.len() * EMBEDDING_STRIDE);
    for face in faces {
        if face.embedding.len() != FACE_EMBEDDING_DIM {
            return Err(format!(
                "face embedding has unexpected dimension: expected {FACE_EMBEDDING_DIM}, found {}",
                face.embedding.len()
            ));
        }
        let [x_min, y_min, x_max, y_max] = face.detection.box_xyxy;
        buffer.extend_from_slice(&[
            x_min * width,
            y_min * height,
            x_max * width,
            y_max * height,
            face.detection.score,
        ]);
        buffer.extend_from_slice(&face.embedding);
    }
    Ok(buffer)
}

/// Runs the CLIP image encoder over JPEG bytes (r26). Returns the
/// 512-float L2-normalized MobileCLIP-S2 embedding produced by
/// [`clip_image_embedding_from_result`].
///
/// Requires an installed engine WITH its semantic half ([`init_semantic`]);
/// otherwise the call is the [`NOT_INITIALIZED`] /
/// [`SEMANTIC_NOT_INITIALIZED`] error. The request runs the clip pipeline
/// only (`run_faces: false`), exactly like the semantic warm-up.
pub(crate) fn clip_image_embedding_jpeg(jpeg_bytes: Vec<u8>) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
    if !state.semantic_ready {
        return Err(SEMANTIC_NOT_INITIALIZED.to_string());
    }
    let result = analyze_image(AnalyzeImageRequest {
        file_id: FILE_ID,
        source: ImageSource::Bytes(jpeg_bytes),
        run_faces: false,
        run_clip: true,
        run_pets: false,
        generate_face_crops: false,
        model_paths: state.model_paths.clone(),
    })
    .map_err(|error| format!("clip image embedding failed: {error}"))?;
    clip_image_embedding_from_result(&result)
}

/// Runs the CLIP text encoder over `text` (r26): upstream's own
/// `run_clip_text` with the pinned text-model + BPE-vocab paths the
/// semantic half registered, returning the 512-float embedding
/// (dimension-validated at the boundary by [`validate_clip_embedding`]).
///
/// Same guards as [`clip_image_embedding_jpeg`]: an installed engine with
/// its semantic half, or the [`NOT_INITIALIZED`] /
/// [`SEMANTIC_NOT_INITIALIZED`] error.
pub(crate) fn clip_text_embedding(text: &str) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
    if !state.semantic_ready {
        return Err(SEMANTIC_NOT_INITIALIZED.to_string());
    }
    let vocab_path = state
        .clip_vocab_path
        .clone()
        .ok_or_else(|| SEMANTIC_NOT_INITIALIZED.to_string())?;
    let result = run_clip_text(RunClipTextRequest {
        text: text.to_string(),
        model_path: state.model_paths.clip_text.clone(),
        vocab_path,
    })
    .map_err(|error| format!("clip text embedding failed: {error}"))?;
    validate_clip_embedding(&result.embedding)?;
    Ok(result.embedding)
}

/// Maps an ente-ml analysis result to the r26 CLIP wire: the
/// L2-normalized 512-float image embedding, verbatim.
///
/// The dimension is validated HERE, at the Rust boundary, mirroring
/// [`embeddings_to_source_pixel_buffer`]'s face check: a runtime model
/// whose output dim differs from [`CLIP_EMBEDDING_DIM`] is a broken engine
/// contract and surfaces as an error (Kotlin throws
/// `IllegalStateException`), never as a silently truncated vector. A
/// missing `clip` half — `clip: None` after a `run_clip: true` request —
/// is the same broken-contract shape.
pub(crate) fn clip_image_embedding_from_result(
    result: &AnalyzeImageResult,
) -> Result<Vec<f32>, String> {
    let Some(clip) = result.clip.as_ref() else {
        return Err("clip embedding is missing from the analysis result".to_string());
    };
    validate_clip_embedding(&clip.embedding)?;
    Ok(clip.embedding.clone())
}

/// The r26 boundary check, shared by the image and text paths: a CLIP
/// embedding whose dimension drifted off [`CLIP_EMBEDDING_DIM`] is a
/// broken engine contract (the same discipline the face-embedding check
/// in [`embeddings_to_source_pixel_buffer`] applies).
fn validate_clip_embedding(embedding: &[f32]) -> Result<(), String> {
    if embedding.len() != CLIP_EMBEDDING_DIM {
        return Err(format!(
            "clip embedding has unexpected dimension: expected {CLIP_EMBEDDING_DIM}, found {}",
            embedding.len()
        ));
    }
    Ok(())
}

fn format_engine_info(faces: bool, semantic: bool) -> String {
    let models = match (faces, semantic) {
        (true, true) => SEMANTIC_MODELS_LABEL,
        (true, false) => FACE_MODELS_LABEL,
        // The r39 semantic-only session: the CLIP pair alone.
        (false, true) => SEMANTIC_ONLY_MODELS_LABEL,
        // Unreachable from the state machine (an EngineState always carries
        // at least one installed half) and never requested by callers; the
        // blank-arm answer keeps the function total instead of panicking.
        (false, false) => "",
    };
    format!("ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={models}")
}

fn warmup_request(model_paths: &ModelPaths, warmup_jpeg: Vec<u8>) -> AnalyzeImageRequest {
    AnalyzeImageRequest {
        file_id: FILE_ID,
        source: ImageSource::Bytes(warmup_jpeg),
        run_faces: true,
        run_clip: false,
        run_pets: false,
        generate_face_crops: false,
        model_paths: model_paths.clone(),
    }
}

/// The r26 semantic warm-up request: the CLIP image pipeline ONLY
/// (`run_clip: true`, `run_faces: false`), so the warm-up builds + runs
/// the CLIP image session without touching the face path.
fn semantic_warmup_request(model_paths: &ModelPaths, warmup_jpeg: Vec<u8>) -> AnalyzeImageRequest {
    AnalyzeImageRequest {
        file_id: FILE_ID,
        source: ImageSource::Bytes(warmup_jpeg),
        run_faces: false,
        run_clip: true,
        run_pets: false,
        generate_face_crops: false,
        model_paths: model_paths.clone(),
    }
}

/// Encodes the featureless warm-up image as JPEG via ente-image's own
/// encoder, so the warm-up exercises the same decode path real detection
/// requests use. A flat gray image guarantees the detector finds no face,
/// keeping the lazy alignment/embedding stages out of the warm-up.
fn warmup_jpeg() -> ente_image::ImageResult<Vec<u8>> {
    let rgb = vec![WARMUP_GRAY_LEVEL; (WARMUP_WIDTH * WARMUP_HEIGHT * 3) as usize];
    encode_rgb(
        &rgb,
        WARMUP_WIDTH,
        WARMUP_HEIGHT,
        EncodedImageFormat::Jpeg {
            quality: WARMUP_JPEG_QUALITY,
        },
    )
}

/// Resolves `dir/file_name` and validates that it is a regular file with the
/// exact pinned byte size, returning the resolved path. This is the cheap
/// first-line validation from the model catalog; the Kotlin layer verified
/// the full SHA-256 before installing the file.
fn validate_model_file(
    dir: &Path,
    file_name: &str,
    expected_size_bytes: u64,
) -> Result<PathBuf, String> {
    let path = dir.join(file_name);
    let metadata = fs::metadata(&path)
        .map_err(|error| format!("model file {} is not readable: {error}", path.display()))?;
    if !metadata.is_file() {
        return Err(format!(
            "model file {} is not a regular file",
            path.display()
        ));
    }
    let actual_size = metadata.len();
    if actual_size != expected_size_bytes {
        return Err(format!(
            "model file {} has unexpected size: expected {expected_size_bytes} bytes, found {actual_size}",
            path.display()
        ));
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ente_image::decode::decode_image_from_bytes;
    use ente_ml::types::{AlignmentResult, ClipResult, Dimensions, FaceDetection, FaceResult};

    fn face_result(score: f32, box_xyxy: [f32; 4]) -> FaceResult {
        FaceResult {
            detection: FaceDetection {
                score,
                box_xyxy,
                keypoints: [[0.5; 2]; 5],
            },
            blur_value: 0.0,
            alignment: AlignmentResult {
                affine_matrix: [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
                center: [0.0, 0.0],
                size: 1.0,
                rotation: 0.0,
            },
            embedding: Vec::new(),
            face_id: String::new(),
        }
    }

    fn analysis(faces: Option<Vec<FaceResult>>, width: u32, height: u32) -> AnalyzeImageResult {
        AnalyzeImageResult {
            file_id: FILE_ID,
            decoded_image_size: Dimensions { width, height },
            faces,
            face_crops: None,
            clip: None,
            pet_faces: None,
            pet_bodies: None,
            used_coreml: false,
            used_webgpu: false,
        }
    }

    #[test]
    fn detections_map_to_source_pixel_coordinates() {
        let result = analysis(
            Some(vec![
                face_result(0.75, [0.1, 0.2, 0.3, 0.4]),
                face_result(0.6, [1.0, 1.0, 1.0, 1.0]),
            ]),
            1000,
            500,
        );

        assert_eq!(
            detections_to_source_pixel_buffer(&result),
            vec![
                100.0, 100.0, 300.0, 200.0, 0.75, 1000.0, 500.0, 1000.0, 500.0, 0.6,
            ]
        );
    }

    #[test]
    fn embeddings_map_to_the_stride_197_wire_format() {
        let mut with_embedding = face_result(0.75, [0.1, 0.2, 0.3, 0.4]);
        with_embedding.embedding = vec![0.5; FACE_EMBEDDING_DIM];
        let result = analysis(Some(vec![with_embedding]), 1000, 500);

        let buffer = embeddings_to_source_pixel_buffer(&result).expect("a 192-dim embedding maps");
        assert_eq!(buffer.len(), EMBEDDING_STRIDE);
        assert_eq!(&buffer[..5], &[100.0, 100.0, 300.0, 200.0, 0.75]);
        assert!(buffer[5..].iter().all(|&value| value == 0.5));
    }

    #[test]
    fn a_wrong_runtime_embedding_dimension_is_an_error_not_bad_stride_math() {
        let mut short = face_result(0.9, [0.0, 0.0, 1.0, 1.0]);
        short.embedding = vec![0.5; FACE_EMBEDDING_DIM - 1];
        let result = analysis(Some(vec![short]), 64, 64);

        let error = embeddings_to_source_pixel_buffer(&result)
            .expect_err("a non-192 embedding is a broken engine contract");
        assert!(error.contains("192"));

        let mut over = face_result(0.9, [0.0, 0.0, 1.0, 1.0]);
        over.embedding = vec![0.5; FACE_EMBEDDING_DIM + 1];
        let result = analysis(Some(vec![over]), 64, 64);
        assert!(embeddings_to_source_pixel_buffer(&result).is_err());
    }

    #[test]
    fn absent_or_empty_face_lists_map_to_an_empty_buffer() {
        assert!(detections_to_source_pixel_buffer(&analysis(Some(Vec::new()), 64, 64)).is_empty());
        assert!(detections_to_source_pixel_buffer(&analysis(None, 64, 64)).is_empty());
        assert!(
            embeddings_to_source_pixel_buffer(&analysis(Some(Vec::new()), 64, 64))
                .expect("no faces")
                .is_empty()
        );
        assert!(
            embeddings_to_source_pixel_buffer(&analysis(None, 64, 64))
                .expect("no faces")
                .is_empty()
        );
    }

    #[test]
    fn engine_info_is_a_single_line_naming_the_pinned_stack() {
        let face_only = format_engine_info(true, false);

        assert_eq!(
            face_only,
            format!(
                "ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={FACE_MODELS_LABEL}"
            )
        );
        assert!(!face_only.contains('\n'));

        // The r26 combined line keeps the same shape, one segment swapped:
        let combined = format_engine_info(true, true);
        assert_eq!(
            combined,
            format!(
                "ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={SEMANTIC_MODELS_LABEL}"
            )
        );
        assert!(!combined.contains('\n'));

        // The r39 semantic-only line names the CLIP pair alone:
        let semantic_only = format_engine_info(false, true);
        assert_eq!(
            semantic_only,
            format!(
                "ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={SEMANTIC_ONLY_MODELS_LABEL}"
            )
        );
        assert!(!semantic_only.contains('\n'));
    }

    // ---- r39: the symmetric session halves ----

    /// A semantic-only session shape: the CLIP paths filled, the face paths
    /// blank (exactly what [`init_semantic`] installs from scratch).
    fn semantic_only_state() -> EngineState {
        EngineState {
            model_paths: ModelPaths {
                clip_image: "/models/mobileclip_s2_image.onnx".to_string(),
                clip_text: "/models/mobileclip_s2_text.onnx".to_string(),
                ..ModelPaths::default()
            },
            clip_vocab_path: Some("/models/bpe_simple_vocab.txt".to_string()),
            semantic_ready: true,
            info: format_engine_info(false, true),
        }
    }

    #[test]
    fn a_semantic_only_session_has_no_face_half_and_the_dedicated_error_names_it() {
        let state = semantic_only_state();

        // The session-shape truth: the face paths are blank, so the face
        // half is not installed and the boundary guard fires with the
        // dedicated message (never upstream's generic missing-path error).
        assert!(!state.faces_ready());
        assert!(state.semantic_ready);
        assert!(FACE_MODELS_NOT_INSTALLED.contains("nativeInit"));

        // A face-only session (the pre-r39 shape) is the mirror image:
        let face_state = EngineState {
            model_paths: ModelPaths {
                face_detection: "/models/yolov5s_face.onnx".to_string(),
                face_embedding: "/models/mobilefacenet.onnx".to_string(),
                ..ModelPaths::default()
            },
            clip_vocab_path: None,
            semantic_ready: false,
            info: format_engine_info(true, false),
        };
        assert!(face_state.faces_ready());
        assert!(!face_state.semantic_ready);
    }

    #[test]
    fn a_blank_detector_path_is_the_face_half_absent_and_whitespace_only_counts_as_blank() {
        // The vendored runtime's own blank test is `trim().is_empty()`, so
        // the faces_ready derivation mirrors it exactly (a path of spaces
        // configures nothing, the same as an empty string).
        let mut state = semantic_only_state();
        assert!(!state.faces_ready());

        state.model_paths.face_detection = "   ".to_string();
        assert!(!state.faces_ready());

        state.model_paths.face_detection = "/models/yolov5s_face.onnx".to_string();
        assert!(state.faces_ready());
    }

    #[test]
    fn warmup_jpeg_round_trips_through_ente_image() {
        let jpeg = warmup_jpeg().expect("warm-up jpeg encodes");
        let decoded = decode_image_from_bytes(&jpeg).expect("warm-up jpeg decodes via ente-image");

        assert_eq!(
            (decoded.dimensions.width, decoded.dimensions.height),
            (WARMUP_WIDTH, WARMUP_HEIGHT)
        );
    }

    #[test]
    fn model_file_validation_checks_existence_and_exact_size() {
        let dir = tempfile::tempdir().expect("temp dir");

        assert!(validate_model_file(dir.path(), "missing.onnx", 4).is_err());

        std::fs::write(dir.path().join("wrong-size.onnx"), b"123").expect("write short file");
        assert!(validate_model_file(dir.path(), "wrong-size.onnx", 4).is_err());

        std::fs::write(dir.path().join("pinned.onnx"), b"1234").expect("write exact file");
        let path = validate_model_file(dir.path(), "pinned.onnx", 4).expect("exact-size file");
        assert_eq!(path, dir.path().join("pinned.onnx"));
    }

    // ---- r26: the semantic (CLIP) half ----

    #[test]
    fn clip_image_embedding_maps_the_512_float_vector_verbatim() {
        let mut result = analysis(None, 8, 8);
        result.clip = Some(ClipResult {
            embedding: vec![0.25; CLIP_EMBEDDING_DIM],
        });

        let embedding =
            clip_image_embedding_from_result(&result).expect("a 512-dim clip embedding maps");
        assert_eq!(embedding, vec![0.25; CLIP_EMBEDDING_DIM]);
    }

    #[test]
    fn a_wrong_runtime_clip_dimension_is_an_error_naming_512() {
        let mut short = analysis(None, 8, 8);
        short.clip = Some(ClipResult {
            embedding: vec![0.5; CLIP_EMBEDDING_DIM - 1],
        });
        let error = clip_image_embedding_from_result(&short)
            .expect_err("a non-512 clip embedding is a broken engine contract");
        assert!(error.contains("512"));

        let mut over = analysis(None, 8, 8);
        over.clip = Some(ClipResult {
            embedding: vec![0.5; CLIP_EMBEDDING_DIM + 1],
        });
        assert!(clip_image_embedding_from_result(&over).is_err());

        // The text path shares the same boundary check:
        assert!(validate_clip_embedding(&[0.5; CLIP_EMBEDDING_DIM]).is_ok());
        let error = validate_clip_embedding(&[0.5; 192]).expect_err("the face dim is not clip's");
        assert!(error.contains("512"));
    }

    #[test]
    fn a_missing_clip_result_is_a_broken_engine_contract() {
        // clip: None after a run_clip: true request --- upstream never
        // produces this, so the boundary treats it as an error:
        let error = clip_image_embedding_from_result(&analysis(None, 8, 8))
            .expect_err("a missing clip half is a broken engine contract");
        assert!(error.contains("missing"));
    }

    #[test]
    fn semantic_warmup_request_only_runs_the_clip_pipeline() {
        let request = semantic_warmup_request(&ModelPaths::default(), Vec::new());

        assert!(!request.run_faces);
        assert!(request.run_clip);
        assert!(!request.run_pets);
        assert!(!request.generate_face_crops);
        assert!(matches!(request.source, ImageSource::Bytes(_)));
    }

    #[test]
    fn the_r26_clip_model_pins_match_the_catalog_names_and_sizes() {
        // The pins the Kotlin `ModelManifest` mirrors (SEMANTIC
        // capability), straight from the vendored catalog:
        assert_eq!(
            CLIP_IMAGE_FILE_NAME,
            "mobileclip_s2_image_gelu_opset20.onnx"
        );
        assert_eq!(CLIP_IMAGE_SIZE_BYTES, 143_057_352);
        assert_eq!(CLIP_TEXT_FILE_NAME, "mobileclip_s2_text_opset18_quant.onnx");
        assert_eq!(CLIP_TEXT_SIZE_BYTES, 67_144_712);
        assert_eq!(CLIP_VOCAB_FILE_NAME, "bpe_simple_vocab_16e6.txt");
        assert_eq!(CLIP_VOCAB_SIZE_BYTES, 3_194_984);

        // And the generic validator still guards the new pins (the cheap
        // wrong-size rejection leg, exercised with a small fixture ---
        // the exact-size legs would need the real multi-MiB assets):
        let dir = tempfile::tempdir().expect("temp dir");
        std::fs::write(dir.path().join(CLIP_VOCAB_FILE_NAME), b"1234").expect("write short vocab");
        assert!(
            validate_model_file(dir.path(), CLIP_VOCAB_FILE_NAME, CLIP_VOCAB_SIZE_BYTES).is_err()
        );
    }
}
