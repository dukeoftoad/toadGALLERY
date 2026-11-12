package com.toadgallery.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// The editor's Material 3 theme --- r63b (the chrome round: violet retires).
// ---------------------------------------------------------------------------
//
// r63b history: the r53 re-pin put the photo editor into the r52 video
// violet (the "one app, one design language" move), and the approved r63
// proposal undoes the SPLIT the other way --- every violet role re-pins to
// the gallery's toad-green ToadDarkColors family (apps/gallery ...
// ui/theme/Theme.kt:80-110), per the r63 proposal's §3 hex table. The fixed
// contract STANDS: the scheme is still deterministic and NOT dynamic color
// (the r47/ADR-0009 promise --- no wallpaper-derived hue can leak in), the
// canvas matting stays pure #000000, and the r52 selection mechanism
// survives re-colored: secondaryContainer = primary, so a SELECTED
// FilterChip renders as the #9CD8A2 accent-filled pill with its label in
// #00390F (8.0:1) with zero per-chip overrides at the call sites.
//
// F-69 structure (this round, per the approved ledger plan): the hexes no
// longer live loose in a darkColorScheme call --- they live in a named
// [EditorPalette] registry below, with the toad-green set as the FIXED
// DEFAULT. The future Settings accent tri-state (toad-green / wallpaper /
// mono) plugs in here; NO UI may switch palettes yet --- structure only.
// The palette-invariant roles (the error family, scrim, the canvas matting)
// stay outside the registry: no accent choice may move them.

/**
 * The editor's palette registry (F-69): every accent-and-neutral role the
 * editor's dark scheme needs, as named data. The r63b toad-green set is the
 * fixed default ([EditorPalette.Toad]); the planned Settings tri-state
 * (toad-green / wallpaper / mono) would add further instances here and pass
 * them through [EditorTheme] --- until that round ships, every call site
 * MUST use the default and nothing may switch palettes at runtime.
 *
 * Roles the registry deliberately does NOT carry (they are accent- and
 * wallpaper-invariant by contract): the error family, the scrim, and the
 * canvas matting black --- those stay pinned in this file.
 */
internal data class EditorPalette(
    val name: String,
    // ---- The accent family ----
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    // ---- The warm near-black neutrals ----
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
) {
    companion object {
        /**
         * The r67 default: the Lilypad family (the editor-rebirth design
         * language, docs/editor/lilypad-design.md) --- one cool-neutral
         * ladder (pond/pad/pad-raised/hairline below, reed/paper above)
         * with the SINGLE ember accent reserved for meaning: selection,
         * the live value, the CTA. Every hex is a LilypadTokens citizen
         * (the pure-Kotlin single source of truth; LilypadTokensTest pins
         * the ladder's WCAG arithmetic), so the palette can never drift
         * from the documented language. EMBER on PAD reads ~6.4:1; PAPER
         * on PAD ~15.9:1; REED on PAD ~6.8:1.
         */
        val Lilypad = EditorPalette(
            name = "lilypad",
            primary = Color(LilypadTokens.EMBER.toInt()),
            onPrimary = Color(LilypadTokens.ON_EMBER.toInt()),
            primaryContainer = Color(LilypadTokens.Roles.EMBER_DEEP.toInt()),
            onPrimaryContainer = Color(LilypadTokens.Roles.ON_EMBER_CONTAINER.toInt()),
            inversePrimary = Color(LilypadTokens.Roles.EMBER_DIM.toInt()),
            secondary = Color(LilypadTokens.Roles.STEEL.toInt()),
            onSecondary = Color(LilypadTokens.Roles.ON_STEEL.toInt()),
            secondaryContainer = Color(LilypadTokens.Roles.STEEL_DEEP.toInt()),
            onSecondaryContainer = Color(LilypadTokens.Roles.ON_EMBER_CONTAINER.toInt()),
            tertiary = Color(LilypadTokens.REED.toInt()),
            onTertiary = Color(LilypadTokens.PAD.toInt()),
            tertiaryContainer = Color(LilypadTokens.HAIRLINE.toInt()),
            onTertiaryContainer = Color(LilypadTokens.Roles.PAPER_SOFT.toInt()),
            background = Color(LilypadTokens.PAD.toInt()),
            onBackground = Color(LilypadTokens.PAPER.toInt()),
            surfaceVariant = Color(LilypadTokens.PAD_RAISED.toInt()),
            onSurfaceVariant = Color(LilypadTokens.REED.toInt()),
            outline = Color(LilypadTokens.Roles.OUTLINE.toInt()),
            outlineVariant = Color(LilypadTokens.HAIRLINE.toInt()),
        )

        /**
         * The registry's precedent entry (the r63b default until r67):
         * the gallery's ToadDarkColors family, hex-for-hex. Kept as the
         * documented fallback lineage --- the Lilypad round's revert seam
         * reads it, nothing else does.
         */
        val Toad = EditorPalette(
            name = "toad",
            primary = Color(0xFF9CD8A2),
            onPrimary = Color(0xFF00390F),
            primaryContainer = Color(0xFF14522A),
            onPrimaryContainer = Color(0xFFEAF6EC),
            inversePrimary = Color(0xFF2E7A3C),
            secondary = Color(0xFFB9CCB3),
            onSecondary = Color(0xFF253424),
            onSecondaryContainer = Color(0xFFD4E8D0),
            tertiary = Color(0xFFB3CCB4),
            onTertiary = Color(0xFF1F3524),
            tertiaryContainer = Color(0xFF35543A),
            onTertiaryContainer = Color(0xFFCCEBCB),
            background = Color(0xFF151512),
            onBackground = Color(0xFFE5E2D8),
            surfaceVariant = Color(0xFF3A3830),
            onSurfaceVariant = Color(0xFFC6C2B6),
            outline = Color(0xFF918F85),
            outlineVariant = Color(0xFF46453F),
        )
    }
}

// Palette-invariant roles (see [EditorPalette]): the error family is
// already toad's (the §3 table: "identical"), the scrim is pure black, and
// inverseOnSurface is the gallery's fixed inversion tone. The canvas
// matting black is not a scheme role at all --- EditActivity paints it.
private val EditorError = Color(0xFFF2B8B5)
private val EditorOnError = Color(0xFF601C10)
private val EditorErrorContainer = Color(0xFF8C1D18)
private val EditorOnErrorContainer = Color(0xFFF9DEDC)
private val EditorScrim = Color(0xFF000000)
private val EditorInverseOnSurface = Color(0xFF1D1C17)

/**
 * The fixed dark [ColorScheme] a palette produces. The r52 selection
 * mechanism lives HERE, once, for every palette: `secondaryContainer` =
 * [EditorPalette.primary] and `onSecondaryContainer` = [EditorPalette.onPrimary],
 * so a SELECTED FilterChip is always the accent-filled pill with the
 * on-accent label --- re-colored with the registry, re-broken by no one.
 */
internal fun EditorPalette.toDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    // The r52 mechanism (re-colored r63b): a SELECTED FilterChip fills with
    // the accent and reads its label in OnAccent --- the design's
    // selected-chip pill, with zero per-chip overrides at the call sites.
    secondaryContainer = primary,
    onSecondaryContainer = onPrimary,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = background,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = onBackground,
    inverseOnSurface = EditorInverseOnSurface,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = EditorScrim,
    error = EditorError,
    onError = EditorOnError,
    errorContainer = EditorErrorContainer,
    onErrorContainer = EditorOnErrorContainer,
)

/**
 * The editor theme every `setContent` in this app wraps its content in:
 * the r67 LILYPAD palette (the editor-rebirth design language) over the
 * deliberately fixed-dark chrome (the photo canvas is always the pond;
 * the scheme carries the accents, sliders, chips and controls). Same
 * fixed-deterministic contract since r47 --- no dynamic color, ever ---
 * with the family re-pinned to the one-neutral-plus-one-ember ladder per
 * docs/editor/lilypad-design.md, and the r67 Manrope/JetBrains-Mono type
 * scale ([LilypadTypography]) riding along.
 *
 * The [palette] parameter is the F-69 plug-in point: every call site
 * (and every test) uses the Lilypad default; the Toad entry stays in the
 * registry as the documented lineage.
 */
@Composable
internal fun EditorTheme(
    palette: EditorPalette = EditorPalette.Lilypad,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = palette.toDarkColorScheme(),
        typography = LilypadTypography,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// r67: the Lilypad TYPOGRAPHY. The design language's two families (the
// rebirth plan, docs/editor/lilypad-design.md): Manrope (OFL) carries
// display and body; JetBrains Mono (OFL) carries every NUMBER a control
// shows. Both are bundled at res/font (static instances of the variable
// masters, instanced at weights 400/600/800 and 600), with their OFL
// texts at docs/licenses/ and the licensing ledger updated --- every
// borrowed asset attributable, per the repo's license discipline.
//
// The four NAMED stops (LilypadTokens' TYPE_ pins) fill material3's
// fifteen slots: display 96/800 lands in the displayLarge run, section
// 30/800 in headlineMedium, body 15/400 in bodyLarge, and the VALUE stop
// is the mono twin [LilypadValueStyle] --- labelMedium's size, the mono
// face --- that every value pill sets. Weights read as the scale's spine:
// 800 shouts, 600 labels, 400 reads.
//
// (r67 recovery note: this section was first restored with a platform-
// monospace fallback after the round's working tree was lost mid-run;
// the fonts have since been re-bundled and this is the full bridge the
// plan specifies.)
// ---------------------------------------------------------------------------

/** Manrope at the three weights the scale uses (400 reads, 600 labels, 800 shouts). */
internal val LilypadTypefaces: FontFamily = FontFamily(
    Font(R.font.manrope_400, FontWeight.W400),
    Font(R.font.manrope_600, FontWeight.W600),
    Font(R.font.manrope_800, FontWeight.W800),
)

/** JetBrains Mono at the value weight: the face every number sets in. */
internal val LilypadMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_600, FontWeight.W600),
)

/**
 * The Lilypad VALUE stop (LilypadTokens.TYPE_VALUE_SP / TYPE_VALUE_WEIGHT):
 * the style every control readout sets its NUMBER in --- the ProSlider's
 * value pill, the counters, the timecodes --- mono 12sp/600, so counting
 * digits never jitter the layout (a tool readout, not prose).
 */
internal val LilypadValueStyle: TextStyle = TextStyle(
    fontFamily = LilypadMono,
    fontSize = LilypadTokens.TYPE_VALUE_SP.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight(LilypadTokens.TYPE_VALUE_WEIGHT),
)

/**
 * The r67 type scale: material3's fifteen roles on Manrope, filled from
 * the four Lilypad stops. Display and headline roles carry the 800
 * spine with a +4sp leading; titles are the 600 workhorses; bodies read
 * at 400; labels are compact 600 (the control surface's voice).
 */
private val LilypadTypography = Typography(
    displayLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = LilypadTokens.TYPE_DISPLAY_SP.sp, lineHeight = (LilypadTokens.TYPE_DISPLAY_SP + 4).sp),
    displayMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 56.sp, lineHeight = 60.sp),
    displaySmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 44.sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 36.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = LilypadTokens.TYPE_SECTION_SP.sp, lineHeight = (LilypadTokens.TYPE_SECTION_SP + 4).sp),
    headlineSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W800, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight(LilypadTokens.TYPE_BODY_WEIGHT), fontSize = LilypadTokens.TYPE_BODY_SP.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontFamily = LilypadTypefaces, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 13.sp),
)
