# Architecture Documentation

**Status:** Placeholder (planned for Phase 1) · **Created:** 2026-10-29

This directory will hold the product's architecture artifacts once the Phase 1 source
audit selects the integration approach (see
[`../integration-decisions.md`](../integration-decisions.md), ADR-0001).

Planned contents:

- **Module diagram** — main app, editor app, shared contracts (`apps/gallery`,
  `apps/edit-extension`, `shared/*`), and where upstream-derived code lives.
- **Data-flow diagram** — MediaStore/content-URI access boundary, thumbnail pipeline,
  metadata extraction (off-main-thread), trash/recovery flow, and editor round-trip.
- **Editor integration contract** — versioned intent/content-URI protocol between
  toadGALLERY and toadGALLERY Edit (ADR-0002), including failure modes (missing,
  outdated, unsupported format).
- **Navigation map** — Photos / Albums / Search / Settings, favorites and trash
  placement, selection behavior across screens (Phase 2).

Until Phase 1 produces the audit evidence, the authoritative integration material lives
in the feature matrix and the ADRs.
