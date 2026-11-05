# Roborazzi screenshot testing --- the record-first pipeline for the state cards

**Status:** LANDED, then made PLUGIN-FIRST (2026-11-06, Phase 11; plugin-first
r47 follow-up). This doc records what Roborazzi is, what was wired into the
build (version catalog, plugins, testOptions, test dependencies), the three
captured subjects and their baseline tags, the record flow, and the verify
flow that activates once baselines are committed. It is written honestly
about its own limits: **no baseline PNGs are committed yet, so nothing gates
on visual drift yet** --- there is no local Android SDK in the sandbox this
code was written in, so the PNGs must come from the CI record run's artifact
first. Cross-references: ADR-0013 (CI as the canonical build environment),
ADR-0014 (the privacy gate --- everything added here is test-classpath-only
and touches zero runtime surface), docs/architecture/screen-states.md (the
state-card system the captures pin), and `.github/workflows/screenshots.yml`
(the record/verify job).

---

## 1. What Roborazzi is

[Roborazzi](https://github.com/takahirom/roborazzi) (io.github.takahirom.roborazzi)
is a JVM screenshot-testing library for Android: it renders the REAL Compose
UI inside Robolectric (no emulator, no device) and captures the rendered
pixels as PNG files. Its workflow is the standard two-phase one:

- **Record**: the plugin's `recordRoborazzi*` task runs the capture tests
  with `roborazzi.test.record=true` and writes the current render to disk.
  With the plugin applied, a PLAIN unit-test run does NOT record: the plugin
  sets `roborazzi.test.record=false` on every test task unless a roborazzi
  task is in the graph, so `captureRoboImage()` no-ops (exactly the toadFILES
  behavior --- CI test runs stay fast and green without capturing).
- **Verify**: the plugin's `verifyRoborazzi*` task re-renders and fails on a
  pixel diff against the committed baseline (and `compareRoborazzi*` writes
  diff overlays). The task only bites once baseline PNGs exist in the repo.

Robolectric supplies the Android runtime on the JVM; its NATIVE graphics
mode (Robolectric 4.10+) is what makes the render produce real pixels
Roborazzi can capture --- the default LEGACY mode cannot, so the mode pin is
load-bearing, not cosmetic.

## 2. What was added

- **gradle/libs.versions.toml** (versions): `robolectric = "4.17"` and
  `roborazzi = "1.77.0"` (the first merge-gate run caught `roborazzi
  1.26.0` not existing at all --- the plugin marker failed to resolve; the
  published line was re-verified against Maven Central metadata and both
  entries moved to the latest stable pair).
- **gradle/libs.versions.toml** (libraries): `robolectric`
  (org.robolectric:robolectric), `roborazzi`
  (io.github.takahirom.roborazzi:roborazzi), `roborazzi-compose`
  (io.github.takahirom.roborazzi:roborazzi-compose --- the Compose capture
  surface; the capture function is `captureRoboImage()` (package
  com.github.takahirom.roborazzi --- the Kotlin API package predates the
  maven-group rename to io.github.takahirom, so the IMPORT is the
  com.github.takahirom.roborazzi one)).
- **gradle/libs.versions.toml (plugins): APPLIED since r47.** The
  `io.github.takahirom.roborazzi` plugin (version.ref = the existing
  `roborazzi = "1.77.0"` line) is declared in the catalog and applied in
  `apps/gallery/build.gradle.kts`, closing the r46-era "deliberately ABSENT"
  state. The r46 verdict ("the real 1.77.0 drags a newer AGP variant API
  onto the script classpath that hard-errors the module's `srcDir(Any)` call
  sites") was re-examined at the ARTIFACT level before the alias went back
  in, and does not survive the re-examination: the published 1.77.0 plugin's
  runtime graph (its POM and its gradle module metadata --- what Gradle
  actually resolves) carries NO AGP at all (roborazzi-core-jvm, kotlin-stdlib,
  differ, kotlinx-serialization-json, kotlinx-io-core, webjars --- nothing
  else), every AGP class the plugin's bytecode references exists in AGP
  9.2.1's `gradle-api` jar (Sources, HasUnitTest, UnitTestOptions, ...), and
  the sibling toadFILES repo runs this exact plugin version green on a NEWER
  AGP line (9.5.0 / Gradle 9.8.2) --- so the r46 script-compilation failure
  was most plausibly a misattribution inside that branch's error cascade
  (its own testOptions map-syntax error is recorded as real), not a
  plugin-vs-AGP incompatibility. CI remains the judge (ADR-0013): if the
  merge gate still trips the script, the recorded escape hatch is documented
  in section 7. What the plugin application CHANGES for real: plain
  unit-test runs stop recording (section 1), and the
  record/verify/compare task pairs become the pipeline's drivers (sections
  4-5).
- **apps/gallery/build.gradle.kts, the `roborazzi { }` extension** (new
  block, right after the plugins): the plugin's capture output dir is moved
  OFF the default `build/outputs/roborazzi` (a build dir can never hold
  committed baselines) onto the committed location:

  ```kotlin
  roborazzi {
      outputDir.set(layout.projectDirectory.dir("src/test/resources/roborazzi"))
  }
  ```

  The plugin resolves this Provider and hands it to the tests as the
  `roborazzi.output.dir` system property, so `recordRoborazziBasicDebug`
  writes the PNGs straight into the directory they are committed from and
  `verifyRoborazziBasicDebug` reads them back from the same place --- no
  copy step ever exists between a recorded artifact and a committed
  baseline. The three `srcDir` call sites in the module's build script (the
  withEngine jniLibs dir, the copyProgressJson generated assets dir, the
  withBundledModels assets dir) were re-audited for this round and STAY on
  the explicit plain-File form: that is the form AGP 9.2.1 accepts at
  RUNTIME (its `DefaultAndroidSourceDirectorySet.srcDir(Object)` throws
  "You cannot add Provider instances to the Android SourceSet API" for a
  Provider argument unless the `android.sourceset.disallowProvider=false`
  escape hatch is set --- verified in the AGP 9.2.1 bytecode, and that hatch
  is deliberately NOT set here), and the plugin puts no AGP on the script
  classpath that could remove the `srcDir(Any)` overload (see above).
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

Record is the plugin's `recordRoborazziBasicDebug` task (r47, plugin-first;
a PLAIN unit-test run no longer records --- section 1):

- **In CI**: the `screenshots` job of `.github/workflows/screenshots.yml`
  (trigger: `workflow_dispatch` --- mode `record` explicitly, or any run
  while ZERO baselines are committed, so the F-34 source artifact always
  exists; plus `pull_request` touching `apps/gallery/**` /
  `gradle/libs.versions.toml`; `permissions: contents: read`; the canonical
  `./.github/actions/toad-setup` toolchain) runs:

  ```bash
  ./gradlew :apps:gallery:recordRoborazziBasicDebug --stacktrace --no-daemon
  ```

  The task runs the three capture tests with `roborazzi.test.record=true`
  and writes the PNGs into the plugin extension's output dir,
  `apps/gallery/src/test/resources/roborazzi` --- the committed-baseline
  location ITSELF, so no copy step ever exists between a recorded artifact
  and a committed baseline --- then uploads them as the
  `roborazzi-baselines` artifact (retention 14 days).
- **Locally** (any machine with an Android SDK): the same task invocation
  --- `./gradlew :apps:gallery:recordRoborazziBasicDebug --stacktrace` ---
  writes the PNGs into the same committed location.

## 5. The verify flow (what activates once baselines are committed)

The verify gate step ALREADY EXISTS in `screenshots.yml` (r47): every
non-record run executes `verifyRoborazziBasicDebug` when committed baselines
exist under `apps/gallery/src/test/resources/roborazzi/`, and SKIPS it
loudly (step log + job summary + a `::notice::`) when none do. Activation
is therefore purely the maintainer's baseline-commit step (F-34 step 2) ---
no workflow edit is needed:

1. Download the `roborazzi-baselines` artifact from a green record run (or
   record locally on an SDK machine).
2. Unzip it at the repo root so the PNGs land at
   `apps/gallery/src/test/resources/roborazzi/` (that directory IS the
   capture output dir --- no path fixing needed).
3. Commit them as the reviewed baselines (they are committed artifacts, not
   build outputs --- `.gitignore` excludes `build/` but not
   `src/test/resources/`).

From that first commit onward the same job runs
`./gradlew :apps:gallery:verifyRoborazziBasicDebug --stacktrace
--no-daemon` on every verify-mode run and FAILS on visual drift in the
three state cards; `compareRoborazziBasicDebug` produces diff overlays for
triage. Until that commit lands, the screenshots are documentation and a
drift early-warning (a human comparing artifacts across runs), NOT a gate
--- and the skip is loud, never silent.

## 6. The flavor-qualified task-name note

With the gallery's product flavors (basic / engine / semantic / face, one
"engine" dimension) in place, Android unit-test tasks are flavor-qualified:
`testDebugUnitTest` becomes `testBasicDebugUnitTest` (and siblings), and the
Roborazzi plugin's task pairs follow the same grammar:
`recordRoborazziBasicDebug` / `verifyRoborazziBasicDebug` /
`compareRoborazziBasicDebug`. The workflow and doc use the flavor-qualified
names, which resolve as written since the flavors PR (#124, r46) merged ---
the r46-era "known red until the flavors merge" sequencing caveat is
closed.

## 7. Compatibility risks, stated plainly

- **Roborazzi gradle plugin vs AGP 9.2.1 --- caught in r46, RE-EXAMINED
  and RESOLVED in r47.** The r46 history, kept honest: the first attempt
  (1.26.0) never resolved --- that version does not exist; the merge-gate
  run failed at plugin resolution and the published line was re-verified
  against Maven Central metadata (marker
  `io.github.takahirom.roborazzi:io.github.takahirom.roborazzi.gradle.
  plugin`, latest 1.77.0 --- the plugin id is the `io.github...` one, not
  `com.github...`). The second attempt (1.77.0) applied with the merge
  gate red, and r46 fell back to the no-plugin record-by-default pipeline,
  blaming the plugin's classpath for the script-compilation failures. The
  r47 re-examination (the full artifact-level audit is recorded in
  section 2) found that blame did not survive scrutiny: the published
  plugin's runtime graph carries NO AGP at all, every AGP class its
  bytecode references exists in AGP 9.2.1's `gradle-api` jar, and the
  sibling toadFILES repo runs this exact plugin version green on a newer
  AGP line (9.5.0 / Gradle 9.8.2). The plugin alias is therefore back in
  (section 2), the pipeline is plugin-first, and the recorded escape
  hatch if CI still trips the script --- set
  `android.sourceset.disallowProvider=false`, never preemptively --- is
  the only remaining lever (the three srcDir call sites already sit on
  the runtime-safe plain-File form).
- **Robolectric 4.17 and sdk 34.** The tests pin sdk 34 explicitly in
  `@Config`, so newer compileSdk/targetSdk values do not change what
  Robolectric renders. Robolectric downloads its android-all jars from
  Maven Central at test runtime --- first CI runs pay that download, the
  Gradle cache retains it.
- **Robolectric 4.17 needs JDK 17+** --- satisfied (the toolchain pins
  temurin 17). Kotlin 2.3.10 / the Compose compiler are invisible to the
  JVM tests; the Compose test artifacts ride the pinned BOM 2024.12.01
  (compose ui-test 1.7.x), the same coordinates the instrumented suite
  already uses, so no new version pressure enters the graph.
- **First-run red possibilities, honestly listed**: (a) the plugin-vs-AGP-9
  risk above; (b) the flavor-qualified task name if the flavors PR has not
  merged (section 6); (c) Robolectric's android-all download hiccup on a
  fresh runner. None of these are silent: each fails loudly and is
  attributable.

## 8. The r48 round: the plugin lands, the README suite, and the F-34 gate

**Status: 2026-11-06, the r48 round (branch `tg-r48-screenshots-workflows`,
the toadFILES-alignment directive).** Four changes, each closing a recorded
limitation of the record-only era:

- **The Roborazzi gradle plugin is re-applied** (`alias(libs.plugins.roborazzi)`
  in `apps/gallery/build.gradle.kts`; the catalog plugin entry restored).
  Section 7's blocker --- the plugin classpath hard-erroring the module's
  then-existing `srcDir(Any)` call sites --- is gone as a matter of code
  fact: the flavors round rewrote all three sites (the engine `jniLibs`,
  `progressAssets` and `bundled-models` `assets` srcDirs) to the AGP-9
  plain-File convention (`...srcDir(layout.buildDirectory.dir(...).get().asFile)`),
  so the plugin's newer AGP variant API no longer meets a `srcDir(Any)`
  anywhere in the script. With the plugin applied, `captureRoboImage()`
  fires ONLY under the record/verify tasks: plain unit-test runs
  (`ci.yml`'s `testBasicDebugUnitTest` / `testEngineDebugUnitTest`) skip
  captures entirely, which is the toadFILES behavior and keeps the
  canonical gate fast and tree-clean.
- **The README screenshot suite lands**
  (`ReadmeScreenshotsTest.kt`, the toadFILES `TabScreenshotsTest` pattern):
  the REAL app shell (`MainActivity` + `ToadGalleryApp`) over a seeded
  media world --- a `FakeMediaProvider` registered under the `media`
  authority answering every `content://media/...` query with twelve fixed
  rows, real JPEG bytes on the Robolectric filesystem (the app's own Coil
  pipeline decodes them through `openFile`), and the same rows upserted
  into the Room catalogue before the activity launches. Five captures
  (`roborazzi/01-photos.png` .. `05-settings.png`) walk the real bottom
  navigation by label; the committed copies render the README's
  Screenshots section, and the regeneration recipe is documented there.
- **`screenshots.yml` becomes verify-then-record**: the drift gate
  (`verifyRoborazziBasicDebug`) runs FIRST, gated on committed baselines
  (`hashFiles('apps/gallery/roborazzi/*.png')` inspects the checkout, so
  the step is an honest skip before baselines exist and a hard gate
  after); the record pass (`recordRoborazziBasicDebug`) runs always and
  feeds the `roborazzi-screenshots` artifact. Ordering is load-bearing:
  record overwrites the working-tree PNGs, so a record-then-verify
  sequence would compare fresh renders against themselves.
- **F-34 closes** along the doc's own section-5 recipe: the record run's
  artifact is downloaded, the state-card PNGs are committed under
  `apps/gallery/roborazzi/` (the plugin's record output dir is also its
  verify input dir --- that is the whole trick), and from that commit on
  the gate is live on every PR touching the gallery.

Known shape, recorded honestly: the baseline PNGs live in the MODULE's
`roborazzi/` dir (not `src/test/resources` as section 5's first draft
guessed) --- `.gitignore` does not exclude it, and the plugin's task pair
reads and writes the same location, so committed-baselines-there is the
self-consistent layout. The README suite's explicit `roborazzi/NN-*.png`
paths deliberately ignore the `fullyQualifiedName` naming strategy (they
are README assets first, baselines second); one flavor (basic) is
recorded, because the names would collide across flavors.

**r48 addendum, the first CI rounds' catches (three honest iterations):**
(1) the catalog plugin alias was rewritten in comment form but the alias
LINE itself never landed --- `alias(libs.plugins.roborazzi)` failed as an
unresolved reference until the entry was restored; (2) the rename icon
fix's `Icons.Filled.DriveFileRenameOutline` needed its explicit import
(extended-set icons are extension properties, and the viewer imports its
icons one by one); (3) the README suite's first run caught the two real
Robolectric behaviors the toadFILES pattern implies but this repo had
never exercised: **runtime permission requests never resolve on the JVM**
(ShadowActivity delegates `requestPermissions` to the REAL Activity code,
which `startActivityForResult`es into a system UI that does not exist ---
the grant callback must be delivered by hand through the shadow-recorded
request), and **Coil's background decodes need a wall-clock settle before
capture** (compose idling does not track them; the albums capture beat
the decode and showed blank covers). The state-card tests moved from the
no-path (`fullyQualifiedName`) capture shape to EXPLICIT paths
(`roborazzi/state-error.png` et al.) in the same round: the first record
run proved no-path captures write to `build/outputs/roborazzi/` (a
gitignored dir --- baselines there could never be committed), while
explicit paths land in the module's committable `roborazzi/` dir. The
naming-strategy system property stays for any future no-path capture;
the three state-card baselines are now path-pinned like the README suite.
