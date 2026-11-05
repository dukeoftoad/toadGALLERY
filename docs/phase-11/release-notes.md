# Phase 11 --- the r45 release notes (tag `v0.4.5-alpha-r45`)

**Status:** the master release-notes document for the Phase 11 close-out
testing pre-release. The GitHub release body points here. Authored in the
r45 docs round (branch `tg-p11-release-docs`); the release itself is cut by
the maintainer after this round merges.

---

## What this release is

This is the **Phase 11 close-out testing pre-release** --- and the first
release in which **both toadGALLERY apps ship together in one GitHub
release**: the main gallery APK and the toadGALLERY Edit companion editor
APK, each built, signed, and published by the dedicated `Release` workflow
(`release.yml`, ADR-0018, issue #59).

It is the **final pre-v1.0 cut** of the Phase 0--11 plan as it stands: the
code side of every phase is merged (Phases 0--4 and 6--7 done; Phase 5
code-side complete with the r39 ente-port exit round; Phase 10 done with
the r40--r42 rounds; Phase 11's release engineering in this window), the
project percent stands at 91 (`progress.json`), and what remains open is
deliberately, honestly open --- the **on-device dogfood and benchmark
rows**, which the project's standing directive reserves for humans on real
devices (see "What this release does NOT claim" below).

It is still a **pre-release** (`prerelease: true`, `make_latest: false`
per ADR-0018): the v1.0 stable cut remains a human decision that waits on
those device rows, not on any further code round.

## The APK files (what to download)

This release carries **six artifact files** --- the two APKs plus, for
each, its `.sha256` checksum and its R8 mapping. Both APKs are needed for
the full experience; either installs and works alone (see "Install +
update notes").

### `toadGALLERY-0.4.5-alpha-r45.apk` --- the gallery app (versionCode 37)

The main app. Everything below is merged code, sourced from the roadmap,
`tasks.md`, and `docs/feature-matrix.md` --- this section invents nothing:

- **Photo/video timeline + viewer** (Phase 3): the staggered-grid Photos
  timeline with Day/Month grouping, the full-screen viewer with
  pinch-zoom/pan/double-tap, animated GIF/WebP, and video playback via
  media3 ExoPlayer with the 10-bit HEVC software-decoder fallback.
- **Albums/folders** (Phase 3): the albums tab and album-detail screens
  over `BUCKET_ID`, with multi-select file operations.
- **Favorites** (Phase 3) with the pinned Favorites album, **user-facing
  sorting** (newest/oldest), and the **recycle bin** (API 30+
  `createTrashRequest`, restore / permanent delete / empty trash).
- **The PIN/biometric hidden vault** (Phase 3, ADR-0017/ADR-0019): the
  at-rest-encrypted app-private vault (AndroidKeyStore AES-256-GCM under a
  PBKDF2-verified PIN, opt-in `BIOMETRIC_STRONG` unlock binding,
  auto-lock, decrypted in-memory thumbnails only). Honest policy: no
  cloud, no recovery --- a forgotten PIN makes the vault permanently
  unopenable (see the support template).
- **Multi-select sharing and file operations** (Phase 3): share, rename,
  copy, move, delete with undo snackbars and batch progress.
- **Aves-grade metadata details + edit + offline maps** (Phase 4, r10--r20):
  the structured Details view (dimensions, capture date + provenance,
  camera, exposure triangle, GPS, raw EXIF tree), editable metadata
  fields, tags/ratings/colour labels, collections and smart-filter chips,
  and the on-device map view with **sideloaded offline tiles** (no online
  tile fetch, ever).
- **Smart search** (Phases 4--5): compose filters over date / type /
  album / favorite / name / metadata fields / tags / people, per the
  roadmap's smart-search task.
- **Opt-in on-device AI** (Phase 5, the ente-port lineage, ADR-0010):
  **semantic (content) search** ("beach at sunset"), **people
  clustering**, and the **Things dynamic collections** (the r39 round) ---
  all on-device, all opt-in with explicit consent rows, all off by
  default. The ente-derived engine models download on first enablement
  from the single allowlisted host `models.ente.com` (ADR-0020; see the
  permissions doc). **One honest distribution note:** the engine's native
  library only enters the gallery APK under the opt-in
  `-PwithEngine` build property, and every published build path ---
  CI and the release workflow alike --- builds without it; on the release
  APK the engine-dependent AI surfaces therefore present their honest
  engine-unavailable state (the r22 facade design --- a missing engine is
  an honest state, never a crash; the on-device AI dogfood rides the
  opt-in engine-enabled debug builds, `engine-apk.yml` /
  `modelpack-apk.yml`).
- **Moments timeline, Stories shelf, collage creator, slideshow** (the r27
  MI organisational round).
- **Settings → Development progress**: the in-app progress screen (the
  r34 F-1 close) mirroring `progress.json` as a build-time snapshot.

### `toadGALLERY-edit-0.4.10-edit-alpha-r45.apk` --- the companion editor (versionCode 15)

toadGALLERY Edit, the separate installable editor APK --- and this is its
**first-ever release artifact**: the editor's version line ran
versionCodes 1--14 (r29--r42) as unreleased CI debug builds only; this
release ships its first signed, published build. What's inside, per the
Phase 6/7/10 delivery records:

- **Editor v1** (r29--r32, Phases 6): crop / rotate / flip / straighten /
  perspective, the full adjustment suite (exposure, brightness, contrast,
  highlights, shadows, saturation, temperature/tint, eight-band HSL,
  vignette, sharpen), and the ADR-0011 **non-destructive recipe
  pipeline** --- the session is a versioned JSON sidecar; every export is
  a new MediaStore entry; no replace-original path exists.
- **Editor v2** (r33--r38, Phase 7): LUT filters with intensity plus
  **user `.cube` import**, the text tool (four bundled SIL OFL 1.1
  fonts), stickers (project-authored vector set + PNG/WebP import),
  freehand drawing, mosaic/blur regions, the five parametric frames ---
  and the **undo/redo capstone** test-pinned across all five layer
  families.
- **Editor v3** (r41, Phase 10): **magic erase**, **sky replacement**
  (five project-authored parametric skies + import), **bokeh**, and the
  **OPT-IN face retouch** (consent dialog + settings kill-switch). The
  four ONNX models ship in-APK (61,613,133 bytes, sha256-pinned) and all
  inference is on-device through ONNX Runtime 1.31.1 --- zero network.
- **The lightweight video editor** (r42, Phase 10, ADR-0012): trim, mute,
  slow-mo (0.25x--2x), frame extraction, and GIF/animated-WebP export
  over the media3 Transformer --- preview and export share one shader
  model; every export is a new file.

The editor is opened **from the gallery** through the versioned
content-URI contract (`org.toadgallery.action.EDIT`): image edits resolve
the contract-v1 surface, video edits negotiate contract v2 (the r42
additive extension --- the v1 image wire is byte-identical). **Install
both APKs for the full experience**; without the editor installed, the
gallery offers the plain third-party `ACTION_EDIT` fallback for images.

## Install + update notes

- **Android 8.0+** (minSdk 26). targetSdk 36.
- The two APKs have **distinct app IDs** (`io.github.dukeoftoad.gallery`
  and `io.github.dukeoftoad.gallery.edit`), so they install and update
  independently --- updating one never touches the other.
- **Gallery updates install in place** over every previous toadGALLERY
  pre-release: the signing identity has been stable since the first
  published build (r3, ADR-0018 --- the pinned release key).
- **The editor is a NEW app ID**: this is its first release artifact, so
  it is a first install, not an update. (Dogfood-side `.debug` builds
  carry the `.debug` suffix and install separately by design.)
- Sideload both APKs with your usual method (file manager, `adb install`,
  etc.). There is no Play Store pipeline (ADR-0018 records this
  deliberately); F-Droid metadata is prepared in this window
  (`fdroid/metadata/`, the r44 round --- see
  `docs/phase-11/fdroid-metadata.md`) but not yet submitted.

## Verification

Every release artifact is verifiable locally:

1. **Checksum** --- each APK ships beside its `.sha256`:
   ```bash
   sha256sum -c toadGALLERY-0.4.5-alpha-r45.apk.sha256
   sha256sum -c toadGALLERY-edit-0.4.10-edit-alpha-r45.apk.sha256
   ```
2. **Signature** --- the release key's signer certificate is pinned in the
   workflow (a hard `apksigner verify` gate refuses to publish anything
   else) and documented here for your side of the check:
   ```bash
   apksigner verify --print-certs toadGALLERY-0.4.5-alpha-r45.apk
   ```
   The `Signer #1 certificate SHA-256 digest` line must read exactly:
   ```
   8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494
   ```
   The same digest applies to both APKs --- one release key, two apps
   (ADR-0018).
3. **Crash de-obfuscation** --- release builds are R8-minified, so each
   app ships its `*-mapping.txt` as a release artifact
   (`toadGALLERY-0.4.5-alpha-r45-mapping.txt`,
   `toadGALLERY-edit-0.4.10-edit-alpha-r45-mapping.txt`). If you report a
   crash, attach the raw stack trace: maintainers retrace it against the
   matching release's mapping. Keep the mapping alongside the APK if you
   archive releases.

## The version history (every released version so far)

For better finding of the project: the complete map of published releases
plus the versions that existed only as CI builds. Sourced from the git tag
list, the `versionCode` comment blocks in `apps/gallery/build.gradle.kts`
and `apps/edit/build.gradle.kts`, and `CHANGELOG.md`.

**Published releases (gallery-only era, `v0.4.0-alpha` and
`v0.4.1-alpha`):**

| Tag | Gallery version | versionCode | What it contained |
|---|---|---|---|
| `v0.4.0-alpha-r3` | 0.4.0-alpha | 8 | the first signed testing pre-release (ADR-0018): the Phase 3 core --- file ops, trash, the vault core, the viewer, on top of the timeline/albums shell |
| `v0.4.0-alpha-r4` | 0.4.0-alpha | 9 | Phase 3 wave 1: the search tab, viewer ops + HEVC decoder fallback, album-detail ops (#62--#65) |
| `v0.4.0-alpha-r5` | 0.4.0-alpha | 10 | Phase 3 wave 2: vault biometric binding + decrypted thumbnails, favorites, sorting (#57/#58/#66) |
| `v0.4.1-alpha-r7` | 0.4.1-alpha | 11 | the Phase 3 completion bump --- the #69 vault thumbnail lock-race guard era (#71) |
| `v0.4.1-alpha-r8` | 0.4.1-alpha | 12 | vault integrity round 2 (#73) + the diagnostics logger and the appearance/About settings |
| `v0.4.1-alpha-r9` | 0.4.1-alpha | 13 | the two r8 device findings fixed: the Caller-provided-IV vault crypto fix + the staggered-grid stale-index crash (#76) |
| `v0.4.1-alpha-r10` | 0.4.1-alpha | 14 | `BIOMETRIC_STRONG` + vault backup exclusions + the Phase 4 catalogue foundation (Room, paging) |
| `v0.4.1-alpha-r11` | 0.4.1-alpha | 15 | the crash/audit findings round (#81): revision tokens, Room transactions, decode-overflow guard |
| `v0.4.1-alpha-r12` | 0.4.1-alpha | 16 | the Phase 4 scan driver wired into app start + persisted EXIF capture dates (#83) |
| `v0.4.1-alpha-r19` | 0.4.1-alpha | 23 | the code-improvement round; releases resumed after the r13--r18 build freeze --- build 23 shipped the accumulated r13--r19 range (timeline capture-date sort, metadata Details view, editable fields, the map view, collections + smart-filter chips, the format audit) |

**This release (both apps, first time):**

| Tag | Gallery | Editor | What it contains |
|---|---|---|---|
| `v0.4.5-alpha-r45` | 0.4.5-alpha (versionCode 37) | 0.4.10-edit-alpha (versionCode 15, first release artifact) | the Phase 11 close-out pre-release: everything above in the APK-files section |

**The versions in between (never released --- CI debug artifacts only):**
per the maintainer's release-freeze directive ("no new device builds until
Phase 5 completes", lifted at the r28 exit gate), gallery versions
`0.4.1-alpha` (versionCodes 17--26, rounds r13--r22), `0.4.2-alpha`
(versionCodes 27--34, rounds r23--r34), `0.4.3-alpha` (versionCode 35,
r39), and `0.4.4-alpha` (versionCode 36, r40) shipped only as CI-built
debug APKs from the opt-in debug workflows --- never as signed release
tags. The editor's whole version line to date (versionCodes 1--14,
`0.1.0-edit-alpha` through `0.4.9-edit-alpha`, rounds r29--r42) was
likewise CI-debug-only. The gallery version jumps 0.4.1 → 0.4.5 in this
release because those intermediate `versionName`s were consumed by the
unreleased CI builds; `versionCode` (37) is the monotonic truth.

## What this release does NOT claim

Honesty section --- the project's standing directive is that **every
benchmark and device result cell is human-only**: no code round, no doc,
and no release claims them (`tasks.md` Phase 5/7/10 rows;
`docs/phase-10/benchmark-scenarios.md` and `device-matrix.md` carry
skeletons whose every result cell reads HUMAN-ONLY).

- The **on-device dogfood rows stay open**: the Phase 5 ente-port rows
  (§13--§18), the Phase 6 editor round-trip/export-robustness rows
  (§19--§20), the Phase 7 editor-tool rows (§22--§27), and the Phase 10
  rows (the r40 a11y walk, the r41 AI latency/quality + airplane-mode
  rows, the r42 long-video/4K/HDR matrix). No device-verified performance
  number exists in any of this release's documentation.
- The **gallery's ente-derived AI engine does not ship in the release
  APK**: the native library is an opt-in build property
  (`-PwithEngine`) that every published build path leaves off, so the
  release artifact's engine-dependent AI features present their honest
  unavailable state; the engine-enabled builds are the opt-in debug
  workflows. (Recorded by the parallel r44 round's
  `docs/phase-11/fdroid-metadata.md`, which also carries the licensing
  consequence: no distributed APK contains the vendored AGPL engine.)
- The **v1.0 stable cut is a future human decision** that waits on those
  rows --- this release stays a pre-release on purpose (ADR-0018).
- **Known limitations** are documented, not hidden:
  [`docs/phase-11/known-limitations.md`](known-limitations.md) sources
  every open follow-up (F-24..F-28 and the rest of the register).
- **F-Droid submission is prepared but not submitted**: the metadata
  lives at `fdroid/metadata/` with the reproducible-build instructions at
  [`docs/phase-11/reproducible-builds.md`](reproducible-builds.md) (the
  r44 round, landing in this same window); actual submission is a
  maintainer action after review.
- The **third-party FOSS review** is self-conducted and recorded at
  [`docs/phase-11/foss-review.md`](foss-review.md) --- its open findings
  are listed there honestly.
