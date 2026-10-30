# Design Tokens & Theme (ui/theme)

**Status:** Active (roadmap Phase 2, issue #11) · **Scope:** apps/gallery visual token system · **Governed by:** ADR-0009 (Compose + Material 3 + dynamic color), ADR-0008 (upstream *designs only* — no assets), ADR-0005→0009 Mi-inspired design direction · **Companion doc:** [screen-states.md](screen-states.md) (the state components this system feeds)

## 1. What this is — and what it is not

- **Code:** `apps/gallery/src/main/kotlin/io/github/dukeoftoad/gallery/ui/theme/DesignTokens.kt`
  (non-color tokens: spacing, shape, elevation, motion, touch target, state
  sizing, type roles) and `Theme.kt` (fallback color palettes + theme wiring).
- **Inspiration, not assets:** the calm, chrome-light, content-forward direction is
  informed by Mi Gallery's *interaction design* only. No Xiaomi (or other upstream)
  fonts, icons, artwork, typefaces, names, or branding enter the tree (ADR-0008;
  issue #11 constraint). The type system ships no font family at all.
- **Invariants are tested:** `DesignTokensTest` (plain JVM JUnit4, no emulator) pins
  the 48dp touch floor, the strictly ascending spacing/shape/elevation scales, the
  radii wiring, the motion durations, and the explicitness of all 15 type roles.
  If a token changes, the frozen tables in §2 change with it — or the change reverts.

## 2. Token tables (frozen)

### 2.1 Spacing — `ToadSpacing`

| Token | Value | Intended usage |
|---|---|---|
| `xs` | 2 dp | hairline gutters between media-grid cells |
| `sm` | 4 dp | tight padding inside small components (thumbnail overlays) |
| `md` | 8 dp | default gap between related elements |
| `lg` | 12 dp | dense group padding |
| `xl` | 16 dp | screen margin / card padding default |
| `xxl` | 24 dp | separation between sections and action clusters |
| `xxxl` | 32 dp | full-screen state-column page padding |

Seven steps on a 4dp base: **2 / 4 / 8 / 12 / 16 / 24 / 32**. The scale is the whole
set — screens pick the nearest step rather than inventing literals.

### 2.2 Shape — `ToadShapes`

| Token | Value | Intended usage |
|---|---|---|
| `extraSmall` | 4 dp | chips, badges, tiny overlays |
| `small` | **8 dp** | small controls (buttons, text fields) |
| `medium` | **12 dp** | cards, menus, dialogs |
| `large` | **16 dp** | large cards, sheet tops, media cards |
| `extraLarge` | 28 dp | extra-large surfaces (full-width sheets) |

`ToadShapes.material` builds the Material 3 `Shapes` from these radii and is passed
to `MaterialTheme.shapes` by `ToadGalleryTheme`, so no component falls back to
library-default radii (small/medium/large match today's M3 defaults; pinning them
prevents silent drift across material3 upgrades).

### 2.3 Elevation — `ToadElevation`

| Token | Value | Intended usage |
|---|---|---|
| `level0` | 0 dp | flat surfaces (screens, state columns) |
| `level1` | 3 dp | raised chips, small FABs |
| `level2` | 6 dp | navigation bars, cards over imagery |
| `level3` | 8 dp | dialogs, floating sheets |

Material 3 levels 0–3. Dark-theme depth comes primarily from *tonal* elevation
(`surfaceTint`, pinned to the primary in both fallback schemes).

### 2.4 Motion — `ToadMotion`

| Token | Value | Intended usage |
|---|---|---|
| `fast` | 150 ms | press feedback, quick fades |
| `standard` | 250 ms | state swaps, container transforms |

Adoption status: **frozen now, consumed later**. The state screens shipped in this
slice are static by design; the two durations are defined and test-pinned so the
first animated slice (e.g. viewer transitions, refresh spinners) adopts one tempo.

### 2.5 Touch target — `ToadTouchTarget`

| Token | Value | Rule |
|---|---|---|
| `minimum` | 48 dp | every interactive affordance must be at least 48 dp (Material guidance; WCAG 2.5.5/2.5.8 alignment) |

Enforcement is systemic, not per-call-site: `ToadGalleryTheme` provides
`LocalMinimumInteractiveComponentSize = ToadTouchTarget.minimum`, and every
Material 3 interactive component (buttons, nav items, toggles) keeps a ≥48 dp
touch area *without growing its visual bounds*. Custom low-level affordances
(e.g. future grid gesture targets) must apply `Modifier.minimumInteractiveComponentSize()`
themselves. The JVM test fails the build if the constant ever drops below 48.

### 2.6 State-component sizing — `ToadSizing`

| Token | Value | Contract |
|---|---|---|
| `stateIcon` | 72 dp | decorative state glyph — frozen by [screen-states.md](screen-states.md) §1 |
| `stateProgress` | 48 dp | full-screen loading indicator; aligned to the touch-target grid so it cannot drift with material3 default changes |

### 2.7 Typography — `ToadTypography`

The Material 3 baseline **pinned explicitly** — all fifteen roles state size, line
height, letter spacing and weight instead of inheriting library defaults. The
values equal today's M3 baseline (zero visual change; they are frozen so they
cannot silently drift tomorrow). Passed to `MaterialTheme.typography` by
`ToadGalleryTheme`.

| Role | Size / line height | Weight | Tracking | Used by the app today |
|---|---|---|---|---|
| displayLarge | 57 / 64 | Normal | 0 | — |
| displayMedium | 45 / 52 | Normal | 0 | — |
| displaySmall | 36 / 44 | Normal | 0 | — |
| headlineLarge | 32 / 40 | Normal | 0 | — |
| headlineMedium | 28 / 36 | Normal | 0 | — |
| headlineSmall | 24 / 32 | Normal | 0 | state-column titles |
| titleLarge | 22 / 28 | Normal | 0 | top bars |
| titleMedium | 16 / 24 | Medium | 0.15 | list items |
| titleSmall | 14 / 20 | Medium | 0.1 | list sub-items |
| bodyLarge | 16 / 24 | Normal | 0.5 | long copy |
| bodyMedium | 14 / 20 | Normal | 0.25 | state-column body |
| bodySmall | 12 / 16 | Normal | 0.4 | supporting text |
| labelLarge | 14 / 20 | Medium | 0.1 | buttons |
| labelMedium | 12 / 16 | Medium | 0.5 | badges |
| labelSmall | 11 / 16 | Medium | 0.5 | `state_shell_badge` footer |

All values in sp. **No font family is bundled** — the platform default typeface is
used; shipping a custom typeface would mean shipping binary assets, and nothing
proprietary is allowed into the tree (the Mi typefaces are NOT used).

## 3. Fallback color palettes (Theme.kt)

Dynamic color (Material You) wins on Android 12+ per ADR-0009. The explicit
schemes below are the deterministic baseline for API 26–31 and any device with
dynamic color disabled — and the accessible reference even where dynamic color
overrides them.

**Rationale — calm warm grays, one restrained accent (MI-inspired interaction
direction, no upstream assets):**

- All neutrals sit on a **warm-gray** thread (slight yellow-red bias — light
  background `#FCFBF7` "paper", dark background `#151512` "warm near-black").
  Chrome recedes; the photos carry the color.
- **One accent family** — the toad-green brand hue (seed `#3E9B4F`) — does all
  expressive work. Secondary/tertiary are desaturated in-family greens, not new
  hues. Error stays on the M3 baseline reds.
- **The seed is not the light primary.** `#3E9B4F` pairs with white at only
  3.5:1 — below the 4.5:1 WCAG AA bar for normal text (button labels are 14sp
  Medium). The light primary is the seed deepened to `#2E7A3C` (5.3:1 with
  white). Dark theme mirrors this with a tone-80 `#9CD8A2` primary.
- `surfaceTint` is pinned to the primary in both schemes so tonal elevation
  never flashes the stock Material purple.

**Light scheme** (fallback on non-dynamic-color devices):

| Role | Hex | Role | Hex |
|---|---|---|---|
| primary | `#2E7A3C` | onPrimary | `#FFFFFF` |
| primaryContainer | `#EAF6EC` | onPrimaryContainer | `#00390F` |
| secondary | `#51634F` | onSecondary | `#FFFFFF` |
| secondaryContainer | `#D4E8D0` | onSecondaryContainer | `#0F1F10` |
| tertiary | `#4E7355` | onTertiary | `#FFFFFF` |
| tertiaryContainer | `#CCEBCB` | onTertiaryContainer | `#0A2010` |
| background / surface | `#FCFBF7` | onBackground / onSurface | `#1D1C17` |
| surfaceVariant | `#EDEAE2` | onSurfaceVariant | `#48473F` |
| surfaceTint | `#2E7A3C` | inversePrimary | `#9CD8A2` |
| inverseSurface | `#32312B` | inverseOnSurface | `#F5F3EA` |
| outline | `#78766C` | outlineVariant | `#C8C5BA` |
| error | `#B3261E` | onError / onErrorContainer | `#FFFFFF` / `#410E0B` |
| errorContainer | `#F9DEDC` | scrim | `#000000` |

**Dark scheme**:

| Role | Hex | Role | Hex |
|---|---|---|---|
| primary | `#9CD8A2` | onPrimary | `#00390F` |
| primaryContainer | `#14522A` | onPrimaryContainer | `#EAF6EC` |
| secondary | `#B9CCB3` | onSecondary | `#253424` |
| secondaryContainer | `#3B4B39` | onSecondaryContainer | `#D4E8D0` |
| tertiary | `#B3CCB4` | onTertiary | `#1F3524` |
| tertiaryContainer | `#35543A` | onTertiaryContainer | `#CCEBCB` |
| background / surface | `#151512` | onBackground / onSurface | `#E5E2D8` |
| surfaceVariant | `#3A3830` | onSurfaceVariant | `#C6C2B6` |
| surfaceTint | `#9CD8A2` | inversePrimary | `#2E7A3C` |
| inverseSurface | `#E5E2D8` | inverseOnSurface | `#1D1C17` |
| outline | `#918F85` | outlineVariant | `#46453F` |
| error | `#F2B8B5` | onError / onErrorContainer | `#601C10` / `#F9DEDC` |
| errorContainer | `#8C1D18` | scrim | `#000000` |

### 3.1 Contrast notes — WCAG AA intent

Ratios computed with the WCAG relative-luminance formula (rounded; re-verify
with a contrast tool at review). Intent: **AA everywhere** — ≥4.5:1 for normal
text, ≥3:1 for large text and UI components; most pairs clear AAA (≥7:1).

Light theme:

| Pair | Ratio | Verdict |
|---|---|---|
| onPrimary `#FFFFFF` on primary `#2E7A3C` (button labels) | 5.3:1 | AA ✓ |
| primary as accent on background (icons/text) | 5.1:1 | AA ✓ |
| onSurface on background (body copy) | 16.5:1 | AAA |
| onSurfaceVariant on background (state body) | 9.0:1 | AAA |
| onSurfaceVariant on surfaceVariant | 7.8:1 | AAA |
| onPrimaryContainer on primaryContainer | 11.9:1 | AAA |
| error on background | 6.3:1 | AA ✓ |
| onSecondary `#FFFFFF` on secondary / onTertiary on tertiary | 6.5:1 / 5.4:1 | AA ✓ |

Dark theme:

| Pair | Ratio | Verdict |
|---|---|---|
| onPrimary `#00390F` on primary `#9CD8A2` (button labels) | 8.0:1 | AAA |
| primary as accent on background (icons/text) | 11.1:1 | AAA |
| onSurface on background (body copy) | 14.1:1 | AAA |
| onSurfaceVariant on background (state body) | 10.3:1 | AAA |
| onSurfaceVariant on surfaceVariant | 6.6:1 | AA ✓ |
| onPrimaryContainer on primaryContainer | 8.3:1 | AAA |
| error on background | 10.7:1 | AAA |
| onSecondary on secondary / onTertiary on tertiary | 7.7:1 | AAA |

Known sub-AA pairs (deliberate, non-text usage): `outline` on background is 4.4:1
(light) / 5.6:1 (dark) — outlines are decorative borders, not text. Dynamic-color
schemes on API 31+ are system-generated and outside our control.

## 4. Edge-to-edge (MainActivity)

- `enableEdgeToEdge()` runs in `onCreate` **before** `setContent`: the first frame
  already draws behind **transparent system bars** — no scrim flash, no
  post-attach relayout (**no layout jumps**). It also transparents the navigation
  bar, disables nav-bar contrast enforcement on API 29+, and stops
  `fitSystemWindows`-style fitting.
- **Single inset-consumption point:** the Material 3 `Scaffold` in
  `ui/ToadGalleryApp.kt` turns `contentWindowInsets` into `innerPadding` for the
  `NavHost`. Screens and the four full-screen states (permission, loading, error,
  unsupported) render inside that inset-paid geometry — the state components'
  centering (`Arrangement.Center` in a fill-max-size column) is measured against
  inset-paid constraints, so they never pad themselves against the status bar and
  never shift when bars appear/hide (rotation, keyboard, gesture-nav hide).
- Album detail's top bar wraps its `LoadingState` in an inset-padded `Box` at the
  call site (per [screen-states.md](screen-states.md)) — the one sanctioned
  place where a screen pays insets itself, because it draws its own top bar.
- Edge-to-edge + `Scaffold` insets means light/dark status-bar icon contrast is
  handled by `enableEdgeToEdge()`'s auto styles; no manual scrim is layered under
  the status bar for the state screens.

## 5. Adoption status — what uses tokens today

- **Theme wiring (app-wide):** typography, shapes, and the 48dp touch-target
  floor apply to every screen through `ToadGalleryTheme`.
- **`MediaPermissionState`, `ErrorState`, `LoadingState`, `UnsupportedFileState`:**
  fully migrated — every dp literal replaced with `ToadSpacing`/`ToadSizing`
  tokens, type roles from the theme, visually identical to the frozen
  screen-state layout (72dp icon, `headlineSmall`/`bodyMedium`, 48dp-floor
  buttons). Signatures untouched (screen-states.md freeze).

## 6. Follow-up list — NOT yet migrated (stay as-is this slice)

These deliberately keep their raw literals in this slice (frozen for concurrent
work / reviewability); each adopts the tokens in its own next slice, following
the state-component pattern:

| File | Outstanding raw literals / work |
|---|---|
| `ui/ToadGalleryApp.kt` | `StateScreen` primitive (32/72/16/8/24dp) + shell paddings; migrate `StateScreen` to tokens (its freeze allows internal-only changes) when its slice lands |
| `ui/photos/PhotosScreen.kt` | timeline header 20/24dp, sync pill 12dp, inline loading/error/empty branches (screen-states.md §5 already tracks their `ErrorState`/`LoadingState` adoption) |
| `ui/albums/AlbumsScreen.kt` | 8dp grid gaps (`ToadSpacing.md`), `RoundedCornerShape(16.dp)` → `ToadShapes.large`, 4dp label padding |
| `ui/settings/SettingsScreen.kt` | 16/24/8/4dp paddings and spacers |
| `ui/media/MediaGrid.kt` | 2dp gutters → `ToadSpacing.xs` (owned by a concurrent slice this round) |
| `ui/media/MediaPagingGrid.kt` | 2dp gutters → `ToadSpacing.xs` (owned by a concurrent slice this round) |
| `ui/media/MediaThumb.kt` | 6dp indicator corner, 4/2dp overlay paddings, raw `Color.Black` scrim → `surfaceVariant`/`onSurfaceVariant` pair (owned by a concurrent slice this round) |
| `ui/albums/AlbumDetailScreen.kt`, `ui/picker/PickedPhotosScreen.kt` | no raw dp literals today; adopt explicit `ToadElevation`/`ToadShapes` for their top bars when next touched |
| `res/values` XML theme lineage | legacy View-era remnants — deleted at the end of the Compose migration (ADR-0009 item 6), not tokenized |
| `ToadMotion` consumers | none yet — first animated component wires the durations |

`MediaPermissions.kt`, `DurationFormats.kt`, ViewModels and data classes need no
migration (no UI literals). This document is the follow-up tracker; when a file
migrates, strike its row here.

`docs/architecture/README.md` index entry: next docs-touching slice (same
convention as screen-states.md).
