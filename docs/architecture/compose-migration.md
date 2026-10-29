# Compose Migration Tracker

**Status:** Initial draft (Phase 2 setup) · **Date:** 2026-10-29 · **Governed by:** ADR-0009

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
| `MainActivity` | View-based + `org.fossify:commons` BaseSimpleActivity | `ComponentActivity` + `setContent { toadGalleryApp() }` | 2 | pending |
| `PlaceholderFragment` (Photos) | View-based Fragment + ViewBinding | Compose `PhotosScreen` composable | 2 | pending |
| `PlaceholderFragment` (Albums) | View-based Fragment + ViewBinding | Compose `AlbumsScreen` composable | 2 | pending |
| `PlaceholderFragment` (Search) | View-based Fragment + ViewBinding | Compose `SearchScreen` composable | 2 | pending |
| `PlaceholderFragment` (Settings) | View-based Fragment + ViewBinding | Compose `SettingsScreen` composable (privacy section first, as Compose smoke test) | 2 | pending |
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

| Dependency | Current (alpha shell) | Target | Notes |
|---|---|---|---|
| `org.fossify:commons` | present | **removed** | ADR-0008. Replace with AndroidX equivalents. |
| `androidx.appcompat` | present | keep (transitively required by some AndroidX libs) | OK. |
| `androidx.fragment` | present | keep until last Fragment-hosted Compose screen migrates; then evaluate removal | OK. |
| `androidx.core` | present | keep | OK. |
| `androidx.constraintlayout` | present | **removed** | Not needed in Compose. |
| `com.google.android.material:material` | present | keep at version that ships Material 3 | Needed for legacy Material 2 views during migration; removed at end. |
| `androidx.compose:compose-bom` | absent | **add** | ADR-0009. |
| `androidx.compose.material3:material3` | absent | **add** | ADR-0009. |
| `androidx.activity:activity-compose` | absent | **add** | ADR-0009. |
| `androidx.navigation:navigation-compose` | absent | **add** | For the `NavHost`. |
| `io.coil-kt:coil-compose` | absent | **add (candidate)** | Compose-first image loader; alternative to Glide. Decision in Phase 2. |
| `androidx.exifinterface:exifinterface` | absent | **add** | ADR-0008. Replaces the planned Aves `exifinterface` fork port. |

## 4. File deletion schedule

| File | When deleted | Reason |
|---|---|---|
| `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/PlaceholderFragment.kt` | Phase 2 (after Compose shell lands) | Replaced by Compose screen composables. |
| `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/MainActivity.kt` | Phase 2 (rewritten in place) | Replaced by Compose `setContent` activity. |
| `apps/gallery/src/main/res/layout/activity_main.xml` | Phase 2 (after Compose shell lands) | View-based layout no longer used. |
| `apps/gallery/src/main/res/layout/fragment_state.xml` | Phase 2 | Same. |
| `apps/gallery/src/main/res/menu/bottom_nav.xml` | Phase 2 | Compose `NavigationBar` replaces it. |
| `apps/gallery/src/main/res/values/styles.xml` | End of migration (Phase 3+) | Material 2 theme entries. |
| `apps/gallery/src/main/res/values/colors.xml` | End of migration (Phase 3+) | Material 2 color entries. |
| `apps/gallery/src/main/res/values/dimens.xml` | End of migration (Phase 3+) | Replaced by Compose design tokens. |

## 5. Open questions

Tracked in ADR-0009 §"Open questions for Phase 2" and in the v2.2
feature matrix's "Open audit questions for Phase 2."
