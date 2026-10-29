# toadGALLERY Editor Contract — v1

**Status:** Spec (ADR-0007) · **Date:** 2026-10-29 · **Owners:** `:shared:editor-contract` (both apps)
This is the versioned integration contract between **toadGALLERY** (gallery) and the
separate **toadGALLERY Edit** APK. It exists so the two apps can ship and update
independently (roadmap Phase 6). The contract is additive-versioned: v1 fields below are
frozen; v2 adds optional fields only, or bumps `MIN_SUPPORTED` explicitly.

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
5. Editor never requires network; no analytics; same offline-first rules as Gallery.

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
