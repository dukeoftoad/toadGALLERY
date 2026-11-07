# Phase 11 --- permissions and privacy (the declaration package)

**Status:** the store-policy/permission-declaration record for both apps,
as of the r45 pre-release. Every row is read from the ACTUAL manifests
(`apps/gallery/src/main/AndroidManifest.xml`,
`apps/edit/src/main/AndroidManifest.xml`), the privacy-gate record
(`docs/phase-2/privacy-gate.md`, as amended by ADR-0020), and the
`PrivacyPolicyTest` pin. This is engineering documentation for store
listings and data-safety forms --- not legal advice.

---

## 1. The gallery app's permissions (what installs, and why)

The **source manifest declares exactly five permissions** --- a set
pinned by `PrivacyPolicyTest` ("the pinned five") so nothing new can
ride in silently:

| Permission | Class | API range | What it is for |
|---|---|---|---|
| `READ_MEDIA_IMAGES` | dangerous | 33+ | browsing the photo library (the granular Android 13+ media grant) |
| `READ_MEDIA_VIDEO` | dangerous | 33+ | browsing the video library (ditto) |
| `READ_EXTERNAL_STORAGE` | dangerous | ≤ 32 (`maxSdkVersion="32"`) | the legacy pre-Android-13 fallback for the same browsing purpose |
| `ACCESS_MEDIA_LOCATION` | dangerous | 29+ | EXIF location consent (the r14 audit decision): the grant decides only whether GPS coordinates from photo metadata are **displayed**; the request is gated in code (`MediaLocationAccess.required()`, false below 29) so nothing is ever asked where the permission does not exist; the coordinates never leave the device |
| `INTERNET` | normal | all | the ONE network grant, landed r22 under **ADR-0020**: fetching the pinned on-device-AI model assets from the single allowlisted host `models.ente.com`, every byte verified (size + SHA-256) before install. No account, no telemetry, no crash reporting, no tile/config fetch of any other kind; media, embeddings and indexes never cross the network |

The **merged manifest** (what the device actually installs) additionally
carries permissions injected by libraries --- each documented in
`docs/phase-2/privacy-gate.md`'s r24 note and pinned by the test suite:

| Permission | Merged from | Why it is accepted |
|---|---|---|
| `USE_BIOMETRIC` | androidx.biometric | the vault's opt-in `BIOMETRIC_STRONG` unlock binding (ADR-0019) --- a normal permission, no prompt |
| `WAKE_LOCK` | androidx.work 2.10.1 | WorkManager's execution-guarantee plumbing for the opt-in background face-index queue; cannot open a socket |
| `RECEIVE_BOOT_COMPLETED` | androidx.work 2.10.1 | rescheduling persisted queue work after a reboot; cannot open a socket |

Two further androidx.work injections are **declined with
`tools:node="remove"` markers** (r24): `ACCESS_NETWORK_STATE` (the queue
never uses network-constrained work) and `FOREGROUND_SERVICE` (r24 runs
plain background workers only). The removal set is pinned exactly by its
own dedicated `PrivacyPolicyTest` test --- shrinking it widens the
network-capability surface, growing it breaks the queue; either is an
ADR-level decision.

### The INTERNET gate story (how the one grant stays the one grant)

The core posture was "offline by construction": no INTERNET permission
at all, so the OS blocks every socket (ADR-0014). **ADR-0020 (r22)
amended it honestly and on record for model fetches only**: the gallery
may open network connections for exactly one purpose --- downloading the
pinned AI model assets from `models.ente.com` when the user first
enables an AI feature. Three enforcement layers keep that grant honest:

1. **The build-time merged-manifest gate** (`InternetPermissionGateTask`)
   reads the merged manifest and fails the build if INTERNET appears
   without allowlist authorization --- the allowlist file
   (`apps/gallery/internet-permission-allowlist.txt`) carries exactly
   one entry, the ADR-0020 declaration. No dependency can inject INTERNET
   silently; the gate catches merger-injected declarations as well as
   source ones.
2. **The JVM privacy suite** (`PrivacyPolicyTest`): the five-permission
   pin, the allowlist protocol, the host-literal source scan
   (`models.ente.com` is the only host literal in the `ml/` package),
   and the URL surface for all eleven pinned manifest entries (every URL
   is `https://models.ente.com/<fileName>` --- no port, query, or
   fragment; injection-guarded).
3. **The instrumented egress suite** (`PrivacyEgressTest`), run by hand
   on a physical device before every tagged release: INTERNET granted
   for the allowlisted host, non-allowlisted targets refused, plus a
   core-flow walk (ADR-0016 keeps this off CI --- the hosted runners
   have no KVM).

## 2. The edit app's permissions (and the hard strip)

toadGALLERY Edit declares **no dangerous permission on Android 10+**.
Its own-contribution `MediaStore` inserts (the save-copy exports) need
no permission at all from API 29. The one declared permission is the
legacy write grant for the oldest supported devices:

| Permission | Class | API range | What it is for |
|---|---|---|---|
| `WRITE_EXTERNAL_STORAGE` | dangerous | ≤ 28 (`maxSdkVersion="28"`) | pre-Q MediaStore inserts for the save-copy export, requested at export time only on Android 9-and-older devices. A storage permission, never a network one |

**Zero network, unconditionally.** The edit manifest deliberately
carries no INTERNET permission and no allowlist mechanism at all: its
own merged-manifest gate (`EditInternetPermissionGateTask`) fails the
build on ANY INTERNET in the merged manifest. The r41 round proved the
gate does its job: the onnxruntime-android AAR's own packaging declares
INTERNET (a sample-convenience declaration), the FIRST merge-gate run
caught the merged manifest gaining it, and the fix was a **hard strip**
(`tools:node="remove"`), never an allowlist --- the merged manifest the
device installs carries no INTERNET at all, so the app cannot even open
a socket. The gallery's ADR-0020 `models.ente.com` grant belongs to the
gallery app only; the edit app's four ONNX models ship **in the APK**
(sha256-pinned), so the editor needs no model download ever.

## 3. Data-safety answers (for any store listing)

- **Data collected: none.** No analytics, no ads, no trackers, no crash
  reporting, no telemetry SDKs (the feature matrix §9 posture; enforced
  by the permission pins above --- there is no surface to phone home
  on, and the egress suite proves it on-device).
- **Data shared: none.** Nothing leaves either device app. The gallery's
  sole network surface is the pinned `models.ente.com` model download at
  explicit user enablement (ADR-0020); the edit app has no network
  surface at all.
- **Everything runs on-device.** Browsing, metadata, search, the AI
  features (ADR-0010: on-device only, no cloud ML), the editor, and the
  video editor.
- **Edits are non-destructive.** The editor works on a versioned recipe
  (sidecar JSON, ADR-0011); every export --- photo or video --- is a new
  `MediaStore` INSERT. No replace-original path exists in either app.
- **The vault is device-bound.** At-rest AES-256-GCM under an
  AndroidKeyStore key (StrongBox when available, TEE fallback;
  ADR-0017), unlocked by a PBKDF2-verified PIN and, if enrolled,
  hardware-bound per-use biometric authentication (`BIOMETRIC_STRONG`,
  ADR-0019). The vault directory and the face-index directory (face
  embeddings, cluster assignments) are **excluded from cloud backup and
  device-to-device transfer** by explicit rules
  (`apps/gallery/src/main/res/xml/backup_rules.xml` +
  `data_extraction_rules.xml`). Honest policy: no cloud, no recovery ---
  a forgotten PIN (or a factory reset) makes the vault permanently
  unopenable, by design.
- **AI features are opt-in.** People clustering, semantic (content)
  search, Things collections, and the editor's face retouch each sit
  behind their own consent surface, default OFF (ADR-0010; the editor's
  retouch adds a settings kill-switch). Enabling is the user's act, and
  the gallery-side models download only then, from the one pinned host.
  One honest distribution note: the gallery's ente-derived engine
  library ships only in the opt-in engine-enabled builds
  (`-PwithEngine`); the release APK carries the AI surfaces in their
  honest engine-unavailable state, and the editor's four models (the
  ones that ship in-APK) run entirely on-device with no download at
  all.

## 4. App-store policy alignment notes

- **targetSdk 36 / compileSdk 36 / minSdk 26 (Android 8.0+)** --- the
  build's own pins (`gradle/libs.versions.toml`;
  `docs/phase-10/device-matrix.md` records the supported window).
- **No location permission of any kind.** The gallery requests no
  `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` and does no background
  location; the only location-adjacent permission is
  `ACCESS_MEDIA_LOCATION` (photo-EXIF display consent, above).
- **No exact alarms.** Neither manifest declares
  `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`; nothing schedules
  time-critical work.
- **No foreground services.** The `FOREGROUND_SERVICE` injection is
  explicitly stripped (r24); if a future user-visible indexing
  notification round ever needs it, that round adds it back
  deliberately, with its own decision record.
- **Scanning/indexing posture.** The library scan runs at app start (the
  r12 scan driver) over `MediaStore` --- no background daemon. The AI
  face-index queue (r24) is WorkManager-backed and runs **only after the
  user opts in**: plain background workers (no `setForeground`, no
  expedited), bounded to one photo in memory at a time, batches of at
  most 50 photos per run, resumable across process death, cancellable by
  the unique work name. There is no always-on background indexing (the
  roadmap defers that past v1.0 by decision).
- **Not a Play Store pipeline** (ADR-0018): the signed artifacts are
  sideload pre-releases; F-Droid metadata is prepared in this window
  (`fdroid/metadata/`, see `docs/phase-11/fdroid-metadata.md`) and
  submission is a maintainer action after review.

## r54 release cut (2026-11-07) — the F-14 gate's first real-tag exercise: three allowlist corrections

The r51 supply-chain round (#140) authored the release-stage
uses-permission gate with allowlists derived from the source
manifests; the `v0.4.5-alpha-r54` tag push was the gate's FIRST run
against real merged APKs (release.yml runs only on tag/dispatch —
no PR had ever exercised it), and it failed exactly as designed:
the merged APKs carry documented library merges the allowlists
omitted. The observed sets (aapt2, printed per APK in the run log):

- **all four gallery flavors**: androidx.biometric's merges are TWO —
  `android.permission.USE_BIOMETRIC` (the allowlist had misspelled it
  `USE_BIOMETIC`, missing the R, so the correctly-merged permission
  flagged as unexpected) plus the legacy
  `android.permission.USE_FINGERPRINT` (never allowlisted); and
  androidx.core (1.9+) injects a per-applicationId
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` signature-class guard
  into every merged manifest on U+ (harmless, auto-granted, name
  embeds the applicationId — each flavor's allowlist carries its own
  spelling).
- **the edit app**: media3's AARs merge `WAKE_LOCK`,
  `ACCESS_NETWORK_STATE` and the legacy uncapped
  `READ_EXTERNAL_STORAGE` (the source manifest requests only the
  pre-Q capped `WRITE_EXTERNAL_STORAGE`), plus the same androidx.core
  dynamic-receiver guard. The r41 hard strip still holds: NO
  `INTERNET` anywhere in the merged APK (ACCESS_NETWORK_STATE is a
  connectivity-STATE read, not a socket grant; the editor's own
  build-time gate still fails on any INTERNET).

The fix is allowlist-only (release.yml): no APK carries a permission
it did not already carry at r46; the gate now describes the merged
reality instead of the source-manifest wish. The rule itself is
unchanged — an unexpected permission still refuses the publish.
