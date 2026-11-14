# toadGALLERY Edit — Roadmap

**Product:** toadGALLERY Edit is a private, offline-first, non-destructive photo and
video editor for Android — a standalone editor APK (GPL-3.0) built from scratch in
Kotlin + Jetpack Compose, launched on its own or through a versioned contract from
the Gallery app. The only network use is a user-triggered download of pinned AI
models from the project's own release host.

**Status:** editor-only isolation. The repository carries exactly four Gradle
modules — `:editor:app`, `:editor:core`, `:shared:editor-contract`,
`:shared:video-edit` — and the gallery, unified, and engine trees have moved to the
separate Gallery codebase. Current editor line: **versionCode 19,
versionName 0.4.14-edit-alpha**, branch `editor-standalone`.

---

## 1. Delivered

What exists today, grouped by capability:

| Capability | What shipped |
|---|---|
| Non-destructive photo pipeline | `EditRecipe` + sidecar store (save-on-commit, restore-on-relaunch, clear-on-export); commit-on-release draft slots; undo/redo across every tool family; save-copy default — originals are never modified |
| Geometry | crop rect with aspect presets, flips, straighten on an expanded canvas, quad perspective; canvas zoom |
| Adjustments (tune rail) | exposure / brightness / contrast / highlights / shadows; saturation / temperature / tint; 8-band HSL; vignette; unsharp sharpen — on the tab-bar chassis shared with the video editor |
| LUT filters | `.cube` parser with honest subset rules, allocation-free trilinear sampler, alpha-preserving intensity blend; five project-authored bundled looks; SAF user-import flow with collision-safe stores |
| Text / stickers / drawing / mosaic / frames | four SIL-OFL bundled fonts; bundled vector stickers plus PNG/WebP imports; freehand strokes with per-stroke re-edit and Chaikin smoothing; painted mosaic/blur regions as the pipeline's final pixel stage; five parametric frames plus imports; one-selection duality across all families |
| On-device AI | magic erase (u2netp segmentation + MI-GAN fill), sky replacement (five authored parametric skies), bokeh (Depth-Anything-V2-Small int8), opt-in face retouch (YuNet detection + explicit consent); ONNX Runtime, NNAPI-first with CPU fallback; the four models are **release-hosted and download on demand** from the pinned public GitHub release `onnx-models-v1` (~61.6 MB total, byte-pinned) instead of shipping in the APK |
| Video editor | six tool tabs (Trim, Speed, Audio, Adjust, Extract, Animate); two-handle trim with a real-frame filmstrip and drag-to-scrub; speed 0.25x–2x; mute + volume gain; nine real adjustments; frame extraction; GIF/WebP animation with byte-budget preflight; pure export plans in `:shared:video-edit` realized by media3 Transformer behind a runner seam |
| Export pipeline | JPEG/PNG/WebP save-copy; per-export metadata policy where the launch request is the privacy floor (strip more, never less) with honest applied-policy narration; 48 MP large-file decode guard and storage-headroom preflight; process-death-safe export; video output under a non-destructive MediaStore policy |
| Contract integration | pure-JVM `:shared:editor-contract`: identity match, version negotiation, request validation, result parsing; launch-request metadata-policy floor; video launch path; application-level contract marker for discovery hardening |
| Privacy and release engineering | build-time merged-manifest permission gate (INTERNET exists solely for the pinned-host model fetch); R8-minified releases with per-APK mapping.txt; env-injected signing with pinned-certificate verification; double-build reproducibility verification; F-Droid metadata; Roborazzi screenshot harness; dispatch-only editor APK build workflow; GitHub Actions as the merge gate |

## 2. Now / maintenance

Current focus:

- **Editor-only codebase.** Keep the four-module tree lean; no gallery code
  returns. The contract's other half (launch, resolution, refresh) lives in the
  Gallery repository and evolves only through the frozen, versioned contract
  surface.
- **Docs consolidation.** The documentation set is being condensed to a compact,
  editor-only shape: this roadmap plus architecture and capability notes.
- **On-demand AI models.** The downloader is pinned to the `onnx-models-v1`
  release assets with byte-size verification and a consent-first flow; the
  pinned-host-only posture (one host, no redirects, fail-closed) is maintained.

Maintenance policies that continue:

- **Permission-set gate.** The merged-manifest allowlist test stays green; any
  permission change is deliberate, documented, and pinned in both directions.
- **Reproducible builds.** The double-build comparison continues on every
  release cut; signing stays env-injected with pinned-certificate verification.
- **F-Droid metadata.** Updated with every release-line change, keeping the
  permissions/privacy description truthful (including the model-fetch network
  grant).
- **Quality gates.** CI is the merge gate; every behavior change gets tests; the
  accessibility conventions (TalkBack labels, focus order, scalable text, 48 dp
  targets) apply to every new surface; no heavy work on the UI thread; the
  non-destructive guarantee stays non-negotiable; model and dependency licenses
  are recorded.

## 3. Planned / candidates

Everything below is a **candidate** — no dates, no commitments. Items were
distilled from the outstanding editor rows in the task ledger and workplan;
gallery-only ideas are intentionally absent.

- **On-device validation sweep.** The outstanding human-only device rows: the
  end-to-end Gallery-to-Edit round trip plus the export robustness matrix
  (metadata policy × GPS, large-file guard, low storage, process death on Q+ and
  pre-Q, distinct failure narrations); per-tool TalkBack passes over the photo
  editor; video-editor TalkBack over the tab surface, the audible volume-gain
  check, filmstrip decode cost on long clips, and trim-handle ergonomics; the
  AI benchmark cells.
- **Video editor extensions.** A text/effects pipeline over video; video-session
  undo/redo; a decode-once option for the waveform strip.
- **Export codec probes.** HEVC and WebM video export paths; HEIC still-image
  export (today the honest JPEG/PNG/WebP set only).
- **Imported-asset management.** A library-management UI for imported stickers
  and frames (deletion exists; organization does not yet).
- **Accessibility and design polish.** Close the text-layer position-drag
  TalkBack gap; pin the derived theme neutrals as first-class design tokens
  instead of documented derivations.
- **Additional on-device AI stages.** Further editing models following the same
  pattern: opt-in consent, pinned release assets, byte-verified download,
  on-device inference only.
- **CI hygiene.** Extract the replicated workflow step blocks into composite
  actions; exercise the dispatch-only editor APK build workflow end to end.

## 4. Non-goals

- No telemetry, analytics, ads, accounts, or cloud services of any kind; the
  editor never talks to a third-party host.
- No gallery features — browsing, albums, search, metadata screens, maps,
  Moments, Stories, vault, people/scene indexing — those live in the separate
  Gallery app/codebase; the editor speaks only the versioned contract.
- No cloud ML; model bytes come only from the pinned project release.
- No multi-track video editing; no RAW development.
