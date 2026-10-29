# toadGALLERY

**A polished, private, offline-first Android gallery** — one coherent product, built on
[Fossify Gallery](https://github.com/FossifyOrg/Gallery) and [Aves](https://github.com/deckerst/aves)
as upstream bases, with a separate installable **toadGALLERY Edit** extension.

> Inspired by the clarity and simplicity of Xiaomi/Mi Gallery — without proprietary assets,
> accounts, cloud services, ads, or analytics. Core functionality works fully offline.

**Status:** `Phase 0 — Align scope and establish the baselines` *(planning)*

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

## Project principles

1. **Audit before merging.** No repository merge happens before the Phase 0/1 audit.
2. **One product experience.** Consistent navigation, design, terminology, permissions.
3. **Architecture based on evidence.** Fossify is native Kotlin; Aves is primarily
   Flutter/Dart. Aves UI code cannot be dropped into a Kotlin UI — the integration
   method is decided from audit results, not assumptions.
4. **Keep the editor separate.** Gallery and Edit are distinct apps with a documented,
   secure integration contract.
5. **Incremental, buildable changes.** A working build at every phase.
6. **Preserve upstream history and license obligations.**
7. **No feature is complete until tested.**

## Repository structure

```text
toadGALLERY/
├── roadmap.md                  # Phased development roadmap (Phases 0–9)
├── LICENSE                     # GPL-3.0 (full text)
├── docs/
│   ├── architecture/           # Module & data-flow diagrams (Phase 1)
│   ├── feature-matrix.md       # Capability origin & status matrix (draft)
│   ├── integration-decisions.md# Architecture decision records
│   ├── licensing-and-attribution.md
│   └── phase-0/
│       ├── upstream-baselines.md  # Recorded commits, licenses, toolchains
│       ├── build-notes.md         # Baseline build verification notes
│       └── risk-register.md       # Initial risk register
├── apps/                       # (Phase 2+) gallery/ and edit-extension/
└── shared/                     # (later phases) shared code, contracts
```

The `apps/`, `shared/`, and `upstream/` layout from the roadmap is a **planning model** —
the final arrangement is validated in Phase 0 and recorded in
[`docs/integration-decisions.md`](docs/integration-decisions.md).
Local upstream reference clones live outside the tree by default (gitignored).

## Documentation index

| Document | Purpose |
|---|---|
| [roadmap.md](roadmap.md) | Full product vision, principles, phases 0–9 |
| [docs/phase-0/upstream-baselines.md](docs/phase-0/upstream-baselines.md) | **Exact upstream commits, licenses, toolchains** (Phase 0 record) |
| [docs/phase-0/build-notes.md](docs/phase-0/build-notes.md) | Baseline build verification status & how to reproduce |
| [docs/phase-0/risk-register.md](docs/phase-0/risk-register.md) | Initial risk register |
| [docs/feature-matrix.md](docs/feature-matrix.md) | Feature overlap matrix (DRAFT — confirmed in Phase 1) |
| [docs/integration-decisions.md](docs/integration-decisions.md) | Architecture decision records (ADR) |
| [docs/licensing-and-attribution.md](docs/licensing-and-attribution.md) | License inventory & attribution obligations |

## Upstream baselines (recorded 2026-10-29)

| Project | Repository | Recorded commit | License |
|---|---|---|---|
| Fossify Gallery | [`FossifyOrg/Gallery`](https://github.com/FossifyOrg/Gallery) | `e9812299b9d444b57072313cd2c3efd59c790f98` (v1.14.1) | GPL-3.0 |
| Aves | [`deckerst/aves`](https://github.com/deckerst/aves) | `4f940ae48bd9364a0a532b84f283ec60755d9904` (v1.15.8+182, `develop`) | BSD-3-Clause |

Details (SDK levels, dependency manifests, language breakdowns, reproduction commands):
[docs/phase-0/upstream-baselines.md](docs/phase-0/upstream-baselines.md).

## Roadmap at a glance

| Phase | Focus |
|---|---|
| **0** | Align scope, establish baselines *(current)* |
| 1 | Source audit and integration design |
| 2 | Product shell and visual foundation |
| 3 | Core media access and browsing |
| 4 | Metadata, formats, and Aves-derived capabilities |
| 5 | Advanced search and organization |
| 6 | toadGALLERY Edit extension |
| 7 | Trash, reliability, privacy, and accessibility |
| 8 | Performance and compatibility |
| 9 | Release readiness |

Track progress via the repository **milestones** (one per phase) and the **Phase 0 issues**.

## License

**GPL-3.0** — see [LICENSE](LICENSE).

toadGALLERY derives from GPL-3.0 licensed upstream work (Fossify Gallery, itself a fork
of the Simple Gallery lineage). BSD-3-Clause code from Aves and Apache-2.0 code from its
bundled modules may be incorporated with attribution; GPL-3.0 governs the combined work
at distribution. License evidence and obligations are tracked in
[docs/licensing-and-attribution.md](docs/licensing-and-attribution.md).
This is engineering due diligence, not legal advice — final review is a Phase 9 gate.
