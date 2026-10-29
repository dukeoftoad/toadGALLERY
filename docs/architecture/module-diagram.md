# Module Diagram — toadGALLERY v1 architecture

**Status:** Phase 1 baseline (2026-10-29) · **Decisions:** ADR-0001, 0005, 0006 ·
Companion: `data-flow.md`, `editor-contract.md`

The module layout validates the roadmap §3 proposal against the audit evidence:
`shared/` shrinks to what is *technically justified* (contracts + metadata), `upstream/`
stays out of the tree (ADR-0003), and both apps are Gradle modules in one repo.

```mermaid
graph TD
    subgraph repo["toadGALLERY monorepo"]
        subgraph apps
            GALLERY["apps/gallery<br/>(APK: toadGALLERY)"]
            EDIT["apps/edit-extension<br/>(APK: toadGALLERY Edit)"]
        end
        subgraph shared["shared (Gradle libraries)"]
            MEDIACONTRACT[":shared:media-contract<br/>MediaItem model, URI identity,<br/>three-date semantics"]
            METADATA[":shared:metadata<br/>metadata service interface,<br/>field taxonomy"]
            EDITCONTRACT[":shared:editor-contract<br/>intent contract v1 constants,<br/>error codes, negotiation"]
            DESIGN[":shared:design-system<br/>theme tokens, shared attrs"]
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

    subgraph ext["External"]
        COMMONS["org.fossify:commons (GPL-3.0)<br/>base activities, SAF file ops, theming"]
        GLIDE["Glide + decoder set"]
        MEDIA3["androidx.media3 (ExoPlayer)"]
        ROOM["androidx.room"]
        EXIFFORK["exifinterface-fork (Apache-2.0)<br/>adapted from Aves — provenance recorded"]
    end

    GALLERY -.-> COMMONS
    GALLERY -.-> GLIDE
    GALLERY -.-> MEDIA3
    MEDIACONTRACT -.-> ROOM
    METADATA -.-> EXIFFORK
    EDIT -.-> GLIDE

    subgraph os["Android platform"]
        MS[("MediaStore /<br/>content providers")]
    end
    GALLERY -- "content URIs only (ADR-0006)" --> MS
    EDIT -- "granted URIs only (ADR-0007)" --> MS
```

## Module responsibilities

| Module | Responsibility | Source |
|---|---|---|
| `:apps/gallery` | UI shell, browsing, viewer, search UI, trash/favorites, settings; orchestrates metadata + file operations | Keep Fossify skeleton (ADR-0001); URI-first model (ADR-0006) |
| `:apps/edit-extension` | Crop/rotate/flip/resize/draw/filters; export pipeline; never touches originals beyond the granted URI | Adapt Fossify editor extraction (ADR-0002) |
| `:shared:media-contract` | `MediaItem` (URI + id + three dates + type), virtual folders (favorites/trash), sort/group vocabulary | Reimplement from Aves `AvesEntry` semantics |
| `:shared:metadata` | `MetadataService` interface, progressive levels (basic → catalog), field taxonomy (EXIF/IPTC/XMP), capability registry | Reimplement Aves architecture; adapt exifinterface fork |
| `:shared:editor-contract` | Contract v1 constants: actions, extras, result/error codes, version negotiation | ADR-0007 |
| `:shared:design-system` | Color roles, type scale, spacing, shape; day/night; Mi-inspired tokens | Phase 2 deliverable |

## Deliberately NOT in the tree

- `upstream/` clones (ADR-0003: pinned commits in docs, clones are local-only)
- Any Flutter/Dart code (ADR-0001)
- Network/cloud/casting/analytics modules (excluded by scope decision, Phase 0 sign-off)
