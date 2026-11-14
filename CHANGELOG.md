# Changelog

All notable changes to **toadGALLERY Edit** — the editor-only codebase — are
documented here in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
format, newest first. This branch was isolated from the original toadGALLERY
project, which shipped a photo gallery app plus this companion editor in one
repository. The history below is distilled from the pre-isolation project log
(the gallery-era `CHANGELOG.md`, the round-by-round worklog, and the phase
release notes); gallery-only entries (timeline, vault, maps, search, AI
indexing, release engineering) are summarized away or omitted. The surviving
Gradle modules are `:editor:app`, `:editor:core`, `:shared:editor-contract`,
and `:shared:video-edit`; the editor anchors at versionCode 19,
versionName `0.4.14-edit-alpha`.

The editor's own version line (the editor versions independently of any
other app):

| versionCode | versionName | Milestone |
|---|---|---|
| 1 | `0.1.0-edit-alpha` | Editor prototype over the versioned gallery-to-editor contract |
| 2 | `0.2.0-edit-alpha` | Edit v1 core: non-destructive recipe pipeline, full adjustment suite |
| 3 | `0.3.0-edit-alpha` | Edit v1 exit: per-export metadata policy, export preflight |
| 4 | `0.3.1-edit-alpha` | Accessibility pass, tested crop inverse |
| 6 | `0.4.1-edit-alpha` | LUT `.cube` filter system plus user look import |
| 7 | `0.4.2-edit-alpha` | Text tool (four bundled OFL fonts) |
| 8 | `0.4.3-edit-alpha` | Stickers (authored vectors plus PNG/WebP import) |
| 9 | `0.4.4-edit-alpha` | Freehand drawing |
| 10 | `0.4.5-edit-alpha` | Mosaic/blur regions |
| 11 | `0.4.6-edit-alpha` | Frames plus the undo/redo capstone |
| 13 | `0.4.8-edit-alpha` | Edit v3 on-device AI (erase, sky, bokeh, retouch) |
| 14 | `0.4.9-edit-alpha` | Lightweight video editor |
| 15 | `0.4.10-edit-alpha` | First signed release artifact |
| 16 | `0.4.11-edit-alpha` | Contract-discovery hardening |
| 17 | `0.4.12-edit-alpha` | Video editor redesign |
| 18 | `0.4.13-edit-alpha` | Photo editor design-language round |
| 19 | `0.4.14-edit-alpha` | Photo editor re-chassis plus UI craft (current) |

## [Unreleased] — editor-only isolation

### Removed

- The gallery app, its unified flavor, and the engine (vendored Rust ML)
  trees are removed from this branch. Only `:editor:app`, `:editor:core`,
  `:shared:editor-contract`, and `:shared:video-edit` remain; the editor
  builds as a standalone installable app (`io.github.dukeoftoad.gallery.edit`).
- The four ONNX AI models — u2netp erase segmentation, MI-GAN inpaint,
  Depth-Anything-V2-Small-int8 depth, YuNet face localization (61,613,133
  bytes total) — are no longer bundled in the APK or the repository.

### Added

- On-demand AI model downloads. The models fetch at runtime from the pinned
  public GitHub release
  `https://github.com/dukeoftoad/toadGALLERY/releases/download/onnx-models-v1`.
  `EditAiManifest.kt` pins each model's file name, byte size, and SHA-256
  digest (plus the whole-set byte budget); `EditAiModelStore.kt` downloads
  into app-private storage, verifies the SHA-256 after every download, and
  re-checks presence and size before every inference. Every AI commit first
  asks for one explicit user consent per batch before anything downloads; a
  failed or cancelled download leaves no partial file behind, and a missing
  model degrades that one feature — never a crash.

### Changed

- `INTERNET` is now the app's single permission, existing only for the
  on-demand model downloads. While the models were bundled, the merged
  manifest hard-stripped `INTERNET` (`tools:node="remove"`) and the build
  gate failed on any permission; the gate now allows exactly this one.
- All project documentation consolidated into README, CHANGELOG,
  ARCHITECTURE, DEVELOPMENT, and ROADMAP.

## [0.4.14-edit-alpha] — versionCode 19

The photo editor's tab-screen chassis and the craft line that followed.
Several editor-surface craft rounds were originally shipped through the
unified gallery APK (unified versionCodes 44–46, `0.4.5-alpha`); the
editor's own version line stayed at versionCode 19.

### Added

- **Ten-tool bottom tab bar**: the long-scroll sectioned page becomes a
  horizontally scrollable tab bar — Adjust, Color, Looks, Crop, AI, Text,
  Stickers, Draw, Regions, Frames — with per-tool screens (back/title/check
  header), a session-Reset footer on the recipe-field tools, system-back
  popping to the main chassis, and AI progress hoisted so a running job
  stays visible and cancellable from every screen. Paint modes
  (Draw/Region/Erase) auto-off on tool exit.
- **Canvas zoom**: pinch to scale between 1x (fit) and 4x, two-finger
  pan while zoomed, double-tap-to-fit back to the identity pose. The
  transform math is pure and JVM-tested, replayed through a pose layer so
  the preview and its crop overlay zoom together; zoom resets on every tool
  entry and is inert while an export runs.
- **Live tonal histogram** in the Adjust panel: an RGB channel chart counted
  from the same pipelined pixels the canvas shows, updating live during a
  slider drag, and spoken to TalkBack as three luma zones.
- **Honest sky thumbnails**: the AI panel's sky chips render the five
  bundled parametric skies through the same renderer the sky stage uses;
  imported skies show their own bitmap.
- **Neutral notch**: bipolar sliders gain a hairline mark at the track's
  exact center, making the double-tap zero discoverable.
- **LabeledSlider** across the Draw, Region, AI, and Crop panels: label,
  live value pill, and double-tap-to-neutral on every control.
- **Ente-style tune rail** on the Adjust panel: a chip rail with circular
  value rings, exactly one active slider, tap-the-active-chip-to-zero, and
  Reset promoted into the tool header across all ten tools.
- A tappable honesty chip opening the export-policy sheet, and a
  hold-to-compare chip on the canvas.
- Roborazzi screenshot baselines for the chassis and the slider panels.

### Changed

- **Toad-green palette registry**: the editor theme is rebuilt around an
  `EditorPalette` registry (primary `#9CD8A2` on `#00390F`, warm dark
  surfaces); violet chrome retires from both the photo and video surfaces.
  The editor stays fixed-dark and non-dynamic by design.
- The video chassis adopts the same design language: the filmstrip becomes
  the primary trim surface (drag-live handles, clamped playhead, pause while
  dragging), the transport simplifies to canvas-tap play/pause plus
  double-tap 10-second seek, the fullscreen toggle anchors inside the
  preview frame, and the Extract/Animate tabs are honestly renamed Frame and
  GIF ("Export GIF").
- Tool navigation stays available across photo tools; selected-tab
  readability and accessibility improved; duplicate bitmap work skipped for
  identity previews; panel crossfades removed.

### Fixed

- A JVM pin asserts the editor header is top-most (the mid-screen header
  device report); the Save-copy CTA gets `maxLines = 1` plus ellipsis for
  longer locales; the video preview fills the screen instead of a hardcoded
  220dp box.

## [0.4.13-edit-alpha] — versionCode 18

### Changed

- The photo editor's chrome restyles onto the video editor's dark palette —
  hardcoded white/black maps to theme roles, the color-swatch selection ring
  reads the accent. Behavior, state machine, and information architecture
  are unchanged.

## [0.4.12-edit-alpha] — versionCode 17

The video editor's UI/UX redesign over the original plan semantics:

### Added

- **Volume**: `VideoEditPlan.volume` (default 1.0 = unchanged; bounds 0–2,
  NaN-gated), applied via media3 `GainProcessor` on export and
  `player.volume` in preview; the Audio screen carries Mute plus a 0–200%
  slider, with an honest no-audio note on silent clips.

### Changed

- Single-scaffold dark chassis: header with the Export pill, preview with a
  fit/zoom toggle, transport row, timestamp row, and a real-frame filmstrip
  (ten decoded thumbnails, drag-to-scrub). A six-tab bottom bar — Trim,
  Speed, Audio, Adjust, Frame, GIF — replaces the long-scroll surface; tool
  screens run a draft flow (Reset / Apply). Text/stickers/filters on video
  remain out of scope per the video-editor ADR; no fake UI is rendered.

## [0.4.11-edit-alpha] — versionCode 16

### Fixed

- Contract-discovery false refusals: with both apps installed, the Edit
  action could narrate "outdated for this gallery contract". The editor now
  declares an application-level contract marker (value 2) as a fallback;
  the resolving side gained metadata visibility, a version-precedence chain,
  a wildcard-mime retry against concrete mime filters, and a package-probe
  fallback.

## [0.4.10-edit-alpha] — versionCode 15

### Added

- The editor's first signed, published release artifact; the entire prior
  version line existed only as CI debug builds. Contents: the non-destructive
  photo pipeline (v1), the five layer families (v2), the on-device AI suite
  (v3), and the lightweight video editor.

## [0.4.9-edit-alpha] — versionCode 14

### Added

- **The lightweight video editor**: trim, mute, slow-mo (0.25x–2x
  non-overlapping segments with a monotonicity rule), frame extraction, and
  GIF / animated-WebP export over the media3 Transformer. Preview and export
  share one shader model; every export publishes a new MediaStore entry
  (non-destructive; the source is never replaced).
- **Editor-contract v2** (additive): video edits negotiate contract v2 while
  the v1 image wire stays byte-identical.

## [0.4.8-edit-alpha] — versionCode 13

### Added

- **Edit v3 on-device AI**, shipped as pixel stages between sharpen and the
  regions:
  - **Magic erase**: paint a seed, u2netp refines the mask, MI-GAN fills the
    region.
  - **Sky replacement**: five project-authored parametric skies plus user
    import, blended over a u2netp sky mask at an adjustable opacity.
  - **Bokeh**: Depth-Anything-V2-Small (int8) runs once at commit; strength
    and focal sliders re-render from the quantized depth artifact with no
    re-inference.
  - **Face retouch** (opt-in): YuNet face localization plus smoothing, eye,
    and slim kernels, gated by a first-use consent dialog and a Settings
    kill-switch; the stage refuses under consent-off even with a restored
    session sidecar.
- Inference runs at commit time only (never in the render loop) through ONNX
  Runtime 1.31.1 (NNAPI-first, CPU fallback); AI artifacts persist under the
  sidecar discipline (atomic writes, content-hash keys, export sweep). At
  this point the four models shipped inside the APK (61,613,133 bytes,
  SHA-256-pinned) — superseded by the [Unreleased] on-demand download above.

## [0.4.6-edit-alpha] — versionCode 11

### Added

- **Frames**: five project-authored parametric frames (Plain, Inner line,
  Double line, Film, Polaroid) as pure integer geometry, plus PNG/WebP frame
  import; one frame at a time, composited last over the finished pixels. The
  undo/redo extension is test-pinned across all five layer families (text,
  stickers, strokes, regions, frames) in one mixed session.

## [0.4.5-edit-alpha] — versionCode 10

### Added

- **Mosaic/blur regions**: paint a region over the photo; it receives a
  mosaic (block average) or a linear blur at a strength, applied as the
  pipeline's final stage on the fully graded pixels so censored output is
  never re-processed. Regions are the fourth exclusive layer family.

## [0.4.4-edit-alpha] — versionCode 9

### Added

- **Freehand drawing**: one gesture is one layer and one undo snapshot;
  per-layer color, width, opacity, and smoothing (Chaikin corner-cutting at
  place time); strokes composite between the sticker and text passes.

## [0.4.3-edit-alpha] — versionCode 8

### Added

- **Stickers**: eight project-authored vector shapes (authored code, not
  image assets) plus PNG/WebP import (2 MB cap, collision-free ids that can
  never shadow the bundled set); per-layer size, opacity, rotation, and
  position; stickers composite below the text pass.

## [0.4.2-edit-alpha] — versionCode 7

### Added

- **Text tool**: four bundled SIL OFL 1.1 fonts (Fira Sans, Playfair
  Display, Caveat, Space Mono), per-layer size, color, opacity, rotation,
  and drag positioning; text renders last, anchored as fractions of the
  output canvas; undo/redo commits at field granularity, never per
  keystroke.

## [0.4.1-edit-alpha] — versionCode 6

### Added

- **LUT filter system**: five project-authored looks (generated by a
  committed deterministic generator; no third-party LUT data) with a live
  intensity slider; a pure Adobe `.cube` parser with an allocation-free
  trilinear sampler; user look import via the SAF document picker
  (validated, stored verbatim, removable, unable to shadow the bundled set).

## [0.3.1-edit-alpha] — versionCode 4

### Changed

- **Accessibility pass**: every editor slider announces its name and live
  value to TalkBack; flip toggles become proper FilterChips with 48dp touch
  targets; the straighten slider follows the drag live; crop presets commit
  through a tested pure inverse (`CropRect.fromPixelRect`).

## [0.3.0-edit-alpha] — versionCode 3

### Added

- **Per-export metadata policy choice**: the export sheet may strip more
  metadata for one saved copy (explicit opt-in) but can never strip less
  than the launching gallery requested; the choice never rides the recipe
  sidecar.
- **Export preflight**: a large-file decode guard that refuses before any
  work, a storage byte-model check against allocatable space, and a
  documented process-death policy.
- The saved-copy result gains `removesLocation` / `stripsAllMetadata`
  triggers in `:shared:editor-contract` so the caller can narrate what a
  saved copy removed.

## [0.2.0-edit-alpha] — versionCode 2

### Added

- **Edit v1 core**: the `EditRecipe` session is a versioned JSON sidecar
  (additive codec: unknown keys ignored, out-of-range values clamped,
  corrupt JSON a structured failure; saved on commit, restored on relaunch,
  cleared on export). Pure pipeline stages — geometry (crop rect plus aspect
  presets, flips, straighten on an expanded canvas, quad perspective
  correction), tone LUT (exposure, brightness, contrast, highlights,
  shadows), saturation, temperature/tint, eight-band HSL, radial vignette,
  and an unsharp mask — run in one documented order so preview and export
  agree by construction. Export offers JPEG (quality slider), PNG, and WebP;
  HEIC is never offered (the platform Bitmap has no HEIC encoder). Every
  export is a new MediaStore entry; no replace-original path exists.

## [0.1.0-edit-alpha] — versionCode 1

### Added

- **The editor app and the versioned contract**: `:shared:editor-contract`,
  a pure-JVM module both apps consumed — frozen v1 constants
  (`org.toadgallery.action.EDIT`), exact editor-identity matching, additive
  version negotiation, the request validator (`content://`-only sources,
  `save_copy`-only export mode, total metadata-policy parse), and the total
  result parser (unknown codes degrade to structured errors, never a crash).
- The editor prototype: an exported contract activity (JPEG/PNG/WebP/HEIC),
  a real brightness and contrast LUT adjust with rotation, a live bounded
  preview, draft sliders, a 64-deep undo/redo stack, and non-destructive
  save-copy export (MediaStore INSERT of a new row only; pending rows
  cancelled on failure so no partial entry survives). The source URI is
  read-only by construction, and the metadata policy (preserve / strip
  location / strip all) treats the caller-requested policy as the privacy
  floor.

## Pre-isolation gallery project (context, summarized)

The editor spent its life as a companion APK to the toadGALLERY gallery app
(`io.github.dukeoftoad.gallery`), which opened it through the versioned
contract above and offered a plain third-party `ACTION_EDIT` fallback when
the editor was not installed. The gallery's own capabilities — media
timeline and albums, metadata details with EXIF write-back, PIN/biometric
vault, recycle bin, offline maps, Moments/Stories/collage/slideshow, and
the opt-in on-device AI engine (semantic search, People, Things over a
vendored Ente-derived Rust/ONNX stack) — are gone from this branch and are
not tracked here. What carries over: the contract module, the release
engineering posture (pinned release key with a hard `apksigner verify`
gate, R8 mappings, pre-release-only publishing), and the editor's
zero-telemetry, on-device-only processing discipline.
