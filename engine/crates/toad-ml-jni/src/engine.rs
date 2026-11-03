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
/// is installed (r26): the face pair plus the MobileCLIP-S2 pair.
const SEMANTIC_MODELS_LABEL: &str = "yolov5s-face,mobilefacenet,mobileclip-s2";

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

/// The initialized engine. Once installed, the face half is immutable for
/// its lifetime; the r26 semantic upgrade ([`init_semantic`]) mutates the
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

/// Initializes the engine against `models_dir`.
///
/// Both pinned model files must exist with their exact catalog byte sizes.
/// Both are registered with ente-ml's global runtime via `init_ml_runtime`;
/// a warm-up `analyze_image` call then forces the dynamic ONNX Runtime load
/// and builds + runs the detector session, so init fails fast (and leaves no
/// engine installed) when the runtime cannot come up. Idempotent: an
/// already-initialized engine succeeds without reloading anything.
pub(crate) fn init(models_dir: &str) -> Result<(), String> {
    let mut engine = lock();
    if engine.is_some() {
        return Ok(());
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

    let model_paths = ModelPaths {
        face_detection: detector_path.to_string_lossy().into_owned(),
        face_embedding: embedder_path.to_string_lossy().into_owned(),
        ..ModelPaths::default()
    };

    // Registers both face models with ente-ml's process-global runtime. The
    // ONNX sessions themselves load lazily on first use.
    init_ml_runtime(model_paths.clone());

    // Warm-up: forces the dynamic ONNX Runtime load (see README.md for the
    // discovery order), parses + optimizes the pinned detector graph, and
    // runs one inference over an inert image. The embedder session is built
    // lazily by ente-ml on the first detected face; its file was validated
    // above (and its SHA-256 by the Kotlin layer at install time).
    let warmup_jpeg = warmup_jpeg().map_err(|error| format!("encode warm-up image: {error}"))?;
    if let Err(error) = analyze_image(warmup_request(&model_paths, warmup_jpeg)) {
        release_ml_runtime();
        return Err(format!("engine warm-up failed: {error}"));
    }

    *engine = Some(EngineState {
        model_paths,
        clip_vocab_path: None,
        semantic_ready: false,
        info: format_engine_info(false),
    });
    Ok(())
}

/// Initializes the semantic (CLIP) half of an already-initialized engine
/// against `models_dir` (r26).
///
/// Requires an installed engine session ([`init`]) — the semantic half
/// registers ON TOP of the face models; without one this is the
/// [`NOT_INITIALIZED`] error. Idempotent: an already-semantic engine
/// succeeds without reloading anything. The three pinned files (CLIP image
/// encoder, CLIP text encoder, BPE vocab) must exist with their exact
/// catalog byte sizes; the combined face + CLIP `ModelPaths` is then
/// registered with ente-ml's global runtime (replacing the face-only
/// registration — the face sessions survive it, same paths) and a warm-up
/// `analyze_image` call with `run_clip: true` builds + runs the CLIP image
/// session over the same inert JPEG the face warm-up uses, so init fails
/// fast when the runtime cannot come up.
///
/// Failure recovery, honestly: a failed warm-up tears the whole runtime
/// down (`release_ml_runtime`) and re-registers the previous face-only
/// paths, leaving the engine in a consistent FACE-ONLY state — semantic
/// calls keep failing with [`SEMANTIC_NOT_INITIALIZED`] and the face
/// sessions rebuild lazily on the next face call (the same lazy rebuild any
/// `release` + `init` cycle goes through). The `EngineState` itself is
/// untouched on that leg, so `semantic_ready` stays `false` and the info
/// line stays the face-only one. A validation failure (missing or
/// wrong-size model file) fires BEFORE any re-registration and leaves the
/// runtime entirely untouched.
pub(crate) fn init_semantic(models_dir: &str) -> Result<(), String> {
    let mut engine = lock();
    let Some(state) = engine.as_mut() else {
        return Err(NOT_INITIALIZED.to_string());
    };
    if state.semantic_ready {
        return Ok(());
    }
    let previous_paths = state.model_paths.clone();

    let models_dir = Path::new(models_dir);
    let clip_image_path =
        validate_model_file(models_dir, CLIP_IMAGE_FILE_NAME, CLIP_IMAGE_SIZE_BYTES)?;
    let clip_text_path =
        validate_model_file(models_dir, CLIP_TEXT_FILE_NAME, CLIP_TEXT_SIZE_BYTES)?;
    let vocab_path = validate_model_file(models_dir, CLIP_VOCAB_FILE_NAME, CLIP_VOCAB_SIZE_BYTES)?;

    let combined = ModelPaths {
        clip_image: clip_image_path.to_string_lossy().into_owned(),
        clip_text: clip_text_path.to_string_lossy().into_owned(),
        ..previous_paths.clone()
    };

    // Re-registers the runtime with the combined face + CLIP paths. The
    // face sessions survive the re-registration (same paths); the CLIP
    // image session is configured here and loads below.
    init_ml_runtime(combined.clone());

    // Warm-up: forces the CLIP image session to build + run over an
    // inert image (ente-ml's CLIP preprocessing rescales it to the
    // encoder's input size), so a broken semantic runtime fails HERE
    // instead of on the first real embedding. On failure the recovery
    // above restores the face-only runtime.
    let warmup_jpeg = warmup_jpeg().map_err(|error| format!("encode warm-up image: {error}"))?;
    if let Err(error) = analyze_image(semantic_warmup_request(&combined, warmup_jpeg)) {
        release_ml_runtime();
        init_ml_runtime(previous_paths);
        return Err(format!("semantic warm-up failed: {error}"));
    }

    state.model_paths = combined;
    state.clip_vocab_path = Some(vocab_path.to_string_lossy().into_owned());
    state.semantic_ready = true;
    state.info = format_engine_info(true);
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
/// a fresh `init` + `init_semantic`.
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
pub(crate) fn detect_faces_jpeg(jpeg_bytes: Vec<u8>) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
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
pub(crate) fn detect_face_embeddings_jpeg(jpeg_bytes: Vec<u8>) -> Result<Vec<f32>, String> {
    let engine = lock();
    let state = engine.as_ref().ok_or_else(|| NOT_INITIALIZED.to_string())?;
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

fn format_engine_info(semantic: bool) -> String {
    let models = if semantic {
        SEMANTIC_MODELS_LABEL
    } else {
        FACE_MODELS_LABEL
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
        let face_only = format_engine_info(false);

        assert_eq!(
            face_only,
            format!(
                "ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={FACE_MODELS_LABEL}"
            )
        );
        assert!(!face_only.contains('\n'));

        // The r26 semantic line keeps the same shape, one segment swapped:
        let semantic = format_engine_info(true);
        assert_eq!(
            semantic,
            format!(
                "ort={ORT_BINDINGS_VERSION} providers={PROVIDERS_LABEL} models={SEMANTIC_MODELS_LABEL}"
            )
        );
        assert!(!semantic.contains('\n'));
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
