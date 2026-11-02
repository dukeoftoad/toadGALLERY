# Phase 5 workstream 2 — Ente source pin (r21 round)

**Pinned upstream commit:** `423b2d80d9ec89d45f5680cb1e1d777bac225f41`
(ente/ente `main`, pinned 2026-11-02).

The pin is authoritative for EVERYTHING derived from upstream: the vendored
`engine/` crates, the Kotlin `ModelManifest` literals, and (in later rounds)
the ML-DB/vector-store extraction and any behavioral thresholds quoted from
Ente source. Never mix code or values from a different commit into this pin.

## What this round vendored (workstream 2, first slice)

| Upstream | Vendored location | State |
| --- | --- | --- |
| `rust/crates/ml` | `engine/crates/ente-ml` | compiles (`cargo check --workspace` green, host target) |
| `rust/crates/assets` | `engine/crates/ente-assets` | compiles |
| `rust/crates/image` | `engine/crates/ente-image` | compiles (keeps upstream's git-pinned heic-decoder dep) |

Adaptation record: `engine/NOTICE.md` (authoritative, diffable against the
pin). Compliance record: `docs/licensing-and-attribution.md` §2/§5.
Delivery policy for model assets: ADR-0020.

## What this round pinned in Kotlin

- `ml/engine/ModelManifest.kt` — the 11-asset catalog (5 core + 6 pet)
  transcribed from the pinned `assets.rs`: all eleven entries match the
  catalog name+size+SHA-256 exactly and are pinned by `ModelProvisionerTest`
  (the pre-merge cross-check against the Rust catalog caught a file-name
  drift in the six pet entries and corrected them to the catalog names).
- `ml/engine/ModelVerifier.kt` + `ml/engine/ModelProvisioner.kt` — the
  verify-then-stage-then-commit install mechanics (net-new Kotlin, upstream
  semantics), transport abstracted behind `ModelDownloader`.

## Deferred (named, ordered)

1. **r22 — HTTP downloader + JNI/UniFFI bridge spike.** `OkHttpModelDownloader`
   behind the existing `ModelDownloader` interface with the ADR-0020 host
   allowlist + egress tests; INTERNET permission lands HERE only; the Rust
   `ente-ml` crate built for Android ABIs via cargo-ndk in CI; one
   byte-input inference through a narrow JNI facade.
2. **r22+ — photos-side extraction.** `rust/crates/photos` `ml_db`,
   `ml_store`, `face_clustering` + `rust/crates/vecdb` vendored as a fourth
   engine crate. Self-containment check at the pin, corrected by re-grep:
   NOT fully self-contained — the trio's production code imports the host
   crate's `db` module (`crate::db::{Connection, Database, OpenOptions, …}`
   in `ml_db/mod.rs`, `ml_db/migrations.rs` and the `ml_db/queries/*`), with
   more of the same inside `#[cfg(test)]` blocks — so the extraction must
   vendor `db` (or the needed subset) alongside the trio, or port the trio
   onto an engine-owned DB seam. `rust/crates/vecdb` exists at the pin.
3. Later — model pack CI target for the offline release channel (ADR-0020 §7).

## Regeneration procedure (moving the pin)

1. Sparse-clone ente/ente at the new commit; extract the same crate set.
2. Re-apply the `engine/NOTICE.md` adaptation list (workspace root, license
   fields, directory names); diff every vendored file against the old pin.
3. Re-transcribe `ModelManifest` literals from the new `assets.rs`; update the
   `ModelProvisionerTest` pin literals.
4. `cargo check --workspace`; full JVM suite.
5. Update this file, `engine/NOTICE.md`, and the licensing ledger §5.

## Fallback verification (no sparse clone)

The pin does not require a working sparse checkout to verify or re-extract.
Verified 2026-11-02 against a local clone checked out at the pin:

- `git ls-tree <pin> --name-only rust/crates/` confirms `ml`, `assets` and
  `image` all exist at the commit (alongside the rest of the monorepo).
- `git archive <pin> rust/crates/ml rust/crates/assets rust/crates/image |
  tar -x` reproduces the exact upstream bytes for diffing — the r21
  byte-identity claims were checked this way (only `Cargo.toml` license
  fields differ per crate).
- Last resort: the GitHub zip archive of the monorepo at the pin
  (`ente-ente-<short-sha>.zip` — the `rust/` subtree of that snapshot)
  yields the same commit's bytes; extract the same crate set from it.
