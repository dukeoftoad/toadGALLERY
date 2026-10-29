# Risk Register — Initial (Phase 0)

**Status:** Draft · **Created:** 2026-10-29
Scale: Likelihood (L/M/H) · Impact (L/M/H) · Owner = whoever owns the phase noted.

| ID | Risk | Category | L | I | Mitigation / decision owner |
|---|---|---|---|---|---|
| R-01 | **Flutter→Kotlin port underestimation.** Aves logic (Dart) cannot be reused as code in a native app; every "port" is really a reimplementation in Kotlin. Schedules built on "porting Aves features" will slip. | Technical | H | H | ADR-0001: treat Aves as design/format reference, not a code source for UI; estimate reimplementation cost per feature in Phase 1. |
| R-02 | **Two toolchains in CI** (Gradle/JDK17 for Fossify-base work, Flutter/JDK21 for Aves reference builds). Doubles build infrastructure and review burden. | Infrastructure | H | M | Keep Aves out of the main build until it is needed; baseline-build scripts live in docs; single primary toolchain for the product. |
| R-03 | **Upstream drift.** Both upstreams move fast (Fossify released v1.14.1 on 2026-10-24; Aves HEAD is 2026-10-28). A frozen fork diverges quickly; a tracking fork churns constantly. | Maintenance | H | M | Pin exact commits (done, `upstream-baselines.md`); define a rebase/re-sync cadence in Phase 1; don't vendor upstream into this repo. |
| R-04 | **GPL compliance across the dependency graph.** Product ships GPL-3.0; every copied file, adapted snippet, and binary dependency must be inventoried or distribution is blocked. | Legal | M | H | `licensing-and-attribution.md` maintained per phase; Phase 9 gate; no code copy without a recorded entry. |
| R-05 | **Feature bloat vs "one product".** Both bases have features outside the roadmap scope (Aves: DLNA cast, maps, charts; Fossify: widgets, VR/cardboard, wallpaper). Importing them wholesale breaks scope and privacy posture. | Product | M | M | Roadmap §6 deferrals are binding; each feature needs an explicit keep/adapt/reimplement/defer decision in `feature-matrix.md`. |
| R-06 | **Offline-first policy vs upstream network features.** Aves ships connectivity/DLNA/Google-services flavors. Any leak into the product violates a core promise. | Privacy | M | H | Model only libre-style flavor; dependency audit excludes network-dependent libs from core; Phase 7 privacy audit gate. |
| R-07 | **Cross-app editor contract versioning.** Gallery↔Edit is a cross-process contract; mismatched versions must fail gracefully, not corrupt media. | Technical | M | H | ADR-0002: versioned intent contract from the first prototype (Phase 1 sprint item 6); never pass absolute paths; URI grants only. |
| R-08 | **MediaStore/partial access fragmentation.** Limited photo access, revoked permissions, removable storage, Private Space (Android 15+), Android 16 changes — behavior differs per OEM and API level. | Platform | H | H | Phase 3 permission matrix tests; devices/API matrix in Phase 8; no reliance on raw file paths in product code. |
| R-09 | **Built-in editor extraction.** Fossify's editor is woven into activities/menus; extracting it into a separate APK may destabilize both apps if done late. | Technical | M | H | Decide extraction mechanics in Phase 1 (contract prototype), implement skeleton in Phase 6; keep upstream editor code unmodified until then. |
| R-10 | **Token/secret hygiene.** CI signing keys and any GitHub PATs used for automation must never enter the repo. GitHub PAT used during bootstrap was exposed in conversation — rotate it. | Security | M | H | Secrets only via env/GitHub encrypted secrets; audit history for leaks before first public release; rotate all bootstrap credentials. |
| R-11 | **Single-maintainer bus factor.** Roadmap assumes multi-phase rigor (audits, ADRs, matrices); a solo contributor skipping gates erodes quality silently. | Process | M | M | Every phase has checklist artifacts in `docs/`; quality gates in roadmap §5 are non-skippable; keep docs the source of truth. |
| R-12 | **Trademark/branding confusion.** Name resembles upstream projects (Fossify Gallery); public communication must not imply endorsement. | Legal | L | M | Clear attribution page; never use upstream logos/branding; document derivation openly (done in README). |
| R-13 | **Destructive schema migration inherited from seed.** Fossify's Room DB uses `fallbackToDestructiveMigration()` — an upstream schema mistake would wipe favorites/trash bookkeeping. Audit finding (source-audit-fossify §3). | Data safety | M | H | toadGALLERY schema v1 starts fresh URI-keyed (ADR-0006) with explicit migrations and **no destructive fallback**; Phase 8 migration tests are release gates. |
| R-14 | **Two dependency POM license gaps.** `androidphotofilters` (naveensingh JitPack fork) and `jxl-coder-glide` lack verified license declarations — release blockers per dependency report §3. | Legal | M | H | Verify POMs before Phase 9; if unresolvable, replace the filter set / JPEG XL coder. Tracked in dependency-license-compatibility.md. |
| R-15 | **Trash semantics conflict.** Fossify: app-level bin (`deleted_ts`); Aves: platform MediaStore trash. Two philosophies must become one behavior users can predict. | Product | H | M | Hybrid decision recorded (feature matrix); final semantics + limitations documented in Phase 7; retention job pattern reused from Fossify. |

## Register conventions

- New risks get the next free ID; never renumber.
- A risk may only be closed when its mitigation has a verification artifact (doc, test, or audit).
- This register is reviewed at every phase boundary before exit criteria sign-off.
