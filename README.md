# toadGALLERY --- `editor` (the Editor product lane)

**toadGALLERY** is a private, offline-first Android gallery family built from
scratch in Kotlin + Jetpack Compose (inspired by Mi Gallery, Aves libre, and
Fossify Gallery --- features and UX only, no upstream code, ADR-0008). The
full project story, screenshots, and goals live in the
[`main` README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md);
the authoritative policy is
[`docs/PROJECT_ARCHITECTURE.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/PROJECT_ARCHITECTURE.md).

**This branch is the standalone Editor product lane** of the virtual fork.

---

## Branch identity card

| | |
|---|---|
| **Role** | Integration/development lane for the standalone companion editor. |
| **Product owned here** | **Editor** --- `toadGALLERY Edit` (`com.toadgallery.editor`), a separately installable APK opened from the gallery via a versioned content-URI contract (`shared/editor-contract`, ADR-0002/0007), updatable independently. |
| **Push CI** | `editor-ci.yml` --- assemble + lint + tests + the contract module. |
| **Releases** | **Retired on this lane** (2026-11-10): releases originate from `unified` (alpha/beta bundles) and `main` (stable) only. |
| **Flows to** | `unified`, via a reviewed PR once the lane's work is ready. |

## CI on this branch

[`editor-ci.yml`](.github/workflows/editor-ci.yml) triggers on pushes to
`editor`, path-filtered over `editor/**`, `shared/**`, the gradle files, and
the workflow tree:

```bash
./gradlew :editor:app:assembleDebug
./gradlew :editor:app:lintDebug
./gradlew :editor:app:testDebugUnitTest :shared:editor-contract:test
```

The contract-module tests always ride the editor gate --- the Gallery ↔ Edit
handoff is a published interface, and the editor may not drift from it.

The branch also carries the repo-wide dispatch surfaces and the other lanes'
CI files --- each is branch-scoped and stays dormant outside its own lane.

## What lives here

- `editor/app` --- the editor application module
  (`com.toadgallery.editor`; the current line is `0.4.14-edit-alpha`).
- `editor/core` --- the editor Kotlin/Compose core:
  - adjustments, filters/LUTs, frames, stickers, text, draw, regions,
    crop/geometry;
  - the on-device AI passes (magic erase, sky replace, bokeh, opt-in face
    retouch) with their bundled non-Ente ONNX models --- the editor bundles
    its applicable models per the distribution policy
    (PROJECT_ARCHITECTURE.md §6; Ente model weights are never bundled, they
    are download-on-demand in the gallery products);
  - the non-destructive edit pipeline (recipes, export preflight, the
    export-policy consent chip);
  - the lightweight video editor (trim, mute, speed, frame extract,
    GIF/WebP export) and the Roborazzi screenshot baselines.
- `shared/` --- the editor-contract (the versioned Gallery ↔ Edit handoff)
  and the video-edit plan module.

## Working on this branch

JDK 17 + Android SDK (`platforms;android-36`). No Rust toolchain needed ---
the editor declares **no network permission at all** and carries no native
engine code.

**When the lane's work is ready:** confirm `editor-ci.yml` is green on the
tip, then open a PR into `unified` --- the four-product integration gate
validates the whole family there. Backports from `main` are manual and rare;
this lane tracks `unified`, not `main`. See
[`.github/BRANCH_STRATEGY.md`](https://github.com/dukeoftoad/toadGALLERY/blob/main/.github/BRANCH_STRATEGY.md)
for the full topology and operator runbook.
