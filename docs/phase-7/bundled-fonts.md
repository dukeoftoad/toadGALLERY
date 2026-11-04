# Bundled OFL Fonts — Provenance and Licensing (r34, Phase 7)

**Status:** r34 (2026-11-04) — the text-tool round's asset record, and the
execution of the licensing gate that `docs/phase-7/text-tool.md` §"Licensing
gate" makes a **blocker for the round**. This file is the per-font evidence
the roadmap's Phase 7 exit criterion points at: *"No bundled asset has an
incompatible license."*

---

## What is bundled and why these four

The text tool ships four bundled typefaces, one per style slot from the
`text-tool.md` candidate table (default humanist sans, display serif,
handwriting, mono). Each font is a **verbatim, unmodified copy** of the file
the canonical `google/fonts` repository distributes, with that family's own
`OFL.txt` committed **beside the TTF** in the same assets directory:

| Registry id | Family (internal name table) | File | Style | Bytes | Glyphs | Axes |
|---|---|---|---|---|---|---|
| `fira-sans` | Fira Sans | `FiraSans-Regular.ttf` | Regular (static) | 456,996 | 2,708 | — |
| `playfair-display` | Playfair Display | `PlayfairDisplay[wght].ttf` | Regular (variable, default instance wght 400) | 300,724 | 1,103 | wght 400–900 |
| `caveat` | Caveat | `Caveat[wght].ttf` | Regular (variable, default instance wght 400) | 403,648 | 1,034 | wght 400–700 |
| `space-mono` | Space Mono | `SpaceMono-Regular.ttf` | Regular (static) | 99,356 | 765 | — |

Total TTF payload **1,260,724 bytes (~1.2 MB)** — inside the round's budget.
Glyph and axis counts were read from the committed files themselves with
`fontTools` (the verification transcript is in the worklog Run #11 entry).

## The Noto Sans → Fira Sans slot swap (documented deviation)

The prep doc's default-sans candidate was **Noto Sans (Regular + Bold)**.
Both of its unmodified forms failed the round's own constraints:

- the `google/fonts` repository distributes Noto Sans only as the
  `NotoSans[wdth,wght].ttf` **variable font (2,049,096 bytes)** — alone it
  exceeds the round's whole-font budget by ~70%;
- the Google Fonts CSS API (legacy user agent) serves Noto Sans as a
  **~28 KB latin subset** — and the round contract forbids subsetting
  ("bundle **unmodified** TTFs (no subsetting, no rename)").

The design is explicitly font-agnostic (`text-tool.md` §1: "swap to another
OFL face; the design above is font-agnostic"), and the slot's actual
requirement is a *default humanist sans*: **Fira Sans Regular** (Erik
Spiekermann / Carrois Type Design, commissioned by the Mozilla Foundation and
Telefónica) fills it with a full-coverage static file at 457 KB. The
"Bold" file from the candidate table was dropped everywhere (no font carries
a second weight): the v1 layer model has no weight axis, and Android's
`Paint.setFakeBoldText` covers any future emphasis need without bundling a
second file. Platform font fallback handles scripts a bundled face lacks —
the bundled set is about **style**, not coverage.

## Provenance (the ledger rows' evidence)

Single canonical source for every font and license: the `google/fonts`
repository at commit **`2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc`**
(2026-11-04 HEAD; the sha256 fingerprints below are the authoritative pin —
the commit makes the URLs immutable):

| File in this repo | Upstream path (`google/fonts` @ `2fe1e8c`) | sha256 |
|---|---|---|
| `apps/edit/src/main/assets/fonts/fira-sans/FiraSans-Regular.ttf` | `ofl/firasans/FiraSans-Regular.ttf` | `c29556a2719bf613ef3d5e070e40d903a8965d9c081beca1375dc1e6e0f93c23` |
| `apps/edit/src/main/assets/fonts/fira-sans/OFL.txt` | `ofl/firasans/OFL.txt` | `8f24842e9174beda18a556c2ae7d54f5dc444340c19a3a9ef77e23bca366adbd` |
| `apps/edit/src/main/assets/fonts/playfair-display/PlayfairDisplay[wght].ttf` | `ofl/playfairdisplay/PlayfairDisplay[wght].ttf` | `c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6` |
| `apps/edit/src/main/assets/fonts/playfair-display/OFL.txt` | `ofl/playfairdisplay/OFL.txt` | `566be814f8e96e93dfa16101331557eb6b5467e9e03f627c0910fe93ca12300e` |
| `apps/edit/src/main/assets/fonts/caveat/Caveat[wght].ttf` | `ofl/caveat/Caveat[wght].ttf` | `0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988` |
| `apps/edit/src/main/assets/fonts/caveat/OFL.txt` | `ofl/caveat/OFL.txt` | `1f9d81d094273d82f3898a1ee8b598a717d050ecbf5ff7bede105b704880157b` |
| `apps/edit/src/main/assets/fonts/space-mono/SpaceMono-Regular.ttf` | `ofl/spacemono/SpaceMono-Regular.ttf` | `95837e182baeeada83368f7748db28357f0a1b75c6b84ff7065b5edf933c8e18` |
| `apps/edit/src/main/assets/fonts/space-mono/OFL.txt` | `ofl/spacemono/OFL.txt` | `8e4ee42b2553e1e01504e61cb0d46d148cd8c9e5eacaa3622a7df2d4f2955b9f` |

The corresponding rows live in `docs/licensing-and-attribution.md` §5 (one
per font, the pattern `text-tool.md` prescribes).

## License verification (from the downloaded artifacts themselves)

Every `OFL.txt` committed here was fetched from the **same upstream
directory as its TTF** and checked directly:

- all four carry the **SIL Open Font License, Version 1.1** header;
- Playfair Display, Caveat and Space Mono licenses name their family and
  project authors explicitly;
- Fira Sans's license names the copyright holders (The Mozilla Foundation
  and Telefónica S.A., 2012–2015) — the family's sole authors — and
  declares **no Reserved Font Name**; the pairing of that license with
  `FiraSans-Regular.ttf` inside `ofl/firasans/` is the canonical
  distribution.

**OFL 1.1 obligations, satisfied by construction:** the fonts are used,
bundled and redistributed **unmodified** (byte-identical; the sha256s above
are enforced by `BundledFontsTest` at CI time), so the Reserved Font Name
clauses are trivially satisfied (no Modified Version exists), and each
font's complete license text travels **with** the font (same APK assets
directory — the license accompanies every copy the app ships).

## In-app presence

The four families appear in the editor's Text tool as the font chip row
(ids `fira-sans`, `playfair-display`, `caveat`, `space-mono`; display names
in `strings_r34.xml`). Attribution beyond the OFL's requirements (a
first-run notice, a licenses screen) is **not owed by the license** and is
not bundled in v1; if the project later ships such a screen, these four
rows are its ready-made input.

## Regeneration / verification procedure

There is no generator (unlike the r33 LUTs): the bytes are upstream's. To
re-verify a committed font against its pin:

```
sha256sum apps/edit/src/main/assets/fonts/<id>/<file>
# compare against the table above / the §5 ledger row
curl -s https://raw.githubusercontent.com/google/fonts/2fe1e8c6b137fb13041a7cfca6fdcd8a910ea4dc/ofl/<family>/<file> | sha256sum
```

`BundledFontsTest` (the `BundledLutsTest` pattern) pins, on the JVM at CI
time: the registry's uniqueness/order, every committed TTF's existence +
exact sha256, and each `OFL.txt` beside its font carrying the SIL OFL 1.1
header — a swapped or corrupted font file fails the build, not the user.
