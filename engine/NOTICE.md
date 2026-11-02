# engine/ — vendored Ente ML engine (NOTICE)

This directory is a vendored, adapted extract of the **Ente Photos** Rust ML
engine. It is **AGPL-3.0** licensed code (c) Ente Services Pvt. Ltd. and
contributors, incorporated into toadGALLERY per the maintainer's direction
(2026-11-02). See `docs/licensing-and-attribution.md` §2 and §5 for the compliance
record and `docs/integration-decisions.md` ADR-0020 for the delivery-policy
context.

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

Deferred to a later round (tracked in `docs/phase-5/ente-pin.md`): extraction
of `rust/crates/photos` `ml_db` / `ml_store` / `face_clustering` modules and
`ente-vecdb` into a fourth vendored crate; the Android JNI/UniFFI bridge; the
cargo-ndk ABI build wired into CI. When the ABI build lands, carry over the
Android rustflags from upstream `rust/.cargo/config.toml` (16 KB page-size
`max-page-size=16384` link args, required by Play Store from mid-2025) into
`engine/.cargo/config.toml` — they are irrelevant to the host-target CI gates
and therefore not vendored this round.

## Verification

Verified at the pin from `engine/`: `cargo check --workspace --all-targets`,
`cargo fmt --all -- --check`, and `cargo clippy --workspace` all pass (host
target). CI runs exactly these three commands on every PR
(`.github/workflows/ci.yml`, `engine` job).
