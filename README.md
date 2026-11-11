# toadGALLERY --- `ente` (the upstream Ente integration lane)

**toadGALLERY** is a private, offline-first Android gallery family built from
scratch in Kotlin + Jetpack Compose (inspired by Mi Gallery, Aves libre, and
Fossify Gallery --- features and UX only, no upstream code, ADR-0008). The
full project story, screenshots, and goals live in the
[`main` README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md);
the authoritative policy is
[`docs/PROJECT_ARCHITECTURE.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/PROJECT_ARCHITECTURE.md).

**This branch is the upstream Ente synchronization lane** --- an upstream
source lane, **not a toadGALLERY product**.

---

## Branch identity card

| | |
|---|---|
| **Role** | Stage and validate selective **Ente upstream** synchronization before its contribution is merged into `unified`. |
| **What it is NOT** | A fifth product lane. There is no "Ente APK" product in the four-product family (Basic Gallery, Gallery Engine, Editor, Unified). Users who want the official Ente distribution obtain it from Ente directly --- this project does not mirror it. |
| **Push CI** | `ente-ci.yml` --- cargo gates + the engine-flavor gallery debug APK (validation only). |
| **Releases** | **Retired** (2026-11-10): `release-ente.yml` was deleted; Ente ships no toadGALLERY release. |
| **Flows to** | `unified`, via a reviewed PR when a synchronized upstream change is worth integrating. |

## The upstream policy in one paragraph

Ente is synchronized **selectively and occasionally** --- roughly annually,
or when a meaningful upstream change warrants it. Review upstream changes
selectively; never blind-import. Validate relevance, compatibility,
licensing/provenance, and project-specific behavior **on this lane** before
contributing the result into `unified`. Keep CI that protects the
synchronization, but never grow a product release lane out of it.

## What "Ente" means inside this repo

- `engine/` --- the **vendored Rust engine** (Ente-derived ML/photos-side
  crates). It is the native heart of Gallery Engine and Unified:
  `libtoad_ml_jni.so` cross-built for Android (cargo-ndk), loaded together
  with `libonnxruntime.so`. Ente is **not** a Kotlin module (the Phase 1
  deviation): the engine-flavor gallery build *is* how the vendored engine
  ships inside this project's products.
- **Models:** Ente Gallery AI model weights are **downloaded on demand**
  (ADR-0020 allowlist-gated, opt-in) and are **never bundled** into the
  project's APKs. Only non-Ente ONNX models (the editor's AI passes) bundle,
  per product requirements.
- `gallery/` rides this lane because the engine integrates into the
  engine-flavor gallery build --- the CI validates that integration end to
  end.

## CI on this branch

[`ente-ci.yml`](.github/workflows/ente-ci.yml) triggers on pushes to `ente`,
path-filtered over `engine/**`, `gallery/**`, the gradle files, and the
workflow tree. Jobs:

- **engine** --- the vendored-engine cargo gates (check + fmt + clippy +
  tests) on the host toolchain.
- **engine-apk** --- the engine-flavor gallery debug assemble
  (`:gallery:app:assembleEngineDebug -PwithEngine`, arm64-v8a) proving the
  Android integration still builds and packages both required native
  libraries.

The branch also carries the repo-wide dispatch surfaces and the other lanes'
CI files --- each is branch-scoped and stays dormant outside its own lane.

## Working on this branch

JDK 17 + Android SDK (`platforms;android-36`) + Rust toolchain with
`cargo-ndk`. An upstream sync round looks like:

1. Import/merge the selected upstream change into `engine/` (selective ---
   take what is useful, leave what is not).
2. Push; let `ente-ci.yml` validate the cargo gates and the engine-flavor
   APK build.
3. Provenance/licensing check (`engine/NOTICE.md`, the licensing ledger).
4. PR the validated result into `unified`.

See
[`.github/BRANCH_STRATEGY.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/.github/BRANCH_STRATEGY.md)
for the full topology and operator runbook.
