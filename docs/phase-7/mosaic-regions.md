# The Mosaic/Blur Regions — r37 Round Design (Phase 7, layer type 4 of 5)

**Status:** DELIVERED on `tg-p7-mosaic` (2026-11-04, the r37 round; pending
PR/CI/merge per the F-12 convention --- see the delivery record at the
bottom). This doc was the design contract the round implemented. Layer
type 4 of Phase 7's five. This one breaks the family shape deliberately:
regions are a PIXEL stage, not a compositing pass --- the user paints a
region, the editor applies a mosaic or a linear blur to that region only,
and the effect must see the FULLY graded pixels.

---

## Scope

A region-layer system in toadGALLERY Edit: paint a closed path over the
photo; the region under the path gets a mosaic (block average) or a
linear blur (horizontal box smear) at a strength; multiple regions, each
re-editable (kind, strength) until export; persisted additively in the
recipe; rendered identically by the preview and the export.

**In scope:** multiple regions; per-region kind (`MOSAIC` |
`LINEAR_BLUR`), strength (1..100), the painted point list; the paint
gesture; recipe persistence; undo/redo at per-commit granularity; the
in-pipeline stage.

**Deliberately out of scope (recorded follow-ups):** feathered/soft mask
edges (v1 is a hard binary mask); an arbitrary blur angle (the linear
blur runs horizontally --- the roadmap names "linear blur", v1 takes the
canonical direction; an angle slider is a recorded extension); a
Gaussian blur kind (the roadmap's two named kinds ship; Gaussian is the
recorded candidate); per-vertex region editing (the stroke family's
F-15 applies here too).

## The six design decisions worth making now

1. **Regions are the FINAL pixel stage, after sharpen.** The stage order
   grows: `… -> vignette -> sharpen -> mosaic/blur regions`. What the
   user censors is the fully graded, fully sharpened image --- no
   downstream stage re-processes the censored output (sharpening a
   mosaic edge would halo it). `EditPipeline.apply` gains the stage;
   `STAGE_ORDER` grows the tail (the existing stage-order assertion
   stays true --- the r37 test file pins the new tail).

2. **The grid-relative honesty, inherited from the vignette.** The
   region mask is fractions of the stage's grid. In the export, `apply`
   runs the geometry INCLUDING the crop, so the stage grid IS the output
   canvas and the mask is exact. In the preview, `apply` runs with the
   crop identity (the crop draws as the overlay rect), so with an
   ACTIVE crop the preview's region placement approximates --- exactly
   the characteristic the radial vignette's centre has carried since
   r30. The export is exact; the §26 device row verifies it.

3. **The mask is a pure scanline fill (even-odd).** For each scanline,
   the polygon edges crossing the pixel-centre line `y + 0.5` produce a
   sorted crossing list; pixel centres strictly between crossing pairs
   are in. Degenerate paths (< 3 points) mask nothing and the model
   refuses them (a region needs a painted AREA; a tap drops honestly
   --- the gesture does not commit under three samples). JVM-tested
   with exact pinned masks.

4. **The effects are integer-deterministic and exactly testable.**
   - `MOSAIC` (block average): cells of `cellPx = max(2,
     strengthPct × minEdge / 1000)` aligned to the grid origin; for
     each cell, the masked pixels average (integer floor division by
     the count) and the average writes back to exactly those pixels.
     Alpha untouched.
   - `LINEAR_BLUR` (horizontal box): `radiusPx = max(1,
     strengthPct × minEdge / 1000)`; for each masked pixel, the
     average of its row window `[x−r, x+r]` (clamped to the grid,
     divided by the clamped length) over a snapshot of the pre-pass
     state, written only to masked pixels. A sliding-window sum makes
     it O(row) per region regardless of radius.
   - Multiple regions apply in list order; a later region's effect
     reads the earlier region's output (last wins in an overlap).

5. **The recipe stays additive: `RegionLayers` optional, absent = clean.**
   The family tolerance contract, verbatim; the wire adds a `kind`
   (`"mosaic"` | `"linearBlur"`): an unknown kind is corrupt data ---
   the layer is skipped (never a crash); a blank id is the family skip.
   Points ride as 2-element arrays like the stroke family's, thinned at
   commit to the shared 512 cap by the same stride rule (the algorithm
   duplicated, not coupled --- the stores' convention, documented in
   both places).

6. **The UI follows the paint-mode pattern with a second mode.** A
   Regions section: the Region-mode chip (mutually exclusive with the
   Draw-mode chip --- one paint mode at a time, enforced at both
   toggles), the kind chips (Mosaic / Linear blur) and the strength
   slider under the duality (paint mode tunes the NEXT region; a
   selected region re-binds the same controls through the live-draft
   pattern), the region layer chips, remove. The gesture: with Region
   mode ON, the drag paints the path live (a transient draft region
   rides the effective recipe, so the preview shows the effect AT
   PAINT TIME through the same pipeline); release commits one region
   (≥ 3 samples) or drops honestly. Pending drafts are swept by the
   ONE `sweepPendingDrafts()` helper (extended by this round, per its
   r36 design).

## Tests the round owes (JVM, edit module)

- `RegionLayersTest` — model validation (ranges, the `r`-prefix
  `newId`, duplicate ids, the < 3-points refusal); the scanline mask
  (exact pinned masks: a triangle, a self-crossing bowtie, a
  degenerate path); the mosaic block average (exact pixels on a hand
  grid); the linear blur window (exact pixels, edge clamps); the
  sliding window's O(row) equivalence.
- `RegionLayersRecipeTest` — absent key (a pre-r37 sidecar carrying
  r34 text + r35 stickers + r36 strokes loads clean --- the round-four
  stacking proof); the lossless round-trip; the unknown-kind skip;
  points tolerance; clamps and defaults; duplicate-id structured
  failure.
- The stage-order tail + the regions-only identity: a recipe with only
  regions is NOT identity, and `apply` runs the stage (pinned by a
  small-grid pipeline test in `RegionLayersTest`).

## Device checklist rows (§26, human-only, later)

1. Paint three regions (a face-sized mosaic, a plate-number linear
   blur, an overlapping pair); TalkBack announces each control.
2. Full round trip Gallery→Edit→export→saved-copy: the regions censor
   the export EXACTLY (the §2 grid-relative honesty: with an active
   crop the preview approximates, the export is exact --- this row
   proves the export); the original is untouched (§19 hash rule).
3. Airplane mode for the whole flow (zero network by construction).
4. Process death mid-region-edit: the recipe restores the regions.

## No licensing gate

Parametric code only; no asset, no import. The roadmap's Phase 7 exit
criterion needs nothing from this round beyond project-authored code
(GPL-3.0 with the project).

---

## Delivery record (r37, 2026-11-04, branch `tg-p7-mosaic`)

The round landed every decision above, with these recorded deltas:

1. **The model — as designed.** `RegionLayer` (id, `RegionKind`,
   `strengthPct` 1..100, points 3..512) in `RegionLayers.kt`; the wire
   kinds `"mosaic"` / `"linearBlur"`; the `r`-prefix `newId`; the thinning
   rule duplicated from the stroke family (documented in both places).
2. **The mask — as designed.** The even-odd scanline fill with strict
   pixel centres: the triangle and the self-crossing bowtie are pinned
   by exact masks (15 and 12 pixels; the bowtie's middle pinch is
   test-pinned empty); a degenerate path masks nothing.
3. **The kernels — as designed, integer floor throughout.** The mosaic
   block average (origin-aligned cells, per-cell reads before writes)
   and the linear blur's sliding window (snapshot source, clamped
   window, floor division) are pinned by exact hand grids; the sliding
   window is pinned equivalent to a naive per-pixel loop on a
   deterministic LCG grid (r=3, w=12). The pre-push harness caught the
   usual single-arithmetic slips (the x=0 window is [0,1], not [0,2];
   a `DoubleArray` has no `chunked`; a missing `kind` argument) ---
   all test-side, the kernels were per spec.
4. **The pipeline stage — as designed.** Stage 13 after sharpen;
   `STAGE_ORDER` grew the tail (the pre-existing stage-order assertion
   stays true); the regions-only recipe is NOT identity and `apply`
   censors (pinned by the 4x4 pipeline test). The grid-relative honesty
   is the vignette's, verbatim in the stage's comment.
5. **The recipe — as designed.** Absent key = a pre-r37 sidecar loads
   clean (test-pinned with one carrying r34 text + r35 stickers + r36
   strokes --- the round-four stacking proof); the unknown-kind skip
   (`"gaussian"` and case-mismatched `"MOSAIC"` both skip --- corrupt
   data, never a crash); points tolerate like the stroke family's;
   strength clamps 1..100 with default 12.
6. **The UI — as designed, the second paint mode.** The Region-mode
   chip is mutually exclusive with the Draw-mode chip (enforced at both
   toggles); the kind chips + strength slider run under the paint
   duality; the draft region previews AT PAINT TIME (the transient
   draft rides the effective recipe through the same `apply()` stage
   the export runs); a tap or two-sample flick drops honestly (the
   commit refuses < 3 points). The selection unification extended to
   the fourth family: every selection point clears the region
   selection (the r35 contract, carried forward).
7. **Tests — the two owed families, all JVM.** `RegionLayersTest` (14:
   model, thinning, the pinned masks, the strength mapping, both
   kernels, the sliding equivalence, the pipeline stage, the order
   tail) + `RegionLayersRecipeTest` (12: the round-four stacking proof,
   the lossless round-trip, the unknown-kind skip, the under-three
   skip, points tolerance, clamps, defaults, duplicates, identity
   folding) --- **26 new tests; the edit module at 340** (r36's 314 +
   26). The local harness ran 333 pure tests green pre-push.

*The §26 device rows ride the checklist, human-only.*

*Delivered on the branch `tg-p7-mosaic`, cut from post-#111 main
(`8c466ee`), in the 2026-11-04 window after the r36 merge.*

---

## Delivery-record correction (r38's branch, 2026-11-04)

Item 6 above overclaimed: **the r37 UI section never landed in the
r37 branch.** The insertion script wrote `build.gradle.kts` (the
versionCode 10 bump) but never wrote `EditActivity.kt` back --- the
section existed only in a Python variable; the assert passed, the
print said "section+versionCode ok", and the file on disk kept its old
bytes. The merged r37 (#112 `6c206d9`) therefore carried the model, the
recipe, the pipeline stage, the gesture scaffolding and the mutual
exclusion --- everything except the surface: `regionMode` was
unreachable, no region chips or sliders existed, and the 8
`strings_r37.xml` entries shipped unused (unused strings don't fail
builds, which is why the merge-gate CI stayed green --- the CI compiled
what was there, honestly; it cannot test for absence).

**The fix rides the r38 branch as the recorded debt** (the repo's
delivery-record convention): the section now sits between the Drawing
section and HSL exactly as designed (decision 6), the versionCode 11
comment carries the story, and the r38 delivery record plus the worklog
Run #13 narrate it. The lesson joins the campaign's file-write discipline:
every script that mutates a file must write THAT file back --- the
version of this failure that matters is the one where the print lies.
