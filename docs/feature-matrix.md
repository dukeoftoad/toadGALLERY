# toadGALLERY --- Feature Matrix (v2.2)

**Status:** Revised 2026-10-29 to align with the v2.2 roadmap, the
`product-vision.md` north star, and ADR-0008 (features/UI/UX/designs
only --- no code ports).\
**Purpose:** For every capability, name its origin, its implementation
path, the phase that delivers it, and its license impact. This is the
single source of truth for "where does feature X come from and when does
it ship?"

## Legend

- **Origin:** where the implementation or design reference comes from.
  - `MI-REF` --- Mi Gallery is the UX/feature reference; we reimplement
    natively in Compose. **No Mi Gallery code is ever used.**
  - `FOSSIFY-REF` --- Fossify Gallery is the UX/feature reference; we
    reimplement natively in Compose. **No Fossify code is ported, no
    `org.fossify:commons` dependency.**
  - `AVES-REF` --- Aves libre is the UX/feature reference; we reimplement
    natively in Compose. **No Aves code is ported, no `exifinterface`
    fork.**
  - `NEW` --- net-new implementation, no upstream source.
  - `3P-LIB` --- third-party library (license recorded per row).
- **Path:** always Reimplement or New (no Keep, no Adapt, no Port ---
    those dispositions are eliminated by ADR-0008).
- **Phase:** the roadmap phase that first delivers this feature.
- **License impact:** notes on attribution, copyleft spread, or model
  licensing that must be honoured.

## 1. Browsing and viewer

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| Timeline / grid browse | FOSSIFY-REF + MI-REF | 3 | GPL-3.0 (our code) |
| Albums / folders | FOSSIFY-REF + MI-REF | 3 | GPL-3.0 |
| Image viewer (pan/zoom) | FOSSIFY-REF + AVES-REF | 3 | GPL-3.0 |
| Video playback | 3P-LIB (androidx.media3, Apache-2.0) | 3 | Apache-2.0 |
| Favorites | FOSSIFY-REF | 3 | GPL-3.0 |
| Sorting | FOSSIFY-REF | 3 | GPL-3.0 |
| Multi-select | MI-REF | 3 | GPL-3.0 |
| Share-to-app intents | FOSSIFY-REF | 3 | GPL-3.0 |
| HEIC/HEIF decode | 3P-LIB (system + image loader) | 3 | Apache-2.0 / system |
| AVIF decode | 3P-LIB | 4 | TBD by Phase 1 audit |
| RAW decode (browse only) | 3P-LIB (libraw or CameraX) | 4 | TBD by Phase 1 audit |
| Animated GIF/WebP | 3P-LIB | 3 | Apache-2.0 |

## 2. File operations and safety nets

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| Copy / move | FOSSIFY-REF + NEW | 3 | GPL-3.0 |
| Delete (to recycle bin) | NEW | 3 | GPL-3.0 |
| Recycle bin (30-day retention) | FOSSIFY-REF + NEW | 3 | GPL-3.0 |
| Restore from recycle bin | NEW | 3 | GPL-3.0 |
| Hidden vault (PIN/biometric) | NEW | 3 | GPL-3.0 |
| Vault at-rest encryption | NEW (Android Keystore + AES-GCM) | 3 | GPL-3.0 |
| Limited photo/video access | FOSSIFY-REF | 3 | GPL-3.0 |
| Removable storage | FOSSIFY-REF | 3 | GPL-3.0 |
| Revoked-access handling | FOSSIFY-REF | 3 | GPL-3.0 |

## 3. Metadata layer (Aves-grade, no Aves code)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| EXIF read | 3P-LIB (`androidx.exifinterface`, Apache-2.0) | 4 | Apache-2.0 |
| EXIF write | 3P-LIB (`androidx.exifinterface`) | 4 | Apache-2.0 |
| IPTC read+write | NEW (own wrapper) | 4 | GPL-3.0 |
| XMP read+write | NEW (own wrapper) | 4 | GPL-3.0 |
| Details view (structured) | AVES-REF | 4 | GPL-3.0 |
| Editable date/time, GPS, copyright | AVES-REF + NEW | 4 | GPL-3.0 |
| Tags / keywords | AVES-REF | 4 | GPL-3.0 |
| Rating | AVES-REF | 4 | GPL-3.0 |
| Colour label | AVES-REF | 4 | GPL-3.0 |
| Comment | AVES-REF | 4 | GPL-3.0 |
| Collections | AVES-REF | 4 | GPL-3.0 |
| Smart-filter chips | AVES-REF | 4 | GPL-3.0 |
| On-device map view (no online tiles) | AVES-REF + NEW | 4 | GPL-3.0 |
| Offline tile pack sideload | NEW | 4 | GPL-3.0 (tiles are user data) |

## 4. Search and organisation (Mi Gallery-grade)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| Moments timeline | MI-REF | 5 | GPL-3.0 |
| Stories (auto highlight reels) | MI-REF | 5 | GPL-3.0 |
| Auto-album: People (opt-in) | MI-REF + 3P-LIB (on-device face cluster) | 5 | Model license TBD by ADR-0010 |
| Auto-album: Places | NEW (from Phase 4 GPS) | 5 | GPL-3.0 |
| Auto-album: Things (opt-in) | MI-REF + 3P-LIB (on-device object/scene tagger) | 5 | Model license TBD by ADR-0010 |
| Smart search (filter composition) | AVES-REF + MI-REF | 5 | GPL-3.0 |
| Semantic search (content) | MI-REF + 3P-LIB (on-device embedding) | 5 | Model license TBD by ADR-0010 |
| Collage maker | MI-REF | 5 | GPL-3.0 |
| Slideshow with transitions | MI-REF | 5 | GPL-3.0 |

## 5. Editor --- core adjustments (Phase 6)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| Editor APK scaffolding | NEW | 6 | GPL-3.0 |
| Gallery-to-Edit URI contract | NEW (ADR-0007) | 1, 6 | GPL-3.0 |
| Non-destructive pipeline (sidecar JSON or XMP) | NEW (ADR-0011) | 6 | GPL-3.0 |
| Crop / rotate / flip / straighten / perspective | MI-REF | 6 | GPL-3.0 |
| Brightness, Contrast, Saturation, Exposure | MI-REF | 6 | GPL-3.0 |
| Temperature, Tint | MI-REF | 6 | GPL-3.0 |
| Highlight, Shadow | MI-REF | 6 | GPL-3.0 |
| Vignette, Sharpness | MI-REF | 6 | GPL-3.0 |
| Per-channel HSL | MI-REF | 6 | GPL-3.0 |
| Undo / redo stack | NEW | 6 | GPL-3.0 |
| Export (JPEG quality, PNG, HEIC) | NEW | 6 | GPL-3.0 |
| Metadata preserve / strip / rewrite on export | NEW (uses `androidx.exifinterface`) | 6 | Apache-2.0 / GPL-3.0 |
| Save copy (default) vs. replace original | NEW | 6 | GPL-3.0 |

## 6. Editor --- filters and annotations (Phase 7)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| LUT-based filters with intensity | MI-REF | 7 | GPL-3.0 |
| Bundled LUTs | NEW (CC0 / project-authored) | 7 | CC0 |
| User LUT import (`.cube`) | NEW | 7 | GPL-3.0 |
| Text tool (font/colour/style/curve) | MI-REF | 7 | GPL-3.0 |
| Bundled fonts | 3P-LIB (OFL-licensed) | 7 | OFL-1.1 |
| Stickers | MI-REF | 7 | GPL-3.0 |
| Bundled stickers | NEW (CC0 / project-authored) | 7 | CC0 |
| User sticker import (PNG/WebP) | NEW | 7 | GPL-3.0 |
| Freehand drawing / brush | MI-REF | 7 | GPL-3.0 |
| Mosaic / linear blur regions | MI-REF | 7 | GPL-3.0 |
| Frames / borders | MI-REF | 7 | GPL-3.0 |
| Bundled frames | NEW (CC0 / project-authored) | 7 | CC0 |

## 7. Editor --- on-device AI features (Phase 8)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| On-device inference runtime (NNAPI / GPU delegate) | 3P-LIB (TBD by ADR-0010) | 8 | TBD by ADR-0010 |
| Magic erase / object removal | MI-REF + 3P-LIB (on-device segmentation + inpainting) | 8 | Model license TBD by ADR-0010 |
| Sky replacement | MI-REF + 3P-LIB (on-device sky segmentation) | 8 | Model license TBD by ADR-0010 |
| Bokeh / portrait blur | MI-REF + 3P-LIB (on-device depth estimation) | 8 | Model license TBD by ADR-0010 |
| Face retouch (opt-in, consent dialog) | MI-REF + 3P-LIB (on-device face model) | 8 | Model license TBD by ADR-0010 |
| Bundled replacement skies | NEW (CC0 / project-authored) | 8 | CC0 |

## 8. Video editor (Phase 9)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| Trim | MI-REF | 9 | GPL-3.0 (media3 Apache-2.0) |
| Mute | MI-REF | 9 | GPL-3.0 (media3 Apache-2.0) |
| Slow-mo | MI-REF | 9 | GPL-3.0 (media3 Apache-2.0) |
| Frame extraction | MI-REF | 9 | GPL-3.0 (media3 Apache-2.0) |
| GIF / WebP export | MI-REF | 9 | GPL-3.0 |
| Light colour adjust on video | NEW (reuses Phase 6 pipeline) | 9 | GPL-3.0 |

## 9. Privacy and security (cross-phase)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| No `INTERNET` permission in core manifest | NEW | 2 | GPL-3.0 |
| Privacy test suite (network proxy asserts zero egress) | NEW | 2 onward | GPL-3.0 |
| No analytics / telemetry / crash-reporting SDKs | FOSSIFY-REF | 2 | GPL-3.0 |
| On-device ML only (no cloud ML) | NEW (ADR-0010) | 5, 8 | TBD |
| Opt-in auto-albums (People, Things) | NEW | 5 | GPL-3.0 |
| Opt-in face retouch with consent dialog | NEW | 8 | GPL-3.0 |
| Recycle bin retention | NEW | 3 | GPL-3.0 |
| Hidden vault PIN/biometric | NEW (Android Keystore + BiometricPrompt) | 3 | GPL-3.0 |
| Vault at-rest encryption | NEW (AES-GCM) | 3 | GPL-3.0 |
| Originals-protected-by-default | NEW (non-destructive pipeline, ADR-0011) | 6 onward | GPL-3.0 |

## 10. Release engineering (Phase 11)

| Capability | Origin | Phase | License impact |
|---|---|---|---|
| GitHub Actions CI workflow | NEW (ADR-0013) | 2 onward | GPL-3.0 |
| Reproducible build | NEW | 11 | GPL-3.0 |
| F-Droid metadata | NEW | 11 | GPL-3.0 |
| License / attribution package | NEW | 11 | Aggregates all of the above |
| Third-party FOSS review | NEW | 11 | n/a |
| Release signing | NEW | 11 | n/a |

## Open audit questions for Phase 2

These are the questions the Phase 2 implementation must answer before
the matrix above can be promoted from PROPOSED to ACCEPTED:

1. Confirm `androidx.exifinterface` has the EXIF write support we need
   (it does for EXIF; IPTC/XMP wrappers are net-new code regardless).
   Under ADR-0008 there is no Aves `exifinterface` fork port --- we use
   AndroidX directly.
2. Which on-device ML models are available under licenses compatible
   with a GPL-3.0 product? Candidates to evaluate: MediaPipe models
   (Apache-2.0), ONNX model zoo (mixed), TensorFlow Hub (mixed). The
   choice drives ADR-0010 finalisation.
3. Is there a clean way to render an on-device map without bundling a
   heavy tile set? Evaluate osmdroid with offline tiles, vs. a minimal
   bespoke renderer that only draws coordinate pins on a blank canvas.
4. Decide whether to keep Glide for image loading or migrate to Coil
   (Compose-first). Initial lean: Coil, because its API is
   Compose-native (`AsyncImage` composable).
5. Confirm Kotlin 2.0+ Compose compiler and Compose BOM version
   compatibility with AGP 9.2.1 and minSdk 26.
6. Decide whether the editor's high-performance preview surface (crop
   handles, brush strokes, mosaic regions) uses Compose Canvas or a
   custom `SurfaceView` wrapped via `AndroidView`. Initial lean:
   Compose Canvas for editor chrome; custom `SurfaceView` for the
   high-throughput image preview, wrapped via `AndroidView`.
