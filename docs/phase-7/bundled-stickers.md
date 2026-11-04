# Bundled Stickers — Authorship and Licensing (r35, Phase 7)

**Status:** r35 (2026-11-04) — the sticker-tool round's asset record, and
the licensing evidence the roadmap's Phase 7 exit criterion points at:
*"No bundled asset has an incompatible license."* For this round the
honest form of that evidence is an **authorship statement about code**,
not a provenance pin about files: no image asset is bundled at all.

---

## Why there is no asset to pin

The r33 LUT round and the r34 font round both bundled *file-shaped*
data, so both carry file evidence: a deterministic generator with
byte-for-byte regeneration (`.cube`), and sha256 pins over verbatim
upstream copies (TTF). Stickers fail the file-shaped test in both
directions:

- **A committed PNG/WebP bundle** would need a deterministic generator.
  A Python PNG writer's zlib output is not guaranteed stable across
  zlib versions, so a byte-for-byte regeneration promise — the
  `gen_bundled_luts.py` guarantee — could not be honestly made; and
  storing the sheets uncompressed costs ~2 MB for eight 256×256 RGBA
  stickers, twenty times the font bundle.
- **A vendored third-party sticker pack** would drag in exactly the
  provenance audit the roadmap's exit criterion exists to force, for
  zero functional gain.

So the bundled set is **authored in code**: eight shape definitions in
`BundledStickers` (pure Kotlin, `apps/edit/.../StickerLayers.kt`), each
a list of filled paths with integer coordinates in a 0..100 design box
(y down, screen convention). The shapes are the project's own work —
not traced from an emoji set, not converted from a sticker pack. They
ship under the repository's GPL-3.0 like every other source file; there
is no separate asset license because there is no third-party asset, and
no §5 provenance-ledger row because no file was copied from anywhere.

## The authored formulas (the shapes ARE the specification)

Common helpers, all in `BundledStickers`'s private builder layer:

- `ngon(cx, cy, r, n, startDeg)` — the regular N-gon approximating a
  circle: vertices at `(round(cx + r·cos θ), round(cy + r·sin θ))` for
  θ = startDeg + 360°·k/n, k = 0..n−1; y grows down, so +θ runs
  clockwise on screen. One subpath: MoveTo(first), LineTo(rest), Close.
- `arcPts(cx, cy, r, fromDeg, toDeg, steps)` — the open polyline along
  the same circle between two angles, `steps` LineTo points after the
  MoveTo (the smile's outer edge; Close chords it into a half-disc).
- `poly(points)` — an explicit polygon subpath: MoveTo(first),
  LineTo(rest), Close.

The palette (Material tones, fixed): red `0xFFE53935`, yellow
`0xFFFDD835`, pink `0xFFF06292`, near-black `0xFF212121`, white
`0xFFFFFFFF`.

| id | Display name | Definition (all coordinates in the 0..100 box) |
|---|---|---|
| `heart` | Heart | one red path: M(50,90) C(20,70)(6,44)→(20,28) C(30,18)(44,24)→(50,36) C(56,24)(70,18)→(80,28) C(94,44)(80,70)→(50,90), Close — lobes at y≈28 spanning x 20..80, the dip at (50,36), the tip at (50,90) |
| `star` | Star | `poly` over the 10-point star: outer R 48 / inner r 20 about (50,50), outer at −90°+72°·k, inner interleaved at −90°+36°+72°·k — (50,2)(62,34)(96,35)(69,44)(78,89)(50,70)(22,89)(31,44)(4,35)(38,34) |
| `smiley` | Smiley | face `ngon(50,50,46,24,0)` yellow; eyes `ngon(36,40,6,12,0)` and `ngon(64,40,6,12,0)` near-black; mouth `arcPts(50,50,30,20,160,7)` + Close (a half-disc, i.e. the arc from 20°..160° through the bottom at 90°), near-black |
| `bubble` | Speech bubble | body `poly` (28,18)(72,18)(82,28)(82,54)(72,64)(28,64)(18,54)(18,28) — the 10-unit-cut octagon in 18..82 × 18..64; tail `poly` (38,63)(58,63)(40,88); both white — one merged silhouette, tail drawn after the body |
| `bolt` | Lightning | `poly` (60,4)(24,54)(44,54)(36,96)(76,40)(54,40), Close chords (54,40)→(60,4); yellow |
| `flower` | Flower | one pink part, five petal subpaths `ngon` r 18 at the five centres (50,72)(29,57)(37,32)(63,32)(71,57) (radius 22 about (50,50), 72° apart); centre `ngon(50,50,13,12,0)` yellow, drawn last |
| `pin` | Location pin | one red path: the 24-gon head `ngon(50,40,26,24,0)` whose last vertex continues LineTo(50,96) before Close (the teardrop tip); inner `ngon(50,40,11,12,0)` white, drawn last |
| `crown` | Crown | one yellow part: `poly` (26,74)(22,36)(38,54)(50,28)(62,54)(78,36)(74,74), Close chords the base; gems: three white `ngon` r 5 subpaths at (22,31)(50,23)(78,31) — one white part drawn after the crown |

Spot coordinates with safe rounding margins (≥0.3 units from a .5
boundary) are pinned by `BundledStickersTest`, so a change to any
formula fails the build, not the user.

## What the registry guarantees

`BundledStickers` is a pure Kotlin object: unique ordered ids, the
display-name table (proper nouns — not localized, the `BundledFonts`
convention), and `shape(id)` returning the immutable definition. The
structural invariants (coordinates within 0..100, subpaths
MoveTo-started and Close-ended, at least three ops per subpath) are
enforced by the shape builder and pinned by the test — a broken bundle
is a broken build, never a runtime crash.
