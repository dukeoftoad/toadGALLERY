# The APK matrix --- four gallery flavors + the edit app

**Status:** LANDED (2026-11-05, the r46 round, versionCode 38). This doc
records the maintainer-demanded four-way gallery APK matrix: one codebase,
four gallery builds over a single Gradle flavor dimension ("engine"), plus
the separate edit app. The demand that triggered the round, verbatim: a
BASIC APK with the engine library and ALL engine UI removed, plus
engine-library-included APKs that install SEPARATELY on distinct
applicationIds, like the separate edit app. Cross-references:
`apps/gallery/build.gradle.kts` (the flavor block + the -PwithEngine
packaging gate, r22/ADR-0020), `buildshape/BuildShape.kt` (the runtime
source of truth), `.github/workflows/release.yml` (the two-invocation
build + the negative packaging gate).

---

## 1. The matrix

| flavor   | applicationId (release)                 | engine `.so` | face index | semantic index | who it is for |
|----------|------------------------------------------|--------------|------------|----------------|---------------|
| basic    | `io.github.dukeoftoad.gallery`           | NO           | no         | no             | The offline-first core gallery: timeline, albums, name search, metadata, maps, trash, vault. The canonical app id --- it updates in place over every previous toadGALLERY pre-release. |
| engine   | `io.github.dukeoftoad.gallery.engine`    | YES          | yes        | yes            | The full AI stack: People (face clustering) AND Magic Search + Things. Installs SEPARATELY from basic. |
| semantic | `io.github.dukeoftoad.gallery.semantic`  | YES          | no         | yes            | The semantic half only: Magic Search + Things, no People. |
| face     | `io.github.dukeoftoad.gallery.face`      | YES          | yes        | no             | The face half only: People, no Magic Search / Things. |
| edit     | `io.github.dukeoftoad.gallery.edit`      | (no engine)  | ---        | ---            | The companion editor, unchanged; shipped since r43 and listed here because the matrix answer is "five APKs, all coexisting". |

- All four gallery flavors share **versionCode 38 / versionName
  0.4.5-alpha** (one `defaultConfig`; the flavors change ids and
  BuildConfig, not the version line).
- The debug buildType's `.debug` suffix composes with the flavor suffixes
  as usual (`io.github.dukeoftoad.gallery.engine.debug`, ...).
- Launcher labels ride per-flavor res source sets
  (`src/<flavor>/res/values/strings.xml` overriding
  `app_launcher_name`). As landed in r46: toadGALLERY / toadGALLERY
  Engine / toadGALLERY Semantic / toadGALLERY Face. Since the r47
  round the ENGINE flavor's label is the plain "toadGALLERY" too ---
  the `.engine` applicationIdSuffix already makes it a separate
  install, so the shared visible name is unambiguous (semantic /
  face keep their suffixed labels; they can be unified the same way
  on request). All five apps still coexist legibly on one launcher.
  Icons are shared.
- The flavor flags are per-flavor `buildConfigField`s
  (`WITH_ENGINE` / `INDEX_FACE` / `INDEX_SEMANTIC`) consumed ONLY through
  `BuildShape` (`buildshape/BuildShape.kt`), whose derived flags are
  conjunctive (`index = withEngine && INDEX_*`); the algebra is pinned
  green under every flavor by `BuildShapeTest`.

## 2. What "removed from basic" means

The basic APK carries no engine library AND no engine UI surface. The UI
half is gated by `BuildShape` in place (nothing deleted, everything
JVM-tested stays):

- Albums tab: the People section renders only when `BuildShape.faceIndex`,
  the Things section only when `BuildShape.semanticIndex`.
- Search tab: the Name/Content mode row renders only with the semantic
  index; without it the screen is the plain Name pipeline (no Content
  chip, no mode row), and the Magic Search state surface is gated too.
- Settings: the whole AI engine section (consent rows included) renders
  only with the engine; inside it, the People consent row + FACE
  capability/download/delete/self-test rows + face queue card ride the
  face gate, and the Magic Search / Things consent rows + semantic model
  card + semantic queue card ride the semantic gate. The
  `AiEngineViewModel` start/cancel/reset paths additionally refuse to run
  without their flag (defense in depth).
- Routes: `people` / `person/{clusterId}` / `things/{collectionId}`
  render a small centered unavailable card
  (`R.string.build_feature_unavailable`, tag
  `build_feature_unavailable`) instead of constructing their ViewModels
  when the build lacks the owning index. Route entries stay --- only the
  bodies are gated.

## 3. Build recipes (exact gradle commands)

From the repo root (JDK 17 + Android SDK; no Rust toolchain needed unless
`-PwithEngine` is passed):

```bash
# The basic APK (debug / release) --- property-less, engine-free:
./gradlew :apps:gallery:assembleBasicDebug
./gradlew :apps:gallery:assembleBasicRelease

# The engine family --- ALWAYS with -PwithEngine (that property alone
# builds + stages libtoad_ml_jni.so and libonnxruntime.so):
./gradlew :apps:gallery:assembleEngineDebug   -PwithEngine
./gradlew :apps:gallery:assembleSemanticDebug -PwithEngine
./gradlew :apps:gallery:assembleFaceDebug     -PwithEngine
# (and assembleEngineRelease / assembleSemanticRelease / assembleFaceRelease)

# JVM tests run per flavor (no native code involved):
./gradlew :apps:gallery:testBasicDebugUnitTest :apps:gallery:testEngineDebugUnitTest

# Lint is flavor-qualified too:
./gradlew :apps:gallery:lintBasicDebug
```

Outputs: `apps/gallery/build/outputs/apk/<flavor>/<type>/`, R8 mappings at
`apps/gallery/build/outputs/mapping/<flavor><Type>/mapping.txt`.

## 4. The `.so` packaging rule

**The basic APK NEVER carries `libtoad_ml_jni.so` or `libonnxruntime.so`.
The engine / semantic / face APKs ALWAYS carry both (arm64-v8a).**

How the rule holds:

- The engine native libraries exist ONLY behind the `-PwithEngine`
  property gate in `apps/gallery/build.gradle.kts` (r22, ADR-0020): no
  property, no jniLibs srcDir, no task, no libraries. The gate is pinned
  by `EnginePackagingPolicyTest`.
- `-PwithEngine` is a GLOBAL property: it would register the engine
  jniLibs srcDir for every variant in its invocation. That is why
  `release.yml` builds in TWO invocations --- basic + edit
  property-less, the engine family with the property --- and why nothing
  else ever mixes the basic flavor into a `-PwithEngine` invocation.
- The release pipeline ENFORCES both directions with `unzip -l` gates:
  the per-build positive gate is routine (ci.yml engine-android,
  apk-build-engine.yml, engine-apk.yml, modelpack-apk.yml); r46 adds the
  NEGATIVE gate on the basic APK (absent libraries, fail the release) in
  `release.yml` --- the maintainer's exact complaint ("current apk has
  not included engine library but showing in app") made impossible to
  ship unnoticed in either direction.

## 5. Install-separately note

The four gallery flavors + the edit app are FIVE independent Android
packages (distinct applicationIds). Any combination coexists on one
device, side by side on the launcher under their own names, and each
updates in place over its own previous installs (one shared ADR-0018
release key). Basic never upgrades into an engine build or vice versa:
moving between matrix rows means installing a different app, exactly the
edit app's separate-app model.
