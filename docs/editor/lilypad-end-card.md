# Lilypad — the editor rebirth: the end card

**Status:** Complete (Phase 1 r67 · Phase 2 r68 + r71 · Phase 3 r69-r70 · Phase 4 r72) ·
**Owner:** dukeoftoad ·
**Design:** docs/editor/lilypad-design.md (the living contract) ·
**Campaign:** PRs #171, #172, #173, #174, #175, #176 — six reviewed rounds, every one CI-gated, every merge a squash with the round's name in the title.

The fire-bellied toad sat motionless on its lily pad — dark, quiet, patient — and struck in an instant when it mattered. This card closes the campaign: what shipped, what it cost, what it promised and kept, and what the next pond is.

## 1. The four phases, as shipped

| Phase | Round(s) | What landed | The promise it kept |
|---|---|---|---|
| **1 — foundation** | r67 (#171) | `LilypadTokens` (one neutral family + one ember), `ProSlider` (drag-anywhere rows, long-press fine, double-tap neutral, haptic zero-tick), `LilypadDeck` (the 64dp/45%/78% three-state sheet), the LookCard shelf (real-pipeline thumbs), the full Adjust board, Manrope + JetBrains Mono bundled | "The full surface, always" — no value hidden behind a chip |
| **2 — photo pro** | r68 (#172) + **r71 (#175)** | The Full-deck curve editor (monotone Fritsch-Carlson bake), RGB parade + clipping zebra, the history scrubber, the crop dial + keystone rows, HSL on ProSlider — and r71: the per-channel R/G/B curve composition in the engine (the three-table apply, the forward state's promised landing) | "Everything live, everything undoable" — the r68 sidecar that carried channel curves renders its promised colour today, no migration |
| **3 — video timeline** | r69 (#173) + r70 (#174) | `ClipTimeline` (the multi-clip model, 14 JVM pins), `LilypadTimelineMath` (anchored pinch-zoom viewport, detents), the zoomable `TimelineStrip` (honest-sparse frames), the sealed `VideoExportJob` + `ClipExportProjection` (per-entry `EditedMediaItems`, the v1 path pinned byte-identical) | "A video timeline that actually edits" — the export half is real; the picker/playlist ride F-77 |
| **4 — craft polish** | r72 (#176) | The perspective corner drag: `LilypadCornerMath` (hit-test, the engine's own clamp bounds, the identity snap) + `CornerOverlay` (the displaced quad's outline and handles, live-draft per tick, one commit per gesture) — behind `cornerDragEnabled` | "One hand runs the studio" — any corner, any direction, one finger; the keystone rows stay as the a11y path |

## 2. The numbers

| Metric | Value |
|---|---|
| Lilypad-prefixed JVM test methods | **169** across 12 test classes (every rule pinned pure — ADR-0011) |
| editor/core total test methods | **668** |
| shared/video-edit test methods | **133** (the r70 module, locally verified before every push since) |
| Revert seams (`LilypadFlags`) | 5 — `decksEnabled`, `curveDeckEnabled`, `historyStripEnabled`, `timelineZoomEnabled`, `cornerDragEnabled` — each a one-line reviewed flip |
| New pure-math objects | 12 (`LilypadSliderMath`, `LilypadDeckMath`, `LilypadCurveMath`, `LilypadCurveDeckMath`, `LilypadKeystoneMath`, `LilypadDialMath`, `LilypadZebraMath`, `LilypadHistoryNames`, `LilypadTimelineMath`, + `ClipTimelineMath`, `ClipTimelineValidator`, `LilypadCornerMath`) |
| CI gates carried the campaign | editor-ci: assemble + lint + `:editor:core:testDebugUnitTest` + `:editor:app` + `:shared:editor-contract:test` + `:shared:video-edit:test` |
| Roborazzi | Suspended as a PR gate (r68, F-76): manual dispatch, one deliberate record pass per phase merge |

## 3. The discipline that held

- **ADR-0011, every round**: pure JVM math objects, thin composable shells, zero Compose imports in the math, every rule test-pinned before the push.
- **The forward-state honesty**: r68 shipped the R/G/B wire shape complete and said so ("not yet composed"); r71 turned it on with no migration and a test proving the old sidecar renders.
- **The local verification recipe** (F-79): kotlinc 2.3.10 + JUnit over the pure subset — 119 green at r71, 131 at r72 — before every push.
- **The revert seams**: every surface behind a flag whose off-rail is the verbatim prior surface; nothing else reads the flag.

## 4. What rides the follow-ups (open tails, honestly)

- **F-77** — the multi-clip surfaces' UI half: the in-editor clip picker, the contract v3 extra, the ExoPlayer playlist preview.
- **F-78** — window-aware thumbnail decode with LRU (the r69 honest-sparse rule is the documented interim).
- **F-75** — the instrumented E2E lane (no connectedDebugAndroidTest anywhere today).
- **F-76** — the Roborazzi re-arming decision (the suspension's 3-line revert is documented in screenshots.yml).
- Phase 4's remaining craft items — the motion/haptics pass, 60fps scrubbing, keyframes/speed ramps, export reliability hardening — are roadmap work, not campaign debt: the campaign's own scope (the chassis rebuild) is complete.

## 5. The pond, closed

Lilypad was a rebuild with a frozen engine: the recipe core, the codec, the pipeline never changed shape — only grew additive fields the old sidecars still read clean. Six rounds, six green CIs, six squash merges, and a sidecar from any of them still opens in the editor built by any later one. The toad is on the pad. The edit is the strike.
