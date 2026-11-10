# toadGALLERY — Project Architecture & Delivery Policy

**Status:** Authoritative project-level source of truth  
**Applies to:** Product boundaries, branch topology, integration, release channels, model distribution, and GitHub Actions responsibilities  
**Last updated:** 2026-11-10

> This document records the intended architecture and operating rules for the project. Workflows, branch documentation, release notes, and implementation decisions must align with it. If an older document or workflow comment conflicts with this policy, this document takes precedence until that material is updated.

---

## 1. Vision and goals

toadGALLERY is a family of related Android products developed from a shared codebase. The project should allow people to use either a focused product or one combined application containing the complete feature set.

The delivery system must:

1. Keep product work independently developable on integration branches.
2. Bring those contributions together on `unified`, where the complete product family is integrated and validated.
3. use `unified` for alpha and beta development and release candidates.
4. Promote only an approved stable state from `unified` to `main`.
5. Treat `main` as the stable release line—not as the everyday integration branch.
6. Keep Ente upstream synchronization selective and occasional, without turning Ente into a fifth project product.
7. Make CI prove that the product builds and their important packaging contracts are valid.
8. Keep release signing, provenance, and artifact publication deliberate and auditable.

The architecture is intentionally a **many-to-one integration flow followed by a controlled stable promotion**.

## 2. Product family

The project has four products. These are the intended user-facing build outputs, not four separate long-lived release branches.

| Product | Purpose |
|---|---|
| **Basic Gallery** | The focused gallery application without the optional Gallery Engine native/AI capability set. |
| **Gallery Engine** | The gallery application with the Gallery Engine capabilities and required native runtime. |
| **Editor** | The standalone companion editor application. |
| **Unified** | One combined application containing the capabilities of the product family in a single APK. |

### Product boundaries

- Basic Gallery and Gallery Engine are two product variants of the gallery experience.
- Editor remains a separately installable product.
- Unified is the combined, all-capabilities application. It is not merely a branch name or a release label.
- Semantic and face capabilities are features/capabilities, not additional top-level products when delivered as part of Gallery Engine or Unified.
- Do not create a separate Ente product slot in the project’s four-product bundle.

Each product may retain its own application ID, versioning needs, build configuration, and packaging checks. Those implementation details must not change the four-product architecture without an explicit project decision.

## 3. Branch topology and direction of flow

### 3.1 Branch roles

| Branch | Role |
|---|---|
| `gallery` | Integration/development branch for Gallery product work. |
| `editor` | Integration/development branch for the standalone Editor. |
| `ente` | Upstream Ente synchronization and compatibility/integration work. This is an upstream source lane, not a fifth product lane. |
| Other feature/integration branches | Focused work that is intended to contribute to the complete application family. |
| **`unified`** | Primary integration branch. Receives contributions from the product and feature integration branches. Hosts the combined application and alpha/beta lifecycle. |
| **`main`** | Stable branch. Receives approved stable promotion from `unified` and is the source of stable releases. |

### 3.2 Required flow

```text
gallery ─────┐
editor ──────┤
ente sync ───┼── PR / reviewed integration ──▶ unified
features ────┘                                  │
                                               │ alpha
                                               │ beta
                                               │ stabilization
                                               ▼
                                  approved stable promotion
                                               │
                                               ▼
                                             main
                                               │
                                               ▼
                                         stable releases
```

The direction is intentional:

1. Product and feature branches contribute **into `unified`**.
2. `unified` is where cross-product integration is resolved and tested.
3. Alpha and beta versions are cut from `unified`.
4. Once a release candidate is accepted as stable, its reviewed state is promoted from `unified` to `main`.
5. Stable releases are cut from `main`.

### 3.3 Promotion guardrails

- Never treat `main` as the normal destination for unfinished product integration.
- Never bypass `unified` when promoting ordinary product or feature work to stable.
- The `unified` → `main` promotion must be deliberate and reviewable. Use a human-reviewed PR/promotion unless the maintainer explicitly changes this policy.
- CI must validate the promotion candidate before merge. A post-merge workflow on `main` is useful verification, but it does **not** replace pre-merge PR protection.
- Do not automatically merge or promote `unified` to `main` merely because a build passed. Stable promotion is a release decision, not just a CI event.
- Backports from `main` to integration branches should be intentional and reviewed; they must not reverse the normal flow.

## 4. Release channels

### 4.1 Alpha and beta

- Alpha and beta development belongs to `unified`.
- Alpha builds are integration feedback builds and may contain incomplete or changing features.
- Beta builds are stabilization candidates and should represent a coherent, tested combination of the product family.
- Release tags and release notes must identify the channel and source commit.
- A beta is not automatically stable. Stable promotion requires an explicit maintainer decision.

### 4.2 Stable

- Stable releases are built from `main`.
- `main` must contain the approved stable state promoted from `unified`.
- Stable tags must identify the exact source commit and must never be silently moved or reused.
- Release publication should be a deliberate, auditable action.
- The stable release family consists of the four defined products. Do not add an Ente fifth APK to that family.

### 4.3 Product consistency across channels

The four products define the product family for both development and stable delivery. The release workflow configuration must state clearly which products are published for each channel. Any deliberate difference between alpha/beta and stable artifact sets must be documented rather than arising accidentally from workflow drift.

## 5. Ente upstream integration policy

Ente is an upstream project/source that may be synchronized into the project when useful. It is not a fifth toadGALLERY product.

- Ente synchronization is occasional: approximately annual or when a meaningful upstream change warrants it. It is not a daily development or routine release lane.
- Review upstream changes selectively. Do not blindly import every upstream change.
- Validate relevant changes, compatibility, licensing/provenance requirements, and project-specific behavior before integrating them into `unified`.
- The `ente` branch may be used to stage and validate upstream synchronization before its contribution is merged into `unified`.
- Keep CI that protects Ente synchronization, but do not make Ente a separate product in the four-product release bundle.
- Users who want the official Ente Gallery distribution can obtain it directly from Ente. The project does not need to mirror that distribution as its own product release.

## 6. AI and ONNX model distribution

Model distribution is determined by model ownership/source and product requirements. There is **no blanket rule prohibiting model weights in APKs**.

### Ente models

- Ente Gallery AI model weights are downloaded on demand.
- Do not bundle Ente model weights into the project APKs as part of the normal product packaging policy.
- The download path must remain explicit, user-respecting, and consistent with the app's privacy/network permission policy.

### Non-Ente models

- Editor AI ONNX models and other non-Ente ONNX model weights may be bundled in APKs where the product requires them.
- The standalone Editor may include its applicable non-Ente model assets.
- Unified must include all applicable non-Ente model assets required to provide its combined offline feature set.
- Gallery Engine's exact non-Ente model bundle should follow its documented product requirements; do not infer that it must bundle every Editor model merely because Unified does.
- CI should verify the intended model assets for each product: required assets are present, Ente model weights are not accidentally bundled, and model checksums/provenance are validated where applicable.

Any change to which product bundles a particular model should be documented as a product/packaging decision.

## 7. GitHub Actions architecture

Workflows must enforce the branch flow above. They are implementation mechanisms, not the source of architectural truth.

### 7.1 Integration branch CI

- `gallery-ci.yml`: validate changes on the Gallery integration branch, including relevant shared and engine dependencies.
- `editor-ci.yml`: validate Editor builds, lint, tests, and its shared contracts.
- `ente-ci.yml`: validate an occasional Ente upstream sync and its relevant native/build integration.
- Feature-specific CI: validate the dependencies and contracts of the feature branch it protects.

Path filters must include all relevant dependency paths. A workflow must not silently skip validation when a changed shared module, native engine, model manifest, build script, or Gradle configuration affects its target.

### 7.2 Unified integration CI

`unified-ci.yml` is the principal integration gate for `unified`.

It should validate the integrated product family, not only the Unified APK. At minimum, the gate should cover:

- Basic Gallery build and relevant tests.
- Gallery Engine build and native-library packaging checks.
- Editor build, lint, tests, and shared editor contracts.
- Unified build and native-library packaging checks.
- Required model-asset presence/absence and integrity checks according to Section 6.
- Relevant Rust/native checks and shared unit/instrumentation tests.

A successful Unified APK build alone does not prove that the other three products still build correctly.

### 7.3 Stable promotion CI

The PR from `unified` to `main` must receive the required integration checks before it can merge. The checks may be reused from the Unified CI design, but the promotion must be tied to the exact candidate commit being reviewed.

The post-merge `main-release.yml` workflow should verify the stable branch after promotion. It is a post-merge safety net and must not be described as the only gate for the promotion PR.

### 7.4 Release workflows

- Alpha/beta release workflows must build from `unified`.
- Stable release workflows must build from `main`.
- Release workflows must pin the intended source branch/ref and record the source SHA.
- Keep release publication separate from ordinary CI.
- Signing secrets must remain in GitHub Encrypted Secrets and be passed only through the approved environment contract.
- Every published APK must pass signature verification against the expected release certificate.
- Packaging gates must verify both required native libraries and required/forbidden model assets.
- Preserve per-product checksums and R8 mapping files for every minified release APK.
- Do not publish an artifact if its build, signature, packaging, or required mapping gate fails.
- Release workflow comments and documentation must describe the actual four-product set and correct source branch/channel.

## 8. Workflow audit implications

The following are policy-alignment requirements for the current workflow set. They are recommendations for workflow maintenance; this document commit does not itself modify workflows.

1. Rework `unified-ci.yml` so it validates all four products and their packaging contracts.
2. Ensure `ci.yml` or an equivalent required-check set protects the `unified` → `main` PR before merge.
3. Keep `main-release.yml` as post-merge stable verification, but align its product matrix with Basic Gallery, Gallery Engine, Editor, and Unified.
4. Align `release-main.yml` with stable releases from `main`; remove the Ente fifth-product release concept.
5. Align `release-unified.yml` with alpha/beta releases from `unified` and make its artifact set explicit.
6. Retain focused integration CI where it provides useful branch protection.
7. Reassess independent `release-gallery.yml`, `release-editor.yml`, and `release-ente.yml` pipelines. They must not create competing release channels or imply that integration branches are stable release sources. Keep a branch-specific release only if there is a documented testing need.
8. Retire or clearly quarantine obsolete workflows whose Gradle targets no longer exist. A workflow described as superseded should not remain a confusing, apparently supported release entry point.
9. Update older branch-strategy documents and workflow comments that describe `main` as the first integration destination, Ente as a product, or the family as five APKs.

## 9. Definition of done for a stable promotion

A candidate is eligible for promotion from `unified` to `main` only when:

- The intended feature/product contributions have been reviewed and integrated into `unified`.
- The full four-product integration CI is green on the exact candidate SHA.
- Required tests and native engine checks are green.
- Model packaging matches the Ente/non-Ente policy in Section 6.
- Alpha/beta feedback has been considered and release-blocking issues are resolved.
- Versioning, release notes, and provenance identify the candidate accurately.
- A maintainer explicitly approves the stable promotion.

After promotion, the stable verification workflow on `main` must pass before the stable release is published.

## 10. Change control and source of truth

This document is the project-level authority for:

- product count and product boundaries;
- branch roles and merge direction;
- alpha/beta versus stable release sources;
- Ente upstream integration and distribution boundaries;
- Ente versus non-Ente model packaging;
- CI and release workflow responsibilities.

Implementation details may evolve, but changes to these rules require an intentional update to this document. When changing the policy, update affected workflows and related documentation in the same change or in a clearly linked follow-up. Do not leave contradictory descriptions in active workflow comments or operator runbooks.

---

**Canonical flow:** integration branches → `unified` (alpha/beta) → approved stable promotion → `main` (stable releases).  
**Canonical product family:** Basic Gallery · Gallery Engine · Editor · Unified.  
**Ente:** occasional upstream integration, not a fifth product.
