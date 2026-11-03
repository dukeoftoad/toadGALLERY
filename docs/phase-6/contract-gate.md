# Phase 6 Entry Gate — the Gallery ↔ Edit Contract Prototype (r29)

**Status:** Delivered (r29) · **Closes the Issue #14 gate** · Spec: `docs/architecture/editor-contract.md` · ADR-0002 / ADR-0007

## What landed

The contract left the spec page and became code, in three pieces:

### 1. `:shared:editor-contract` — one truth, both apps

A pure-JVM Kotlin module (no Android surface at all) compiling the
contract's decision logic once and consuming it from both sides:

- `EditContract` — every frozen v1 constant (action, extras, metadata key,
  error codes, result codes, export mode, metadata policies). `RESULT_ERROR`
  is `RESULT_FIRST_USER + 1` so it can never be mistaken for OK/CANCELED.
- `EditorIdentity` — exact application-ID match (`io.github.dukeoftoad.gallery.edit`),
  with the ONE documented `.debug` dogfood suffix exception; hostile
  prefix extensions (`…edit.evil`) are rejected.
- `EditorVersionNegotiator` — the additive-versioning rule: any editor
  declaring `≥ CONTRACT_VERSION_MIN (1)` is Ready (its version passes
  through as the negotiated version); below-minimum or missing meta-data
  is refused.
- `EditRequestValidator` + `EditLaunchExtrasBuilder` — the shared request
  gate (version window, `content://`-only source URIs — absolute
  filesystem paths are refused, `save_copy`-only export mode, total
  metadata-policy parse, known-operation-hint rule) and the typed launch
  extras.
- `EditResultParser` — all three terminal states, total (unknown result
  codes and malformed OK replies degrade to structured `ERROR_INTERNAL`,
  never crashes; unknown editor error codes are preserved in the message
  but classified internal).

Every rule is JVM-tested in milliseconds on CI — no emulator, no
Robolectric.

### 2. `:apps:edit` — the toadGALLERY Edit prototype APK

The separate installable editor app (`versionCode 1`, `0.1.0-edit-alpha`):

- **Manifest surface**: exported `EditActivity` filtered on
  `org.toadgallery.action.EDIT` (+ DEFAULT, image/jpeg|png|webp|heic),
  meta-data `org.toadgallery.contract.EDIT = 1`. Zero network: NO
  `INTERNET` permission, enforced by the module's own merged-manifest gate
  (stricter than the gallery's — there is no allowlist mechanism at all;
  contract §4.5). The single declared permission is
  `WRITE_EXTERNAL_STORAGE` capped at `maxSdkVersion=28` (pre-Q MediaStore
  inserts; Q+ own-contribution inserts need no permission).
- **A real modify step**: brightness + contrast via a per-channel LUT
  (brightness scale 2.55, standard 8-bit contrast factor pivoted at 128,
  composed brightness-then-contrast), left rotation as pure grid math, a
  live bounded-preview session (2048 px) with draft sliders and commit-on-
  release semantics, undo/redo over a bounded 64-deep snapshot stack.
- **Non-destructive save-copy export** (ADR-0011): full-resolution decode →
  adjust → rotate → compress JPEG q95 → MediaStore **INSERT of a NEW row**
  (`IS_PENDING` on Q+, cleared after the bytes land; a failed write
  CANCELS the pending row so no partial entry survives) → EXIF metadata
  policy applied through `androidx.exifinterface` (`preserve` copies every
  tag, `strip_location` nulls the GPS tag set, `strip_all` writes nothing)
  → `RESULT_OK` with `EXTRA_OUTPUT_URI` + the applied policy echoed. The
  source URI is read-only by construction: never opened for write, never
  updated, never deleted.
- **Terminal states**: the pre-compose validation failures answer
  `RESULT_ERROR` with structured codes before any UI exists; back is
  `RESULT_CANCELED`; decode/IO/OOM/permission failures map to
  `ERROR_UNSUPPORTED` / `ERROR_IO` / `ERROR_PERMISSIONS` /
  `ERROR_INTERNAL`.
- **Conformance suite**: a JVM test parses the app's own source manifest
  and pins the contract surface (action, mime types, meta-data v1, the
  zero-network posture, the capped storage permission) — CI's runnable
  proof of the editor's side of the contract.

### 3. The gallery half

- `ui/viewer/EditorLaunch.kt` — package-visible resolution over the
  contract action (exact identity match → enabled flag → version
  negotiation), the typed launch-intent builder (Parcelable source Uri +
  ClipData + read-only grant scoped to the one URI), the third-party
  `ACTION_EDIT` chooser fallback for the not-installed state, and the
  reply parser. The pure resolution rule is JVM-tested in the gallery
  module.
- The viewer's **Edit op** (image pages only, `Icons.Filled.Create`):
  not installed → honest message + fallback chooser; outdated → refusal +
  update nudge; disabled → explanatory message; ready → launch. The reply
  narrates through the shared parser (`Saved copy` / structured error;
  canceled stays silent).
- `<queries>` visibility for the contract action and the fallback action
  (API 30+ package visibility — without it resolution would always answer
  not-installed).
- **Refresh needs no rescan and no new wiring**: the saved copy is a
  MediaStore INSERT the existing ContentObserver-driven paging
  invalidation picks up — the contract's "Gallery refreshes via MediaStore
  change observation" property, already proven by the issue #38 follow-up
  work.

## Why the hash-compare test matrix entry rides the checklist

CI cannot install two APKs on an emulator (ADR-0016), so the round trip is
proven in two layers here (contract logic + manifest conformance, both
CI-green) and one layer on the device: the physical
launch → grant → modify → export → gallery-refresh round trip, the
original-untouched hash comparison, and the three terminal states join the
device-validation checklist as the dogfood verification of this gate.

## Test totals for the round

- `:shared:editor-contract`: ~40 JVM tests (negotiation, identity, request
  validation, launch extras, result parsing, frozen constants).
- `:apps:edit`: ~35 JVM tests (LUT math, rotation math, session undo/redo,
  export plan, metadata policy, manifest conformance).
- `:apps:gallery`: 9 new JVM tests (resolver rule); the gallery total goes
  1011 → 1020.
