# Screen-State System (frozen)

**Status:** Frozen (roadmap Phase 2, issue #13) · **Scope:** every full-screen state in the gallery app · **Governed by:** ADR-0009 (Compose + Material 3), ADR-0006 (URI-first)

One reusable component per semantic state, defined before feature code lands.
Feature slices render these states (or extend this document) — never hand-roll
a screen state inline. Instrumented tests assert states through stable
`Modifier.testTag`s, decoupled from copy (issue #38.7).

## 1. The five states

| State | Component (frozen signature) | Call sites today | Tag |
|---|---|---|---|
| **Empty** | `StateScreen(iconRes, titleRes, bodyRes, actionRes?, onAction?)` — `ui/ToadGalleryApp.kt` (raw layout, frozen) | Search/Settings placeholders; Photos, Albums, AlbumDetail empty | wrapper tag (see §2) |
| **Loading** | `LoadingState(tag: String? = null)` — `ui/media/LoadingState.kt` | Albums (`albums_state_loading`), AlbumDetail (`album_detail_state_loading`) | per call site |
| **Denied** | `MediaPermissionState(onGrant: () -> Unit, tag: String? = null)` — `ui/media/MediaPermissionState.kt` | Photos, Albums | `state_permission_denied` (component default, `PERMISSION_DENIED_TAG`) |
| **Unsupported** | `UnsupportedFileState(fileName: String?, tag: String? = null)` — `ui/media/UnsupportedFileState.kt` | none yet (viewer/editor slices) | per future call site |
| **Error** | `ErrorState(titleRes, bodyRes, actionRes, onRetry, tag: String? = null)` — `ui/media/ErrorState.kt` | none yet (Photos migrates later) | per call site |

Loading/Denied/Unsupported/Error are semantic wrappers around one shared
layout, mirroring `StateScreen` token for token: 72dp decorative icon
(`onSurfaceVariant`), `headlineSmall` title, centered `bodyMedium` body,
optional `TextButton`, `state_shell_badge` footer. `StateScreen` remains the
raw layout primitive (frozen) and is still called directly where a state needs
a bespoke drawable (nav placeholders, Photos' empty).

**ErrorState design note (frozen):** self-contained rather than delegating
to `StateScreen`, whose `iconRes` parameter is required — the app's drawable
set has no generic error glyph and the frozen `ErrorState` signature carries
no icon, so delegation would hardcode e.g. `ic_nav_photos` into a *generic*
error (mislabeling non-Photos call sites). It renders `Icons.Filled.Warning`
(`material-icons-core`) instead, on a byte-for-byte mirror of `StateScreen`'s
layout, so a future internal swap stays call-site compatible.

## 2. Tag scheme (issue #38.7)

- Pattern: `<screen>_state_<state>` — e.g. `albums_state_loading`,
  `album_detail_state_empty`, `album_detail_state_error` (reserved).
- Component-owned intrinsic defaults apply only where mandated: the denial
  state always carries `state_permission_denied` (shared by Photos + Albums so
  one tag finds any denial; the `tag` param exists for per-screen overrides
  that #38.7 later requires).
- `LoadingState`, `ErrorState`, `UnsupportedFileState` tag nothing by default;
  call sites pass their stable tag.
- `StateScreen` is frozen without a tag parameter, so per-screen empty tags
  ride on a wrapper `Box` with `testTag` at the call site (Albums,
  AlbumDetail).
- Tags are stable API: never rename; tests reference the literals.

## 3. The denial contract

**What access:** photo & video *read* access only — `READ_MEDIA_IMAGES` +
`READ_MEDIA_VIDEO` on Android 13+, legacy `READ_EXTERNAL_STORAGE` below (set
dispatch lives in `ui/media/MediaPermissions.kt`). No write, no location, no
network.

**Honesty:** `state_permission_body` states plainly what is needed and the
privacy posture — media stays on device; the app has no network access at all
(offline by construction, issue #20 privacy gate).

**Re-request path:** "Grant access" relaunches the shared permission request.
The launcher answers immediately, without UI, when access is already granted —
so after granting in Settings, that one tap is also the re-check that resumes
the flow. (No automatic resume-scan: returning from Settings without granting
must not ambush the user with another system dialog; the tap is explicit.)

**Settings deep-link (why it must exist):** after two denials Android
silently denies further requests — the dialog never appears, the re-request
button becomes a dead end, and the app's details page in system Settings is
the only remaining path. "Open Settings" deep-links there internally
(`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`, `package:`-scheme URI); call
sites need no wiring. The caption (`state_permission_settings_hint`) explains
exactly this, so the user is never left wondering why a button "does nothing".

## 4. Copy principles (all state strings)

- Honest, specific, non-alarmist; no marketing, no fake personality.
- Say what access is needed and what the app does with it — nothing more.
- Never blame the user; never over-promise ("arrive with…" beats "coming soon!").
- Safety-reassuring on errors: state that nothing was changed
  (`state_photos_error_body`).
- Privacy-forward: on-device only; the network posture may be repeated where
  relevant (denial, first-run empty states).

## 5. Migration notes

- **Photos (deferred):** `PhotosScreen` keeps its inline state layering this
  slice (frozen for concurrent work). It already calls `MediaPermissionState`
  and inherits the deep-link + default tag untouched; its inline loading
  `Box`, error `StateScreen` call and empty state adopt `LoadingState` /
  `ErrorState` / tagged wrappers later. Its error copy
  (`state_photos_error_*` + `state_action_retry`) becomes the canonical
  `ErrorState` argument set.
- **AlbumDetail error (gap):** the ViewModel defensively maps query failures
  to an empty bucket, so no error state renders today. A later slice should
  add `AlbumDetailState.Error` and render `ErrorState(tag =
  "album_detail_state_error")`.
- **Unsupported (no call sites yet):** defined now so the viewer (Phase 3)
  and the editor round trip receiving `ERROR_UNSUPPORTED` (Phase 6,
  `editor-contract.md`) adopt it instead of reinventing per-screen variants.
- **Search/Settings placeholders:** stay on raw `StateScreen` until those
  features exist.
- `docs/architecture/README.md` index entry: next docs-touching slice.
