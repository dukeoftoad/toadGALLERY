# Compose Migration Tracker

**Status:** Shell migration complete (PR #16); privacy gate + suite landed (ADR-0014, issue #20) · **Date:** 2026-10-29 · **Governed by:** ADR-0009

This document tracks the migration of toadGALLERY's UI from the
View-based + `org.fossify:commons` shell (built under the now-superseded
ADR-0005) to the canonical Jetpack Compose + Material 3 stack chosen by
ADR-0009.

The migration is **incremental**: the existing View-based shell keeps
building and running until each screen is replaced. New screens ship in
Compose from day one. No big-bang rewrite.

## 1. Migration strategy

1. **Add Compose toolchain (additive, Phase 2 first task).** Add the
   Compose BOM, Material 3, Activity Compose, and Compose View interop
   to `apps/gallery/build.gradle.kts` and the Kotlin Compose compiler
   plugin to the root `build.gradle.kts`. The existing View-based shell
   still builds.
2. **Replace `org.fossify:commons`** with a self-contained Compose + Material 3
   equivalent (own theme, own color system, own base activity using
   `ComponentActivity` + `setContent { ... }`). This is the breaking
   change that ends the View-based shell's life.
3. **Replace the shell's `MainActivity` + `PlaceholderFragment`** with a
   Compose-native `setContent { ... }` activity hosting a `NavHost` for
   the four top-level destinations (Photos, Albums, Search, Settings).
4. **Migrate downstream screens** as each Phase 3+ feature lands. No
   big-bang rewrite.
5. **Delete Material 2 theme files** (`styles.xml`, `colors.xml`) once
   all View-based screens are gone.

## 2. Screen migration tracker

| Screen | Current (Phase 2 alpha) | Target | Scheduled phase | Status |
|---|---|---|---|---|
| `MainActivity` | ~~View-based + `org.fossify:commons` BaseSimpleActivity~~ → rewritten | `ComponentActivity` + `setContent { toadGalleryApp() }` | 2 | **DONE (PR #16)** |
| `PlaceholderFragment` (Photos) | ~~View-based Fragment~~ → deleted | Compose `StateScreen` composable | 2 | **DONE (PR #16)** |
| `PlaceholderFragment` (Albums) | ~~View-based Fragment~~ → deleted | Compose `StateScreen` composable | 2 | **DONE (PR #16)** |
| `PlaceholderFragment` (Search) | ~~View-based Fragment~~ → deleted | Compose `StateScreen` composable | 2 | **DONE (PR #16)** |
| `PlaceholderFragment` (Settings) | ~~View-based Fragment~~ → deleted | Compose `StateScreen` composable | 2 | **DONE (PR #16)** |
| Timeline / grid browse | (none in alpha shell) | Compose `LazyVerticalGrid` | 3 | not started |
| Image viewer | (none) | Compose + custom pan/zoom | 3 | not started |
| Video player | (none) | Compose + `androidx.media3` UI | 3 | not started |
| Recycle bin | (none) | Compose | 3 | not started |
| Hidden vault | (none) | Compose + BiometricPrompt | 3 | not started |
| Metadata Details | (none) | Compose | 4 | not started |
| On-device map | (none) | Compose Canvas + offline tile renderer | 4 | not started |
| Moments timeline | (none) | Compose | 5 | not started |
| Stories | (none) | Compose + media3 | 5 | not started |
| Auto-albums | (none) | Compose | 5 | not started |
| Smart search | (none) | Compose | 5 | not started |
| Collage maker | (none) | Compose Canvas | 5 | not started |
| Slideshow | (none) | Compose + media3 | 5 | not started |
| Editor shell | (none) | Compose (in `:apps/edit-extension`) | 6 | not started |
| Editor: crop/adjust UI | (none) | Compose + custom SurfaceView for preview via `AndroidView` | 6 | not started |
| Editor: filters/annotations | (none) | Compose | 7 | not started |
| Editor: AI features | (none) | Compose | 8 | not started |
| Video editor | (none) | Compose + media3 Transformer | 9 | not started |

## 3. Dependency changes

| Dependency | Before migration | Now | Notes |
|---|---|---|---|
| `org.fossify:commons` | present | **REMOVED (PR #16)** | ADR-0008 satisfied. Replaced by `ComponentActivity` + Material 3 theme. |
| `androidx.appcompat` | present | **REMOVED (PR #16)** | Not needed by `ComponentActivity`. |
| `androidx.fragment` | present | **REMOVED (PR #16)** | No Fragments remain. |
| `androidx.core` | present | **REMOVED (PR #16)** | Transitive via activity-compose; re-add when directly used. |
| `androidx.constraintlayout` | present | **REMOVED (PR #16)** | Not needed in Compose. |
| `com.google.android.material:material` | present | keep (1.13.0) | Provides `Theme.Material3.DayNight.NoActionBar` for the manifest. |
| `androidx.compose:compose-bom` | absent | **ADDED (PR #16)** | ADR-0009. |
| `androidx.compose.material3:material3` | absent | **ADDED (PR #16)** | ADR-0009. |
| `androidx.activity:activity-compose` | absent | **ADDED (PR #16)** | ADR-0009. |
| `androidx.navigation:navigation-compose` | absent | **ADDED (PR #16)** | For the `NavHost`. |
| `io.coil-kt:coil-compose` | absent | **ADDED (PR #16)** | Compose-first image loader for Phase 3. |
| `androidx.exifinterface:exifinterface` | absent | **ADDED (PR #16)** | ADR-0008: replaces the planned Aves `exifinterface` fork port. | |

## 4. File deletion schedule

| File | When deleted | Reason |
|---|---|---|
| `PlaceholderFragment.kt` | **PR #16** | Replaced by Compose `StateScreen` composables. |
| `MainActivity.kt` | **PR #16** (rewritten in place) | Compose `setContent` activity; no longer extends `BaseSimpleActivity`. |
| `res/layout/activity_main.xml` | **PR #16** | View-based layout no longer used. |
| `res/layout/fragment_state.xml` | **PR #16** | Replaced by `StateScreen` composable. |
| `res/menu/bottom_nav.xml` | **PR #16** | Compose `NavigationBar` replaces it. |
| `res/values/styles.xml` | **PR #16** | Manifest theme now references `Theme.Material3.DayNight.NoActionBar` directly. |
| `res/values/dimens.xml` | **PR #16** | Spacing now lives in Compose code; token system expands in issue #11. |
| `res/values/colors.xml` | keep (trimmed in PR #16) | Brand tokens remain as the XML mirror of `ui/theme/Theme.kt` until issue #11 consolidates. | |

## 5. Open questions

Tracked in ADR-0009 §"Open questions for Phase 2" and in the v2.2
feature matrix's "Open audit questions for Phase 2."
