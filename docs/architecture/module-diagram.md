# Module Diagram — toadGALLERY v2.2 architecture

**Status:** Phase 1 baseline revised 2026-10-29 for v2.2 · **Decisions:** ADR-0008
(features-only, no code ports), ADR-0009 (Compose), ADR-0013 (GitHub Actions CI) ·
Companion: `data-flow.md`, `editor-contract.md`, `compose-migration.md`

The module layout reflects ADR-0008 (no `org.fossify:commons`, no Aves `exifinterface`
fork) and ADR-0009 (Compose-native UI). The Phase 2 app shell is in transition: the
View-based + commons shell still builds, and the Compose toolchain is added alongside
it for the incremental migration tracked in `compose-migration.md`.

```mermaid
graph TD
    subgraph repo["toadGALLERY monorepo"]
        subgraph apps
            GALLERY["apps/gallery<br/>(APK: toadGALLERY)<br/>Kotlin + Compose"]
            EDIT["apps/edit-extension<br/>(APK: toadGALLERY Edit)<br/>Kotlin + Compose"]
        end
        subgraph shared["shared (Gradle libraries)"]
            MEDIACONTRACT[":shared:media-contract<br/>MediaItem model, URI identity,<br/>three-date semantics (ADR-0006)"]
            METADATA[":shared:metadata<br/>metadata service interface,<br/>field taxonomy,<br/>androidx.exifinterface + IPTC/XMP wrappers"]
            EDITCONTRACT[":shared:editor-contract<br/>intent contract v1 constants,<br/>error codes, negotiation (ADR-0007)"]
            DESIGN[":shared:design-system<br/>Compose Material 3 tokens,<br/>theme, dynamic color (ADR-0009)"]
            ML[":shared:ml-models<br/>on-device model wrappers<br/>(Phases 5, 8; ADR-0010)"]
        end
        subgraph ci[".github/workflows"]
            CI["ci.yml<br/>JDK 17 + Android SDK<br/>build + lint + test (ADR-0013)"]
        end
    end

    GALLERY --> MEDIACONTRACT
    GALLERY --> METADATA
    GALLERY --> EDITCONTRACT
    GALLERY --> DESIGN
    EDIT --> EDITCONTRACT
    EDIT --> METADATA
    EDIT --> MEDIACONTRACT
    EDIT --> DESIGN

    subgraph ext["External (all permissively licensed)"]
        COMPOSE["androidx.compose (Apache-2.0)<br/>Material 3, UI, Foundation, Navigation"]
        ANDROIDX["androidx.* (Apache-2.0)<br/>activity, fragment, core, exifinterface"]
        MEDIA3["androidx.media3 (Apache-2.0)<br/>ExoPlayer, Transformer"]
        ROOM["androidx.room (Apache-2.0)"]
        COIL["Coil (Apache-2.0)<br/>Compose-first image loader"]
    end

    GALLERY -.-> COMPOSE
    GALLERY -.-> ANDROIDX
    GALLERY -.-> MEDIA3
    GALLERY -.-> COIL
    MEDIACONTRACT -.-> ROOM
    METADATA -.-> ANDROIDX
    EDIT -.-> COMPOSE
    EDIT -.-> MEDIA3
    ML -.-> ANDROIDX

    subgraph os["Android platform"]
        MS[("MediaStore /<br/>content providers")]
    end
    GALLERY -- "content URIs only (ADR-0006)" --> MS
    EDIT -- "granted URIs only (ADR-0007)" --> MS

    subgraph upstreams["Upstreams (reference only --- ADR-0008)"]
        FOSSIFY["Fossify Gallery @ e9812299<br/>(GPL-3.0, REFERENCE ONLY)"]
        AVES["Aves libre @ 4f940ae<br/>(BSD-3-Clause, REFERENCE ONLY)"]
        MI["Mi Gallery<br/>(proprietary, REFERENCE ONLY)"]
    end
    upstreams -.->|features / UI / UX / designs only| repo
```

## Module responsibilities

| Module | Responsibility | Source under ADR-0008 |
|---|---|---|
| `:apps/gallery` | UI shell, browsing, viewer, search UI, trash/favorites, settings; orchestrates metadata + file operations | Reimplemented from scratch in Compose (FOSSIFY-REF + MI-REF) |
| `:apps/edit-extension` | Crop/rotate/flip/resize/draw/filters/adjustments/AI; export pipeline; never touches originals beyond the granted URI | Reimplemented from scratch in Compose (MI-REF) |
| `:shared:media-contract` | `MediaItem` (URI + id + three dates + type), virtual folders (favorites/trash), sort/group vocabulary | Reimplemented from scratch (AVES-REF for the model shape, ADR-0006) |
| `:shared:metadata` | `MetadataService` interface, progressive levels (basic → catalog), field taxonomy (EXIF/IPTC/XMP), capability registry | Reimplemented from scratch using `androidx.exifinterface` (ADR-0008) + own IPTC/XMP wrappers (AVES-REF for taxonomy) |
| `:shared:editor-contract` | Contract v1 constants: actions, extras, result/error codes, version negotiation | Net-new (ADR-0007) |
| `:shared:design-system` | Compose Material 3 color roles, type scale, spacing, shape; day/night; dynamic color; Mi-inspired tokens | Net-new in Compose (ADR-0009) |
| `:shared:ml-models` | On-device ML model wrappers for face clustering, object/scene tagging, semantic embedding, segmentation, inpainting, depth, sky, face retouch | Net-new (ADR-0010) |
| `.github/workflows/ci.yml` | Build, lint, test on every push and PR | Net-new (ADR-0013) |

## Deliberately NOT in the tree

- `org.fossify:commons` (ADR-0008 --- transitional in the alpha shell, removed by
  `compose-migration.md`)
- The Aves `exifinterface` fork (ADR-0008 --- use `androidx.exifinterface` instead)
- Any Flutter/Dart code (ADR-0008 re-affirms ADR-0001's no-Flutter stance)
- Any Mi Gallery code or asset (proprietary; reference only)
- `upstream/` clones (ADR-0003 --- pinned commits in docs, clones are local-only references)
- Network/cloud/casting/analytics modules (excluded by scope decision, Phase 0 sign-off)
