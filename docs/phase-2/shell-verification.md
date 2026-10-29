# Phase 2 Shell Build Record — `apps/gallery` 0.1.0-alpha

**Built:** 2026-10-29 (UTC) · **Result: BUILD SUCCESSFUL** (`:apps:gallery:assembleDebug`, 28s incremental)

## Artifact

| Field | Value |
|---|---|
| APK | `gallery-debug.apk` — **30 MB** |
| SHA-256 | `6d98500dec043b36…` (prefix; archived as `toadgallery-0.1.0-alpha-debug.apk` in session artifacts) |
| Package (debug) | `io.github.dukeoftoad.gallery.debug` (release id: `io.github.dukeoftoad.gallery` per ADR-0005) |
| Version | `0.1.0-alpha` (versionCode 1) |
| compileSdk / targetSdk | 36 / 36 · minSdk 26 (ADR-0005 / Phase 0 sign-off) |
| Label | `toadGALLERY` |
| Kotlin / AGP / Gradle | 2.3.10 (AGP built-in) / 9.2.1 / 9.5.1 — identical lineage to the verified Fossify baseline |

## What the shell contains (roadmap Phase 2 first slice)

- Four-destination bottom navigation: **Photos / Albums / Search / Settings** (ADR-0005)
- Reusable screen-state system seeded (empty-state copy per destination; the
  permission-denied state becomes live when Phase 3 media access arrives) — issue #13
- Theme inherits the Fossify commons lineage (**Material3-based**, verified from the
  commons 7.1.3 AAR) with toadGALLERY identity tokens (green primary) — issue #11 seed
- Adaptive launcher icon (own vector, no proprietary assets)
- **No browsing logic** — nothing is coupled to unfinished internals (roadmap Phase 2
  exit criterion "no large feature implementation coupled to unfinished navigation")

## Permission posture (privacy gate — verified via `aapt2 dump badging`)

The shell requests **no dangerous permissions** (no storage, no network, no camera).
Two inherited entries come from the commons manifest and are inert for this app:

1. `org.fossify.android.permission.WRITE_GLOBAL_SETTINGS` — custom (Fossify inter-app
   settings sharing); signature-scoped by its declaration, not dangerous for this package.
2. `android.permission.USE_FINGERPRINT` — deprecated since API 28, normal protection level.

**Follow-up (Phase 2-3):** attempt to drop inherited manifest entries via manifest merger
`tools:node="remove"` once nothing in the used commons surface needs them — the same
technique Fossify uses to strip `ACCESS_NETWORK_STATE`/`CAMERA`.

## Honest limits of this verification

- **Assembled ≠ launched.** No emulator exists in the build container. The definition-of-done
  for the shell is on-device: launch, tab switching, day/night, edge-to-edge insets,
  TalkBack labels. Checklist below — check on a real device (Android 8+ and Android 16 ideal).
- [ ] Cold launch < 2s (reference hardware note: Phase 8 defines the target device)
- [ ] All 4 tabs navigate + preserve state on rotation
- [ ] Day/night themes switch correctly; edge-to-edge not obscured by the bottom bar
- [ ] Launcher icon + label correct ("toadGALLERY", green)
- [ ] TalkBack reaches all nav items and state texts

## Reproducing the build

Same recipe as the upstream baselines (`docs/phase-0/build-notes.md` §1): JDK 17+ (built with
Temurin 21), Android SDK (platforms;android-36, build-tools 36.0.0), then:

```bash
./gradlew :apps:gallery:assembleDebug --no-daemon
```

Notes for other environments: the project needs ≥4 GB RAM for Gradle+Kotlin (heap caps are
in `gradle.properties`); Jetifier is enabled (the commons lineage still ships one
pre-AndroidX support artifact — same as upstream Fossify Gallery).
