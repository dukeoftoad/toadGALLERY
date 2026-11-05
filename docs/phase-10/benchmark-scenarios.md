# Phase 10 — the repeatable benchmark scenarios (the r40 skeleton; every
# result cell is HUMAN-ONLY)

**Status:** SKELETON (r40, 2026-11-05, branch `tg-p10-hardening`). This
doc is the roadmap Phase 10 deliverable "repeatable benchmark scenarios
and baseline results" — the SCENARIOS are the r40 round's code-side
half; the RESULTS are the on-device half and are never claimed by a
code round (the standing directive: the §-numbered device rows are
human-only). Nothing in this file invents a measurement.

---

## Purpose

The roadmap's hardening task list says: *measure cold launch, first
usable content, scroll smoothness, memory, thumbnail cache behavior,
search latency, metadata extraction, and editor open-to-first-preview
time* — and the profiling rule: *profile startup and scrolling before
optimizing*. This file turns each named target into a scenario a human
can execute identically across runs, with the fields to record and the
pass target from the roadmap's own "Suggested initial targets" section.
The Baseline Profiles decision ("consider ... if beneficial") is
DEFERRED with the results: the profiling that would justify them is a
device-only act (see the deferral note at the bottom).

## The defined test devices (declared, HUMAN-ONLY)

The roadmap's targets attach to a *defined* mid-range test device. The
definition is recorded here so every scenario says the same word and
means the same hardware; the actual device instance, its exact SoC/RAM,
and every number measured on it are the human-only part.

| id | class | definition the scenarios attach to | status |
| :--- | :--- | :--- | :--- |
| BM-1 | mid-range phone | the dogfood device the §-checklist already uses; the roadmap's "defined mid-range test device" | device instance + results: HUMAN-ONLY |
| BM-2 | low-range phone | the slowest device the compatibility matrix covers (see `device-matrix.md`) | device instance + results: HUMAN-ONLY |

## The defined test libraries

The roadmap says *test libraries of approximately 10,000 and 50,000
items, with realistic media mixtures*. The composition is specified so
the two benchmark runs are comparable; the media set itself is built
on-device (human-only).

| id | items | composition target (the mixture the builder aims for) | status |
| :--- | :--- | :--- | :--- |
| L10K | ~10,000 | the Phase 3 representative set: majority phone-camera JPEGs (12 MP class), ~15% video (mixed 1080p/4K), ~5% unusual formats (HEIC, PNG, GIF), EXIF present on the photo majority, some no-EXIF and some corrupt rows by design | set build: HUMAN-ONLY |
| L50K | ~50,000 | the same ratios scaled, plus the long-tail the 50k row count stresses: deep folders, duplicate-looking names, long-video outliers (10+ min) | set build: HUMAN-ONLY |

## The scenarios

Each scenario: the steps are human-executable as written (the tooling
names are suggestions, not shipped code — this sandbox has no Android
SDK), the record fields are what the result row carries, the target is
the roadmap's own number where it has one. Every scenario runs on BM-1
and BM-2 against L10K and L50K.

| id | scenario | steps (repeatable shape) | record | target (roadmap) |
| :--- | :--- | :--- | :--- | :--- |
| S1 | Cold launch → first usable content | kill the app; `adb shell am start` the gallery; measure to the first populated timeline frame (a screen-record frame-step, or Macrobenchmark `TimeToInitialDisplay` + the content-populated frame) | ms; scan state at first content (idle vs pass running); L10K and L50K rows | about 2 s on BM-1; no full-library rescan on every launch |
| S2 | Scroll smoothness (the grid) | from a settled timeline, fling 10 screens down and back, 3 runs; record frame timings (Perfetto / `dumpsys gfxinfo` framestats over the fling window) | jank %, p50/p90/p99 frame ms; dropped-frame count | smooth thumbnail scrolling under the documented representative set |
| S3 | Memory + thumbnail cache behavior | repeat S2 with a memory trace; then rotate/freeze-proof: background the app, return, re-measure | peak RSS/PSS during scroll; cache hit ratio if instrumented; OOM/stall events | no full-resolution decode for ordinary grid thumbnails |
| S4 | Search latency | 10 searches against indexed fields (name contains, date jump, album, favorite, people, magic-search phrase) from the search entry, 3 runs each | per-query ms to first result page; perceived-immediate note (the roadmap's word) | search on indexed fields feels immediate on the target library size |
| S5 | Metadata extraction | open details on 20 sampled items spanning formats (JPEG/HEIC/video), cold each time | per-open ms; any format that falls off the fast path | (no numeric target — the record builds the format table) |
| S6 | Editor open → first preview | launch the editor on a 12 MP JPEG from the grid, cold each time, 5 runs | ms to first preview frame | about 1 s on BM-1 |
| S7 | Editor v2 stack end-to-end | open the same 12 MP item; apply one LUT + one text + one sticker + one stroke + one region + one frame (the r35–r38 composite), preview + export | preview interaction ms; export wall ms; output byte size | (records build the v2 cost table; the JVM pipeline tests stay the correctness gate) |
| S8 | Background scan boundedness | trigger a full rescan (settings entry) on L50K; cancel it mid-walk; relaunch and cancel again during app-start pass | wall ms to cancellation actually stopping the walk (the r40 `ScanWalkPolicy` probe's device confirmation); mutex-release observed; scan resumed cleanly | background work bounded, cancellable, no unnecessary device wake |

## The measurement discipline

1. **Profile before optimizing** (the roadmap's rule): the S1/S2/S6
   baselines are recorded BEFORE any optimization PR is authored; an
   optimization round cites the baseline row it moves.
2. **The result cells are human-only.** A code round may EXTEND this
   file's scenario table (a new named scenario follows the same shape);
   it never fills a result cell.
3. **Same device, same build shape, same library** per comparison; any
   deviation is recorded in the row.
4. Results ride the device-validation checklist window when the dogfood
   round runs (the §22–§27 pattern: the checklist is never edited by a
   code round).

## The Baseline Profiles deferral (recorded)

The roadmap's "consider Baseline Profiles if beneficial" stays OPEN
until S1/S2/S6 baselines exist: the decision needs the startup/scroll
profile evidence only a device run produces. If the profiles land, the
follow-up register carries the debt item; nothing in the build carries
a speculative profile today (a baseline profile without measurement is
exactly the un-evidenced change the hardening round exists to prevent).
