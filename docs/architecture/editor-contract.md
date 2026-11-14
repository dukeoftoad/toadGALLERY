# toadGALLERY Editor Contract — v1

**Status:** Spec (ADR-0007) · **Date:** 2026-10-29 · **Owners:** `:shared:editor-contract` (both apps)
This is the versioned integration contract between **toadGALLERY** (gallery) and the
separate **toadGALLERY Edit** APK. It exists so the two apps can ship and update
independently (roadmap Phase 6). The contract is additive-versioned: v1 fields below are
frozen; v2 adds optional fields only, or bumps `MIN_SUPPORTED` explicitly.

> **r29 implementation note (Issue #14 gate).** The contract is now code:
> `shared/editor-contract/` compiles every constant and decision rule below
> as a pure-JVM module both apps consume, `apps/edit/` is the prototype
> editor APK (applicationId `io.github.dukeoftoad.gallery.edit`, debug
> builds suffixed `.debug`), and the gallery resolves/launches it from the
> viewer's Edit op (`apps/gallery/.../ui/viewer/EditorLaunch.kt`). Three
> deltas against the text below, all additive and recorded here because the
> spec is the source of truth: **(1)** `EditorIdentity` also accepts the
> EXACT `applicationId + ".debug"` package (both apps' dogfood builds carry
> Gradle's standard `.debug` suffix; the release ID stays the only other
> accepted value and hostile prefix extensions are rejected); **(2)**
> `RESULT_OK` additionally carries `EXTRA_METADATA_POLICY_APPLIED`
> (`org.toadgallery.extra.METADATA_POLICY_APPLIED`, the metadata policy the
> editor actually applied, per §4.4's honesty-report requirement — absent
> on pre-r29 editors and tolerated by the parser); **(3)** the prototype
> exports JPEG-only (`image/jpeg`, quality 95) and pre-declares
> `image/jpeg|png|webp|heic` in its filter — undecodable formats answer
> `ERROR_UNSUPPORTED`. See `docs/phase-6/contract-gate.md`.

> **r31 implementation note (the Edit v1 exit).** Two additive deltas,
> recorded here because the spec is the source of truth: **(1)** the
> metadata policy is honored as a PRIVACY FLOOR — the editor's export
> sheet (r31) offers the three §4.4 policies per export, but a choice
> that would strip LESS than the launch request's policy is disabled
> (`ExportPolicyChoice.isBlockedByFloor`); `EXTRA_METADATA_POLICY_APPLIED`
> continues to echo the policy ACTUALLY applied (the user's stronger
> choice or the request's floor), so §4.4's "respected exactly" holds and
> the upgrade is explicit, opt-in, and reported; **(2)** the gallery's
> honesty UI is now code: additive `SavedCopy.removesLocation` /
> `stripsAllMetadata` triggers over the parsed echo
> (`EditResult.kt`) choose the saved-copy narration — a `null` echo (a
> pre-r29 editor) makes no claim. No frozen constant changed; no new
> error code (the r31 preflight rides `ERROR_IO`/`ERROR_UNSUPPORTED`);
> the wire surface is byte-identical to v1.

## 1. Identity & discovery

| Item | Value |
|---|---|
| Editor application ID | `io.github.dukeoftoad.gallery.edit` |
| Editor launch activity | exported, filtered on the contract action below |
| Contract action | `org.toadgallery.action.EDIT` |
| Version meta-data | `<meta-data android:name="org.toadgallery.contract.EDIT" android:value="1"/>` (editor manifest, launch activity) |
| Gallery minimum accepted | `CONTRACT_VERSION_MIN = 1` |

Gallery resolves the editor via `PackageManager` (action + matching app ID — never
implicit resolution, to prevent spoofed editors receiving URIs). States Gallery must
handle (roadmap Phase 6): **not installed** (offer install path + fallback to third-party
editors), **outdated** (version < gallery's `MIN`: refuse launch, offer update),
**disabled/quiet** (explanatory message).

### Discovery hardening (r46)

Field symptom: with **both current APKs installed** (gallery 0.4.5-alpha,
edit 0.4.10-edit-alpha), the viewer's Edit button refused with the outdated
narration ("toadGALLERY Edit is outdated for this gallery's contract") — the
`Outdated` state — even though the installed editor declares versions the
negotiator accepts.

Two root causes, both in the gallery resolver's discovery path
(`apps/gallery/.../ui/viewer/EditorLaunch.kt`):

1. **Meta-data population on query results is not guaranteed.** The query
   ran with `PackageManager.MATCH_DEFAULT_ONLY` only; the documented flag
   for populating the returned components' `metaData` bundles is
   `GET_META_DATA`. Without it, a query-result `ActivityInfo` can come
   back with a `null` bundle on some Android versions/devices, the
   resolver read that as "no marker", and the negotiator mapped the null
   to `Outdated(null)` — the exact field symptom, manufactured by the
   gallery, not the editor.
2. **Wildcard-vs-specific mime filter matching.** The query used the
   wildcard mimes (`image/*` / `video/*`), but the editor's intent-filters
   declare SPECIFIC subtypes (image: jpeg/png/webp/heic; video:
   mp4/quicktime/3gpp). PackageManager mime matching is asymmetric — an
   intent carrying a wildcard type never matches a filter declaring only
   specific subtypes (a filter's own wildcard covers the intent; the
   reverse never holds) — so the query could return zero contract hits
   and the resolver answered "not installed" for an installed editor.

Fix shape (all in the gallery adapter; the pure `EditorResolver.resolve`
rule is untouched):

- the query flags are now `MATCH_DEFAULT_ONLY or GET_META_DATA`;
- the declared version resolves through the shared pure precedence rule
  `EditorVersionMetadata.pick` (`:shared:editor-contract`, JVM-tested):
  a valid **activity-level** marker wins; otherwise the
  **application-level** marker; null only when both are absent (some
  PackageManager paths populate only one of the two bundles — a direct
  `getActivityInfo(cn, GET_META_DATA)` re-fetch rescues the activity
  level, `getApplicationInfo(pkg, GET_META_DATA)` the application
  level);
- a zero-hit wildcard query retries with the editor's known concrete
  mimes exactly as its manifest filters declare them;
- a final **package-probe fallback**: identity is known exactly (release
  id + `.debug`), so the resolver asks for the editor's package directly
  (`getPackageInfo` with `GET_ACTIVITIES or GET_META_DATA`) and
  synthesizes candidates from its exported activities that carry the
  contract marker — killing the whole class of filter-shape-vs-query-shape
  mismatches AND the meta-data population gap in one honest fallback;
  the Disabled refinement stays the last state check.

Spec delta recorded (additive, per the versioning rule): editor builds
since r46 ALSO declare the marker at the application level
(`<meta-data android:name="org.toadgallery.contract.EDIT" android:value="2"/>`
directly inside `<application>`); the two activity-level markers are
unchanged. **Older editors (activity-level marker only, any declared
version ≥ 1) remain fully supported** — the activity level still wins the
precedence, so the application-level fallback changes nothing for a
healthy read.

## 2. Launch (Gallery → Edit)

| Extra / flag | Type | Required | Meaning |
|---|---|---|---|
| `EXTRA_CONTRACT_VERSION` | int | yes | negotiated contract version (= editor's declared version) |
| `EXTRA_SOURCE_URI` | `Uri` (also via `ClipData`) | yes | source media; Gallery grants `FLAG_GRANT_READ_URI_PERMISSION` scoped to this URI only |
| `EXTRA_OPERATION_HINT` | string | no | requested entry tool: `crop` \| `rotate` \| `resize` \| `draw` \| `adjust` \| `filter` (UI pre-selection only) |
| `EXTRA_EXPORT_MODE` | string | yes | `save_copy` (default & only mode in v1 replace policy: `replace_original` **is not offered in v1**) |
| `EXTRA_METADATA_POLICY` | string | yes | `preserve` (default: keep EXIF incl. GPS) \| `strip_location` \| `strip_all` — editor never silently strips anything else |

Absolute filesystem paths MUST NOT appear anywhere in the contract. Editors that cannot
operate on the granted content URI return `ERROR_UNSUPPORTED` (e.g., a RAW format it
cannot decode).

## 3. Results (Edit → Gallery)

| Result | Extras | Meaning |
|---|---|---|
| `RESULT_OK` | `EXTRA_OUTPUT_URI` (content URI of the saved copy, written via MediaStore insert into the source's album or Pictures) | user confirmed export; Gallery refreshes via MediaStore change observation — no rescan |
| `RESULT_CANCELED` | — | user backed out; nothing was modified anywhere |
| `RESULT_ERROR` | `EXTRA_ERROR_CODE` + `EXTRA_ERROR_MESSAGE` | structured failure (below) |

Error codes: `ERROR_VERSION`, `ERROR_UNSUPPORTED`, `ERROR_IO` (incl. low storage),
`ERROR_PERMISSIONS`, `ERROR_INTERNAL`.

## 4. Editor behavior guarantees (binding)

1. **Non-destructive pipeline**: previews and working copies only; the source URI is
   read-only by construction (grant is read-only).
2. **Undo/redo** is provided in-session; nothing is written until explicit export.
3. **Default Save copy**; v1 has no replace-original at all (v2 may add it only with a
   safety proof: e.g., same album + `MANAGE_MEDIA`-granted write path + explicit user
   confirmation with undo affordance via trash).
4. **Metadata policy respected exactly** — GPS removal is opt-in, explicit, and reported
   in the result so Gallery can show "location removed" honesty UI.
5. One disclosed network use: the AI models download on demand from the pinned
   release URLs after an explicit user confirmation, are SHA-256-verified, and
   touch no other host; no analytics, no telemetry. (Amendment 2026-11: the
   original "never requires network" guarantee predates the on-demand model
   delivery — the merged manifest may declare exactly `INTERNET` and nothing
   else, enforced by the build gate and re-checked at release time.)

## 6. Prototype requirement (roadmap §7 sprint item 6)

Before the full editor is built (Phase 6), a minimal prototype APK must demonstrate:
launch → grant → modify → export → Gallery refresh, plus all three terminal states
(OK / CANCELED / ERROR) and the outdated-extension refusal path. The prototype lives in
the Phase 2 milestone first (it needs the shell to launch from).

## 7. Test matrix (carried into Phase 6 exit)

- [ ] Round trip: source visible → edit → new copy adjacent, original untouched (hash compare)
- [ ] Cancel mid-edit → zero side effects
- [ ] Outdated editor installed → refusal + update prompt
- [ ] Uninstalled editor → fallback offer (third-party editors + install path)
- [ ] Unsupported format → ERROR_UNSUPPORTED, Gallery explains
- [ ] Large file (≥ 50 MP / ≥ 200 MB video poster frame) under memory pressure
- [ ] Process death during edit → no partial files in MediaStore (temp via cache dir)
- [ ] Low storage during export → clean error, no partial entry
- [ ] URI permission revoked between launch and export → ERROR_PERMISSIONS, no crash
