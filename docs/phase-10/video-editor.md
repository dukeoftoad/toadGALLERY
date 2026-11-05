# The Lightweight Video Editor — the Phase 10 sub-scope B design contract
# (folded from Phase 9; trim, mute, slow-mo, frame extraction, GIF/WebP)

**Status:** CONTRACT-ONLY (2026-11-05). This round delivers **the design
only** — no code, no tests, no dependency changes ride it. The
implementation rounds that consume this contract are the Phase 10 rounds
after the hardening block and after sub-scope A (the Phase 10 ordering:
harden first, then the AI editing sub-scope, then this one); their
r-round naming and tasks.md wiring are a parallel track this doc does
not touch. This contract decomposes the Phase 9 task text **verbatim**
(carried unchanged under Phase 10's deferred sub-scope B) and is
**bounded by ADR-0012** — it never shrinks the scope, and it never
exceeds it.

---

## The facts this contract rests on

- **ADR-0012 is the scope law.** Its six decision points bound this
  contract: the v1 feature set (trim, mute, slow-mo, frame extraction,
  GIF/WebP export); light colour adjust bounded to the real-time-shader
  Phase 6 adjustments; the Phase 6 parameter SET reused, not the
  pipeline implementation; the media3 `ExoPlayer`/`Transformer` render
  path with a GL shader consuming the same parameter struct as the photo
  pipeline; the original never modified, exports are new files; the
  out-of-scope list (multi-track editing, transitions, titles,
  subtitles, audio mixing, video colour grading beyond the listed
  adjustments, time-remapping beyond simple slow-mo). Its two open
  questions (Transformer on minSdk 26; which APK hosts the editor) are
  carried below as implementation-round items, not silently answered.
- **media3 is already in the tree — partially.** The catalog pins
  media3 1.11.1; the gallery consumes `media3-exoplayer` + `media3-ui`
  for in-app playback over content URIs only, with the catalog note that
  **media3 declares no INTERNET permission, so the ADR-0014 gate holds**.
  `media3-transformer` / `media3-effect` are NOT yet dependencies — the
  round adds them at the same pinned catalog version.
- **The pure-plan pattern exists and is the house style.** The photo
  editor's `ExportPlan` encodes the non-destructive policy as data ("the
  activity executes it against MediaStore"), JVM-pinned edge by edge;
  `ExportPreflight` guards decode and storage before work starts; the
  pending-row discipline (IS_PENDING on Q+, a failed write cancels the
  pending row) is test-pinned. The video editor inherits all three.
- **The editor contract is image-only today** (ADR-0002/0007: the
  action filters image mime types); a video launch path is a contract
  decision, not a free side effect (decision 8).
- **CI is compile + JVM tests** (ADR-0016); instrumented tests and the
  long/4K/HDR matrix run on physical devices only, as human-only rows.
- **The editor's zero-network posture is unconditional** (the
  merged-manifest gate with no allowlist): every media3 artifact must
  keep that true — the catalog's no-INTERNET note is the reason this
  sub-scope is allowed to add media3 at all.

## Scope

**In scope:** trim (in/out points, export the trimmed segment); mute
(strip the audio track); slow-mo (0.25x–2x, whole-clip or a selected
region); frame extraction (single frame as JPEG/PNG at a user-chosen
timestamp); GIF/WebP export (short animation from an in/out range, size
+ fps controls); light colour adjust reusing the Phase 6 adjustment set
on video frames via media3, with the real-time/export-pass boundary
documented (decision 6); the pure export-plan model; the media3
integration surface; the reusable adjustment-pipeline-on-video code in
`shared/` (the Phase 9 deliverable); the non-destructive rule; the
honest long/4K/HDR test matrix as human-only rows.

**Deliberately out of scope (ADR-0012 §5 and recorded follow-ups):**
multi-track editing, transitions, titles, subtitles, audio mixing,
video colour grading beyond the listed adjustments, time-remapping
beyond simple slow-mo; geometry (crop/rotate) on video (not in the
task text, not in the ADR set — a recorded follow-up); HSL per-channel,
sharpen, LUT looks, mosaic/blur regions, and all AI features on video
(the ADR-0012 §2 boundary — see the table); a persisted video recipe
sidecar (the photo sidecar machinery is not promised for video v1 —
session state is transient; re-editability across sessions is the
recorded follow-up; the non-destructive rule itself is fully in force).

## The design decisions worth making now

1. **The pure export-plan model is the centerpiece.** A `VideoEditPlan`
   (trim range, mute, speed plan, colour-adjust parameters, output
   format) plus `FrameExtractPlan` and `AnimationExportPlan` — all pure,
   all JVM-testable, the `ExportPlan` precedent verbatim: the plan
   encodes every policy as data and the activity merely executes it.
   Validation is total and structured: out-of-bounds or nonsensical
   input is a plan FAILURE with an honest message (the request-validator
   shape), never a silent clamp of a hostile value — the UI already
   bounds its own inputs, so a plan failure means a bug or tampering,
   and it says so.

2. **Trim as a validated `TimeRange`.** `startMs`/`endMs` with the
   facts supplied as plan inputs (clip duration, audio-track presence).
   Rules: `0 <= start < end <= duration`; a zero/negative-length or
   out-of-bounds range is a structured failure. The trimmed export keeps
   A/V alignment (the plan shifts both streams to the trim start); the
   original file is only ever read.

3. **The speed plan: 0.25x–2x, whole-clip or region, with monotonicity
   pinned.** The model is a sorted, non-overlapping list of
   `{startMs, endMs, speedFactor}` segments over the trim range — the
   v1 surface exposes whole-clip (one segment) or ONE selected region
   (the rest rides at 1x), but the model stays general. Rules the JVM
   tests pin: every factor within 0.25..2.0; segments sorted and
   non-overlapping (gaps ride at 1x); per-segment source time strictly
   increases (the monotonicity rule — no plan may fold time backwards);
   the derived output duration is pure math (`Σ segmentLength /
   factor`) pinned by tests. Preview and export agree by plan
   construction: the preview realizes the same plan via playback-speed,
   the export via the Transformer speed API — one model, two
   realizations, no drift.

4. **Audio is an input fact, and a missing track is an honest no-op.**
   The plan carries `audioTrackPresent`; **mute** on such a clip is a
   recorded no-op and the export proceeds video-only — never a crash,
   never a silent lie: the UI states "no audio track" instead of showing
   a dead toggle (the honest-note convention). Mute on a normal clip
   strips the audio track in the export. There is no audio mixing, no
   gain, no replacement — the ADR-0012 §5 boundary.

5. **Frame extraction and GIF/WebP are plans with bounds, not free
   renders.** `FrameExtractPlan`: one timestamp (within duration),
   JPEG|PNG, one new MediaStore entry via the naming rule. 
   `AnimationExportPlan`: in/out range within duration, target width
   bounded, fps bounded, GIF|WebP; the derived frame count
   (`range × fps`, rounded, deduplicated against the source frame
   rate) is pure math pinned by tests; a **byte-budget preflight**
   (the `ExportPreflight` shape) estimates the output and refuses
   over-budget requests with an honest message BEFORE the render. The
   caps' concrete numbers (max width, fps ceiling, byte budget) are
   round-pinned and documented — this contract pins only that the
   bounds exist, are plan fields, and are enforced.

6. **The Phase 6 adjustment reuse boundary — the documented table
   (ADR-0012 §2/§3, restated as the contract of record):**

   | Adjustment | On video v1? | Preview path | Export path |
   |---|---|---|---|
   | brightness | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | contrast | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | exposure | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | highlights | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | shadows | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | saturation | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | temperature | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | tint | YES — real-time shader | GL uniform, live | same shader, single export pass |
   | vignette | YES — real-time shader | GL radial mask, live | same shader, single export pass |
   | HSL per-channel | NO (ADR-0012 §2 explicit) | — | — |
   | sharpen | NO (absent from the ADR set) | — | — |
   | LUT looks | NO (absent from the ADR set) | — | — |
   | mosaic/blur regions | NO (absent from the ADR set) | — | — |
   | geometry (crop/rotate) | NO (not in the task text; follow-up) | — | — |
   | any AI feature | NO (ADR-0012 §2 explicit) | — | — |

   The nine YES rows are real-time (shader-capable) and require **no
   separate export pass** beyond the export render itself — the same
   shader runs in preview and in the Transformer effect chain, consuming
   the same parameter struct (ranges [-100..100], 0 = neutral, the
   Phase 6 semantics verbatim). The parameter SET is reused, NOT the
   pixel-pipeline implementation (ADR-0012 §3): the reusable code is a
   new pure module in `shared/` (the Phase 9 deliverable) holding the
   video parameter projection + the uniform mapping + validation — the
   module name is the round's; JVM tests pin the projection's ranges and
   a table-conformance test pins that the shader mapping covers exactly
   the nine YES rows and nothing else.

7. **The media3 surface is a seam, not a tangle.** Preview:
   `ExoPlayer` (the gallery viewer precedent, the catalog's pinned
   version). Export: `Transformer` behind a `VideoExportRunner` seam
   (plan in; typed progress + result out) — the `ModelDownloader`-seam
   precedent — so JVM tests drive the plan against a fake runner and
   only the thin runner implementation touches media3. Frame extraction
   rides the retriever/player path the round picks and documents.
   Everything runs off the UI thread with honest progress and
   cancellation (the cross-phase performance gate).

8. **Hosting and launch are contract decisions, carried not assumed.**
   ADR-0012's open question — the toadGALLERY Edit APK vs the main
   Gallery APK, on APK size budget — stays open until the round measures
   the media3 addition's size delta; the lean is the Edit APK (the
   Phase 9 deliverable lists it first and the editor is the edit
   surface), but the decision is recorded, not narrated. The launch path
   needs a decision too: the editor contract filters image mimes today,
   so video either rides an additive contract extension (the
   `EditorVersionNegotiator` additive-versioning rule governs; the
   contract doc moves with it) or a gallery-internal surface — the round
   decides and records (open item 5).

9. **Non-destructive, exactly as the photo side is.** The original
   video is NEVER modified: the source URI is read-only by construction
   (never opened for write, never updated, never deleted — the contract
   §4.1 shape carried from the photo editor). Every export — trimmed
   video, muted export, slow-mo export, extracted frame, animation — is
   a MediaStore INSERT of a NEW entry (IS_PENDING on Q+ while the bytes
   land; a FAILED write cancels the pending row so no partial entry
   survives; the naming rule derives a "-edit" suffixed name per
   format). There is no replace-original path at all. The Phase 9 exit
   criterion ("No original video is ever modified") is pinned by the
   same plan tests that pin the photo export policy.

## Tests the implementation rounds owe (JVM)

Named families, no counts claimed here:

- `VideoEditPlanTest`: the `TimeRange` validation matrix (bounds,
  ordering, zero-length, the structured failures); the speed-plan rules
  (factor bounds, sorted non-overlapping segments, gaps at 1x, the
  monotonicity rule, the derived-duration math); the audio-fact
  handling (mute no-op on a no-audio clip; strip on a normal clip); the
  colour-parameter projection validation.
- `FrameExtractPlanTest` / `AnimationExportPlanTest`: timestamp and
  range validation; the frame-count derivation; the bounds; the
  preflight refusal path.
- The shared video-adjust module: the projection's ranges/identity; the
  table-conformance test (exactly the nine ADR-0012 YES rows mapped).
- The runner-seam tests over a fake `VideoExportRunner` (typed results,
  progress ordering, cancellation).
- The conformance additions: no new permissions anywhere; the editor's
  INTERNET gate stays green with the media3 artifacts present.

**Device/engine-only slices (honest):** Transformer behavior on real
codecs and containers, the GL shader rendering, HDR/4K decode behavior,
performance numbers, and every device row below.

## Device checklist rows (human-only, later; the §-numbering rides the
## device-validation checklist's next update)

1. Trim, mute, slow-mo end-to-end on representative clips; the round
   trip Gallery→(edit)→export→new entry; the original untouched (the
   hash rule).
2. **The honest matrix rows, human-only and never CI-faked:** a long
   video (10+ minutes), a 4K clip, an HDR clip (where the device
   supports HDR), and a clip with no audio track. Results and the
   performance budget are DOCUMENTED by the round (the Phase 9 exit
   criterion "documented and within an agreed budget") — this contract
   invents no numbers.
3. Frame extraction (JPEG + PNG) and GIF/WebP export at the bounds of
   the size/fps controls; the preflight refusal shown honestly.
4. Airplane mode for the whole flow (zero network by construction).
5. TalkBack over the new video surface (the r32 conventions); the
   no-audio-track honest note announced.

## Licensing gate

- `media3-transformer` (+ the effect artifact, if used) joins the
  dependency ledger at the pinned catalog version (Apache-2.0; the
  exoplayer/ui rows already exist — the round adds the new artifact
  rows).
- No model or third-party asset licensing rides this sub-scope (no AI,
  no bundled assets beyond project-authored code).
- The provenance ledger §5 stays empty of new code-level adaptations
  (media3 is a dependency, not adapted code).

---

## Delivery record (this contract round): the design only

Delivered in this round: **this document** — the design contract above.
No code, no tests, no dependency changes, no strings; and zero edits to
roadmap.md, tasks.md, worklog.md, progress.json or follow-up.md (the
wiring is a parallel track). No delivery claims are made beyond the
design; no test counts, performance numbers or cap values are invented
here.

**Open items for the implementation rounds (in order, the r-round
naming left to the roadmap wiring):**

1. Verify `Transformer` at the pinned catalog version supports the
   required pipeline on minSdk 26 — ADR-0012's first open question —
   and record the answer BEFORE the export work leans on it.
2. Measure the media3 addition's APK size delta and record the hosting
   decision (Edit APK vs Gallery APK) per ADR-0012's second open
   question; the ADR/contract docs move with the decision.
3. Add `media3-transformer` (+ effect if used) to the version catalog at
   the pinned version; extend the dependency ledger.
4. Land the pure plan module + the shared video-adjust module (the
   Phase 9 `shared/` deliverable) with their JVM suites.
5. Decide and record the launch path (the additive editor-contract
   extension vs a gallery-internal surface); move the contract docs.
6. Round-pin the caps (max width, fps ceiling, byte budget) and the
   performance budget for long/4K/HDR; document them.
7. Land the surfaces (trim/mute/speed controls, the frame-extract and
   animation sheets, the nine adjustment sliders) + the export runner
   with progress/cancellation.
8. Run the device matrix rows; document the results; bump the hosting
   app's `versionCode` with the narrating comment block, per the
   discipline.

## Cross-phase gates, applied to sub-scope B

- **Build:** CI green; the new `shared/` module and the media3
  artifacts compile; the editor's merged-manifest INTERNET gate stays
  green (media3 declares no INTERNET — the catalog note holds).
- **Tests:** every plan rule JVM-tested; the table-conformance test
  pins the ADR-0012 boundary; regressions owned.
- **Privacy:** zero network by construction (no new permissions, no
  network code; media3's no-INTERNET declaration); the airplane-mode
  device row verifies on-device.
- **Data safety:** the original is never modified; every export is a
  new MediaStore entry with the pending-row failure recovery; no
  replace-original path exists.
- **Permissions:** zero new permissions (MediaStore inserts stay
  own-contribution on Q+; the capped legacy write permission unchanged
  pre-Q).
- **Performance:** no heavy decode/render on the UI thread; the
  long/4K/HDR budget documented by the round; progress and cancellation
  honest.
- **Licensing:** the new media3 artifact rows recorded; no other
  licensing surface moves.
- **Accessibility:** the new surfaces to the r32 standard; the honest
  no-audio note announced.
- **Documentation:** the ADR-0012 open questions' answers recorded; the
  adjustment-boundary table and the hosting/launch decisions land in
  the round's docs and, where they change them, the architecture docs.
