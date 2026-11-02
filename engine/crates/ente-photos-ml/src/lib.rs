//! toadGALLERY vendored extract of the Ente Photos ML persistence +
//! clustering stack (see `engine/NOTICE.md`).
//!
//! Vendored from Ente's monorepo `rust/crates/photos` at pinned commit
//! `423b2d80d9ec89d45f5680cb1e1d777bac225f41`: the `db`, `ml_db`,
//! `ml_store` and `face_clustering` modules, byte-identical except for the
//! two `ente_core::crypto::random_bytes` imports (routed through the
//! net-new `crypto` seam below). Upstream gates the native-only modules
//! behind `#[cfg(not(target_arch = "wasm32"))]`; this crate is native-only,
//! so they are declared unconditionally.
//!
//! The `db` module is upstream's connection-pool wrapper over rusqlite;
//! `ml_db` is the SQLite schema + queries for the ML index (files, faces,
//! clusters, persons, CLIP); `ml_store` is the on-device vector store
//! (`ente-vecdb`) + fill/clustering orchestration; `face_clustering` is the
//! pure-math clustering used both directly and through `ml_store`.

pub mod crypto;
pub mod db;
pub mod face_clustering;
pub mod ml_db;
pub mod ml_store;
