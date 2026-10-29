# Data-Flow Diagrams — toadGALLERY v1

**Status:** Phase 1 baseline (2026-10-29) · **Decisions:** ADR-0006 (URI-first), ADR-0001 (stack)

## 1. Media browsing & grid

```mermaid
flowchart LR
    MS[("MediaStore<br/>content provider")]
    F["Fetch layer (Kotlin)<br/>MediaStore.Files queries<br/>(patterns reused from Fossify)"]
    C[("Room cache<br/>URI-keyed (shared:media-contract)<br/>+ DateTaken corrections")]
    UI["Grid UI<br/>MediaAdapter-derived"]
    TH["Glide thumbnail<br/>pipeline (grid-size decode only)"]

    MS -- "content URIs + IDs (off main thread)" --> F
    F -- "upsert / invalidate" --> C
    F -- "MediaItem list" --> UI
    UI -- "uri → grid-size request" --> TH
    TH -- "no full-res decode in grid cells" --> MS
    C -- "favorites / sort / group state" --> UI
```

Rules enforced by this flow: no raw-path queries in the product layer; grid cells never
decode full-resolution originals (roadmap Phase 3 task); fetch work is cancellable
(`shouldStop`-style flag pattern from Fossify retained).

## 2. Metadata details (progressive enrichment — Aves pattern in Kotlin)

```mermaid
flowchart TD
    V["Viewer / details request"]
    S["MetadataService<br/>(shared:metadata interface)"]
    L1["level: basic<br/>(from MediaStore columns)"]
    L2["level: aspectRatio / dimensions"]
    L3["level: catalog<br/>(EXIF/IPTC/XMP via exifinterface-fork)"]
    L4["level: references<br/>(stacks, panorama, multipage — later phases)"]
    CACHE[("per-entry metadata cache<br/>invalidated on content change")]

    V --> S
    S --> L1 --> L2 --> L3 --> L4
    L3 -- "background parse, off main thread" --> CACHE
    S -- "read-through" --> CACHE
    L3 -.->|"unavailable ≠ absent (taxonomy-driven)"| V
```

## 3. File operations & trash (safe-by-default)

```mermaid
flowchart TD
    U["User action<br/>(delete/copy/move/restore)"]
    CONF["Explicit confirmation<br/>(destructive ops only)"]
    SAF["SAF / content-resolver write<br/>(no raw-path writes)"]
    MS2[("MediaStore / DocumentsProvider")]
    TR["Trash semantics<br/>(hybrid — finalized Phase 7)"]
    DB[("Room: favorites/trash bookkeeping")]

    U --> CONF --> SAF --> MS2
    SAF -- "status" --> TR
    TR -- "state" --> DB
    MS2 -- "change notification" --> F2["Fetch layer invalidation<br/>(no full rescans per launch)"] --> DB
```

## 4. Gallery → Edit → Gallery round trip (contract v1 — see editor-contract.md)

```mermaid
sequenceDiagram
    participant G as toadGALLERY
    participant E as toadGALLERY Edit
    Note over G: check installed + CONTRACT_VERSION meta-data
    G->>E: ACTION_EDIT + source URI (grant read) + prefs
    Note over E: operate on copies/previews only
    E-->>G: RESULT_OK + output URI (new copy in MediaStore)
    E-->>G: RESULT_CANCELED (no changes anywhere)
    E-->>G: RESULT_ERROR + structured error code
    Note over G: MediaStore observer refreshes grid — no rescan
```

## Date semantics (ADR-0006, applies to every display/sort/filter)

- `date_added` (MediaStore): import/sync time.
- `date_modified` (MediaStore): file change time (edits via non-our apps included).
- `date_taken` + tz offset (catalog): capture time — **the user-facing default for
  timeline grouping/sorting**; falls back to `date_added` when genuinely absent
  (never silently — the taxonomy distinguishes absent from unknown, Phase 4).
