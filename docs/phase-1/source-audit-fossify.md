# Source Audit — Fossify Gallery (v1.14.1 @ `e9812299`)

> **⚠️ REFRAMED 2026-10-29 by ADR-0008.**
> This audit was originally produced as a "code inventory for porting"
> under the now-superseded ADR-0001. Under ADR-0008 (features/UI/UX/
> designs only --- no code ports), the audit is reinterpreted as a
> **feature/UI/UX inventory for reimplementation inspiration**. The
> factual content (what Fossify does, how it does it) is unchanged and
> remains useful as a reference. The actionable consequence has
> changed: we no longer "Keep" or "Adapt" Fossify code; we
> **Reimplement** informed by what this audit documents. No
> `org.fossify:commons` dependency. No skeleton reuse.

**Auditor:** project lead bootstrap · **Date:** 2026-10-29 · **Method:** direct source reading of the
pinned baseline (no assumptions from documentation). Companion: `source-audit-aves.md`.
This audit feeds the integration matrix and the ADR updates; it is evidence, not opinion.

## 1. Stack and module shape

Single Gradle module `:app` (~105 Kotlin files) + `org.fossify:commons:7.1.3` (separate
library, GPL-3.0, provides base activities, helpers, style system). ViewBinding UI (no
Compose). The app is small enough to be fully readable — a major argument for choosing it
as the product's seed (ADR-0001).

## 2. Media access layer

- Core: `helpers/MediaFetcher.kt` (916 lines) + `asynctasks/GetMediaAsynctask.kt`.
- Source of truth: **`MediaStore.Files` external** URI with type filters; folder discovery
  via `getFoldersToScan()` (MediaStore `DATA` projection + DCIM/Pictures seeds +
  `.nomedia` handling + included/excluded/hidden folder rules from `Config`).
- **Path-based identity throughout**: `Medium` is keyed by `full_path`; favorites,
  date-takens, and sorting are path-keyed. `media_store_id` was only added to the schema
  in migration 9→10 — upstream itself is mid-transition to MediaStore identity.
- Legacy paths persist deliberately: OTG (USB) support, `requestLegacyExternalStorage`,
  a dedicated **Android 11 hole workaround** (`getAndroid11FolderMedia` used when the user
  hasn't granted all-files access), and `MANAGE_EXTERNAL_STORAGE` prompts
  (`GrantAllFilesDialog`, `AllFilesPermissionDialog`, `StoragePermissionRequiredDialog`).
- Result for toadGALLERY: the fetch layer works but its path assumptions conflict with the
  roadmap's content-URI boundary (roadmap §2 principle, Phase 3 tasks). Reuse the
  MediaStore query patterns; replace the path-keyed identity (ADR-0006).

## 3. Persistence (Room, `gallery.db` v10)

- Entities: `Directory`, `Medium`, `Widget`, `DateTaken`, `Favorite`; DAOs in `interfaces/`.
- `Medium`: name, full_path, parent_path, last_modified, date_taken, size, type
  (images/videos/gifs/raws/svgs/portraits bitmask), video_duration, is_favorite,
  **deleted_ts** (trash marker), media_store_id.
- **Trash = app-level**: `RECYCLE_BIN` is a virtual folder; deleted items carry
  `deleted_ts != 0` in the DB (config: `use_recycle_bin`, `last_bin_check`, retention
  checks on boot via `BootCompletedReceiver`). This predates the platform
  `MediaStore` trash API — toadGALLERY must reconcile the two (roadmap Phase 7).
- `DateTaken` table caches corrected EXIF dates (with `last_fixed` bookkeeping) — an
  upstream answer to capture-date drift; concept worth keeping.
- ⚠ **`fallbackToDestructiveMigration()` is enabled.** A future schema mistake wipes
  favorites/trash records. toadGALLERY must forbid destructive fallback once user data
  matters (risk register R-05 addition, Phase 8 migration tests).

## 4. UI/navigation

18 activities (Splash, Main, Media, Search, ViewPager, Settings, VideoPlayer,
Photo/Video/PhotoVideo, Edit, BaseCrop, SetWallpaper, Included/Excluded/HiddenFolders,
WidgetConfigure + commons' About/Customization). Grid browsing via `MediaAdapter` /
`DirectoryAdapter` with sectioning (`ThumbnailSection`), styling dialogs, and a
20-way launcher-alias icon theming system. Exports PICK/GET_CONTENT (acts as a system
picker), VIEW for image/video (incl. `REVIEW` camera intents), and `ACTION_EDIT` +
`com.android.camera.action.CROP` for third-party editor integration — the existing
upstream "editor contract" that toadGALLERY's own Edit app will formalize (ADR-0007).

## 5. Viewer

`ViewPagerActivity` → `ViewPagerFragment` → `PhotoFragment` / `VideoFragment` (+`PlaybackSpeedFragment`).
- Images: `subsampling-scale-image-view` (Fossify fork) for large-image tiling, gestureviews
  for zoom/pan, Glide for standard decode (`MyGlideImageDecoder`, `Glide.kt`), custom
  `SvgModule` for SVG, Picasso paths for region decode (`PicassoRegionDecoder`).
- Video: **androidx.media3 ExoPlayer** (`Player.kt` extensions, `VideoGestureHelper`,
  brightness/volume side-scroll `MediaSideScroll`), playback-speed UI.
- Extended details overlay from EXIF (see §6). Third-party editor launching from viewer.

## 6. Metadata

- Libraries: `it.sephiroth.android.exif` + Apache `sanselan` (+ `IsoTypeReader` for MP4
  rotation). `ExifUtils.kt` maps EXIF to the extended-details dialog.
- Manifest requires **`ACCESS_MEDIA_LOCATION`** — mandatory for real GPS EXIF on Android 10+.
- Scope: presentation-level EXIF only — no catalog model, no XMP/IPTC, no field
  taxonomy. This is the capability gap Aves fills best (see the Aves audit).

## 7. Search

`SearchActivity.getAllMedia()` loads **all media, then filters by name** (text contains).
No index, no query composition, no date/type/album filters. Confirms the Phase 5 plan:
reimplement Aves-style composable filters over the MediaStore cache (ADR-0001 evidence).

## 8. Editor (source of the future toadGALLERY Edit app)

`EditActivity` + `BaseCropActivity` (+ new-photo/video edit layouts), `EditorDrawCanvas`
(drawing/annotation), `FiltersAdapter`/`FilterThumbnailsManager` (androidphotofilters),
vanniktech `android-image-cropper` 4.7.0, resize dialogs, save-as flow, `RotateTransformation`
for Glide. Everything is in-app today; the extraction into a separate APK with a versioned
contract is a toadGALLERY decision (ADR-0002/0007), not an upstream one.

## 9. Permissions posture (verified from manifest)

`READ_MEDIA_IMAGES`/`VIDEO`/`VISUAL_USER_SELECTED` (partial access handled),
`ACCESS_MEDIA_LOCATION`, `SET_WALLPAPER`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`,
legacy `WRITE_EXTERNAL_STORAGE` (maxSdk 40), `MANAGE_EXTERNAL_STORAGE`, `MANAGE_MEDIA`.
Upstream **actively removes** `ACCESS_NETWORK_STATE` and `CAMERA` via `tools:node=remove`
— same privacy instinct as toadGALLERY. No INTERNET permission anywhere. Good.

## 10. Widgets & background

`MyWidgetProvider` + `WidgetConfigureActivity` (folder widget, `Widget` DB entity,
`NewPhotoFetcher` JobService to catch new photos). Deferred by our scope decision (§6 of
the roadmap); the JobService pattern is reusable for trash-retention checks.

## 11. Audit conclusions for integration

1. **Keep as product skeleton** (ADR-0001 evidence): coherent, readable, actively
   maintained, license-clean (GPL-3.0), already verified to build in our toolchain.
2. **Port forward, not sideways**: the path-keyed identity, Android-11 workarounds, and
   destructive migration fallback must not leak into toadGALLERY (ADR-0006).
3. **commons coupling**: activities extend commons' `SimpleActivity`; theming, dialogs,
   and file-operation plumbing come from the commons library. Acceptable (GPL-3.0,
   same lineage) and keeps maintenance cost low — keep the dependency.
4. Editor extraction (Phase 6) has a clean seam: editor code is localized in a handful of
   classes + libraries.
