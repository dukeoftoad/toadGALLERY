# Reproducible builds --- the canonical recipe and the verification machinery

**Status:** DOCUMENTED (2026-11-05, the r44 round; r51 refresh: every
task/path below follows the r46+ flavor-qualified build world, and the
engine-family exclusion is stated explicitly in §1/§4). This doc delivers the
reproducible-build half of the Phase 11 "F-Droid metadata" task (the
metadata half is the sibling `docs/phase-11/fdroid-metadata.md`). It
records what "reproducible" means for this repo, the canonical recipe
(ADR-0013), the verification machinery the r43 round lands
(`.github/workflows/reproducible-build.yml`), the honest status line, and
the local third-party verification recipe. Cross-references: ADR-0013
(CI as the canonical build environment), ADR-0014 (the privacy gate rides
every assemble), ADR-0018 (release signing --- the reason the comparison
target is the UNSIGNED artifact), and the roadmap Phase 11 exit criterion
"both artifacts build reproducibly".

---

## 1. What "reproducible" means here

The claim: **a third party --- F-Droid in practice --- building the same
commit from source, with the pinned toolchain, gets the same UNSIGNED APK
bytes.** Two qualifications, both deliberate:

- **Unsigned, not signed.** F-Droid builds its own binaries and re-signs
  them (the ADR-0018 line); the project's pinned release key governs only
  its own GitHub pre-releases. Signing embeds the APK Signature Scheme
  v2/v3 blocks and changes bytes, and a signed APK can never byte-match an
  unsigned build --- so signed-APK determinism is **not claimed**, and
  `Binaries: []` in the F-Droid metadata is the honest consequence: the
  comparison target is the unsigned artifact built from source, verified
  by the repo double-building it (§3), not by comparing against a
  published signed binary.
- **Per-release, not per-commit.** Verification runs on every `v*` tag
  push --- the exact artifact set a release publishes --- not on every
  push to main. Between releases the claim is design intent backed by the
  pinned toolchain; at each release it becomes a measurement.

The claim covers both apps' unsigned release APKs and both
R8 `mapping.txt` files. Since the r46 flavor matrix the verified gallery
artifact is the **basic flavor** --- `gallery-basic-release-unsigned.apk`,
the flavor-qualified `:apps:gallery:assembleBasicRelease` task, the APK the
F-Droid recipe packages --- while the edit app has no flavors and stays
`edit-release-unsigned.apk`. The engine-family flavors
(`engine`/`semantic`/`face`) are **excluded from the measurement**: they
package the vendored Rust engine (built under `-PwithEngine`) and are owned
by a follow-up measurement round; the basic-pair property is what the
machinery was cut to verify. The mapping must **byte-match**: R8 is
deterministic given the same commit, toolchain and inputs, so a mapping
mismatch is a real finding, never zip-metadata noise.

## 2. The canonical recipe (ADR-0013)

GitHub Actions is the canonical build environment; every workflow builds
through `.github/actions/toad-setup`, which pins exactly:

- **JDK 17** (temurin) with the gradle cache;
- **Android SDK**: `platforms;android-36` + `build-tools;36.0.0`
  (minSdk 26 / targetSdk 36 / compileSdk 36);
- **Gradle 9.5.1** via the committed wrapper
  (`gradle/wrapper/gradle-wrapper.properties`; the wrapper jar is
  committed and is the F-Droid metadata's one standard scanignore entry).

The workflows: `ci.yml` gates every PR and main push (build, lint, tests
--- plus the engine jobs, which are source gates only);
`release.yml` produces the signed pre-releases on `v*` tags (ADR-0018,
with the hard `apksigner verify` pin on the signer certificate digest);
`reproducible-build.yml` (the r43 round) does the verification in §3.

Building locally is the same recipe without the runner (since r46 the
gallery task is flavor-qualified: `assembleBasicRelease` builds the basic
flavor, the canonical offline artifact):

```console
$ ./gradlew :apps:gallery:assembleBasicRelease :apps:edit:assembleRelease
```

- **No `TG_*` env** (the local/dev case): the signing config stays
  unwired by design (ADR-0018's env-injected convention) and both apps
  fall back to the unsigned APKs --- `apps/gallery/build/outputs/apk/basic/release/gallery-basic-release-unsigned.apk`
  and `apps/edit/build/outputs/apk/release/edit-release-unsigned.apk`,
  with the R8 mappings at
  `apps/gallery/build/outputs/mapping/basicRelease/mapping.txt` and
  `apps/edit/build/outputs/mapping/release/mapping.txt`.
  This is the F-Droid path: identical bytes expected from the identical
  toolchain.
- **With the `TG_*` env present** (the release-runner case): the same
  command signs --- the GitHub pre-release path; signed determinism is
  not claimed (§1).
- Every assemble runs the ADR-0014 privacy gate transitively (the gate
  anchors on `assemble<Variant>`), so no verified artifact can carry the
  INTERNET permission.
- The engine-family flavors (`engine`/`semantic`/`face`, built under
  `-PwithEngine`) are OFF in every published reproducibility path and
  **excluded from the claim**: the measurement covers the basic flavor +
  the edit app --- exactly what the F-Droid recipe packages. Building the
  engine family needs Rust + cargo-ndk + NDK and changes the artifact;
  that is a recorded follow-up measurement round, not part of this claim
  (see the sibling doc §4.3).

## 3. The verification machinery (`reproducible-build.yml`, the r43 round)

The r43 round lands `.github/workflows/reproducible-build.yml` with
exactly this shape (described as agreed across the parallel rounds; the
workflow file itself is the r43 branch's deliverable, reviewed and merged
by the lead):

- **Triggers:** `v*` tag pushes + `workflow_dispatch`, checking out the
  exact tag SHA --- it verifies the same bits release.yml publishes, from
  the same commit, with no signing secrets involved at all (the workflow
  never exports the `TG_*` environment; with no signing config wired, the
  assemble tasks' output is the `*-unsigned.apk` --- r46+: the gallery
  task is the flavor-qualified `:apps:gallery:assembleBasicRelease`, the
  edit app's stays `:apps:edit:assembleRelease`).
- **Two clean builds** of BOTH unsigned release APKs (the gallery's basic
  flavor + the unflavored edit app; the engine-family flavors are
  excluded --- §1) + both `mapping.txt`
  (two R8 passes each), and the cleanliness is engineered, not assumed:
  pass 1 runs with `--no-build-cache` --- with a warm build cache a
  post-clean rebuild could replay cached task outputs (`FROM-CACHE`),
  which would make the byte comparison trivially pass: verification
  theater --- and between passes a full `gradle clean` removes every
  module's `build/` directory, so pass 2 is a real re-build, not an
  incremental one. Only the dependency cache (inputs, not outputs) stays
  warm. The toolchain is the same `toad-setup` as ci.yml's build job.
- **Byte compare:** the four artifacts are collected under canonical
  names per pass (`gallery-release-unsigned.apk`, `edit-release-unsigned.apk`,
  `gallery-mapping.txt`, `edit-mapping.txt` --- renamed copies; the on-disk
  gallery outputs are `apk/basic/release/gallery-basic-release-unsigned.apk`
  and `mapping/basicRelease/mapping.txt` since r46) with their sha256 list, and
  the two passes are compared. The verdicts are honestly named:
  - **BYTE-REPRODUCIBLE** --- every artifact compared byte-identical
    (`cmp`); the claim of §1, fully met.
  - **CONTENT-REPRODUCIBLE** --- APK bytes differ but the unzipped
    contents are identical; the zip-metadata variance (entry
    order/timestamps) is reported as a warning, the run stays green, and
    the verdict is *named* content-reproducible --- an honest partial
    result, never silently upgraded to a pass. Even in this verdict the
    `mapping.txt` files must still byte-match (R8 is deterministic; there
    is no zip layer in them).
  - **NOT-REPRODUCIBLE** --- any APK differs in content, or any
    `mapping.txt` differs at all: an error and a red run.
- **Report artifact:** the comparison report (per-app verdicts, the
  sha256 lists from both passes) is uploaded as a workflow artifact
  attached to the run, and the r43 round extends `release.yml` to build +
  publish both APKs with per-app `mapping.txt` so the published
  pre-release and the verified artifact set are the same commit by
  construction.

**How to read the results on a release:** open the run on the tag (the
Actions tab filtered to the tag, or the link stamped in the release
notes); the run's conclusion is the gate, and the report artifact carries
the per-app verdicts. The status line to record on the release: "both
unsigned release APKs byte-identical across two clean builds (or:
content-identical, byte-differing in zip metadata only --- stated
honestly), mapping.txt byte-identical".

## 4. The honest status line

- Verification is **per-release**, on every `v*` tag push; there is no
  per-commit claim on main.
- **Signed-APK determinism is not claimed** --- the unsigned artifact is
  the F-Droid comparison target (§1).
- **`mapping.txt` must byte-match** --- R8 is deterministic given the
  same commit + toolchain; a mapping mismatch is a finding, never noise.
- **The engine-family flavors (engine/semantic/face) are excluded from the
  measurement** --- they build under `-PwithEngine` with the Rust
  toolchain in the artifact and are owned by a follow-up measurement
  round; the verified set is the basic-flavor gallery APK + the edit APK
  (§1).
- **The first measurement is IN (2026-11-05, the r45 close-out): the
  `v0.4.5-alpha-r45` push measured BYTE-REPRODUCIBLE** --- run
  `42002437535` on the tag (and the identical-tree pre-flight run
  `42000127417` at the same SHA before the tag was cut): all four
  pairs --- the gallery and edit unsigned release APKs AND both
  `mapping.txt` files --- byte-identical across two clean
  `--no-build-cache` builds. The claim is measured fact at this tag,
  not design intent; every future `v*` tag re-measures it.
- The historical note, kept honestly: the workflow landed with the r43
  round and first ran red --- not on reproducibility but on the build
  itself (the lintVital-family implicit-dependency catches, recorded in
  `follow-up.md`'s Phase 11 window); the green path was restored by the
  catch-fix commits before the tag was cut.

## 5. Local verification (the third-party recipe)

What anyone --- an F-Droid packager, a distro auditor, a user --- can run
to check the claim independently, double-build and compare (the recipe
below is the r46+ flavor-world shape; at the r45 tag and older the
flavorless `:apps:gallery:assembleRelease` + `apk/release/` paths were
correct --- those tags predate the flavor matrix):

```console
$ git clone https://github.com/dukeoftoad/toadGALLERY.git
$ cd toadGALLERY
$ git checkout v0.4.5-alpha-r46
# toolchain: JDK 17 + Android SDK (platform 36, build-tools 36.0.0);
# no other setup --- the gradle wrapper (9.5.1) is committed.

$ ./gradlew :apps:gallery:assembleBasicRelease :apps:edit:assembleRelease
$ mkdir -p /tmp/rb1
$ cp apps/gallery/build/outputs/apk/basic/release/gallery-basic-release-unsigned.apk /tmp/rb1/gallery.apk
$ cp apps/edit/build/outputs/apk/release/edit-release-unsigned.apk /tmp/rb1/edit.apk
$ cp apps/gallery/build/outputs/mapping/basicRelease/mapping.txt /tmp/rb1/gallery-mapping.txt
$ cp apps/edit/build/outputs/mapping/release/mapping.txt /tmp/rb1/edit-mapping.txt

$ ./gradlew clean
$ ./gradlew :apps:gallery:assembleBasicRelease :apps:edit:assembleRelease
$ mkdir -p /tmp/rb2
$ cp apps/gallery/build/outputs/apk/basic/release/gallery-basic-release-unsigned.apk /tmp/rb2/gallery.apk
$ cp apps/edit/build/outputs/apk/release/edit-release-unsigned.apk /tmp/rb2/edit.apk
$ cp apps/gallery/build/outputs/mapping/basicRelease/mapping.txt /tmp/rb2/gallery-mapping.txt
$ cp apps/edit/build/outputs/mapping/release/mapping.txt /tmp/rb2/edit-mapping.txt

# byte compare (the claim) --- all four pairs must match:
$ cmp /tmp/rb1/gallery.apk /tmp/rb2/gallery.apk
$ cmp /tmp/rb1/edit.apk /tmp/rb2/edit.apk
$ cmp /tmp/rb1/gallery-mapping.txt /tmp/rb2/gallery-mapping.txt
$ cmp /tmp/rb1/edit-mapping.txt /tmp/rb2/edit-mapping.txt
```

On a byte mismatch, run the same content-level fallback the workflow runs
--- and read it with the same zip-metadata caveat (§3):

```console
$ mkdir -p /tmp/rb1x /tmp/rb2x
$ (cd /tmp/rb1x && unzip -q /tmp/rb1/gallery.apk)
$ (cd /tmp/rb2x && unzip -q /tmp/rb2/gallery.apk)
$ diff -r /tmp/rb1x /tmp/rb2x && echo "content-identical (byte difference is zip metadata only)"
```

`sha256sum` all four artifacts from both builds and keep the hashes with
the comparison --- that is the evidence trail the workflow's report
artifact automates. A third party comparing against the GitHub
pre-release should compare **content against the unsigned build** and
expect the signed pre-release to differ in exactly two places: the
signature blocks and the zip metadata around them; that is the honest
limit of what a published signed binary can verify, which is why the
repo's own double-build (not a signed-binary comparison) is the
machinery (§1).
