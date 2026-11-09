#!/usr/bin/env bash
# toadGALLERY --- vendored engine: Android native build (r22; ADR-0020 JNI bridge).
#
# Builds engine/crates/toad-ml-jni (cdylib -> libtoad_ml_jni.so) for Android
# ABIs with cargo-ndk, and stages BOTH native libraries the app must package:
#
#   libtoad_ml_jni.so   --- built here from the vendored engine
#   libonnxruntime.so   --- ONNX Runtime 1.31.0, the exact version the
#                           ort-sys 2.0.0-rc.15 bindings pin (api-31).
#                           The vendored crates use ort's load-dynamic mode,
#                           so the shared library is a RUNTIME supply, not a
#                           cargo build artifact (ort-sys's dist.tsv ships
#                           only a static .a for Android): it is fetched from
#                           Microsoft's official onnxruntime-android AAR on
#                           Maven Central, sha256-pinned in this script.
#
# Both are staged into BOTH app modules' build-dir jniLibs folders (the
# Phase 1 modularization: TWO modules assemble engine-enabled APKs now):
#
#   gallery/app/build/rust-jniLibs/<abi>/    (:gallery:app engine-flavor APKs)
#   unified/app/build/rust-jniLibs/<abi>/    (:unified:app the unified APK)
#
# (arm64-v8a, x86_64 with --all). Each app module's build.gradle.kts adds its
# own build-dir jniLibs srcDir ONLY when Gradle runs with -PwithEngine.
# Nothing lands in the source tree (the folders live under build/, which is
# gitignored).
#
# Invoked by the Gradle task `buildRustEngine` (preBuild dependency) and by
# CI's engine-android job. Idempotent: re-running is safe (cargo is
# incremental; staging is rm-then-copy of exactly what this run built).
#
# Usage: build-android.sh [--all]    (--all additionally builds x86_64)
#
# Environment: ANDROID_NDK_HOME wins if set; else the newest NDK under an
# SDK root (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Android/Sdk, /opt/android-sdk,
# /usr/local/lib/android/sdk) is used; else the pinned NDK is installed via
# sdkmanager (requires java). ANDROID_HOME is passed by the Gradle task from
# the environment or local.properties sdk.dir.

set -euo pipefail

echo "==> toadGALLERY engine: Android native build"

ALL=0
for arg in "$@"; do
  case "$arg" in
    --all) ALL=1 ;;
    *) echo "error: unknown argument '$arg' (usage: $0 [--all])" >&2; exit 2 ;;
  esac
done

# Repo root from the script location (engine/scripts/../..), CWD-independent.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENGINE_DIR="$REPO_ROOT/engine"
# Phase 1 modularization: BOTH app modules package the engine --- stage into
# each module's build-dir jniLibs (see the header note above).
OUT_ROOTS=(
  "$REPO_ROOT/gallery/app/build/rust-jniLibs"
  "$REPO_ROOT/unified/app/build/rust-jniLibs"
)

# --- 1. Toolchain (fail loudly if absent; add the rust targets) ------------

command -v cargo >/dev/null 2>&1 || {
  echo "error: cargo not found --- install Rust via https://rustup.rs first" >&2
  exit 1
}
command -v rustup >/dev/null 2>&1 || {
  echo "error: rustup not found --- cargo is present but rustup manages the Android targets" >&2
  exit 1
}
for tool in curl unzip sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "error: $tool not found --- required by the ONNX Runtime supply step" >&2
    exit 1
  }
done

echo "==> rustup: ensure Android targets"
RUST_TARGETS=(aarch64-linux-android)
[ "$ALL" -eq 1 ] && RUST_TARGETS+=(x86_64-linux-android)
rustup target add "${RUST_TARGETS[@]}"

echo "==> cargo-ndk (pinned; install only if missing)"
if ! command -v cargo-ndk >/dev/null 2>&1; then
  cargo install cargo-ndk --version 3.5.4 --locked
fi

# --- 2. NDK discovery -------------------------------------------------------
# Preference order (r50: the PIN is first-class, not the fallback): (1) an
# existing ANDROID_NDK_HOME; (2) the PINNED NDK under any known SDK root;
# (3) the newest other NDK as a compatibility fallback; (4) sdkmanager
# install of the pinned version. The r49-and-earlier shape preferred the
# NEWEST NDK over the pin, so a runner-image NDK bump silently moved the
# Rust toolchain while the policy test asserted "The NDK must stay
# pinned" --- true only for the no-NDK-at-all path.

PINNED_NDK_VERSION="28.1.13356709"
NDK_HOME="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_HOME" ] || [ ! -d "$NDK_HOME" ]; then
  for sdk_root in \
      "${ANDROID_HOME:-}" \
      "${ANDROID_SDK_ROOT:-}" \
      "$HOME/Android/Sdk" \
      "/opt/android-sdk" \
      "/usr/local/lib/android/sdk"; do
    [ -n "$sdk_root" ] && [ -d "$sdk_root/ndk" ] || continue
    pinned="$sdk_root/ndk/$PINNED_NDK_VERSION"
    if [ -d "$pinned" ]; then
      NDK_HOME="$pinned"
      break
    fi
    newest="$(ls -1d "$sdk_root"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$newest" ]; then
      NDK_HOME="$newest"
      break
    fi
  done
fi

if [ -z "$NDK_HOME" ] || [ ! -d "$NDK_HOME" ]; then
  # No installed NDK anywhere: install the pinned one via sdkmanager.
  # 28.1.13356709 verified against `sdkmanager --list` (installed and used
  # for the r22 local build proof); the discovery above makes this pin
  # matter only on machines with no NDK at all. Prefer bumping this to
  # whatever sdkmanager offers when you touch it.
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/cmdline-tools" ]; then
    for fallback_root in "$HOME/Android/Sdk" "/opt/android-sdk" "/usr/local/lib/android/sdk"; do
      if [ -d "$fallback_root/cmdline-tools" ]; then SDK_ROOT="$fallback_root"; break; fi
    done
  fi
  SDK_MGR=""
  if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/cmdline-tools" ]; then
    SDK_MGR="$(find "$SDK_ROOT/cmdline-tools" -path '*/bin/sdkmanager' | sort | tail -1)"
  elif command -v sdkmanager >/dev/null 2>&1; then
    SDK_MGR="$(command -v sdkmanager)"
    SDK_ROOT="$(cd "$(dirname "$SDK_MGR")/../../.." && pwd)"
  fi
  [ -n "$SDK_MGR" ] || {
    echo "error: no NDK found and no sdkmanager available --- set ANDROID_NDK_HOME or ANDROID_HOME" >&2
    exit 1
  }
  PINNED_NDK="ndk;$PINNED_NDK_VERSION"
  echo "==> no installed NDK; $SDK_MGR --install $PINNED_NDK"
  yes | "$SDK_MGR" --licenses >/dev/null || true
  "$SDK_MGR" --install "$PINNED_NDK"
  NDK_HOME="$(ls -1d "$SDK_ROOT"/ndk/* | sort -V | tail -1)"
fi
echo "==> NDK: $NDK_HOME"

# --- 3. Build (release, per-ABI) --------------------------------------------

echo "==> cargo ndk build: arm64-v8a$([ "$ALL" -eq 1 ] && echo ' + x86_64')"
cd "$ENGINE_DIR"
NDK_ARGS=(-t arm64-v8a)
[ "$ALL" -eq 1 ] && NDK_ARGS+=(-t x86_64)
# NOTE: cargo-ndk's `-p` is the ANDROID PLATFORM (API level = the app's
# minSdk, 26); the crate selection rides in the trailing cargo args.
ANDROID_NDK_HOME="$NDK_HOME" cargo ndk "${NDK_ARGS[@]}" -p 26 build --release --package toad-ml-jni

# --- 4. ONNX Runtime shared library (official Android AAR, pinned) ----------
# Verified against the 1.31.0 AAR (r22 local build proof): exports
# OrtGetApiBase@@VERS_1.31.0 (the api-31 bindings' exact version) and its LOAD
# segments are aligned 0x4000 (Android 16 KB page size). Cached under
# engine/target/ort-cache/ (gitignored); ~55 MB, contains all four ABIs.

ORT_VERSION="1.31.0"
ORT_AAR_SHA256="72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7"
ORT_CACHE_DIR="$ENGINE_DIR/target/ort-cache"
ORT_AAR="$ORT_CACHE_DIR/onnxruntime-android-$ORT_VERSION.aar"
ORT_EXTRACT="$ORT_CACHE_DIR/extracted"

ort_aar_url() { # <maven-root>
  echo "$1/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"
}

fetch_aar() {
  local root got
  for root in https://repo1.maven.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
    echo "    fetching from $root"
    if curl -fSL --retry 3 --max-time 600 -o "$ORT_AAR" "$(ort_aar_url "$root")"; then
      got="$(sha256sum "$ORT_AAR" | cut -d' ' -f1)"
      if [ "$got" = "$ORT_AAR_SHA256" ]; then return 0; fi
      echo "    sha256 mismatch from $root (got $got)" >&2
    fi
  done
  return 1
}

if [ -f "$ORT_AAR" ] && [ "$(sha256sum "$ORT_AAR" | cut -d' ' -f1)" = "$ORT_AAR_SHA256" ]; then
  echo "==> ONNX Runtime $ORT_VERSION: AAR cache hit"
else
  mkdir -p "$ORT_CACHE_DIR"
  echo "==> ONNX Runtime $ORT_VERSION: fetching the official Android AAR (sha256-pinned)"
  fetch_aar || { echo "error: could not fetch/verify onnxruntime-android-$ORT_VERSION.aar" >&2; exit 1; }
fi
rm -rf "$ORT_EXTRACT"
mkdir -p "$ORT_EXTRACT"
unzip -oq "$ORT_AAR" 'jni/*' -d "$ORT_EXTRACT"

# --- 5. Stage both native libraries into the build-dir jniLibs folder -------

stage_cargo() { # <rust-triple> <abi>
  local triple="$1" abi="$2" src="$ENGINE_DIR/target/$triple/release/libtoad_ml_jni.so"
  [ -f "$src" ] || { echo "error: libtoad_ml_jni.so for $abi was not built" >&2; exit 1; }
  rm -rf "$OUT_ROOT/$abi"
  mkdir -p "$OUT_ROOT/$abi"
  cp -f "$src" "$OUT_ROOT/$abi/libtoad_ml_jni.so"
  echo "    $abi/libtoad_ml_jni.so ($(wc -c < "$src" | tr -d ' ') bytes)"
}

stage_ort() { # <abi>
  local abi="$1" src="$ORT_EXTRACT/jni/$abi/libonnxruntime.so"
  [ -f "$src" ] || { echo "error: libonnxruntime.so for $abi missing from the AAR" >&2; exit 1; }
  mkdir -p "$OUT_ROOT/$abi"
  cp -f "$src" "$OUT_ROOT/$abi/libonnxruntime.so"
  echo "    $abi/libonnxruntime.so ($(wc -c < "$src" | tr -d ' ') bytes)"
}

echo "==> staging into (relative to the repo root):"
for out in "${OUT_ROOTS[@]}"; do echo "    ${out#"$REPO_ROOT"/}"; done
for abi in arm64-v8a $([ "$ALL" -eq 1 ] && echo x86_64); do
  triple="aarch64-linux-android"
  [ "$abi" = "x86_64" ] && triple="x86_64-linux-android"
  for OUT_ROOT in "${OUT_ROOTS[@]}"; do
    stage_cargo "$triple" "$abi"
    stage_ort "$abi"
  done
done

echo "==> done"
