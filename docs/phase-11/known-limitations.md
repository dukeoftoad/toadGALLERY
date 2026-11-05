# Phase 11 --- known limitations (the honest list)

**Status:** the release-time known-limitations record for the r45
pre-release. Every item below is sourced from the repo's own registers ---
`follow-up.md` (the running follow-up log), `roadmap.md` ("Defer until
after v1.0" and the phase exit notes), and `tasks.md` (the human-only
device-row rows) --- with the citation in each section. Nothing here is
invented; nothing known is omitted.

---

## (a) The standing human-only directive (benchmarks and device results)

Every benchmark, latency, and device-result cell in this project is
**human-only**: the dogfood maintainer on real devices decides them, not
an assistant round, not a doc, not a release. The skeletons exist ---
`docs/phase-10/benchmark-scenarios.md` (the S-scenarios over the
L10K/L50K libraries and the BM-1/BM-2 device classes) and
`docs/phase-10/device-matrix.md` (the API 26--36 × scenario-family
matrix) --- and every result cell in them reads HUMAN-ONLY by design.
Concretely open device work: the Phase 5 ente-port dogfood rows
(tasks.md §3, the checklist §13--§18), the Phase 6 editor round-trip and
export-robustness rows (§19--§20), the Phase 7 per-tool rows
(§22--§27: LUT, text, stickers, drawing, mosaic/blur, frames), the r41
AI latency/quality/airplane-mode rows, and the r42 long-video / 4K /
HDR / TalkBack matrix. **No performance or compatibility number in any
toadGALLERY documentation is device-verified yet.** The v1.0 stable cut
waits on these rows --- that is the standing reason this release is a
pre-release.

## (b) The r41/r42 follow-ups (F-24..F-28, the Phase 10 exit record)

From `follow-up.md`, the 2026-11-05 r41/r42 sections --- summarized
accurately, one paragraph each:

- **F-24 --- the fill-patch resolution bound (magic erase).** The r41
  magic-erase inpaint patch is bounded by the session's working
  resolution, so an erased region on a very large export is softer than
  its surroundings. The recorded follow-up is a full-resolution
  export-time re-inference pass, whose cost the contract names alongside
  (a second full decode + minutes of inference); it rides a later round
  with the device latency rows in hand.
- **F-25 --- the sky mask is the shared u2netp saliency, not a portrait
  segmentation.** Sky replacement masks the u2netp saliency's
  above-the-subject region, not a portrait-grade sky segmentation; a
  dedicated sky-segmentation model is the recorded follow-up if the
  device rows show mask bleed on portrait subjects.
- **F-26 --- v1 retouch is ONE global selection.** The face-retouch
  controls (smoothing, eye brighten, face slim) apply as one global
  selection over all detected faces; per-face tuning owes its own round
  (a UI + model decision, not a toggle).
- **F-27 --- video geometry is out of scope for v1.** Crop/rotate for
  video is not in the Phase 9 task text and not in the ADR-0012 set; a
  later round owes the family decision with its own pinned grids,
  mirroring the photo editor's geometry round.
- **F-28 --- the video editor's session state is transient by design.**
  No persisted video recipe sidecar ships in v1 (the photo sidecar
  machinery is deliberately not promised for video), so re-editability
  across sessions is the recorded follow-up. The non-destructive rule
  itself is fully in force: every export is a new MediaStore entry; no
  replace-original path exists.

## (c) The other open follow-ups (the rest of the register)

Also from `follow-up.md` --- these are open, known, and scheduled, not
surprises:

- **The gesture-a11y family (F-11/F-14/F-16).** Layer positioning in the
  editor (text, stickers, drawing) is a drag gesture that is not
  TalkBack-reachable in v1; the family fix (position sliders or
  a11y-aware drag) owes one round across ALL layer families. The Phase 10
  r40 a11y sweep hardened everything else (headings, live regions,
  48 dp targets); this family is the recorded remainder.
- **F-13 --- no imported-sticker management UI.** Removing a bad sticker
  import requires clearing app data or a hand-edit; the
  `ImportedStickerStore.delete` seam exists and is tested, the affordance
  does not. (Imported LUTs and frames do have their remove affordances.)
- **F-15 --- no per-point stroke editing.** Freehand stroke points are
  not editable in v1; re-editing a stroke means delete-and-repaint (the
  family decision is recorded as deferred).
- **F-17 --- linear blur is horizontal-only; no angle slider.** The
  Gaussian-blur kind is the recorded candidate; both ride a later window.
- **F-18 --- hard mask edges.** Mosaic/blur regions use a hard binary
  mask in v1; feathered/soft edges owe their own round.
- **F-19 --- no frame stacking.** One frame mounting at a time, by
  design; stackable frames would be a model change, not a toggle.
- **F-21 --- the Things membership cut is round-pinned.** The 0.70
  cosine-distance cut is honestly arbitrary; per-library tuning rides a
  later window if the on-device dogfood asks for it.
- **F-22 --- the Things section is one LazyRow.** No "See all" browse
  screen yet when collections grow past a row; the create dialog and
  chips are not TalkBack-audited to the r32 editor standard.
- **F-23 --- the rescan memory-bounds watch item.**
  `MediaScanDriver.rescanVolume` loads the whole catalogue once per
  rescanned volume --- an accepted one-time cost on the documented
  design, watched at the 50k-library scale with the benchmark evidence in
  hand.

## (d) Product-level limits (by design, from the roadmap's "Defer until
after v1.0" list)

These are not bugs --- they are the recorded scope line
(`roadmap.md` §6, "Defer until after v1.0"):

- **No cloud sync, no accounts, no social sharing, no ads, no
  analytics** --- the offline-first product spine (product vision,
  ADR-0010/0014). Not "coming soon": out of the v1.0 scope by decision.
- **The gallery's ente-derived AI engine does not ship in the release
  APK.** The engine's native library is an opt-in build property
  (`-PwithEngine`) that every published build path leaves off; the
  release artifact's engine-dependent AI features (semantic search,
  people clustering, Things) present their honest engine-unavailable
  state, and the on-device AI dogfood rides the opt-in engine-enabled
  debug builds (`engine-apk.yml` / `modelpack-apk.yml`). Recorded by the
  parallel r44 round's `docs/phase-11/fdroid-metadata.md` (with the
  licensing consequence: no distributed APK contains the vendored AGPL
  engine).
- **Offline maps only.** The map view renders sideloaded offline tile
  packs; there is no online tile provider and no online reverse
  geocoding. Places labels come from coarse on-device coordinate-bucket
  regions, never a network lookup.
- **No RAW development.** Complex RAW development and
  professional-grade editing are deferred; format support follows the
  verified-decoders-only posture (no broad format promises without
  verified decoder support --- the Phase 4 format audit's rule).
- **No multi-track video editing, video transitions, video titles, or
  video colour grading** beyond the adjustments the photo pipeline
  exposes (ADR-0012's out-of-scope list). The video editor is the
  lightweight one: trim, mute, slow-mo, frame extraction, GIF/WebP
  export.
- **No Play Store pipeline.** Distribution is sideloaded GitHub
  pre-releases today, with F-Droid metadata prepared (the r44 round) and
  submission pending.

## (e) Release-engineering limits

- **Pre-releases only until v1.0** (ADR-0018): every testing build
  publishes with `prerelease: true` and `make_latest: false`. The stable
  cut is a future human decision gated on the device rows above.
- **One signing identity, and losing it costs the update channel**
  (ADR-0018): the release key's certificate digest is pinned in the
  workflow and the docs; if the keystore is ever lost or compromised,
  the only path is a new identity --- sideload updates over the old
  identity become impossible and users must uninstall and reinstall the
  newly-signed build. Mitigations on record: 25-year key validity,
  secrets held only in GitHub Encrypted Secrets, and the loud,
  documented digest pin.
- **The instrumented egress suite is a manual physical-device gate**
  (ADR-0016): CI runs the build-time INTERNET gate and the JVM privacy
  suites on every push, but the on-device egress probe runs before every
  tagged release by hand --- no emulator job exists on CI (the hosted
  runners expose no KVM).
