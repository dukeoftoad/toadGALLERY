# Roborazzi screenshot testing --- the record-first pipeline for the state cards

**Status:** LANDED (2026-11-06, the Phase 11 screenshot-testing round). This
doc records what Roborazzi is, what was wired into the build (version
catalog, plugins, testOptions, test dependencies), the three captured
subjects and their baseline tags, the record flow that works today, and the
verify flow that activates once baselines are committed. It is written
honestly about its own limits: **no baseline PNGs are committed yet, so
nothing in this round gates on visual drift yet** --- there is no local
Android SDK in the sandbox this code was written in, so the PNGs must come
from the CI record run's artifact first. Cross-references: ADR-0013 (CI as
the canonical build environment), ADR-0014 (the privacy gate --- everything
added here is test-classpath-only and touches zero runtime surface),
docs/architecture/screen-states.md (the state-card system the captures
pin), and `.github/workflows/screenshots.yml` (the record job).

---

## 1. What Roborazzi is

[Roborazzi](https://github.com/takahirom/roborazzi) (io.github.takahirom.roborazzi)
is a JVM screenshot-testing library for Android: it renders the REAL Compose
UI inside Robolectric (no emulator, no device) and captures the rendered
pixels as PNG files. Its workflow is the standard two-phase one:

- **Record**: a test run (or the plugin's `recordRoborazzi*` task) writes
  the current render to disk. With no verify flag set, Roborazzi's
  `captureRoborazzi()` **records by default** during a plain unit-test run
  --- which is why the record half of this pipeline needs no special task.
- **Verify**: the plugin's `verifyRoborazzi*` task re-renders and fails on a
  pixel diff against the committed baseline (and `compareRoborazzi*` writes
  diff overlays). The task only bites once baseline PNGs exist in the repo.

Robolectric supplies the Android runtime on the JVM; its NATIVE graphics
mode (Robolectric 4.10+) is what makes the render produce real pixels
Roborazzi can capture --- the default LEGACY mode cannot, so the mode pin is
load-bearing, not cosmetic.

## 2. What was added

- **gradle/libs.versions.toml** (versions): `robolectric = "4.17.0"` and
  `roborazzi = "1.77.0"` (the first merge-gate run caught `roborazzi
  1.26.0` not existing at all --- the plugin marker failed to resolve; the
  published line was re-verified against Maven Central metadata and both
  entries moved to the latest stable pair).
- **gradle/libs.versions.toml** (libraries): `robolectric`
  (org.robolectric:robolectric), `roborazzi`
  (io.github.takahirom.roborazzi:roborazzi), `roborazzi-compose`
  (io.github.takahirom.roborazzi:roborazzi-compose --- the Compose capture
  surface, `captureRoborazzi()` on `SemanticsNodeInteraction`).
- **gradle/libs.versions.toml (plugins): deliberately ABSENT.** The
  `io.github.takahirom.roborazzi` plugin was wired first and caught by the
  merge gate twice: 1.26.0 does not exist (resolution failure), and the
  real 1.77.0 --- while it applies --- drags a newer AGP variant API onto
  the script classpath that hard-errors this module's existing
  `srcDir(Any)` call sites (script-compilation errors at the
  withEngine/progress/bundled-models blocks that were green without the
  plugin). The documented fallback activated: the plugin entry is removed;
  the captures ride plain unit-test runs, which need no plugin. The
  verify/compare task pairs get their own round when an AGP-9-clean
  plugin story exists.
- **apps/gallery/build.gradle.kts, testOptions** (new block, next to
  `buildFeatures`):

  ```kotlin
  testOptions {
      unitTests {
          isIncludeAndroidResources = true
          all {
              it.systemProperty["robolectric.graphicsMode"] = "NATIVE"
              it.systemProperty["roborazzi.record.namingStrategy"] = "fullyQualifiedName"
          }
      }
  }
  ```

  `isIncludeAndroidResources` gives the JVM tests the app's real resources
  (the state cards resolve `stringResource` copy); NATIVE graphics is the
  Roborazzi precondition (section 1); `fullyQualifiedName` naming pins each
  PNG to its fully-qualified test method so baseline paths survive
  reordering and refactors.
- **apps/gallery/build.gradle.kts, dependencies** (test scope only --- zero
  runtime APK surface, the ADR-0014 gate is unaffected):
  `libs.robolectric`, `libs.roborazzi`, `libs.roborazzi.compose`,
  the BOM-pinned `compose.ui.test.junit4` (the same artifact the
  instrumented suite already uses --- it must ALSO sit on the unit-test
  classpath for `createComposeRule()` under Robolectric) and
  `androidx.test.ext.junit` (the `AndroidJUnit4` runner). The existing
  debug-scope `ui-test-manifest` already registers the `ComponentActivity`
  the rule launches.
- **Tests**: `apps/gallery/src/test/kotlin/io/github/dukeoftoad/gallery/ui/screenshot/`
  --- three self-contained capture tests (section 3).
- **CI**: `.github/workflows/screenshots.yml` (new file; no existing
  workflow was touched) --- the record job (section 4).

## 3. The three captured subjects and their baseline tags

All three subjects are the shared state cards of the screen-state system
(issue #13, docs/architecture/screen-states.md) --- deliberately chosen
public composables with the lightest parameter surfaces in `ui/media`: no
ViewModel, no Coil request, no `LaunchedEffect` timing, no network. The
only variability in their renders comes from the environment, which every
test pins identically: `@RunWith(AndroidJUnit4::class)`,
`@GraphicsMode(GraphicsMode.Mode.NATIVE)`,
`@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")` (a Pixel-4-class
viewport), and `MaterialTheme(colorScheme = lightColorScheme())`.

| Test file | Subject | Inputs | Baseline PNG (fullyQualifiedName tag) |
| --- | --- | --- | --- |
| `ErrorStateScreenshotTest.kt` | `ErrorState` | the three `state_photos_error_*`/`state_action_retry` string resources, no-op retry | `io.github.dukeoftoad.gallery.ui.screenshot.ErrorStateScreenshotTest.errorStateRecordsBaseline.png` |
| `LoadingStateScreenshotTest.kt` | `LoadingState` | none | `io.github.dukeoftoad.gallery.ui.screenshot.LoadingStateScreenshotTest.loadingStateRecordsBaseline.png` |
| `UnsupportedFileStateScreenshotTest.kt` | `UnsupportedFileState` | fixed literal file name (the non-null branch exercises strictly more layout) | `io.github.dukeoftoad.gallery.ui.screenshot.UnsupportedFileStateScreenshotTest.unsupportedFileStateRecordsBaseline.png` |

Two determinism decisions worth recording:

- **LoadingState's spinner is the one non-trivial capture.** An
  INDETERMINATE `CircularProgressIndicator` rotates on the frame clock; an
  unclocked capture records a different rotation angle per run and would
  make a future verify gate flaky by construction. The test freezes the
  Compose test clock BEFORE `setContent` (`mainClock.autoAdvance = false`)
  and advances it one fixed 16ms frame, so every capture lands on the same
  point of the rotation cycle. The other two subjects are static.
- **Test method names are space-free camelCase on purpose** (a deliberate
  deviation from the repo's backtick sentence-style test names): with
  `fullyQualifiedName` the method name becomes the baseline file name, and
  spaces in committed PNG paths would make the artifact globs and the
  verify task's file matching needlessly fragile.

## 4. The record flow (what works today)

Record happens during a PLAIN unit-test run --- Roborazzi records by default
when no verify flag is set, so the capture calls fire on every test run:

- **In CI**: the `screenshots` job of `.github/workflows/screenshots.yml`
  (trigger: `workflow_dispatch`, or pull_request touching `apps/gallery/**`
  / `gradle/libs.versions.toml`; `permissions: contents: read`; the
  canonical `./ ./.github/actions/toad-setup` toolchain) runs:

  ```bash
  ./gradlew :apps:gallery:testBasicDebugUnitTest --stacktrace --no-daemon
  ```

  then uploads the recorded PNGs as the `roborazzi-screenshots` artifact
  (retention 14 days), probing BOTH candidate locations ---
  `apps/gallery/src/test/resources/**/*.png` and
  `apps/gallery/build/outputs/roborazzi/**` --- because Roborazzi's default
  capture output directory moved between releases and the pinned 1.77.0's
  choice is not verified from the sandbox. `if-no-files-found` stays at the
  default `warn` (never `error`): an empty capture set must not mask the
  outcome of the test run itself.
- **Locally** (any machine with an Android SDK): the same invocation ---
  `./gradlew :apps:gallery:testBasicDebugUnitTest --stacktrace` --- writes
  the PNGs under the module.

## 5. The future verify flow (what activates once baselines land)

1. Download the `roborazzi-screenshots` artifact from a green record run
   (or record locally on an SDK machine).
2. Copy the PNGs into the module's capture output location
   (`apps/gallery/src/test/resources/` or wherever the pinned version wrote
   them --- the artifact's internal layout tells you which).
3. Commit them as the reviewed baselines (they are committed artifacts, not
   build outputs --- `.gitignore` excludes `build/` but not
   `src/test/resources/`).
4. Add the gate step to `screenshots.yml`'s job (after the unit-test step):

   ```bash
   ./gradlew :apps:gallery:verifyRoborazziBasicDebug --stacktrace --no-daemon
   ```

   From that point the job fails on visual drift in the three state cards,
   and `compareRoborazziBasicDebug` produces diff overlays for triage.

Until step 4 exists, the screenshots are documentation and a drift
early-warning (a human comparing artifacts across runs), NOT a gate. That
limitation is recorded here rather than papered over: record-only is the
honest first step when no local Android SDK exists to have produced the
baselines from.

## 6. The flavor-qualified task-name note

With the gallery's product flavors (basic / engine / semantic / face, one
"engine" dimension) in place, Android unit-test tasks are flavor-qualified:
`testDebugUnitTest` becomes `testBasicDebugUnitTest` (and siblings), and the
Roborazzi plugin's task pairs follow the same grammar:
`recordRoborazziBasicDebug` / `verifyRoborazziBasicDebug` /
`compareRoborazziBasicDebug`. This round's workflow and doc use the FINAL,
flavor-qualified names by design. Sequencing caveat, recorded honestly: if
this PR is reviewed/merged BEFORE the flavors PR, the record step fails with
"task not found" until the flavor PR merges --- a known, deliberate red, not
a silent skip (the same note lives in the workflow's header comment). Once
the flavor PR lands, the names resolve as written.

## 7. Compatibility risks, stated plainly

- **Roborazzi gradle plugin vs AGP 9.2.1 --- CAUGHT, fallback ACTIVATED.**
  The first attempt (1.26.0) never resolved --- that version does not
  exist; the merge-gate run failed at plugin resolution and the published
  line was re-verified against Maven Central metadata (marker
  `io.github.takahirom.roborazzi:io.github.takahirom.roborazzi.gradle.
  plugin`, latest 1.77.0 --- the plugin id is the `io.github...` one, not
  `com.github...`). The second attempt (1.77.0) applied cleanly but broke
  the BUILD SCRIPT itself: the plugin's classpath carried a newer AGP
  variant API in which `srcDir(Any)` is gone (deprecated ->
  `directories`), so the module's pre-existing withEngine /
  copyProgressJson / withBundledModels srcDir call sites became script
  COMPILATION ERRORS ("No 'set' operator..."-class failures alongside the
  genuinely-wrong testOptions map syntax, itself fixed to
  `systemProperty(key, value)` method calls). The documented fallback
  activated exactly as written: the plugin alias and the catalog plugin
  entry are removed; recording via plain test runs needs no plugin; only
  the verify/compare task pairs do, and section 5's flow is not activated
  yet anyway. A future round may pin an AGP-9-clean plugin version --- the
  catalog comment marks the spot.
- **Robolectric 4.17.0 and sdk 34.** The tests pin sdk 34 explicitly in
  `@Config`, so newer compileSdk/targetSdk values do not change what
  Robolectric renders. Robolectric downloads its android-all jars from
  Maven Central at test runtime --- first CI runs pay that download, the
  Gradle cache retains it.
- **Robolectric 4.17.0 needs JDK 17+** --- satisfied (the toolchain pins
  temurin 17). Kotlin 2.3.10 / the Compose compiler are invisible to the
  JVM tests; the Compose test artifacts ride the pinned BOM 2024.12.01
  (compose ui-test 1.7.x), the same coordinates the instrumented suite
  already uses, so no new version pressure enters the graph.
- **First-run red possibilities, honestly listed**: (a) the plugin-vs-AGP-9
  risk above; (b) the flavor-qualified task name if the flavors PR has not
  merged (section 6); (c) Robolectric's android-all download hiccup on a
  fresh runner. None of these are silent: each fails loudly and is
  attributable.
