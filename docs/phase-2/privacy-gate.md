# Privacy Gate: Offline by Construction (Phase 2, issue #20)

Status: **ACTIVE** --- Phase 2 exit criterion. Implemented 2026-10-29.
Owner: toadGALLERY core (apps/gallery).

## Why this exists

toadGALLERY's privacy promise is not "we do not use the network" --- it is
"the app *cannot* use the network". The product vision
([`docs/product-vision.md`](../product-vision.md)) and ADR-0010 (on-device AI
only, models shipped in the APK, no cloud ML) both assume the core gallery
holds **no `android.permission.INTERNET`**. Without that permission the
Android OS refuses every socket the app process could open: no code path, no
third-party dependency, and no future feature can silently phone home. This is
strictly stronger than any behavioural promise, proxy audit, or
`NetworkSecurityConfig` logging, because it is enforced by the kernel rather
than by convention.

The threat this gate addresses is concrete: **any dependency can inject
`<uses-permission android:name="android.permission.INTERNET"/>` into the
merged manifest via manifest merger**, without touching our source manifest.
A source-manifest review cannot catch that; only the merged manifest --- the
file that actually ships inside the APK --- can.

## The three enforcement layers

### Layer 1 --- Build-time merged-manifest gate (Gradle)

`InternetPermissionGateTask` (in [`apps/gallery/build.gradle.kts`](../../apps/gallery/build.gradle.kts))
registers one `gate<Variant>InternetPermission` task per variant via
`androidComponents.onVariants`:

* **Input:** `SingleArtifact.MERGED_MANIFEST` --- the merged manifest AGP
  produces from our manifest plus every dependency's manifest. Reading it
  through the provider automatically schedules `process<Variant>MainManifest`
  first, so the gate always inspects fresh output.
* **Behaviour:** if the merged manifest declares
  `android.permission.INTERNET` and the allowlist does not authorize it, the
  task throws and **the build fails** with a pointer to this document.
* **Anchoring:** the gate is attached to `assemble<Variant>`,
  `bundle<Variant>`, and `check`. Because CI's binding step is
  `assembleDebug` (ADR-0013), **no CI run can produce an APK that was not
  gated**. Local `installDebug`/`connectedDebugAndroidTest` paths also flow
  through `assemble`, so manual testing is covered too.

### Layer 2 --- Privacy policy unit tests (JVM)

`PrivacyPolicyTest` ([src/test](../../apps/gallery/src/test/kotlin/io/github/dukeoftoad/gallery/PrivacyPolicyTest.kt))
runs in the ordinary `testDebugUnitTest` task, which is **binding on CI**
since this change:

1. the *source* manifest declares no INTERNET permission (fast signal before
   any manifest merging),
2. the allowlist file exists and has **zero active entries** (it must ship
   empty), and
3. a regression tripwire asserting the gate task and its
   `SingleArtifact.MERGED_MANIFEST` wiring are still present in the build
   script --- so the enforcement model cannot be silently deleted.

### Layer 3 --- Instrumented egress suite (manual, on-device)

`PrivacyEgressTest` ([src/androidTest](../../apps/gallery/src/androidTest/kotlin/io/github/dukeoftoad/gallery/PrivacyEgressTest.kt))
is a **manual release gate**: run on a physical device before every tagged
release with `./gradlew :apps:gallery:connectedDebugAndroidTest`. Until
2026-10-30 it also ran on the CI emulator; that job was removed because the
hosted runners expose no KVM (pure software emulation made the job slow and
probabilistically red at APK install, before any test executed) --- ADR-0016.
It still proves, on a real system image:

1. **Runtime permission assert** --- the app uid holds no INTERNET permission
   on a real system image.
2. **Active egress probe** --- the suite opens a TCP `Socket` to a public
   resolver (raw IP `1.1.1.1:53`, no DNS dependency) and asserts the OS
   blocks it. A `SecurityException` or `SocketException`/EACCES passes; a
   successful connection or timeout fails the suite. If any future change
   ever grants effective network access, this test turns red on CI.
3. **Core-flow walk** --- cold launch plus a walk across all four top-level
   tabs (Photos, Albums, Search, Settings) so the guarantee is exercised
   against real app startup. "Open a photo" joins the walk in Phase 3 when
   media browsing lands; the walk is extended, never replaced.

## Allowlist protocol

The allowlist is
[`apps/gallery/internet-permission-allowlist.txt`](../../apps/gallery/internet-permission-allowlist.txt).
It shipped **empty** through Phase 4; since r22 it carries exactly one
entry (issue #94): the first-party INTERNET declaration behind ADR-0020's
model downloads (pinned host `models.ente.com` — the ADR's revision note
records the egress gates that scope it). An entry
(`android.permission.INTERNET`) may only be added when all of the
following hold:

1. A superseding or new ADR in
   [`docs/integration-decisions.md`](../integration-decisions.md) authorizes
   network access for a specifically scoped feature.
2. An open tracking issue documents the exact dependency or manifest source
   that injects the permission and why it cannot be avoided.
3. The change is called out in the PR description and linked from the ADR.

Reviewers should treat any diff that adds an active allowlist line as
requiring the same scrutiny as an ADR change.

## Verification evidence

* CI `build` job (assemble + lint + unit tests):
  `https://github.com/dukeoftoad/toadGALLERY/actions/workflows/ci.yml`
* Manual instrumented run (physical device, before each tagged release):
  `./gradlew :apps:gallery:connectedDebugAndroidTest` --- Layer 3 evidence.
  CI does not run emulator jobs (ADR-0016); the binding CI signal is the
  Layer 1 + Layer 2 gate inside `build`.
* Local negative-path check (performed 2026-10-29, see issue #20 for the
  transcript): temporarily declaring INTERNET in the source manifest makes
  `assembleDebug` fail with `PRIVACY GATE FAILED`; removing it restores the
  build. The gate therefore catches both source-level and merger-injected
  declarations.

## Phase 3 extension points

* The core-flow walk gains "open a photo from the timeline" as soon as
  URI-first MediaStore browsing lands (roadmap Phase 3).
* If the separate toadGALLERY Edit APK (ADR-0002) ever requires network for
  model downloads (it does not per ADR-0010 --- models ship in the APK), that
  APK gets its own gate and allowlist; the core gallery's policy is not
  relaxed for it.
