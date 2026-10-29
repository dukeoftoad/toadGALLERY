# Licensing and Attribution

**Status:** Living document (updated every phase) · **Last revised:** 2026-10-29 (v2.2 —
features-only per ADR-0008; Aves `exifinterface` fork port cancelled; `org.fossify:commons`
slated for removal per compose-migration).
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
- No proprietary or trademark-encumbered assets from Mi Gallery.

## 3. Obligations the project has accepted

1. **Source disclosure:** any distributed build must come with corresponding GPL-3.0
   source at the matching commit (GitHub releases must link the exact source tree; CI
   workflow per ADR-0013 is the canonical build recipe).
2. **Notice preservation:** upstream copyright notices and the GPL-3.0 text may never be
   removed from derivative files. (Under ADR-0008 there are no derivative files of upstream
   code; this obligation applies to the product's own code.)
3. **State changes:** substantial adaptation of upstream code should be marked. (Under
   ADR-0008 there is no upstream code adaptation; this obligation is moot but retained.)
4. **Per-file provenance:** any file copied or adapted from an upstream records origin +
   commit + license in the table below (§5) at the moment of the copy. **The ledger stays
   empty under ADR-0008.**
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
  our own Compose theme).

## 5. Provenance ledger (fill at the moment of any copy/port)

| File(s) in toadGALLERY | Origin | Upstream commit | Upstream license | Adaptation notes |
|---|---|---|---|---|
| *(empty --- no upstream code is copied into this repository; ADR-0008)* | | | | |

> The only upstream-derived artifact committed so far is the root `LICENSE`, which is the
> canonical GNU GPL v3 text. Under ADR-0008 this is no longer "taken from Fossify verbatim
> as required for GPL distribution" --- it is the project's own voluntary license choice
> (ADR-0004 clarification).

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
