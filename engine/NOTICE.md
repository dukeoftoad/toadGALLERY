# engine/ — vendored Ente ML engine (NOTICE)

This directory is a vendored, adapted extract of the **Ente Photos** Rust ML
engine. It is **AGPL-3.0** licensed code (c) Ente Services Pvt. Ltd. and
contributors, incorporated into toadGALLERY per the maintainer's direction
(2026-11-02). The full AGPL-3.0 license text ships alongside this notice as
`engine/LICENSE`, so any redistribution of `engine/` carries the license
text itself, not only this grant pointer. See
`docs/licensing-and-attribution.md` §2 and §5 for the compliance record and
the per-artifact license offer, and `docs/integration-decisions.md` ADR-0020
for the delivery-policy context.

## Pinned source

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/ente/ente (monorepo) |
| Pinned commit | `423b2d80d9ec89d45f5680cb1e1d777bac225f41` |
| Pin date | 2026-11-02 |
| Upstream path | `rust/crates/ml`, `rust/crates/assets`, `rust/crates/image` |

The pin is authoritative: never "upgrade" by copying newer upstream files over
these. To move the pin, re-extract at a new commit, re-run the adaptation
checklist below, update `docs/phase-5/ente-pin.md`, and regenerate the model
manifest (the Kotlin `ModelManifest` must always match the pinned
`crates/ente-ml/src/assets.rs`).

## What was vendored

| Upstream crate | Vendored crate | Role |
| --- | --- | --- |
| `rust/crates/ml` | `engine/crates/ente-ml` | Inference engine: YOLO face detection + NMS, alignment, MobileFaceNet embeddings, MobileCLIP image/text encoders, ONNX Runtime provider policy, model catalog (`src/assets.rs`) |
| `rust/crates/assets` | `engine/crates/ente-assets` | Asset store primitives: download, size+SHA-256 verification, staged atomic install, archive extraction |
| `rust/crates/image` | `engine/crates/ente-image` | Byte-oriented decode (JPEG/HEIC/AVIF/JXL/PNG/TIFF/RAW via heic-decoder), colour management, bounded decode, re-encode |

## Adaptations from upstream (complete list)

1. New workspace root (`engine/Cargo.toml`): members reduced to the three
   vendored crates; `[workspace.dependencies]` subset carried over **verbatim**
   (versions unchanged); upstream `[workspace.lints]` carried over verbatim;
   `edition = "2024"` and the `profile.dev.package.ente-ml` opt-level kept.
   Upstream's `rust/clippy.toml` is vendored verbatim as `engine/clippy.toml`
   (`allow-expect-in-tests`/`allow-unwrap-in-tests`) so the clippy gate lints
   the vendored crates under Ente's own configuration. Upstream's
   `rust/.cargo/config.toml` and `rust/checks/` tooling are deliberately not
   vendored: nothing in them affects the host `cargo check`/`fmt`/`clippy`
   gates (see the deferral note below for the r22 Android rustflags).
   `engine/Cargo.lock` is a fresh lockfile resolved for the vendored subset
   (upstream's spans the whole monorepo); the resolution keeps the git-pinned
   `heic-decoder` rev, and upstream's `[patch.crates-io]` section (a
   tauri-plugin-updater pin) is not carried over — nothing in the vendored
   subset uses it.
2. `license = "AGPL-3.0"` added to `[workspace.package]` in `engine/Cargo.toml`
   and `license.workspace = true` added to each vendored crate's `[package]`
   (upstream omitted license fields; the crate-level declaration now points at
   AGPL-3.0).
3. Directory renames: `crates/assets` → `crates/ente-assets`,
   `crates/image` → `crates/ente-image` (crate names unchanged).
4. Everything else — including the git-pinned `heic-decoder` dependency
   (`ente/heic-decoder@2a20a208`) — is byte-identical to upstream at the pin.

Deferred to r23 and delivered below: extraction of `rust/crates/photos`
`ml_db` / `ml_store` / `face_clustering` modules and `ente-vecdb` into the
fourth and fifth vendored crates.

## r23 additions (the indexing round: persistence + clustering)

The r23 delivery round vendored the photos-side ML persistence + clustering
stack, at the same pin (`423b2d80d9ec89d45f5680cb1e1d777bac225f41`):

| Upstream source | Vendored location | Role |
| --- | --- | --- |
| `rust/crates/vecdb` (whole crate) | `engine/crates/ente-vecdb` | Append-only vector store (HNSW graph, snapshots, CRC-guarded log) used by the ML store |
| `rust/crates/photos` `src/db.rs` | `engine/crates/ente-photos-ml/src/db.rs` | Connection-pool wrapper over rusqlite (WAL writer + read-only readers, busy timeout) |
| `rust/crates/photos` `src/ml_db/**` | `engine/crates/ente-photos-ml/src/ml_db/` | SQLite schema, migrations and queries for the ML index (files, faces, clusters, persons, CLIP, caches) |
| `rust/crates/photos` `src/ml_store/**` | `engine/crates/ente-photos-ml/src/ml_store/` | On-device vector store orchestration: fill from `ml_db`, face-distance index, cluster averages, clustering driver |
| `rust/crates/photos` `src/face_clustering/**` | `engine/crates/ente-photos-ml/src/face_clustering/` | Pure-math face clustering (complete + incremental linear algorithms, cluster-id generation) |

`ente-photos-ml` is a NEW wrapper crate around those four module trees; its
`lib.rs` and `crypto.rs` are net-new toadGALLERY code (clearly headered in
file, like toad-ml-jni's). Byte-identity method: every vendored file diffed
against a `git archive FETCH_HEAD` extraction of the pin (see the audit note
below); upstream's own `#[cfg(test)]` tests are vendored unchanged and run
under `cargo test`.

Adaptations from upstream (complete list for r23):

1. The four photos modules' only external references outside their subtree
   are `crate::db::{...}` (internal to the new crate), `ente_vecdb`
   (workspace ref), and `ente_core::crypto::random_bytes` at exactly two
   call sites (`face_clustering/mod.rs:8`, `ml_store/face_distances.rs:5`).
   Instead of vendoring all of `ente-core` for one function, the two imports
   are rewritten to `use crate::crypto::random_bytes;` and a net-new
   `src/crypto.rs` seam reproduces `random_bytes` with byte-for-byte
   upstream semantics (`getrandom::fill` + `expect`, carrying upstream's own
   `#[expect(clippy::expect_used, reason = ...)]` attribute, which silences
   the workspace's `expect_used` deny lint). This 2-line import rewrite is
   the ONLY diff to the vendored module files.
2. `ente-vecdb`'s `Cargo.toml` gains `license.workspace = true` (AGPL-3.0
   declaration, same as the r21 crates); everything else in it is
   byte-identical to upstream (upstream already names the package
   `ente-vecdb` and uses `[lints] workspace = true`).
3. `engine/Cargo.toml`: `crates/ente-photos-ml` and `crates/ente-vecdb`
   added to members; `workspace.dependencies` gained `ente-photos-ml` /
   `ente-vecdb` path entries, `rusqlite = { version = "0.40.2",
   default-features = false, features = ["bundled"] }` (verbatim upstream),
   and `getrandom = "0.4.0"` — upstream's entry is `getrandom = { version =
   "0.4.0", features = ["wasm_js"] }`; the wasm_js feature is dropped
   because the engine workspace is native-only (the seam never compiles for
   wasm). `[profile.dev.package.ente-vecdb] opt-level = 3` added (verbatim
   upstream; keeps the vector-store tests fast).
4. Upstream gates `db`/`ml_db`/`ml_store` behind
   `#[cfg(not(target_arch = "wasm32"))]` in the photos `lib.rs`; the engine
   is native-only, so `ente-photos-ml/src/lib.rs` declares all four modules
   unconditionally (no vendored module file contains those cfgs).
5. `ente-photos-ml` declares exactly the dependencies the four modules use:
   `rusqlite` (upstream's effective resolution: default features on top of
   the workspace's bundled pin), `ente-vecdb`, `getrandom`, `log`,
   `thiserror`, plus `tempfile` as a dev-dependency for the vendored inline
   tests. No serde/serde_json/futures are needed (the modules use none).

Byte-identity audit method for r23 (re-runnable): extract the pin with
`git archive 423b2d80d9ec89d45f5680cb1e1d777bac225f41 -- rust/crates/vecdb
rust/crates/photos/src/{db.rs,ml_db,ml_store,face_clustering}` and diff each
vendored file against it; every file must be byte-identical except the two
recorded `random_bytes` import lines.

## r22 additions (not from upstream)

The r22 delivery round added three things to `engine/` that are **net-new
toadGALLERY code, not vendored** — they carry no upstream provenance:

1. `crates/toad-ml-jni/` — the JNI bridge crate (cdylib
   `libtoad_ml_jni.so`, AGPL-3.0 via `license.workspace = true`): a
   four-function surface over ente-ml's face pipeline. Its provenance,
   ORT dylib discovery order and build flow are documented in
   `crates/toad-ml-jni/README.md`.
2. `.cargo/config.toml` — **upstream-derived** (the one r22 file with
   upstream provenance): a subset of upstream `rust/.cargo/config.toml` @
   `423b2d80` — the `CARGO_CFG_TARGET_FEATURE` env workaround, the
   aarch64 `chacha20_force_neon` cfg, and the Android 16 KB page-size
   `max-page-size=16384` link args for aarch64/armv7/x86_64 — carried
   over so the Android ABI build links like upstream's own mobile build.
   Everything else in the upstream file (aliases, non-Android targets)
   is not vendored.
3. `scripts/build-android.sh` — the cargo-ndk Android build (NDK
   discovery + pinned fallback install, arm64-v8a / `--all` for x86_64)
   plus the ONNX Runtime supply: `libonnxruntime.so` comes from
   Microsoft's official 1.31.0 Android AAR on Maven Central
   (sha256-pinned in the script — the exact version the ort-sys
   2.0.0-rc.15 `api-31` bindings expect; the vendored crates use
   load-dynamic, so ort-sys's own Android dist (a static `.a`) is not
   usable and the shared library is a fetched supply, never a cargo
   artifact). Verified in the r22 local build proof: both staged `.so`
   files have 0x4000-aligned LOAD segments and the cdylib exports all
   four JNI symbols.

The workspace root gained the `jni` crate (pinned 0.21.1 in
`[workspace.dependencies]`) and the `crates/toad-ml-jni` member; the three
vendored crates are untouched (byte-identity per the adaptation list above
still holds).

## Verification

Verified at the pin from `engine/`: `cargo check --workspace --all-targets`,
`cargo fmt --all -- --check`, and `cargo clippy --workspace` all pass (host
target; the r22 additions pass the same gates). CI runs exactly these three
commands on every PR (`.github/workflows/ci.yml`, `engine` job), plus the
`engine-android` job for the ABI build and an engine-enabled assemble.
