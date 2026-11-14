# toadGALLERY Edit

The editor extension of **toadGALLERY**: a private, offline-first photo
and video editor for Android, built with Kotlin + Jetpack Compose. It
ships as a separate installable APK (`com.toadgallery.editor`) that the
main Gallery app opens through the versioned editor contract — and it
works standalone too.

- **One network use, disclosed.** The AI models are not bundled in the APK;
  they download on demand into app-private storage when you first use an AI
  tool — after an explicit confirmation, from the pinned GitHub release URLs
  only (`EditAiManifest.DOWNLOAD_BASE`), SHA-256-verified, with no analytics
  or telemetry. The merged manifest may declare exactly
  `android.permission.INTERNET` and nothing else — a build-time gate fails
  the build on any other permission, and the release workflow re-checks the
  final APK's permission set.
- **Non-destructive pipeline.** Edits are recipes over the original;
  export defaults to saving a copy.

## Modules

| Module | Purpose |
|---|---|
| `editor/app` | Standalone Edit APK shell: applicationId, signing, permission gate |
| `editor/core` | The editor library: photo chassis, tune rail, video editor, export pipeline, AI stages |
| `shared/editor-contract` | Pure-JVM Gallery ↔ Edit contract (identity, version negotiation, request/result parsing) |
| `shared/video-edit` | Pure-JVM video export plans (trim, speed, mute, frames, animation) |

## Build

```bash
./gradlew :editor:app:assembleDebug          # debug APK
./gradlew :editor:app:assembleRelease        # R8 release; signs only with TG_* env vars
./gradlew :editor:app:gateDebugPermissionSet     # privacy gate
./gradlew :editor:core:recordRoborazziDebug  # screenshot baselines
./gradlew :editor:core:testDebugUnitTest     # unit tests
```

Toolchain: JDK 17, Android SDK 36, AGP 9.2.1, Kotlin 2.3.10 (pinned in
`gradle/libs.versions.toml`).

## Layout

```text
editor/core/src/main/kotlin/com/toadgallery/editor/   editor sources
editor/core/src/main/assets/luts/                      bundled .cube filters
editor/core/src/main/kotlin/.../EditAiModelStore.kt    on-demand model downloader
editor/core/src/test/                                  unit + Roborazzi tests
shared/                                                pure-JVM contract + plans
docs/                                                  architecture, ADRs, phase records
```

GPL-3.0 — see `LICENSE`.
