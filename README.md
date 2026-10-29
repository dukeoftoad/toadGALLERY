# toadGALLERY

**A polished, private, offline-first Android gallery** — one coherent product, built on
[Fossify Gallery](https://github.com/FossifyOrg/Gallery) and [Aves](https://github.com/deckerst/aves)
as upstream bases, with a separate installable **toadGALLERY Edit** extension.

> Inspired by the clarity and simplicity of Xiaomi/Mi Gallery — without proprietary assets,
> accounts, cloud services, ads, or analytics. Core functionality works fully offline.

**Status:** Phase 1 — Source audit & integration design **delivered** (Phase 0 exited;
see [docs/phase-0/sign-off.md](docs/phase-0/sign-off.md)). Next: Phase 2 product shell.

---

## Product goals

- Build **one coherent product**, not two competing gallery apps merged together.
- Use **Fossify Gallery** and **Aves** as upstream base branches and sources of
  implementation, deliberately deciding how their code and features fit together.
- Deliver a clean, fast, intuitive gallery with strong media browsing, metadata, search,
  and safe file operations.
- Ship **toadGALLERY Edit** as a separate APK that opens from the main Gallery app and
  updates independently.
- **Protect originals by default**; make destructive operations explicit.
- Track licensing, attribution, testing, and upstream changes from day one.

See [`roadmap.md`](roadmap.md) for the full phased plan (Phases 0–9).

## How the two bases contribute (Phase 1 answer, evidence-based)

| Base | Role in toadGALLERY |
|---|---|
| **Fossify Gallery** (Kotlin, GPL-3.0) | The product **skeleton**: browsing, viewer (Glide + subsampling + media3), favorites, file operations, editor code to extract — kept/adapted (ADR-0001) |
| **Aves** (Flutter/Dart, BSD-3-Clause) | The **design & model reference**: URI-first media identity, three-date semantics, progressive metadata service, composable filter algebra, format taxonomy, offline-first service pattern — reimplemented in Kotlin (no Dart ships) |
| Aves `exifinterface` fork (Apache-2.0) | The single **code-level adaptation** (native EXIF parser, attribution required) |

Key evidence documents: [source audit: Fossify](docs/phase-1/source-audit-fossify.md) ·
[source audit: Aves](docs/phase-1/source-audit-aves.md) ·
[integration decisions (ADRs)](docs/integration-decisions.md) ·
[feature matrix](docs/feature-matrix.md).

## Repository structure

```text
toadGALLERY/
├── roadmap.md                  # Phased development roadmap (Phases 0–9)
├── LICENSE                     # GPL-3.0 (full text)
├── docs/
│   ├── architecture/           # module + data-flow diagrams, editor contract v1
│   ├── phase-0/               # baselines, build notes, sign-off, risk register
│   ├── phase-1/               # source audits, dependency/license report
│   ├── feature-matrix.md      # capability origin & owner (audited)
│   ├── integration-decisions.md# ADR-0001..0007 (accepted)
│   └── licensing-and-attribution.md
├── apps/                       # (Phase 2+) gallery/ and edit-extension/
└── shared/                     # (Phase 2+) media-contract, metadata, editor-contract, design-system
```

`apps/` and `shared/` are **coming in Phase 2**; the validated module layout is
[docs/architecture/module-diagram.md](docs/architecture/module-diagram.md).

## Upstream baselines (recorded 2026-10-29)

| Project | Recorded commit | License | Baseline build |
|---|---|---|---|
| Fossify Gallery v1.14.1 | `e9812299b9d444b…` (`main`) | GPL-3.0 | **BUILT & VERIFIED** — `gallery-30-foss-debug.apk` (66 MB), see [build notes](docs/phase-0/build-notes.md) |
| Aves 1.15.8+182 | `4f940ae48bd936…` (`develop`) | BSD-3-Clause | BLOCKED (audit-container disk); full recipe + cause chain documented — issue #2 |

## Roadmap at a glance

| Phase | Focus | State |
|---|---|---|
| **0** | Align scope, establish baselines | **Exited** — [sign-off](docs/phase-0/sign-off.md) |
| **1** | Source audit and integration design | **Delivered** — audits, matrix, ADRs, diagrams, license report |
| **2** | Product shell and visual foundation | **Next** — installable dev APK, design tokens, navigation |
| 3 | Core media access and browsing | planned (URI-first model per ADR-0006) |
| 4 | Metadata, formats, and Aves-derived capabilities | planned |
| 5 | Advanced search and organization | planned (filter algebra per audit) |
| 6 | toadGALLERY Edit extension | contract spec ready ([v1](docs/architecture/editor-contract.md)) |
| 7 | Trash, reliability, privacy, and accessibility | planned |
| 8 | Performance and compatibility | planned |
| 9 | Release readiness | planned |

Progress is tracked in the repository **milestones** (one per phase) and **issues**.

## License

**GPL-3.0** — see [LICENSE](LICENSE). Derives from GPL-3.0 licensed upstream work
(Fossify Gallery, itself a continuation of the Simple Gallery lineage). BSD-3-Clause
material from Aves and Apache-2.0 modules enter only with preserved notices; each is
recorded in [licensing-and-attribution.md](docs/licensing-and-attribution.md). This is
engineering compliance tracking, not legal advice.
