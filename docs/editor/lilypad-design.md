# Lilypad — the editor rebirth design language

**Status:** Active (Phase 1 shipped as r67) · **Owner:** dukeoftoad ·
**Source:** the founder's vision document ("Lilypad — the editor rebirth",
November 2026) · **Implementation:** `editor/core/.../Lilypad*.kt`,
`ProSlider.kt`, `LilypadCards.kt`

The fire-bellied toad sits motionless on its lily pad — dark, quiet,
patient — and strikes in an instant when it matters. That is this editor:
the interface is the pond; the edit is the strike.

## 1. The mandate

The photo editor was honest but toy-like (a chip rail that revealed ONE
slider at a time, hiding every other value); the video editor was a
single-clip trimmer wearing an editor's badge. Both sit on excellent
engineering (the non-destructive recipe core, the JVM-pinned purity of
ADR-0011, the a11y contracts of r32, the Roborazzi discipline) that
deserved a better body. The rebuild keeps the engine and replaces the
chassis, held to three promises:

- **P1 — a photo studio professionals trust**: the full control surface
  visible at once, live tabular values, scopes that tell the truth,
  looks you can see before you choose.
- **P2 — a video timeline that actually edits** (Phase 3): multi-clip,
  text overlays, music, speed — previewed through ExoPlayer, exported
  through media3 Transformer.
- **P3 — one language across both**: the same looks, the same color
  grammar, the same typography, the same export feel.

## 2. The six principles

1. **The photo is the hero.** Dark canvas, no large colorful surfaces,
   chrome recedes until touched.
2. **The full surface, always.** No value is hidden behind a chip.
3. **Everything live, everything undoable.** Zero-ms feedback during
   drags, commit on release, history you can scrub.
4. **Visual-first choices.** Looks, skies, frames, fonts — shown, not
   listed.
5. **One hand runs the studio.** Bottom-anchored, thumb-sized controls.
6. **Honest software.** No watermark hostages, no fake placeholders,
   numbers that sum to 100 because they do.

## 3. The tokens (`LilypadTokens.kt`, pure Kotlin, JVM-pinned)

One neutral family + one ember. Color is information, never decoration:
the ember marks selection, the live value, the CTA — nothing else.

| Token | Hex | Role |
|---|---|---|
| Pond | `#0B0C0E` | the canvas |
| Pad | `#15171B` | sheets (the Deck) |
| Pad raised | `#1E2126` | controls, pills at rest |
| Hairline | `#2A2E35` | borders, slider tracks |
| Reed | `#9BA0A8` | muted text (≥ 4.5:1 on Pad) |
| Paper | `#F2F1EE` | text (≥ 7:1 on Pad) |
| Ember | `#FF6B45` | THE accent |
| On ember | `#2A0E05` | text on ember fills |

- **Depth** comes from lightness steps (pond → pad → pad-raised) and
  hairlines — never drop shadows.
- **Shape**: 16dp sheet corners, 12dp controls/cards, 8dp chips.
- **Type**: Manrope (OFL) for display/body (96/800, 30/800, 15/400),
  JetBrains Mono (OFL) for every number (12/600) — counting digits
  never jitter the layout. Both bundled at `res/font`, OFL texts at
  `docs/licenses/`.
- **Motion**: one spring for sheets (damping 0.85, stiffness 380); fades
  240ms standard / 180ms tool; parallax drift 8dp. Value feedback: 0ms.
- **Haptics**: four primitives — ZERO_TICK (crossing a neutral),
  DETENT_DOUBLE (snap points), COMMIT_SOFT (a draft landing),
  EXPORT_SUCCESS. Nothing else buzzes.
- The M3 bridge roles live in `LilypadTokens.Roles`; the scheme mapping
  in `EditorPalette.Lilypad` (EditorTheme.kt). The r52 selection
  mechanism survives: a SELECTED FilterChip is the ember-filled pill.

## 4. The Phase 1 kit (r67, this round)

- **ProSlider** (`ProSlider.kt` + `LilypadSliderMath.kt`): the flagship
  row — label left, mono value pill right (ember-when-live), the
  hand-drawn track under them. Drag anywhere on the row (relative
  scrub, the Snapseed lesson), long-press = fine mode at 10% gain,
  double-tap = neutral, haptic tick on zero crossing, center notch on
  bipolar ranges. a11y: name + state + adjustability
  (progressBarRangeInfo).
- **Deck** (`LilypadDeck.kt` + `LilypadDeckMath.kt`): the three-state
  bottom sheet replacing the r54 fixed 300dp window — Collapsed (64dp
  peek), Half (45% working surface), Full (78%, reserved for the Phase 2
  curve deck). Spring motion, hairline top edge, 16dp top corners,
  scrolling content. `LilypadFlags.decksEnabled` is the reviewed revert
  seam.
- **LookCard shelf** (`LilypadCards.kt`): the Looks panel's two-column
  card grid — thumbs render the REAL preview base through the SAME
  pipeline the export uses, press-and-hold compares against the plain
  original, the None card shows the photo itself.
- **The Adjust board** (`PhotoPanelTone.kt`): every ADJUST_SLIDERS
  parameter visible at once, histogram leading (the B1 fix).
- **The canvas matting** re-pins to Pond (`EditActivity`), and the video
  lane's scheme follows the same registry (one language, both products).

## 5. The phase roadmap

- **Phase 1 — foundation (r67, this round)**: tokens, kit, Adjust +
  Looks decks, TuneRail retired, fonts, CI gates the Lilypad tests.
- **Phase 2 — the photo pro round**: tone curve editor (Full deck), RGB
  parade + clipping zebra, history scrubber, crop dial + perspective,
  HSL rows on ProSlider.
- **Phase 3 — the video timeline**: pinch-zoom timeline, multi-clip
  Composition, shared Looks on GL effects, text overlays, speed, music,
  Transformer export with foreground-service progress.
- **Phase 4 — craft polish**: the motion/haptics pass, 60fps scrubbing,
  keyframes and speed ramps, export reliability hardening.

## 6. Budgets and risks

| Budget | Target |
|---|---|
| Preview latency | full-res working-grid render < 16ms at preview scale during drags |
| Scrub performance | timeline playhead ≥ 55fps p90 on Pixel 6a class |
| Export reliability | ≥ 99% success, failures produce human-readable diagnoses |
| Cold open | launch → first adjustable slider < 1.5s on mid-range |

**R1 — baseline churn**: the restyle invalidates the Roborazzi
baselines by design; the mitigation is ONE deliberate re-record pass
per phase merge (reviewed with a rendered diff), never scattered
re-records. r67 ships the new fixtures (ProSliderScreenshotTest) and
deletes the rail's; the record pass is a documented follow-up.

**R2 — timeline performance** (Phase 3): thumbnail windows with LRU
frame caching, reduced effect resolution during scrub.

**R3 — the rebuild trap**: the engine is frozen and CI-gated (recipe,
pipeline, JVM pins); the chassis ships through the proven editor →
unified → alpha pipeline; every phase gate is a ship gate.

## 7. Attribution

Interaction patterns are studied from the market's best (Lightroom,
Snapseed, Darkroom, VN/CapCut) and re-derived from behavior; every line
of code is original (GPL-3.0 repo). The bundled fonts carry their OFL
notices (`docs/licenses/`), recorded in `docs/licensing-and-attribution.md`.
