# toadGALLERY Edit

The editor extension of **toadGALLERY**: a private, offline-first photo
and video editor for Android, built with Kotlin + Jetpack Compose. It
ships as a separate installable APK (`com.toadgallery.editor`) that the
main Gallery app opens through the versioned editor contract — and it
works standalone too.

- **No network. Ever.** The merged manifest must not declare
  `android.permission.INTERNET` — a build-time gate fails the build if
  it does. All AI models ship inside the APK
  (`editor/core/src/main/assets/models/`).
- **Non-destructive pipeline.** Edits are recipes over the original;
  export defaults to saving a copy.

## Modules

| Module | Purpose |
|---|---|
| `editor/app` | Standalone Edit APK shell: applicationId, signing, INTERNET build gate |
| `editor/core` | The editor library: photo chassis, tune rail, video editor, export pipeline, AI stages |
| `shared/editor-contract` | Pure-JVM Gallery ↔ Edit contract (identity, version negotiation, request/result parsing) |
| `shared/video-edit` | Pure-JVM video export plans (trim, speed, mute, frames, animation) |

## Build

```bash
./gradlew :editor:app:assembleDebug          # debug APK
./gradlew :editor:app:assembleRelease        # R8 release; signs only with TG_* env vars
./gradlew :editor:app:gateDebugInternetPermission  # privacy gate
./gradlew :editor:core:recordRoborazziDebug  # screenshot baselines
./gradlew :editor:core:testDebugUnitTest     # unit tests
```

Toolchain: JDK 17, Android SDK 36, AGP 9.2.1, Kotlin 2.3.10 (pinned in
`gradle/libs.versions.toml`).

## Layout

```text
editor/core/src/main/kotlin/com/toadgallery/editor/   editor sources
editor/core/src/main/assets/models/                    bundled ONNX models
editor/core/src/main/assets/luts/                      bundled .cube filters
editor/core/src/test/                                  unit + Roborazzi tests
shared/                                                pure-JVM contract + plans
docs/                                                  architecture, ADRs, phase records
```

GPL-3.0 — see `LICENSE`.
