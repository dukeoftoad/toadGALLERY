# Phase 11 --- the third-party FOSS review record (self-conducted)

**Status:** the roadmap's Phase 11 task "commission or self-conduct a
review of the final source tree for license compliance, attributions,
and any accidentally included proprietary code" --- **self-conducted**,
in the r45 docs round, over the tree as it stands at the r43--r45 merge
window (base `f610860` + the parallel release-engineering and
F-Droid-metadata rounds landing in the same window). This document
records what was checked, what the repo's own records support, and ---
honestly --- what could not be verified from the records.

---

## Method and scope

The review is a records audit: every claim below traces to a committed
document, test, or manifest in this repository, cited inline. The tree
reviewed: the full default-branch tree --- `apps/` (gallery + edit),
`shared/`, `engine/`, `docs/`, the build files, the workflow files, and
the assets in `apps/edit/src/main/assets/`.

### 1. The dependency screen

Checked against `docs/phase-1/dependency-license-compatibility.md` (the
Phase 1 direct-dependency screen, as superseded by ADR-0008 and issue
#21):

- The direct dependency set is permissive-only and GPL-3.0-compatible:
  the AndroidX/Compose stack, `androidx.media3` 1.11.1, `androidx.work`
  2.10.1, Room, Coil, `androidx.exifinterface` --- all Apache-2.0;
  `onnxruntime-android` 1.31.1 (Apache-2.0, Maven Central prebuilt AAR,
  the r41 edit-AI inference seam). **No GPL-incompatible dependency is
  present**; the screen's two former blockers
  (`androidphotofilters`, `jxl-coder-glide`) were DROPPED per ADR-0008
  and never entered the build.
- The one GPL-family runtime element is the vendored Ente engine ---
  see item 3 below, which is the review's headline finding and is
  accepted on record, not accidental.
- The parallel r44 round's F-Droid metadata (`fdroid/metadata/`, see
  `docs/phase-11/fdroid-metadata.md`) carries its own dependency audit
  for the submission; it lands in the same window and cross-checks this
  screen. Note: that audit is the r44 agent's record --- this review
  references it and does not duplicate it.

### 2. The provenance ledger (no ported upstream code)

Per `docs/licensing-and-attribution.md` §5: **no code is ported from
Mi Gallery (proprietary), Fossify Gallery (GPL-3.0), or Aves libre
(BSD-3-Clause)** --- the ADR-0008 features/UI/UX/designs-only stance.
The three references are named as design references in the About-screen
attribution, carefully without implying endorsement; no Mi Gallery
asset, icon, name, or marketing text exists in the tree. The ledger is
NOT empty --- since r21 it records the one sanctioned exception (the
ADR-0020 vendored Ente engine) plus every copied asset: the four OFL
fonts (item 5) and the four ONNX models (item 4). Every row carries
origin + upstream commit + license + adaptation notes, per the
obligations register. **Verified against the actual tree: the ledger's
rows match the files that exist; no un-ledgered copied file was found
by this review's asset scan.**

### 3. The vendored engine (`engine/`) --- the AGPL-3.0 posture

`engine/` is a vendored, adapted extract of the Ente Photos Rust ML
engine, **AGPL-3.0** © Ente Services Pvt. Ltd. and contributors,
incorporated under ADR-0020 with the maintainer's explicit direction
(2026-11-02). Per `engine/NOTICE.md`:

- The pin is authoritative (`423b2d80d9ec89d45f5680cb1e1d777bac225f41`,
  2026-11-02); five vendored crates (`ente-ml`, `ente-assets`,
  `ente-image`, `ente-vecdb`, `ente-photos-ml`) plus the net-new
  `toad-ml-jni` bridge and the upstream-derived `.cargo/config.toml`
  subset.
- The adaptation list is complete and re-runnable: every vendored file
  is byte-identical to `git archive` of the pin except the recorded
  workspace-root changes, the crate renames, the added
  `license.workspace = true` fields, and (r23) the two `random_bytes`
  import lines rewritten onto the engine-owned crypto seam. Upstream's
  copyright notices and license are preserved (crate-level AGPL-3.0
  declarations + `engine/NOTICE.md`).
- **The consequence, on record and accepted:** AGPL-3.0 is
  GPL-3.0-compatible one-way only --- distributed builds containing
  `engine/` would be effectively offered under **AGPL-3.0** (the
  maintainer accepted this 2026-11-02; `docs/licensing-and-attribution.md`
  §2). **The honest fact for distribution, recorded by the parallel r44
  round** (`docs/phase-11/fdroid-metadata.md`): **no distributed APK
  contains the engine.** The native libraries only enter the gallery APK
  under the opt-in `-PwithEngine` build property, and every published
  build path --- ci.yml, release.yml, and the F-Droid recipe --- builds
  without it; the Kotlin facade treats the missing library as an
  unavailable engine and the engine-dependent AI features degrade
  honestly. So the AGPL-3.0 consequence governs the **source
  distribution** (the repository itself carries the vendored AGPL code,
  source-available per the license), not any shipped binary; both apps'
  `License: GPL-3.0` claim is accurate for every artifact actually
  distributed. **Open finding F-11-1 below**: the README's license
  section does not yet name the source-tree's AGPL engine exception ---
  recommend the lead fold one sentence into the README license section
  at the r45 merge review.

### 4. The AI models

- **The four editor models** (r41, ship-in-APK): u2netp (Apache-2.0,
  via the tagged-Apache ONNX conversion), MI-GAN inpaint (MIT, LICENSE
  fetched + verified at fetch time), Depth-Anything-V2-Small int8
  (Apache-2.0), YuNet face detection (Apache-2.0, opencv_zoo 2023mar).
  All four are committed under `apps/edit/src/main/assets/models/`,
  pinned by name + size + full sha256 in `EditAiManifest.kt` and
  asserted against the committed bytes by `EditAiManifestTest`; ledger
  rows record source, conversion chain, and license per ADR-0010 §6.
  All inside ADR-0010 §2's preference bands and GPL-3.0-mix compatible.
- **The gallery-side engine models** (ADR-0020 runtime download from
  `models.ente.com`, 11 pinned assets): the delivery policy is fully
  recorded (pinned host, size + SHA-256 verification, allowlist
  protocol). **Open finding F-11-2**: the individual upstream license
  terms of those 11 model files are not individually recorded in the
  licensing ledger --- the repo records the ENGINE code's AGPL-3.0 and
  the delivery policy, but no per-asset license rows for the downloaded
  models. ADR-0010 §6's "model attribution and license recorded"
  requirement is fully met for the four editor models; for the gallery
  models this review could not verify per-asset terms from the records
  and records the gap rather than asserting it. Recommend: a ledger
  subsection naming the 11 assets' upstream licenses (Ente's own model
  distribution), or an explicit maintainer statement that the AGPL
  engine's model catalog carries the project's accepted posture.

### 5. The assets

- **LUTs** (r33): the five bundled looks are project-authored ---
  generated by a committed deterministic generator; the licensing
  evidence is `docs/phase-7/bundled-luts.md` (no separate asset license
  because the assets are the generator's output --- GPL-3.0 project
  code). User-imported `.cube` files are user data, never distributed.
- **Stickers** (r35): eight project-authored bundled stickers as
  in-code vector shapes (integer coordinates, fills only) --- no image
  asset ships; authorship record `docs/phase-7/bundled-stickers.md`.
- **Frames** (r38): five project-authored parametric frames as pure
  integer geometry functions --- no image asset ships.
- **Skies** (r41): five project-authored parametric skies as pure
  deterministic render functions --- **no third-party sky bitmap ships
  in the APK** (ledger row records the decision).
- **Fonts** (r34): four faces bundled verbatim from `google/fonts`
  @ `2fe1e8c` --- Fira Sans, Playfair Display, Caveat, Space Mono ---
  each **SIL OFL 1.1** with its `OFL.txt` committed beside the TTF and
  the sha256 pinned by `BundledFontsTest`; the license rows live in the
  ledger §5 and `docs/phase-7/bundled-fonts.md`. No Reserved Font Name
  issue (all four are used unmodified, so the RFN clause is trivially
  satisfied).

### 6. Binary-blob scan

The tree contains **no prebuilt executables or opaque binary blobs**
other than: (a) the four ONNX model files in the edit app --- data
files, sha256-pinned, licenses ledgered (item 4); (b) the standard
Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`, the standard
Gradle-distributed binary); and (c) committed font TTFs (OFL, item 5).
No `.so`, `.a`, `.class`, `.jar`, or executable blobs are committed ---
the native `libtoad_ml_jni.so` + `libonnxruntime.so` are built or
fetched by `engine/scripts/build-android.sh` at build time (the ONNX
Runtime supply is sha256-pinned in the script), never committed. The
edit app's ONNX Runtime arrives via the Maven Central AAR dependency,
not a committed blob.

### 7. GPL-3.0 (and AGPL-3.0) obligations

- **License text present:** the root `LICENSE` carries the GNU GPL v3
  text (the project's voluntary choice, ADR-0004/0008).
- **Source distribution:** every release tag points at buildable source
  in this repository, and **CI is the canonical build recipe**
  (ADR-0013) --- the r44 round's reproducible-build instructions
  (`docs/phase-11/reproducible-builds.md`, landing in this window)
  document building bit-comparable APKs from any tag.
- **Notice preservation:** upstream notices preserved in the vendored
  engine (item 3); Apache-2.0/MIT notices for the models ride the APK
  About attribution (the ledger rows record this; the r41 device row
  for the About-screen attribution surfacing is human-only, so
  on-device verification is pending --- noted, not claimed).
- **Per-file provenance:** the §5 ledger (item 2).
- **Open finding F-11-3:** the obligations register
  (`docs/phase-1/dependency-license-compatibility.md` §6, item 3)
  planned a machine-readable `THIRD_PARTY_NOTICES` package "at Phase 9
  release packaging"; no such file exists in the tree. The
  human-readable record (`docs/licensing-and-attribution.md`) and the
  About-screen credits carry the content today. Recommend: generate the
  notices file (or amend the register) before the v1.0 cut.

## Findings and verdict

**Findings (all open items, none blocking the pre-release, all recorded
for the lead's merge review):**

- **F-11-1** --- the README license section does not yet name the
  source tree's AGPL-3.0 engine exception (item 3); no distributed
  binary contains the engine, so this is source-attribution
  completeness, not a distribution violation.
- **F-11-2** --- the 11 gallery-side downloaded models lack per-asset
  license rows in the ledger (item 4).
- **F-11-3** --- the planned machine-readable `THIRD_PARTY_NOTICES`
  package does not exist (item 7).
- **F-11-4 (observation)** --- the About-screen attribution surfacing
  for the edit app's Apache/MIT model notices is a recorded r41
  human-only device row; this review verifies the record exists, not
  the on-device rendering.

**Verdict:** from the repo's own records, the tree is **clean of
accidentally included proprietary code** --- every upstream-derived
element is vendored on record with its license preserved and its
adaptations diffable against a pin, every asset is either
project-authored or license-ledgered with a committed license text, and
the dependency set is permissive plus the one accepted AGPL engine
exception. No GPL-3.0 distribution obligation is unmet by the records:
license text, source-at-tag, notices, and provenance are all in place,
with the four findings above as the honest remainder --- three are
documentation-completeness items and one is a display-verification item
that belongs to the human-only device rows. The review is
self-conducted per the roadmap's "commission or self-conduct" option;
it recommends the findings be closed in the merge review or scheduled
before the v1.0 stable cut.
