# Integration Decisions — Architecture Decision Records

**Format:** lightweight ADRs. Each decision states context, options considered, and the
choice. Decisions are never edited in place after acceptance — a superseding ADR is added.
Statuses: `PROPOSED` (awaiting phase evidence) → `ACCEPTED` (evidence supports it) → `SUPERSEDED`.

**Latest revision:** 2026-10-29 — ADR-0008 supersedes ADR-0001 (features/UI/UX/designs only,
no code ports, no `org.fossify:commons`); ADR-0009 supersedes ADR-0005 (Jetpack Compose, not
View-based); ADR-0010/0011/0012 introduced (on-device AI, non-destructive pipeline, video
editor); ADR-0013 introduced (GitHub Actions as the build environment).

---

## ADR-0001 — Primary architecture and upstream integration approach

**Status:** **SUPERSEDED 2026-10-29 by ADR-0008** (kept for history; do not act on this ADR).
Originally ACCEPTED 2026-10-29 on Phase 1 source-audit evidence.

**Original decision.** Native Kotlin base seeded from the Fossify Gallery code base; Aves
contributes as a design/format/metadata reference with reimplementation in Kotlin; one
code-level adaptation: the Apache-2.0 exifinterface fork from Aves.

**Why superseded.** The product direction was revised on 2026-10-29: toadGALLERY now takes
**features, UI/UX, and designs only** from all upstreams (Mi Gallery, Fossify, Aves). No code
is ported from any upstream — not from Fossify (no `org.fossify:commons` dependency, no
skeleton reuse), and not from Aves (no `exifinterface` fork port — use `androidx.exifinterface`
directly). toadGALLERY is built from scratch in Kotlin + Jetpack Compose (ADR-0009). See
ADR-0008 for the authoritative statement.

**Context.** toadGALLERY builds on two upstreams with incompatible stacks:
Fossify Gallery is ~100% Kotlin (native Android, ViewBinding, Room, Glide; targetSdk 36);
Aves is ~74% Dart on Flutter 3.47.8 with ~18% Kotlin native modules (compileSdk 37).
The roadmap forbids assuming an integration method before the audit.

**Options considered.**
1. **Native Kotlin base (Fossify skeleton) + selective Aves ports.** *(Originally chosen; now superseded.)*
2. Flutter base (Aves skeleton) + Fossify ports (mirror problem, editor rewritten, GPL Dart reimplementation).
3. Literal repository merge / dual-UI product (rejected: two UI stacks, unmaintainable).
4. Clean-room rebuild (originally rejected; **now chosen by ADR-0008** with the clarifying
   constraint that upstreams contribute features/UI/UX/designs only).

**Evidence (Phase 1 audit, 2026-10-29) — retained for reference.**
- Fossify baseline **builds and packages successfully** in our toolchain.
- Fossify's codebase is small (~105 Kotlin files), coherent, GPL-3.0, and its media stack
  (MediaStore → Room → Glide/media3 → ViewBinding) informed the product's target shape.
- Aves' value is in its *models* — URI-first entry, three-date semantics, progressive
  metadata, filter algebra, format taxonomy, no-op service pattern — all documented in
  `source-audit-aves.md` as Kotlin reimplementation references. Its Dart UI cannot be reused.
- The Phase 1 dependency/license screen found no blocker that argued for a different base.

**Consequences (historical).** Under ADR-0001 the product carried `org.fossify:commons` as a
dependency and the Aves exifinterface fork as a port. Both are removed by ADR-0008.

---

## ADR-0002 — toadGALLERY Edit is a separate APK with a versioned content-URI contract

**Status:** ACCEPTED (2026-10-29; contract spec finalized as ADR-0007/architecture doc) · **Date:** 2026-10-29

**Context.** The roadmap requires a separate installable editor, updatable independently.
Fossify's editor is built-in today (activities, libraries); Aves keeps an in-app transform
editor.

**Decision.** The main Gallery app never embeds the editor. A separate APK
(**toadGALLERY Edit**) is launched via a **versioned intent contract** (specified in
ADR-0007 and `docs/architecture/editor-contract.md`): Gallery grants URI-scoped read
permission for the source media; the editor returns/publishes the result or a structured
error; absolute file paths are never part of the contract. Gallery defines behavior for
missing / outdated / format-limited extension (checked via manifest meta-data). Default
save mode is **Save copy**; replace-original is opt-in per operation proven safe. Metadata
(GPS/EXIF) is preserved unless the user chooses explicit export settings.

**Evidence.** Fossify already publishes an editor-facing intent surface
(`ACTION_EDIT`, `com.android.camera.action.CROP` in its manifest) — extraction follows a
path upstream itself partially treads. The seam is clean: editor code is localized
(source-audit-fossify.md §8).

**Unaffected by ADR-0008.** The contract is between two toadGALLERY APKs we build ourselves.
The contract is unaffected by whether the gallery or editor code is ported or reimplemented.

---

## ADR-0003 — Upstream tracking: pinned commits, no embedded forks

**Status:** ACCEPTED (in effect since Phase 0; re-affirmed 2026-10-29; clarified by ADR-0008) · **Date:** 2026-10-29

**Decision.** Upstreams are recorded by exact pinned commit in
`docs/phase-0/upstream-baselines.md` and cloned locally on demand (gitignored). toadGALLERY
does not vendor, subtree, or submodule either upstream; any future change to this policy
is a superseding ADR. History/attribution is preserved via recorded SHAs, per-file
provenance notes, and upstream repositories themselves.

**Clarification added by ADR-0008 (2026-10-29).** Under the features-only stance, "pinned
commits" remains the practice, but the pins now exist to identify **which version of each
upstream we drew features/UI/UX/designs from** — not which version we ported code from.
The provenance ledger at `docs/licensing-and-attribution.md` §5 stays empty (no upstream
code is copied).

**Evidence.** The bootstrap experience validated this: both baselines were pulled, audited,
and (in Fossify's case) built, from pinned commits without entangling histories. The Aves
baseline audit surfaced upstream defects (stale flavor pins, premature compileSdk 37) that
were cleanly documented *because* our tree stays decoupled.

---

## ADR-0004 — Product license: GPL-3.0

**Status:** ACCEPTED (2026-10-29) · **Clarified by ADR-0008 (2026-10-29).** · **Date:** 2026-10-29

**Decision.** The combined work is distributed under **GPL-3.0** (root `LICENSE`).

**Clarification added by ADR-0008.** Under ADR-0001 the GPL-3.0 choice was *obligatory*
because the product derived from Fossify's GPL-3.0 code. Under ADR-0008 the product derives
from no upstream code, so GPL-3.0 is now a **voluntary choice** that aligns with the
FOSS/gallery community ethos and keeps the door open to future code-level collaboration with
Fossify-org-licensed projects. The choice stands; the rationale changes.

**Dependency screen.** `docs/phase-1/dependency-license-compatibility.md` screens the
dependency set; two items were flagged as release blockers pending POM verification
(androidphotofilters fork, jxl-coder-glide). Under ADR-0008 these dependencies are
re-evaluated because their inclusion was driven by the Fossify-seed decision; both are
candidates for replacement with our own Compose-native implementations.

---

## ADR-0005 — App identity and UI stack

**Status:** **SUPERSEDED 2026-10-29 by ADR-0009** (kept for history; do not act on this ADR).
Originally ACCEPTED 2026-10-29.

**Original decision.** View-based Android UI (ViewBinding + Material themes) on top of
`org.fossify:commons`. No Compose. Application IDs: Gallery `io.github.dukeoftoad.gallery`
(displayed "toadGALLERY"); Edit extension `io.github.dukeoftoad.gallery.edit` (displayed
"toadGALLERY Edit"). Design system: token-based, Mi-Gallery-inspired, Material theme
overlays + commons' color system. Navigation: single-activity-per-screen, four top-level
destinations (Photos, Albums, Search, Settings).

**Why superseded.** Two independent directives both invalidate this ADR:

1. The user directive "build on Kotlin and Jetpack Compose if modern, else follow up Flutter
   but rethink" explicitly selects Compose, contradicting the "No Compose" clause.
2. ADR-0008 removes `org.fossify:commons` from the dependency set. ADR-0005 was built on top
   of commons' base activities, theme system, and color system; without commons, the ADR-0005
   stack cannot stand.

**What survives into ADR-0009.** The application IDs (`io.github.dukeoftoad.gallery`,
`io.github.dukeoftoad.gallery.edit`), the four top-level destinations (Photos, Albums,
Search, Settings), and the Mi-Gallery-inspired design direction all carry forward. Only the
toolkit (Views → Compose) and the dependency (`org.fossify:commons` → none) change.

**Consequences (historical).** The Phase 2 app shell at `apps/gallery/` was built against
ADR-0005 (View-based, commons-dependent, 0.1.0-alpha, 30 MB APK). Under ADR-0009 this shell
is to be migrated incrementally to Compose; see `docs/architecture/compose-migration.md`.

---

## ADR-0006 — Data model: URI-first, MediaStore-anchored

**Status:** ACCEPTED · **Date:** 2026-10-29

**Context.** The two bases disagree fundamentally on media identity (audited):
Fossify keys everything by filesystem path (with an only-recent `media_store_id` column);
Aves keys by content URI + MediaStore ID, derives paths lazily, and reads the platform
trash flag. The roadmap requires content-URI boundaries and no reliance on raw paths.

**Decision.** toadGALLERY's model layer adopts the **Aves shape in Kotlin**:
- Primary identity: **content URI + MediaStore ID**; path is derived metadata, never a key.
- Dates follow the **three-date model**: `date_added`, `date_modified`,
  `date_taken` (+ timezone offset where the catalog provides it).
- Room remains the persistence layer, but schema v1 of `toadGALLERY.db` starts
  URI-keyed — no migration from Fossify's schema, no destructive-migration fallback.
- Favorites: keyed by (uri, id).
- Trash: platform trash API where the OS supports it; app-level fallback documented in
  a later ADR when semantics are finalized.

**Unaffected by ADR-0008.** The URI-first model is a design decision, not a code port. The
Aves shape was always going to be reimplemented in Kotlin (even under ADR-0001 the "port"
was a reimplementation); ADR-0008 only removes the ambiguity about whether any Aves code
was being copied (it was not, and is not).

---

## ADR-0007 — Editor integration contract v1 (content-URI, versioned)

**Status:** ACCEPTED (spec) · **Date:** 2026-10-29 · **Full spec:** `docs/architecture/editor-contract.md`

**Decision.** Gallery ↔ Edit communication uses a **versioned intent contract**
(`CONTRACT_VERSION = 1`): Gallery sends a single content URI with `FLAG_GRANT_READ_URI_PERMISSION`
(+ ClipData), an optional operation hint, and export preferences; Edit returns
`RESULT_OK` with the output URI (via MediaStore insert or a pre-granted target URI),
`RESULT_CANCELED`, or `RESULT_ERROR` with a structured error code (unsupported format,
version mismatch, IO failure). Missing/outdated extension states are Gallery's
responsibility and are user-explained. Version negotiation is by manifest meta-data
published by the editor (`org.toadgallery.edit.CONTRACT_VERSION`), with the rule:
**gallery refuses to launch below its minimum supported version and prompts to update**.

**Consequences.** The Phase 6 editor is built against a frozen contract; the Gallery can
ship third-party editor support (Fossify-style `ACTION_EDIT`) as a fallback path when the
extension is missing — one recovery path, not two.

**Unaffected by ADR-0008.** The contract is a toadGALLERY-to-toadGALLERY protocol; no
upstream code is involved.

---

## ADR-0008 — Features/UI/UX/designs only from upstreams; build from scratch (SUPERSEDES ADR-0001)

**Status:** ACCEPTED (2026-10-29, user directive) · **Date:** 2026-10-29 · **Supersedes:** ADR-0001

**Context.** User directive 2026-10-29: "we are taking only features, ui/ux, designs from
all apps for toadGALLERY." This revises the integration approach from "seed from Fossify +
selective Aves ports" to "take inspiration only, build everything from scratch." The
directive is unambiguous and applies to all three upstreams (Mi Gallery, Fossify, Aves).

**Decision.**

1. **No code is ported, adapted, or vendored from any upstream.** This applies to Mi
   Gallery (already the case — proprietary), Fossify Gallery (new — no skeleton, no
   `org.fossify:commons` dependency), and Aves libre (new — no `exifinterface` fork port).
2. **toadGALLERY is built from scratch in Kotlin + Jetpack Compose** (per ADR-0009).
3. **What we *do* take from upstreams:**
   - **Features** — the set of capabilities (e.g. "magic erase," "EXIF write," "people
     clustering," "stories," "trim video") is informed by what Mi Gallery, Fossify, and
     Aves libre offer.
   - **UI/UX patterns** — interaction design (e.g. "swipe up to reveal metadata," "long-press
     to multi-select," "pinch to switch timeline/album/grid") is informed by what those
     apps do well. We reimplement the interactions natively in Compose.
   - **Designs** — visual direction, information density, color/typography/spacing
     decisions, animation feel. We do not copy assets, icons, trademarks, or proprietary
     artwork. Mi Gallery is the primary visual reference for the editor and the
     organisational UX; Aves libre is the primary reference for the metadata Details view;
     Fossify is the primary reference for the privacy posture and offline-first behavior.
4. **The `exifinterface` strategy is changed.** Under ADR-0001 we planned to port Aves's
   Apache-2.0 `exifinterface` fork. Under ADR-0008 we instead use `androidx.exifinterface`
   directly (Apache-2.0, published by Google's AndroidX team) and write our own IPTC/XMP
   wrappers around it. No Aves code enters the tree.
5. **The `org.fossify:commons` dependency is removed.** The Phase 2 app shell at
   `apps/gallery/` currently depends on commons; per ADR-0009 + the compose-migration
   tracker, the shell is migrated to a self-contained Compose + Material 3 implementation
   with no commons dependency. The migration is incremental; see
   `docs/architecture/compose-migration.md`.
6. **The Phase 1 source audits remain valid as feature/UI/UX references.** The audits at
   `docs/phase-1/source-audit-fossify.md` and `source-audit-aves.md` are reinterpreted
   from "code inventory for porting" to "feature/UI/UX inventory for reimplementation
   inspiration." Their content is unchanged; their framing is updated.
7. **The licensing-and-attribution doc is updated.** The provenance ledger at §5 stays
   empty (no upstream code is copied). The GPL-3.0 license choice is now voluntary
   (ADR-0004 clarification), not obligatory.

**Consequences.**

- Pro: Full architectural freedom. No inherited technical debt from Fossify's
  path-keyed Room schema, no inherited `fallbackToDestructiveMigration`, no inherited
  View-based UI obligation, no inherited dependency on `org.fossify:commons`'s
  evolution cadence.
- Pro: Cleaner license story. The product is GPL-3.0 by choice; no Apache-2.0 port
  attribution to maintain for the exifinterface fork; no GPL-3.0 obligation inherited
  from Fossify.
- Pro: Smaller, more focused dependency tree. We pull only what we need, not what
  commons brings transitively.
- Con: More upfront implementation work. The browsing grid, viewer, file ops, trash,
  favorites, and settings UI must all be written from scratch in Compose rather than
  reused from Fossify. The compose-migration tracker makes this work visible.
- Con: We lose the ability to cherry-pick upstream Fossify fixes. Mitigation: the
  source audits already extracted the patterns we care about; we apply fixes by
  re-deriving them in our code, not by porting.
- Con: We lose the `org.fossify:commons` SAF plumbing, theming, and base activities.
  Mitigation: Compose + Material 3 + AndroidX SAF primitives cover the same surface.

**Open questions for Phase 2 (compose-migration tracker).**

- Confirm `androidx.exifinterface` has the write support we need (it does for EXIF;
  IPTC/XMP wrappers are net-new code regardless).
- Decide whether to keep `androidx.media3` for video playback (yes — it is not an
  upstream port, it is an AndroidX library).
- Decide whether to keep Glide for image loading (yes — same reasoning; or evaluate
  Coil as a Compose-first alternative).

---

## ADR-0009 — Jetpack Compose UI toolkit; Material 3 + dynamic color (SUPERSEDES ADR-0005)

**Status:** ACCEPTED (2026-10-29, user directive) · **Date:** 2026-10-29 · **Supersedes:** ADR-0005

**Context.** User directive 2026-10-29: "build on Kotlin and Jetpack Compose if modern, else
follow up Flutter but rethink." Combined with ADR-0001's no-Flutter stance (re-affirmed by
ADR-0008), this is the definitive UI toolkit decision. ADR-0005 said "No Compose"; this ADR
reverses that.

**Decision.**

1. **Jetpack Compose is the canonical UI toolkit** for all toadGALLERY UI, gallery and
   editor alike.
2. **Material 3 (Material You) + dynamic color** is the design system. Light/dark themes
   via `dynamicColorScheme`; static fallback palette for devices without dynamic color
   support.
3. **The Phase 2 app shell at `apps/gallery/` migrates incrementally.** The current shell
   is View-based + `org.fossify:commons` (per superseded ADR-0005). The migration:
   - Add the Compose BOM + Material 3 + Activity Compose + Compose View interop to the
     gallery module's `build.gradle.kts` (additive — does not break the existing View-based
     shell).
   - Replace `org.fossify:commons`'s base activity, theme, and color system with a
     self-contained Compose + Material 3 equivalent.
   - Replace `MainActivity.kt` and `PlaceholderFragment.kt` with a Compose-native
     `setContent { ... }` activity hosting a `NavHost` for the four top-level destinations.
   - Migrate downstream screens as each Phase 3+ feature lands (no big-bang rewrite).
   - Track migration status in `docs/architecture/compose-migration.md`.
4. **No `org.fossify:commons` dependency.** Anything previously provided by commons
   (BaseSimpleActivity, AppCompat theme lineage, color helpers, SAF utilities) is
   replaced with AndroidX equivalents or written fresh.
5. **Kotlin 2.0+ Compose compiler plugin** is added to the root `build.gradle.kts` and
   the gallery module. Versions are pinned deliberately in `gradle/libs.versions.toml`.
6. **Material 2 theme files** (`apps/gallery/src/main/res/values/styles.xml`,
   `colors.xml`) are kept as legacy theme entries until all View-based screens are
   migrated; they are deleted at the end of the migration.
7. **No Flutter.** Restated for emphasis; ADR-0008 is authoritative.

**Consequences.**

- Pro: Flagship new features (editor, AI tools, metadata Details, search, auto-albums,
  Stories, collage, slideshow, vault, recycle bin) get a modern toolkit from day one.
- Pro: Working baseline is preserved during the migration (the View-based shell still
  builds and runs until each screen is replaced).
- Pro: Material 3 + dynamic color gives a coherent Mi Gallery-grade polish story.
- Pro: Contributors who know Compose can ship new features without learning Fossify's
  View-based internals or `org.fossify:commons`'s custom base classes.
- Con: Two UI toolkits coexist during the migration. Mitigation: the migration tracker
  makes the debt visible; each phase's exit criteria include "no new View-based screens
  added."
- Con: Interop glue at the seams is a small ongoing cost. Mitigation: keep the seams
  narrow; prefer Compose hosted in a Fragment over View embedded in Compose.

**Open questions for Phase 2.**

- Confirm Kotlin 2.0+ Compose compiler and Compose BOM version compatibility with AGP
  9.2.1 and minSdk 26 (the existing libs.versions.toml already pins Kotlin 2.3.10 —
  Compose compiler ships with Kotlin 2.0+, so this is just adding the BOM).
- Decide whether the editor's high-performance preview surface uses Compose Canvas or a
  custom `SurfaceView` wrapped via `AndroidView`. Initial lean: Compose Canvas for editor
  chrome; custom `SurfaceView` for the high-throughput image preview, wrapped via
  `AndroidView`. To be prototyped in Phase 6.
- Decide whether to keep Glide for image loading or migrate to Coil (Compose-first).
  Initial lean: Coil, because its API is Compose-native (`AsyncImage` composable) and
  removes a Glide-View interop seam.

---

## ADR-0010 — On-device AI policy (NEW, v2.0 roadmap)

**Status:** ACCEPTED (policy); **Date:** 2026-10-29 · **Application phases:** 5, 8.

**Context.** The v2 roadmap introduces on-device AI features in Phase 5 (People clustering,
Things tagging, semantic search) and Phase 8 (magic erase, sky replacement, bokeh, opt-in
face retouch). The hard constraint is that toadGALLERY must remain offline-first with no
`INTERNET` permission in the core manifest and zero outbound network traffic in the privacy
test suite. Therefore, all AI features must run with models shipped in the APK and inference
happening on the user's device.

**Decision.**

1. **No cloud ML API calls anywhere in the editor or gallery, ever.**
2. **All models ship in the APK.** Model files are licensed under terms compatible with a
   GPL-3.0 product (preferred: Apache-2.0 or MIT; acceptable: CC-BY-4.0; unacceptable: any
   license that restricts redistribution or that requires attribution beyond a notice file).
3. **Inference runtime** uses Android's NNAPI where available (for hardware acceleration
   via NPU/GPU/DSP), with a CPU fallback via TensorFlow Lite or ONNX Runtime. The runtime
   choice is recorded in this ADR once Phase 1 evaluates the options.
4. **No model download at runtime.** If a feature's model is too large to ship in the APK,
   the feature does not ship. There is no "download model on first use" path.
5. **Opt-in by default for face features.** People clustering and face retouch are disabled
   by default and require an explicit user opt-in. The consent dialog explains what the
   model does, that it runs on-device, and that no face data leaves the device. The user
   can permanently disable these features in Settings.
6. **Model attribution and license** are recorded in `licensing-and-attribution.md` and
   surfaced in the About screen.
7. **The privacy test suite** is extended in Phase 8 to assert zero outbound connections
   during every AI feature.

**Consequences.**

- Pro: The product's privacy invariant holds even with AI features.
- Pro: AI features work offline, on planes, in tunnels, and on devices without Google
  Play Services.
- Con: Model size hits APK size. Mitigation: ship the largest models (e.g. inpainting) as
  app-bundle dynamic feature modules delivered on demand *only via the Play Store dynamic
  delivery path* — but this is incompatible with F-Droid distribution and the no-network
  invariant, so the realistic mitigation is to choose models that fit in a reasonable APK
  size budget. If a model does not fit, the feature does not ship in v1.0.
- Con: Model quality is bounded by what can run on a mid-range phone. Magic erase will
  not be as good as a server-side diffusion model. The product copy must be honest.

**Open questions for Phase 1.**

- Which specific models to evaluate for: face clustering, object/scene tagging, semantic
  embedding, segmentation, inpainting, depth estimation, sky segmentation, face retouch.
  Initial candidates: MediaPipe (Apache-2.0), ONNX model zoo (mixed licenses),
  TensorFlow Hub (mixed).
- Confirm NNAPI + TF Lite vs. NNAPI + ONNX Runtime is the right runtime stack on minSdk 26.
- Decide whether the largest models (inpainting, sky replacement) can fit in a reasonable
  APK size budget. If not, those features slip past v1.0.

---

## ADR-0011 — Non-destructive edit pipeline format (NEW, v2.0 roadmap)

**Status:** ACCEPTED (policy); **Date:** 2026-10-29 · **Application phase:** 6.

**Context.** The editor must be non-destructive: the original bytes are never modified until
the user explicitly exports. Edits are stored as a replayable recipe that the editor can
re-render on demand.

**Decision.** Use a custom JSON sidecar as the canonical format, with an XMP export option
for interoperability.

- The canonical recipe is a JSON file stored at `<media-stem>.toadedit.json` next to the
  original (where file system permits) or in app-private storage keyed by content URI.
- The JSON schema is versioned (top-level `schema_version` integer).
- The schema covers: source URI hash, list of operations (crop, adjustment, filter, text
  layer, sticker, drawing, mosaic, frame, AI operation) with parameters, layer order, and
  export settings.
- An XMP export writes the same operations into an XMP sidecar using a custom namespace
  (`xmlns:toadedit`). One-way export for interoperability; we do not read XMP recipes back
  in v1.0.
- The original bytes are never modified. "Replace original" exports to a temp file and
  atomically swaps, with the original moved to the recycle bin first.

**Consequences.** See v2 roadmap §ADR-0011. Net: we control the schema; JSON is debuggable;
XMP export gives a baseline interop story; the sidecar is non-standard but exportable.

**Open questions for Phase 6 design.**

- Confirm where sidecars can live on minSdk 26 / target 36 with scoped storage.
- Decide the JSON schema's exact shape.

---

## ADR-0012 — Video editor scope and pipeline reuse (NEW, v2.0 roadmap)

**Status:** ACCEPTED (policy); **Date:** 2026-10-29 · **Application phase:** 9.

**Context.** The v2 roadmap adds a lightweight video editor in Phase 9 (trim, mute,
slow-mo, frame extraction, GIF/WebP export, light colour adjust).

**Decision.**

1. **Scope v1.0 video editor to:** trim, mute, slow-mo, frame extraction, GIF/WebP export.
2. **Light colour adjust on video is in scope but bounded:** only the Phase 6 adjustments
   that can be applied as a real-time shader (brightness, contrast, saturation, exposure,
   temperature, tint, highlight, shadow, vignette) are exposed on video. HSL per-channel
   and AI features are NOT exposed on video in v1.0.
3. **Reuse the Phase 6 pipeline parameter set**, not the pipeline implementation. The
   video path renders via a media3 `ExoPlayer`/`Transformer` with a GL shader that
   consumes the same parameter struct as the photo pipeline.
4. **Non-destructive:** the original video is never modified; the editor exports a new file.
5. **Out of scope for v1.0:** multi-track editing, transitions, titles, subtitles, audio
   mixing, video colour grading beyond the listed adjustments, time-remapping beyond
   simple slow-mo.

**Consequences.** See v2 roadmap. Net: bounded scope keeps Phase 9 shippable; reuse of
Phase 6 parameter semantics avoids a second mental model.

**Open questions for Phase 9 design.**

- Confirm media3 Transformer supports the required real-time shader pipeline on minSdk 26.
- Decide whether the video editor lives in the toadGALLERY Edit APK or in the main Gallery
  APK. APK size budget may force the latter.

---

## ADR-0013 — GitHub Actions as the build and CI environment (NEW, v2.2)

**Status:** ACCEPTED · **Date:** 2026-10-29.

**Context.** The Phase 0/1/2 build artifacts (Fossify baseline build, Aves baseline attempt,
the toadGALLERY 0.1.0-alpha shell) were produced in an ad-hoc audit container with manually
provisioned JDK 17/21 + Android SDK + (for Aves) Flutter SDK. This is not reproducible by
other contributors and not enforceable as a quality gate. User directive 2026-10-29:
"another blocker you said about environment which we use github actions as solution."

**Decision.**

1. **GitHub Actions is the canonical build and CI environment.** All builds, lint, tests,
   and artifact production run on GitHub-hosted runners via workflows in `.github/workflows/`.
2. **The local-build requirement is dropped.** Contributors can build locally if they want,
   but the source of truth for "does it build" is the CI workflow. A green CI check is
   required to merge any PR.
3. **The initial CI workflow** (`.github/workflows/ci.yml`) sets up JDK 17 + Android SDK
   (platforms;android-36, build-tools;36.0.0) + Gradle 9.5.1, then runs `./gradlew
   :apps:gallery:assembleDebug`, `./gradlew lint`, and `./gradlew test` on every push and
   PR. The workflow is documented in the README.
4. **Flutter is NOT in the CI matrix.** Per ADR-0008, building Aves libre is optional and
   not blocking. There is no Flutter setup step in CI.
5. **The Phase 0/1 baseline build artifacts** (Fossify APK, the Aves blocker
   documentation) are historical records and are not re-built in CI. The CI workflow
   builds only the toadGALLERY apps.
6. **Future CI extensions** (tracked as separate issues): upload APK artifacts on tag
   pushes, run the privacy test suite (once Phase 2 lands it), run Baseline Profile
   generation (Phase 8), produce release-signed APKs (Phase 11).
7. **Secrets.** No secrets are required for the initial CI workflow. Release signing keys,
   when needed, will live in GitHub Encrypted Secrets and are referenced by name only in
   workflow YAML — never committed.

**Consequences.**

- Pro: The build environment blocker (no local Android SDK / Flutter in this container)
  is resolved. Any contributor can push and let CI verify the build.
- Pro: Reproducibility is enforced. The CI workflow is the canonical recipe.
- Pro: PR review has a green-build gate.
- Pro: Free for public repos under GitHub Actions limits.
- Con: CI minutes are bounded. Mitigation: cache Gradle deps and the Gradle wrapper; only
  run full builds on PRs and main, not on every push to a feature branch (configurable).
- Con: GitHub-hosted runners do not have device emulators by default. On-device smoke tests
  require either an emulator step (slow, flaky) or a third-party service (e.g. Firebase
  Test Lab). Deferred to Phase 10/11.

**Open questions.**

- Decide whether to enable Gradle Build Cache remote storage (GitHub Actions cache is
  per-runner; a remote cache would speed up cold builds). Initial: no; revisit if build
  times exceed 5 minutes.
- Decide whether to add a `release.yml` workflow for tagged releases. Initial: defer to
  Phase 11.

---

## ADR index

| ADR | Title | Status |
|---|---|---|
| 0001 | Primary architecture & integration approach | **SUPERSEDED by 0008** |
| 0002 | Separate Edit APK with versioned content-URI contract | ACCEPTED |
| 0003 | Upstream tracking: pinned commits, no embedded forks | ACCEPTED (clarified by 0008) |
| 0004 | Product license: GPL-3.0 | ACCEPTED (clarified by 0008) |
| 0005 | App identity and UI stack (views, Material, provisional app IDs) | **SUPERSEDED by 0009** |
| 0006 | Data model: URI-first, MediaStore-anchored | ACCEPTED |
| 0007 | Editor integration contract v1 | ACCEPTED |
| 0008 | Features/UI/UX/designs only from upstreams; build from scratch | ACCEPTED (supersedes 0001) |
| 0009 | Jetpack Compose UI toolkit; Material 3 + dynamic color | ACCEPTED (supersedes 0005) |
| 0010 | On-device AI policy | ACCEPTED (policy) |
| 0011 | Non-destructive edit pipeline format | ACCEPTED (policy) |
| 0012 | Video editor scope and pipeline reuse | ACCEPTED (policy) |
| 0013 | GitHub Actions as the build and CI environment | ACCEPTED |
