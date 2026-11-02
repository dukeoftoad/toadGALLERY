# Licensing and Attribution

**Status:** Living document (updated every phase) · **Last revised:** 2026-11-02 (v2.4 —
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
| Ente ML engine (Rust) | `ente/ente` `rust/crates/{ml,assets,image}` @ `423b2d80d9ec89d45f5680cb1e1d777bac225f41` | **AGPL-3.0** (c) Ente Services Pvt. Ltd. and contributors | 2026-11-02, repo LICENSE + crate tree | **Vendored + adapted** under ADR-0020 / maintainer direction (2026-11-02): `engine/` workspace, see `engine/NOTICE.md` |

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
- **AGPL-3.0 (Ente ML engine, vendored under ADR-0020)** → AGPL-3.0 is GPL-3.0-compatible
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
| `engine/crates/toad-ml-jni/**` | net-new Rust (r22, the JNI bridge over `ente-ml`/`ente-image`) | --- | AGPL-3.0 | Not vendored --- net-new toadGALLERY code (the Kotlin-facing cdylib); `license.workspace = true` places it in the AGPL-3.0 engine workspace because it compiles against the AGPL vendored crates; the §2 distributed-binary consequence is unchanged |
| `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ml/engine/*` | net-new Kotlin | --- | GPL-3.0 (project) | Model manifest (all 11 assets match the pinned Rust catalog name+size+digest, test-pinned), verifier, provisioner --- Ente-inspired reimplementations, not ports |

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
