# Phase 0 Build Notes — Upstream Baseline Verification

**Status:** PENDING (environment-blocked) · **Last updated:** 2026-10-29

## Purpose

The roadmap requires each upstream project to **build independently in a clean
environment** before any integration work. This file records toolchain requirements,
build commands, results, and blockers for both baselines. It is updated whenever a
baseline build is attempted.

## Environment status of the initial bootstrap

The Phase 0 bootstrap that authored this repository ran in a container **without an
Android SDK, without Flutter, and without a JDK-level Android toolchain**. That is a
deliberate limitation, not a shortcut: build verification must happen in a proper
development environment. Everything that could be verified without building — commit
hashes, licenses, manifests, toolchain declarations — has been recorded in
[`upstream-baselines.md`](upstream-baselines.md). The build steps below are the remaining work.

## Fossify Gallery baseline build

| Field | Value |
|---|---|
| Baseline commit | `e9812299b9d444b57072313cd2c3efd59c790f98` (v1.14.1) |
| Requirements (declared) | JDK 17, Android SDK w/ compileSdk 36 platform, Gradle wrapper (included), KSP 2.3.8, AGP 9.2.1 |
| Build commands | `./gradlew assembleFossDebug` (FOSS flavor, debug) · `./gradlew assembleGplayDebug` (gplay) |
| Expected artifacts | `app/build/outputs/apk/foss/debug/` APKs |
| Result | **PENDING** |
| Blockers / notes | Signing is optional for debug; release needs `keystore.properties` or `SIGNING_*` env vars (see `keystore.properties_sample`). |

## Aves baseline build

| Field | Value |
|---|---|
| Baseline commit | `4f940ae48bd9364a0a532b84f283ec60755d9904` (1.15.8+182, `develop`) |
| Requirements (declared) | Flutter SDK 3.47.8 (bundled via `flutter_wrapper`), Dart ≥3.13.7, JDK 21 Temurin (auto-provisioned by Gradle toolchains), Android SDK w/ compileSdk 37 |
| Build commands | `./flutterw build apk --flavor libre` (libre/FOSS flavor — **always build this flavor, not the Google flavor**) |
| Expected artifacts | `build/app/outputs/flutter-apk/app-libre-release.apk` (or debug variant) |
| Result | **PENDING** |
| Blockers / notes | `minSdk`/`targetSdk` are delegated to Flutter toolchain defaults — **record the resolved values here after the first successful build**. A Google API key placeholder is expected to remain `NONE` in the libre flavor. |

## ToadGALLERY baseline build (once Phase 2 shell exists)

To be added when the first product shell exists. Until then this project tracks only
documentation and upstream baselines; there is no toadGALLERY code to build.

## Definition of done for this document

- [ ] Fossify Gallery: clean-machine build of `fossDebug` succeeds; toolchain recorded
- [ ] Aves: clean-machine build of `libre` flavor succeeds; resolved minSdk/targetSdk recorded
- [ ] Both: app IDs, signing/debug setup, and first-launch smoke test notes recorded
- [ ] Any failure documented with cause and workaround (or escalated to the risk register)
