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


---

# Whole-repository audit — Phases 0–4

> **Audit status:** read-only source review. These are findings and proposed work items, not implemented fixes. No application source files were changed. Items need confirmation with targeted tests and, where noted, device validation.

## High-priority findings

### A. Vault backup can restore ciphertext without its Keystore key

**Areas:** `AndroidManifest.xml`, `VaultRepository.kt`

- The manifest currently enables Android backup (`android:allowBackup="true"`).
- Vault data is stored under `context.filesDir/vault`, while encryption depends on an Android Keystore key.
- A backup/restore or device-transfer path may restore encrypted vault files without a transferable copy of the original Keystore key. The restored vault could therefore be inaccessible.

**Proposed work**
- Explicitly exclude vault contents from cloud backup and device-to-device transfer rules.
- Evaluate moving vault storage to `noBackupFilesDir` as an additional safeguard.
- Define expected behavior when a restored installation has vault ciphertext but no corresponding key; avoid destructive silent recovery.
- Review whether catalogue metadata or biometric binding leaks sensitive vault state through backup.

**Verification:** backup/restore across devices; device transfer; reinstall; key missing/invalidated; verify vault data is neither exposed nor silently lost.

### B. Public-media eligibility boundary can be asserted by callers

**Area:** `MediaEligibility.kt`

- `EligiblePublicMedia` has an internal constructor, but `PUBLIC_INDEXABLE` is public and `asEligible(identity)` accepts a supplied identity.
- Depending on module boundaries and call sites, callers may be able to assert that an item is public/indexable without a trusted provenance check.

**Proposed work**
- Keep the creation of eligible-public-media values behind a trusted MediaStore/catalogue boundary.
- Derive eligibility from verified identity/provenance rather than caller-supplied assertions.
- Add negative tests proving vault/private identities cannot enter public indexing, search, or thumbnail pipelines.

### C. Volume generation is used like an item revision

**Areas:** `MediaRevision.kt`, `CatalogueReconciler.kt`, `MediaChangeFeed.kt`

- A per-volume generation/checkpoint is compared in a way that can behave like an individual media item's revision.
- A single change on a volume may consequently make unrelated items appear changed and trigger unnecessary reprocessing.

**Proposed work**
- Treat volume generation as a scan checkpoint only.
- Use item-specific revision inputs for per-item invalidation.
- Add a regression test showing that changing one item does not invalidate unrelated items on the same volume.

### D. Catalogue live-row and tombstone writes are not atomic

**Area:** `RoomMediaCatalogue.kt`

- Deleting a live row and writing its tombstone are separate operations.
- Upserting a live row and clearing its tombstone are also separate operations.
- A process interruption between writes can leave inconsistent catalogue state.

**Proposed work**
- Wrap each logical live-row/tombstone transition in a Room transaction.
- Add interruption/failure tests for both delete and restore/upsert transitions.
- Verify idempotency when change-feed events are replayed.

### E. Media permission model must handle Android 14+ selected-photo access

**Areas:** `MediaPermissions.kt`, `AndroidManifest.xml`

- The inspected permission model requests image/video media access but does not explicitly model Android 14's selected-photos-and-videos access mode.
- A coarse “some permission granted” state is insufficient to distinguish full library access from user-selected access.

**Project platform requirement:** **minimum Android 14 (API 34)**. Android 13 and earlier are out of scope for the supported app matrix. Permission logic and tests should target Android 14 and later only.

**Proposed work**
- Model full image access, full video access, selected-media access, and denied/revoked states distinctly.
- Request the applicable image and video permissions together on first launch, with a concise explanation of why full gallery access is needed.
- Do not repeatedly show an app-owned permission explainer after the user has granted the required access.
- Detect partial/selected access and explain how to grant full access in system Settings when the feature requires it.
- Recheck permission state when returning to the app, because the user can change access in Settings or the system photo picker.
- Keep Android's system permission and consent requirements intact; do not attempt to silently grant permissions or bypass OS protections.

**Important platform limitation:** ordinary Android apps cannot force-grant runtime permissions during APK installation. The user must approve the OS permission request, normally on first launch. Android may also require separate system confirmation for certain media modifications/deletions; the app cannot guarantee that every future rename/delete operation is prompt-free.

## Medium-priority findings

### F. Date resolver drops valid pre-1970 timestamps

**Area:** `MediaDateResolver.kt`

Negative Unix timestamps represent valid dates before 1970. The current resolver discards negative values.

**Proposed work:** preserve valid negative timestamps and distinguish invalid/sentinel values from legitimate pre-epoch dates. Add tests for pre-1970, epoch, and post-epoch media dates.

### G. EXIF mirrored orientations lose reflection information

**Area:** `ExifMath.kt`

Reducing EXIF orientation to rotation degrees does not preserve mirrored/flip transformations.

**Proposed work:** represent the complete orientation transform (rotation plus horizontal/vertical reflection) and test all EXIF orientation values.

### H. Media reads create avoidable full-size byte copies

**Area:** `MediaReadHandle.kt`

The `ByteArrayOutputStream` followed by `toByteArray()` can temporarily hold an additional full-size copy of the media content.

**Proposed work:** assess bounded/streaming reads or another ownership-safe approach; enforce size limits and test large files under memory pressure.

### I. Capture-date metadata contract is incomplete

**Area:** `ExifReader.kt`

The inspected date path reads `DateTimeOriginal` but does not fully preserve offset, subsecond, and digitized-date information.

**Proposed work:** define precedence and timezone semantics for original/digitized dates and offsets; add fixtures for offset and subsecond tags, missing tags, and malformed metadata.

### J. Decode planner arithmetic needs overflow protection

**Areas:** `CanonicalImageDecoder.kt`, `CanonicalDecodePlan.kt`

Dimension/byte calculations should be checked for `Long` overflow when processing malformed or adversarial dimensions.

**Proposed work:** use checked arithmetic and reject impossible decode plans safely; add boundary/overflow tests.

### K. Room schema export is disabled

**Area:** `MediaCatalogueDatabase.kt`

Room schema export is currently disabled, reducing the ability to review and verify schema changes and migrations.

**Proposed work:** enable schema export, commit versioned schema snapshots, and add migration verification before schema-changing releases.

### L. Biometric weak-authenticator / CryptoObject incompatibility

**Area:** `VaultBiometrics.kt`

This is also captured in the crash findings above. CryptoObject operations should use a crypto-compatible strong biometric policy, with a tested PIN fallback when unavailable.

## Additional Android 14+ permission and file-operation report

### M. Avoid repeated app-owned permission prompts; respect OS consent

**Areas:** `MediaPermissions.kt`, `MediaFileOps.kt`, `ContentResolverMediaAccess.kt`

- File operations already handle `RecoverableSecurityException` and can return a `NeedsConsent` result for rename/delete.
- Move can copy the destination and then fail to delete the source, resulting in a partial operation that requires consent.
- Existing comments indicate an intention to request one consent for a batch, not one consent per item. The UI call sites still need review to confirm they actually batch requests and retry only the operations covered by the user's approval.
- Android's system authorization to modify/delete media is not a blanket permanent permission. It may be required again for later separate operations.

**Desired UX**
1. On first app launch, explain full gallery access and request the applicable Android 14+ image/video permissions together.
2. Remember the permission state by querying Android, not by assuming a previous grant is permanent.
3. If full access is granted, do not show a redundant app-owned permission popup on every rename/delete.
4. Batch supported rename/delete requests and use the Android system consent flow where required.
5. After approval, retry only the operations authorized by that consent; report partial failures clearly.
6. If access is revoked or limited in Settings, explain the specific missing access and offer the system Settings route.
7. Never bypass Android permission or user-consent protections.

**Verification matrix (Android 14+)**
- API 34: full image + video access; selected photos/videos; denied; revoked in Settings.
- Newer Android versions: verify permission result behavior and selected-media reselection paths on each supported API level.
- Rename/delete one item and multiple items; batch consent; cancellation; partial success; repeat action after approval.
- Move where destination copy succeeds but source deletion requires consent.
- Confirm no duplicate app-owned permission dialogs when the OS already reports the required access.

## Phase 4 roadmap gaps / integration work

The source review found useful contracts and infrastructure, but the following roadmap capabilities were not complete or not fully integrated into end-to-end app behavior at audit time:

- Metadata Details screen.
- EXIF write-back.
- IPTC/XMP read/write wrappers.
- On-device map view.
- Collections and smart-filter chips.
- Full media-format support audit.
- `ACCESS_MEDIA_LOCATION` permission and runtime flow.
- Scan driver connecting the Room catalogue and media change feed.
- Adoption of the capture-date resolver in timeline sorting/grouping.
- Room schema export and migration verification.

Do not describe the incremental catalogue as fully complete until the scan driver/startup integration and end-to-end behavior are verified.

## CI and verification limitations

- The latest main CI/release and this documentation PR's CI were observed passing during the audit.
- CI covers debug APK, debug lint, and JVM unit tests.
- Physical-device biometric behavior is not automated.
- Keystore behavior and hardware decoder compatibility require device verification.
- The privacy/egress instrumentation suite is a manual release check.
- Production Room glue is not fully exercised by JVM-only tests.
- A green CI run is not proof of runtime correctness on affected devices.

## Recommended implementation order (approval required)

1. **Permission UX and file operations (Android 14+):** audit UI call sites for duplicate prompts; implement full/selected/denied permission state; batch OS consent where supported; test API 34 and newer.
2. **Vault data safety:** exclude vault from backup/device transfer and define missing-key recovery.
3. **Eligibility boundary:** prevent untrusted callers from asserting public-media eligibility.
4. **Room consistency:** transact live-row/tombstone state changes.
5. **Catalogue revision semantics:** separate volume scan checkpoints from item revisions.
6. **Media correctness and resilience:** dates, EXIF orientation/date metadata, bounded reads, checked decode arithmetic.
7. **Roadmap integration:** scan driver, timeline date adoption, metadata and format features, map/collections as separately scoped work.
8. **Crash fixes:** strong biometric crypto compatibility, paging race hardening, and decoder error handling as described above.

## Approval and scope control

This remains a **documentation-only draft PR**. The audit report and proposed Android 14+ permission behavior are documentation, not authorization to change application code. No source implementation, merge, or release is included. Obtain explicit maintainer approval before implementing any item. Validate each finding with focused tests; record device/API-level results and exact files changed in the implementation PR.
