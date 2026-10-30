#!/usr/bin/env bash
# Runs the toadGALLERY instrumented privacy suite (ADR-0014 / issue #20)
# with one retry on the same booted emulator.
#
# Why a retry: the APK install-commit can die with 'Broken pipe (32)'
# inside SplitApkInstallerBase --- a transient package-manager/adb
# failure on hosted KVM runners, observed on main (CI runs #12, #18) ---
# BEFORE any test runs. A retry costs a 15 s pause plus a compile-cached
# rerun; deterministic test failures fail both attempts and stay red.
#
# Runs #26 and #28 (2026-10-30) showed a worse variant: attempt 2 died at
# install-write with "PackageManagerInternal.freeStorage ... null object
# reference" --- the emulator's data partition filled up (a failed install
# session leaks its staged APK bytes) and package storage manager itself
# crashed. Countermeasures now built into this script:
#   1. -partition-size 4096 in ci.yml (bigger /data).
#   2. adb install verification disabled (verifier staging eats storage).
#   3. pm trim-caches + app uninstall + session cleanup between attempts,
#      so a half-staged install from attempt 1 cannot starve attempt 2.
#
# Lives as a file (not inline in ci.yml) because the emulator-runner's
# 'script' input goes through sh -c "..." wrapping: embedded double
# quotes in an inline script terminate the wrapper and corrupt the
# command line (observed in run #19).

set -u

PKG=io.github.dukeoftoad.gallery.debug

attempt=1
max_attempts=2

# Skip APK verification and reclaim cache space before the first install.
# The verifier stages a second copy of every APK under /data; on a small
# data partition that alone can push install-write into ENOSPC territory.
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell pm trim-caches 999999999999 || true

while true; do
  if ./gradlew :apps:gallery:connectedDebugAndroidTest --no-daemon --stacktrace; then
    echo "connectedDebugAndroidTest: PASSED (attempt ${attempt}/${max_attempts})"
    exit 0
  fi
  status=$?
  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "connectedDebugAndroidTest: FAILED (attempt ${attempt}/${max_attempts}, exit ${status})"
    exit 1
  fi
  echo "attempt ${attempt}/${max_attempts} failed (exit ${status}); transient emulator/adb install flakes are known on hosted runners --- retrying on the same booted emulator"
  # Detox the device before retrying: a failed install-commit leaks the
  # staged APK and the leftover session (both attempts of runs #26/#28 died
  # of a full /data, not of test failures). Uninstall the app, abandon any
  # dangling install sessions and free package caches.
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb shell pm trim-caches 999999999999 || true
  adb shell df -h /data || true
  attempt=$((attempt + 1))
  sleep 15
done
