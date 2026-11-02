//! toadGALLERY face-detection JNI bridge (r22 spike).
//!
//! Provenance: net-new toadGALLERY code, r22 — this crate is *our* adapter,
//! not vendored from upstream. It links the AGPL-3.0 vendored Ente engine
//! (`ente-ml`, `ente-image`) and exposes a deliberately narrow JNI surface for
//! Kotlin's `io.github.dukeoftoad.gallery.ml.engine.MlEngineJni`:
//!
//! * [`nativeInit`] — validate and register the pinned face models in a
//!   models directory, then warm up ONNX Runtime.
//! * [`nativeEngineInfo`] — one-line human-readable engine description.
//! * [`nativeDetectFacesJpeg`] — face boxes + scores for a JPEG byte payload,
//!   mapped to source-image pixel coordinates.
//! * [`nativeRelease`] — tear the engine down.
//!
//! Contract notes (see also `README.md`):
//!
//! * Every entry point is wrapped in [`std::panic::catch_unwind`]; a panic
//!   never unwinds across the JNI boundary and is reported to Kotlin as
//!   `java.lang.IllegalStateException("panic: …")`.
//! * Engine failures are surfaced as `java.lang.IllegalStateException` whose
//!   message is the Rust error's `Display` string.
//! * The engine state lives in a process-global `OnceLock<Mutex<…>>` that is
//!   held for the whole of `nativeInit`, `nativeDetectFacesJpeg` and
//!   `nativeRelease`; inference is therefore serialized across calling
//!   threads.
//! * All Java object access goes through the `jni` crate's safe wrappers; no
//!   raw JNI pointers are handled outside them.

use std::{
    any::Any,
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JString},
    sys::{self, jboolean, jfloatArray, jstring},
};

mod engine;

/// The bridge's single Java exception type; matches the Kotlin-side contract.
const ILLEGAL_STATE_EXCEPTION: &str = "java/lang/IllegalStateException";

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
/// Frees the engine. Returns `true` if an engine existed and was released,
/// `false` if none did; either way the engine ends up uninitialized. Calling
/// `nativeInit` again afterwards starts from a clean state.
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
        Ok(if engine::release() {
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
