# The Edit v3 AI features — the Phase 10 sub-scope A design contract (folded
# from Phase 8; magic erase, sky replacement, bokeh, opt-in face retouch)

**Status:** CONTRACT-ONLY (2026-11-05). This round delivers **the design
only** — no code, no tests, no models, no strings ride it. The
implementation rounds that consume this contract are the Phase 10 rounds
after the hardening block; their r-round naming and tasks.md wiring are a
parallel track this doc deliberately does not touch. This contract
decomposes the Phase 8 task text **verbatim** (the roadmap carries it
unchanged under Phase 10's deferred sub-scope A) — it never shrinks it:
all four features, the models-in-APK packaging, the extended privacy
suite, and every Phase 8 exit criterion are in scope below.

---

## The pipeline facts this contract rests on

The design is shaped by the r30–r38 EDIT pipeline as it actually exists:

- **The stage order** (`EditPipeline.STAGE_ORDER`): `flip -> straighten ->
  perspective -> quarterTurns -> crop -> tone LUT -> saturation ->
  temperature/tint -> HSL bands -> LUT filter -> vignette -> sharpen ->
  mosaic/blur regions`. The regions stage is the FINAL pixel stage — the
  r37 contract's assertion is that **no downstream stage re-processes the
  censored output**, and that assertion must survive this sub-scope.
- **The layer model:** five layer families exist (1 text, 2 stickers,
  3 strokes, 4 regions, 5 frames); four are additive recipe containers,
  one is a pixel stage. Each family carries the same tolerance contract:
  an absent sidecar key loads clean, an unresolvable id round-trips
  verbatim and degrades honestly at render, numerics coerce to family
  defaults, a malformed container is a structured parse failure — never a
  crash.
- **The one-shared-compositor pattern:** preview and export render
  through the same resolver and the same compositor call sites — they
  agree by construction, not by duplicated code.
- **The identity fast path:** the identity recipe returns the input grid
  EXACTLY; every stage with an absent/zero container is skipped.
- **The bounded preview:** the session edits a bounded preview decode
  (`PREVIEW_MAX_DIMENSION` 2048); export re-decodes full. Masks and
  fractions therefore live as output-canvas fractions and are exact at
  any grid; the vignette/r37 grid-relative honesty applies.
- **The editor's zero-network posture is UNCONDITIONAL:** the edit
  module's merged-manifest gate (`EditInternetPermissionGateTask`) fails
  the build on any INTERNET permission with **no allowlist mechanism at
  all** — the ADR-0020 models.ente.com grant belongs to the GALLERY's
  engine downloader, never the editor. `EditManifestConformanceTest`
  pins the posture on the JVM side.
- **The undo/redo mechanism** has existed since r30 (the snapshot stack
  over whole recipes); every family pinned its per-commit granularity and
  the r38 capstone (`UndoRedoExtensionTest`) pinned the five-family
  mixed session.
- **The editor versionCode discipline:** every round bumps the edit
  module's `versionCode` (currently 11, `0.4.6-edit-alpha`) with a
  comment block narrating the round.

## Scope

**In scope:** the four Phase 8 features (magic erase, sky replacement,
bokeh, opt-in face retouch) as non-destructive, re-editable layers in the
Phase 6 pipeline; the on-device model packaging (both magic-erase models,
the depth model, and the retouch model set ship in the APK per
ADR-0010); the privacy-suite extension asserting zero outbound
connections during any AI feature (ADR-0010 §7); the consent machinery;
the editor surfaces (Compose, the r32 a11y conventions); undo/redo and
sidecar extension; attribution records.

**Deliberately out of scope (recorded follow-ups, not omissions):** any
cloud or network path (forbidden outright by ADR-0010 §1 and by the
editor's gate); runtime model download for the editor (no allowlist
exists to authorize one — ADR-0010 §4's rule applies instead: a model
that does not fit means the feature does not ship); feathered/soft mask
edges (the r37 recorded extension carries forward); full-resolution
re-inference at export time (see decision 3's honest limitation); video
(sub-scope B's contract, `video-editor.md`); per-face retouch parameter
tuning in v1 (v1 is one global retouch selection over detected faces).

## The design decisions worth making now

1. **The AI features are PIXEL STAGES, slotted between sharpen and the
   regions.** They transform existing pixels (fill, replace, blur,
   smooth) — the region round's precedent — not compositing passes that
   draw over. The tail grows once:
   `… -> vignette -> sharpen -> AI erase -> AI sky -> AI bokeh ->
   AI retouch -> mosaic/blur regions`. The AI order is semantic, and
   stated: erase first (later stages see the restored surroundings), sky
   next (the new sky exists before the depth-driven blur), bokeh over the
   replaced world, retouch polishes the subject last among the AI
   stages — and the regions stage stays FINAL so a mosaic over a
   retouched face is still a mosaic. The existing stage-order assertion
   test gains the new tail; an AI-stages-only recipe is NOT identity and
   `apply` runs the stages; absent AI containers run nothing and the
   identity fast path stays exact. The stages inherit the grid-relative
   honesty verbatim (the vignette's, then the r37's): exact in the export
   where the stage grid IS the output canvas; approximate in the preview
   under an active crop.

2. **Inference runs at COMMIT time, never in the render loop.** The
   models run once, when the user applies or re-applies a feature, off
   the UI thread, cancellable, with honest progress. What inference
   produces is a **derived artifact** (a mask, a depth map, face boxes, a
   fill patch) persisted in app-private storage. Every render — preview
   and export — replays deterministically from the stored artifact plus
   the layer's parameters; no model ever runs during render or export.
   Parameter changes that do not change the artifact (blur strength,
   focal point, sky opacity, retouch strengths) re-render without
   re-inference — this is what makes "non-destructive and re-editable"
   (the Phase 8 exit criterion) literally true, and it keeps preview and
   export byte-agreement by construction.

3. **The derived-artifact store: `edit-ai/` in the editor's app-private
   storage, beside the sidecar directory.** The sidecar-discipline
   mechanics apply: atomic tmp-then-rename writes, forgiving reads,
   per-session hygiene. Artifacts are keyed by layer id plus a content
   hash of (source hash, inputs, model identity) — re-inference writes a
   NEW artifact and never mutates one in place, so the undo stack stays
   coherent (undoing a re-apply restores the recipe that references the
   previous artifact id, which is still present). The store is
   append-only for the session and swept together with the sidecar on
   export/clear. Artifact encodings: masks as RLE rows over
   output-canvas fractions (exact at any grid — the RegionMask plumbing's
   convention); depth maps as quantized 8-bit single-channel grids at a
   bounded working resolution, bilinearly upscaled at apply; face boxes
   as normalized rects; the inpaint fill as a working-resolution patch
   pasted under its mask. The recipe references artifacts by id; a
   missing artifact round-trips verbatim and renders NOTHING at render
   time (the frame/sticker tolerance contract — never a crash), and the
   UI says the layer degraded instead of showing dead controls. **The
   honest limitation, stated now:** the fill patch is bounded by the
   session's working resolution, so an erased region on a very large
   export is softer than its surroundings; a full-resolution
   export-time re-inference pass is the recorded follow-up with its cost
   (a second full decode + minutes of inference) named alongside.

4. **Four new additive recipe containers — layer types 6–9.** The family
   shape, verbatim:
   - `aiErase: AiEraseLayers?` — a LIST (multiple erase regions, like
     the region family): per-layer id + painted points (3..512, the
     shared stride-thinning cap, the `newId` prefix convention).
   - `sky: SkySelection?` — SINGLE (the `LutSelection`/`FrameSelection`
     shape): `{maskRef, skyId, opacityPct 0..100 (default 100)}`. One
     sky at a time — the mounting rule's shape.
   - `bokeh: BokehSelection?` — SINGLE:
     `{depthRef, blurStrengthPct 1..100, focalDepthNorm 0..1}`.
   - `retouch: RetouchSelection?` — SINGLE:
     `{boxesRef, smoothingPct 0..100, eyeBrightenPct 0..100,
     faceSlimPct 0..100}` (defaults 0 = no contribution).
   All four: absent key = a pre-AI sidecar / a clean session; empty
   normalises to absent; unresolvable refs and ids round-trip verbatim
   and degrade at render; numerics coerce with family defaults;
   malformed containers are structured parse failures. The stacking
   proof continues: a pre-AI sidecar carrying all five earlier
   containers loads clean.

5. **Magic erase — paint region → segmentation → inpainting; BOTH
   models ship in the APK.** The r37 paint gesture is reused: an
   Erase-mode chip joining the exclusive paint-mode duality (ONE paint
   mode at a time across Draw/Region/Erase, enforced at both toggles);
   the painted path seeds a mask through the existing even-odd
   `RegionMask` plumbing; at commit, the segmentation model refines the
   seed into the object mask and the inpainting model fills it; the
   refined mask + fill persist as artifacts; the stage pastes the fill
   under the mask. A tap under three samples drops honestly (the region
   commit rule). Product copy is honest per ADR-0010's own consequence:
   on-device quality is bounded — this is not a server-side diffusion
   model, and the strings say so without apologising.

6. **Sky replacement — auto-detect at apply; a bundled CC0 sky set OR a
   user-supplied image.** The detection model identifies the sky region
   once at commit (the mask artifact); finding no sky is an honest
   refusal message, not an empty layer. The sky reference is a registry
   id: the bundled CC0 set (provenance + sha256 ledger rows in a phase-10
   doc, the bundled-fonts/luts shape) plus user-supplied skies imported
   through the **ImportedFrameStore/StickerStore contract verbatim** —
   PNG/WebP magic-before-write, verbatim bytes, the size cap,
   collision-suffixed ids that can never shadow the bundled set. One sky
   at a time; an unresolvable skyId (a version skew, a removed import)
   round-trips verbatim and degrades to no-replacement. The sky bitmap
   is scaled to COVER the mask's bounding box (center-crop — not the
   frames' full-canvas stretch; a stretched sky reads wrong), blended at
   the selection's opacity.

7. **Bokeh — the depth model ships in the APK; strength + focal point
   re-render from the artifact.** The depth map is estimated once at
   commit (the quantized 8-bit artifact); blur strength and focal depth
   are pure re-renders over it — the Phase 6 blur-kernel discipline (the
   r37 integer-deterministic, hand-grid-pinned kernels, clamped windows,
   floor division) and the r37 region-mask plumbing (the fraction/RLE
   conventions) reused, not reinvented. The variable blur is
   depth-driven: per-pixel radius from the depth distance to the focal
   band, integer-deterministic and exactly testable. No re-inference on
   any slider move; re-estimation is a deliberate re-apply.

8. **Face retouch — OPT-IN per ADR-0010 §5, the first consent surface in
   the editor.** Disabled by default; the retouch entry is hidden until
   consent is granted. The first-use consent dialog's text must state,
   plainly: what the model does, that it runs **on-device only**, and
   that **no face data leaves the device** (the ADR-0010 §5 requirements;
   the strings land in the round's strings file). A permanent kill-switch
   lives in a minimal editor settings sheet (the editor has no Settings
   surface today — this is its first, one-row sheet): disabling hides the
   entry AND the stage refuses to run even if a restored sidecar carries
   a retouch layer — the layer stays verbatim in the recipe, the stage
   skips, the honest dominance-order shape (the SemanticContentPolicy
   consent-off precedent). The consent state machine is a pure,
   JVM-testable slice: default-off, grant (per-install persisted), deny
   (re-asks next use), permanent disable. The retouch model set (face
   localization + the retouch processing) ships in the APK and counts
   against the size budget; v1 exposes the three task-named controls
   (skin smoothing, brighten eyes, slim face) as one global selection
   over the detected faces — "slim face" is a local displacement warp
   inside the stage, NOT the geometry toolset.

9. **Model packaging: `assets/models/`, pinned like the engine pins, with
   the selection criteria pinned INSTEAD of the models.** The edit
   module gains an `assets/models/` tree (the `assets/luts` and
   `assets/fonts` precedent) and a pure Kotlin manifest recording each
   file's name, byte size and SHA-256, pinned by JVM literals — the
   `ModelProvisionerTest` shape **minus the downloader**: there is no
   fetch path to verify, because there is no fetch path. On-device,
   presence + size are checked before inference; full digest verification
   is a release/dogfood gate, not a per-launch cost. **Model selection is
   an implementation-round decision; this contract pins the criteria:**
   - **Format:** runtime-compatible (ONNX opset or TF Lite flatbuffer)
     against the ADR-0010 §3 stack — NNAPI where available, CPU fallback.
     The engine workstream's ONNX Runtime packaging is the named prior
     art; the runtime choice itself is the round's (open item 1).
   - **License:** ADR-0010 §2's bands — Apache-2.0/MIT preferred,
     CC-BY-4.0 acceptable, anything restricting redistribution refused —
     compatible with the product's GPL-3.0 mix, documented in the
     attribution package (the Phase 8 exit criterion).
   - **Size budget:** the whole AI set fits a documented APK budget (the
     number is the round's, measured). A model that does not fit means
     the feature does not ship (ADR-0010 §4) — there is no editor
     download path to rescue it.
   - **On-device latency:** a per-apply budget on the Phase 10
     defined mid-range test device, the number pinned by the round's
     measurements; inference is one-time per commit, off the UI thread.
   - **Determinism:** the same model + inputs + device yields the same
     artifact — the preview ≡ export property depends on it.
   **The ADR-0020 distinction, exactly:** ADR-0020's runtime-download
   policy (pinned host, size+SHA-256 verification, allowlisted fetches)
   refines ADR-0010 for the GALLERY's Ente-derived engine workstream
   only; ADR-0010's revision says so explicitly — **Phase 8's editor-AI
   models KEEP the ship-in-APK bundling rule**. What the editor reuses
   from ADR-0020 is the *verification discipline* (pinned manifests,
   byte-exact JVM pins) and, where it helps, the verify-then-stage
   install shape — as prior art, with no INTERNET permission, no
   downloader, and no allowlist entry ever created on the editor side.
   ADR-0020 §7's offline release pack interplay: the editor's bundled
   models ARE the offline-pack discipline for the editor — shipped,
   hash-pinned, verified at build/test time.

10. **The inference seam degrades honestly, with dedicated messages.**
    The editor gains an engine facade over the chosen runtime (the
    `MlEngine`/`MlEngineJni` shape): a missing or broken native runtime
    is the graceful `LibraryMissing`-style state — never a crash; the
    affected feature answers honestly and the rest of the editor keeps
    working. Every absent-model case answers a **dedicated message
    naming the recovery**, mirroring the engine's
    `FACE_MODELS_NOT_INSTALLED` precedent (a dedicated boundary message
    beats the generic missing-path error, so the caller learns the
    actual shape): e.g. a magic-erase apply on a build whose bundled
    models are missing or corrupt answers "the magic-erase models are
    missing from this build; the feature is unavailable" — never a
    generic inference failure. Builds normally always bundle the models
    (ship-in-APK), so the absent case is a version skew or a hand-pruned
    build — the degradation exists because the honest-degradation rule
    does not depend on probability.

## The privacy-suite extension (ADR-0010 §7)

- **The build-time gate stays unconditional and green.** Model assets
  carry no permission; the merged-manifest gate's assertion (no INTERNET,
  no allowlist) remains true, and no allowlist entry is ever created. The
  gate task's comment ("the editor has no models") is updated by the
  implementation round to narrate the new fact — the gate's logic does
  not change.
- **JVM:** `EditManifestConformanceTest` stays; a host-literal source
  scan over the new edit-AI packages joins it (the ADR-0020 §6 `ml/`
  scan shape): no host literals, no network classes, ever.
- **Instrumented (physical device, the ADR-0016 manual gate):** an
  editor-side egress probe — the gallery's `PrivacyEgressTest` precedent
  extended per ADR-0010 §7 — runs EACH AI feature end-to-end and asserts
  **zero outbound connections during any AI feature**. CI cannot run
  instrumented tests (ADR-0016), so this rides the physical-device
  release gate, honestly labelled.

## Tests the implementation rounds owe (JVM, edit module)

Named families, no counts claimed here:

- The four container codec tests: the round-N stacking proof (a pre-AI
  sidecar carrying text + stickers + strokes + regions + frame loads
  clean), lossless round-trips, clamps/defaults/duplicates, the
  missing-artifact and unresolvable-ref degradations.
- The mask/depth plumbing: RLE round-trip exactness; fraction upscale
  exactness on hand grids; the erase seed mask over the shared
  `RegionMask`.
- The AI pixel kernels: the variable depth-driven blur, the sky
  cover-crop blend, the retouch smoothing/brighten kernels and the slim
  displacement warp — integer-deterministic, hand-grid pinned, the r37
  discipline verbatim (including the sliding-window equivalence pattern
  where a window applies).
- The consent state machine (pure): default-off, grant, deny, permanent
  disable, and the stage-refusal rule under consent-off.
- The model manifest pin test (name + size + SHA-256 literals over the
  committed `assets/models/` files).
- The artifact store contract: atomic writes, collision-free keys,
  append-only session semantics, the sweep, missing-ref tolerance.
- The undo/redo extension: per-commit granularity for the new families'
  controls; the capstone's mixed session extended across all nine
  families.
- The pipeline tail: the updated `STAGE_ORDER` assertion; the
  AI-stages-only recipe is NOT identity and `apply` runs the stages.

**Device/engine-only slices (honest):** the model inference itself
(segmentation, inpainting, depth, face localization), NNAPI delegate
availability and fallback behavior, latency numbers, the instrumented
egress probe, and every device row below.

## Device checklist rows (human-only, later; the §-numbering rides the
## device-validation checklist's next update)

1. Each AI feature end-to-end on a real photo: apply, adjust, undo,
   re-apply; TalkBack announces every new control (the r32 conventions:
   labelled sliders announcing live values, 48 dp targets).
2. Full round trip Gallery→Edit→export→saved-copy with each AI layer:
   the export renders the AI stages; the original is untouched (the
   §19-style hash rule); re-opening the copy re-edits (strength/focal/
   opacity changes replay without re-inference).
3. Airplane mode for the whole flow, plus the instrumented egress probe
   green: zero outbound connections during every AI feature.
4. Face retouch consent: the dialog copy (what the model does / on-device
   / no face data leaves the device), the deny path, the permanent
   Settings disable, and the stage-refusal with a restored retouch
   sidecar.
5. Process death mid-AI-edit: the recipe + artifacts restore; a
   hand-removed artifact renders nothing and says so.
6. Latency: per-apply inference within the round-documented budget on the
   defined mid-range device; the numbers recorded in the round's docs.

## Licensing gate

- Every model's license and source recorded per ADR-0010 §6 in
  `licensing-and-attribution.md` and surfaced in the About screen;
  GPL-3.0 compatibility asserted per the Phase 8 exit criterion.
- The bundled CC0 sky set: provenance + sha256 ledger rows (the
  bundled-fonts/luts doc shape) in the round's phase-10 doc.
- No vendored-crate NOTICE.md changes (the engine pin is untouched by
  this sub-scope).

---

## Delivery record (this contract round): the design only

Delivered in this round: **this document** — the design contract above.
No code, no tests, no models, no strings, no gradle changes; and zero
edits to roadmap.md, tasks.md, worklog.md, progress.json or
follow-up.md (the wiring is a parallel track). No delivery claims are
made beyond the design; no test counts, model names or sizes are
invented here.

**Open items for the implementation rounds (in order, the r-round
naming left to the roadmap wiring):**

1. Pick the inference runtime (ONNX Runtime vs TF Lite) against
   ADR-0010 §3's NNAPI-then-CPU stack; pin the version; wire the
   editor's NDK/ABI build surface — the editor builder deliberately has
   no Rust/NDK today, so this is new build surface to add and document.
2. Select the four features' models against decision 9's criteria
   (format, license, size budget, latency, determinism); record
   licenses + provenance; commit the assets; pin the manifest literals.
3. Land the four recipe containers + the pipeline stages (the
   `STAGE_ORDER` tail) + the `edit-ai/` artifact store + the inference
   seam with its dedicated degradation messages.
4. Land the four editor surfaces: the Erase paint mode (the exclusive
   duality grows to three), the sky registry + import chip, the bokeh
   controls, the consent dialog + the settings kill-switch sheet + the
   strings file.
5. Extend the privacy suites: the JVM host-literal scan over the new
   packages; the instrumented egress probe asserting zero outbound
   connections during every AI feature (ADR-0010 §7).
6. Extend undo/redo (per-family granularity + the capstone's mixed
   session) and the sidecar stacking proofs.
7. Run the device rows; record the latency numbers; complete the
   attribution ledger rows (models + skies); update the feature matrix
   and the editor-contract doc where the surfaces change it.
8. Bump the edit module's `versionCode` with the narrating comment
   block, per the discipline.

## Cross-phase gates, applied to sub-scope A

- **Build:** CI green; the editor's merged-manifest INTERNET gate stays
  green with models bundled (assets carry no permission).
- **Tests:** every pure slice above JVM-tested; the stacking proofs and
  the undo/redo capstone extended; regressions owned.
- **Privacy:** zero outbound connections during any AI feature,
  asserted by the extended suite (ADR-0010 §7); the gate's no-allowlist
  posture intact; ADR-0014's editor story unchanged.
- **Data safety:** the original bytes are never modified (the ADR-0011
  sidecar + read-only source URI disciplines carry); AI layers are
  non-destructive and re-editable.
- **Permissions:** zero new permissions — the editor keeps its single
  capped legacy write permission; MediaStore inserts stay
  own-contribution.
- **Performance:** inference off the UI thread, cancellable, honest
  progress; the identity fast path untouched; the editor's
  open-to-first-preview budget unaffected for recipes without AI layers.
- **Licensing:** model + sky attribution recorded (ADR-0010 §6); the
  GPL-3.0 mix verified.
- **Accessibility:** every new surface to the r32 standard (TalkBack
  labels, focus order, scalable text, 48 dp targets); the consent dialog
  fully announced.
- **Documentation:** this contract implemented means the round's doc,
  the attribution ledger, the feature matrix and (where surfaces change
  them) the architecture docs move with it.
