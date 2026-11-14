package com.toadgallery.editor

// ---------------------------------------------------------------------------
// r67: the Lilypad foundation round (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the design system as PURE DATA.
//
// What this file is: every number the new language needs, in ONE object,
// with ZERO compose/android imports. Three worlds read it and none may
// fork it: the Compose bridge (EditorTheme.kt's [EditorPalette.Lilypad]
// and the LilypadTypography this round adds), the JVM test harness
// (LilypadTokensTest pins the ladder's WCAG arithmetic where the data is
// pure --- the ADR-0011 split: pure logic tested pure), and the r67
// surfaces themselves (the Deck's sheet states, the ProSlider board's
// pills --- they take corners, motion and haptics from HERE, never a
// local literal). The colors are ARGB Longs (0xFF......) so the exact
// sRGB bits survive every hop: Compose takes them as Color(long.toInt()),
// the tests take them as Longs, the layers keep the value they picked.
//
// The language in four commitments (the design doc; each group below
// documents its slice):
//   1. DARK-FIRST, ONE neutral family + ONE ember. The ladder is a single
//      cool-neutral axis --- pond / pad / pad-raised / hairline below the
//      text tones, reed / paper above --- with no second hue anywhere.
//      EMBER is the only expressive color, and it spends itself on
//      interaction alone (selection, the live value, the CTA): one accent
//      cannot go out of fashion, and one accent cannot fight a photo.
//   2. DEPTH FROM LIGHTNESS STEPS, NOT SHADOWS. Every elevation is a
//      measured step up the ladder (canvas pond -> sheet pad -> control
//      pad-raised -> hairline border); no drop shadow buys depth, so the
//      dark theme never muddies and stacked sheets stay legible.
//   3. TABULAR NUMERALS FOR VALUES. Every number a control shows (value
//      pills, timecodes, export counters) sets in the mono face --- the
//      LilypadValueStyle bridge in EditorTheme.kt over [LilypadMono]'s
//      bundled JetBrains Mono --- so counting digits never jitter the
//      layout. The TYPE_VALUE pins are that contract.
//   4. MOTION IS PHYSICS, NOT CHOREOGRAPHY. The Deck rides ONE spring
//      (stiffness + damping, never a duration curve); the two durations
//      that remain cover fades only, and the parallax drift is a single
//      constant, not a curve family.
//
// [Roles] is the material3 BRIDGE set: hexes that exist only because a
// ColorScheme slot needs a tone the core ladder does not carry
// (the unfocused outline, the pressed accent, the steel secondary the
// video lane reads). They are Lilypad citizens --- same families, same
// rules --- but they answer to the bridge; the test pins them to full
// alpha and the 6-digit ARGB range like every ladder step.
//
// (r67 recovery note: the r67 working tree was lost mid-round; this file
// was restored verbatim from the round's read-back --- the lost-tree
// recovery documented in the round's delivery report.)
// ---------------------------------------------------------------------------

/**
 * The r67 Lilypad design system: the color ladder, the M3 bridge roles,
 * the shape pins, the type scale, the motion constants and the haptic
 * vocabulary as pure Kotlin data (ARGB Longs, Ints, Floats --- no
 * compose, no android, by contract). Single source of truth for the
 * editor rebirth: [EditorPalette.Lilypad] maps it onto material3,
 * LilypadTokensTest pins its arithmetic on the JVM, and no surface may
 * carry a hex or corner of its own.
 */
internal object LilypadTokens {

    // ---- The color ladder (one neutral family + one ember) ---------------
    //
    // Eight constants: the six neutrals climb strictly by luminance (the
    // test pins the ordering --- change a step, break a test, read the
    // doc), and the ember pair rides on top. Surfaces climb the ladder
    // for depth; text sits on its two paper tones; the ember marks
    // interaction.

    /** The canvas: the editor's deepest tone, the water the photo floats on. */
    const val POND: Long = 0xFF0B0C0EL

    /** The sheet: panels, decks and tool surfaces sit one step above the pond. */
    const val PAD: Long = 0xFF15171BL

    /** The control: sliders, wells and pills sit a step above their sheet. */
    const val PAD_RAISED: Long = 0xFF1E2126L

    /** The hairline: 1dp borders and track edges --- a tone, never a bar. */
    const val HAIRLINE: Long = 0xFF2A2E35L

    /** The muted text: labels, captions, hints (>= 4.5:1 on the sheet). */
    const val REED: Long = 0xFF9BA0A8L

    /** The text: the paper tone every reading string sets in (>= 7:1 on the sheet). */
    const val PAPER: Long = 0xFFF2F1EEL

    /** The ember: the ONE accent --- selection, the live value, the CTA. */
    const val EMBER: Long = 0xFFFF6B45L

    /** The text on ember: the deep ember-brown an ember fill reads in. */
    const val ON_EMBER: Long = 0xFF2A0E05L

    /**
     * The material3 BRIDGE hexes: tones a ColorScheme slot needs that the
     * ladder does not carry. Same families, same rules (dark-first, one
     * neutral axis + one ember), but these answer to the bridge --- they
     * ride [EditorPalette.Lilypad]'s roles and the video lane's scheme,
     * not the surface ladder.
     */
    object Roles {
        /** outline: the unfocused border / notch tone, cooler and quieter than reed. */
        const val OUTLINE: Long = 0xFF6E747DL

        /** The ember's deep well: the pressed / container tone under the accent. */
        const val EMBER_DEEP: Long = 0xFF4A2318L

        /** The text on the ember well: the warm paper an ember container reads in. */
        const val ON_EMBER_CONTAINER: Long = 0xFFFFD9CCL

        /** The dimmed ember: the inverse / pressed accent, ember at rest. */
        const val EMBER_DIM: Long = 0xFFB3492FL

        /** The steel: the cool secondary chrome (the video lane's soft accent). */
        const val STEEL: Long = 0xFFC9CDD3L

        /** The text on steel: the same tone as PAD_RAISED --- one neutral axis, reused. */
        const val ON_STEEL: Long = 0xFF1E2126L

        /** The deep well under steel chrome: the secondary container tone. */
        const val STEEL_DEEP: Long = 0xFF3A2C24L

        /** The soft paper: on-container text where full PAPER would glare. */
        const val PAPER_SOFT: Long = 0xFFE4E6E9L
    }

    // ---- Shape (depth from lightness; edges from three radii) ------------
    //
    // Three corners, no more: a surface is a sheet, a control, or a chip,
    // and its radius says which. No squircle bets, no per-surface radii.

    /** The sheet corner: panels, decks, sheets (16dp --- the big soft plate). */
    const val SHEET_CORNER_DP = 16

    /** The control corner: sliders' wells, value pills, cards (12dp). */
    const val CONTROL_CORNER_DP = 12

    /** The chip corner: chips, tags, the smallest plates (8dp). */
    const val CHIP_CORNER_DP = 8

    // ---- Type (four named stops; Manrope reads, mono counts) -------------
    //
    // The scale has four NAMED stops; material3's fifteen slots are filled
    // from them in EditorTheme's LilypadTypography (display/section/body
    // land in displayLarge/headlineMedium/bodyLarge, and the VALUE stop is
    // labelMedium's size twin in the mono face --- LilypadValueStyle).

    /** The display stop: the immersive numerals and hero values. 96sp, Manrope 800. */
    const val TYPE_DISPLAY_SP = 96
    const val TYPE_DISPLAY_WEIGHT = 800

    /** The section stop: panel and sheet titles. 30sp, Manrope 800. */
    const val TYPE_SECTION_SP = 30
    const val TYPE_SECTION_WEIGHT = 800

    /** The body stop: reading copy and labels. 15sp, Manrope 400. */
    const val TYPE_BODY_SP = 15
    const val TYPE_BODY_WEIGHT = 400

    /** The value stop: every number a control shows. 12sp, JetBrains Mono 600. */
    const val TYPE_VALUE_SP = 12
    const val TYPE_VALUE_WEIGHT = 600

    // ---- Motion (one spring, two fades, one drift) ------------------------
    //
    // The sheet is PHYSICS: a single spring whose damping/stiffness pair
    // (not a duration curve) carries every Deck state change, so the sheet
    // feels like one object the whole way up. The two durations that
    // remain cover fades only; the parallax drift is a constant offset the
    // canvas layer applies, not a curve.

    /** The standard fade: content crossfades and state transitions (240ms). */
    const val MOTION_STANDARD_MS = 240

    /** The tool fade: a tool panel's quicker in/out (180ms --- lighter cargo). */
    const val MOTION_TOOL_FADE_MS = 180

    /** The parallax drift: the canvas's counter-travel under a sheet drag (8dp). */
    const val MOTION_PARALLAX_DP = 8

    /** The sheet spring's damping (0.85 --- settled, a breath short of critical). */
    const val MOTION_SHEET_DAMPING = 0.85f

    /** The sheet spring's stiffness (380 --- firm enough to feel direct). */
    const val MOTION_SHEET_STIFFNESS = 380f

    /**
     * The haptic vocabulary: FOUR primitives, the whole vocabulary --- every
     * control picks from this list and nothing invents its own amplitude
     * story. The names say what the USER learns, not which View API fires:
     * the zero tick (crossing a control's neutral), the detent double
     * (arriving at a snap point), the soft commit (a draft landing) and the
     * one celebratory export success.
     */
    enum class Haptic {
        /** Crossing a control's neutral: the notch at the track's center. */
        ZERO_TICK,

        /** Arriving at a snap point: deck states, chip rows, stepped sliders. */
        DETENT_DOUBLE,

        /** A draft landing: apply, tool exit, the quiet confirmation. */
        COMMIT_SOFT,

        /** The export finished: the one celebratory pulse in the whole editor. */
        EXPORT_SUCCESS,
    }
}
