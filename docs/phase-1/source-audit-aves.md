# Source Audit — Aves (1.15.8+182 @ `4f940ae`, `develop`)

> **⚠️ REFRAMED 2026-10-29 by ADR-0008.**
> This audit was originally produced as a "code inventory for porting"
> under the now-superseded ADR-0001 (which planned to port the Aves
> `exifinterface` Apache-2.0 fork). Under ADR-0008 (features/UI/UX/
> designs only --- no code ports), the audit is reinterpreted as a
> **feature/UI/UX inventory for reimplementation inspiration**. The
> factual content (what Aves does, how it does it) is unchanged and
> remains useful as a reference. The actionable consequence has
> changed: we no longer port any Aves code (including the
> `exifinterface` fork); we use `androidx.exifinterface` directly
> (Apache-2.0, AndroidX) and write our own IPTC/XMP wrappers.

**Auditor:** project lead bootstrap · **Date:** 2026-10-29 · **Method:** direct source reading of the
pinned baseline. Companion: `source-audit-fossify.md`. Aves is consulted here as an
**evidence source and design reference** for what toadGALLERY will **reimplement in
Kotlin** (per ADR-0008) --- Dart code is not portable, but its *models* are.

## 1. Stack and module shape

Flutter 3.47.8 (Dart ≥3.13.7) app with **13 in-repo workspace plugins**
(`aves_model`, `aves_ui`, `aves_services`, `aves_video`, `aves_video_mpv`, `aves_map`,
`aves_magnifier`, `aves_utils`, `aves_report` + crashlytics/google variants) and an
`android/` Gradle project containing the app module plus a **forked
`androidx.exifinterface` module** (`ExifInterfaceFork`, Apache-2.0, with extended
capabilities). Flavor system: **libre / izzy / play** — main entry points
`main_libre.dart`, `main_play.dart`, etc.

## 2. Media access & data model (the crown jewel)

`AvesEntry` (`lib/model/entry/entry.dart`) is **URI-first**:

- identity: **`uri` + `contentId`** (MediaStore ID), `pageId` for multi-page docs;
  `path`/`fileName` are derived conveniences, not keys — the exact inversion of
  Fossify's model and the shape toadGALLERY adopts (ADR-0006).
- **`trashed` flag comes from the platform MediaStore trash state** (+ `TrashDetails`
  lazily fetched) — trash is delegated to the OS where supported, the opposite of
  Fossify's app-level bin. Phase 7 must reconcile these two philosophies.
- Dates: `dateAddedSecs`, `dateModifiedMillis`, `sourceDateTakenMillis`, plus a
  **catalog date with timezone offset** (`catalogDateMillis`,
  `catalogTimeZoneOffsetMinutes`) — Aves models timezone-correct capture dates.
  Roadmap Phase 5 requires defining "capture date vs file date" semantics: adopt this
  three-date model (added/modified/date-taken-with-tz).
- `origin`, **`stackedEntries`** (burst shots / RAW+developed pairs get grouped into a
  single stack) — a UX differentiator worth a Phase 4 decision.
- Progressive enrichment enum `EntryDataType { basic, aspectRatio, catalog, address,
  references }` — metadata loads by level, off the critical path. This is the pattern
  the roadmap's Phase 4 "metadata service with clear boundary" asks for.

## 3. Filter algebra (the Phase 5 blueprint)

`lib/model/filters/` implements a **composable filter AST**, not a search screen:

- Leaf filters: `mime`, `date`, `weekday`, `rating`, `coordinate`, `path`, `type`,
  `favourite`, `trash`, `recent`, `missing`, `query` (text), aspect-ratio, credentials.
- Containers: `SetAnd`, `SetOr`, `AlbumGroup`, `TagGroup`, `DynamicAlbum` — arbitrary
  nesting; a saved "dynamic album" is a serialized filter subtree.
- UI side: filter chips (`expandable_filter_row`, `filter_bar`) render the AST's leaves;
  collections are `CollectionLens(source, filters)` (`model/source/`).
- **Reimplement in Kotlin** as a filter model over the Room/ContentResolver cache
  (ADR-0001; matrix row "search" = Reimplement). Fossify's name-contains search is
  strictly weaker; Aves' tree is the target.

## 4. Metadata service (the Phase 4 blueprint)

`lib/services/metadata/metadata_fetch_service.dart` defines an abstract service with a
**platform-channel boundary** to the native exifinterface fork:

- `getAllMetadata` → map of directories → label/value rows (EXIF + directories taxonomy)
- `getCatalogMetadata` (camera/lens/exposure, `{background: true}` lazy mode),
  `getOverlayMetadata` (on-image overlay fields), `getXmp`, `getIptc`, `getContentCredentials`,
  `getGeoTiffInfo`, `getPanoramaInfo`, `getMultiPageInfo`, `getFields(fieldSet)` (single-field fetch).
- Field taxonomy is a **first-class model**: `ref/metadata/exif.dart`, `iptc.dart`,
  `xmp.dart` enumerate fields with labels/descriptions — "distinguish unavailable from
  genuinely absent" (roadmap Phase 4) is solvable with exactly this kind of catalog.
- The native side (Apache-2.0 fork) is the one piece of Aves code that can be **adapted
  as code** into our Kotlin app with attribution (see license compatibility report).
- Dates: `MetadataDateField` model distinguishes date sources — again, adopt.

## 5. Formats

`ref/mime_types.dart` registry; video profile models for h264/hevc/aac
(bit-depth/channel layouts), stereo-3D modes, panorama (XMP GPano), GeoTIFF, multipage
(TIFF/HEIF stacks), SVG, motion photos (embedded video via `embedded_data_service`),
RAW with paired-JPEG handling. Aves does **capability detection** and says what a
device/library can decode, rather than promising format support — matching the roadmap's
"state actual device/library limitations" requirement. Phase 4 will port the *taxonomy*
and device-capability checks, not the decoders.

## 6. Viewer interactions

`aves_magnifier` (custom zoom/pan), `view_state`, viewer transition styles, playback via
**`aves_video_mpv`** (libmpv; `aves_video_exo` exists but disabled at HEAD), video track
selection, playback speed dialog, slideshow with ken-burns options. For toadGALLERY we
keep Fossify's media3 viewer (matrix decision) and treat Aves' viewer polish as a
benchmark, not code.

## 7. Caching

- Thumbnail pipeline: custom Flutter providers (`thumbnail_provider`,
  `full_image_provider`, `region_provider`, `descriptor_provider`) — a clean layering of
  grid-thumb / full-decode / tiled-region decode.
- Metadata caching per entry (`model/entry/cache.dart`) keyed by content identity with
  invalidation on content change — mirror this for the Kotlin metadata service cache
  (roadmap Phase 4: "cache results only where justified").
- Analysis service: histogram/stats collection is a background job with progress —
  the model for Phase 5/8 index work.

## 8. Privacy architecture (directly relevant to our offline-first policy)

- Flavor system **swaps service implementations** at build time: libre uses
  `aves_services_none` (no-op platform services) and `aves_report_console` instead of
  Google-services/crashlytics — the codebase ships explicit **no-op implementations**
  for anything network-flavored. toadGALLERY adopts this pattern natively (ADR-0001:
  no network layer in the product at all).
- Maps are **offline-first already**: vector tiles + Leaflet-style rendering
  (`lib/widgets/common/map/leaflet/`), bundled country/state geometry (`lib/geo/`) —
  no network dependency for basic map display. (Maps stay deferred per our scope.)
- DLNA casting, connectivity checks, online geocoding exist behind services — all
  **excluded** from toadGALLERY by the Phase 0 scope decision.

## 9. Editor

`lib/widgets/editor/`: `TransformController` (crop region, rotate90/180/270, flipH/V,
straighten with matrix math), cropper UI, plus `entry_editor_page` (metadata editing:
date/description/rating/location/tags). So Aves **does** include a built-in transform
editor + rich metadata editor — correcting the earlier draft matrix ("Aves has no
editor"). Impact on us: none for the editor app (toadGALLERY Edit is a separate APK
with Fossify-derived tools per ADR-0002), but the **metadata editor UI** is a strong
reference for Phase 4 metadata presentation/editing.

## 10. Audit conclusions for integration

1. Aves contributes **models, not modules**: URI-first entry, three-date semantics,
   progressive metadata levels, filter algebra, format taxonomy, offline-service
   pattern. All are **reimplementation targets in Kotlin** (ADR-0001 confirmed by evidence).
2. The **exifinterface fork is the single code-adapt candidate** (Apache-2.0, native,
   already shaped for gallery EXIF needs).
3. Aves' own trash delegation vs Fossify's app bin is the biggest semantic conflict —
   resolve in Phase 7 with a written decision.
4. Nothing found that blocks the integration approach; several things found that
   **raise the bar** on what "one coherent product" means (stacking, date semantics,
   field taxonomy). Budget them in Phases 4–5.
