# Feature Matrix — Capability Origin & Status

**Status:** AUDITED (Phase 1 exit version) · **Audit sources:** `docs/phase-1/source-audit-fossify.md`,
`docs/phase-1/source-audit-aves.md` (evidence from the pinned baselines, 2026-10-29) ·
**Owner column = the implementation owner/source required by the Phase 1 exit criteria.**

Disposition vocabulary (roadmap §Phase 1): **Keep** (use one upstream's implementation),
**Adapt** (port with license notice), **Reimplement** (fresh code, design from the other base),
**Defer** (post-first-release), **Exclude** (deliberately out).

## Core capabilities

| Capability | Fossify (evidence) | Aves (evidence) | Disposition | Implementation owner | Phase | Status |
|---|---|---|---|---|---|---|
| Timeline / grid browsing | Yes — MainActivity/MediaActivity + MediaAdapter, sections | Yes — collection grids w/ layout variety | **Keep** Fossify, restyle | `MediaAdapter`-derived Kotlin, view-based | 3 | audited |
| Albums / folder management | Yes — incl/ex/hidden folders + dialogs | Yes — collections, dynamic albums | **Keep** Fossify; **Reimplement** Aves dynamic-album concept post-v1 | Fossify dialogs + Config | 3 | audited |
| Data model / media access | Path-keyed Room cache; MediaStore.Files; Android-11 workarounds | **URI-first** (`uri`+`contentId`), platform trash flag, 3-date model | **Reimplement** Aves' model in Kotlin on Room; reuse Fossify's MediaStore queries | toadGALLERY `shared/media-contract` (new) | 3 | audited |
| Image viewer | subsampling-scale-image-view + gestureviews + Glide | magnifier plugin, polished transitions | **Keep** Fossify stack | PhotoFragment-derived | 3 | audited |
| Video playback | media3 ExoPlayer 1.10.1, speed UI | mpv (Exo alt disabled) | **Keep** Fossify (media3); mpv **excluded** (license/complexity) | VideoFragment-derived | 3 | audited |
| Favorites | Yes — Room favorites table | Yes — filter leaf | **Keep** Fossify | FavoritesDao-derived | 3 | audited |
| Trash | App-level bin (`deleted_ts`, retention on boot) | Platform MediaStore trash (`trashed` flag) | **Reimplement** hybrid: platform trash API when available, app bin fallback; semantics doc in Phase 7 | toadGALLERY (new), Fossify bin as fallback reference | 3+7 | audited |
| Search / filters | Name-contains over full scan | **Composable filter AST** (SetAnd/SetOr, mime/date/rating/path/type…) | **Reimplement** Aves algebra in Kotlin | toadGALLERY filter engine (new) | 5 | audited |
| Multi-select & sharing | Yes | Yes | **Keep** Fossify | adapters + commons | 3 | audited |
| File operations (copy/move/delete/restore) | Yes — SAF-aware via commons | Yes | **Keep** Fossify; add explicit-confirm UX pass | commons pipelines | 3 | audited |
| Metadata details | EXIF-only overlay dialog (sephiroth exif + sanselan) | **Service boundary + field taxonomy + progressive levels + IPTC/XMP/C2PA/GeoTIFF/Panorama** | **Reimplement** Aves architecture; **Adapt** Aves exifinterface fork (Apache-2.0) as the native parser | toadGALLERY metadata service | 4 | audited |
| Format breadth | GIF/APNG/aWebP/AVIF/JXL/SVG via Glide decoders | Capability detection + taxonomy incl. RAW pairs, motion photos, stereo-3D | **Keep** Fossify decoders; **Reimplement** Aves capability/taxonomy layer | Glide pipeline + toadGALLERY format registry | 4 | audited |
| Stacking (burst/RAW pairs) | No | `stackedEntries` | **Defer** (Phase 4 evaluation, likely post-v1) | toadGALLERY | 4/5 | audited |
| Editing tools | Built-in: crop/rotate/resize/draw/filters | Transform editor + metadata editor (in-app) | **Adapt** Fossify editor into **separate APK** (toadGALLERY Edit) | EditActivity extraction per ADR-0002/0007 | 6 | audited |
| Metadata editing (date/rating/tags) | No | entry editor UI | **Defer** (natural Edit-app/Phase 4 extension) | toadGALLERY | 4+ | audited |
| Caching / thumbnails | Glide (+legacy Picasso paths) | Layered providers (thumb/full/region) + metadata cache w/ invalidation | **Keep** Fossify (Glide); **Adopt** Aves invalidation semantics for metadata cache | Glide + toadGALLERY | 3+4 | audited |
| Widgets | Yes (folder widget + job service) | No | **Defer** | — | post-v1 | audited |
| Wallpaper / print | Yes | Wallpaper only | **Defer** (wallpaper), **Exclude** print (not must-have) | — | post-v1 | audited |
| Slideshow | Yes | Yes | **Keep** Fossify | dialogs + fragments | 3+ | audited |
| Maps / geocoding | No | **Offline** vector maps + bundled geo | **Defer** (roadmap §6; revisit — Aves' offline maps reduce the usual privacy objection) | — | post-v1 | audited |
| DLNA / casting | No | Yes | **Exclude** (offline-first policy) | — | never | audited |
| Cloud / accounts / analytics | None in foss flavor (network perm removed) | None in libre flavor; `services_none` no-op pattern | **Exclude**; adopt Aves' no-op service pattern as architecture rule | toadGALLERY | 2+ | audited |
| Accessibility | Partial, untested | Partial + settings module (animations/timouts) | **Reimplement** baseline checks (Phase 7 gate) | toadGALLERY | 7 | audited |

## Conflicts resolved by this matrix

1. **Two trash models** → hybrid with a written semantic (Phase 7 deliverable).
2. **Two identity models** → URI-first everywhere (ADR-0006); Fossify's path-keyed cache
   is migrated, not kept.
3. **Two video stacks** → media3 only; mpv excluded (dependency report §4).
4. **Path-based file ops** → must run through SAF/content URIs (roadmap principle), keeping
   commons' SAF plumbing and dropping its raw-path fast paths.

## Non-negotiable product constraints (apply to every row)

1. Offline-first: no core feature may require network access; no network permission.
2. Originals protected: destructive operations explicit and reversible where the platform allows.
3. Content URIs everywhere: no raw filesystem-path dependencies in product code.
4. One product: consistent navigation, terminology, and design tokens across all screens.
