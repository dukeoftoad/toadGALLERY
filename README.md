# toadGALLERY

**A single, private, offline-first Android gallery** that browses and organises like
Mi Gallery, exposes metadata like Aves libre, edits like Mi Gallery's editor, and
respects your data like Fossify --- **built from scratch in Kotlin + Jetpack Compose**.

> Inspired by Mi Gallery (editor UX, organisational features), Aves libre (metadata
> depth, collections), and Fossify Gallery (privacy posture, offline-first). **No code
> is ported from any upstream** (ADR-0008) --- only features, UI/UX, and designs. No
> Flutter. No accounts, no cloud, no ads, no analytics. Core functionality works fully
> offline. The core app manifest does not declare `INTERNET`.

**Status:** v2.2 revision (2026-10-29). Phase 0/1 complete. Phase 2 shell is
**Compose-native + Material 3** (ADR-0009; the View-based + `org.fossify:commons`
shell and dependency are gone, see
[`docs/architecture/compose-migration.md`](docs/architecture/compose-migration.md)).
CI on GitHub Actions per ADR-0013, including a build-time **INTERNET-permission
gate** and an instrumented **privacy egress suite** on an API 34 emulator
([`docs/phase-2/privacy-gate.md`](docs/phase-2/privacy-gate.md), issue #20):
the app is offline by construction --- no INTERNET permission means the OS
blocks every socket.

---

## Product goals

1. Build **one coherent product** called toadGALLERY, not two competing gallery apps.
2. **Take features, UI/UX, and designs only** from Mi Gallery (proprietary, reference
   only), Fossify Gallery (GPL-3.0, reference only), and Aves libre (BSD-3-Clause,
   reference only). **No code is ported, adapted, or vendored from any upstream.**
3. Build toadGALLERY **from scratch in Kotlin + Jetpack Compose**.
4. Deliver a clean, fast, intuitive gallery with strong media browsing, deep metadata,
   smart search, safe file operations, and a flagship editor.
5. Ship **toadGALLERY Edit** as a separate APK, opened from the main Gallery via a
   versioned content-URI contract, updatable independently.
6. Keep the product private and offline-first: no mandatory account, cloud service,
   ads, analytics, or network access. No `INTERNET` permission in the core manifest.
7. Protect originals by default. Non-destructive editing pipeline; recycle bin;
   PIN/biometric hidden vault.
8. Use **GitHub Actions as the canonical build and CI environment**.

See [`roadmap.md`](roadmap.md) for the full phased plan (Phases 0–11) and
[`docs/product-vision.md`](docs/product-vision.md) for the authoritative north star.

## How the three references contribute (v2.2, ADR-0008)

| Reference | Role in toadGALLERY | What we take | What we deliberately leave |
|---|---|---|---|
| **Mi Gallery** (proprietary) | UX/feature *reference* only | Editor tool layout, Moments/Stories/Auto-albums model, smart-search affordances, collage/slideshow UX, video trim flow | Any code, branding, icons, cloud services, online ML |
| **Aves libre** (BSD-3-Clause) | UX/feature *reference* only | EXIF/IPTC/XMP patterns (reimplemented using `androidx.exifinterface`), tags/ratings/colour labels, collections, on-device map view, format taxonomy | Flutter/Dart UI, the `exifinterface` fork (use AndroidX directly), network tile providers |
| **Fossify Gallery** (GPL-3.0) | UX/feature *reference* only | Privacy posture, offline-first behavior, broad gallery shape | `org.fossify:commons`, any code skeleton, path-keyed Room schema, View-based UI obligation |
| **Jetpack Compose + Material 3** (Apache-2.0) | Canonical UI toolkit | Declarative UI, Material 3 + dynamic color, animation primitives, Compose Canvas | (none — this is the chosen toolkit) |

Key documents: [product vision](docs/product-vision.md) ·
[source audit: Fossify](docs/phase-1/source-audit-fossify.md) ·
[source audit: Aves](docs/phase-1/source-audit-aves.md) ·
[integration decisions (ADRs 0001–0013)](docs/integration-decisions.md) ·
[feature matrix](docs/feature-matrix.md) ·
[Compose migration tracker](docs/architecture/compose-migration.md).

## Repository structure

```text
toadGALLERY/
├── .github/workflows/ci.yml     # CI: build + lint + test (ADR-0013)
├── roadmap.md                   # Phased development roadmap (Phases 0–11)
├── LICENSE                      # GPL-3.0 (voluntary per ADR-0004/0008)
├── docs/
│   ├── product-vision.md        # Authoritative north star
│   ├── architecture/            # module + data-flow diagrams, editor contract, compose migration
│   ├── phase-0/                 # baselines, build notes (historical), sign-off, risk register
│   ├── phase-1/                 # source audits (reference-only framing), dependency report
│   ├── phase-2/                 # shell verification (transitional)
│   ├── feature-matrix.md        # capability origin & owner (v2.2)
│   ├── integration-decisions.md # ADR-0001..0013
│   └── licensing-and-attribution.md
├── apps/                        # gallery/ (Kotlin + Compose); edit-extension/ (Phase 6)
├── shared/                      # (Phase 2+) media-contract, metadata, editor-contract, design-system, ml-models
└── gradle/                      # wrapper, libs.versions.toml
```

## Build & CI

The canonical build is GitHub Actions (ADR-0013). To build locally you need:

- JDK 17 (Temurin recommended)
- Android SDK with `platforms;android-36` and `build-tools;36.0.0`
- Gradle 9.5.1 (project wrapper)

```bash
./gradlew :apps:gallery:assembleDebug
./gradlew :apps:gallery:lintDebug
./gradlew :apps:gallery:testDebugUnitTest
```

No Flutter SDK required. No Dart SDK required. The CI workflow at
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) is the source of truth for
"does it build"; a green CI check is required to merge any PR.

### Release builds / testing pre-releases

Testing pre-releases are produced by the dedicated `Release` workflow
([`.github/workflows/release.yml`](.github/workflows/release.yml), ADR-0018,
issue #59), separate from CI: push a `v*` tag, or dispatch the workflow by
hand (its `tag_name` input defaults to a `versionName`-derived
`v0.4.2-alpha-r<run_number>`, so testing releases can be cut ad hoc). The
signed APK is published as a GitHub **pre-release** --- never marked latest
while pre-1.0 --- together with a `.sha256` checksum and the R8
`mapping.txt` for de-obfuscating crash traces. A hard `apksigner verify`
gate pins the signer certificate SHA-256 digest
(`8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494`), so an
unsigned or wrongly-signed APK cannot publish. Verify a downloaded APK with
`apksigner verify --print-certs` and compare the digest. Local builds are
unaffected: without the signing env vars the release build falls back to
unsigned.

## Upstream references (recorded 2026-10-29)

| Project | Recorded commit | License | Role under ADR-0008 |
|---|---|---|---|
| Fossify Gallery v1.14.1 | `e9812299b9d444b…` (`main`) | GPL-3.0 | Reference only (no code ported) |
| Aves 1.15.8+182 | `4f940ae48bd936…` (`develop`) | BSD-3-Clause | Reference only (no code ported, including the `exifinterface` fork) |
| Mi Gallery | (proprietary, no pinned commit) | Proprietary | UX/feature reference only (no code, no assets, no trademark) |

## Roadmap at a glance (v2.2)

| Phase | Focus | State |
|---|---|---|
| **0** | Align scope, establish baselines | **Exited** — [sign-off](docs/phase-0/sign-off.md) |
| **1** | Source audit, integration design, contract prototype | **Delivered** — audits, matrix, ADRs, diagrams, license report; reframed as reference-only under ADR-0008 |
| **2** | Product shell, visual foundation, privacy hardening, Compose toolchain, CI | **Exited** — Compose-native shell 0.2.x ([compose-migration.md](docs/architecture/compose-migration.md)); CI as merge gate (ADR-0013); privacy gates + test suites enforced ([ADR-0014](docs/integration-decisions.md), [privacy-gate.md](docs/phase-2/privacy-gate.md)) |
| 3 | Core media access, browsing, file ops, recycle bin, hidden vault | **In progress** — photo timeline merged ([#26](https://github.com/dukeoftoad/toadGALLERY/pull/26)); albums + detail view ([#29](https://github.com/dukeoftoad/toadGALLERY/issues/29)); incremental Paging 3 timeline ([#28](https://github.com/dukeoftoad/toadGALLERY/issues/28)) |
| 4 | Aves-grade metadata layer (`androidx.exifinterface` + own IPTC/XMP wrappers) | planned |
| 5 | MI-style organisational UX (Moments, Stories, opt-in auto-albums, semantic search, collage, slideshow) | planned |
| 6 | toadGALLERY Edit v1: core adjustments + non-destructive pipeline | contract spec ready ([v1](docs/architecture/editor-contract.md)) |
| 7 | toadGALLERY Edit v2: filters and annotations | planned |
| 8 | toadGALLERY Edit v3: on-device AI features (magic erase, sky replace, bokeh, opt-in face retouch) | planned (ADR-0010) |
| 9 | Lightweight video editor (trim, mute, slow-mo, frame extract, GIF/WebP) | planned (ADR-0012) |
| 10 | Performance, compatibility, accessibility, reliability hardening | planned |
| 11 | Release readiness (F-Droid metadata, FOSS review, signing, v1.0 cut) | planned |

Progress is tracked in the repository **milestones** (one per phase) and **issues**.

## License

**GPL-3.0** — see [LICENSE](LICENSE). Per ADR-0004 (clarified by ADR-0008), this is a
**voluntary** choice that aligns with the FOSS/gallery community ethos; the product
derives from no upstream code. Third-party dependencies (AndroidX, Compose, media3,
Coil, `androidx.exifinterface`) are Apache-2.0 and attributed per their terms in
[licensing-and-attribution.md](docs/licensing-and-attribution.md). This is engineering
compliance tracking, not legal advice.
