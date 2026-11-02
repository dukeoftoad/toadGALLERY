//! toadGALLERY face-detection + face-index JNI bridge (r22 spike, r23
//! indexing round).
//!
//! Provenance: net-new toadGALLERY code, r22/r23 — this crate is *our*
//! adapter, not vendored from upstream. It links the AGPL-3.0 vendored
//! Ente engine (`ente-ml`, `ente-image`) and the vendored persistence
//! stack (`ente-photos-ml`), exposing a deliberately narrow JNI surface
//! for Kotlin's `io.github.dukeoftoad.gallery.ml.engine.MlEngineJni`:
//!
//! * [`nativeInit`] — validate and register the pinned face models in a
//!   models directory, then warm up ONNX Runtime.
//! * [`nativeEngineInfo`] — one-line human-readable engine description.
//! * [`nativeDetectFacesJpeg`] — face boxes + scores for a JPEG byte
//!   payload, mapped to source-image pixel coordinates.
//! * [`nativeDetectFaceEmbeddingsJpeg`] (r23) — the same detection plus
//!   the 192-float MobileFaceNet embedding per face (flat stride 197).
//! * [`nativeStoreOpen`] / [`nativeStorePutFace`] /
//!   [`nativeStoreFaceCount`] / [`nativeStoreCluster`] /
//!   [`nativeStoreClose`] (r23) — the app-private face-index store over
//!   the vendored `ente-photos-ml` persistence + clustering stack.
//! * [`nativeStoreIndexedFaceFileIds`] / [`nativeStoreDeleteFacesForFiles`]
//!   (r24) — the store maintenance pair the background queue drives:
//!   which files carry current-version face rows, and the per-file
//!   invalidation delete.
//! * [`nativeStoreFaceClusters`] / [`nativeStoreClusterFileIds`] /
//!   [`nativeStoreMergeFaceClusters`] /
//!   [`nativeStoreRemoveFacesFromCluster`] (r25) — the People UI read +
//!   manual cluster-management paths over the same open-store state
//!   machine: the live cluster list (JSON), a cluster's file ids, the
//!   merge-two-people upsert and the remove-photo-from-person delete.
//! * [`nativeRelease`] — tear the engine (and the store) down.
//!
//! Contract notes (see also `README.md`):
//!
//! * Every entry point is wrapped in [`std::panic::catch_unwind`]; a panic
//!   never unwinds across the JNI boundary and is reported to Kotlin as
//!   `java.lang.IllegalStateException("panic: …")`.
//! * Engine failures are surfaced as `java.lang.IllegalStateException` whose
//!   message is the Rust error's `Display` string; the two caller-input
//!   failures (a wrong embedding dimension on `nativeStorePutFace`, a
//!   source==target merge on `nativeStoreMergeFaceClusters`) are surfaced
//!   as `java.lang.IllegalArgumentException`.
//! * The engine state lives in a process-global `OnceLock<Mutex<…>>` that is
//!   held for the whole of `nativeInit`, the detection/embedding calls and
//!   `nativeRelease`; the face-index store has its own process-global mutex
//!   held across every store operation. Inference and store access are
//!   therefore serialized across calling threads.
//! * All Java object access goes through the `jni` crate's safe wrappers; no
//!   raw JNI pointers are handled outside them.

use std::{
    any::Any,
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JFloatArray, JLongArray, JString},
    sys::{self, jboolean, jdouble, jfloatArray, jlong, jlongArray, jstring},
};

mod engine;
mod store;

/// The bridge's single Java exception type; matches the Kotlin-side contract.
const ILLEGAL_STATE_EXCEPTION: &str = "java/lang/IllegalStateException";

/// The one caller-input Java exception type (wrong embedding dimension on
/// `nativeStorePutFace`); matches the Kotlin-side contract.
const ILLEGAL_ARGUMENT_EXCEPTION: &str = "java/lang/IllegalArgumentException";

/// Bridges a fallible engine call into the JNI contract.
///
/// `Ok` maps to the returned JNI value; an `Err` string (and any caught
/// panic) is thrown to Kotlin as [`ILLEGAL_STATE_EXCEPTION`] and the
/// `failure` sentinel is returned instead.
fn call_or_throw<'local, T>(
    env: &mut JNIEnv<'local>,
    failure: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw_illegal_state(env, &message);
            failure
        }
        Err(panic) => {
            throw_illegal_state(env, &panic_message(panic));
            failure
        }
    }
}

/// Reports `message` to Kotlin as a `java.lang.IllegalStateException`.
///
/// Throwing can itself fail (e.g. a pending out-of-memory condition); that
/// error is deliberately swallowed so the JVM surfaces its own pending
/// condition while the entry point still returns its failure sentinel.
fn throw_illegal_state(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new(ILLEGAL_STATE_EXCEPTION, message);
}

/// Best-effort extraction of a caught panic payload for the exception
/// message; panics raised by `panic!("…")` and `unwrap` carry `&str`/`String`
/// payloads.
fn panic_message(panic: Box<dyn Any + Send>) -> String {
    if let Some(message) = panic.downcast_ref::<&'static str>() {
        format!("panic: {message}")
    } else if let Some(message) = panic.downcast_ref::<String>() {
        format!("panic: {message}")
    } else {
        "panic: unknown panic payload".to_string()
    }
}

/// `MlEngineJni.nativeInit(modelsDir: String): Boolean`.
///
/// Initializes the engine against `modelsDir`: both pinned face models are
/// validated (exact catalog file names and byte sizes) and registered with
/// ente-ml's global runtime, then a warm-up inference forces the dynamic
/// ONNX Runtime load and builds the detector session. Returns `true` on
/// success. Idempotent: an already-initialized engine returns `true` without
/// reloading. Any failure leaves no engine installed (call `nativeRelease` +
/// `nativeInit` again to retry).
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeInit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    models_dir: JString<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |env| {
        let models_dir: String = env
            .get_string(&models_dir)
            .map_err(|error| format!("read modelsDir argument: {error}"))?
            .into();
        engine::init(&models_dir)?;
        Ok(sys::JNI_TRUE)
    })
}

/// `MlEngineJni.nativeEngineInfo(): String?`.
///
/// Returns a one-line human-readable engine description, or `NULL` while the
/// engine is uninitialized (per the contract, without raising an exception).
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeEngineInfo<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let Some(info) = engine::engine_info() else {
            return Ok(ptr::null_mut());
        };
        let java_string = env
            .new_string(info)
            .map_err(|error| format!("allocate engine info string: {error}"))?;
        Ok(java_string.into_raw())
    })
}

/// `MlEngineJni.nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray`.
///
/// Decodes the JPEG bytes with `ente-image`, runs the ente-ml face detector
/// (upstream YOLO input size and NMS thresholds), and returns `N * 5` floats
/// `[x1, y1, x2, y2, score, …]` in source-image pixel coordinates with the
/// upstream detection confidence as the score. An empty array means "no
/// faces". Throws `java.lang.IllegalStateException` when the engine is not
/// initialized or anything in the pipeline fails.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeDetectFacesJpeg<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jpeg_bytes: JByteArray<'local>,
) -> jfloatArray {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let jpeg_bytes = env
            .convert_byte_array(&jpeg_bytes)
            .map_err(|error| format!("read jpegBytes argument: {error}"))?;
        let detections = engine::detect_faces_jpeg(jpeg_bytes)?;
        let array = env
            .new_float_array(detections.len() as i32)
            .map_err(|error| format!("allocate detection result array: {error}"))?;
        if !detections.is_empty() {
            env.set_float_array_region(&array, 0, &detections)
                .map_err(|error| format!("fill detection result array: {error}"))?;
        }
        Ok(array.into_raw() as jfloatArray)
    })
}

/// `MlEngineJni.nativeRelease(): Boolean`.
///
/// Frees the engine (and closes the r23 face-index store, if one is
/// open). Returns `true` if an engine existed and was released, `false`
/// if none did; either way the engine ends up uninitialized and the
/// store closed. Calling `nativeInit` again afterwards starts from a
/// clean state (and a later `nativeStoreOpen` reopens the store).
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeRelease<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |_| {
        let freed = engine::release();
        // The r23 contract: engine release closes the store too. The
        // store's own close result never masks the engine result.
        store::close();
        Ok(if freed { sys::JNI_TRUE } else { sys::JNI_FALSE })
    })
}

/// `MlEngineJni.nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray):
/// FloatArray`.
///
/// Decodes the JPEG bytes with `ente-image` and runs the ente-ml face
/// pipeline (the same `analyze_image` flow as
/// `nativeDetectFacesJpeg`), returning `N * (5 + 192)` flat row-major
/// floats: per face `[x1, y1, x2, y2, score, e0..e191]` in source-image
/// pixel coordinates with the 192-dimensional MobileFaceNet embedding
/// appended. An empty array means "no faces". A runtime embedding
/// dimension other than 192 is validated at the Rust boundary and throws
/// `java.lang.IllegalStateException` (as does an uninitialized engine or
/// any pipeline failure).
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeDetectFaceEmbeddingsJpeg<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jpeg_bytes: JByteArray<'local>,
) -> jfloatArray {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let jpeg_bytes = env
            .convert_byte_array(&jpeg_bytes)
            .map_err(|error| format!("read jpegBytes argument: {error}"))?;
        let buffer = engine::detect_face_embeddings_jpeg(jpeg_bytes)?;
        let array = env
            .new_float_array(buffer.len() as i32)
            .map_err(|error| format!("allocate embedding result array: {error}"))?;
        if !buffer.is_empty() {
            env.set_float_array_region(&array, 0, &buffer)
                .map_err(|error| format!("fill embedding result array: {error}"))?;
        }
        Ok(array.into_raw() as jfloatArray)
    })
}

/// `MlEngineJni.nativeStoreOpen(dir: String): Boolean`.
///
/// Opens the vendored `ente_photos_ml::ml_store::MlStore` at
/// `<dir>/ml-index.sqlite` (creating the database through its own
/// migrations). Idempotent: an already-open store returns true without
/// reopening. Returns true on success; failure throws
/// `java.lang.IllegalStateException`.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreOpen<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir: JString<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |env| {
        let dir: String = env
            .get_string(&dir)
            .map_err(|error| format!("read dir argument: {error}"))?
            .into();
        store::open(&dir)?;
        Ok(sys::JNI_TRUE)
    })
}

/// `MlEngineJni.nativeStorePutFace(fileId: Long, embedding: FloatArray):
/// Long`.
///
/// Inserts one face row through the vendored upstream insert path
/// (`MlDb::bulk_insert_faces`, single-row form) and returns the face's
/// per-file ordinal (> 0; the upstream schema keys faces by the
/// `(file_id, face_id)` pair, so there is no rowid to return). A
/// wrong-dimension embedding throws
/// `java.lang.IllegalArgumentException`; a closed store or DB failure
/// throws `java.lang.IllegalStateException`.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStorePutFace<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    file_id: jlong,
    embedding: JFloatArray<'local>,
) -> jlong {
    let fail = |env: &mut JNIEnv<'local>, exception: &str, message: String| -> jlong {
        let _ = env.throw_new(exception, &message);
        0
    };
    // The caller-input failure is the IllegalArgumentException leg of the
    // contract; everything below stays IllegalStateException. The array is
    // read through the safe region-copy JNI helpers (no raw element
    // pointers), sized from the array's own length.
    let length = match env.get_array_length(&embedding) {
        Ok(length) => length as usize,
        Err(error) => {
            return fail(
                &mut env,
                ILLEGAL_ARGUMENT_EXCEPTION,
                format!("read embedding argument: {error}"),
            );
        }
    };
    let mut values = vec![0.0_f32; length];
    if let Err(error) = env.get_float_array_region(&embedding, 0, &mut values) {
        return fail(
            &mut env,
            ILLEGAL_ARGUMENT_EXCEPTION,
            format!("read embedding argument: {error}"),
        );
    }
    if values.len() != store::FACE_EMBEDDING_DIM {
        return fail(
            &mut env,
            ILLEGAL_ARGUMENT_EXCEPTION,
            format!(
                "embedding must be {} floats; got {}",
                store::FACE_EMBEDDING_DIM,
                values.len()
            ),
        );
    }
    match store::put_face(file_id, &values) {
        Ok(ordinal) => ordinal,
        Err(message) => fail(&mut env, ILLEGAL_STATE_EXCEPTION, message),
    }
}

/// `MlEngineJni.nativeStoreFaceCount(): Long`.
///
/// Total face rows in the store, via the vendored upstream count query
/// (`MlDb::get_total_face_count`). Throws
/// `java.lang.IllegalStateException` when the store is not open.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreFaceCount<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    call_or_throw(&mut env, 0, |_| store::face_count())
}

/// `MlEngineJni.nativeStoreCluster(distanceThreshold: Double): String`.
///
/// Runs the vendored upstream clustering over the faces in the store at
/// `distanceThreshold` — `MlDb::get_faces_for_clustering()` →
/// `MlStore::recluster_faces(...)` (upstream's own full-recluster flow:
/// `face_clustering::recluster` + the upstream assignment/summary/centroid
/// write paths) — and returns the compact summary JSON
/// `{"faces":N,"clusters":M,"sizes":[s1,s2,...]}` with sizes descending.
/// Throws `java.lang.IllegalStateException` on failure or a closed store.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreCluster<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    distance_threshold: jdouble,
) -> jstring {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let json = store::cluster(distance_threshold)?;
        let java_string = env
            .new_string(&json)
            .map_err(|error| format!("allocate cluster summary string: {error}"))?;
        Ok(java_string.into_raw())
    })
}

/// `MlEngineJni.nativeStoreIndexedFaceFileIds(): LongArray` (r24).
///
/// The file IDs that currently carry face rows at the vendored pin's
/// `FACE_ML_VERSION`, via upstream's own `MlDb::face_indexed_file_ids`
/// read (the map's ml_version half is dropped on the wire). An empty
/// array means no indexed files. Throws
/// `java.lang.IllegalStateException` when the store is not open.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreIndexedFaceFileIds<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlongArray {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let file_ids = store::indexed_face_file_ids()?;
        let array = env
            .new_long_array(file_ids.len() as i32)
            .map_err(|error| format!("allocate indexed-file-id array: {error}"))?;
        if !file_ids.is_empty() {
            env.set_long_array_region(&array, 0, &file_ids)
                .map_err(|error| format!("fill indexed-file-id array: {error}"))?;
        }
        Ok(array.into_raw() as jlongArray)
    })
}

/// `MlEngineJni.nativeStoreDeleteFacesForFiles(fileIds: LongArray):
/// Boolean` (r24).
///
/// Deletes every face row for `fileIds` through the vendored upstream
/// delete path (`MlDb::delete_face_index_for_files`, chunked +
/// transactional; an empty array is upstream's own no-op). This is the
/// per-file invalidation primitive behind revision-driven re-indexing and
/// orphan cleanup. Returns true on success; failure throws
/// `java.lang.IllegalStateException`.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreDeleteFacesForFiles<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    file_ids: JLongArray<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |env| {
        let length =
            env.get_array_length(&file_ids)
                .map_err(|error| format!("read fileIds argument: {error}"))? as usize;
        let mut values = vec![0_i64; length];
        if length > 0 {
            env.get_long_array_region(&file_ids, 0, &mut values)
                .map_err(|error| format!("read fileIds argument: {error}"))?;
        }
        store::delete_faces_for_files(&values)?;
        Ok(sys::JNI_TRUE)
    })
}

/// `MlEngineJni.nativeStoreFaceClusters(): String` (r25).
///
/// The live cluster list for the People UI, as the JSON array
/// `[{"cluster_id":"…","count":12,"cover_file_id":4813},…]`
/// (a JSON `null` cover when the cluster's top face id has no parsable
/// leading file-id segment), sorted by count DESC then cluster_id ASC.
/// Counts come from the vendored upstream `MlDb::cluster_id_to_face_count`
/// — the LIVE `face_clusters` assignments table, deliberately NOT the
/// `cluster_summary` table the clustering pass refreshes, so manual
/// merges/exclusions reflect immediately; covers from
/// `MlDb::get_face_ids_for_cluster_ordered_by_score` (limit 1). Throws
/// `java.lang.IllegalStateException` when the store is not open or a read
/// fails.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreFaceClusters<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let json = store::face_clusters()?;
        let java_string = env
            .new_string(&json)
            .map_err(|error| format!("allocate face clusters string: {error}"))?;
        Ok(java_string.into_raw())
    })
}

/// `MlEngineJni.nativeStoreClusterFileIds(clusterId: String): LongArray`
/// (r25).
///
/// The file IDs whose faces currently sit in `clusterId`, via the
/// vendored upstream `MlDb::get_file_ids_of_cluster_id` verbatim (the
/// DISTINCT file-id read over the live `face_clusters` table; the
/// ordering is upstream's own). An empty array means no files —
/// including an unknown cluster id. Throws
/// `java.lang.IllegalStateException` when the store is not open or the
/// read fails.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreClusterFileIds<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cluster_id: JString<'local>,
) -> jlongArray {
    call_or_throw(&mut env, ptr::null_mut(), |env| {
        let cluster_id: String = env
            .get_string(&cluster_id)
            .map_err(|error| format!("read clusterId argument: {error}"))?
            .into();
        let file_ids = store::cluster_file_ids(&cluster_id)?;
        let array = env
            .new_long_array(file_ids.len() as i32)
            .map_err(|error| format!("allocate cluster file-id array: {error}"))?;
        if !file_ids.is_empty() {
            env.set_long_array_region(&array, 0, &file_ids)
                .map_err(|error| format!("fill cluster file-id array: {error}"))?;
        }
        Ok(array.into_raw() as jlongArray)
    })
}

/// `MlEngineJni.nativeStoreMergeFaceClusters(sourceClusterId: String,
/// targetClusterId: String): Boolean` (r25).
///
/// Moves every face of `sourceClusterId` into `targetClusterId` — the
/// People UI's "merge these two people" — through the vendored upstream
/// read (`MlDb::get_face_ids_for_cluster`) and atomic batch upsert
/// (`MlDb::force_update_cluster_ids`); an empty source is a no-op
/// returning true. `cluster_summary` rows are deliberately untouched:
/// the People read counts the live assignments table, and the next
/// clustering pass recomputes summaries (and, honestly, ALL assignments
/// from the embeddings) anyway.
///
/// A `sourceClusterId` equal to `targetClusterId` is the one caller-input
/// failure of this entry point and throws
/// `java.lang.IllegalArgumentException` BEFORE any store mutation — the
/// exact mechanism `nativeStorePutFace` uses for its embedding-dimension
/// guard (argument reads on this leg surface as `IllegalArgumentException`
/// too). Everything else stays `java.lang.IllegalStateException`.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreMergeFaceClusters<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_cluster_id: JString<'local>,
    target_cluster_id: JString<'local>,
) -> jboolean {
    // The caller-input leg mirrors nativeStorePutFace's embedding guard:
    // validate the input at the JNI boundary, throw
    // IllegalArgumentException, return the failure sentinel — all before
    // any store access or mutation.
    let fail_argument = |env: &mut JNIEnv<'local>, message: String| -> jboolean {
        let _ = env.throw_new(ILLEGAL_ARGUMENT_EXCEPTION, &message);
        sys::JNI_FALSE
    };
    let source_cluster_id: String = match env.get_string(&source_cluster_id) {
        Ok(value) => value.into(),
        Err(error) => {
            return fail_argument(&mut env, format!("read sourceClusterId argument: {error}"));
        }
    };
    let target_cluster_id: String = match env.get_string(&target_cluster_id) {
        Ok(value) => value.into(),
        Err(error) => {
            return fail_argument(&mut env, format!("read targetClusterId argument: {error}"));
        }
    };
    if source_cluster_id == target_cluster_id {
        return fail_argument(
            &mut env,
            format!("source and target cluster ids must differ; both are \"{source_cluster_id}\""),
        );
    }
    call_or_throw(&mut env, sys::JNI_FALSE, |_| {
        store::merge_face_clusters(&source_cluster_id, &target_cluster_id)?;
        Ok(sys::JNI_TRUE)
    })
}

/// `MlEngineJni.nativeStoreRemoveFacesFromCluster(clusterId: String,
/// fileIds: LongArray): Boolean` (r25).
///
/// Removes `fileIds`' faces from `clusterId` — the People UI's "remove
/// this photo from this person" / split primitive. Per file, the vendored
/// upstream `MlDb::get_faces_for_given_file_id` reads the face rows and
/// `MlDb::get_cluster_id_for_face_id` the CURRENT assignment; only faces
/// actually sitting in `clusterId` are dropped, through one atomic
/// `MlDb::remove_face_id_to_cluster_id` batch (a file whose other faces
/// live in other clusters keeps those; the face rows themselves stay for
/// the next clustering pass). An empty array is a no-op returning true.
/// Throws `java.lang.IllegalStateException` when the store is not open or
/// any read/write fails.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreRemoveFacesFromCluster<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cluster_id: JString<'local>,
    file_ids: JLongArray<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |env| {
        let cluster_id: String = env
            .get_string(&cluster_id)
            .map_err(|error| format!("read clusterId argument: {error}"))?
            .into();
        let length =
            env.get_array_length(&file_ids)
                .map_err(|error| format!("read fileIds argument: {error}"))? as usize;
        let mut values = vec![0_i64; length];
        if length > 0 {
            env.get_long_array_region(&file_ids, 0, &mut values)
                .map_err(|error| format!("read fileIds argument: {error}"))?;
        }
        store::remove_faces_from_cluster(&cluster_id, &values)?;
        Ok(sys::JNI_TRUE)
    })
}

/// `MlEngineJni.nativeStoreClose(): Boolean`.
///
/// Closes the face-index store (flushing the ente-vecdb index handles).
/// Idempotent; returns true.
#[expect(
    unsafe_code,
    reason = "JNI export: the symbol name is the fixed Kotlin-side calling contract"
)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_io_github_dukeoftoad_gallery_ml_engine_MlEngineJni_nativeStoreClose<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    call_or_throw(&mut env, sys::JNI_FALSE, |_| {
        Ok(if store::close() {
            sys::JNI_TRUE
        } else {
            sys::JNI_FALSE
        })
    })
}

#[cfg(test)]
mod tests {
    use std::any::Any;

    use super::panic_message;

    #[test]
    fn panic_payloads_are_prefixed_and_preserved() {
        let str_panic: Box<dyn Any + Send> = Box::new("boom");
        assert_eq!(panic_message(str_panic), "panic: boom");

        let string_panic: Box<dyn Any + Send> = Box::new(String::from("boom"));
        assert_eq!(panic_message(string_panic), "panic: boom");
    }

    #[test]
    fn unknown_panic_payloads_get_a_stable_message() {
        let opaque_panic: Box<dyn Any + Send> = Box::new(42_u8);
        assert_eq!(panic_message(opaque_panic), "panic: unknown panic payload");
    }
}
