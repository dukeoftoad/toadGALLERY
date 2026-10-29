# Phase 2 Privacy Hardening Record — Build Gate + Test Suite (ADR-0014)

**Built & verified:** 2026-10-29 (UTC) · **App:** `apps/gallery` 0.2.1-alpha (versionCode 3) · **Issue:** #20 (Phase 2 exit criterion)

## What was built

The product-vision invariant — *no `INTERNET` permission in the core manifest, no
network access, no telemetry* — is no longer enforced by convention. It is enforced
by the build itself, in four independent layers (full rationale in
[ADR-0014](../integration-decisions.md)):

### Layer 1 — Gradle privacy gate (`privacyGate<Variant>` tasks)

Defined in `apps/gallery/build.gradle.kts`. Each variant's **merged manifest** (the
app manifest merged with every dependency contribution — the exact XML aapt2
packages) is audited before packaging:

- **Deny-list (absolute):** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE` — never allowed, any phase.
- **Allow-list (policy-declared per variant):** every declared permission must be in
  `expectedPermissionsByVariant` and vice versa. Phase 2: **empty** for `debug` and
  `release`. Phase 3 adds `READ_MEDIA_*` via roadmap review — never silently.
- **Cleartext ban:** `android:usesCleartextTraffic="true"` fails the build.
- **Raw-text scan:** deny tokens are also searched in the raw manifest text
  (ignoring `tools:node="remove"`), catching element forms the parser does not model.
- **Wiring:** gate tasks are dependencies of `assemble<Variant>` and `check`, and run
  as a **blocking** CI step (ADR-0013) — a red gate is a red build.
- **Report:** `apps/gallery/build/privacy/privacy-gate-<variant>.txt` (variant,
  declared permissions, allow-list, self-scoped allowances, verdict), uploaded as a
  CI artifact.

**One documented self-scoped allowance:** the manifest merger auto-injects
`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-protected,
app-scoped, required by androidx for `RECEIVER_NOT_EXPORTED` dynamic receivers). It
is auto-allowed but named in every gate report — the allowance is visible, never
silent. (First real catch of the gate: on its inaugural run it surfaced exactly this
permission, which was then reviewed and policy-allowed via this record.)

### Layer 2 — Privacy test suite (JVM unit tests, binding in CI)

`apps/gallery/src/test/kotlin/io/github/dukeoftoad/gallery/privacy/` — 15 tests:

| Class | Tests | Guards |
|---|---|---|
| `SourceManifestPrivacyTest` | 4 | Source manifest: deny-list, allow-list equality, cleartext, parseability |
| `BuildScriptPrivacyGateContractTest` | 5 | Build script: deny-list intact, gate registered on merged manifest, assemble/check wiring, explicit per-variant allow-lists, visible self-scoped allowance |
| `CiWorkflowPrivacyTest` | 4 | CI: gate runs for both variants, blocking (no `continue-on-error`), tests binding, reports uploaded |
| `SourceNetworkApiBanTest` | 2 | `src/main` may not reference HTTP clients (`HttpURLConnection`, `openConnection(`, OkHttp, Retrofit, Volley, Ktor client, raw sockets) or the `INTERNET` permission string |

Weakening any layer fails the suite; the layers deliberately duplicate the deny-list
so desync is loud rather than silent.

## Verification (this repository, 2026-10-29)

Environment: Temurin JDK 17.0.21, Android SDK `platforms;android-36` +
`build-tools;36.0.0`, Gradle 9.5.1 (project wrapper) — identical recipe to CI.

| Check | Command | Result |
|---|---|---|
| Gate (debug + release) | `./gradlew :apps:gallery:privacyGateDebug :apps:gallery:privacyGateRelease` | **PASS** both variants (declared: self-scoped dynamic-receiver permission only) |
| Build | `./gradlew :apps:gallery:assembleDebug` | **BUILD SUCCESSFUL** — gate runs before packaging (wired) |
| Tests | `./gradlew :apps:gallery:testDebugUnitTest` | **15/15 pass** |
| Lint | `./gradlew :apps:gallery:lintDebug` | **BUILD SUCCESSFUL** → now binding in CI (no `continue-on-error`) |
| **Negative test** | Inject `android.permission.INTERNET` into the source manifest, re-run gate | **BUILD FAILED** with three independent violations (deny-list declared · allow-list drift · raw-text scan); manifest restored, gate PASS again |

The negative test is the point: a network permission sneaking in through any path —
hand-written manifest, dependency manifest merge, or future refactor — now fails the
build before an APK can exist.

## CI wiring (ADR-0013 + ADR-0014)

The CI order is: SDK setup → **privacy gate (blocking, both variants)** →
assembleDebug → lint (binding) → unit tests (binding) → artifacts (APK, gate
reports, lint, tests). The privacy posture therefore cannot regress silently: any
regression is a red PR.

## Extending in later phases

- **Phase 3** (media access): add the `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` /
  `READ_MEDIA_VISUAL_USER_SELECTED` family to `expectedPermissionsByVariant` AND to
  `SourceManifestPrivacyTest.expected` in the same PR, with a roadmap-review note.
  Both layers must change together or the build fails.
- **Phase 8** (on-device AI, ADR-0010 §7): add the runtime zero-outbound-connections
  audit (network proxy around a device run). The lists defined here are the contract
  that audit verifies end-to-end.
- **Open question (recorded in ADR-0014):** `android:allowBackup="true"` → revisit as
  `false` + explicit `dataExtractionRules` in Phase 3, when the app first holds real
  user data.
