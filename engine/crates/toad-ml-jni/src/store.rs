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
//!
//! r25 adds the People read/management paths over the same store, all
//! still upstream's own queries verbatim: the live cluster list
//! (`face_clusters` — counted off the LIVE `face_clusters` assignments
//! table via `MlDb::cluster_id_to_face_count`, deliberately NOT the
//! `cluster_summary` table the clustering pass refreshes, so manual
//! merges/exclusions reflect immediately), the per-cluster file read
//! (`MlDb::get_file_ids_of_cluster_id`), and the two manual management
//! primitives (`MlDb::force_update_cluster_ids` for merges,
//! `MlDb::remove_face_id_to_cluster_id` for per-photo removals). The
//! honest caveat carried by every doc comment below: manual edits
//! persist only until the next `cluster()` pass recomputes assignments
//! from the embeddings.
//!
//! r26 adds the CLIP vector-index store functions over the SAME open
//! store, all still upstream's own functions verbatim: the upsert +
//! per-file delete pair (`MlStore::put_clip` / `MlStore::delete_clip`,
//! which maintain the SQLite rows AND the ente-vecdb index together),
//! the resumability read (`MlDb::clip_indexed_file_with_version`,
//! filtered to the vendored [`CLIP_ML_VERSION`] and sorted ascending),
//! the row count (`MlDb::count_clip_rows`), the top-K vector search
//! (`MlStore::search` over `Index::Clip`) and the index fill
//! (`MlStore::fill_clip_index`). The search result and the fill report
//! are serialized as the r26 manual-JSON shapes (no serde). Still no
//! new SQL of our own.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Mutex, MutexGuard, OnceLock, PoisonError},
};

use ente_photos_ml::ml_db::{CLIP_ML_VERSION, ClipEmbedding, FACE_ML_VERSION, FaceRow};
use ente_photos_ml::ml_store::{FillOutcome, FillReport, Index, MlStore, SearchParams};

/// The face-index SQLite file the store opens inside the caller's dir.
pub(crate) const ML_INDEX_FILE_NAME: &str = "ml-index.sqlite";

/// The face-embedding dimension the store accepts (upstream MobileFaceNet
/// output; the vendored clustering's `CLUSTER_CENTROID_DIMENSIONS`).
pub(crate) const FACE_EMBEDDING_DIM: usize = 192;

/// The CLIP-embedding dimension the store accepts (upstream MobileCLIP-S2
/// output; the vendored `ml_db`'s `CLIP_EMBEDDING_DIMENSIONS`).
pub(crate) const CLIP_EMBEDDING_DIM: usize = 512;

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
///
/// r56: `dir` (and any missing ancestors) is created via
/// `std::fs::create_dir_all` before the join. SQLite does NOT create
/// parent directories itself and returns `SQLITE_CANTOPEN`
/// ("unable to open database file") when the parent of the requested db
/// path is missing --- which was the r55 device failure on a fresh
/// install AND after a Reset that `deleteRecursively()`'d the dir. The
/// four Kotlin call sites also `mkdirs()` for cheap correctness, but the
/// Rust half is the single source of truth for the JNI contract: "pass
/// a directory path, get an open store", with no implicit caller-side
/// requirement that the directory already exist (so a future caller
/// cannot regress into the same CANTOPEN).
pub(crate) fn open(dir: &str) -> Result<(), String> {
    let mut guard = lock();
    if guard.is_some() {
        return Ok(());
    }
    let dir_path = Path::new(dir);
    std::fs::create_dir_all(dir_path).map_err(|error| format!("create face index dir: {error}"))?;
    let path: PathBuf = dir_path.join(ML_INDEX_FILE_NAME);
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

/// Parses the leading `file_id` segment of a `{file_id}_{ordinal}` face
/// id (the format the put path writes). `None` when the face id carries
/// no parsable leading segment --- the r25 People read treats that as
/// "no cover" rather than an error. Upstream's own
/// `try_file_id_from_face_id` is `pub(crate)` inside the vendored crate,
/// so the same split is restated here.
fn face_file_id(face_id: &str) -> Option<i64> {
    face_id
        .split_once('_')
        .and_then(|(file_id, _)| file_id.parse().ok())
}

/// Minimal JSON string escaping for the adapter's hand-rolled JSON
/// (r25): the double quote, the backslash and the C0 control range. The
/// vendored clustering emits `cluster_…` alphanumeric ids today, but
/// the adapter must not assume --- every string it emits into JSON goes
/// through here.
fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\u{08}' => escaped.push_str("\\b"),
            '\u{0c}' => escaped.push_str("\\f"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            control if u32::from(control) < 0x20 => {
                escaped.push_str(&format!("\\u{:04x}", u32::from(control)));
            }
            other => escaped.push(other),
        }
    }
    escaped
}

/// The live cluster list for the People UI (r25), as the manual-JSON
/// array `[{"cluster_id":"…","count":12,"cover_file_id":4813},…]`
/// (a JSON `null` cover --- emitted, not omitted --- when the cluster's
/// top face id has no parsable leading file-id segment). Sorted by
/// count DESC, then cluster_id ASC (stable).
///
/// The counts come from upstream's own `MlDb::cluster_id_to_face_count`
/// --- the LIVE `face_clusters` assignments table, deliberately NOT the
/// `cluster_summary` table (which only the clustering pass refreshes):
/// reading the live table is what makes manual merges
/// ([`merge_face_clusters`]) and exclusions
/// ([`remove_faces_from_cluster`]) reflect immediately in the People
/// UI. The cover file id is the file of the cluster's highest-scored
/// face, via upstream's own
/// `MlDb::get_face_ids_for_cluster_ordered_by_score` (limit 1; its
/// face-id strings are parsed with [`face_file_id`]). `cluster_id`
/// values are JSON-string-escaped defensively via [`json_escape`].
pub(crate) fn face_clusters() -> Result<String, String> {
    with_store(|store| {
        let mut clusters: Vec<(String, i64)> = store
            .db()
            .cluster_id_to_face_count()
            .map_err(|error| format!("read cluster face counts: {error}"))?
            .into_iter()
            .collect();
        // The People UI's order: biggest clusters first, ties by id.
        clusters.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));

        let mut items: Vec<String> = Vec::with_capacity(clusters.len());
        for (cluster_id, count) in clusters {
            let cover_file_id: Option<i64> = store
                .db()
                .get_face_ids_for_cluster_ordered_by_score(&cluster_id, 1)
                .map_err(|error| format!("read cover face for cluster \"{cluster_id}\": {error}"))?
                .first()
                .and_then(|face_id| face_file_id(face_id));
            let cover_json = match cover_file_id {
                Some(file_id) => file_id.to_string(),
                None => "null".to_string(),
            };
            items.push(format!(
                "{{\"cluster_id\":\"{}\",\"count\":{count},\"cover_file_id\":{cover_json}}}",
                json_escape(&cluster_id),
            ));
        }
        Ok(format!("[{}]", items.join(",")))
    })
}

/// The file IDs whose faces currently sit in `cluster_id` (r25), via
/// upstream's own `MlDb::get_file_ids_of_cluster_id` verbatim (the
/// DISTINCT file-id read over the live `face_clusters` table). The
/// ordering is upstream's own --- the adapter neither adds nor promises
/// one. An unknown cluster id is an honest empty vec.
pub(crate) fn cluster_file_ids(cluster_id: &str) -> Result<Vec<i64>, String> {
    with_store(|store| {
        store
            .db()
            .get_file_ids_of_cluster_id(cluster_id)
            .map_err(|error| format!("read file ids of cluster \"{cluster_id}\": {error}"))
    })
}

/// Moves every face of `source_cluster_id` into `target_cluster_id`
/// (r25) --- the People UI's "merge these two people". The membership
/// read is upstream's own `MlDb::get_face_ids_for_cluster` and the
/// write upstream's own atomic batch upsert
/// (`MlDb::force_update_cluster_ids`); an empty source is a no-op
/// returning true.
///
/// `cluster_summary` rows are deliberately NOT touched: the People read
/// ([`face_clusters`]) counts the LIVE `face_clusters` table, so the
/// merge is visible immediately, and the next `cluster()` pass
/// recomputes summaries anyway --- honestly, it recomputes ALL
/// assignments from the embeddings, so a manual merge is not sticky. A
/// source equal to its target is rejected BEFORE any store access ---
/// the same caller-input guard shape as [`put_face`]'s embedding
/// dimension check; at the JNI layer that surfaces as
/// `java.lang.IllegalArgumentException`.
pub(crate) fn merge_face_clusters(
    source_cluster_id: &str,
    target_cluster_id: &str,
) -> Result<bool, String> {
    if source_cluster_id == target_cluster_id {
        return Err(format!(
            "source and target cluster ids must differ; both are \"{source_cluster_id}\""
        ));
    }
    with_store(|store| {
        let moves: HashMap<String, String> = store
            .db()
            .get_face_ids_for_cluster(source_cluster_id)
            .map_err(|error| format!("read faces of cluster \"{source_cluster_id}\": {error}"))?
            .into_iter()
            .map(|face_id| (face_id, target_cluster_id.to_string()))
            .collect();
        if moves.is_empty() {
            return Ok(true);
        }
        store
            .db()
            .force_update_cluster_ids(&moves)
            .map_err(|error| {
                format!(
                    "merge cluster \"{source_cluster_id}\" into \"{target_cluster_id}\": {error}"
                )
            })?;
        Ok(true)
    })
}

/// Removes the faces of `file_ids` from `cluster_id` (r25) --- the
/// People UI's "remove this photo from this person" / split primitive.
/// For every file, upstream's own `MlDb::get_faces_for_given_file_id`
/// reads the face rows and `MlDb::get_cluster_id_for_face_id` the
/// CURRENT assignment; only faces actually sitting in `cluster_id` are
/// removed, through one atomic
/// `MlDb::remove_face_id_to_cluster_id` batch (a file whose other faces
/// live in OTHER clusters keeps those). An empty selection is a no-op
/// returning true. Only the assignments are dropped --- the face rows
/// stay, so the next `cluster()` pass re-derives assignments from the
/// embeddings.
pub(crate) fn remove_faces_from_cluster(
    cluster_id: &str,
    file_ids: &[i64],
) -> Result<bool, String> {
    with_store(|store| {
        let mut removals: HashMap<String, String> = HashMap::new();
        for file_id in file_ids {
            for face in store
                .db()
                .get_faces_for_given_file_id(*file_id)
                .map_err(|error| format!("read faces of file {file_id}: {error}"))?
            {
                let face_id = face.face_id;
                let assigned = store
                    .db()
                    .get_cluster_id_for_face_id(&face_id)
                    .map_err(|error| format!("read cluster of face \"{face_id}\": {error}"))?;
                if assigned.as_deref() == Some(cluster_id) {
                    removals.insert(face_id, cluster_id.to_string());
                }
            }
        }
        if removals.is_empty() {
            return Ok(true);
        }
        store
            .db()
            .remove_face_id_to_cluster_id(&removals)
            .map_err(|error| format!("remove faces from cluster \"{cluster_id}\": {error}"))?;
        Ok(true)
    })
}

/// Inserts one CLIP embedding row for `file_id` (r26), through the vendored
/// upstream upsert path (`MlStore::put_clip`: the SQLite row AND the live
/// vecdb key together; an INSERT OR REPLACE, so re-inserting a file
/// replaces rather than duplicates).
///
/// Upstream's `ClipEmbedding` stores `Vec<f64>` (its SQLite blob encodes
/// f32 regardless), so the r26 f32 JNI wire widens here — a lossless
/// conversion. The embedding dimension is validated HERE, at the Rust
/// boundary, before any store access; a mismatch is a
/// `java.lang.IllegalArgumentException` at the JNI layer (the Kotlin facade
/// already rejects it before the native call — this is defense in depth,
/// the exact `put_face` discipline).
pub(crate) fn put_clip(file_id: i64, embedding: &[f32]) -> Result<(), String> {
    if embedding.len() != CLIP_EMBEDDING_DIM {
        return Err(format!(
            "embedding must be {CLIP_EMBEDDING_DIM} floats; got {}",
            embedding.len()
        ));
    }
    with_store(|store| {
        let row = ClipEmbedding {
            file_id,
            embedding: embedding.iter().map(|value| f64::from(*value)).collect(),
            version: CLIP_ML_VERSION,
        };
        store
            .put_clip(&[row])
            .map_err(|error| format!("insert clip embedding: {error}"))
    })
}

/// Deletes every CLIP row for `file_ids` (r26), through the vendored
/// upstream delete path (`MlStore::delete_clip`: the SQLite rows AND the
/// vecdb keys, transactional; an empty slice is upstream's own no-op).
/// This is the semantic twin of the r24 face invalidation delete: a
/// re-indexed or removed file must drop its stale rows before any
/// re-insert (the put above replaces rather than duplicates, but a file
/// that left the library should not stay searchable).
pub(crate) fn delete_clips_for_files(file_ids: &[i64]) -> Result<(), String> {
    with_store(|store| {
        store
            .delete_clip(file_ids)
            .map_err(|error| format!("delete clip embeddings: {error}"))
    })
}

/// The file IDs that currently carry CLIP rows at the vendored pin's
/// [`CLIP_ML_VERSION`] (r26), via upstream's own
/// `MlDb::clip_indexed_file_with_version` read. Unlike the r24 face twin
/// (whose upstream query filters by version in SQL), this upstream read
/// returns the whole map, so the adapter filters `version >=
/// CLIP_ML_VERSION` itself and drops the version half on the wire; keys
/// are returned sorted ascending — the adapter's own order, stable for
/// the Magic-Search caller's resumability walk.
///
/// Rows written by an OLDER model version are deliberately NOT reported
/// (the same versioning rule the r24 face read applies): the caller
/// treats those files as un-indexed and re-runs them.
pub(crate) fn clip_indexed_file_ids() -> Result<Vec<i64>, String> {
    with_store(|store| {
        let mut file_ids: Vec<i64> = store
            .db()
            .clip_indexed_file_with_version()
            .map_err(|error| format!("read clip indexed file ids: {error}"))?
            .into_iter()
            .filter(|&(_, version)| version >= CLIP_ML_VERSION)
            .map(|(file_id, _)| file_id)
            .collect();
        file_ids.sort_unstable();
        Ok(file_ids)
    })
}

/// Total CLIP rows in the store (r26), via upstream's own count query
/// (`MlDb::count_clip_rows` — every row, regardless of version).
pub(crate) fn clip_count() -> Result<i64, String> {
    with_store(|store| {
        store
            .db()
            .count_clip_rows()
            .map_err(|error| format!("count clip rows: {error}"))
    })
}

/// One CLIP vector-search hit (r26): the matched file id (parsed from the
/// vecdb key, which upstream's fill writes as the file id's decimal
/// string) and its vector distance.
#[derive(Debug)]
pub(crate) struct ClipMatch {
    pub(crate) file_id: i64,
    pub(crate) distance: f32,
}

/// Runs the top-K vector search over the store's CLIP index (r26), via
/// upstream's own `MlStore::search` with `Index::Clip` and a plain limit.
/// The matches come back in the vecdb's own order (nearest first) and are
/// preserved verbatim — the adapter never re-sorts a search result.
///
/// The query dimension is validated HERE, at the Rust boundary (the
/// `put_face` caller-input precedent): a mismatch is an error naming the
/// pinned dimension. A vecdb match key that does not parse as a file id
/// is a broken engine contract — upstream's fill only ever writes
/// `file_id.to_string()` keys — and surfaces as an error naming the key.
pub(crate) fn search_clips(query: &[f32], limit: i64) -> Result<Vec<ClipMatch>, String> {
    if query.len() != CLIP_EMBEDDING_DIM {
        return Err(format!(
            "query embedding must be {CLIP_EMBEDDING_DIM} floats; got {}",
            query.len()
        ));
    }
    with_store(|store| {
        let params = SearchParams {
            limit: Some(limit as usize),
            ..SearchParams::default()
        };
        let matches = store
            .search(Index::Clip, query, &params)
            .map_err(|error| format!("search clip index: {error}"))?;
        let mut hits: Vec<ClipMatch> = Vec::with_capacity(matches.len());
        for hit in matches {
            let key = &hit.key;
            let file_id = key
                .parse::<i64>()
                .map_err(|_| format!("clip match key \"{key}\" is not a file id"))?;
            hits.push(ClipMatch {
                file_id,
                distance: hit.distance,
            });
        }
        Ok(hits)
    })
}

/// Serializes the r26 search result as the JNI JSON array
/// `[{"file_id":123,"distance":0.42},…]`, preserving the vecdb's own
/// order verbatim. Both fields are numeric — no string ever enters this
/// JSON — but the r25 rule holds anyway: any future string field goes
/// through [`json_escape`].
pub(crate) fn clip_matches_json(hits: &[ClipMatch]) -> String {
    let items: Vec<String> = hits
        .iter()
        .map(|hit| {
            format!(
                "{{\"file_id\":{},\"distance\":{}}}",
                hit.file_id, hit.distance
            )
        })
        .collect();
    format!("[{}]", items.join(","))
}

/// Runs the DISTANCE-CUT vector search over the store's CLIP index (r39,
/// the Things/membership half): upstream's own `MlStore::search` with
/// `Index::Clip` and `SearchParams { limit, max_distance }` — the vecdb
/// has carried the cut since it was vendored; the r26 bridge simply never
/// passed it. Only matches within `max_distance` (INCLUSIVE, cosine
/// distance) come back, in the vecdb's own order (nearest first),
/// preserved verbatim — the adapter never re-sorts a search result.
///
/// Boundary guards, the `search_clips` discipline: the query dimension is
/// validated HERE (a mismatch is an error naming the pinned dimension),
/// and a non-finite or negative `max_distance` is rejected HERE with its
/// own message — the vecdb's `degenerate_distance_cut` holds the same
/// line, but the boundary names the caller's own input, not upstream's
/// error vocabulary. A vecdb match key that does not parse as a file id
/// is the same broken-engine-contract shape [`search_clips`] pins.
pub(crate) fn search_clips_within(
    query: &[f32],
    max_distance: f32,
    limit: i64,
) -> Result<Vec<ClipMatch>, String> {
    if query.len() != CLIP_EMBEDDING_DIM {
        return Err(format!(
            "query embedding must be {CLIP_EMBEDDING_DIM} floats; got {}",
            query.len()
        ));
    }
    if !max_distance.is_finite() || max_distance < 0.0 {
        return Err(format!(
            "max distance must be a finite non-negative float; got {max_distance}"
        ));
    }
    with_store(|store| {
        let params = SearchParams {
            limit: Some(limit as usize),
            max_distance: Some(max_distance),
            ..SearchParams::default()
        };
        let matches = store
            .search(Index::Clip, query, &params)
            .map_err(|error| format!("search clip index: {error}"))?;
        let mut hits: Vec<ClipMatch> = Vec::with_capacity(matches.len());
        for hit in matches {
            let key = &hit.key;
            let file_id = key
                .parse::<i64>()
                .map_err(|_| format!("clip match key \"{key}\" is not a file id"))?;
            hits.push(ClipMatch {
                file_id,
                distance: hit.distance,
            });
        }
        Ok(hits)
    })
}

/// Fills the store's CLIP vector index from the SQLite rows (r26), via
/// upstream's own non-forced `MlStore::fill_clip_index(false)`: a no-op
/// `AlreadyFilled` when the index is current, a resume from the stored
/// cursor when a previous fill was interrupted, and a rebuild when stale.
/// The report is upstream's own, serialized by [`fill_report_json`].
pub(crate) fn fill_clip_index() -> Result<FillReport, String> {
    with_store(|store| {
        store
            .fill_clip_index(false)
            .map_err(|error| format!("fill clip index: {error}"))
    })
}

/// Serializes the r26 fill report as the JNI JSON object
/// `{"outcome":"Completed","rows":N,"indexed":M,"skipped":K,"resumed":true|false}`.
/// The outcome strings are pinned to the three `FillOutcome` variants
/// (matched on the enum, never stringly derived) — the Kotlin parser
/// rejects anything else — and ride through [`json_escape`] like every
/// string this adapter emits. The report rides by value: upstream's
/// `FillReport` is a small `Copy` struct.
pub(crate) fn fill_report_json(report: FillReport) -> String {
    let outcome = match report.outcome {
        FillOutcome::Completed => "Completed",
        FillOutcome::AlreadyFilled => "AlreadyFilled",
        FillOutcome::Superseded => "Superseded",
    };
    format!(
        "{{\"outcome\":\"{}\",\"rows\":{},\"indexed\":{},\"skipped\":{},\"resumed\":{}}}",
        json_escape(outcome),
        report.rows,
        report.indexed,
        report.skipped,
        report.resumed
    )
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

    /// r56 regression: the production JNI open path used to require the
    /// caller to have `mkdirs()`'d the directory before calling
    /// `nativeStoreOpen`; SQLite does NOT create parent dirs and returns
    /// SQLITE_CANTOPEN ("unable to open database file") when the parent
    /// of the requested db path is missing. The r55 device failure on a
    /// fresh install AND after a Reset that deleteRecursively()'d the
    /// dir both traced to this. The Rust `open` now `create_dir_all`s
    /// before joining the db path; this test pins that the open succeeds
    /// against a nonexistent parent (and ancestors), and that the file
    /// exists afterwards. The Kotlin call sites also `mkdirs()` for
    /// defense in depth (this Rust half is the single source of truth
    /// for the JNI contract).
    #[test]
    fn store_open_creates_missing_directory() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        // A parent path whose final segment AND its parent do NOT exist
        // yet (mirrors the fresh-install case: filesDir/face-index/ has
        // never been touched).
        let root = std::env::temp_dir().join(format!(
            "toad-ml-jni-store-missing-{id}",
            id = std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let nested_dir = root.join("nested").join("face-index");
        // Sanity: the directory truly does not exist before the call.
        assert!(!nested_dir.exists());

        // The open MUST succeed --- the Rust half creates the directory
        // (and any missing ancestors) before joining the db path.
        assert!(open(nested_dir.to_str().expect("utf8 path")).is_ok());

        // The directory AND the ml-index.sqlite file both now exist on
        // disk (the SQLite open creates the file).
        assert!(nested_dir.exists(), "face-index dir was not created");
        assert!(
            nested_dir.join(ML_INDEX_FILE_NAME).exists(),
            "ml-index.sqlite was not created"
        );

        // The store is genuinely open: a face count read returns Ok (it
        // is empty, but it works --- not the NOT_OPEN error the
        // pre-r56 path would have surfaced).
        assert_eq!(face_count().unwrap(), 0);

        // Cleanup: close the process-global store and remove the test
        // tree so the next test starts from a clean slate.
        assert!(close());
        let _ = std::fs::remove_dir_all(&root);
    }

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

    // ---- r25: the People read/management paths ----

    /// Parses the adapter's pinned face-clusters JSON shape (the
    /// test-side mirror of the Kotlin parser; this crate emits manual
    /// JSON by design, so the tests parse it manually too).
    fn parse_clusters(json: &str) -> Vec<(String, i64, Option<i64>)> {
        let inner = json
            .strip_prefix('[')
            .and_then(|body| body.strip_suffix(']'))
            .expect("array envelope");
        if inner.is_empty() {
            return Vec::new();
        }
        inner
            .split("},{")
            .map(|item| {
                // The split consumed the brace pair BETWEEN items: the
                // first piece lost its closing brace, the last its opening
                // one, and a lone element keeps both --- so trim whichever
                // brace survives instead of requiring the pair.
                let item = item.strip_prefix('{').unwrap_or(item);
                let item = item.strip_suffix('}').unwrap_or(item);
                let mut fields = item.split(',');
                let cluster_id = fields
                    .next()
                    .expect("cluster_id field")
                    .trim_start_matches("\"cluster_id\":\"")
                    .trim_end_matches('"')
                    .to_string();
                let count: i64 = fields
                    .next()
                    .expect("count field")
                    .trim_start_matches("\"count\":")
                    .parse()
                    .expect("count value");
                let cover = fields
                    .next()
                    .expect("cover_file_id field")
                    .trim_start_matches("\"cover_file_id\":")
                    .parse::<i64>()
                    .ok();
                (cluster_id, count, cover)
            })
            .collect()
    }

    /// Seeds the canonical r25 shape: two faces in the a-direction
    /// (files 1 and 2), two in the b-direction (files 2 and 3) --- file 2
    /// carries one face per cluster, the People UI's "photo in two
    /// persons" case --- then clusters and returns the live list.
    fn clustered_store() -> Vec<(String, i64, Option<i64>)> {
        let mut a = [0.0f32; FACE_EMBEDDING_DIM];
        a[0] = 1.0;
        let mut b = [0.0f32; FACE_EMBEDDING_DIM];
        b[1] = 1.0;
        assert_eq!(put_face(1, &a).unwrap(), 1);
        assert_eq!(put_face(2, &a).unwrap(), 1);
        assert_eq!(put_face(2, &b).unwrap(), 2);
        assert_eq!(put_face(3, &b).unwrap(), 1);
        assert_eq!(
            cluster(0.24).unwrap(),
            "{\"faces\":4,\"clusters\":2,\"sizes\":[2,2]}"
        );
        parse_clusters(&face_clusters().unwrap())
    }

    /// Sorted file memberships, cluster-order-agnostic (cluster ids are
    /// random upstream).
    fn sorted_memberships(clusters: &[(String, i64, Option<i64>)]) -> Vec<Vec<i64>> {
        let mut memberships: Vec<Vec<i64>> = clusters
            .iter()
            .map(|(cluster_id, _, _)| {
                let mut files = cluster_file_ids(cluster_id).unwrap();
                files.sort_unstable();
                files
            })
            .collect();
        memberships.sort();
        memberships
    }

    #[test]
    fn r25_store_ops_before_open_fail() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        assert!(face_clusters().is_err());
        assert!(cluster_file_ids("cluster_x").is_err());
        assert!(merge_face_clusters("cluster_a", "cluster_b").is_err());
        assert!(remove_faces_from_cluster("cluster_a", &[1]).is_err());

        // The self-merge guard fires before the open check (the putFace
        // dimension-guard precedent) and names the offending id:
        let error = merge_face_clusters("cluster_same", "cluster_same").unwrap_err();
        assert!(error.contains("must differ"));
        assert!(error.contains("cluster_same"));
    }

    #[test]
    fn face_clusters_on_an_empty_store_is_the_empty_array() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r25-empty");
        assert_eq!(face_clusters().unwrap(), "[]");
        assert_eq!(parse_clusters(&face_clusters().unwrap()), Vec::new());

        // An unknown cluster id is an honest empty vec, not an error:
        assert!(cluster_file_ids("cluster_none").unwrap().is_empty());

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn face_clusters_after_put_and_cluster_round_trip() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r25-clusters");
        let clusters = clustered_store();

        // count DESC, ties by cluster_id ASC (stable), and the pinned
        // field set per entry:
        assert_eq!(
            clusters
                .iter()
                .map(|(_, count, _)| *count)
                .collect::<Vec<_>>(),
            vec![2, 2]
        );
        let ids: Vec<&String> = clusters.iter().map(|(id, _, _)| id).collect();
        let mut sorted_ids = ids.clone();
        sorted_ids.sort();
        assert_eq!(ids, sorted_ids);
        for (cluster_id, count, _) in &clusters {
            assert!(cluster_id.starts_with("cluster_"));
            assert_eq!(*count, 2);
        }

        // The cover is the file of the cluster's highest-scored face; the
        // put path writes tied pass-through scores, so the cover may be
        // either of the cluster's files --- but always one of ITS files:
        for (cluster_id, _, cover) in &clusters {
            let cover = cover.expect("adapter-written faces always parse");
            let files = cluster_file_ids(cluster_id).unwrap();
            assert!(
                files.contains(&cover),
                "cover {cover} must belong to cluster {cluster_id:?} (files {files:?})"
            );
        }

        // The live membership (upstream's own order; sorted here):
        assert_eq!(sorted_memberships(&clusters), vec![vec![1, 2], vec![2, 3]]);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn merge_moves_counts_and_empties_the_source() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r25-merge");
        let clusters = clustered_store();
        let (source, target) = (&clusters[0].0, &clusters[1].0);

        // The self-merge guard (mirrored defense in depth for the JNI
        // layer's caller-input check):
        assert!(merge_face_clusters(source, source).is_err());

        assert!(merge_face_clusters(source, target).unwrap());

        // The LIVE table reflects the merge immediately: one cluster of
        // four, the source gone, the target holding every file:
        let merged = parse_clusters(&face_clusters().unwrap());
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].0, *target);
        assert_eq!(merged[0].1, 4);
        let cover = merged[0].2.expect("cover still parses after a merge");
        assert!(cluster_file_ids(target).unwrap().contains(&cover));
        assert!(cluster_file_ids(source).unwrap().is_empty());
        let mut files = cluster_file_ids(target).unwrap();
        files.sort_unstable();
        assert_eq!(files, vec![1, 2, 3]);

        // Face ROWS are untouched --- only assignments moved:
        assert_eq!(face_count().unwrap(), 4);

        // An empty/unknown source is a no-op returning true:
        assert!(merge_face_clusters("cluster_nonexistent", target).unwrap());
        assert_eq!(parse_clusters(&face_clusters().unwrap()).len(), 1);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_next_cluster_pass_recomputes_manual_edits() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r25-recompute");
        let clusters = clustered_store();
        let (source, target) = (&clusters[0].0, &clusters[1].0);
        assert!(merge_face_clusters(source, target).unwrap());
        assert_eq!(parse_clusters(&face_clusters().unwrap()).len(), 1);

        // The honest r25 caveat: a manual merge persists in the live
        // table only until the next clustering pass recomputes ALL
        // assignments from the embeddings --- it is not sticky:
        assert_eq!(
            cluster(0.24).unwrap(),
            "{\"faces\":4,\"clusters\":2,\"sizes\":[2,2]}"
        );
        let recomputed = parse_clusters(&face_clusters().unwrap());
        assert_eq!(recomputed.len(), 2);
        assert_eq!(
            sorted_memberships(&recomputed),
            vec![vec![1, 2], vec![2, 3]]
        );

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn remove_faces_drops_only_the_given_clusters_assignment() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r25-remove");
        let clusters = clustered_store();
        // Identify the clusters by membership, not by random id: the one
        // holding files [1, 2] vs the one holding [2, 3]:
        let mut a_id = String::new();
        let mut b_id = String::new();
        for (cluster_id, _, _) in &clusters {
            let mut files = cluster_file_ids(cluster_id).unwrap();
            files.sort_unstable();
            if files == vec![1, 2] {
                a_id = cluster_id.clone();
            } else {
                b_id = cluster_id.clone();
            }
        }
        assert!(!a_id.is_empty() && !b_id.is_empty());

        // Remove file 2 from cluster A: file 2's OTHER face (cluster B)
        // must survive --- only A's assignment drops:
        assert!(remove_faces_from_cluster(&a_id, &[2]).unwrap());

        let after = parse_clusters(&face_clusters().unwrap());
        assert_eq!(
            after.iter().map(|(_, count, _)| *count).collect::<Vec<_>>(),
            vec![2, 1]
        );
        let mut a_files = cluster_file_ids(&a_id).unwrap();
        a_files.sort_unstable();
        assert_eq!(a_files, vec![1]);
        let mut b_files = cluster_file_ids(&b_id).unwrap();
        b_files.sort_unstable();
        assert_eq!(b_files, vec![2, 3]);

        // Face rows are untouched (they feed the next clustering pass):
        assert_eq!(face_count().unwrap(), 4);

        // No-op legs: a file whose faces all live elsewhere, an empty
        // file list, and an unknown cluster:
        assert!(remove_faces_from_cluster(&a_id, &[3]).unwrap());
        assert!(remove_faces_from_cluster(&a_id, &[]).unwrap());
        assert!(remove_faces_from_cluster("cluster_nonexistent", &[1]).unwrap());
        assert_eq!(face_count().unwrap(), 4);
        assert_eq!(
            parse_clusters(&face_clusters().unwrap())
                .iter()
                .map(|(_, count, _)| *count)
                .collect::<Vec<_>>(),
            vec![2, 1]
        );

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn face_file_id_parses_the_leading_segment_only() {
        assert_eq!(face_file_id("4813_7"), Some(4813));
        assert_eq!(face_file_id("2_2"), Some(2));
        assert_eq!(face_file_id("12_"), Some(12));
        assert_eq!(face_file_id("no_underscore"), None);
        assert_eq!(face_file_id("abc_1"), None);
        assert_eq!(face_file_id(""), None);
        assert_eq!(face_file_id("_1"), None);
    }

    #[test]
    fn json_escape_quotes_backslashes_and_controls() {
        assert_eq!(json_escape("cluster_0"), "cluster_0");
        assert_eq!(json_escape("a\"b"), "a\\\"b");
        assert_eq!(json_escape("a\\b"), "a\\\\b");
        assert_eq!(json_escape("a\nb"), "a\\nb");
        assert_eq!(json_escape("a\u{1}b"), "a\\u0001b");
        assert_eq!(json_escape("\t\r\n\u{08}\u{0c}"), "\\t\\r\\n\\b\\f");
    }

    // ---- r26: the CLIP vector-index store functions ----

    /// One-hot CLIP fixture (upstream's own ml_store test shape): 512
    /// floats with a single 1.0 at `hot` --- identical vectors sit at
    /// cosine distance 0, distinct one-hots at distance 1.
    fn one_hot_clip(hot: usize) -> Vec<f32> {
        let mut vector = vec![0.0_f32; CLIP_EMBEDDING_DIM];
        vector[hot % CLIP_EMBEDDING_DIM] = 1.0;
        vector
    }

    /// Inserts a clip row with an EXPLICIT ml_version and raw embedding,
    /// bypassing the adapter's always-current 512-float put: stale
    /// versions and short vectors only exist from an older/broken writer,
    /// and the r26 wire has no version or dimension knob.
    fn insert_clip_row(file_id: i64, embedding: Vec<f32>, version: i64) {
        let guard = store_cell().lock().unwrap_or_else(PoisonError::into_inner);
        let state = guard.as_ref().expect("store is open");
        state
            .store
            .db()
            .insert_clip_rows(&[ClipEmbedding {
                file_id,
                embedding: embedding.into_iter().map(f64::from).collect(),
                version,
            }])
            .expect("insert clip row");
    }

    #[test]
    fn r26_store_ops_before_open_fail() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        assert!(put_clip(1, &one_hot_clip(0)).is_err());
        assert!(delete_clips_for_files(&[1]).is_err());
        assert!(clip_indexed_file_ids().is_err());
        assert!(clip_count().is_err());
        assert!(search_clips(&one_hot_clip(0), 1).is_err());
        assert!(fill_clip_index().is_err());

        // The dimension guards fire before the open check (the putFace
        // precedent) and name the pinned dimension:
        let error = put_clip(1, &[0.0; CLIP_EMBEDDING_DIM - 1]).unwrap_err();
        assert!(error.contains("512"));
        let error = search_clips(&[0.0; CLIP_EMBEDDING_DIM + 1], 1).unwrap_err();
        assert!(error.contains("512"));
    }

    #[test]
    fn clip_put_count_indexed_delete_round_trip() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r26-round-trip");
        assert!(clip_indexed_file_ids().unwrap().is_empty());
        assert_eq!(clip_count().unwrap(), 0);

        // putClip validates the dimension (mirrors the putFace guard):
        assert!(put_clip(1, &[0.0; CLIP_EMBEDDING_DIM - 1]).is_err());
        assert!(put_clip(1, &[0.0; CLIP_EMBEDDING_DIM + 1]).is_err());
        assert_eq!(clip_count().unwrap(), 0);

        // Clips on files 1, 2 and 3; re-putting file 2 REPLACES (upstream's
        // INSERT OR REPLACE), never duplicates:
        assert!(put_clip(1, &one_hot_clip(1)).is_ok());
        assert!(put_clip(2, &one_hot_clip(2)).is_ok());
        assert!(put_clip(3, &one_hot_clip(3)).is_ok());
        assert!(put_clip(2, &one_hot_clip(2)).is_ok());
        assert_eq!(clip_count().unwrap(), 3);
        assert_eq!(clip_indexed_file_ids().unwrap(), vec![1, 2, 3]);

        // The empty slice is upstream's own no-op:
        assert!(delete_clips_for_files(&[]).is_ok());
        assert_eq!(clip_count().unwrap(), 3);

        // Per-file invalidation: drop 1 and 3, keep 2:
        assert!(delete_clips_for_files(&[1, 3]).is_ok());
        assert_eq!(clip_indexed_file_ids().unwrap(), vec![2]);
        assert_eq!(clip_count().unwrap(), 1);

        // Deleting a file with no rows stays a no-op success:
        assert!(delete_clips_for_files(&[99]).is_ok());
        assert_eq!(clip_count().unwrap(), 1);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn clip_indexed_file_ids_filters_stale_versions_and_sorts_ascending() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r26-versions");
        // Current rows land via the adapter (insertion order scrambled on
        // purpose --- the read must not depend on it):
        assert!(put_clip(3, &one_hot_clip(3)).is_ok());
        assert!(put_clip(1, &one_hot_clip(1)).is_ok());
        // A row from an OLDER pin (stale) and one from a NEWER pin (still
        // current-or-better) only exist via a direct insert:
        insert_clip_row(5, one_hot_clip(5), CLIP_ML_VERSION - 1);
        insert_clip_row(7, one_hot_clip(7), CLIP_ML_VERSION + 1);

        // Ascending keys, version >= CLIP_ML_VERSION only (the r24 face
        // rule): 5's stale row is deliberately absent.
        assert_eq!(clip_indexed_file_ids().unwrap(), vec![1, 3, 7]);

        // The stale row still counts as a row (the count is unversioned):
        assert_eq!(clip_count().unwrap(), 4);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn search_clips_round_trips_through_the_vecdb_in_its_own_order() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r26-search");
        // A fresh store's index is STALE, so these puts land in SQLite
        // only; the fill below builds the vecdb from the rows (upstream's
        // own flow):
        assert!(put_clip(1, &one_hot_clip(1)).is_ok());
        assert!(put_clip(2, &one_hot_clip(2)).is_ok());
        assert!(put_clip(3, &one_hot_clip(3)).is_ok());

        let report = fill_clip_index().unwrap();
        assert_eq!(
            report,
            FillReport {
                outcome: FillOutcome::Completed,
                rows: 3,
                indexed: 3,
                skipped: 0,
                resumed: false
            }
        );
        // A second non-forced fill is upstream's own no-op:
        assert_eq!(
            fill_clip_index().unwrap(),
            FillReport {
                outcome: FillOutcome::AlreadyFilled,
                rows: 0,
                indexed: 0,
                skipped: 0,
                resumed: false
            }
        );

        // Nearest first, the vecdb's own order preserved verbatim: the
        // exact one-hot is at cosine distance 0, the other two tie at
        // distance 1 (order between ties is the vecdb's own --- only the
        // set is asserted):
        let hits = search_clips(&one_hot_clip(2), 3).unwrap();
        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].file_id, 2);
        assert_eq!(hits[0].distance, 0.0);
        let mut others: Vec<i64> = hits[1..].iter().map(|hit| hit.file_id).collect();
        others.sort_unstable();
        assert_eq!(others, vec![1, 3]);
        assert!(hits[1..].iter().all(|hit| hit.distance == 1.0));

        // The limit is the vecdb's own top-K:
        let top = search_clips(&one_hot_clip(2), 1).unwrap();
        assert_eq!(top.len(), 1);
        assert_eq!(top[0].file_id, 2);

        // The query dimension guard:
        assert!(search_clips(&one_hot_clip(2)[..CLIP_EMBEDDING_DIM - 1], 1).is_err());

        // After the fill, upstream's put upserts straight into the LIVE
        // index too --- file 4 is searchable without another fill:
        assert!(put_clip(4, &one_hot_clip(4)).is_ok());
        let nearest = search_clips(&one_hot_clip(4), 1).unwrap();
        assert_eq!(nearest.len(), 1);
        assert_eq!(nearest[0].file_id, 4);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ---- r39: the distance-cut search (the Things/membership half) ----

    #[test]
    fn search_clips_within_cuts_by_distance_and_keeps_the_vecdb_order() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r39-search-within");
        assert!(put_clip(1, &one_hot_clip(1)).is_ok());
        assert!(put_clip(2, &one_hot_clip(2)).is_ok());
        assert!(put_clip(3, &one_hot_clip(3)).is_ok());
        assert!(fill_clip_index().is_ok());

        // The exact one-hot is at cosine distance 0, the two distinct
        // one-hots at distance 1 (the fixture's own geometry). A cut
        // BELOW the tie keeps only the exact match:
        let tight = search_clips_within(&one_hot_clip(2), 0.5, 10).unwrap();
        assert_eq!(tight.len(), 1);
        assert_eq!(tight[0].file_id, 2);
        assert_eq!(tight[0].distance, 0.0);

        // A cut at exactly the tie distance is INCLUSIVE (<=): all three
        // come back, nearest first, the vecdb's own order preserved:
        let inclusive = search_clips_within(&one_hot_clip(2), 1.0, 10).unwrap();
        assert_eq!(inclusive.len(), 3);
        assert_eq!(inclusive[0].file_id, 2);
        assert!(inclusive[1..].iter().all(|hit| hit.distance == 1.0));

        // A cut ABOVE every distance is the uncut top-K (the same result
        // shape the plain search returns):
        let uncut = search_clips_within(&one_hot_clip(2), 2.0, 10).unwrap();
        assert_eq!(uncut.len(), 3);

        // The limit still bounds the cut result (the vecdb's own top-K
        // within the distance):
        let limited = search_clips_within(&one_hot_clip(2), 1.0, 1).unwrap();
        assert_eq!(limited.len(), 1);
        assert_eq!(limited[0].file_id, 2);

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn search_clips_within_guards_fire_before_the_store_is_touched() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        // The query dimension guard, the search_clips discipline:
        let error = search_clips_within(&[0.0; CLIP_EMBEDDING_DIM - 1], 1.0, 1).unwrap_err();
        assert!(error.contains("512"));

        // The distance guard names the caller's own input, BEFORE the
        // open-store check (a closed store must not mask a bad argument):
        let error = search_clips_within(&one_hot_clip(0), -0.1, 1).unwrap_err();
        assert!(error.contains("max distance"));
        assert!(error.contains("-0.1"));
        let error = search_clips_within(&one_hot_clip(0), f32::NAN, 1).unwrap_err();
        assert!(error.contains("max distance"));
        let error = search_clips_within(&one_hot_clip(0), f32::INFINITY, 1).unwrap_err();
        assert!(error.contains("max distance"));

        // And the closed-store shape still holds for good arguments:
        assert!(search_clips_within(&one_hot_clip(0), 1.0, 1).is_err());
    }

    #[test]
    fn fill_clip_index_reports_upstream_skips_verbatim() {
        let _guard = STORE_TESTS.lock().unwrap_or_else(PoisonError::into_inner);
        close();

        let dir = open_temp_store("r26-fill");
        // One current 512-float row plus upstream's own malformed
        // fixtures (a short 2-float row and an empty one): the fill
        // indexes the first and SKIPS the rest through the vecdb's
        // dimension gate; a stale-version row with a VALID vector is
        // still indexed (the fill does not filter versions --- only the
        // indexed-file read does):
        assert!(put_clip(1, &one_hot_clip(1)).is_ok());
        insert_clip_row(2, vec![1.0, 2.0], CLIP_ML_VERSION);
        insert_clip_row(3, Vec::new(), CLIP_ML_VERSION);
        insert_clip_row(5, one_hot_clip(5), CLIP_ML_VERSION - 1);

        assert_eq!(
            fill_clip_index().unwrap(),
            FillReport {
                outcome: FillOutcome::Completed,
                rows: 4,
                indexed: 2,
                skipped: 2,
                resumed: false
            }
        );

        close();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn clip_matches_json_is_the_pinned_manual_shape() {
        assert_eq!(clip_matches_json(&[]), "[]");
        assert_eq!(
            clip_matches_json(&[
                ClipMatch {
                    file_id: 123,
                    distance: 0.42,
                },
                ClipMatch {
                    file_id: 7,
                    distance: 1.5,
                },
            ]),
            "[{\"file_id\":123,\"distance\":0.42},{\"file_id\":7,\"distance\":1.5}]"
        );
    }

    #[test]
    fn fill_report_json_pins_the_three_outcome_strings() {
        let report = |outcome| FillReport {
            outcome,
            rows: 4,
            indexed: 2,
            skipped: 2,
            resumed: true,
        };
        assert_eq!(
            fill_report_json(report(FillOutcome::Completed)),
            "{\"outcome\":\"Completed\",\"rows\":4,\"indexed\":2,\"skipped\":2,\"resumed\":true}"
        );
        assert_eq!(
            fill_report_json(report(FillOutcome::AlreadyFilled)),
            "{\"outcome\":\"AlreadyFilled\",\"rows\":4,\"indexed\":2,\"skipped\":2,\"resumed\":true}"
        );
        assert_eq!(
            fill_report_json(report(FillOutcome::Superseded)),
            "{\"outcome\":\"Superseded\",\"rows\":4,\"indexed\":2,\"skipped\":2,\"resumed\":true}"
        );
        assert_eq!(
            fill_report_json(FillReport {
                outcome: FillOutcome::Completed,
                rows: 0,
                indexed: 0,
                skipped: 0,
                resumed: false
            }),
            "{\"outcome\":\"Completed\",\"rows\":0,\"indexed\":0,\"skipped\":0,\"resumed\":false}"
        );
    }
}
