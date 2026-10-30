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
# Lives as a file (not inline in ci.yml) because the emulator-runner's
# 'script' input goes through sh -c "..." wrapping: embedded double
# quotes in an inline script terminate the wrapper and corrupt the
# command line (observed in run #19).

set -u

attempt=1
max_attempts=2

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
  attempt=$((attempt + 1))
  sleep 15
done
