# Integration Decisions — Architecture Decision Records

**Format:** lightweight ADRs. Each decision states context, options considered, and the
choice. Decisions here may be superseded by newer ADRs; never edit history — add a new ADR.
Statuses: `PROPOSED` (awaiting phase evidence) → `ACCEPTED` (evidence supports it) → `SUPERSEDED`.

---

## ADR-0001 — Primary architecture and upstream integration approach

**Status:** PROPOSED (to be confirmed by the Phase 1 source audit) · **Date:** 2026-10-29

**Context.** toadGALLERY builds on two upstreams with incompatible stacks:
Fossify Gallery is ~100% Kotlin (native Android, ViewBinding, Room, Glide; targetSdk 36);
Aves is ~74% Dart on Flutter 3.47.8 with ~18% Kotlin native modules (compileSdk 37).
The roadmap forbids assuming an integration method before the audit.

**Options considered.**
1. **Native Kotlin base (Fossify skeleton) + selective Aves ports.** Reimplement
   chosen Aves behaviors (metadata model, search/filter UX, format handling) in Kotlin;
   copy only permissively-licensed native pieces where they fit (e.g. the Apache-2.0
   exifinterface fork).
2. **Flutter base (Aves skeleton) + Fossify ports.** Same problem mirrored: Fossify's
   Kotlin (GPL) would need full reimplementation in Dart; the editor and native
   media/file-op code would be rewritten from scratch.
3. **Literal repository merge / dual-UI product.** Rejected outright: two UI stacks in
   one app violates "one product experience", doubles maintenance, and makes licensing
   and testing intractable.
4. **Clean-room rebuild using both as references only.** Highest cost; discards a
   working, maintained, GPL-compatible foundation during Phase 0 for no evidence-backed
   reason.

**Decision.** Option 1 is the working hypothesis: **toadGALLERY is a native
Kotlin/Android product seeded from the Fossify Gallery base; Aves contributes primarily
as a feature/design/format reference, with selective ports of its permissively-licensed
native components and reimplemented Dart logic in Kotlin.** The Phase 1 audit converts
this from PROPOSED to ACCEPTED (or forces a revision) with file-level evidence.

**Consequences.** Dart code is never vendored; "port from Aves" always means
"reimplement in Kotlin, informed by Aves". This keeps one toolchain (JDK/AGP/Gradle),
one UI system, and one licensing surface. Cost: reimplementing Aves-derived features is
slower than copy-paste and is budgeted as such (risk R-01).

---

## ADR-0002 — toadGALLERY Edit is a separate APK with a versioned content-URI contract

**Status:** PROPOSED (prototype required before full editor build; roadmap sprint item 6) · **Date:** 2026-10-29

**Context.** The roadmap requires a separate installable editor that can be updated
independently. Fossify's editor is currently built-in (activities, menus, libs:
cropper, photofilters); Aves has no built-in editor.

**Decision.** The main Gallery app never embeds the editor. A separate APK
(**toadGALLERY Edit**) is launched via a **versioned intent contract**:
Gallery grants URI-scoped permission for the source media, passes the requested
operation, and the editor returns/publishes the result (or a cancellation/error) through
the same contract. Absolute file paths are never part of the contract. Gallery must
define behavior for: extension not installed, extension outdated (contract version
mismatch), and unsupported formats. Default save behavior is **Save copy**;
replace-original is opt-in and only for operations proven safe. Originals are never
silently overwritten; metadata (EXIF/GPS) is preserved or stripped only per explicit
export settings.

**Consequences.** The extraction of Fossify's editor code becomes a Phase 6 exercise
against a stable contract, not a Phase 2 surgery (risk R-09). The contract prototype is
a blocking Phase 1 sprint item.

---

## ADR-0003 — Upstream tracking method: pinned commits, no embedded forks

**Status:** PROPOSED · **Date:** 2026-10-29

**Context.** The roadmap sketches `upstream/fossify-gallery` and `upstream/aves` as
tracked reference folders, but also warns the layout is a model to validate in Phase 0.
Embedding both projects as nested forks would triple repo size, entangle histories, and
create the exact "two repos in one" trap the roadmap forbids.

**Decision.** Upstreams are recorded by **exact pinned commit** in
`docs/phase-0/upstream-baselines.md` and cloned locally on demand (gitignored under
`upstream/`). toadGALLERY does **not** vendor, subtree, or submodule either upstream
until Phase 1 evidence justifies a different mechanism; if submodules are later adopted,
they pin the same commits and the change is recorded as a superseding ADR. History and
attribution are preserved via the recorded SHAs, per-file provenance notes in the
licensing doc, and upstream's own repositories.

**Consequences.** The main tree stays small and buildable; re-sync cadence is a policy
decision (risk R-03). Any copied file must still carry a provenance note per the
licensing doc.

---

## ADR-0004 — Product license: GPL-3.0

**Status:** PROPOSED (final confirmation is a Phase 9 release gate) · **Date:** 2026-10-29

**Context.** Fossify Gallery is GPL-3.0 (verified from its `LICENSE` at the pinned
commit). Aves is BSD-3-Clause (verified from its `LICENSE`; its exifinterface fork is
Apache-2.0). toadGALLERY will contain substantial code derived from Fossify Gallery.

**Decision.** The combined work is distributed under **GPL-3.0** (repo root `LICENSE`).
BSD-3-Clause and Apache-2.0 components remain under their own licenses with attribution
notices — both are GPL-compatible. Every copied/adapted file gets a provenance header
note recorded in `licensing-and-attribution.md`.

**Consequences.** Distribution (of any build) requires corresponding GPL-3.0 source
disclosure, preserved notices, and stated changes. A permissive-license ambition for the
whole product is not viable while Fossify-derived code is included; that trade-off was
accepted by adopting ADR-0001.

---

## ADR index

| ADR | Title | Status |
|---|---|---|
| 0001 | Primary architecture & integration approach | PROPOSED |
| 0002 | Separate Edit APK with versioned content-URI contract | PROPOSED |
| 0003 | Upstream tracking: pinned commits, no embedded forks | PROPOSED |
| 0004 | Product license: GPL-3.0 | PROPOSED |
