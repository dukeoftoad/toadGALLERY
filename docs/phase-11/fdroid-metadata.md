# The F-Droid submission package --- the Phase 11 metadata round (r44)

**Status:** PREPARED (2026-11-05, the r44 round). This doc delivers the
Phase 11 "F-Droid metadata" task's remainder from the roadmap's Phase 11
section: *prepare the F-Droid build metadata, verify the build is
reproducible (CI workflow is the canonical recipe per ADR-0013), and
confirm no proprietary dependencies.* The reproducible-build half lives in
the sibling doc (`docs/phase-11/reproducible-builds.md`, same round); this
doc carries the two metadata files, the submission process, the
build-environment requirements, and the no-proprietary-dependency audit
(the full third-party FOSS review is a separate Phase 11 task owned by
another round --- the dependency/proprietary screen here is the F-Droid
metadata doc's half of it, per the roadmap's split).

The licensing ground truth this doc cites (not rewrites):
`docs/licensing-and-attribution.md` (the living compliance record, §2
upstream inventory + §5 provenance ledger), `engine/NOTICE.md` (the
vendored engine's posture), and the original Phase 1 screen at
`docs/phase-1/dependency-license-compatibility.md` (confirmed below ---
its dropped-dependency outcome per ADR-0008/issue #21 still stands; the
screen carries no open blockers).

---

## 1. What is in `fdroid/metadata/`

Two files, one per package --- the product deliberately ships as **two
separate F-Droid packages** versioned independently off one shared release
tag (the editor-contract meta-data version 2; the tag follows the
GALLERY's `versionName`, the editor's `versionName` is its own):

| File | Package | AutoName | Build entry |
|---|---|---|---|
| `io.github.dukeoftoad.gallery.yml` | `io.github.dukeoftoad.gallery` | toadGALLERY | `0.4.5-alpha` / vercode 37 |
| `io.github.dukeoftoad.gallery.edit.yml` | `io.github.dukeoftoad.gallery.edit` | toadGALLERY Edit | `0.4.10-edit-alpha` / vercode 15 |

Both Build entries point at the same commit, the release tag
`v0.4.5-alpha-r45` (the `v<versionName>-r<round>` tag convention; the
suffix is the round the release is cut in, not the code round that last
bumped the versionCode --- the gallery's vercode 37 and the editor's
vercode 15 were last bumped by the r43 round). The tag is cut by the
maintainer at release time; **the fdroiddata merge request should go in
only after the tag exists**, because `fdroid build` needs the commit to
resolve.

The shared fields follow the repo's posture honestly rather than
optimistically:

- `Binaries: []` --- the repo publishes no binaries for F-Droid to verify
  against. The GitHub pre-releases are release-SIGNED APKs (ADR-0018),
  and signed-APK determinism is not claimed; the comparison target for
  reproducibility is the **unsigned** artifact built from source (the
  sibling doc's §1).
- `AntiFeatures: []` --- none, by construction: no ads, no tracking, no
  nonfree-net. The INTERNET permission is build-time-gated to absent in
  both apps (ADR-0014; the editor's gate is even stricter --- no allowlist
  mechanism at all, and the r41 catch hard-strips the ONNX Runtime AAR's
  sample-convenience INTERNET declaration from the merged manifest), so
  the OS blocks every socket and there is no code path that could phone
  home.
- `scanignore` --- the standard gradle-wrapper jar entry in both files,
  plus the editor's ONNX model directory (data files, not executables ---
  the disposition is argued in the file's MaintainerNotes). The bundled
  OFL fonts were deliberately NOT scanignored; if `fdroid scanner` flags
  them at merge-request time, the path gets the same commented treatment.

## 2. The submission process (fdroiddata)

The metadata goes to **fdroiddata** (`https://gitlab.com/fdroid/fdroiddata`)
as a **new-app merge request**: one MR carrying both files (F-Droid's
convention is one MR per app submission; two new apps from one repo may
ride one MR --- `fdroid lint` will say which it prefers at review time),
each named `<package-id>.yml` under `metadata/`, exactly the shape in
`fdroid/metadata/` here. The MR description should answer fdroiddata's
new-app questions from this repo's own record: GPL-3.0 (ADR-0004,
voluntary), no upstream code (ADR-0008 --- the upstreams are UX/feature
references only), offline by construction (ADR-0014), source on GitHub at
the release tags.

Verification before submitting (and at every update), from a fdroiddata
clone with `fdroidserver` installed:

```console
$ fdroid checkupdates io.github.dukeoftoad.gallery
$ fdroid checkupdates io.github.dukeoftoad.gallery.edit
$ fdroid lint io.github.dukeoftoad.gallery
$ fdroid lint io.github.dukeoftoad.gallery.edit
$ fdroid scanner io.github.dukeoftoad.gallery
$ fdroid scanner io.github.dukeoftoad.gallery.edit
$ fdroid build --verbose io.github.dukeoftoad.gallery=37
$ fdroid build --verbose io.github.dukeoftoad.gallery.edit=15
```

- `checkupdates` finds the `v*` tags (`UpdateCheckMode: Tags` in both
  files). The gallery file lets `AutoUpdateMode: Version` derive its
  builds from the tag; the editor file stays `AutoUpdateMode: None`
  because its versionName cannot be derived from a tag that follows the
  gallery's --- its Build entries are curated by hand (the honest
  convention, argued in that file's MaintainerNotes).
- `lint` validates the metadata shape; `scanner` screens the source tree
  for prebuilt binaries (the scanignore dispositions above).
- `build` must produce the unsigned APKs at the `output:` globs recorded
  in the files; that build is the reproducibility comparison target and
  also runs the ADR-0014 privacy gate transitively (it anchors on
  `assemble<Variant>`).

## 3. The build-environment requirements

Identical to the canonical CI setup (ADR-0013) --- what
`.github/actions/toad-setup` installs, no more:

- **JDK 17** (temurin), the gradle cache included;
- **Android SDK**: platform `android-36` + build-tools `36.0.0`
  (`compileSdk`/`targetSdk` 36, `minSdk` 26);
- **Gradle 9.5.1** via the committed wrapper
  (`gradle/wrapper/gradle-wrapper.properties`); the wrapper jar is the
  one scanignore entry.

No `sudo`, no `ndk`, no Rust: the default recipe does not build the
vendored Rust engine (see §4's engine note) and the editor's AI runs on
the ONNX Runtime AAR from Maven Central --- neither needs a native
toolchain. `fdroid build` with JDK 17 + SDK 36 + build-tools 36 matches
the recipe byte-for-byte in toolchain terms.

## 4. The dependency-license audit --- no proprietary dependencies

Walk of the ACTUAL dependency set (`gradle/libs.versions.toml`, the
version catalog every module resolves through) plus the shipped assets.
Every row cites where the license claim comes from; the verdicts are
against the product license GPL-3.0 (ADR-0004, voluntary). Note the
compatibility frame used: **Apache-2.0 and MIT are both GPL-3.0
compatible** (the FSF's list --- Apache-2.0's incompatibility is with
GPL-2.0 only, which is irrelevant here); OFL-1.1 is GPL-compatible.

### 4.1 The build + runtime dependencies (the shipped closure)

| Dependency (catalog ref) | Version | License | GPL-3.0-compatible |
|---|---|---|---|
| Kotlin toolchain + compose compiler + jvm plugins | 2.3.10 | Apache-2.0 | yes |
| AGP (`com.android.application`) | 9.2.1 | Apache-2.0 | yes |
| Compose BOM + ui/material3/foundation/icons/tooling | 2024.12.01 | Apache-2.0 (AndroidX) | yes |
| androidx.activity:activity-compose | 1.10.1 | Apache-2.0 | yes |
| androidx.navigation:navigation-compose | 2.8.5 | Apache-2.0 | yes |
| androidx.lifecycle (viewmodel + runtime-compose) | 2.8.7 | Apache-2.0 | yes |
| androidx.biometric | 1.1.0 | Apache-2.0 | yes |
| androidx.work:work-runtime-ktx | 2.10.1 | Apache-2.0 | yes |
| androidx.paging (runtime + compose) | 3.3.2 | Apache-2.0 | yes |
| androidx.exifinterface | 1.4.1 | Apache-2.0 | yes |
| androidx.room (runtime + ktx + compiler) | 2.8.5 | Apache-2.0 | yes |
| com.google.android.material:material | 1.13.0 | Apache-2.0 | yes |
| io.coil-kt (coil-compose/video/gif) | 2.7.0 | Apache-2.0 | yes |
| androidx.media3 (exoplayer/ui/transformer/effect) | 1.11.1 | Apache-2.0 | yes |
| KSP (`com.google.devtools.ksp`) | 2.3.10 | Apache-2.0 | yes |
| `com.microsoft.onnxruntime:onnxruntime-android` (prebuilt AAR) | 1.31.1 | **MIT** (upstream's own LICENSE; the repo's notes record Apache-2.0 --- the recorded discrepancy in §6, both permissive) | yes |

Test-classpath only (never in any shipped APK --- called out because one
of them is NOT GPL-compatible, which is fine precisely because it never
ships): `junit:junit` 4.13.2 (EPL-1.0), `org.json:json` 20240303 (the
upstream "use freely" permissive license, JVM test parser only), the
androidx.test runner/rules/ext-junit + compose test artifacts (Apache-2.0).

### 4.2 The shipped assets (the editor APK)

| Asset | License | Provenance record |
|---|---|---|
| `erase-segmentation-u2netp.onnx` (4,574,861 bytes) | Apache-2.0 (U-2-Net; ONNX conversion tagged Apache-2.0) | `docs/licensing-and-attribution.md` §5, sha256 pinned by `EditAiManifestTest` |
| `erase-inpaint-migan.onnx` (29,546,882 bytes) | MIT (MI-GAN, LICENSE fetched + verified; the first author's conversion mirror, MIT) | §5, sha256-pinned |
| `bokeh-depth-dav2-small-int8.onnx` (27,258,801 bytes) | Apache-2.0 (Depth-Anything-V2-Small official card; onnx-community int8 conversion, Apache-2.0) | §5, sha256-pinned |
| `retouch-faces-yunet.onnx` (232,589 bytes) | Apache-2.0 (opencv_zoo `models/face_detection_yunet` 2023mar) | §5, sha256-pinned |
| the four ONNX models, total | --- | **61,613,133 bytes**, three Apache-2.0 + one MIT, all four sha256-pinned by the app's own manifest test (a swapped model fails the build) |
| bundled fonts (fira-sans, playfair-display, caveat, space-mono) | SIL OFL 1.1 (`OFL.txt` beside each file) | §5 ledger rows, sha256-pinned by `BundledFontsTest` |
| bundled LUTs (`assets/luts/*.cube`) | GPL-3.0 (project) | project-authored, deterministic regeneration (r33, `tools/gen_bundled_luts.py`) --- no third-party data |

### 4.3 The vendored Rust engine (`engine/`)

The engine workspace is **AGPL-3.0** (declared at `engine/Cargo.toml`
`[workspace.package]`; the full adaptation record is `engine/NOTICE.md`).
AGPL-3.0 is GPL-3.0-compatible **one-way**: AGPL code may enter a GPL-3.0
work only by re-licensing the combined derivative under AGPL-3.0 --- the
consequence the maintainer accepted and recorded in
`docs/licensing-and-attribution.md` §2 (*distributed builds containing
`engine/` are effectively offered under AGPL-3.0*). The honest fact for
F-Droid: **no distributed APK contains it.** The engine's native libraries
only enter the gallery APK when gradle runs with `-PwithEngine` (Rust +
cargo-ndk + NDK), and every published build path --- ci.yml, release.yml,
and therefore the F-Droid recipe in the metadata files --- builds without
the property; the Kotlin facade treats the missing native library as an
unavailable engine and the engine-dependent AI features degrade honestly.
So both packages' `License: GPL-3.0` is accurate for every artifact that
is actually distributed. **Guard note:** if a future recipe turns the
engine on, the gallery APK's effective offer becomes AGPL-3.0 and its
`License:` field must change with it --- that is a recorded follow-up
condition, not a gap.

The engine's crates.io dependencies (anyhow, image, ort, rusqlite, tokio,
getrandom, jni, ... per `engine/Cargo.toml`, all resolved through
`engine/Cargo.lock`) are the standard permissive MIT/Apache-2.0 set, plus
the git-pinned `heic-decoder` carried verbatim from upstream's pin
(`engine/NOTICE.md`) --- none of it enters any shipped APK through the
default recipe, so it never reaches the F-Droid audit surface at all.

### 4.4 Conclusion

**No proprietary dependencies.** Every shipped byte is either the
project's own GPL-3.0 code, an Apache-2.0/MIT AndroidX-or-Kotlin
dependency, an Apache-2.0/MIT ONNX model, or an OFL-1.1 font --- all
GPL-3.0-compatible. The two non-permissive items in the tree (the AGPL-3.0
engine, which no distribution contains, and the EPL-1.0 test-only junit)
are both outside every shipped artifact. The GPL-3.0 source-distribution
obligation is met by the GitHub repo + release tags: every release links
the exact commit it was built from (the release workflow stamps the SHA
into the published notes), the full GPL-3.0 text is the root `LICENSE`,
and notice preservation is tracked in `docs/licensing-and-attribution.md`
§3.

## 5. Update conventions (the by-round cadence)

The repo releases by round: each delivery round bumps the affected app's
`versionCode` once (the gallery is at 37, the editor at 15 --- both last
bumped by the r43 round) and the maintainer cuts one shared tag
`v<versionName>-r<round>` per release window. For F-Droid that means: the
gallery's Build entries can be auto-derived (`AutoUpdateMode: Version`,
vercode read from `apps/gallery/build.gradle.kts`), while the editor's
are curated by hand each release (`AutoUpdateMode: None`) because the
shared tag follows the gallery's versionName --- the disposition is
argued in both files' MaintainerNotes so fdroiddata reviewers see the
reasoning, not just the setting.

## 6. Honest gaps and recorded follow-ups

1. **The onnxruntime-android license discrepancy.** The repo's own notes
   record the AAR as Apache-2.0 (the `libs.versions.toml` comment and the
   §5 ledger row); upstream licenses ONNX Runtime under **MIT**. Both are
   permissive and GPL-3.0-compatible, so the audit verdict is unchanged,
   but the repo's records should say MIT at the next
   `licensing-and-attribution.md` touch --- that doc is the licensing
   owner's to edit (not this round's), so it is recorded here as a gap
   with the correction queued.
2. **The engine-off recipe.** The F-Droid gallery build ships without
   the native ML engine (§4.3). If users want the engine-built flavor,
   the recipe needs Rust/NDK fields and the AGPL guard note fires ---
   deliberately left as a follow-up rather than half-specified here.
3. **Scanner dispositions are unverified until the MR.** The
   gradle-wrapper jar and ONNX model scanignore entries are the standard
   practice, but `fdroid scanner`'s actual disposition is only knowable
   at merge-request time; the fonts were not scanignored at all (§1).
4. **The tag does not exist yet.** `v0.4.5-alpha-r45` is cut by the
   maintainer at release time; the metadata targets it per the agreed
   convention. Until the tag exists, `fdroid build` for vercode 37/15
   cannot run against it --- submit after the release round lands it.
5. **First verification run pending.** The reproducible-build workflow
   (the r43 round) has not yet run on a release tag; the honest status
   line and the local third-party recipe are in the sibling doc
   (`docs/phase-11/reproducible-builds.md` §4).
