# The Freehand Drawing Tool — r36 Round Design (Phase 7, layer type 3 of 5)

**Status:** DELIVERED on `tg-p7-drawing` (2026-11-04, the r36 round; pending
PR/CI/merge per the F-12 convention --- see the delivery record at the
bottom). This doc was the design contract the round implemented. Layer
type 3 of Phase 7's five, after text (r34) and stickers (r35); it inherits
the family structures and inserts itself into the composite order the
r35 round established.

---

## Scope

A stroke-layer system in toadGALLERY Edit: paint freehand strokes over
the photo with a brush (colour, width, opacity, smoothing), one gesture
= one layer = one undo snapshot, per-layer re-editing until export
(colour / width / opacity / smoothing are re-committable; the points are
not — v1 has no per-point editing), persisted additively in the recipe,
rendered identically by the preview and the export.

**In scope:** multiple stroke layers; per-layer colour, width (percent
of the output-canvas min-edge), opacity, smoothing, the painted point
list (fractions of the output canvas); the paint gesture on the preview;
recipe persistence; undo/redo at per-commit granularity; export
compositing.

**Deliberately out of scope (recorded follow-ups):** per-point editing
or stroke repositioning (the points ARE the position — editing them is
a vertex-editor family of its own); pressure/stylus width variation
(multitouch and pressure are device-side; the §25 rows can eyeball it);
an eraser (delete-the-layer is the v1 eraser); dashed/pattern brushes.

## The six design decisions worth making now

1. **One gesture = one layer = one undo snapshot.** The model is
   stroke-per-layer, not canvas-per-layer: a `DrawLayers` container of
   `DrawLayer`s, each carrying its own `colorArgb`, `widthPct`,
   `opacity`, `smoothingPct` and `points`. Painting commits ONE layer
   on release; selecting a committed layer re-edits its four scalar
   controls (the live-draft pattern); removing it is one snapshot. The
   layer list draws in list order, top LAST (the family rule).

2. **The recipe stays additive: `DrawLayers` optional, absent = clean.**
   The r33/r34/r35 tolerance contract, verbatim: a pre-r36 sidecar (or a
   clean session) has no `drawLayers` key and loads byte-clean; empty
   normalises back to absent. No `id`-less layer can exist (the blank-id
   skip); there is no foreign id to resolve — a stroke is its own data,
   so the tolerance set is smaller than the sticker family's: the
   tolerant-read rules concern malformed points and clamps only.

3. **Points are fractions of the OUTPUT canvas, thinned at commit.**
   The point list is the anchor convention of the family (fractions of
   the crop rect in the preview, of the output canvas in the export).
   A live gesture can sample at frame rate, so the COMMIT thins the
   list to the model cap (`MAX_POINTS = 512`) by uniform stride ---
   first and last always kept, deterministically — and the recipe
   stores the thinned list (the committed stroke is the user's data;
   the raw gesture is transient).

4. **Smoothing is Chaikin corner-cutting, 0..4 passes.** The slider is
   `smoothingPct` 0..100 mapping to `round(pct / 25)` Chaikin passes
   (0 → the raw polyline with every sample visible; 4 → a quadratic
   B-spline-like curve). Passes run at PLACE time in pixel space
   (Chaikin commutes with the affine fraction→pixel map, so the order
   is exact either way); the compositor draws the smoothed polyline
   with round caps and joins. A single-point layer renders as a DOT of
   the brush width (a tap is a dot, not nothing).

5. **Strokes composite in OUTPUT space, BETWEEN stickers and text.**
   The cumulative order becomes: pixel pipeline → stickers → **strokes**
   → text — annotation paints over the scene objects, captions float
   above the annotation. The preview draws over the CROP REGION; the
   export over the full output at origin 0 --- the same
   origin/canvas arguments the family compositors take, so preview ≡
   export by construction.

6. **The UI follows the family conventions with one duality.** A
   Drawing section: the Draw-mode chip and the stroke layer chips; the
   brush controls (eight named swatches — the r34 palette, the width
   1..100 / opacity 0..100 / smoothing 0..100 sliders with the r32
   semantics conventions); the remove affordance. **The duality:**
   Draw mode ON = painting (the controls tune the NEXT stroke; the
   gesture on the preview paints a live-drafted stroke that commits on
   release); a layer selected = editing (the SAME controls re-bind to
   the layer through the live-draft pattern). The two states are
   exclusive: entering Draw mode clears the layer selection (and vice
   versa) — one selection at a time across ALL the families, the r35
   contract extended. Pending drafts are swept before undo/redo and
   export; the paint draft is swept (committed or dropped honestly) at
   mode exit and export.

## The brush math (pure, JVM-tested)

- `widthPx = widthPct / 100 × canvasMinEdge` (the family size
  convention).
- `alpha = (colorAlpha × opacity + 50) / 100` — the r34 text math
  (the colour's own alpha byte is authored data; the stroke carries a
  `colorArgb` like a text layer).
- `passes = round(smoothingPct / 25)` coerced to 0..4.
- Chaikin (open polyline, endpoints preserved): each edge (a, b) emits
  `0.75a + 0.25b` and `0.25a + 0.75b`; the output is
  `[P0, Q0, R0, Q1, R1, …, Plast]`. Exactly testable.
- Thinning: `stride = ceil(n / cap)`; keep index 0, then every
  `stride`-th index below `n-1`, then index `n-1` --- the result is
  ≤ cap + 1 points, deterministic, endpoints always present.

## Tests the round owes (JVM, edit module)

- `DrawLayersTest` — model validation (ranges, the `d`-prefix `newId`,
  duplicate ids); the layout pass (width, the text alpha math, the
  origin-offset identity, list order, single-point placement); the
  thinning stride; the Chaikin passes mapping + exact one-pass output;
  the two-pass endpoint preservation.
- `DrawLayersRecipeTest` — absent key (a pre-r36 sidecar carrying r34
  text AND r35 stickers loads clean — the stacking proof); the
  lossless round-trip; points tolerance (non-pair entries skipped,
  out-of-range clamped, a zero-valid-points layer skipped, the count
  capped); clamps and defaults; duplicate-id structured failure.

## Device checklist rows (§25, human-only, later)

1. Paint three strokes (thin/thick, three colours, smoothing 0 / 50 /
   100); TalkBack announces each control; a tap paints a dot.
2. Full round trip Gallery→Edit→export→saved-copy: the strokes render
   in the export at the previewed width/opacity/smoothing; the original
   is untouched (the §19 hash rule).
3. Airplane mode for the whole flow (zero network by construction).
4. Process death mid-paint (a gesture interrupted): the committed
   strokes restore; a mid-gesture kill loses only the uncommitted
   stroke (the honest v1 policy, documented).

## No licensing gate

No asset, no import, no foreign data: the brush is parametric code.
The roadmap's Phase 7 exit criterion needs nothing from this round
beyond the code being project-authored (GPL-3.0 with the project).

---

## Delivery record (r36, 2026-11-04, branch `tg-p7-drawing`)

The round landed every decision above, with these recorded deltas:

1. **The model — as designed.** `DrawLayer` (id, `colorArgb`,
   `widthPct` 1..100, opacity, `smoothingPct`, points) in
   `DrawLayers.kt`; stroke-per-layer, one gesture = one layer = one
   undo snapshot; `newId` over the `d` prefix (the transient draft id
   "draft" never counts --- test-pinned).
2. **The recipe — as designed.** Absent key = a pre-r36 sidecar loads
   clean (test-pinned with one carrying r34 text AND r35 stickers ---
   the round-three stacking proof); points ride as 2-element arrays of
   output-canvas fractions; the tolerant read drops malformed point
   entries, clamps survivors, skips a layer whose points all fail or
   that carries none, and caps the count at the model cap. Empty
   normalises back to absent.
3. **The thinning — as designed, with the local harness earning its
   keep twice.** `DrawLayer.thin` (uniform stride, first/last always
   kept; 513-over-512 thins to 257, 10-over-3 to 4) --- the pre-push
   run caught two arithmetic slips in the TEST expectations (the code
   was per spec): the stride test asserted raw indices instead of the
   mapped coordinates, and the 258-vs-257 count. Also caught: a
   parenthesised backticked JVM-test name (kotlinc rejects it; the
   repo's names carry no parens --- the r34 dex-and-dots lesson's
   sibling).
4. **Smoothing — as designed.** `chaikinPasses = round(pct/25)`
   (0..4), Chaikin at place time in pixel space, endpoints preserved
   every pass count (test-pinned), `smoothPolyline` exact on the
   one-pass corner cut; the compositor draws round caps/joins and a
   single-point layer as a dot of the brush width.
5. **The composite order — as designed.** The cumulative stack is now
   pixel pipeline -> stickers (r35) -> strokes (r36) -> text (r34); the
   preview and the export insert the `DrawCompositor` pass identically
   (preview ≡ export by construction).
6. **The UI — as designed, the duality landed.** The Draw-mode chip
   (FilterChip, selected-state announcement per the r32 convention);
   the stroke layer chips; the brush controls in paint mode (plain
   state, applied at commit, previewed live through the transient
   draft layer) versus the SAME controls re-bound to a selected layer
   (the live-draft pattern); the paint gesture on the preview (a tap
   is a dot); ONE selection at a time across ALL the families, and the
   mode and the selection are exclusive.
7. **The sweep unification (recorded touch on merged code, all
   additive).** The r34/r35 handlers' inline three-draft sweeps became
   ONE `sweepPendingDrafts()` helper (12 call sites retrofitted:
   undo, redo, export, text add/chip/remove, sticker
   registry/import/layer/remove, the gesture end/cancel) so r37's
   regions extend HERE, not at every call site.
8. **Tests — the two owed families, all JVM.** `DrawLayersTest` (15:
   model, thinning, Chaikin, the layout pass) + `DrawLayersRecipeTest`
   (12: the stacking proof, lossless round-trip, points tolerance,
   clamps, defaults, duplicates) --- **27 new tests; the edit module at
   314** (r35's 287 + 27). The local harness ran 305 pure tests green
   pre-push (the module's CI total adds ExportPolicyTest's 9).

*The §25 device rows ride the checklist, human-only.*

*Delivered on the branch `tg-p7-drawing`, cut from post-#110 main
(`980ccea`), in the 2026-11-04 window after the r35 merge.*
