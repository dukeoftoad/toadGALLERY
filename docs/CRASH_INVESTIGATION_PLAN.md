# ToadGALLERY crash investigation — proposed remediation plan

> **RESOLVED --- r11 round (2026-11-01).** The maintainer approved this plan for execution; the implementation is PR #82 (`tg-r11-strong-biometrics`, versionCode 15) with the round's docs in its second commit. Each finding below now carries a status line recording what happened to it; deferred items keep their rationale; the original draft text is preserved for the record.

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

**Status: FIXED in PR #82 (r11).** Root cause confirmed against the androidx.biometric 1.1.0 sources and sharpened beyond this draft: the rejection is UNCONDITIONAL on every device --- `authenticate(PromptInfo, CryptoObject)` throws whenever the prompt's allowed-authenticator set includes `BIOMETRIC_WEAK` (the check tests the requested flags, not the device's sensor class; the library's own default for crypto sessions is `BIOMETRIC_STRONG`, with the source comment "Crypto auth requires a Class 3 (Strong) biometric."). The vault prompt and the `canPrompt` readiness probe now use `BIOMETRIC_STRONG`; Class-2-only devices land in the existing `Unsupported` readiness (PIN-only) --- which resolves the weak-only-enrolled and no-biometric verification rows by construction. Lockout arrives through the prompt's error path (mapped to the existing failure narration); invalidated keys keep the ADR-0019 binding-removal fallback. JVM pins: the builder state (negative text + `BIOMETRIC_STRONG`) and a regression test asserting the exact `isWeakBiometricAllowed` bit-math can never be satisfied by the vault's request.

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

**Status: already fixed (r10, PR #78); the log entry predates the fix.** The `Negative text must be set and non-empty.` report in this round's device log is a STALE crash-buffer entry from build 13 --- its frames carry build 13's `r8-map-id`, different from build 14's (all frames of one APK share one mapping hash, which is how stale entries are told apart from current-build findings). The "Use PIN" negative button has been set since r10; locale-variant verification rides the build-15 checklist (section 13).

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

**Status: stale-buffer entry; existing mitigations stand.** The `IndexOutOfBoundsException: Index 60, Size 60` report is from a build older than 14 (its `r8-map-id` matches neither build 13's nor build 14's) --- it is the r8 grid stale-index crash already fixed in PR #76 and verified gone in the r9 device round. The bounds-checked snapshot fallback this draft notes remains in place; deeper paging hardening rides future paging work rather than the r11 round.

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

**Status: no crash; recoverable-error UX already in place.** The HEVC/10-bit reports are ExoPlayer decoder failures (device noise on the affected Qualcomm hardware), not FATAL crashes, and the viewer already surfaces a recoverable playback error with decoder fallback enabled. Player lifecycle cleanup stays a standing device-checklist verification item.

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

## Implementation checklist — RESOLVED (r11, PR #82)

- [x] Approve and implement the strong-biometric / crypto compatibility change. (PR #82)
- [x] Add biometric availability, fallback, lockout, and Keystore regression tests. (JVM builder-state pins incl. the isWeakBiometricAllowed bit-math; the hardware lockout/Keystore rows stay on the device checklist --- ADR-0016)
- [x] Confirm or improve negative-button text handling and add resource coverage tests. (fixed in r10 PR #78; retained and re-pinned in r11)
- [x] Add paging refresh/concurrency regression tests and fix any reproduced unsafe access. (N/A this round: the report was a stale-buffer entry from a pre-r10 build; the bounds-checked recovery was verified in place --- deeper paging hardening rides future paging work)
- [x] Improve decoder failure UX and verify player lifecycle cleanup. (no change needed: recoverable-error UX verified present; lifecycle cleanup stays a checklist verification item)
- [x] Implement only additional defects confirmed by reproduction or source-level evidence. (A, C, D, F, I-EXIF, J, K implemented; B, G, H, M deferred with rationale)
- [x] Run unit tests, lint, and debug/release builds. (491 JVM tests green, 34 new; lintDebug green; assembleDebug + assembleRelease R8+KSP green)
- [ ] Verify on Android 13 hardware, including the affected biometric and Qualcomm playback paths.
- [ ] Record test results and exact files changed in the eventual implementation PR.

## Acceptance criteria

**Resolution:** criteria 1, 2, 4 are implemented and JVM-pinned in PR #82; criterion 3 (paging) was resolved as a stale-buffer entry with the existing recovery verified in place; the device-verification criterion is the remaining gate (build-15 checklist section 13).

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

**Status: IMPLEMENTED in PR #82.** New `res/xml/backup_rules.xml` (pre-31 `fullBackupContent`) and `res/xml/data_extraction_rules.xml` (31+, `cloud-backup` AND `device-transfer`) exclude `filesDir/vault` (pin.bin, index.bin, blobs/, biometric.binding) while `allowBackup="true"` keeps preferences and diagnostics backable. The `noBackupFilesDir` move was evaluated and REJECTED: it would orphan existing installs' vaults with no safe migration of sealed state. The debt pre-tracked in docs/integration-decisions.md ("Should allowBackup become false + explicit dataExtractionRules? Deferred to Phase 3/4...") is now paid; a JVM test pins the exclusion.

### B. Public-media eligibility boundary can be asserted by callers

**Area:** `MediaEligibility.kt`

- `EligiblePublicMedia` has an internal constructor, but `PUBLIC_INDEXABLE` is public and `asEligible(identity)` accepts a supplied identity.
- Depending on module boundaries and call sites, callers may be able to assert that an item is public/indexable without a trusted provenance check.

**Proposed work**
- Keep the creation of eligible-public-media values behind a trusted MediaStore/catalogue boundary.
- Derive eligibility from verified identity/provenance rather than caller-supplied assertions.
- Add negative tests proving vault/private identities cannot enter public indexing, search, or thumbnail pipelines.

**Status: DEFERRED (verified not exploitable today).** `asEligible` performs a purely static check and NO production code mints `EligiblePublicMedia` yet (only the access-layer signatures and tests); negative tests already prove vault/trashed/revoked/unsupported/corrupt identities resolve to null. Trusted-provenance hardening is scheduled with the Phase 4 scan-driver wiring, before any AI call site exists.

### C. Volume generation is used like an item revision

**Areas:** `MediaRevision.kt`, `CatalogueReconciler.kt`, `MediaChangeFeed.kt`

- A per-volume generation/checkpoint is compared in a way that can behave like an individual media item's revision.
- A single change on a volume may consequently make unrelated items appear changed and trigger unnecessary reprocessing.

**Proposed work**
- Treat volume generation as a scan checkpoint only.
- Use item-specific revision inputs for per-item invalidation.
- Add a regression test showing that changing one item does not invalidate unrelated items on the same volume.

**Status: IMPLEMENTED in PR #82 (semantics corrected before wiring).** The per-item revision token is now specified as the row's own `MediaStore.MediaColumns.GENERATION_MODIFIED`; the per-volume `getGeneration` is scan-checkpoint-only (which `MediaChangeFeed` already was). The reconciler test that had pinned the re-index-storm behavior (generation bump with identical size+date = changed) is replaced by the corrected pair --- one row's token bump marks THAT row changed; unrelated rows stay unchanged --- and the contract KDocs were corrected across the revision/change-feed/catalogue types.

### D. Catalogue live-row and tombstone writes are not atomic

**Area:** `RoomMediaCatalogue.kt`

- Deleting a live row and writing its tombstone are separate operations.
- Upserting a live row and clearing its tombstone are also separate operations.
- A process interruption between writes can leave inconsistent catalogue state.

**Proposed work**
- Wrap each logical live-row/tombstone transition in a Room transaction.
- Add interruption/failure tests for both delete and restore/upsert transitions.
- Verify idempotency when change-feed events are replayed.

**Status: IMPLEMENTED in PR #82.** The two transitions are single `@Transaction` composite DAO methods (`removeItemsAndWriteTombstones`, `upsertItemsAndClearTombstones`); the in-memory reference implementation was already atomic and now documents the equivalence, pinned by a deterministic interleaving test. Interruption/rollback behavior of the Room runtime itself remains integration/device territory per ADR-0016.

### E. Media permission model must handle Android 14+ selected-photo access

**Areas:** `MediaPermissions.kt`, `AndroidManifest.xml`

- The inspected permission model requests image/video media access but does not explicitly model Android 14's selected-photos-and-videos access mode.
- A coarse “some permission granted” state is insufficient to distinguish full library access from user-selected access.

**CORRECTION to this draft:** the supported matrix is NOT Android 14+. The app's `minSdk` is **26** (`libs.versions.toml` `app-build-minimumSDK = "26"`; targetSdk 36), and the shipping permission model already spans the 26-32 `READ_EXTERNAL_STORAGE` leg and the 33+ media-permission leg. What is genuinely missing is the API 34+ selected-photos state (`READ_MEDIA_VISUAL_USER_SELECTED`), and the work below remains planned --- targeted at the true 26-to-current matrix, not an Android-14-only app.

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

**Status: IMPLEMENTED in PR #82.** Negative epochs are valid pre-1970 instants; only UNKNOWN-source candidates are discarded (the contract has no 0-sentinel); the cross-epoch disagreement gap check is overflow-safe; six new resolver tests (pre-1970, epoch, post-epoch, cross-epoch extremes).

### G. EXIF mirrored orientations lose reflection information

**Area:** `ExifMath.kt`

Reducing EXIF orientation to rotation degrees does not preserve mirrored/flip transformations.

**Proposed work:** represent the complete orientation transform (rotation plus horizontal/vertical reflection) and test all EXIF orientation values.

**Status: DEFERRED (deliberate, already documented).** The rotation-only summary is stated honestly in the KDoc and tests; the full rotation+reflection representation should be co-designed with the canonical decode contract that will actually consume it, to avoid changing the API shape twice.

### H. Media reads create avoidable full-size byte copies

**Area:** `MediaReadHandle.kt`

The `ByteArrayOutputStream` followed by `toByteArray()` can temporarily hold an additional full-size copy of the media content.

**Proposed work:** assess bounded/streaming reads or another ownership-safe approach; enforce size limits and test large files under memory pressure.

**Status: DEFERRED (zero production consumers).** The transient ~2x of a bounded (64 MiB-capped) read in an unwired path does not justify a contract change now; the streaming variant belongs with the real consumer's memory budget (the Ente-bridge integration), and the `MediaReadHandle` interface is the reserved seam for it.

### I. Capture-date metadata contract is incomplete

**Area:** `ExifReader.kt`

The inspected date path reads `DateTimeOriginal` but does not fully preserve offset, subsecond, and digitized-date information.

**Proposed work:** define precedence and timezone semantics for original/digitized dates and offsets; add fixtures for offset and subsecond tags, missing tags, and malformed metadata.

**Status: EXIF HALF IMPLEMENTED in PR #82; XMP deferred.** The extraction half of the claim was accurate --- but the audit missed that the RESOLVER half of the contract (CaptureDateSource precedence EXIF_ORIGINAL > EXIF_DIGITIZED > XMP > CONTAINER > MEDIASTORE, timezone-offset and subsecond fields, disagreement flag) was already built and JVM-tested, just unwired. The extractor now reads DateTimeDigitized, OffsetTimeOriginal/Digitized and SubSecTimeOriginal (offset-aware epoch math with a documented UTC anchor when the offset tag is absent or malformed; decimal-fraction subsecond convention, >3 digits truncated) and `ParsedExif.captureDateCandidates()` emits both EXIF candidates feeding that resolver. XMP parsing stays UNSUPPORTED (its own capability-matrix change).

### J. Decode planner arithmetic needs overflow protection

**Areas:** `CanonicalImageDecoder.kt`, `CanonicalDecodePlan.kt`

Dimension/byte calculations should be checked for `Long` overflow when processing malformed or adversarial dimensions.

**Proposed work:** use checked arithmetic and reject impossible decode plans safely; add boundary/overflow tests.

**Status: IMPLEMENTED in PR #82.** The memory-budget check is a multiplication-free exact inequality (adversarial dimensions whose area would overflow Long are refused deterministically; a negative budget refuses everything); six boundary tests (Int.MAX-scale dims, Long-overflow area, sampling ceiling, 1x1, budget boundaries).

### K. Room schema export is disabled

**Area:** `MediaCatalogueDatabase.kt`

Room schema export is currently disabled, reducing the ability to review and verify schema changes and migrations.

**Proposed work:** enable schema export, commit versioned schema snapshots, and add migration verification before schema-changing releases.

**Status: IMPLEMENTED in PR #82.** `exportSchema = true` + `room.schemaLocation`; the v1 snapshot (3 entities) is committed under `apps/gallery/schemas/` with a README stating the migration-review gate.

### L. Biometric weak-authenticator / CryptoObject incompatibility

**Area:** `VaultBiometrics.kt`

This is also captured in the crash findings above. CryptoObject operations should use a crypto-compatible strong biometric policy, with a tested PIN fallback when unavailable.

**Status: FIXED --- same resolution as finding 1 (PR #82).**

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

**Status: DEFERRED as planned work (premise corrected above).** The existing `RecoverableSecurityException`/`NeedsConsent` handling and the batch-consent intent were verified in source; the full/selected/denied state modeling and the duplicate-prompt audit of UI call sites are scheduled as their own round across the 26-to-current matrix.

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

## Recommended implementation order (executed r11, PR #82)

1. **Permission UX and file operations (Android 14+):** audit UI call sites for duplicate prompts; implement full/selected/denied permission state; batch OS consent where supported; test API 34 and newer.
2. **Vault data safety:** exclude vault from backup/device transfer and define missing-key recovery.
3. **Eligibility boundary:** prevent untrusted callers from asserting public-media eligibility.
4. **Room consistency:** transact live-row/tombstone state changes.
5. **Catalogue revision semantics:** separate volume scan checkpoints from item revisions.
6. **Media correctness and resilience:** dates, EXIF orientation/date metadata, bounded reads, checked decode arithmetic.
7. **Roadmap integration:** scan driver, timeline date adoption, metadata and format features, map/collections as separately scoped work.
8. **Crash fixes:** strong biometric crypto compatibility, paging race hardening, and decoder error handling as described above.

## Approval and scope control

## Resolution record (2026-11-01, r11 round)

The maintainer approved this plan for execution. The implementation is **PR #82** (`tg-r11-strong-biometrics`, versionCode 15): findings 1/L (biometric authenticator class), A (vault backup exclusion), C (per-row revision token), D (Room transactions), K (schema export), J (decode overflow), F (pre-1970 dates) and I's EXIF half (richer date metadata) implemented; findings 2 and 3 resolved as stale crash-buffer entries from pre-r10 builds via `r8-map-id` attribution; B, G, H and M deferred with rationale; E/M's Android-14-only platform premise corrected (this app's minSdk is 26). Verification: 491 JVM tests (34 new), lintDebug, assembleDebug and assembleRelease (R8+KSP) green. The remaining gate is the build-15 device round (device-validation checklist section 13): the biometric sheet must appear with the Use PIN button, a Class 3 fingerprint must open the vault, a Class 2-only device must honestly show PIN-only, and the no-new-FATAL scan must apply the map-id attribution rule.
