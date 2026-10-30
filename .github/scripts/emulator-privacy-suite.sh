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
#   3. pm trim-caches + app uninstall + adb reboot between attempts,
#      so a half-staged install from attempt 1 cannot starve attempt 2.
#
# Run #33 (2026-10-30) failed the same way AFTER 1+2+3 --- and the log
# revealed the real environment: the runner has NO KVM ("hardware
# acceleration is not available"; -accel off), so the emulator runs under
# pure software emulation (TCG). Package-manager operations are slow and
# the adb stream is timing-fragile: install-commit can die mid-commit with
# 'Broken pipe (32)' purely from the slowness. The flake is therefore
# probabilistic (runs #22/#24/#27/#29/#31/#32 landed, #26/#28/#33 died),
# so the strategy is: more attempts + a full package-manager state reset
# between attempts. An adb reboot (a) drops in-flight install sessions,
# (b) restarts system_server, clearing whatever wedged freeStorage state,
# (c) takes ~40 s under TCG --- far cheaper than a new emulator boot.
#
# Lives as a file (not inline in ci.yml) because the emulator-runner's
# 'script' input goes through sh -c "..." wrapping: embedded double
# quotes in an inline script terminate the wrapper and corrupt the
# command line (observed in run #19).

set -u

PKG=io.github.dukeoftoad.gallery.debug

attempt=1
max_attempts=3

# Reset the adb daemon too: the Broken pipe originates in the adb stream,
# so a stale server connection is part of the wedge.
adb kill-server >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true
adb wait-for-device

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
  echo "attempt ${attempt}/${max_attempts} failed (exit ${status}); transient emulator/adb install flakes are known on hosted runners --- rebooting device and retrying"
  # Full package-manager state reset before retrying: a failed install-commit
  # leaks the staged APK and the leftover session (runs #26/#28/#33 died at
  # install-write afterwards, never at a test). adb reboot drops in-flight
  # sessions and restarts system_server, clearing the wedged freeStorage
  # state; ~40 s under TCG vs ~7 min for a fresh emulator boot.
  adb reboot || true
  adb wait-for-device
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if adb shell getprop sys.boot_completed 2>/dev/null | grep -q '^1'; then
      echo "device back up after reboot (wait ${i})"
      break
    fi
    sleep 10
  done
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb shell settings put global verifier_verify_adb_installs 0 || true
  adb shell pm trim-caches 999999999999 || true
  adb shell df -h /data || true
  adb kill-server >/dev/null 2>&1 || true
  adb start-server >/dev/null 2>&1 || true
  adb wait-for-device
  attempt=$((attempt + 1))
done
