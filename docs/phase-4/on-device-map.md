# The on-device map view (r16 round)

**Status:** shipped slice --- the Phase 4 roadmap item "on-device map view
--- no online tile fetch; bundled minimal renderer; offline tile-pack
sideload documented clearly". This document is the round's contract record:
what is bundled, why there are ZERO network tiles ever, what the consent
semantics are, and what the documented (NOT implemented) tile-pack sideload
contract for a later round says.

---

## What shipped

| Piece | Where |
| --- | --- |
| Bundled world outline (asset) | `apps/gallery/src/main/assets/maps/world-110m.geojson` |
| Dependency-free GeoJSON reader + equirectangular projection | `data/geo/GeoJson.kt`, `data/geo/Equirectangular.kt` |
| Compose renderer (fixed whole-world viewport) | `ui/map/WorldMapCanvas.kt` |
| Grid-bucket clustering + hit-testing | `ui/map/MapClusterGrid.kt` |
| GPS location index pass (scan hook) | `data/media/location/LocationIndexer.kt` |
| Re-index-on-grant decision policy | `data/media/location/LocationConsentReindexPolicy.kt` |
| Room v3 -> v4 (latitude/longitude/locationSource columns) | `data/catalogue/room/*`, `schemas/.../4.json` |
| Map tab (nav destination, states, location sheet) | `ui/map/MapScreen.kt`, `ui/map/MapViewModel.kt`, `ui/map/MapContentPolicy.kt`, `ui/ToadGalleryApp.kt` |
| UI strings | `res/values/strings_r16.xml` |

## The bundled renderer

- **Cartography:** Natural Earth's **110m land polygon set**
  (`ne_110m_land`), converted to a GeoJSON `FeatureCollection`
  (CRS84 lon/lat). Natural Earth data is **public domain** (no
  attribution required; https://www.naturalearthdata.com/). The committed
  asset is ~135 KB: 127 polygon features, 5,091 exterior-ring positions.
- **Projection:** equirectangular (plate carree) --- longitude maps
  linearly to x, latitude linearly to y, whole-globe aspect 2:1. Chosen
  for v1 because the fixed whole-world viewport needs only constant-speed
  math and a clean letterbox; Mercator's pole inflation buys nothing at
  this zoom and sphere-accurate projections are a tile-pack-era concern.
  Normalizations (pinned by `EquirectangularTest`): latitude CLAMPS at
  the poles, longitude WRAPS modulo 360 (a lon of 190 is the same
  meridian as -170; clamping would pile such dots onto the antimeridian
  edge).
- **Viewport:** FIXED and whole-world in v1 --- no zoom, no pan. The 2:1
  map letterboxes into the canvas (portrait phones get a full-width band
  around the vertical center); the math lives in the pure
  `EquirectangularViewport` class.
- **Graticule:** a light 30-degree grid (meridians and parallels), drawn
  under the landmasses from theme color roles.
- **Theming:** ocean/land/coastline/graticule/dot colors all come from
  the Material `colorScheme` roles the toad palettes feed, so the map
  follows light/dark + dynamic color like every other surface. No
  hardcoded map pigments.
- **Dots:** one dot per grid-bucket cluster (24x12 cells, one per 15
  degrees square on the 2:1 map; at most 288 draw targets). Dot radius
  grows with the cluster's count; multi-photo clusters carry a count
  badge. Tapping a dot/cluster opens a bottom sheet listing that
  location's items in timeline order; tapping an item opens the existing
  viewer flow (the shell-scoped viewer window hand-off the grid screens
  use).
- **Interiors/holes:** the parser keeps only EXTERIOR rings (v1 scope
  cut): Natural Earth's holes are inland seas (the Caspian), invisible at
  whole-world zoom. The extension point when zoom lands is
  `GeoJsonParser.parseOutline` itself.

## ZERO network tiles, ever (ADR-0014)

The map **cannot fetch tiles**: the app holds no INTERNET permission
(enforced at build time by the merged-manifest privacy gate and proven at
runtime by `PrivacyEgressTest`), and the renderer contains no URL, no
network client and no caching layer --- the only "map data" is the bundled
asset's parsed polygons plus the catalogue's persisted coordinates. This
is a permanent privacy posture for the core gallery, not a v1 limitation:
even the future tile-pack sideload (below) stays offline by construction.

## The offline tile-pack sideload contract (documented, NOT implemented)

v1 does not implement tile loading of any kind. The documented contract a
later round implements against:

- A **tile pack** is a user-sideloaded, self-contained bundle (e.g. an
  MBTiles/SQLite or directory-of-PNG ZIP on external storage, selected
  through a system file picker) providing raster tiles for higher-detail
  zoom levels. It is OPTIONAL: without one, the map stays on the bundled
  world outline.
- Loading is **local file reads only** --- still no network, no INTERNET
  permission, no tile server. The ADR-0014 offline gate is unaffected.
- The pack must be validated at load (bounded cache size, no path escape,
  graceful rejection of corrupt packs) and its provenance/coverage shown
  in Settings; nothing auto-downloads, ever.
- Vector outlines (bundled or sideloaded) remain the fallback rendering
  path for any zoom level the pack does not cover.

This round implements exactly none of that; the renderer's fixed viewport
is the honest v1 answer until the pack contract lands.

## GPS persistence (Room v4) and the location index pass

- **Schema:** `catalogue_items` grows three columns --- `latitude` REAL,
  `longitude` REAL (signed decimal degrees), `locationSource` TEXT (the
  `ItemLocationSource` enum's name; `EXIF_GPS` or the special
  `MEDIA_LOCATION_UNGRANTED`). Migration v3 -> v4 is
  `ALTER TABLE ... ADD COLUMN` only (non-destructive, the v1 -> v2
  pattern); `4.json` is the exported schema snapshot, hand-written this
  round and carrying an identityHash recomputed with Room 2.8.5's own
  algorithm (`androidx/room/vo/SchemaIdentityKey.kt`), cross-validated by
  reproducing the committed v1/v2/v3 hashes with the same computation.
- **The index pass** (`LocationIndexer`, a `ScanIndexHook` beside the
  capture-date indexer) runs after each scan's added/changed rows are
  applied: for every eligible public row it reads the item's bytes ONCE
  through the `MediaReadHandle` boundary and hands them to the EXISTING
  EXIF assembly (`ParsedExif` -> `ExifMath`), persisting the validated
  DMS-to-decimal fix via a targeted partial UPDATE that can neither
  clobber concurrent writers nor resurrect a tombstoned row. The two
  indexers share one `MediaAccess` and one eligibility gate; a
  `CompositeScanIndexHook` fans the driver's single callback out to both
  with per-hook failure isolation. Sequencing note (the capture-date
  pattern, unchanged): the scan's upsert writes the row WITHOUT location
  fields (the catalogue record carries none this round), and the hook
  re-fills them immediately after the batch --- an upserted row's
  location is briefly null between the two, exactly like its capture
  date.
- **Null means honestly unknown** (the capture-date doctrine): ABSENT,
  UNREADABLE and UNSUPPORTED evidence each persist plain nulls --- the
  distinction stays at the JVM-tested seam, not in the row.

## Consent and redaction semantics (API 29+)

Verified against
https://developer.android.com/training/data-storage/shared/media: on
Android 10+ the platform **redacts EXIF location from other apps' media**
unless `ACCESS_MEDIA_LOCATION` is granted --- the tags simply come back
absent. The indexer therefore consults the r14 consent machinery (the
SAME `AndroidMediaLocationAccess` the Settings row and the Details gate
use --- reused, not forked) ONCE per scan batch:

- **Ungranted (API 29+):** no bytes are read at all. EXIF-capable rows
  persist `null`/`null` with the `MEDIA_LOCATION_UNGRANTED` provenance
  (the honest "withheld" state, and exactly the set a grant-triggered
  re-pass would target); non-EXIF-capable formats persist plain nulls
  (claiming "withheld" for a PNG would be a lie).
- **Below API 29:** the permission does not exist, the platform never
  redacted anything, and the pass behaves like any EXIF read.
- **Display side:** the Map tab's content state consults the r14 display
  gate (`MediaLocationDisplayRules`) --- consent off shows the consent
  state (pointing at Settings), never dots drawn from coordinates the
  user's consent choice withholds.

## Re-index behavior and its honest v1 limitation

The re-index-on-grant decision ladder (smallest honest branch, branch
(b)) is the pure `LocationConsentReindexPolicy`: given
below-29 / granted / pending-withheld-count it answers NOT_REQUIRED,
NO_GRANT, NOTHING_PENDING or REPASS_USEFUL. **v1 deliberately wires no
automatic re-pass** with REPASS_USEFUL: the scan pipeline's existing
seams cannot express "re-index rows whose CONTENT did not change" (on API
30+ an unchanged MediaStore means every volume SKIPs, and even a forced
manual pass feeds the hooks only reconciler-added/changed rows). Doing it
right needs a "re-index rows WHERE locationSource =
'MEDIA_LOCATION_UNGRANTED'" driver pass or the Phase 5 Places index's
own scheduler --- invasive pipeline surgery this round refuses to do
behind the driver's back.

The honest v1 semantics that ship instead:

- Items first scanned while ungranted resolve their location on their
  NEXT CONTENT CHANGE (any scan that observes the row as added/changed
  re-runs the location indexer on it, now granted).
- The map screen SURFACES the pending count (a footnote whenever
  withheld rows exist), so the limitation is visible, not hidden in a
  log line.
- A grant-triggered full re-pass lands with the Phase 5 Places index.

## Test surface (JVM, ADR-0016)

`GeoJsonParserTest` (inline fixtures + a real parse of the committed
asset through a plain `File` read), `EquirectangularTest` (projection
decisions, round-trip, both letterbox orientations),
`MapClusterGridTest` (deterministic bucketing, edge coercion,
hit-testing, the radius scale), `LocationIndexerTest` (the pure mapping
table + end-to-end hook behavior with fake rows/handles, the
`CaptureDateIndexerTest` pattern), `LocationConsentReindexPolicyTest`
(the decision table) and `MapContentPolicyTest` (the screen states). No
android.* in any of them; the Android-backed extraction
(`AndroidGpsCandidateSource`), the renderer and the ViewModel are thin
shells per the repo's seam split.
