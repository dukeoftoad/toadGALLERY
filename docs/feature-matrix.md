# Feature Matrix — Capability Origin & Status

**Status:** DRAFT (seeded during Phase 0 bootstrap; every row must be confirmed by the
Phase 1 source audit before it may drive implementation) · **Created:** 2026-10-29

## How to read this

- **Fossify / Aves columns** state what the upstream *has at the recorded baseline*
  (commit, not main-page marketing). Evidence marked `obs` = observed in the baseline
  tree during the Phase 0 audit; `doc` = documented upstream, not yet code-verified.
- **Disposition** uses the roadmap vocabulary: **Keep** (use one upstream's
  implementation as-is), **Adapt** (port/modify with license notice), **Reimplement**
  (fresh code in target architecture), **Defer** (post-first-release), **Exclude**
  (deliberately not in toadGALLERY).
- Dispositions here are *initial proposals* pending the Phase 1 evidence audit.
- Status column: `draft` → `audited` (Phase 1) → `owned` (an implementation owner exists)
  → `done` (tested per roadmap §5).

## Core capabilities

| Capability | Fossify Gallery (v1.14.1) | Aves (1.15.8+182) | Initial disposition | Status |
|---|---|---|---|---|
| Timeline / grid browsing | Yes — `MainActivity`/`MediaActivity`, sections, adapters (`obs`) | Yes — rich collections UI (`obs`) | **Keep Fossify** as skeleton; study Aves grouping | draft |
| Albums / folder management | Yes — incl. hidden/excluded/included folders (`obs`) | Yes — collections/dynamic albums (`obs`) | **Adapt**: Fossify base + Aves concepts | draft |
| Image viewer | Yes — ViewPager + subsampling-scale-image-view, gestures (`obs`) | Yes — `aves_magnifier`, interaction polish | **Keep Fossify**, adopt Aves UX patterns | draft |
| Video playback | Yes — media3/ExoPlayer 1.10.1, playback speed (`obs`) | Yes — mpv plugin + exo plugin (`obs`) | **Keep Fossify** (ExoPlayer); mpv optional later | draft |
| Search | Basic text search (`SearchActivity`, `obs`) | Advanced filter/chip search (`obs`) | **Reimplement** Aves-style search model in Kotlin (Phase 5) | draft |
| Favorites | Yes — Room-backed (`obs`) | Yes (`doc`) | **Keep Fossify** | draft |
| Trash / recycle bin | Yes (`obs`) | Yes (`doc`) | **Keep Fossify**; verify provider-level semantics (Phase 7) | draft |
| Sorting / grouping | Yes — sorting/grouping dialogs (`obs`) | Yes (`obs`) | **Keep Fossify**; align terminology | draft |
| Multi-select & sharing | Yes (`obs`) | Yes (`obs`) | **Keep Fossify** | draft |
| File operations (copy/move/delete/restore) | Yes — SAF-aware (`obs`) | Yes (`obs`) | **Keep Fossify**; explicit confirm UX pass | draft |
| Metadata details view | Basic — extended EXIF (sephiroth exif, sanselan) (`obs`) | Deep — EXIF/IPTC/XMP categories, charts (`obs`) | **Reimplement** Aves-style metadata service in Kotlin (Phase 4) | draft |
| Format breadth (HEIC/AVIF/RAW/animated) | Broad via Glide+decoders: GIF, APNG, aWebP, AVIF, JXL, SVG (`obs`) | Broad, incl. RAW & motion photo handling (`obs`) | **Audit first** (Phase 4); likely **Adapt** both | draft |
| Caching / thumbnail pipeline | Glide (+Picasso legacy paths) (`obs`) | Custom image cache (`obs`) | **Keep Fossify** (Glide); measure (Phase 8) | draft |
| Maps / reverse geocoding | No | Yes — `aves_map` (`obs`) | **Defer** (optional, network-aware, post-first-release) | draft |
| DLNA / casting | No | Yes — `dlna_dart` (`obs`) | **Exclude** (offline-first, no network) | draft |
| Editing tools | Built-in: crop, rotate/flip, resize, draw, filters, other-app editors (`obs`) | None built-in — delegates to external editors (`obs`) | **Split into separate APK** per roadmap Phase 6 (ADR-0002) | draft |
| Widgets | Yes (`obs`) | No (n/a) | **Defer** (post-first-release) | draft |
| Wallpaper / print | Yes (`obs`) | No | **Defer** (nice-to-have) | draft |
| Slideshow | Yes (`obs`) | Yes (`doc`) | **Keep Fossify** | draft |
| Cloud / accounts / analytics | None in FOSS flavor (`obs` — flavor split) | None in libre flavor (`obs`); Google flavor exists | **Exclude** from toadGALLERY in any flavor | draft |
| Accessibility (TalkBack, targets, contrast) | Partial, untested (`doc`) | Partial (`doc`) | **Reimplement** baseline checks (Phase 7 gate) | draft |

## Overlap summary (initial)

The overlap is substantial in browsing, albums, favorites, trash, and file operations —
exactly the areas where the roadmap's "one product" principle demands a single chosen
implementation. The complementarity is also real: Fossify brings the native Kotlin
foundation, a working editor codebase to extract, and broad decoder coverage; Aves brings
a superior metadata model, search/filter UX, and format intelligence worth reimplementing.

## Dependency overlap (to resolve in Phase 1)

- Both use ExoPlayer-family video stacks (media3 vs. mpv/exo plugins) — one must win.
- Image loading: Glide/Picasso (Fossify) vs. custom Flutter cache (Aves) — Glide stays;
  Aves-side ideas migrate as design references only.
- EXIF: sephiroth + sanselan (Fossify) vs. **forked androidx.exifinterface with
  XMP support** (Aves, Apache-2.0) — the Aves fork is the strongest **Adapt** candidate
  discovered so far, and it is permissively licensed (see
  `licensing-and-attribution.md`).
- Database: Room (Fossify) vs. custom Dart stores — Room remains the product database.

## Non-negotiable product constraints (apply to every row)

1. Offline-first: no core feature may require network access.
2. Originals protected: destructive operations are explicit and reversible where the platform allows.
3. Content URIs everywhere: no raw filesystem-path dependencies in product code.
4. One product: consistent navigation, terminology, and design tokens across all screens.
