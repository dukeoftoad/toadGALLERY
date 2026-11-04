# Bundled LUT Looks — Authorship and Licensing (r33, Phase 7)

**Status:** r33 (2026-11-03) — the Phase 7 "LUT-based filter system"
round's asset record. This file is the licensing evidence the roadmap's
Phase 7 exit criterion points at: *"No bundled asset has an
incompatible license."*

---

## Authorship statement

All five bundled `.cube` looks are **project-authored for
toadGALLERY**. They contain no third-party LUT data — not sampled from
another product, not converted from a downloaded preset pack. The single
source of the bytes is the committed generator:

```
tools/gen_bundled_luts.py
```

The generator is deterministic (no clocks, no randomness, no network):
running it against this repository reproduces the committed files
byte-for-byte. The formulas below are the complete definition of each
look — anything not in a formula is not in the file. Regenerate with:

```
python3 tools/gen_bundled_luts.py
```

License: the generator, the generated `.cube` files, and this document
are part of the toadGALLERY repository and ship under its GPL-3.0
license. There is no separate asset license because there is no
third-party asset. (The roadmap also permits CC0 for bundled LUTs;
project-authored-under-GPL-3.0 satisfies the same criterion — the
project controls the rights either way.)

## The five looks (the formulas ARE the specification)

Common helpers: `clamp01`, `luma(r,g,b) = 0.299r + 0.587g + 0.114b`,
`contrast(v, k) = 0.5 + (v − 0.5)·k`. Grid: `LUT_3D_SIZE 17`
(4,913 samples, ~130 KB per look; smooth for parametric curves, five of
them ≈ 650 KB in the APK).

| id | Title | Definition |
|---|---|---|
| `warm-film` | toadGALLERY Warm Film | per channel: `v₁ = contrast(v, 1.12)`, `v₂ = v₁ + 0.045·(1 − v₁)` (lifted shadow floor), then gains R×1.06, G×1.00, B×0.92 |
| `cool-chrome` | toadGALLERY Cool Chrome | `contrast(v, 1.18)` per channel, then gains R×0.94, G×1.00, B×1.07 |
| `silver-mono` | toadGALLERY Silver Mono | `m = contrast(luma, 1.08)`; R=G=B=m (rec.601 monochrome) |
| `matte-fade` | toadGALLERY Matte Fade | `y = luma`; `out = 0.09 + (y + (c − y)·0.75)·0.83` (blacks lifted to 0.09, whites held at 0.92, desaturated 25%) |
| `vivid-punch` | toadGALLERY Vivid Punch | `out = contrast(y + (c − y)·1.28, 1.10)` (28% saturation push, mild contrast) |

Every value is clamped to [0, 1] after the formula (the generator
writes 6 decimals). The probes in
`apps/edit/src/test/.../BundledLutsTest.kt` pin these formulas at
grid-exact coordinates — the tests are the executable form of this
table.

## Format details

- Domain: the default `0 0 0` – `1 1 1`.
- Table order: R index increments fastest (the order the engine's
  parser expects — `CubeLutParser` in `apps/edit/`).
- Each file carries `TITLE` (the display name source), `LUT_3D_SIZE`,
  both `DOMAIN` lines, and two provenance comment lines pointing back
  at the generator.

## Runtime behaviour (the tolerance contract)

The registry (`BundledLuts`) loads the looks from `assets/luts/` via an
injected stream opener at session start — one parse, shared by preview
and export, so the two cannot disagree. An asset that fails to read or
parse is skipped (absent from the chip row), never thrown: the
conformance test pins that every committed asset parses, so a runtime
skip means a broken build, not bad data. A recipe referencing a look
this build does not bundle keeps the id in its sidecar (the recipe is
the user's data) and degrades to no-look at apply time — the r33
tolerance contract.

## User-imported LUTs

The roadmap's "the user can import their own `.cube` files" is the next
Phase 7 round (an SAF pick-and-copy flow indexing alongside the bundled
set); the engine, registry and pipeline stage built in r33 are the
foundation it plugs into. This document covers the BUNDLED set only.
