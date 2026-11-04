# The Sticker Tool — r35 Round Design (Phase 7, layer type 2 of 5)

**Status:** DELIVERED on `tg-p7-stickers` (2026-11-04, the r35 round; pending
PR/CI/merge per the F-12 convention --- see the delivery record at the
bottom). This doc was the design contract the round implemented; the
decisions below are the ones argued with and, where reality disagreed,
the delivery record records the deltas. It follows the r34 text tool,
whose structures it inherits wherever they exist.

---

## Scope

A sticker-layer system in toadGALLERY Edit: stamp bundled or
user-imported PNG/WebP stickers over the photo, per-layer
size/opacity/rotation/position, persisted additively in the recipe,
rendered identically by the preview and the export, one selected layer
at a time across the layer families, announced correctly under TalkBack.

**In scope:** multiple sticker layers; per-layer sticker id, size,
opacity, rotation, position; a bundled set of eight project-authored
vector stickers; a user-import store for PNG/WebP; recipe persistence;
undo/redo at per-commit granularity; export compositing; the licensing
authorship record for the bundled set.

**Deliberately out of scope (later rounds / recorded follow-ups):**
freehand drawing (r36), mosaic/blur (r37), frames (r38); flipping a
sticker layer; per-sticker recolouring; a library-management affordance
for imported stickers (delete exists in the store and is JVM-tested, but
no UI surfaces it yet — a hand-edited sidecar is the only way to remove
a library entry today); stroke/shadow decorations (the same "decoration
family" the text round deferred).

## The six design decisions worth making now

1. **The bundled set is AUTHORED IN CODE, not committed image assets.**
   The roadmap requires "small bundled set (CC0 or project-authored)".
   The r33/r34 precedent generated data files (`.cube`, TTF) because the
   data was genuinely file-shaped. Stickers fail that test: a committed
   PNG bundle would need a deterministic generator whose zlib output is
   stable across zlib versions (it is not, in general) or ~2 MB of
   stored-deflate payload for eight 256×256 RGBA sheets. Instead the
   eight stickers are **pure Kotlin shape definitions** — integer
   coordinates in a 0..100 design box, fills only, no strokes — exactly
   the "project-authored" the roadmap names, with zero external
   provenance to audit: the shapes are project code, GPL-3.0 with the
   project, no third-party asset, no §5 ledger row, no per-file license.
   The authorship record is `docs/phase-7/bundled-stickers.md` (the
   `bundled-luts.md` role, adapted for authored code). APK cost: a few
   hundred integers.

2. **The recipe stays additive: `StickerLayers` optional, absent = clean.**
   The r33/r34 tolerance contract, verbatim: a pre-r35 sidecar (or a
   clean session) has no `stickerLayers` key and loads byte-clean; empty
   normalises back to absent. Per layer, v1 fields: `id`, `stickerId`,
   `sizePct` (0..300, % of the output-canvas min-edge; the box maps to a
   square of that edge), `opacity` (0..100), `rotationDeg` (−180..180),
   `centerX`/`centerY` (0..1 fractions of the output canvas). No colour
   field: a sticker carries its own palette. An **unresolvable
   `stickerId`** (a version skew, a hand-edited sidecar, a deleted
   import) round-trips verbatim and **renders NOTHING at render time** —
   there is no "default sticker" to degrade to, and faking one would
   draw something the user never chose. Never a load failure, never a
   crash. A blank `id` is the skip (the r34 rule); a blank `stickerId`
   is a skip too — with no default shape, "no sticker chosen" cannot be
   rendered.

3. **Stickers composite in OUTPUT space, BELOW text.** The cross-type
   order this round establishes: pixel pipeline (geometry → adjustments
   → LUT → vignette → sharpen) first, then `StickerCompositor`, then the
   r34 `TextCompositor` — stickers are scene objects, captions float
   above them. Within the family, layers draw in list order, top LAST
   (the r34 rule). The preview draws over the CROP REGION inside the
   post-geometry frame; the export over the full output at origin 0 —
   the same `origin/canvas` arguments `TextCompositor` takes, so preview
   ≡ export by construction. Later rounds insert their families into
   this order and the round docs record the cumulative stack.

4. **Two resolution kinds, one resolver.** A sticker id resolves to
   EITHER a bundled vector shape (the registry) OR a user-imported
   bitmap (the store, decoded once per registry load). The compositor
   takes a single `resolver: (String) -> Resolved?`; the activity
   supplies bundled-first, imported-second (the store's collision rule
   guarantees disjoint ids). A vector draws from its op list scaled into
   the size box; a bitmap scales to FIT inside the box preserving its
   aspect (a non-square import lands as itself, not stretched).

5. **Undo/redo = the existing snapshot stack, granularity per-commit.**
   Sticker add / per-layer edit / drag-release / layer delete each push
   exactly one snapshot; live drafts (slider drags, the position drag)
   preview through `effectiveRecipe()` and commit once, on release —
   the r34 pattern, no new mechanism. Pending drafts are swept before
   undo/redo and export (a draft is never silently lost).

6. **The UI follows the r32/r33/r34 conventions exactly.** A Stickers
   section: the registry chip row (bundled eight, then imported
   id-sorted, then the SAF import chip for `image/png` + `image/webp`)
   — tapping a chip ADDS a layer at canvas centre; then the layer chips
   (their sticker's display name, the numbered fallback for blanks);
   then per-selected-layer controls: size (0..300) / opacity (0..100) /
   rotation (−180..180) sliders (the r32 semantics conventions), remove,
   and drag-to-position on the preview (the r34 gesture, 48 dp grab
   radius, live-drafted, one commit on release). **One selected layer at
   a time across the families**: selecting a sticker layer clears the
   text selection and vice versa — the drag target and the per-layer
   controls follow the single selection (the recorded r34 ambiguity:
   two live selections would fight over the gesture). Every string
   lands in `strings_r35.xml`; the module string count moves 94 → 106
   and is recounted in the delivery record.

## The bundled eight (the authored set)

| Registry id | Display name | Parts (draw order, top last) |
|---|---|---|
| `heart` | Heart | red heart silhouette (4 cubics, one path) |
| `star` | Star | yellow 5-point star (10-line polygon, outer R 48 / inner r 20 about (50,50)) |
| `smiley` | Smiley | yellow 24-gon face (r 46); two black 12-gon eyes (r 6 at (36,40)/(64,40)); black half-disc smile (arc r 30 from 20°..160°, chord-closed) |
| `bubble` | Speech bubble | white rounded octagon (18..82 × 18..64, 10-unit corner cuts); white tail triangle (38,63)-(58,63)-(40,88) — one merged white silhouette |
| `bolt` | Lightning | yellow 6-point zigzag polygon (60,4)(24,54)(44,54)(36,96)(76,40)(54,40), chord-closed |
| `flower` | Flower | five pink 12-gon petals (r 18, centres at radius 22, 72° apart about (50,50)) in one part; yellow 12-gon centre (r 13) on top |
| `pin` | Location pin | red teardrop (24-gon head r 26 about (50,40), continuing to the tip at (50,96)); white 12-gon inner circle (r 11) on top |
| `crown` | Crown | yellow 6-point crown polygon (spikes at 22/50/78 × 36/28/36, valleys at 38/62, base at 74); three white 12-gon gems (r 5 at the spike tops) on top |

All coordinates are integers in the 0..100 design box (y down, screen
convention); every circle in the set is a regular N-gon (N = 12 or 24)
computed from the centre/radius formula, and every polygon point is the
rounded trigonometric value — the formulas are the authorship record,
`BundledStickersTest` pins the structural invariants and spot
coordinates. The palette (Material tones): red `0xFFE53935`, yellow
`0xFFFDD835`, pink `0xFFF06292`, near-black `0xFF212121`, white
`0xFFFFFFFF`.

## The import store (the `ImportedLutStore` pattern, image-shaped)

`ImportedStickerStore(dir)`: SAF-picked PNG/WebP bytes stored VERBATIM
(never re-encoded) in the editor's app-private `imported-stickers/`
directory. The parse-before-write step is a **magic-byte + size check**
(the store never decodes images — that is the platform's job at render
time, and a corrupt payload decodes to null and is skipped, never
thrown): PNG requires the 8-byte signature `\x89PNG\r\n\x1a\n`; WebP
requires `RIFF` at 0..3 and `WEBP` at 8..11; anything else, or over the
2 MB cap, is REJECTED before any write — a rejection leaves the store
byte-identical. The id is DERIVED (slug of the picked file-name stem,
`[a-z0-9-]`, ≤40 chars), collision-suffixed against the bundled registry
AND existing imports (`-2`, `-3`, …) so an import can never shadow the
bundled set; the file keeps its magic's extension (`$id.png` /
`$id.webp`). The directory listing IS the index; delete is idempotent
and path-safe. No UI affordance surfaces delete yet (decision 1's
follow-up); the store API is complete and JVM-tested so the affordance
is additive later.

## Tests the round owes (JVM, edit module)

- `StickerLayersTest` — model validation (ranges, duplicate ids,
  `newId` over the `s` prefix); the layout pass (box scaling = the
  size-percent identity, centre anchoring, origin offset, list order,
  alpha = opacity-only `(opacity·255 + 50) / 100` half-up).
- `StickerLayersRecipeTest` — absent key (a pre-r35 sidecar carrying
  r34 text layers loads clean with stickers absent); lossless
  round-trip; the stickerId tolerance contract (unresolvable kept
  verbatim, render-skip); clamps; blank-id and blank-stickerId skips;
  duplicate-id structured failure.
- `BundledStickersTest` — registry self-consistency (8 unique ordered
  ids, display names); every shape structurally valid (coordinates in
  0..100, subpaths MoveTo-started and Close-ended, ≥3 ops); spot
  coordinates pin the authored trigonometry (safe rounding margins).
- `ImportedStickerStoreTest` — magic validation (PNG/WebP accepted,
  garbage and over-cap rejected, store byte-identical after a
  rejection); verbatim bytes; id derivation and collision suffixing vs
  the bundled registry and existing imports; idempotent delete; sorted
  listing.

## Device checklist rows (§24, human-only, later)

1. Stamp three stickers (bundled + one imported PNG + one imported
   WebP); TalkBack announces each control; the drag gesture
   repositions the selected layer.
2. Full round trip Gallery→Edit→export→saved-copy: the stickers render
   in the export at the previewed position/size/rotation/opacity; the
   original is untouched (the §19 hash rule).
3. Airplane mode for the whole flow (zero network by construction; the
   row proves it).
4. Process death mid-sticker-edit: the recipe restores the layers; an
   import whose file was hand-removed from app-private storage renders
   nothing and never crashes (the tolerance contract on-device).

## Licensing gate (the record, not a blocker)

No third-party asset is bundled: the eight shapes are project-authored
code (GPL-3.0 with the project), so there is no §5 ledger row and no
per-file license. The authorship record is
`docs/phase-7/bundled-stickers.md` — created in this round so the
roadmap's Phase 7 exit criterion ("No bundled asset has an incompatible
license") has its stickers evidence in place before any asset-shaped
follow-up (an imported-frame round, say) could blur the line.

---

## Delivery record (r35, 2026-11-04, branch `tg-p7-stickers`)

The round landed every decision above, with these recorded deltas:

1. **The bundled set — as designed.** Eight in-code vector shapes in
   `BundledStickers` (pure Kotlin, JVM-tested), integer coordinates in
   the 0..100 design box, fills only; the authored formulas (ngon /
   arcPts / poly + the heart's four cubics) are recorded in
   `bundled-stickers.md`. No image asset, no sha256 pin, no §5 row —
   instead `BundledStickersTest` pins the structural invariants and the
   spot coordinates (safe rounding margins ≥ 0.3 units from a .5
   boundary), so a change to any formula fails the build. The local
   pre-push harness caught two authored-coordinate errors before any
   push (the smiley spot test conflated the pin's r=26 centre with the
   face's r=46; a flower op-count was off by five per subpath) — the
   first round in the campaign with tests executed BEFORE CI.
2. **The recipe — as designed, with the image-shaped tolerance delta.**
   Absent key = a pre-r35 sidecar loads clean (test-pinned, including
   one carrying r34 text layers — the stacking proof); an unresolvable
   `stickerId` round-trips verbatim and renders NOTHING (there is no
   default sticker to degrade to); a blank `stickerId` is a skip
   alongside a blank `id` — the one delta from r34's blank-fontId-
   reads-as-default (a fake sticker would draw something the user never
   chose). Numerics coerce like every other slider; empty normalises
   back to absent.
3. **The composite order — as designed.** `StickerCompositor` runs in
   OUTPUT space, BELOW the `TextCompositor` pass (scene objects under
   captions), through the same crop-region arguments in the preview and
   origin 0 in the export --- preview ≡ export by construction. Vectors
   draw from the op list scaled into the size square (centred on the
   anchor); imported bitmaps FIT inside the square preserving aspect.
4. **Two resolution kinds, one resolver — as designed.** The activity's
   `stickerResolver` resolves bundled-vector-first, imported-bitmap-
   second; the imported bitmaps live in ONE shared activity map
   (the bundledFonts pattern) that the composition's loader
   (keyed on a version counter, the looks pattern) refills per import —
   the preview and the export render through the same instances, and a
   payload that fails to decode is absent, never thrown.
5. **Undo/redo — as designed, granularity per-commit.** Sticker add /
   per-layer slider release / drag-release / remove each push exactly
   one snapshot; the draft-sweep set extended across the families:
   undo, redo, export, add, remove and target switch now sweep BOTH
   position drafts (text and sticker) — the r34 text surface took three
   additive edits for this (recorded as the round's touch on merged
   code, all additive).
6. **The UI — as designed plus one contract.** The registry chips are
   ADD actions, not selections (a tap adds a layer at canvas centre and
   selects it); the layer chips carry the selection; **one selected
   layer at a time across the families** --- selecting a sticker clears
   the text selection and vice versa, and the preview's drag gesture
   follows the single selection (the sticker wins). The string recount:
   `strings_r35.xml` adds **10** strings; the module's string-ref count
   moves **94 → 104** (strings.xml 46 + r32 16 + r33 12 + r34 20 + r35
   10). The design doc's 106 estimate dropped the numbered layer-chip
   fallback on delivery --- a sticker layer's chip always resolves to
   its sticker's display name (the raw id for an import or an
   unresolvable id), so no fallback string exists.
7. **Tests — the four owed families, all JVM.** `StickerLayersTest`
   (model, the s-prefix `newId`, the size box, the opacity-only alpha,
   the origin-offset identity, list order), `StickerLayersRecipeTest`
   (absent-key clean incl. the r34-stacking proof, lossless round-trip,
   the stickerId tolerance, clamps, skips, defaults, duplicate-id
   structured failure), `BundledStickersTest` (registry, structural
   validity, the spot coordinates, the palette), `ImportedStickerStoreTest`
   (magic-before-write, verbatim bytes, collision suffixing vs the
   bundled set and existing imports, the listing-as-index, idempotent
   delete, rejection leaves the store byte-identical). **46 new JVM
   tests; the edit module at 287** (r34's 241 + 46). The local harness
   ran all 278 pure tests green before the push (the module's CI total
   adds ExportPolicyTest's 9).
8. **The import store — as designed.** PNG/WebP magic-before-write
   (≥ 12 bytes, PNG signature or RIFF…WEBP), a 2 MB cap, verbatim
   storage under the magic's extension, derived collision-suffixed
   ids that can never shadow the bundled set; the directory listing is
   the index; delete is idempotent and path-safe. No UI affordance
   surfaces delete yet (the recorded follow-up F-13); the store API is
   complete and tested so the affordance is additive later.

*The §24 device rows ride the checklist, human-only.*

*Delivered on the branch `tg-p7-stickers`, cut from post-#109 main
(`5e8cf19`), in the 2026-11-04 window after the r34 merge window
(Run #12).*
