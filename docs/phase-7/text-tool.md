# The Text Tool — r34 Round Design (Phase 7, layer types 1 of 5)

**Status:** DELIVERED on `tg-p7-text-tool` (2026-11-04, the r34 round; see
the delivery record at the bottom). This doc was the design contract the
round implemented; the decisions below are the ones argued with and, where
reality disagreed, the delivery record records the deltas.

Phase 7's remaining task list names five layer types — text (OFL fonts),
stickers, freehand drawing, mosaic/blur regions, frames — "each a round".
Text goes first: it is the only one that needs third-party assets (fonts),
so it also carries the licensing work the other four can then inherit.

---

## Scope

One text-layer system in toadGALLERY Edit: add/edit/remove styled text
layers over the photo, persisted in the recipe, rendered identically by
the preview and the export, announced correctly under TalkBack.

**In scope:** multiple text layers; per-layer text, font, size, color,
opacity, rotation, position; recipe persistence; undo/redo; export
compositing; OFL font bundling + provenance.

**Deliberately out of scope (later rounds):** stickers, freehand,
mosaic/blur, frames; curved/text-on-path; per-glyph styling; system-font
picker; text shadows/strokes (revisit with frames — same "decoration"
family); RTL shaping beyond what `android.graphics.Paint` gives free.

## The five design decisions worth making now

1. **Fonts: OFL only, bundled verbatim, four of them.** The roadmap says
   "OFL fonts"; the provenance rule (roadmap v2.2 invariant; the ledger at
   `docs/licensing-and-attribution.md` §5) means every bundled font needs a
   recorded origin + license. Candidate set (all SIL OFL, all on Google
   Fonts; final pick is the next round's first verification):

   | Slot | Candidate | Why |
   |---|---|---|
   | Default humanist sans | **Noto Sans** (Regular + Bold) | The safe default; huge coverage |
   | Display serif | **Playfair Display** | High-contrast display face |
   | Handwriting | **Caveat** | The fun layer |
   | Mono | **Space Mono** | The technical look |

   Rules: bundle **unmodified** TTFs (no subsetting, no rename — keeps the
   OFL Reserved Font Name clause trivially satisfied), ship each font's
   `OFL.txt` beside it in assets, and add one provenance-ledger row per
   font (origin URL, version, license, fingerprint) — the exact pattern
   `bundled-luts.md` set for assets. Budget ~1.2 MB total for four.

2. **The recipe stays additive: `TextLayers` optional, absent = clean.**
   Same tolerance contract as r33's `LutSelection`: a pre-r34 sidecar
   (or an r34 sidecar from a build that never touched text) has no
   `textLayers` key and loads byte-clean. Per layer, v1 fields:
   `id`, `text`, `fontId`, `sizePct` (0..300, % of canvas min-edge),
   `colorArgb`, `opacity` (0..100), `rotationDeg`, `centerX`/`centerY`
   (fractions 0..1 of the output canvas). An **unresolvable `fontId`**
   round-trips verbatim and **degrades to the default font** at render
   time — never a load failure, never a silent drop.

3. **Text renders in OUTPUT space, last, above everything.** Pipeline
   placement: a new final stage **after** sharpen (13 of 13) — but
   implemented as a compositing pass, not a pixel stage: geometry,
   adjustments, LUT, vignette, sharpen all run first; text anchors are
   fractions of the **post-geometry export canvas**, so a crop/rotate
   never re-rotates the text's own angle. (Consequence: text does NOT
   move when you crop afterwards — its center is re-expressed in the new
   canvas. Acceptable v1 honesty; document it in the doc that replaces
   this one.)

4. **Undo/redo = the existing snapshot stack, unchanged.** Text add /
   per-layer edit / layer delete each push a new `EditSessionState`
   snapshot into the existing 64-deep stack. No new mechanism. The
   layer list is pure state; the doc the next round writes must pin the
   snapshot granularity (per-gesture vs per-control-commit — recommend
   per-commit, matching the sliders).

5. **The UI follows r32/r33 conventions exactly.** A Text chip (Filters
   row family) → the layer list (bottom sheet or row, like the import
   chip row) → per-layer controls: text field, font chip row (bundled
   four), size/opacity/rotation sliders (live-drag + `semantics`
   announcements: "Text size, 120 percent"), color swatches. Every
   string lands in `strings_r34.xml`; the module string-ref count moves
   74 → ~90 and must be recounted in the round doc.

## Tests the next round owes (JVM, edit module)

- `TextLayoutTest` — anchor fractions × canvas → pixel rect; rotation
  about the center; clamping at the canvas edge.
- Recipe round-trip: `TextLayers` absent (pre-r34 sidecar) loads clean;
  full round-trip; unresolvable `fontId` degrades + round-trips verbatim.
- `FontRegistryTest` — bundled ids unique/ordered; broken asset skips,
  never throws (the `BundledLuts` pattern).
- Compositing: draw order (layers in list order, top last), opacity,
  and the preview ≡ export identity (same code path, no second renderer).

## Device checklist rows (§23, human-only, later)

1. Add two text layers, different fonts; TalkBack announces each control.
2. Full round trip Gallery→Edit→export→saved-copy: text renders in the
   export; original untouched (§19 hash rule still holds).
3. Airplane mode for the whole flow (fonts are local; zero network by
   construction, but the row proves it).
4. Process death mid-text-edit: the recipe restores the layers.

## Licensing gate (blocks the round, do first)

Per-font ledger rows in `docs/licensing-and-attribution.md` §5 + each
`OFL.txt` committed beside its TTF. If any candidate's license cannot be
verified from the downloaded artifact itself, that font is out — swap to
another OFL face; the design above is font-agnostic.

---

## Delivery record (r34, 2026-11-04, branch `tg-p7-text-tool`)

The round landed every decision above, with these recorded deltas:

1. **Fonts — the Noto Sans slot swapped for Fira Sans.** The prep doc's
   candidate rule ("bundle unmodified TTFs, no subsetting") collided with
   reality twice for Noto Sans: its only unmodified full form in
   `google/fonts` is the **2,049,096-byte `wdth,wght` variable** (over the
   round's whole budget alone), and the Google Fonts CSS API serves
   **~28 KB latin subsets** (cmap 300 B — subsetting is exactly what the
   contract forbids). The slot's requirement is a *default humanist sans*:
   **Fira Sans Regular** fills it (full static, 457 KB, OFL 1.1). The
   "(Regular + Bold)" second weight was dropped from every face — the v1
   layer model carries no weight axis and `Paint.setFakeBoldText` covers
   future emphasis without a second file. All four faces verified from
   their own downloaded artifacts (SIL OFL 1.1 headers, family/copyright
   identification); the full evidence is `docs/phase-7/bundled-fonts.md`
   + the four §5 ledger rows. Committed bundle: **1,260,724 bytes**.
2. **`TextLayers` additive recipe — as designed.** Absent key = a pre-r34
   sidecar loads clean (test-pinned); an unresolvable `fontId` round-trips
   verbatim and degrades to the default face at render (never a load
   failure, never a silent drop); blank-`id` entries are the one skip
   (a layer without identity cannot be re-targeted by the UI, drag or
   undo/redo). Wire colors are 8-hex-digit `AARRGGBB`; the color's own
   alpha byte is authored data and multiplies with the opacity slider at
   render (`(a·opacity + 50) / 100`, half-up — test-pinned).
3. **Output-space compositing — as designed.** `TextCompositor` runs
   AFTER the whole pixel pipeline (geometry → … → sharpen), not as a pixel
   stage; the preview draws the same specs over the CROP REGION inside the
   post-geometry frame and the export over the full output at origin 0 —
   one code path, and `TextLayersTest` pins the offset identity (preview
   center = export center + crop origin, identical size/alpha/face).
4. **Undo/redo — as designed, granularity pinned: per-commit.** Every
   text control commits exactly once per interaction: sliders on release
   (the live-draft pattern), the text field on IME Done / target switch /
   add / remove / before export (per-keystroke commits would flood the
   64-deep stack), the position drag on release. Pending drafts are
   swept before undo/redo and export — a draft is never silently lost.
5. **UI — as designed plus a position gesture.** Add chip → layer chips
   (showing their text) → text field / font chip row / size (0–300) /
   opacity (0–100) / rotation (−180–180) sliders (the r32 semantics
   conventions) / eight named color swatches (radio semantics) / remove.
   **Position** is a drag gesture on the preview (48 dp grab radius
   around the selected layer, live-drafted, one commit on release).
   **Recorded nit:** position has no slider, so it is not
   TalkBack-reachable in v1 — the a11y position control rides a later
   round with the other gesture-based interactions.
6. **The string recount (the r33-era debt).** `strings_r34.xml` adds **20**
   strings; the edit module's string-ref count moves **74 → 94**
   (strings.xml 46 + strings_r32 16 + strings_r33 12 + strings_r34 20).
   The prep estimate was "~90" — the delta is the eight named color
   swatches (a11y names) minus the four font names (proper nouns ride the
   `BundledFonts` registry, not resources).
7. **Tests — the four owed families, all JVM.** `TextLayersTest`
   (anchors/origin/order/degradation/rotation/alpha/em-size + the
   preview≡export offset identity), `TextLayersRecipeTest` (absent-key
   clean, lossless round-trip, the fontId tolerance contract, clamps,
   skips, defaults, duplicate-id structured failure),
   `BundledFontsTest` (registry self-consistency, the four committed
   sha256 pins, OFL.txt-beside-TTF, the size budget). The fourth family —
   compositing draw order / opacity — lives in the pure layout specs the
   compositor executes (`TextLayersTest` pins order + alpha; the
   Paint/Canvas pass is the thin shared executor).
8. **PR #108 (the redundant `tg-p7-lut-import` PR) closes with this
   round**: its import flow is already merged in #107's window extension,
   and this branch's merge commit carries `Closes #108`.

*Prepared in the 15-minute slice after the r33 interleaving recovery, on
the branch whose tree matched the documented state at `1439c8c`. Delivered
by the r34 round above.*
