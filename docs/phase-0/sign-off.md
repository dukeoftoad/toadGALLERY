# Phase 0 Sign-Off — Exit Review

**Date:** 2026-10-29 (UTC) · **Role:** Project lead (bootstrap) · **Scope:** roadmap Phase 0

This document records the Phase 0 exit review against the roadmap's exit criteria,
including the decisions the roadmap requires to be *agreed*, not assumed.

## Exit criteria verdict

| # | Criterion (roadmap) | Verdict | Evidence |
|---|---|---|---|
| 1 | Both upstream baselines are built **or** their blockers are documented | **MET** | Fossify Gallery v1.14.1: BUILD SUCCESSFUL, APK verified (`docs/phase-0/build-notes.md` §2). Aves 1.15.8+182: blocked by audit-container disk capacity; complete cause chain, fix recipe, and handoff checklist documented (`build-notes.md` §3). Issue #2 remains open for the on-hardware build. |
| 2 | Exact revisions and applicable licenses are recorded | **MET** | `docs/phase-0/upstream-baselines.md` — Fossify Gallery `e9812299` (GPL-3.0), Aves `4f940ae` (BSD-3-Clause), exifinterface fork (Apache-2.0). All verified from license files at the pinned commits, not from metadata assumptions. |
| 3 | No irreversible repository merge has been made before the audit | **MET** | The toadGALLERY tree contains only documentation + the roadmap. Upstreams remain external clones at pinned SHAs. No code has been copied. |

## Agreed decisions (roadmap Phase 0 "tasks" that require agreement)

1. **Must-have scope.** Roadmap §6 "First usable release" is adopted **as binding**, and
   §6 "Defer until the foundation is stable" is adopted as the explicit deferral list.
   No additions. Notable consequences enforced downstream:
   - Aves network features (DLNA casting, online maps, connectivity checks) are **excluded**
     from toadGALLERY entirely, not deferred.
   - Widgets and wallpaper/set-as features of Fossify are **deferred** (not must-have).
2. **Initial supported Android range.** **minSdk 26 (Android 8.0), targetSdk 36,
   compileSdk 36.** Rationale: 26 is the floor of the primary base (Fossify); Aves
   resolves to minSdk 24 but brings no API 24/25 capability we need that justifies
   lowering the floor and widening the test matrix; 36 is the newest publicly available
   platform and the compile level of a working verified build in this environment.
3. **Android 16 (API 36) behavior checklist** (test targets for Phase 3/7/8):
   - Partial photo/video access: `READ_MEDIA_VISUAL_USER_SELECTED` selection flow,
     re-selection from Settings, app behavior after user trims access
   - `ACCESS_MEDIA_LOCATION` (Fossify baseline requires it; verify EXIF GPS retrieval)
   - `MANAGE_MEDIA` grant path for trash/restore semantics
   - Edge-to-edge enforcement (targetSdk 35+) — window insets, system bar styling
   - Predictive back gesture (`android:enableOnBackInvokedCallback`) — verify viewer
     back-stack behavior
   - Private Space visibility behavior for gallery apps (Android 15+, test on 16)
   - Photo picker interplay: toadGALLERY keeps direct `READ_MEDIA_*` permissions
     (a gallery is a media-manager, not a picker consumer) — verify policy compliance
     in the intended distribution channel at Phase 9.
4. **Feature matrix & integration options.** Initial matrix + integration options
   accepted as the **starting position for the Phase 1 audit** (they were seeded from
   baseline evidence, not speculation). Phase 1 confirms or rewrites them with code-level
   evidence — done in this same session, see `docs/phase-1/`.

## Deliverables checklist (roadmap Phase 0)

- [x] Reproducible baseline build notes for both projects — `docs/phase-0/build-notes.md`
- [x] Source and dependency inventory — `docs/phase-0/upstream-baselines.md` (sources),
      `docs/phase-1/dependency-license-compatibility.md` (dependencies & licenses)
- [x] Feature overlap matrix — `docs/feature-matrix.md`
- [x] Initial risk register and integration options — `docs/phase-0/risk-register.md`,
      `docs/integration-decisions.md`

## Phase 0 outcome

**Phase 0 is exited.** Work proceeds to Phase 1 (source audit and integration design)
with all Phase 1 deliverables produced in the same bootstrap session; the Aves on-hardware
baseline build (issue #2) remains tracked and is the only Phase 0 artifact still open —
it does not block Phase 1 because the audit operates on source, not on binaries.
