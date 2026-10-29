# toadGALLERY --- Development Roadmap

**Status:** Planning draft\
**Product vision:** A polished, private, offline-first Android gallery
inspired by the simplicity of Xiaomi/Mi Gallery, built using Fossify
Gallery and Aves as the two upstream base branches, with a separate
installable **toadGALLERY Edit** extension.

## 1. Product goals

-   Build one coherent product called **toadGALLERY**, not two competing
    gallery apps.
-   Use **Fossify Gallery** and **Aves** as upstream base branches and
    sources of implementation, while deliberately deciding how their
    code and features fit together.
-   Deliver a clean, fast, intuitive gallery with strong media browsing,
    metadata, search, and safe file operations.
-   Ship **toadGALLERY Edit** as a separate APK that can be opened from
    the main Gallery app and updated independently.
-   Keep the product private and offline-first: no mandatory account,
    cloud service, ads, analytics, or network access.
-   Protect originals by default and make destructive operations
    explicit.
-   Establish clear licensing, attribution, testing, and upstream-change
    tracking from the start.

## 2. Project principles

1.  **Audit before merging.** Do not blindly merge repositories or
    duplicate overlapping features. Inspect both bases and document what
    to retain, adapt, port, replace, or defer.
2.  **One product experience.** Different upstream implementations must
    feel like one app, with consistent navigation, design, terminology,
    permissions, and media behavior.
3.  **Choose architecture based on evidence.** Fossify is a native
    Android/Kotlin project; Aves is primarily Flutter/Dart with some
    Android-native code. Aves UI code cannot simply be dropped into a
    Kotlin UI. Decide the integration method after the source,
    dependency, and build audit.
4.  **Keep the editor separate.** The Gallery and Edit extension are
    distinct installable apps, with a documented and secure integration
    contract.
5.  **Prefer incremental, buildable changes.** Maintain a working build
    at every phase; avoid a large, untestable rewrite.
6.  **Preserve upstream history and license obligations.** Record
    repository URLs, exact commits, licenses, copied/adapted files,
    dependency licenses, and required notices.
7.  **No feature is complete until tested.** Include error handling,
    accessibility, performance, permissions, and large-library behavior.

## 3. Target repository and app structure

The exact directory layout is a proposal to validate in Phase 0. The
final structure should reflect the chosen integration strategy.

``` text
toadGALLERY/
├── apps/
│   ├── gallery/                 # Main toadGALLERY app
│   └── edit-extension/          # Separate toadGALLERY Edit APK
├── shared/                      # Shared code only where technically appropriate
│   ├── media-contract/
│   ├── metadata/
│   ├── design-system/
│   └── editor-contract/
├── upstream/
│   ├── fossify-gallery/         # Tracked upstream reference/base
│   └── aves/                    # Tracked upstream reference/base
├── docs/
│   ├── architecture/
│   ├── feature-matrix.md
│   ├── integration-decisions.md
│   └── licensing-and-attribution.md
└── roadmap.md
```

This is a planning model, not a requirement to keep both upstream
projects as nested folders. If Git history, build systems, or license
requirements make another arrangement safer, document that decision
before implementation.

### Branch and repository policy

-   Record the exact upstream commit used for each base.
-   Keep upstream history and source attribution where practical.
-   Maintain a feature matrix showing the origin and status of each
    capability.
-   Use short-lived implementation branches and reviewed, buildable
    merges.
-   Do not create a permanent maintenance burden by maintaining an
    unnecessary independent Aves fork.
-   Do not describe the product as a direct merge until the integration
    audit confirms the chosen approach.

## 4. Phased roadmap

## Phase 0 --- Align scope and establish the baselines

**Purpose:** Make the project reproducible before changing code.

### Tasks

-   Confirm the canonical upstream repositories and selected base
    branches/commits:
    -   Fossify Gallery: https://github.com/FossifyOrg/Gallery
    -   Aves: https://github.com/deckerst/aves
-   Clone/check out both bases and record commit hashes, licenses, build
    instructions, toolchain versions, and dependency manifests.
-   Build each upstream project independently in a clean environment.
-   Record build failures, platform requirements, test results, app IDs,
    and signing/debug setup.
-   Define the initial supported Android range and the Android 16
    behavior that must be tested.
-   Create `docs/feature-matrix.md`, `docs/integration-decisions.md`,
    and `docs/licensing-and-attribution.md`.
-   Agree on the initial must-have features and explicitly defer
    nonessential features.

### Deliverables

-   Reproducible baseline build notes for both projects.
-   Source and dependency inventory.
-   Feature overlap matrix.
-   Initial risk register and integration options.

### Exit criteria

-   Both upstream baselines are built or their blockers are documented.
-   Exact revisions and applicable licenses are recorded.
-   No irreversible repository merge has been made before the audit.

## Phase 1 --- Source audit and integration design

**Purpose:** Determine how Fossify and Aves contribute to one product.

### Tasks

-   Audit Fossify's media access, models/database, UI/navigation, image
    viewer, search, trash, metadata, editing dependencies, and
    permission handling.
-   Audit Aves's browsing, filtering, metadata, media-format handling,
    viewer interactions, caching, and platform-specific code.
-   Identify overlap, incompatible assumptions, duplicate dependencies,
    and feature gaps.
-   For every candidate feature, select one path:
    -   **Keep** the implementation from one base.
    -   **Adapt/port** compatible code with license notices.
    -   **Reimplement** the behavior in the target architecture.
    -   **Defer** it to a later release.
-   Decide whether Aves contributes source modules, selected native
    code, or primarily feature/design references. Do not assume Flutter
    UI can be embedded directly into a native Kotlin app without a
    deliberate architecture decision.
-   Define the main app's package ID, app identity, navigation,
    persistence, media-access boundary, and design system.
-   Define how the editor will receive media and return results.

### Deliverables

-   Completed feature-by-feature integration matrix.
-   Architecture decision records, including the reason for the chosen
    integration approach.
-   Dependency and license compatibility report.
-   Initial app/module diagram and data-flow diagram.

### Exit criteria

-   Every major feature has a clear implementation owner/source.
-   The team can explain how the two bases contribute without
    maintaining two disconnected gallery products.
-   Licensing and technical blockers are identified before substantial
    code is copied.

## Phase 2 --- Product shell and visual foundation

**Purpose:** Establish the toadGALLERY identity and a working app shell.

### Tasks

-   Set product name, package/application IDs, icons, theme, splash
    screen, and release identity.
-   Establish a consistent visual system inspired by Mi Gallery's
    clarity and simplicity without copying proprietary assets or
    branding.
-   Define typography, spacing, color roles, light/dark themes, system
    bars, edge-to-edge layout, and accessible touch targets.
-   Build the main navigation and initial screens: Photos, Albums,
    Search, and Settings; place Favorites and Trash where usability
    testing supports.
-   Define empty, loading, denied-permission, unsupported-file, and
    error states.
-   Keep UI transitions and selection behavior consistent across
    screens.

### Deliverables

-   Installable toadGALLERY development APK.
-   Design tokens and reusable UI components.
-   Navigation skeleton and screen-state guidelines.

### Exit criteria

-   App launches and navigates reliably.
-   Visual foundations are reusable and accessible.
-   No large feature implementation is coupled to unfinished navigation.

## Phase 3 --- Core media access and browsing

**Purpose:** Make the Gallery useful and dependable.

### Tasks

-   Implement Android MediaStore/content-URI access and modern
    photo/video permission flows.
-   Support Android's limited photo/video access where applicable; avoid
    relying on unrestricted filesystem paths.
-   Build timeline/grid browsing, albums/folders, image viewer, video
    playback, favorites, sorting, multi-select, and sharing.
-   Implement safe copy, move, delete, restore, and trash behavior with
    explicit confirmation where appropriate.
-   Handle content URIs, removable storage where supported, revoked
    access, missing files, and provider errors.
-   Load thumbnails progressively; do not decode full-resolution
    originals for grid cells.
-   Add cancellation and clear user feedback for long-running
    operations.

### Deliverables

-   Working end-to-end browsing and basic media operations.
-   Media-access and file-operation tests.
-   Defined behavior for unavailable or partially accessible media.

### Exit criteria

-   Users can browse and open supported local media.
-   Basic operations work on real devices and content providers.
-   Permission changes and missing files do not crash the app or corrupt
    data.

## Phase 4 --- Metadata, formats, and Aves-derived capabilities

**Purpose:** Add deeper media information and carefully selected
advanced capabilities.

### Tasks

-   Build a metadata service with a clear boundary between metadata
    extraction and UI.
-   Reuse suitable existing libraries first; assess Aves-specific
    capabilities and format readers before porting anything.
-   Present available metadata such as dimensions, file size, capture
    date, camera/lens information, exposure settings, and GPS data when
    present.
-   Distinguish unavailable metadata from metadata that is genuinely
    absent.
-   Run expensive parsing off the main thread and cache results only
    where justified.
-   Audit support for HEIC/HEIF, AVIF, RAW, animated formats, and
    unusual video formats; state actual device/library limitations
    rather than promising universal support.
-   Consider maps or reverse geocoding only as optional features
    requiring explicit network-aware behavior.

### Deliverables

-   Metadata details view.
-   Format-support matrix and tested sample corpus.
-   Aves port/adaptation records and license notices.

### Exit criteria

-   Metadata extraction does not block scrolling or viewer interaction.
-   Unsupported/corrupt files fail gracefully.
-   Each imported or adapted component has traceable origin and license
    compliance.

## Phase 5 --- Advanced search and organization

**Purpose:** Make large libraries easy to explore.

### Tasks

-   Extend search to compose filters for filename, date range, media
    type, album/folder, favorites, and available metadata.
-   Add clear filter chips, removable conditions, useful sorting, and
    empty-result explanations.
-   Reuse existing indexes where practical; add database/indexing only
    after measuring actual query cost.
-   Define timezone and date semantics for capture date versus file
    date.
-   Avoid rescanning the entire library on every search or app launch.
-   Add tests for missing dates, duplicate names, unusual characters,
    and large result sets.

### Deliverables

-   Advanced search interface and filter model.
-   Search/query tests and performance measurements.
-   Documented indexing and cache invalidation behavior.

### Exit criteria

-   Filters combine predictably and can be cleared easily.
-   Search remains responsive on representative large libraries.
-   Indexes stay consistent when media changes or permissions are
    revoked.

## Phase 6 --- toadGALLERY Edit extension

**Purpose:** Deliver a separate editor APK that integrates naturally
with the Gallery.

### Tasks

-   Create a separate installable app ID and release artifact for
    **toadGALLERY Edit**.
-   Define a versioned intent/content-URI contract: launch editor, pass
    source media and requested operation, return or publish the result,
    and report cancellation/errors.
-   Grant only the URI permissions required; never depend on arbitrary
    absolute paths.
-   Define behavior when the extension is not installed, is outdated, or
    cannot open a format.
-   Implement a non-destructive editing pipeline and preview model.
-   Initial tools: crop, rotate/flip, resize, drawing/annotation, basic
    adjustments, and a small filter set, ordered by product priority.
-   Provide undo/redo where supported and keep previews separate from
    original bytes.
-   Default to **Save copy**. Offer replace-original only when the
    operation can be performed safely and the user explicitly chooses
    it.
-   Preserve or intentionally remove metadata according to clear export
    settings; never silently strip GPS/EXIF.
-   Test large files, cancellation, low storage, process death, and
    round-trip return to Gallery.

### Deliverables

-   Separate toadGALLERY Edit APK.
-   Versioned editor integration contract.
-   End-to-end Gallery → Edit → Gallery tests.
-   Export/original-preservation policy.

### Exit criteria

-   Gallery can detect and launch the extension safely.
-   The editor can export a result and the Gallery refreshes correctly.
-   Original files are not silently overwritten or lost.
-   Missing extension and failed edits have clear recovery paths.

## Phase 7 --- Trash, reliability, privacy, and accessibility

**Purpose:** Make everyday use safe and trustworthy.

### Tasks

-   Finalize trash/recovery semantics and retention behavior; explain
    when Android/provider limitations prevent a reversible delete.
-   Review all permissions and ensure they are requested only when
    needed.
-   Ensure no mandatory account, cloud, analytics, advertising, or
    background network requirement is introduced.
-   Add TalkBack labels, focus order, scalable text, contrast checks,
    and appropriately sized touch targets.
-   Test configuration changes, low memory, background/foreground
    transitions, interrupted operations, and storage exhaustion.
-   Review sensitive metadata display and export behavior.
-   Add privacy and data-handling documentation.

### Deliverables

-   Privacy/permission audit.
-   Accessibility test report.
-   Recovery and failure-mode test suite.

### Exit criteria

-   Common failure paths do not lose user data.
-   Core flows are usable with accessibility features.
-   Network access, if any optional feature requires it, is disclosed
    and not required for core use.

## Phase 8 --- Performance and compatibility

**Purpose:** Validate real-world behavior before release.

### Tasks

-   Measure cold launch, first usable content, scroll smoothness,
    memory, thumbnail cache behavior, search latency, and metadata
    extraction.
-   Test libraries of approximately 10,000 and 50,000 items, with
    realistic media mixtures.
-   Profile startup and scrolling before optimizing; consider Baseline
    Profiles if beneficial.
-   Test low/mid-range devices, Android 16, older supported Android
    versions, large images, long videos, corrupt files, and partial
    media permissions.
-   Verify that background work is bounded, cancellable, and does not
    wake the device unnecessarily.
-   Test upgrades, database migrations, editor version mismatch, and
    both apps installed/uninstalled in different orders.

### Suggested initial targets

These are goals to validate, not guarantees: - First usable gallery
content within about 2 seconds on a defined mid-range test device. - No
full-library rescan on every launch. - Smooth thumbnail scrolling under
a documented representative test set. - Search on indexed fields feels
immediate on the target library size. - No full-resolution decode for
ordinary grid thumbnails.

### Deliverables

-   Repeatable benchmark scenarios and baseline results.
-   Device/API compatibility matrix.
-   Prioritized performance fixes.

### Exit criteria

-   No known critical data-loss, permission, or stability issue remains.
-   Performance is measured against documented devices and sample
    libraries.
-   Unsupported formats and platform constraints are documented.

## Phase 9 --- Release readiness

**Purpose:** Prepare the two apps for maintainable distribution.

### Tasks

-   Review all source and dependency licenses, notices, attribution, and
    source-distribution obligations.
-   Configure separate app IDs, versioning, signing, build variants, and
    release artifacts.
-   Set up automated builds, unit tests, lint/static checks, and
    installation/integration smoke tests.
-   Verify clean install, upgrade, rollback/recovery where feasible, and
    independent editor updates.
-   Prepare release notes, known limitations, privacy documentation, and
    support/issue templates.
-   Confirm app-store policy and permission declarations for the
    intended distribution channel.

### Deliverables

-   Release-ready Gallery APK and Edit APK.
-   Reproducible build instructions and release checklist.
-   License/attribution package and documented known limitations.

### Exit criteria

-   Both artifacts build reproducibly and pass the release test suite.
-   License and distribution obligations are reviewed.
-   Gallery and editor can be released and updated independently.

## 5. Cross-phase quality gates

Every phase must maintain these conditions:

-   **Build:** changes compile, or the blocker is documented and
    isolated.
-   **Tests:** add tests for new behavior and regressions.
-   **Privacy:** core gallery functionality remains offline-first.
-   **Data safety:** originals and user files are protected from
    accidental destructive operations.
-   **Permissions:** test full access, limited access, denial, and
    revocation where supported.
-   **Performance:** no heavy file I/O, metadata parsing, or
    full-resolution decoding on the UI thread.
-   **Licensing:** record source origin and license for copied/adapted
    code and dependencies.
-   **Documentation:** update architecture and feature decisions when
    implementation choices change.

## 6. Initial must-have scope

### First usable release

-   Photo/video timeline and viewer.
-   Albums/folders and favorites.
-   Multi-select, sharing, and basic file operations.
-   Metadata details for supported formats.
-   Advanced search with date/type/album/favorite/name filters.
-   Trash/recovery where platform APIs permit.
-   Separate toadGALLERY Edit APK with a reliable launch/export round
    trip.
-   Android permission, privacy, accessibility, and performance checks.

### Defer until the foundation is stable

-   Cloud sync, accounts, social sharing, ads, and analytics.
-   AI classification or generative editing.
-   Always-on background indexing.
-   Online maps/reverse geocoding as a core dependency.
-   Complex RAW development or professional-grade editing.
-   Broad format promises without verified decoder support.

## 7. First implementation sprint

1.  Check out and record both upstream base revisions.
2.  Build Fossify and Aves independently; document toolchain and
    blockers.
3.  Inventory features, dependencies, licenses, and overlapping
    implementations.
4.  Create the feature matrix and choose an integration strategy for
    each capability.
5.  Establish a minimal toadGALLERY identity/build configuration without
    breaking the baseline.
6.  Prove the chosen Gallery ↔ Edit communication approach with a small
    prototype before building the full editor.
7.  Only then begin the first core browsing implementation.

## 8. Definition of success

toadGALLERY succeeds when it feels like one thoughtfully designed
gallery rather than a collection of merged upstream features: browsing
is fast, search and metadata are useful, file operations are safe, the
product respects privacy, and the separate Edit extension behaves as a
natural part of the experience.

**Planning note:** This roadmap is a proposal. Exact architecture,
package/module layout, supported formats, SDK levels, performance
thresholds, and release scope should be confirmed through Phase 0 and
Phase 1 evidence rather than assumed in advance.
