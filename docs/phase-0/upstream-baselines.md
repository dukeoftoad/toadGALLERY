# Upstream Baselines — Phase 0 Record

**Status:** Recorded · **Recorded on:** 2026-10-29 (UTC) · **Recorder:** toadGALLERY Phase 0 bootstrap
**Purpose:** Make the project reproducible before changing code. This document pins the exact
upstream revisions, licenses, and toolchains that all further audit work (Phase 1) is measured against.

Method: GitHub REST API metadata + shallow clones (`--depth 1`) of both upstreams at their
default branches. No repository merge has been made — clones are local, gitignored references.

---

## 1. Fossify Gallery

| Field | Value |
|---|---|
| Repository | <https://github.com/FossifyOrg/Gallery> |
| Default branch | `main` |
| **Recorded commit** | `e9812299b9d444b57072313cd2c3efd59c790f98` |
| Commit subject | `chore(release): v1.14.1 (30) (#1184)` |
| Commit date | 2026-10-24 |
| Version at HEAD | **v1.14.1** (versionCode 30) |
| License | **GPL-3.0** (full GPLv3 text at repo root `LICENSE`) |
| Application ID | `org.fossify.gallery` |
| Language mix (bytes) | Kotlin ~100% (+ traces of Ruby for fastlane) |

### Toolchain (from `gradle/libs.versions.toml`, `gradle.properties`, `app/build.gradle.kts`)

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.10 |
| KSP | 2.3.8 |
| Java compatibility | 17 (`VERSION_17`) |
| compileSdk / targetSdk | **36** (Android 16) |
| **minSdk** | **26** (Android 8.0+) |
| Detekt | 1.23.8 (with compose-rules 0.4.28) |

### Key dependencies (selection)

- **UI:** ViewBinding (no Compose in app UI), Fossify Commons `org.fossify:commons:7.1.3`
- **Image loading:** Glide (KSP compiler 5.0.7, with `avif-integration`), Picasso 2.71828,
  `subsampling-scale-image-view` (Fossify fork, zoomable large images), gestureviews (Fossify fork)
- **Video:** androidx.media3 / ExoPlayer 1.10.1
- **Database:** Room 2.8.5 (runtime + ktx + KSP compiler)
- **Metadata:** `it.sephiroth.android.exif:library:1.0.1`, Apache Sanselan `0.97-incubator`
- **Formats:** android-gif-drawable 1.2.31, animated APNG/WebP/AVIF (penfeizhou `animation-android` 3.0.5),
  JPEG XL (`jxl-coder-glide` 2.6.1), SVG (androidsvg-aar 1.4), WebP decoder (zjupure 2.7.4.16.0)
- **Editing:** vanniktech `android-image-cropper` 4.7.0, `androidphotofilters` (naveensingh fork) —
  *upstream has a built-in editor, which toadGALLERY splits into the separate Edit app*
- **Build:** product flavors `foss` / `gplay`, optional ABI splits, R8 minify + resource shrink,
  debug suffix `.debug`, signing via `keystore.properties` or env vars

### Feature surface observed at HEAD (audit input for Phase 1)

Timeline/grid browsing; albums (directories); included/excluded/hidden folder management;
search activity; favorites; recycle bin (trash); slideshow; wallpaper set; widget;
video player (playback speed, cardboard/VR intent); built-in photo/video editor
(crop, rotate, flip, resize, draw, filters); portrait/photo items; extended EXIF details;
third-party editor intents; 40+ translations via fastlane metadata.

---

## 2. Aves

| Field | Value |
|---|---|
| Repository | <https://github.com/deckerst/aves> |
| Default branch | `develop` |
| **Recorded commit** | `4f940ae48bd9364a0a532b84f283ec60755d9904` |
| Commit subject | `minor` |
| Commit date | 2026-10-28 |
| Version at HEAD | **1.15.8+182** (from `pubspec.yaml`) |
| License | **BSD-3-Clause** (repo root `LICENSE`, (c) 2020 Thibault Deckers) — *verified from file, not assumed* |
| Application ID | `deckers.thibault.aves` |
| Language mix (bytes) | Dart ~74.4%, Kotlin ~18.2%, Java ~7.1% |

### Toolchain (from `pubspec.yaml`, `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`)

| Component | Version |
|---|---|
| Flutter SDK | 3.47.8 (bundled via `flutter_wrapper`) |
| Dart SDK | `>=3.13.7 <4.0.0` |
| Java toolchain | JDK 21 (Temurin/Adoptium, Gradle toolchain provisioning) |
| compileSdk | **37 upstream** — **36 is the newest publicly installable platform** (verified 2026-10-29); smoke build patched to 36 |
| minSdk / targetSdk | Flutter 3.47.8 defaults: **minSdk 24, targetSdk 36** (resolved from `FlutterExtension.kt` of the bundled SDK — see build-notes.md) |

### In-repo workspace plugins (13)

`aves_magnifier`, `aves_map`, `aves_model`, `aves_report`, `aves_report_crashlytics`,
`aves_screen_state`, `aves_services`, `aves_services_google`, `aves_ui`, `aves_utils`,
`aves_video`, `aves_video_exo` (disabled at HEAD), `aves_video_mpv`

### Notable dependencies & native modules

- **Native module fork:** `android/exifinterface` — a **fork of `androidx.exifinterface`**
  (`ExifInterfaceFork.java`), license **Apache-2.0** (own `LICENSE.txt`)
- **Video:** mpv-based player plugin (`aves_video_mpv`), ExoPlayer plugin available
- **Charts:** `community_charts_flutter` (deckerst fork) — histogram-style visualizations
- **Network/casting:** `dlna_dart` (deckerst fork), `connectivity_plus`, `floating` fork
  — *note: network features exist upstream and must be consciously excluded or gated
  for toadGALLERY's offline-first policy*
- **Flavors:** `libreProfile` (FOSS) vs Google flavor (google-services.json, Crashlytics)
  — *toadGALLERY must model only the libre flavor*

### Feature surface observed at HEAD (audit input for Phase 1)

Rich browsing/collections with filters & chips; deep metadata explorer (EXIF/XMP/IPTC
categories, histograms); extensive format handling (incl. RAW variants, motion photos);
map view (`aves_map`) — candidate for optional deferral; DLNA casting (defer/exclude);
entry editor is external (Aves delegates to other editors).

---

## 3. Reproducing these baselines

```bash
git clone https://github.com/FossifyOrg/Gallery.git
git -C Gallery checkout e9812299b9d444b57072313cd2c3efd59c790f98

git clone -b develop https://github.com/deckerst/aves.git
git -C aves checkout 4f940ae48bd9364a0a532b84f283ec60755d9904
```

Build verification commands and their expected outputs are tracked in
[`build-notes.md`](build-notes.md) (pending a proper Android/Flutter toolchain environment).

## 4. Initial supported Android range (proposal — confirm in Phase 0)

- **minSdk proposal:** 26 (Android 8.0) — the floor of the two bases' effective support;
  raising it above 26 must be a deliberate decision, not an accident.
- **target/compileSdk proposal:** 36 (Android 16) at first release; Aves already compiles
  against 37, so toadGALLERY should plan to track the newest stable API quickly.
- **Android 16 behaviors to test (candidates — verify against final SDK docs):**
  partial photo/video access (`READ_MEDIA_VISUAL_USER_SELECTED`) flows and re-prompts,
  Private Space visibility, edge-to-edge enforcement, predictive back gesture
  (`enableOnBackInvokedCallback`), photo picker interplay, and MANAGE_MEDIA role changes.

## 5. What remains open in Phase 0

All items closed at the Phase 0 sign-off (2026-10-29) except the Aves on-hardware build:

- [x] Baseline builds — Fossify **built & verified**; Aves **blocker fully documented** (roadmap accepts "built or blockers documented") — `build-notes.md`
- [x] Aves `minSdk`/`targetSdk` resolved (Flutter 3.47.8 defaults: 24 / 36)
- [x] Proposed Android range **confirmed**: minSdk 26, target/compile 36 — rationale in `sign-off.md`
- [x] Android 16 behavior checklist confirmed — `sign-off.md` §3
- [x] Dependency license screening (direct deps) — `../phase-1/dependency-license-compatibility.md`
- [x] Must-have scope & deferrals adopted as binding (roadmap §6) — `sign-off.md` §1
- [ ] Aves APK build on adequate hardware/CI — **GitHub issue #2** (tracked; does not block Phase 1+)
