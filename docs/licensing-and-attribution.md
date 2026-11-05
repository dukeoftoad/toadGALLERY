# Licensing and Attribution

**Status:** Living document (updated every phase) · **Last revised:** 2026-11-02 (v2.5 —
r23 records the photos-side vendored crates `ente-vecdb` (whole upstream `vecdb`) and
`ente-photos-ml` (partial `photos` extraction with one crypto seam) in §2/§5; v2.4 —
r22 records the net-new `engine/crates/toad-ml-jni` JNI bridge crate, AGPL-3.0 via the
engine workspace; v2.3 — Ente ML engine vendored under ADR-0020 with the AGPL-3.0
one-way-compatibility consequence; still features-only per ADR-0008 otherwise; Aves
`exifinterface` fork port cancelled; `org.fossify:commons` slated for removal per
compose-migration).
This is engineering compliance tracking, not legal advice.

## 1. Governing decision

toadGALLERY is distributed under **GPL-3.0** (see root `LICENSE`). Per ADR-0004 (clarified
by ADR-0008): under the original ADR-0001 the GPL-3.0 choice was *obligatory* because the
product derived from Fossify's GPL-3.0 code. Under ADR-0008 (features/UI/UX/designs only,
no code ports) the product derives from no upstream code, so GPL-3.0 is now a **voluntary
choice** that aligns with the FOSS/gallery community ethos. The choice stands; the
rationale changes.

## 2. Upstream license inventory (verified from files at the pinned commits)

| Component | Source | License | Verified | Role under ADR-0008 |
|---|---|---|---|---|
| Fossify Gallery | `FossifyOrg/Gallery` @ `e9812299` | **GPL-3.0** | 2026-10-29, file content | **Reference only.** No code ported. |
| Aves | `deckerst/aves` @ `4f940ae` | **BSD-3-Clause** (© 2020 Thibault Deckers) | 2026-10-29, file content | **Reference only.** No code ported (including the `exifinterface` fork). |
| Mi Gallery | (Xiaomi, proprietary) | Proprietary | n/a | **UX/feature reference only.** No code, no assets, no trademark. |
| Jetpack Compose + Material 3 | `androidx.compose` | **Apache-2.0** | AndroidX metadata | Canonical UI toolkit (ADR-0009). |
| `androidx.exifinterface` | `androidx.exifinterface` | **Apache-2.0** | AndroidX metadata | Replaces the planned Aves `exifinterface` fork port (ADR-0008). |
| Ente ML engine (Rust) | `ente/ente` `rust/crates/{ml,assets,image}` + (r23) `rust/crates/vecdb` and the `photos` crate's `db`/`ml_db`/`ml_store`/`face_clustering` modules @ `423b2d80d9ec89d45f5680cb1e1d777bac225f41` | **AGPL-3.0** (c) Ente Services Pvt. Ltd. and contributors | 2026-11-02, repo LICENSE + crate tree | **Vendored + adapted** under ADR-0020 / maintainer direction (2026-11-02): `engine/` workspace, see `engine/NOTICE.md` |

### Attribution chain

- **Fossify Gallery** is itself a continuation of the **Simple Gallery** lineage
  (Simple Mobile Tools), maintained by the Fossify organization. We do not port Fossify
  code, but the product's privacy posture and offline-first behavior are inspired by that
  lineage. Attribution in the About screen credits Fossify Gallery as a design reference.
- **Aves** is © Thibault Deckers. We do not port Aves code (including the `exifinterface`
  fork), but the product's metadata Details view and collections UX are inspired by Aves.
  Attribution in the About screen credits Aves libre as a design reference.
- **Mi Gallery** is a Xiaomi product. We do not use any Mi Gallery code, assets, or
  branding. The product's editor UX and organisational features are inspired by Mi Gallery.
  Attribution in the About screen is careful not to imply endorsement.

### License compatibility summary

- GPL-3.0 (the product, voluntary) → source disclosure obligation applies on distribution.
- Apache-2.0 (AndroidX, Compose, `androidx.exifinterface`) → GPL-3.0-compatible; notice
  + license text required for the dependency.
- BSD-3-Clause (Aves) → GPL-compatible; *not* ported under ADR-0008, so no notice text
  required for Aves code in the tree (none is in the tree).
- **AGPL-3.0 (Ente ML engine, vendored under ADR-0020; r23 extends the vendored
  set with the photos-side persistence stack — `vecdb` whole and the `photos`
  crate's db/ml_db/ml_store/face_clustering modules)** → AGPL-3.0 is GPL-3.0-compatible
  one-way (AGPL code may enter a GPL-3.0 work only by re-licensing the combined derivative
  under AGPL-3.0). Consequence accepted by the maintainer (2026-11-02): distributed builds
  containing `engine/` are effectively offered under **AGPL-3.0**. The vendored crate tree
  keeps upstream's AGPL-3.0 via `engine/NOTICE.md` + crate-level `license` fields; the
  pinned upstream commit makes every adaptation diffable. No re-licensing of vendored
  files occurs.
- No proprietary or trademark-encumbered assets from Mi Gallery.

## 3. Obligations the project has accepted

1. **Source disclosure:** any distributed build must come with corresponding GPL-3.0
   source at the matching commit (GitHub releases must link the exact source tree; CI
   workflow per ADR-0013 is the canonical build recipe).
2. **Notice preservation:** upstream copyright notices and the GPL-3.0 text may never be
   removed from derivative files. (No upstream code was ported under ADR-0008; under
   ADR-0020 the vendored engine keeps upstream's AGPL-3.0 notice via `engine/NOTICE.md`
   plus crate-level `license` fields.)
3. **State changes:** substantial adaptation of upstream code should be marked. Done for
   the vendored Ente engine: `engine/NOTICE.md` is the authoritative adaptation list,
   diffable against the pin (ADR-0020).
4. **Per-file provenance:** any file copied or adapted from an upstream records origin +
   commit + license in the table below (§5) at the moment of the copy. The ledger stayed
   empty under ADR-0008 until r21, when the ADR-0020 vendoring populated it.
5. **Dependency licenses:** every binary dependency of shipped code gets a license check
   before first release (Phase 11 gate). The Phase 1 direct-dependency screen found no
   GPL-3.0 incompatibility in the AndroidX/Compose set; the Fossify-derived dependencies
   (`org.fossify:commons`, `androidphotofilters`, `jxl-coder-glide`) are slated for removal
   per ADR-0008 + `docs/architecture/compose-migration.md` and are not release blockers.

## 4. What is deliberately NOT allowed in this repo

- Any proprietary or brand asset from Xiaomi/Mi Gallery — inspiration of *interaction
  design* only; no assets, icons, names, or marketing text.
- No code whose license is unverified.
- No license laundering: files imported as "reference" that later end up in builds
  without provenance entries are treated as a release blocker.
- **No code ported from any upstream** (ADR-0008). This includes the Aves `exifinterface`
  fork (use `androidx.exifinterface` instead) and `org.fossify:commons` (use AndroidX +
  our own Compose theme). The ADR-0020 vendoring of the Ente engine is the single,
  recorded exception (AGPL-3.0, pinned, diffable — see §2 and §5).

## 5. Provenance ledger (fill at the moment of any copy/port)

| File(s) in toadGALLERY | Origin | Upstream commit | Upstream license | Adaptation notes |
|---|---|---|---|---|
| `engine/crates/ente-ml/**` | `ente/ente` `rust/crates/ml` @ `423b2d80` | `423b2d80d9ec89d45f5680cb1e1d777bac225f41` | AGPL-3.0 | Workspace-root recreation (dependency subset), `license.workspace = true` added; otherwise byte-identical; `engine/NOTICE.md` is the authoritative adaptation list |
| `engine/crates/ente-assets/**` | `ente/ente` `rust/crates/assets` @ `423b2d80` | (same pin) | AGPL-3.0 | Directory rename only + `license.workspace = true` |
| `engine/crates/ente-image/**` | `ente/ente` `rust/crates/image` @ `423b2d80` | (same pin) | AGPL-3.0 | Directory rename only + `license.workspace = true`; keeps upstream's git-pinned `heic-decoder` dep |
| `engine/crates/toad-ml-jni/**` | net-new Rust (r22, the JNI bridge over `ente-ml`/`ente-image`; r23 extends the surface with `nativeDetectFaceEmbeddingsJpeg` + the face-index store over the vendored `MlStore`) | --- | AGPL-3.0 | Not vendored --- net-new toadGALLERY code (the Kotlin-facing cdylib); `license.workspace = true` places it in the AGPL-3.0 engine workspace because it compiles against the AGPL vendored crates; the §2 distributed-binary consequence is unchanged |
| `engine/crates/ente-vecdb/**` | `ente/ente` `rust/crates/vecdb` @ `423b2d80` (r23, vendored whole) | (same pin) | AGPL-3.0 | Vendored byte-identical; `license.workspace = true` + directory rename only; `engine/NOTICE.md` is the authoritative adaptation list |
| `engine/crates/ente-photos-ml/**` | `ente/ente` `rust/crates/photos` (partial: `db`, `ml_db`, `ml_store`, `face_clustering` modules) @ `423b2d80` (r23) | (same pin) | AGPL-3.0 | Partial-crate extraction; the four modules are byte-identical except the two `use` import lines for `ente_core::crypto::random_bytes`, rewritten onto an engine-owned crypto seam (net-new `src/crypto.rs` + wrapper `lib.rs`, recorded in `engine/NOTICE.md`); the modules' `crate::db::…` imports are satisfied by vendoring the host `db` module in the same crate |
| `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ml/engine/*`, `.../ml/index/*` | net-new Kotlin | --- | GPL-3.0 (project) | Model manifest (all 11 assets match the pinned Rust catalog name+size+digest, test-pinned), verifier, provisioner; r23 adds `ml/index/IndexCoordinator.kt` (detect → embed → put → cluster over the JNI surface) --- Ente-inspired reimplementations, not ports |
| `apps/edit/src/main/assets/fonts/fira-sans/FiraSans-Regular.ttf` (+ `OFL.txt` beside it) | `google/fonts` `ofl/firasans` @ `2fe1e8c` | `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc` | SIL OFL 1.1 | r34 text tool, the default humanist sans slot (the prep doc's Noto Sans candidate swapped: its only unmodified full form is the 2.0 MB `wdth,wght` variable, over the round's whole budget; CSS-API statics are latin subsets --- `docs/phase-7/bundled-fonts.md`); verbatim byte-identical, sha256 `c29556a2…e0f93c23` pinned by `BundledFontsTest`; no Reserved Font Name declared |
| `apps/edit/src/main/assets/fonts/playfair-display/PlayfairDisplay[wght].ttf` (+ `OFL.txt`) | `google/fonts` `ofl/playfairdisplay` @ `2fe1e8c` | `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc` | SIL OFL 1.1 | r34 text tool, display serif slot; verbatim byte-identical variable font (default instance = Regular wght 400), sha256 `c40f2293…917d8d6` pinned by `BundledFontsTest`; unmodified so the RFN clause is trivially satisfied |
| `apps/edit/src/main/assets/fonts/caveat/Caveat[wght].ttf` (+ `OFL.txt`) | `google/fonts` `ofl/caveat` @ `2fe1e8c` | `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc` | SIL OFL 1.1 | r34 text tool, handwriting slot; verbatim byte-identical variable font (default instance = Regular wght 400), sha256 `0bdb6b66…34157988` pinned by `BundledFontsTest`; unmodified so the RFN clause is trivially satisfied |
| `apps/edit/src/main/assets/fonts/space-mono/SpaceMono-Regular.ttf` (+ `OFL.txt`) | `google/fonts` `ofl/spacemono` @ `2fe1e8c` | `2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc` | SIL OFL 1.1 | r34 text tool, mono slot; verbatim byte-identical static, sha256 `95837e18…933c8e18` pinned by `BundledFontsTest`; unmodified so the RFN clause is trivially satisfied |
| `apps/edit/src/main/assets/models/erase-segmentation-u2netp.onnx` | `xuebinqin/U-2-Net` (Apache-2.0), ONNX conversion `huggingface.co/BritishWerewolf/U-2-Netp` (tagged Apache-2.0) | (conversion repo, 2026-11-05 fetch) | Apache-2.0 | r41 Edit v3 AI, the magic-erase seed->object refinement + the sky saliency input (one graph serves both features); 4,574,861 bytes, sha256 `309c8469…c76f4ddd8` pinned by `EditAiManifestTest` (`EditAiManifest`); the Apache-2.0 notice rides the APK's About attribution |
| `apps/edit/src/main/assets/models/erase-inpaint-migan.onnx` | `Picsart-AI-Research/MI-GAN` (MIT, LICENSE fetched+verified), ONNX conversion `huggingface.co/andraniksargsyan/migan` (the first author's mirror, MIT) | (conversion repo, 2026-11-05 fetch) | MIT | r41 Edit v3 AI, the magic-erase inpainting fill; 29,546,882 bytes, sha256 `593eba0b…880fc5ae` pinned by `EditAiManifestTest`; MIT carries the attribution requirement (the About screen) and permits the GPL-3.0 mix |
| `apps/edit/src/main/assets/models/bokeh-depth-dav2-small-int8.onnx` | `depth-anything/Depth-Anything-V2-Small` (official card Apache-2.0), int8 ONNX conversion `huggingface.co/onnx-community/depth-anything-v2-small` (Apache-2.0) | (conversion repo, 2026-11-05 fetch) | Apache-2.0 | r41 Edit v3 AI, the bokeh depth estimation; 27,258,801 bytes, sha256 `01aa7a23…c6b9001` pinned by `EditAiManifestTest`; the int8 conversion's CPU-EP resolution is recorded in the round doc |
| `apps/edit/src/main/assets/models/retouch-faces-yunet.onnx` | `opencv/opencv_zoo` `models/face_detection_yunet` 2023mar (Apache-2.0) | (opencv_zoo, 2026-11-05 fetch) | Apache-2.0 | r41 Edit v3 AI, the OPT-IN face-retouch localization (ADR-0010 section 5 consent surface); 232,589 bytes, sha256 `8f2383e4…d2552fa4` pinned by `EditAiManifestTest` |
| (runtime dep, no file copy) `com.microsoft.onnxruntime:onnxruntime-android` 1.31.1 | Maven Central prebuilt AAR | 1.31.1 | Apache-2.0 | r41 Edit v3 AI inference seam (the prebuilt-AAR answer to the contract's open item 1 --- no editor NDK build surface); NNAPI-first with CPU fallback; declares no INTERNET permission, so the edit module's merged-manifest gate stays green and allowlist-free |
| `apps/edit/src/main/kotlin/io/github/dukeoftoad/gallery/edit/BundledSkies.kt` | net-new Kotlin (project-authored parametric skies) | --- | GPL-3.0 (project) | r41 Edit v3 AI, the five bundled skies (Day clear / Dusk / Night / Overcast / Golden hour) are pure deterministic render functions --- NO third-party sky bitmaps ship in the APK, so there is no image asset, no sha256 pin and no bitmap ledger row (the round doc records the decision) |

> The only upstream-derived artifact under ADR-0008 proper is the root `LICENSE` --- the
> canonical GNU GPL v3 text, the project's own voluntary license choice (ADR-0004
> clarification), no longer "taken from Fossify verbatim as required for GPL
> distribution". The ADR-0020 vendored Ente engine (rows above) is the recorded
> exception to the no-upstream-code rule.

> Short hashes in the Origin column (`e9812299`, `4f940ae`, `423b2d80`) are prefixes of
> the full commits recorded in the same row's Upstream-commit column or in §2.

## 6. Review gates

- Phase 1 exit: dependency license compatibility report for every library retained.
  Status: complete; re-evaluation under ADR-0008 tracked in
  `docs/architecture/compose-migration.md`.
- Phase 4 exit: metadata layer uses `androidx.exifinterface` (Apache-2.0) + our own
  IPTC/XMP wrappers. No Aves port. (Replaces the v1 "Aves port/adaptation records
  complete" gate.)
- Phase 11 exit: full license/attribution package, notices, and source-distribution
  procedure verified as part of release readiness. CI workflow is the canonical
  reproducible-build recipe (ADR-0013).
