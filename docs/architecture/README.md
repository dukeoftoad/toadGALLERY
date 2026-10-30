# Architecture Documentation

**Status:** Phase 1 deliverables present · **Last updated:** 2026-10-29

| Document | Contents |
|---|---|
| [`module-diagram.md`](module-diagram.md) | Module layout (`apps/`, `shared/*`), external dependencies, upstream relations (Mermaid) |
| [`data-flow.md`](data-flow.md) | Browsing/grid, progressive metadata, file-ops/trash, Gallery↔Edit round trip, date semantics (Mermaid) |
| [`editor-contract.md`](editor-contract.md) | Versioned Gallery ↔ Edit intent contract v1 (ADR-0007 spec) |
| [`file-ops.md`](file-ops.md) | File operations (issue #30): API matrix, consent recovery, move/undo semantics, the `DeleteMode.TO_TRASH` seam for #31, limitations |

Sources of authority for anything not yet here: the [ADRs](../integration-decisions.md)
(decisions), the [feature matrix](../feature-matrix.md) (capability ownership), and the
[Phase 1 audits](../phase-1/) (evidence).

Deferred contents (added in later phases): storage migration notes (Phase 3), metadata
service internals (Phase 4), search index design (Phase 5), editor pipeline (Phase 6).
