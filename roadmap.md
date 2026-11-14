# toadGALLERY --- Development Roadmap (Compact)

**Status:** v3.0 compact recraft --- 2026-11-12\
**Product:** A private, offline-first Android gallery built from scratch
in Kotlin + Jetpack Compose, inspired by Mi Gallery's simplicity and
Aves-grade metadata --- with **toadGALLERY Edit** as a separate
installable editor APK. No code is ported from any upstream: upstreams
(Fossify Gallery, Aves libre, Mi Gallery) contribute features, UI/UX and
designs only. CI is GitHub Actions.

**Current head:** the `editor` branch at r74 ("the pending-ten round on
the editor lane"); unified versionCode 46, editor versionCode 19
(`0.4.14-edit-alpha`). Detailed per-round history lives in
`CHANGELOG.md`, `worklog.md`, and `docs/`.

---

## 1. Status snapshot

| Phase | Scope | Status |
|---|---|---|
| 0 | Baselines, toolchain, audits | DONE |
| 1 | Source audit, integration design, contract prototype | DONE (re-framed in v2.2) |
| 2 | Product shell, visual foundation, Compose + CI | REWORK DONE (Compose-native since v2.2) |
| 3 | Core media access, browsing, file ops, bin, vault | Core complete; polish ongoing |
| 4 | Aves-grade metadata layer | Complete (EXIF/XMP, on-device maps) |
| 5 | MI-style organisational UX (search, Moments, Stories) | Code-side COMPLETE (r39 ente exit) |
| 6 | Edit v1: adjustments, non-destructive pipeline | COMPLETE (contract + prototype landed r29) |
| 7 | Edit v2: filters (LUT), text, stickers, draw, mosaic, frames | CODE-COMPLETE |
| 8 | Edit v3 AI | FOLDED into Phase 10 (r41) |
| 9 | Lightweight video editor | FOLDED into Phase 10 (r42) |
| 10 | Hardening + Edit v3 AI + video editor | DONE (code-complete) |
| 11 | Release readiness, reproducible builds, F-Droid | DONE |

Post-Phase-11 rounds (r46--r74) delivered: the four-way gallery APK
matrix (basic + engine/semantic/face variants), the contract-discovery
hardening, Roborazzi screenshot harness, stable unified signing, the
Settings re-crafts, the unified app, the editor's tune-rail re-chassis
(photo editor adopts the video editor's tab-bar architecture), the
toad-green re-pin, canvas zoom, the honest-feedback kit (live histogram,
real sky thumbnails, neutral notch), and the one-launcher unification
under Settings. The head continues on the editor lane.

---

## 2. Product goals

1. One coherent product, not two competing gallery apps.
2. Upstreams contribute features, UI/UX, designs only --- never code.
3. Built from scratch in Kotlin + Jetpack Compose, Material 3.
4. Clean, fast browsing; deep metadata; smart search; safe file ops; a
   flagship editor.
5. **toadGALLERY Edit** ships as a separate APK behind a versioned
   content-URI contract, independently updatable.
6. Private and offline-first: no account, cloud, ads, analytics. The
   editor carries no INTERNET permission at all (build-gated); the
   gallery's single network grant is the allowlisted model fetch
   (ADR-0020).
7. Protect originals: non-destructive pipeline, recycle bin, PIN vault.
8. GitHub Actions is the canonical build/CI environment.
9. Licensing, attribution, testing, and upstream tracking from day one.

## 3. Project principles

1. **No code ports** --- from-scratch implementations only.
2. **One product experience** across every screen.
3. **ADRs record every significant decision**, superseded openly.
4. **The editor is the flagship** --- three editor phases plus video.
5. **On-device ML only** --- models ship in the APK; no cloud ML, ever.
6. **Editor stays a separate app** with a versioned, secure contract.
7. **Incremental, buildable changes**; a working build at every phase.
8. **License obligations preserved**; the provenance ledger records any
   code-level adaptation (empty per ADR-0008).
9. **Nothing is complete until tested** --- errors, a11y, performance,
   permissions, large libraries.
10. **CI is the merge gate.**

## 4. Repository structure (as built)

```text
toadGALLERY/
├── editor/
│   ├── app/                  # :editor:app --- standalone Edit APK shell
│   │   └── ...               # INTERNET-permission build gate, signing
│   └── core/                 # :editor:core --- the editor library
│       └── ...               # photo chassis, tune rail, video editor,
│                             # export pipeline, AI stages, ONNX assets
├── shared/
│   ├── editor-contract/      # :shared:editor-contract (pure JVM)
│   └── video-edit/           # :shared:video-edit (pure JVM export plans)
├── gallery/                  # :gallery:core / :gallery:app (main app)
├── unified/                  # :unified:app (everything-in-one build)
├── engine/                   # vendored Rust ML crates (gallery/unified)
├── docs/                     # architecture, ADRs, phase records
├── fdroid/                   # F-Droid metadata for both apps
├── tools/                    # lex check, LUT generator, CI signing
├── .github/                  # workflows: CI, releases, screenshots
├── CHANGELOG.md / worklog.md / tasks.md / roadmap.md
└── gradle/                   # libs.versions.toml (Kotlin 2.3.10, AGP 9.2.1)
```

**Branch policy:** short-lived implementation branches, squash merges,
green CI required. GPL-3.0 by voluntary choice (ADR-0004). Upstreams
pinned in `docs/phase-0/upstream-baselines.md` for reference only.

---

## 5. Phase summaries

### Phase 0 --- Baselines (DONE)

Bootstrap artifacts, Phase 0 sign-off, dependency license audit,
Android range (minSdk 26, target/compile 36), ADR-0001..0007 recorded.
See `docs/phase-0/sign-off.md`.

### Phase 1 --- Audit and integration design (DONE)

Feature-by-feature audit of Fossify/Aves/Mi Gallery; the integration
decision became ADR-0008 (from-scratch, no ports) and ADR-0009
(Compose-native). The Gallery-to-Edit contract was prototyped early.

### Phase 2 --- Shell and visual foundation (DONE via rework)

The View-based/commons shell was reworked into a Compose-native,
Material 3 app per ADR-0009; CI (GitHub Actions) became the merge gate
(ADR-0013, ADR-0016). Design tokens, themes (light/dark, toad-green
family), empty/denied/error states established.

### Phase 3 --- Core media access and browsing (core complete)

MediaStore/content-URI access, modern permission flows including
limited access; timeline/grid browsing, albums, viewer, video
playback, favorites, sort, multi-select, share; safe copy/move/delete
with recycle bin and the hidden PIN vault. Safe handling of revoked
permissions, missing files, provider errors; progressive thumbnails;
cancellable long-running operations.

### Phase 4 --- Aves-grade metadata (complete)

Details view over `androidx.exifinterface` + XMP/IPTC wrappers:
dimensions, size, capture dates, camera/lens, exposure, GPS; editable
common fields; on-device maps via offline sideload only. Parsing stays
off the main thread; unsupported formats degrade gracefully.

### Phase 5 --- MI-style organisational UX (code-side complete, r39)

Smart search with composable filters; Moments timeline; opt-in
auto-albums; Stories; collage maker; slideshow; on-device people
clustering and object/scene tagging via the vendored Rust engine
(ente-derived crates, AGPL) exposed through JNI --- gallery/unified
builds only; the editor never links it.

### Phase 6 --- Edit v1: core adjustments (complete)

The versioned Gallery-to-Edit contract as pure-JVM code
(`shared/editor-contract`): identity matching, version negotiation,
request validation, result parsing --- tested in milliseconds on both
sides' CI. The non-destructive pipeline (recipe + sidecar store,
commit-on-release drafts, undo/redo, save-copy default) and the core
adjustment suite landed with the standalone Edit prototype APK.

### Phase 7 --- Edit v2: filters and annotations (code-complete)

Five-parameter LUT `.cube` filter system with user-import flow; text,
stickers, drawing, mosaic/blur, frames --- each tool behind the same
layer/recipe model with JVM-pinned edge rules.

### Phase 8 + 9 --- folded into Phase 10 (maintainer directive, 2026-11-04)

### Phase 10 --- Hardening + Edit v3 AI + video editor (DONE)

- **Hardening (r40):** 17 heading anchors + 8 live regions a11y sweep;
  bounded background work with cooperative scan cancellation.
- **Edit v3 AI (r41):** magic erase (u2netp refinement + MI-GAN fill),
  sky replacement (five authored parametric skies), bokeh
  (Depth-Anything-V2-Small int8), opt-in face retouch (YuNet +
  consent). Four ONNX graphs (61.6 MB total) ship in
  `assets/models/` through ONNX Runtime (NNAPI-first, CPU fallback);
  the ORT AAR's stray INTERNET is hard-stripped via
  `tools:node="remove"` --- the merged editor manifest stays
  INTERNET-free.
- **Video editor (r42):** pure export-plan module
  (`shared/video-edit`: trim ranges, speed segments, mute, volume,
  frame extraction, GIF/WebP animation with byte-budget preflight)
  realized by media3 Transformer behind the `VideoExportRunner` seam;
  non-destructive MediaStore output policy.

### Phase 11 --- Release readiness (DONE)

R8-minified release variants for both apps with per-APK mapping.txt;
env-injected `TG_*` signing with pinned-certificate verification;
`reproducible-build.yml` double-build comparison; release notes,
known limitations, permissions/privacy docs, FOSS review;
`fdroid/metadata/` for both apps. First both-APK pre-release cut at
`v0.4.5-alpha-r45`.

---

## 6. Cross-phase quality gates

- **Build:** CI green or the blocker documented and isolated.
- **Tests:** every new behavior and regression gets tests.
- **Privacy:** offline-first; the INTERNET build gate stays green.
- **Data safety:** originals protected; non-destructive stays
  non-destructive.
- **Permissions:** full / limited / denied / revoked all tested.
- **Performance:** no heavy I/O, parsing, or full-res decode on the UI
  thread.
- **Licensing:** origins and licenses recorded for models and
  dependencies.
- **Accessibility:** TalkBack labels, focus order, scalable text,
  adequate touch targets on every new screen.

## 7. v1.0 must-have scope

- Photo/video timeline and viewer; albums, favorites, bin, vault.
- Multi-select, sharing, basic file operations.
- Metadata details + edit for supported formats; offline maps.
- Smart search (date/type/album/favorite/name/metadata/people/tags) +
  semantic search.
- Moments, opt-in auto-albums, Stories, collage, slideshow.
- **Edit v1+v2+v3:** crop/rotate/straighten/perspective, full
  adjustments, filters, text, stickers, drawing, mosaic, frames, magic
  erase, sky, bokeh, face retouch.
- Lightweight video editor: trim, mute, slow-mo, frames, GIF/WebP.
- Separate Edit APK with a reliable launch/export round trip.
- Permission, privacy, accessibility, performance checks.
- F-Droid-ready reproducible builds with the license package.

**Deferred:** cloud/accounts/social/ads/analytics; always-on indexing;
online maps; RAW development; multi-track video; unverified format
promises. **Explicitly not deferred:** on-device AI editing and
people/scene tagging (both opt-in, both on-device).

## 8. Definition of success

toadGALLERY v1.0 feels like one thoughtfully designed gallery: browsing
is fast, search and metadata are useful, file operations are safe, the
editor rivals Mi Gallery's in everyday capability, and every feature ---
including AI --- runs entirely on the user's device with zero network
traffic. Built from scratch in Kotlin + Compose; CI green at every
release.
