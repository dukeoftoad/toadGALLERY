#!/usr/bin/env python3
"""Generates the five bundled .cube LUTs for toadGALLERY Edit (r33, Phase 7).

Every bundled look is PROJECT-AUTHORED: this script is the single source
of the data, the formulas below are the whole look, and running it again
reproduces the committed files byte-for-byte (deterministic: no clocks,
no randomness, no network). No third-party LUT data is involved, so the
bundle is GPL-3.0-compatible by construction (docs/phase-7/bundled-luts.md
records the formulas and the authorship statement).

Grid: LUT_3D_SIZE 17 (4913 samples; ~130 KB per look) --- smooth for
parametric looks, small enough for five of them in the APK.

Usage: python3 tools/gen_bundled_luts.py   (writes apps/edit/src/main/assets/luts/)
"""
import os

SIZE = 17
OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    os.pardir, "apps", "edit", "src", "main", "assets", "luts",
)


def clamp01(v: float) -> float:
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def luma(r: float, g: float, b: float) -> float:
    return 0.299 * r + 0.587 * g + 0.114 * b


def contrast(v: float, amount: float) -> float:
    return 0.5 + (v - 0.5) * amount


# ---- The five looks (the formulas ARE the documentation) ----

def warm_film(r: float, g: float, b: float):
    # A warm film-stock feel: gentle S-contrast, shadows lifted off black,
    # red pushed and blue pulled in proportion to brightness.
    def ch(v: float, gain: float) -> float:
        out = contrast(v, 1.12)
        out = out + 0.045 * (1.0 - out)  # lifted shadow floor
        return clamp01(out * gain)

    return ch(r, 1.06), ch(g, 1.00), ch(b, 0.92)


def cool_chrome(r: float, g: float, b: float):
    # Cool and crisp: deeper contrast, blues boosted, reds held back.
    return (
        clamp01(contrast(r, 1.18) * 0.94),
        clamp01(contrast(g, 1.18) * 1.00),
        clamp01(contrast(b, 1.18) * 1.07),
    )


def silver_mono(r: float, g: float, b: float):
    # Black-and-white from the rec.601 luma, with a touch of punch.
    m = clamp01(contrast(luma(r, g, b), 1.08))
    return m, m, m


def matte_fade(r: float, g: float, b: float):
    # Faded matte print: desaturated, blacks lifted to a grey floor,
    # whites held below paper-white.
    y = luma(r, g, b)
    out = tuple(0.09 + (y + (c - y) * 0.75) * 0.83 for c in (r, g, b))
    return tuple(clamp01(c) for c in out)


def vivid_punch(r: float, g: float, b: float):
    # Saturated and punchy: colours pushed away from luma, mild contrast.
    y = luma(r, g, b)
    return tuple(
        clamp01(contrast(y + (c - y) * 1.28, 1.10)) for c in (r, g, b)
    )


LOOKS = [
    ("warm-film", "toadGALLERY Warm Film", warm_film),
    ("cool-chrome", "toadGALLERY Cool Chrome", cool_chrome),
    ("silver-mono", "toadGALLERY Silver Mono", silver_mono),
    ("matte-fade", "toadGALLERY Matte Fade", matte_fade),
    ("vivid-punch", "toadGALLERY Vivid Punch", vivid_punch),
]


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    for lut_id, title, fn in LOOKS:
        lines = [
            f'TITLE "{title}"',
            f"LUT_3D_SIZE {SIZE}",
            "DOMAIN_MIN 0.0 0.0 0.0",
            "DOMAIN_MAX 1.0 1.0 1.0",
            "# Project-authored by tools/gen_bundled_luts.py (r33).",
            f"# id: {lut_id} --- deterministic regeneration, no third-party data.",
        ]
        # R index increments fastest (the .cube table order this engine parses).
        for b in range(SIZE):
            for g in range(SIZE):
                for r in range(SIZE):
                    ro, go, bo = fn(r / (SIZE - 1), g / (SIZE - 1), b / (SIZE - 1))
                    lines.append(f"{ro:.6f} {go:.6f} {bo:.6f}")
        path = os.path.join(OUT_DIR, f"{lut_id}.cube")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"wrote {path} ({len(lines) - 6} samples)")


if __name__ == "__main__":
    main()
