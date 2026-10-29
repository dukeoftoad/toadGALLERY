# Licensing and Attribution

**Status:** Living document (updated every phase) · **Created:** 2026-10-29
This is engineering compliance tracking, not legal advice.

## 1. Governing decision

toadGALLERY is distributed under **GPL-3.0** (see root `LICENSE`, added 2026-10-29
during the Phase 0 bootstrap). This follows from ADR-0004: the product derives from
GPL-3.0 licensed work, so the combined work must be GPL-3.0 at distribution.

## 2. Upstream license inventory (verified from files at the pinned commits)

| Component | Source | License | Verified |
|---|---|---|---|
| Fossify Gallery | `FossifyOrg/Gallery` @ `e9812299` | **GPL-3.0** (root `LICENSE`, full GPLv3 text) | 2026-10-29, file content |
| Aves | `deckerst/aves` @ `4f940ae` | **BSD-3-Clause** (root `LICENSE`, © 2020 Thibault Deckers) | 2026-10-29, file content |
| Aves `android/exifinterface` | fork of `androidx.exifinterface` | **Apache-2.0** (module `LICENSE.txt`) | 2026-10-29, file content |

### Attribution chain

- **Fossify Gallery** is itself a continuation of the **Simple Gallery** lineage
  (Simple Mobile Tools), maintained by the Fossify organization. Derivative attribution
  must credit that lineage, not just the fork.
- **Aves** is © Thibault Deckers. Its README additionally credits the authors of
  libraries it depends on; ported components must carry those notices forward.

### License compatibility summary

- GPL-3.0 code (Fossify) → combined work GPL-3.0. Obligatory.
- BSD-3-Clause (Aves) → GPL-compatible; may be included in the GPL-3.0 product with
  copyright notice + license text + disclaimer preserved for the ported portion.
- Apache-2.0 (exifinterface fork) → GPL-3.0-compatible (Apache-2.0 is explicitly
  compatible with GPLv3); notice + license text required for vendored/adapted files.

## 3. Obligations the project has accepted

1. **Source disclosure:** any distributed build must come with corresponding GPL-3.0
   source at the matching commit (GitHub releases must link the exact source tree).
2. **Notice preservation:** upstream copyright notices, the GPL-3.0 text, and the
   BSD/Apache notices for ported portions may never be removed from derivative files.
3. **State changes:** substantial adaptation of upstream code should be marked
   (file-level comments are sufficient and preferred).
4. **Per-file provenance:** any file copied or adapted from an upstream records
   origin + commit + license in the table below (§5) at the moment of the copy.
5. **Dependency licenses:** every binary dependency of shipped code gets a license
   check before first release (Phase 9 gate). The Phase 1 direct-dependency screen
   found **no GPL-3.0 incompatibility**; two items are flagged for POM verification
   (androidphotofilters fork, jxl-coder-glide) and one option is explicitly not
   adopted (libmpv) — see
   [`phase-1/dependency-license-compatibility.md`](phase-1/dependency-license-compatibility.md).

## 4. What is deliberately NOT allowed in this repo

- Any proprietary or brand asset from Xiaomi/Mi Gallery — inspiration of *interaction
  design* only; no assets, icons, names, or marketing text.
- No code whose license is unverified.
- No license laundering: files imported as "reference" that later end up in builds
  without provenance entries are treated as a release blocker.

## 5. Provenance ledger (fill at the moment of any copy/port)

| File(s) in toadGALLERY | Origin | Upstream commit | Upstream license | Adaptation notes |
|---|---|---|---|---|
| *(empty — no upstream code has been copied into this repository yet)* | | | | |

> The only upstream-derived artifact committed so far is the root `LICENSE`, which is the
> canonical GNU GPL v3 text (taken from the Fossify Gallery baseline tree verbatim, as
> required for GPL distribution).

## 6. Review gates

- Phase 1 exit: dependency license compatibility report for every library retained.
- Phase 4 exit: Aves port/adaptation records complete (per roadmap).
- Phase 9 exit: full license/attribution package, notices, and source-distribution
  procedure verified as part of release readiness.
