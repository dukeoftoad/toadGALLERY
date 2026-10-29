# Integration Decisions — Architecture Decision Records

**Format:** lightweight ADRs. Each decision states context, options considered, and the
choice. Decisions are never edited in place after acceptance — a superseding ADR is added.
Statuses: `PROPOSED` (awaiting phase evidence) → `ACCEPTED` (evidence supports it) → `SUPERSEDED`.

---

## ADR-0001 — Primary architecture and upstream integration approach

**Status:** ACCEPTED (2026-10-29, on Phase 1 source-audit evidence) · **Date:** 2026-10-29

**Context.** toadGALLERY builds on two upstreams with incompatible stacks:
Fossify Gallery is ~100% Kotlin (native Android, ViewBinding, Room, Glide; targetSdk 36);
Aves is ~74% Dart on Flutter 3.47.8 with ~18% Kotlin native modules (compileSdk 37).
The roadmap forbids assuming an integration method before the audit.

**Options considered.**
1. **Native Kotlin base (Fossify skeleton) + selective Aves ports.**
2. Flutter base (Aves skeleton) + Fossify ports (mirror problem, editor rewritten, GPL Dart reimplementation).
3. Literal repository merge / dual-UI product (rejected: two UI stacks, unmaintainable).
4. Clean-room rebuild (rejected: discards a verified working base for no evidence-backed reason).

**Decision.** Option 1. **toadGALLERY is a native Kotlin/Android product seeded from the
Fossify Gallery base; Aves contributes as a design/format/metadata reference, with
reimplementation in Kotlin, plus one code-level adaptation: the Apache-2.0 exifinterface fork.**

**Evidence (Phase 1 audit, 2026-10-29).**
- Fossify baseline **builds and packages successfully** in our toolchain (build-notes §2) — the
  "working build at every phase" principle has a concrete foundation.
- Fossify's codebase is small (~105 Kotlin files), coherent, GPL-3.0, and its media stack
  (MediaStore → Room → Glide/media3 → ViewBinding) is directly the product's target shape.
- Aves' value is in its *models* — URI-first entry, three-date semantics, progressive
  metadata, filter algebra, format taxonomy, no-op service pattern — all documented in
  `source-audit-aves.md` as Kotlin reimplementation targets. Its Dart UI cannot be reused
  and no reason was found to maintain a Flutter runtime inside the product.
- No blocker found in the dependency/license screen that argues for a different base.

**Consequences.** One toolchain (Gradle/AGP/Kotlin), one UI system, one licensing surface.
"Port from Aves" always means "reimplement informed by Aves" — budgeted as reimplementation
(risk R-01). The only Aves *code* entering the tree is the permissively-licensed
exifinterface fork (recorded in the provenance ledger at copy time).

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

---

## ADR-0003 — Upstream tracking: pinned commits, no embedded forks

**Status:** ACCEPTED (in effect since Phase 0; re-affirmed 2026-10-29) · **Date:** 2026-10-29

**Decision.** Upstreams are recorded by exact pinned commit in
`docs/phase-0/upstream-baselines.md` and cloned locally on demand (gitignored). toadGALLERY
does not vendor, subtree, or submodule either upstream; any future change to this policy
is a superseding ADR. History/attribution is preserved via recorded SHAs, per-file
provenance notes, and upstream repositories themselves.

**Evidence.** The bootstrap experience validated this: both baselines were pulled, audited,
and (in Fossify's case) built, from pinned commits without entangling histories. The Aves
baseline audit surfaced upstream defects (stale flavor pins, premature compileSdk 37) that
were cleanly documented *because* our tree stays decoupled.

---

## ADR-0004 — Product license: GPL-3.0

**Status:** ACCEPTED (2026-10-29, license compatibility report found no incompatibility) · **Date:** 2026-10-29

**Decision.** The combined work is distributed under **GPL-3.0** (root `LICENSE`). BSD-3
(Aves) and Apache-2.0 (exifinterface fork) components carry their own notices.
`docs/phase-1/dependency-license-compatibility.md` screens the dependency set; two items
are flagged as release blockers pending POM verification (androidphotofilters fork,
jxl-coder) — tracked for Phase 9, no effect on development.

---

## ADR-0005 — App identity and UI stack

**Status:** ACCEPTED (package IDs provisional until a domain is owned; visual identity set in Phase 2) · **Date:** 2026-10-29

**Context.** Phase 1 tasks require defining the main app's package ID, identity, and
design system direction before the Phase 2 shell.

**Decision.**
- **UI stack:** View-based Android UI (ViewBinding + Material themes) on top of
  `org.fossify:commons` — consistent with every Keep decision in the matrix. No Compose.
- **Application IDs (provisional):** Gallery `io.github.dukeoftoad.gallery` → displayed as
  "toadGALLERY"; Edit extension `io.github.dukeoftoad.gallery.edit` → "toadGALLERY Edit".
  The `io.github.<user>` namespace reflects what this project controls today; migrating to
  a proper domain namespace later is a Phase 9 packaging task (application ID change before
  first public release is cheap; after is impossible).
- **Design system:** token-based, Mi-Gallery-inspired clarity (large photo-first grids,
  restrained chrome, bottom navigation) implemented as Material theme overlays + commons'
  color system; no proprietary assets (roadmap §2/Phase 2 rule).
- **Navigation:** single-activity-per-screen on a simple back stack (Fossify pattern),
  four top-level destinations: **Photos, Albums, Search, Settings**; Favorites and Trash
  surface as virtual albums (deferred placement testing to Phase 2 usability pass).

**Consequences.** All Phase 2+ UI code is Views; Compose may only be revisited by a
superseding ADR (it would contradict the Keep dispositions).

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
  URI-keyed — no migration from Fossify's schema, no destructive-migration fallback
  (risk found in audit §3).
- Favorites: keyed by (uri, id) with path kept only for legacy import from Fossify
  installs — out of scope for v1 (fresh product).
- Trash: platform trash API where the OS supports it; app-level fallback documented in
  Phase 7 (ADR to be added then when semantics are finalized).

**Consequences.** Fossify's MediaFetcher patterns are reused for query construction, but
the cache, favorites, and sorting all move to URI keys. This is the largest deliberate
departure from the seed base — it is the roadmap's own requirement, not taste.

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

---

## ADR index

| ADR | Title | Status |
|---|---|---|
| 0001 | Primary architecture & integration approach | ACCEPTED |
| 0002 | Separate Edit APK with versioned content-URI contract | ACCEPTED |
| 0003 | Upstream tracking: pinned commits, no embedded forks | ACCEPTED |
| 0004 | Product license: GPL-3.0 | ACCEPTED |
| 0005 | App identity and UI stack (views, Material, provisional app IDs) | ACCEPTED |
| 0006 | Data model: URI-first, MediaStore-anchored | ACCEPTED |
| 0007 | Editor integration contract v1 | ACCEPTED |
