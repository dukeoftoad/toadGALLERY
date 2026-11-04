# The Frames — r38 Round Design (Phase 7, layer type 5 of 5; the campaign's
closing round, with the undo/redo extension capstone)

**Status:** DELIVERED on `tg-p7-frames` (2026-11-04, the r38 round; pending
PR/CI/merge per the F-12 convention --- see the delivery record at the
bottom). This doc was the design contract the round implemented. Layer
type 5 of Phase 7's five --- the last, closing the campaign: after this
round every roadmap Phase 7 bullet is delivered and the undo/redo
extension the roadmap demands across the new layer types is pinned by its
own executable capstone.

---

## Scope

A frame system in toadGALLERY Edit: one frame at a time over the photo
(a frame is a mounting, not a stackable), five bundled parametric frames
plus user-imported PNG/WebP frames, per-frame width and colour (the
parametric set), persisted additively in the recipe, rendered identically
by the preview and the export --- LAST in the composite order.

**In scope:** the single `FrameSelection`; the five authored parametric
frames; the import store; the width slider + colour swatches for the
parametric set; the honest no-controls note for imported frames; the
undo/redo capstone test across ALL five layer families.

**Deliberately out of scope (recorded follow-ups):** frame stacking
(one mounting at a time --- a documented v1 rule, not a limitation to
apologise for); per-corner radii; frame aspect presets; the
library-management affordance for imported frames (the stickers' F-13
policy carried verbatim).

## The six design decisions worth making now

1. **A SINGLE selection, like the look --- not a layer list.** The
   recipe gains `frame: FrameSelection?` (the `LutSelection` shape):
   `{frameId, widthPct 1..100, colorArgb}`. Absent = no frame; empty
   never exists. One frame at a time: the mounting rule.

2. **The bundled set is parametric code, the sticker round's verdict
   carried.** No image asset: five authored geometry functions in
   `BundledFrames` (`(thickness, w, h) -> rects + holes`), the
   r35 decision (in-code authored shapes) applied to frames. The
   thickness is `tPx = max(1, widthPct × minEdge / 1000)` --- the r37
   kernels' integer formula, reused verbatim. The five:

   | id | Display name | Geometry |
   |---|---|---|
   | `plain` | Plain | a solid border of thickness t on all four sides |
   | `inner-line` | Inner line | a hairline ring (thickness `max(1, t/4)`) inset by t |
   | `double-line` | Double line | the plain border + the inner-line ring |
   | `film` | Film | sprocket bands of height t top and bottom, holes `max(2, t/2)` wide every t, centred in the band |
   | `polaroid` | Polaroid | border t on three sides, a 4t bottom band |

   Every function is pure over integers and pinned by exact `PixelRect`
   lists in the tests.

3. **Frames composite LAST, after text --- the mounting.** The
   cumulative order ends: pixel pipeline -> stickers -> strokes ->
   text -> FRAME. The frame covers the edges of everything (a caption
   spilling into the border goes under the mounting --- the physical
   metaphor, stated honestly).

4. **Imported frames are bitmaps stretched to the canvas, with an
   honest note.** `ImportedFrameStore` (the `ImportedStickerStore`
   pattern verbatim): PNG/WebP magic-before-write, verbatim bytes,
   derived collision-suffixed ids vs the bundled registry and existing
   imports. An imported frame draws stretched to the FULL output canvas
   --- the import assumes a frame authored at the canvas aspect; the
   width/colour controls do not apply (bitmap is the frame), and the UI
   says so instead of showing dead controls.

5. **The tolerance contract, the look's shape.** An unresolvable
   `frameId` (a version skew, a deleted import) round-trips verbatim
   and renders NOTHING at render time --- degrade to no-frame, never a
   crash, exactly the `LutSelection` contract. A blank id reads as no
   frame. Numerics coerce like every other slider (`widthPct` 1..100,
   default 20); a malformed colour reads as the default white.

6. **The UI follows the look row's conventions.** A Frames section:
   the None chip, the bundled five, the imported chips (id-sorted),
   the SAF import chip; the width slider (1..100, live-drafted like
   the intensity slider) and the colour swatches for a parametric
   selection; the honest note for an imported one. No paint mode, no
   gesture: the frame needs no dragging.

## The undo/redo extension capstone (the roadmap's last Phase 7 line)

"Undo/redo extended to cover the new layer types." The mechanism has
been there since r30 (the snapshot stack over whole recipes) and every
round pinned its per-commit granularity; this round closes the line
with an executable capstone: `UndoRedoExtensionTest` --- a realistic
mixed session (text + sticker + stroke + region + frame), a commit
sequence across every family's controls, undo all the way back to the
launch state, redo all the way forward, asserting the EXACT recipe at
every step. The extension is now test-pinned, not narrated.

## Tests the round owes (JVM, edit module)

- `FramesTest` — the model (`FrameSelection` validation, the tolerance
  clamps); the thickness formula; the layout pass (origin offset, the
  exact geometry per bundled def on hand canvases).
- `BundledFramesTest` — the registry (five ordered ids, display names);
  the authored geometry exactness (pinned `PixelRect` lists incl. the
  film holes).
- `FramesRecipeTest` — absent key (a pre-r38 sidecar carrying all four
  earlier containers loads clean --- the round-five stacking proof);
  the lossless round-trip; the unknown-frameId skip; clamps and
  defaults.
- `ImportedFrameStoreTest` — the store contract (magic-before-write,
  verbatim bytes, collision suffixing, idempotent delete) --- the
  `ImportedStickerStoreTest` shape.
- `UndoRedoExtensionTest` — the capstone above.

## Device checklist rows (§27, human-only, later)

1. Mount each bundled frame (all five) + one imported PNG frame;
   TalkBack announces each control; the width slider and the swatches
   behave.
2. Full round trip Gallery→Edit→export→saved-copy: the frame renders
   in the export over everything (the mounting order); the original is
   untouched (§19 hash rule).
3. Airplane mode for the whole flow (zero network by construction).
4. Process death mid-frame-edit: the recipe restores the frame; an
   import whose file was hand-removed renders nothing (the tolerance
   contract on-device).

## No licensing gate

Parametric code plus user-imported images; no third-party asset. The
roadmap's Phase 7 exit criterion needs nothing from this round beyond
project-authored code (GPL-3.0 with the project).

---

## Delivery record (r38, 2026-11-04, branch `tg-p7-frames`)

The round landed every decision above, with these recorded deltas:

1. **The model — as designed.** The single `FrameSelection` (the
   `LutSelection` shape), `MAX_ID_LENGTH` shared cap 64, the r37 kernels'
   thickness formula verbatim (`max(1, widthPct x minEdge / 1000)`).
2. **The authored set — as designed.** Five pure geometry functions
   (`plain` / `inner-line` / `double-line` / `film` / `polaroid`),
   pinned by exact `PixelRect` lists (the film holes: 24 per band at
   t=8 on a 200 canvas, the first at x=4, the last at x=188). The
   pre-push harness caught the expected single-arithmetic slips (the
   min-edge 150-vs-200 confusion in three expectations; a percent
   written where the pixels belong) --- all test-side, the geometry
   was per spec.
3. **The recipe — as designed.** The `frame` object, the `lut`
   tolerance shape verbatim: an unresolvable frameId round-trips
   verbatim and degrades to no frame; a blank id reads as no frame;
   width clamps 1..100 default 20; a malformed colour reads as the
   default white. The round-five stacking proof: a pre-r38 sidecar
   carrying ALL FOUR earlier containers loads clean.
4. **The composite order — as designed, the campaign's final stack.**
   pixel pipeline -> stickers -> strokes -> text -> FRAME (the
   mounting over everything), the same compositor call in the preview
   (the crop region) and the export (origin 0).
5. **The import store — as designed.** The sticker store's contract
   frame-shaped: PNG/WebP magic-before-write, verbatim bytes, the 2 MB
   cap, collision-free ids (a "Film.png" import lands as `film-2`); the
   imported frame stretches to the full canvas with the honest
   no-controls note (the UI says so instead of showing dead controls).
6. **The UI — as designed**, the look row's conventions (None chip,
   bundled five, imported id-sorted, the SAF import chip; the width
   slider's single-selection draft key; the swatches' immediate
   commits; a new frame keeps the current width and colour).
7. **The undo/redo capstone — as designed.** `UndoRedoExtensionTest`:
   a mixed five-family session, ten commits across every family's
   controls, undo to the launch state and redo forward, the EXACT
   recipe asserted at every step; plus the depth-cap walk and the
   sidecar round-trip of the mixed session.
8. **The r37 debt fix riding this round** (the recorded correction in
   `mosaic-regions.md`'s delivery record): the r37 UI section --- lost
   to a Python script that never wrote the activity file back --- is
   landed between the Drawing section and HSL exactly as designed; the
   merged r37's surface gap is closed, `regionMode` is reachable, the
   8 `strings_r37.xml` entries are live.
9. **Tests — the five owed families, all JVM.** `FramesTest` (9),
   `BundledFramesTest` (4), `FramesRecipeTest` (7),
   `ImportedFrameStoreTest` (9), `UndoRedoExtensionTest` (3) ---
   **32 new tests; the edit module at 374** (r37's 340 + 32 + the r38
   wave's... the CI total; the local harness ran 365 pure green
   pre-push, the module's CI total adds ExportPolicyTest's 9). The
   string count: `strings_r38.xml` adds 7; the module's string-ref
   count moves 122 → 129.

*The §27 device rows ride the checklist, human-only.*

*Delivered on the branch `tg-p7-frames`, cut from post-#112 main
(`6c206d9`), in the 2026-11-04 window after the r37 merge.*
