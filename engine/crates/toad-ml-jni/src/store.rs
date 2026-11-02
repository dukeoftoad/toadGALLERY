//! The r23 face-index store state and the ente-photos-ml adapter logic
//! behind the JNI surface.
//!
//! This is a thin adapter over the vendored upstream persistence stack
//! (`ente-photos-ml`): [`crate::store`] owns ONE `MlStore` per process
//! (the SQLite ML index via `MlDb::open` + the ente-vecdb-backed centroid
//! index slots) and routes the JNI store entry points through the
//! upstream crate's own public API — the insert path (`MlDb::
//! bulk_insert_faces`), the read/count paths (`get_faces_for_clustering`,
//! `get_total_face_count`) and the clustering orchestration
//! (`MlStore::recluster_faces`, which drives upstream's
//! `face_clustering::recluster` and persists assignments + cluster
//! summaries + centroid vectors). No math is reimplemented here.
//!
//! r24 adds the two maintenance reads the background queue needs, both
//! still upstream's own queries verbatim: `MlDb::face_indexed_file_ids`
//! (which files currently carry face rows, filtered to the vendored
//! [`FACE_ML_VERSION`]) and `MlDb::delete_face_index_for_files` (the
//! per-file invalidation delete). No new SQL is authored here.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Mutex, MutexGuard, OnceLock, PoisonError},
};

use ente_photos_ml::ml_db::{FACE_ML_VERSION, FaceRow};
use ente_photos_ml::ml_store::MlStore;

/// The face-index SQLite file the store opens inside the caller's dir.
pub(crate) const ML_INDEX_FILE_NAME: &str = "ml-index.sqlite";

/// The face-embedding dimension the store accepts (upstream MobileFaceNet
/// output; the vendored clustering's `CLUSTER_CENTROID_DIMENSIONS`).
pub(crate) const FACE_EMBEDDING_DIM: usize = 192;

/// Message used when a store operation runs before a successful open.
const NOT_OPEN: &str = "face index store is not open; call nativeStoreOpen(dir) first";

/// Placeholder quality values for the r23 dogfood insert path.
///
/// The r23 JNI wire (`nativeStorePutFace`) carries only the embedding —
/// no detection score and no blur value — but the upstream read paths the
/// contract pins (`get_total_face_count`, `get_faces_for_clustering`)
/// filter rows through the upstream quality thresholds
/// (`score > 0.80 AND blur > 10.0`, see
/// `ente-photos-ml/src/ml_db/queries/faces.rs`). Rows are therefore
/// inserted with pass-through quality markers so the vendored pipeline
/// sees them; the real detection score stays available on the
/// embeddings wire for the caller. (Upstream's own `ml_store` test
/// fixtures insert the same shape: a positive score well above the
/// threshold and a high blur value.)
const PASS_THROUGH_SCORE: f64 = 1.0;
const PASS_THROUGH_BLUR: f64 = 300.0;

struct StoreState {
    store: MlStore,
}

fn store_cell() -> &'static Mutex<Option<StoreState>> {
    static STORE: OnceLock<Mutex<Option<StoreState>>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(None))
}

/// Locks the store state. The lock is held for the whole of every store
/// operation, serializing store access across calling threads. A panic
/// caught at the JNI boundary can poison the lock; the guarded state is a
/// plain `Option` that stays consistent, so recovery is safe (the same
/// recovery the engine mutex uses).
fn lock() -> MutexGuard<'static, Option<StoreState>> {
    store_cell().lock().unwrap_or_else(PoisonError::into_inner)
}

/// Runs `operation` with the open store, mapping its errors into the JNI
/// error string shape.
fn with_store<T>(operation: impl FnOnce(&MlStore) -> Result<T, String>) -> Result<T, String> {
    let guard = lock();
    let state = guard.as_ref().ok_or_else(|| NOT_OPEN.to_string())?;
    operation(&state.store)
}

/// Opens the face-index store at `<dir>/ml-index.sqlite` (created through
/// `MlDb`'s own migrations). Idempotent: an already-open store succeeds
/// without reopening.
pub(crate) fn open(dir: &str) -> Result<(), String> {
    let mut guard = lock();
    if guard.is_some() {
        return Ok(());
    }
    let path: PathBuf = Path::new(dir).join(ML_INDEX_FILE_NAME);
    let store = MlStore::open(&path).map_err(|error| format!("open face index store: {error}"))?;
    *guard = Some(StoreState { store });
    Ok(())
}

/// Inserts one face row for `file_id` with a [`FACE_EMBEDDING_DIM`]-
/// float embedding. Returns the face's per-file ordinal (> 0).
///
/// Face identity upstream is the `(file_id, face_id)` pair (the `faces`
/// table's primary key; there is no exposed rowid), so the JNI surface
/// returns the face's ordinal within its file: face_id
/// `<file_id>_<ordinal>`, deduplicating on re-insert like the rest of
/// the upstream schema. Inserted via `MlDb::bulk_insert_faces` (the
/// single-row form of the upstream insert path).
///
/// The embedding dimension is validated HERE, at the Rust boundary; a
/// mismatch is a `java.lang.IllegalArgumentException` at the JNI layer.
pub(crate) fn put_face(file_id: i64, embedding: &[f32]) -> Result<i64, String> {
    if embedding.len() != FACE_EMBEDDING_DIM {
        return Err(format!(
            "embedding must be {FACE_EMBEDDING_DIM} floats; got {}",
            embedding.len()
        ));
    }
    with_store(|store| {
        let ordinal = store
            .db()
            .get_faces_for_given_file_id(file_id)
            .map_err(|error| format!("read existing faces for file {file_id}: {error}"))?
            .len() as i64
            + 1;
        let row = FaceRow {
            file_id,
            face_id: format!("{file_id}_{ordinal}"),
            detection_json: "{}".to_string(),
            embedding: embedding.iter().map(|value| f64::from(*value)).collect(),
            score: PASS_THROUGH_SCORE,
            blur: PASS_THROUGH_BLUR,
            is_sideways: false,
            image_height: 0,
            image_width: 0,
            ml_version: FACE_ML_VERSION,
        };
        store
            .db()
            .bulk_insert_faces(std::slice::from_ref(&row))
            .map_err(|error| format!("insert face row: {error}"))?;
        Ok(ordinal)
    })
}

/// Total face rows in the store, via upstream's own count query
/// (`MlDb::get_total_face_count`, the quality-filtered upstream
/// definition).
pub(crate) fn face_count() -> Result<i64, String> {
    with_store(|store| {
        store
            .db()
            .get_total_face_count()
            .map_err(|error| format!("count face rows: {error}"))
    })
}

/// The file IDs that currently carry face rows, via upstream's own
/// `MlDb::face_indexed_file_ids` read with the vendored pin's
/// [`FACE_ML_VERSION`] as the minimum (r24).
///
/// Rows written by an OLDER model version are deliberately NOT reported:
/// upstream's own versioning rule marks them stale, so the caller's plan
/// treats those files as un-indexed and re-runs them (delete first, then
/// re-insert --- `put_face` ordinals would otherwise append duplicates).
/// The ml_version half of upstream's map is dropped on the wire: the JNI
/// surface only answers "does this file have CURRENT-version face rows".
pub(crate) fn indexed_face_file_ids() -> Result<Vec<i64>, String> {
    with_store(|store| {
        store
            .db()
            .face_indexed_file_ids(FACE_ML_VERSION)
            .map(|per_file| per_file.into_keys().collect())
            .map_err(|error| format!("read indexed file ids: {error}"))
    })
}

/// Deletes every face row for `file_ids`, via upstream's own
/// `MlDb::delete_face_index_for_files` (chunked + transactional; r24).
///
/// This is the per-file invalidation primitive the background queue's
/// revision wiring drives: a `CONTENT_CHANGED` revision (or a file that
/// left the eligible public listing --- deleted, trashed, vault-moved)
/// must drop its stale pixel-derived rows BEFORE any re-index, because
/// `put_face` ordinals append rather than replace. An empty slice is a
/// no-op (upstream's own early return). Cluster assignments and summaries
/// are upstream-managed by the NEXT `cluster()` pass, exactly like the
/// r23 dogfood flow.
pub(crate) fn delete_faces_for_files(file_ids: &[i64]) -> Result<(), String> {
    with_store(|store| {
        store
            .db()
            .delete_face_index_for_files(file_ids)
            .map_err(|error| format!("delete face index for files: {error}"))
    })
}

/// Runs the vendored upstream clustering over the faces in the store at
/// `distance_threshold` and returns the compact summary JSON
/// `{"faces":N,"clusters":M,"sizes":[s1,s2,...]}` with sizes descending.
///
/// The flow is the upstream one, end to end:
/// `MlDb::get_faces_for_clustering()` (the upstream quality-filtered read)
/// → `MlStore::recluster_faces(...)` — which drives
/// `face_clustering::recluster` (upstream's own recommended default flow
/// for a full recluster) and PERSISTS the assignments, cluster summaries
/// and centroid vectors through the upstream write paths. Only the
/// summary JSON is assembled here; the math and the writes are entirely
/// the vendored crate's. `file_creation_times` is empty because the r23
/// wire carries no capture instants (upstream treats unknown times
/// first-class).
pub(crate) fn cluster(distance_threshold: f64) -> Result<String, String> {
    with_store(|store| {
        let face_ids: Vec<String> = store
            .db()
            .get_faces_for_clustering()
            .map_err(|error| format!("read faces for clustering: {error}"))?
            .into_iter()
            .map(|face| face.face_id)
            .collect();
        let assignments = store
            .recluster_faces(&face_ids, &HashMap::new(), distance_threshold)
            .map_err(|error| format!("cluster faces: {error}"))?;

        let mut sizes: HashMap<&str, usize> = HashMap::new();
        for cluster_id in assignments.values() {
            *sizes.entry(cluster_id.as_str()).or_default() += 1;
        }
        let mut sizes: Vec<usize> = sizes.into_values().collect();
        sizes.sort_unstable_by(|left, right| right.cmp(left));

        let sizes_json = sizes
            .iter()
            .map(usize::to_string)
            .collect::<Vec<_>>()
            .join(",");
        Ok(format!(
            "{{\"faces\":{},\"clusters\":{},\"sizes\":[{sizes_json}]}}",
            assignments.len(),
            sizes.len()
        ))
    })
}

/// Closes the store (flushing and releasing the ente-vecdb index handles
/// via `MlStore::release`). Returns `true` when a store existed and was
/// closed, `false` when none did; either way the store ends up closed.
pub(crate) fn close() -> bool {
    let mut guard = lock();
    let Some(state) = guard.take() else {
        return false;
    };
    let _ = state.store.release();
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn open_temp_store(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "toad-ml-jni-store-test-{tag}-{id}",
            id = std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp store dir");
        open(dir.to_str().expect("utf8 temp path")).expect("store opens");
        dir
    }

    /// Serializes store-state tests across the process's test threads
    /// (the store cell is process-global).
    static STORE_TESTS: Mutex<()> = Mutex::new(());

    #[test]
    fn store_lifecycle_open_put_count_cluster_close() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);

        // Closed by default; ops before open fail:
        close();
        assert!(face_count().is_err());

        let dir = open_temp_store("lifecycle");
        // Idempotent open:
        assert!(open(dir.to_str().unwrap()).is_ok());
        assert!(open(dir.to_str().unwrap()).is_ok());

        // putFace validates the dimension:
        assert!(put_face(1, &[0.0; FACE_EMBEDDING_DIM - 1]).is_err());
        assert!(put_face(1, &[0.0; FACE_EMBEDDING_DIM + 1]).is_err());

        // Two distinct faces in one direction, two in another:
        let mut a = [0.0f32; FACE_EMBEDDING_DIM];
        a[0] = 1.0;
        let mut b = [0.0f32; FACE_EMBEDDING_DIM];
        b[1] = 1.0;
        assert_eq!(put_face(1, &a).unwrap(), 1);
        assert_eq!(put_face(2, &a).unwrap(), 1);
        assert_eq!(put_face(2, &b).unwrap(), 2);
        assert_eq!(face_count().unwrap(), 3);

        let summary = cluster(0.24).unwrap();
        assert_eq!(summary, "{\"faces\":3,\"clusters\":2,\"sizes\":[2,1]}");

        // Count reflects the quality-filtered upstream definition; the
        // pass-through rows stay visible:
        assert_eq!(face_count().unwrap(), 3);

        // Close is idempotent; a later open reopens cleanly:
        assert!(close());
        assert!(!close());
        assert!(face_count().is_err());
        assert!(open(dir.to_str().unwrap()).is_ok());
        assert_eq!(face_count().unwrap(), 3);
        close();

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn identical_ordinal_upserts_do_not_duplicate_faces() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("upsert");
        let embedding = [0.0f32; FACE_EMBEDDING_DIM];
        assert_eq!(put_face(7, &embedding).unwrap(), 1);
        assert_eq!(put_face(7, &embedding).unwrap(), 2);
        assert_eq!(face_count().unwrap(), 2);
        close();

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn indexed_file_ids_and_delete_for_files_round_trip() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        // Store ops before open fail (the NOT_OPEN leg for the r24 fns):
        assert!(indexed_face_file_ids().is_err());
        assert!(delete_faces_for_files(&[1]).is_err());

        let dir = open_temp_store("r24-delete");
        assert!(indexed_face_file_ids().unwrap().is_empty());

        // Faces on files 1 (two faces), 2 (one) and 3 (one):
        let mut a = [0.0f32; FACE_EMBEDDING_DIM];
        a[0] = 1.0;
        let mut b = [0.0f32; FACE_EMBEDDING_DIM];
        b[1] = 1.0;
        assert_eq!(put_face(1, &a).unwrap(), 1);
        assert_eq!(put_face(1, &b).unwrap(), 2);
        assert_eq!(put_face(2, &a).unwrap(), 1);
        assert_eq!(put_face(3, &a).unwrap(), 1);

        let mut indexed = indexed_face_file_ids().unwrap();
        indexed.sort_unstable();
        assert_eq!(indexed, vec![1, 2, 3]);

        // The empty slice is upstream's own documented no-op:
        assert!(delete_faces_for_files(&[]).is_ok());
        assert_eq!(face_count().unwrap(), 4);

        // Per-file invalidation: drop 1 and 3, keep 2:
        assert!(delete_faces_for_files(&[1, 3]).is_ok());
        assert_eq!(indexed_face_file_ids().unwrap(), vec![2]);
        assert_eq!(face_count().unwrap(), 1);

        // Re-index after delete restarts ordinals at 1 (no face_id PK
        // collision with the deleted rows --- the delete-then-insert
        // contract the background queue depends on):
        assert_eq!(put_face(3, &b).unwrap(), 1);
        assert_eq!(face_count().unwrap(), 2);
        let mut indexed = indexed_face_file_ids().unwrap();
        indexed.sort_unstable();
        assert_eq!(indexed, vec![2, 3]);

        // Deleting a file with no rows stays a no-op success:
        assert!(delete_faces_for_files(&[99]).is_ok());
        assert_eq!(face_count().unwrap(), 2);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
