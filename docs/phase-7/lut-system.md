# The LUT `.cube` Filter System — r33 Round Doc (Phase 7 opener)

**Status:** r33 (2026-11-03) — complete code-side, riding branch
`tg-p7-lut-engine` (stacked on `tg-p6-edit-v1-exit`). Editor
`versionCode 5` (`0.4.0-edit-alpha`). 41 new JVM tests; the edit module
is at 199. The on-device half is checklist §22; the licensing record is
`bundled-luts.md` (same folder).

---

## What this round delivers

The roadmap's Phase 7 task 1 — "LUT-based filter system with an
intensity slider; no copyrighted LUTs bundled; bundled defaults CC0 or
project-authored" — minus the user-import flow (the next round; see
"Deliberately not in this round").

| Piece | File(s) | What it is |
|---|---|---|
| The engine | `apps/edit/.../CubeLut.kt` | The pure Adobe `.cube` parser + the allocation-free trilinear sampler + the alpha-preserving intensity blend. Zero Android imports; JVM-tested. |
| The recipe layer | `apps/edit/.../EditRecipe.kt` | `LutSelection(lutId, intensity 0..100)` as an ADDITIVE optional sidecar field; `isFilterIdentity` folds into the pipeline's exact-identity fast path. |
| The pipeline stage | `apps/edit/.../EditPipeline.kt` | Stage 10 "LUT filter", between the HSL bands (9) and vignette/sharpen (11/12). The look grades the GRADED pixels; the caller resolves the id → `CubeLut` and the stage runs only on an id-matched pairing at intensity > 0. |
| The bundled set | `apps/edit/src/main/assets/luts/*.cube`, `tools/gen_bundled_luts.py` | Five project-authored looks from the committed deterministic generator. See `bundled-luts.md`. |
| The registry + loader | `apps/edit/.../BundledLuts.kt` | Unique ordered ids; a stream-injected loader (JVM-testable); a broken asset skips, never throws. |
| The import store | `apps/edit/.../ImportedLutStore.kt` | The user-imported `.cube` store: parse-before-write, verbatim bytes, derived collision-suffixed ids (an import can never shadow a bundled look), the directory listing as the index, idempotent path-safe delete. |
| The experience | `apps/edit/.../EditActivity.kt`, `strings_r33.xml` | The Filters chip row (bundled + imported + the Import action) + the intensity slider (r32 live-drag + a11y conventions) over the ONE shared look cache: the activity's lazy `bundledLooks` merged with the import store, resolved identically by the preview and the export; the remove-look affordance for imported looks. |

## The four design decisions worth remembering

1. **The pipeline stays asset-blind.** `EditPipeline.apply` takes the
   caller-RESOLVED `filter: CubeLut?` (default null — every pre-r33
   call site is source-compatible). The stage applies only when
   `recipe.lut`, the resolved look, and the ids PAIR UP at intensity
   above zero. Anything else degrades to no-look, never a crash.

2. **The tolerance contract.** A sidecar referencing a look this build
   does not bundle (a version skew, a hand-edit) keeps the id verbatim
   — the recipe is the user's data — and the applier degrades to
   no-look. The UI shows this honestly: no chip is selected. This is
   the same additive-versioning philosophy as the launch contract and
   the recipe codec (unknown keys ignored, wrong values structured
   failures).

3. **One shared cache, one pipeline.** The preview and the export
   resolve through the same lazily-parsed `bundledLooks` map and the
   same `EditPipeline.apply` — the previewed look cannot differ from
   the exported look by construction (the r30 preview/export agreement
   principle extended to Phase 7).

4. **Project-authored assets only, generated in-repo.** No third-party
   LUT data ships; the committed generator's formulas ARE the looks,
   deterministic and reproducible (`python3 tools/gen_bundled_luts.py`
   regenerates the committed bytes exactly — verified in the r33
   verification pass). This satisfies the roadmap's licensing exit
   criterion with zero external provenance to audit.

## Stage order (the honest number)

`flip -> straighten -> perspective -> quarterTurns -> crop -> tone LUT
-> saturation -> temperature/tint -> HSL bands -> LUT filter ->
vignette -> sharpen` — 12 stages, pinned by `EditPipeline.STAGE_ORDER`
and the stage-order test. The look sits after the per-pixel colour
work and before the position-masked spatial effects: a colour-only
look must not change what the vignette and the sharpen masks see.

## Deliberately NOT in this round

- **Tetrahedral interpolation** (the quality upgrade over trilinear).
  Trilinear is the tested baseline; tetrahedral changes sample values
  and would need its own hand-verified probe set — a deliberate,
  separate change if it ever comes.
- **Look thumbnails on the chips** (rendering each look over a small
  crop of the current image). A device-experience polish; the chip
  labels are honest without it.

*(The user-import `.cube` flow — originally listed here — was built in
this same window's extension waves 7–9: `ImportedLutStore` + the SAF
import chip + the merged registry + the remove-look affordance. The
roadmap's Phase 7 task-1 bullet is complete.)*

## Verification

- 51 new JVM tests: `CubeLutTest` (24 — parser rules, rejection
  matrix, hand-computed interpolation incl. the identity-by-
  multilinearity proof and the 0.875 corner-weight blend),
  `EditRecipeTest` +4 (the additive round-trip, the pre-r33 sidecar,
  the unresolvable id, the dialed-out identity),
  `PipelineExportTest` +4 (paired apply, mismatch/unresolved
  degradation, the dialed-out fast path, the after-HSL stage pin),
  `BundledLutsTest` (9 — registry invariants, per-asset conformance,
  loadAll ordering, opener failure, the five look probes at grid-exact
  coordinates), and `ImportedLutStoreTest` (10 — the verbatim
  round-trip, the suggested-name fallback, the rejected-import
  cleanliness, slug edge cases, re-import suffixing, the
  bundled-shadow pin, load/loadAll, the store recreation, the
  corrupt-file degradation, the delete matrix).
- The local static gates (ADR-0013's no-toolchain reality):
  `tools/kotlin_lex_check.py` on every module file; string-reference
  resolution (74/74); XML parse of every strings file; the `.cube`
  format validator; the generator determinism diff; and — added after
  the concurrent actor's CI fix exposed the gap — the XML-comment
  double-hyphen audit across every `apps/edit` XML. The first CI
  compile of this code rides the branch's PR.
