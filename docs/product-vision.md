# toadGALLERY --- Product Vision

**Status:** Authoritative north-star document. Supersedes any conflicting
statement in earlier planning artefacts.\
**Last revised:** 2026-10-29 (v2.2 --- features/UI/UX/designs only; Kotlin
+ Jetpack Compose; no Flutter; CI on GitHub Actions).

## 1. One-sentence statement

> toadGALLERY is a single, private, offline-first Android gallery that
> browses and organises like Mi Gallery, exposes metadata like Aves libre,
> edits like Mi Gallery's editor, and respects your data like Fossify ---
> built from scratch in Kotlin + Jetpack Compose, taking only features,
> UI/UX, and designs from upstreams (no code ports), with CI on GitHub
> Actions.

## 2. The four lineages and what each contributes

| Lineage | Role in toadGALLERY | What we take | What we deliberately leave |
|---|---|---|---|
| **Mi Gallery** (Xiaomi, proprietary) | UX and feature *reference* only --- no code, no assets, no trademark | Editor tool layout, Moments/Stories/Auto-albums mental model, smart-search affordances, collage and slideshow UX, video trim flow | Any proprietary code, branding, icons, cloud services, account system, ad framework, online ML |
| **Aves libre** (BSD-3-Clause) | UX and feature *reference* only --- no code ports | EXIF/IPTC/XMP read+write *patterns* (we reimplement using `androidx.exifinterface`), tags/keywords/ratings/color labels, collections, on-device map view, format taxonomy | Flutter/Dart UI code, the `exifinterface` fork (we use `androidx.exifinterface` directly), any network-dependent tile provider, any feature that requires a server round-trip |
| **Fossify Gallery** (GPL-3.0) | UX and feature *reference* only --- no code ports, no `org.fossify:commons` dependency | Privacy posture, offline-first behavior, the broad shape of a gallery (timeline/albums/viewer/file ops) | The `org.fossify:commons` library, any code skeleton, the inherited `fallbackToDestructiveMigration`, the path-keyed Room schema, the View-based UI obligation |
| **Jetpack Compose + Material 3** (Apache-2.0) | Canonical UI toolkit | Declarative UI, Material 3 + dynamic color, animation primitives, Compose Canvas for editor chrome | (none --- this is the chosen toolkit, not a source to selectively draw from) |

### 2.1 Toolkit stance (locked in by user directive, ADR-0008 + ADR-0009)

**The product is built in Kotlin + Jetpack Compose. Flutter is not used.
No code is ported from any upstream.**

This is not a default; it is an explicit decision driven by the user
directives "build on Kotlin and Jetpack Compose if modern, else follow
up Flutter but rethink" and "we are taking only features, ui/ux, designs
from all apps for toadGALLERY." ADR-0008 (no code ports, no
`org.fossify:commons`, no Aves `exifinterface` fork) and ADR-0009
(Compose, not Views; supersedes ADR-0005) are the authoritative records.

Practically:

- **The product source is pure Kotlin + Compose.** No Dart, no Flutter,
  no `org.fossify:commons`, no Aves code, no Fossify code skeleton.
- **All screens ship in Compose** --- the Phase 2 View-based shell is
  migrated incrementally per `docs/architecture/compose-migration.md`.
- **The only required build environment is JDK 17 + Android SDK**
  (platform 36, build-tools 36.x, AGP 9.2.1, Kotlin 2.3.10, Compose BOM
  latest stable). No Flutter SDK, no Dart SDK.
- **CI is GitHub Actions** (ADR-0013). The local-build requirement is
  dropped; a green CI check is required to merge any PR.

## 3. Product pillars

### 3.1 Browse and organise like Mi Gallery

The first screen the user opens must feel familiar to anyone who has used
Mi Gallery: a fast, dense, edge-to-edge timeline that groups media by
date, with quick jumps to Albums, Search, and a per-day "Moments" strip.
Auto-generated albums --- People, Places, Things --- are available but
strictly opt-in, because they require on-device ML indexing and the user
must consent before that scan ever runs. Stories (short auto-generated
highlight reels) and the collage maker live in the same organisational
layer and use only media already on the device.

This pillar is not about cloning Mi Gallery's look pixel-for-pixel. It is
about matching the *information density, gesture economy, and
discoverability* of Mi Gallery while keeping the toadGALLERY identity
distinct and free of proprietary assets. Where Mi Gallery uses cloud
compute for an auto-album, toadGALLERY uses an on-device model or simply
does not offer the album.

### 3.2 Expose metadata like Aves libre

Every media item has a Details view that goes deep: dimensions, file
size, MIME type, capture date (with timezone semantics stated
explicitly), camera make/model/lens, full exposure triangle, GPS
coordinates with an on-device map pin, and the complete EXIF/IPTC/XMP
block in a collapsible tree. The user can edit the most common fields
directly --- date/time, GPS, copyright, keywords/tags, rating, colour
label, comment --- and the write goes back to the file via
`androidx.exifinterface` (and our own IPTC/XMP wrappers), not to a
sidecar database. Aves's collections and smart-filter chips are
reimplemented in Compose to give the user Aves-grade query power without
ever leaving the toadGALLERY shell.

**No Aves code is ported** (ADR-0008). The `exifinterface` strategy is
to use `androidx.exifinterface` directly (Apache-2.0, AndroidX) and
write our own IPTC/XMP wrappers around it. Aves is the design reference;
Aves is not the code source.

Maps are on-device only. There is no online tile fetch in the core
product. If the user wants richer tiles they can sideload an offline
tile pack; that capability is documented but not bundled.

### 3.3 Edit like Mi Gallery's editor

The editor is a flagship, not a side feature. It is delivered as a
separate APK (toadGALLERY Edit) so it can ship, update, and be removed
independently, but it must feel like a native extension of the gallery:
one tap from any photo opens the editor with the source URI pre-loaded,
the result comes back as a copy (or, with explicit user choice and a
safe path, a replace-in-place), and the gallery refreshes immediately.

The editor ships in three layers, each a roadmap phase:

1. **Core adjustments** --- crop, rotate, straighten, perspective, and a
   full adjustment suite (brightness, contrast, saturation, exposure,
   temperature, tint, highlight, shadow, vignette, sharpness, per-channel
   HSL). Non-destructive pipeline (ADR-0011). Undo/redo. Export with
   quality and format control. Metadata preserve / strip / rewrite on
   export.
2. **Filters and annotations** --- LUT-based filter system with an
   intensity slider (no copyrighted LUTs bundled; user can import their
   own), stickers, text with font/colour/style/curve, freehand drawing
   and brush, mosaic and linear blur regions, frames and borders.
3. **On-device AI features** --- magic erase / object removal, sky
   replacement, bokeh / portrait blur, and (opt-in, with an explicit
   consent dialog) face retouch. Every model runs on-device. There is no
   cloud ML API call anywhere in the editor or the gallery, ever
   (ADR-0010).

The non-destructive pipeline stores edits as a sidecar JSON or XMP
record, never overwriting the original bytes until the user explicitly
exports. "Save copy" is the default; "replace original" is offered only
when the operation is reversible and the user has confirmed.

### 3.4 Respect your data like Fossify

This pillar is the invariant that constrains every other decision in the
project. Concretely:

- **No `INTERNET` permission in the core app manifest.** Optional features
  that genuinely need network (none are planned for v1) would live in a
  separate module with a separate permission and an explicit user
  toggle.
- **No accounts, no cloud sync, no telemetry, no analytics, no ads.**
- **No background indexing without explicit user opt-in.** On first
  launch the gallery indexes only enough to render the timeline; deeper
  indexes (people clustering, object tagging) require the user to turn
  them on.
- **On-device ML only.** If a feature cannot be done with a model that
  ships inside the APK and runs on the user's CPU/GPU/NPU, the feature
  does not ship.
- **Originals are sacred.** Destructive operations require an explicit
  confirmation; the recycle bin retains deleted items for a configurable
  window; the hidden vault is PIN/biometric-gated and stored in
  app-private storage.
- **FOSS end to end.** GPL-3.0 product (by voluntary choice per
  ADR-0004/0008, not by derivation obligation), with all third-party
  dependencies attributed per their terms. No proprietary dependencies.

## 4. What toadGALLERY is *not*

- Not a cloud gallery. There is no toadGALLERY account, no toadGALLERY
  server, no toadGALLERY backup. If the user wants backup they bring
  their own (Syncthing, rsync, a NAS) and toadGALLERY stays out of the
  way.
- Not a professional RAW developer. Lightroom-grade develop modules are
  out of scope. RAW *decoding* for browsing and basic adjustment is in
  scope; parametric RAW development is not.
- Not a social network. No likes, no follows, no public profiles, no
  feed. Sharing means "hand this URI to another local app", not "publish
  to a service".
- Not a video production suite. The video editor trims, mutes, applies
  slow-mo, extracts frames, and exports short GIF/WebP animations. It
  does not do multi-track editing, transitions, titles, or colour
  grading beyond what the photo adjustment pipeline already exposes.
- Not a fork of Mi Gallery, Fossify, or Aves. We use all three as
  *references for features, UI/UX, and design*; we do not decompile,
  redistribute, or imitate their branding, and we do not port their
  code.

## 5. Success criteria

toadGALLERY v1.0 succeeds when all of the following are true on a clean
mid-range Android device with no network connection:

1. A user opening the app for the first time sees their full timeline
   render within the performance targets defined in the roadmap, with no
   permission prompt beyond the documented media-access one.
2. The user can open any photo, enter the editor, perform a
   crop-plus-adjustment-plus-filter edit, export a copy, and return to
   the gallery with the new copy visible --- all without the device ever
   making a network request.
3. The user can view the EXIF/IPTC/XMP metadata of any supported file,
   edit the common fields, and write the changes back to the file
   in-place.
4. The user can opt into on-device people clustering, run it once over a
   10,000-item library, and get usable People albums --- with the
   clustering happening entirely on-device and the index stored only in
   app-private storage.
5. The user can use magic erase on a photo to remove an unwanted object,
   again with zero network traffic, and the result is a copy with the
   original preserved.
6. A privacy audit using a network proxy confirms that the device makes
   no outbound connections during any of the above flows.
7. The build is reproducible, the license/attribution package is
   complete, and F-Droid metadata is ready for submission.
8. The CI workflow on GitHub Actions is green at the v1.0 tag.

## 6. How this document drives the roadmap

The roadmap (`roadmap.md`) is the *how* and *when*. This document is the
*what* and *why*. When the roadmap and this document disagree, this
document wins --- and the roadmap gets revised, not the other way around.
