# Dependency & License Compatibility Report — Phase 1

> **⚠️ PARTIALLY SUPERSEDED 2026-10-29 by ADR-0008.**
> This report was originally produced under ADR-0001 (Fossify-seed
> strategy). Under ADR-0008 (features/UI/UX/designs only, no code
> ports) the following dependencies were re-evaluated; per issue #21
> (2026-10-30) the two Fossify-derived libraries below are **DROPPED**
> from the plan entirely (rationale in §3; blockers cleared in §5):
> - `org.fossify:commons` (no longer needed; replace with AndroidX
>   equivalents and our own Compose theme) — removed; tracked in
>   `docs/architecture/compose-migration.md`.
> - `androidphotofilters` (naveensingh fork) --- **DROPPED** (issue #21).
>   Was a release blocker pending POM verification; under ADR-0008 it is
>   removed from the plan entirely — a Fossify-derived editor dependency,
>   and the editor is reimplemented from scratch in Phase 6.
> - `jxl-coder-glide` --- **DROPPED** (issue #21). A Fossify-derived JPEG XL
>   decoder, removed from the plan entirely; JPEG XL support, if ever
>   needed, is re-evaluated fresh under the features-only stance.
>
> The remaining dependency screen (androidx.*, media3, Glide/Coil,
> etc.) stands. The product license remains GPL-3.0 (voluntary per
> ADR-0004 clarification).

**Date:** 2026-10-29 · **Purpose:** roadmap Phase 1 deliverable: identify licensing and
technical blockers **before substantial code is copied**. Not legal advice.
Licenses below were read from the repositories/POM metadata at (or near) the pinned
baselines; anything not fully verified is explicitly flagged `VERIFY`.

## 1. Product license frame

toadGALLERY ships as **GPL-3.0** (ADR-0004, voluntary per ADR-0008 clarification).
Consequence for every dependency: it must be GPL-3.0-compatible (permissive licenses are;
copyleft licenses other than GPL/AGPL are generally not). This report screens for that.

## 2. Upstream base licenses (verified from files)

| Component | License | Consequence for us |
|---|---|---|
| Fossify Gallery (`org.fossify.gallery` code) | GPL-3.0 | Derived code keeps GPL-3.0; attribution to Fossify + Simple Gallery lineage. |
| `org.fossify:commons:7.1.3` | GPL-3.0 | Same. Binary dependency on Maven Central — acceptable, same lineage. |
| Aves (Dart) | BSD-3-Clause | Reference use; any translated *behavior* is our Kotlin code (ideas aren't licensed). Keep a "inspired by Aves" credit. |
| Aves `android/exifinterface` fork | Apache-2.0 | **Code-adaptable** with notice + license text preserved (the one port candidate). |

## 3. Fossify baseline dependency screen (the libraries we inherit by "Keeping" its stack)

| Dependency | License | GPL-3.0 compatible | Notes |
|---|---|---|---|
| androidx (Room, media3/ExoPlayer, Lifecycle, ConstraintLayout, SwipeRefresh, DocumentFile, Print) | Apache-2.0 | Yes | clean |
| Glide (+avif-integration, KSP compiler 5.0.7) | BSD-style / Apache-2.0 dual headers | Yes | verify per-artifact POM at lock time |
| Picasso 2.71828 | Apache-2.0 | Yes | consider dropping (dup of Glide role) |
| subsampling-scale-image-view (Fossify fork) | Apache-2.0 (upstream davemorrissey) | Yes | fork provenance recorded |
| gestureviews (Fossify fork) | Apache-2.0 | Yes | |
| it.sephiroth.android.exif 1.0.1 | Apache-2.0 | Yes | will be superseded by the Aves fork port (Phase 4) |
| Apache Sanselan 0.97-incubator | Apache-2.0 | Yes | |
| androidx.media3 ExoPlayer 1.10.1 | Apache-2.0 | Yes | our chosen video stack |
| vanniktech android-image-cropper 4.7.0 | MIT | Yes (MIT is GPL-compat) | used by the future Edit app |
| android-gif-drawable 1.2.31 | Apache-2.0 | Yes | |
| penfeizhou animation (APNG/aWebP/AVIF) 3.0.5 | Apache-2.0 | Yes | |
| zjupure WebpDecoder (Glide) | Apache-2.0 (libwebp BSD) | Yes | `VERIFY` POM |
| awxkee jxl-coder-glide 2.6.1 | Apache-2.0? | n/a — **DROPPED** | **DROPPED per ADR-0008 (issue #21).** Fossify-derived JPEG XL decoder, removed from the plan entirely; JPEG XL support, if ever needed, is re-evaluated fresh under the features-only stance. (Historical: `VERIFY` — wraps libjxl, BSD; POM never verified, no longer needs to be.) |
| androidphotofilters (naveensingh) | `VERIFY` | n/a — **DROPPED** | **DROPPED per ADR-0008 (issue #21).** Fossify-derived editor dependency, removed from the plan entirely; the editor is reimplemented from scratch in Phase 6. (Historical: JitPack build of a GitHub fork, upstream Apache-2.0 by origin; fork POM was never verified — no longer a blocker.) |
| androidsvg-aar 1.4 | Apache-2.0 | Yes | |
| okhttp/okio (transitively via commons) | Apache-2.0 | Yes | verify no runtime network use |

## 4. Aves baseline screen (libraries relevant to what we adapt/port)

| Dependency | License | Notes |
|---|---|---|
| androidx.exifinterface (Aves fork) | Apache-2.0 | our port target; keep its LICENSE.txt |
| sqflite (Dart DB) | MIT | we use Room instead; no port |
| libmpv via aves_video_mpv | mpv: **GPL-2+ / LGPL option per build** | **Not adopted** — we stay on media3; mpv noted as a future option only if its exact build license is verified |
| fluster (map clustering) | BSD-2? `VERIFY` | only relevant if maps are ever un-deferred |
| DLNA (dlna_dart fork) | `VERIFY` | excluded from toadGALLERY anyway |
| Flutter/Dart ecosystem deps | various permissive | irrelevant — no Dart ships in our product |

## 5. Compatibility verdict

- **No GPL-3.0 incompatibility found** among the dependencies we intend to keep or adapt.
- Former **release blockers** — **resolved 2026-10-30 (issue #21): both dependencies are
  DROPPED per ADR-0008**, so neither needs POM verification nor blocks release:
  1. `androidphotofilters` (naveensingh fork via JitPack) — **DROPPED**: Fossify-derived
     editor dependency; the editor is reimplemented from scratch in Phase 6.
  2. `jxl-coder-glide` — **DROPPED**: Fossify-derived JPEG XL decoder; JPEG XL support,
     if ever needed, is re-evaluated fresh under the features-only stance.
  The dependency screen now carries **no open release blockers**.
- The `libmpv` option is documented as "not adopted" so it cannot silently enter a build.
- Full transitive closure will be generated mechanically from the Gradle lockfile
  (`dependencies`/`lockfile` output) at Phase 9 release packaging; this report screens
  the direct dependency set, which is where compatibility decisions are actually made.

## 6. Obligations register (what we must ship at release)

1. GPLv3 full text (root `LICENSE`) — present since the Phase 0 bootstrap.
2. Per-file provenance for: Fossify-derived Kotlin files; the Aves exifinterface port;
   any future vendored decoder. Recorded in `licensing-and-attribution.md` §5 at copy time.
3. `THIRD_PARTY_NOTICES` package (Phase 9) with the table above in machine-readable form.
4. Source availability: every published APK links its exact source tree commit.
5. Attribution credits: Fossify + Simple Gallery lineage; Thibault Deckers / Aves
   (BSD-3) for ported designs; Apache-2.0 notices for the exifinterface fork.
