# ToadGALLERY crash investigation — proposed remediation plan

> **Draft review only.** This branch contains an investigation plan, not application fixes. No source files have been changed. Do not treat any item below as approved implementation work until the maintainer explicitly approves it.

## Context

- Repository: `dukeoftoad/toadGALLERY`
- App: `io.github.dukeoftoad.gallery`
- Reported build: Android 13, version `0.4.1-alpha`, build 14
- Audit was read-only. No local Android build or physical-device reproduction was performed as part of this review.

## Findings and proposed actions

### 1. Biometric crypto prompt is configured for weak biometrics

**Severity:** Critical  
**Area:** `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ui/vault/VaultBiometrics.kt`

The inspected implementation combines a `BIOMETRIC_WEAK` authenticator policy with a cryptographic `BiometricPrompt.CryptoObject` backed by a `Cipher`. AndroidX does not support a CryptoObject with Class 2 (weak) biometrics; this can fail when the prompt is built or invoked.

**Proposed remediation**
- Use a crypto-compatible strong biometric policy (`BIOMETRIC_STRONG`) for operations that require the Keystore-backed Cipher.
- Check authenticator availability before presenting the prompt.
- Preserve a clear PIN fallback when strong biometrics are unavailable or authentication is locked out.
- Handle invalidated or unavailable Keystore keys without crashing.

**Verification**
- Strong biometric enrolled and available.
- Weak-only biometric enrolled.
- No biometric enrolled / hardware unavailable.
- Temporary lockout and permanent lockout.
- Keystore key invalidated or missing.
- Confirm PIN fallback remains usable in each supported fallback case.

### 2. Biometric negative-button text crash signature

**Severity:** High  
**Area:** `VaultBiometrics.kt` and `vault_biometric_prompt_negative`

A reported crash states: `IllegalArgumentException: Negative text must be set and non-empty`. The current inspected source calls `setNegativeButtonText(negativeText)`, and the resource currently resolves to `Use PIN`. This suggests the specific invalid/empty value may already have been corrected in the current tree, but the runtime path still needs verification (including configuration/resource variants).

**Proposed remediation**
- Keep the negative action non-empty for every prompt configuration that requires it.
- Validate the resolved string at the prompt boundary and fail over to a safe non-empty resource if appropriate.
- Confirm all resource qualifiers and supported locales contain the expected value.

**Verification**
- Exercise the prompt on-device across supported locales and configuration changes.
- Verify the resolved negative label is never null or blank.

### 3. Paging grid out-of-bounds crash

**Severity:** High  
**Area:** `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ui/media/MediaPagingGrid.kt`

A reported crash states: `IndexOutOfBoundsException: Index 60, Size 60`. The inspected current source already contains an `IndexOutOfBoundsException` recovery path and a bounds-checked snapshot fallback. This may be a prior mitigation; it does not establish that all stale-index or concurrent refresh paths are safe.

**Proposed remediation**
- Retain bounds-checked access and avoid indexing a paging snapshot with a stale position.
- Re-check the item count at the point of access, and render a safe empty/loading state when the requested item is no longer present.
- Prefer stable item keys and avoid retaining positional indices across refreshes where possible.

**Verification**
- Refresh while scrolling near the end of the grid.
- Append/refresh while the user is opening an item.
- Delete or move media while a page is visible.
- Test empty, shrinking, and rapidly changing libraries.

### 4. HEVC / Qualcomm decoder playback failure

**Severity:** Medium  
**Area:** `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ui/viewer/ViewerScreen.kt`

The reported media issue involves HEVC playback, including a 10-bit profile / device decoder capability mismatch. The inspected viewer already enables ExoPlayer decoder fallback. That is useful mitigation, but it cannot guarantee support when no compatible decoder exists.

**Proposed remediation**
- Keep decoder fallback enabled.
- Surface a recoverable, user-readable playback error when no decoder can handle the stream.
- Ensure player resources are released on navigation and lifecycle changes.
- Do not silently classify unsupported media as a gallery-wide crash.

**Verification**
- HEVC 10-bit sample on affected Qualcomm hardware.
- Supported HEVC and H.264 samples.
- Corrupt or truncated video.
- Retry after playback failure and navigate away during playback.

## Additional read-only audit checklist

- [ ] Vault lifecycle, cancellation, and prompt dismissal paths.
- [ ] Keystore key creation, persistence, invalidation, and recovery.
- [ ] Coroutine cancellation and lifecycle cleanup.
- [ ] Paging 3 refresh/append races and stale item references.
- [ ] Thumbnail decoding for corrupt, oversized, or unsupported media.
- [ ] ExoPlayer release and listener cleanup.
- [ ] Unsafe collection indexing and force-unwrapped values.
- [ ] Android 13 media permissions and permission-denied states.
- [ ] Debug/release configuration differences and regression-test coverage.

## Implementation checklist — approval required

- [ ] Approve and implement the strong-biometric / crypto compatibility change.
- [ ] Add biometric availability, fallback, lockout, and Keystore regression tests.
- [ ] Confirm or improve negative-button text handling and add resource coverage tests.
- [ ] Add paging refresh/concurrency regression tests and fix any reproduced unsafe access.
- [ ] Improve decoder failure UX and verify player lifecycle cleanup.
- [ ] Implement only additional defects confirmed by reproduction or source-level evidence.
- [ ] Run unit tests, lint, and debug/release builds.
- [ ] Verify on Android 13 hardware, including the affected biometric and Qualcomm playback paths.
- [ ] Record test results and exact files changed in the eventual implementation PR.

## Acceptance criteria

- No crash from weak-biometric + CryptoObject incompatibility; unsupported biometric configurations use the documented PIN fallback.
- Biometric prompt negative text is always valid and non-empty.
- Paging refresh and concurrent updates cannot produce an out-of-bounds crash.
- Unsupported video codecs produce a recoverable playback error rather than an app crash.
- Relevant regression tests and build checks pass; device verification is recorded.

## Scope and change control

This is a **draft planning PR only**. It adds this investigation document and makes no runtime changes. The findings distinguish reported crash signatures from code paths already mitigated in the inspected source. No fixes, merges, or releases are authorized by this PR. Obtain explicit maintainer approval before implementing any checklist item.
