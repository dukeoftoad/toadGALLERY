package com.toadgallery.editor

// Lilypad design system as pure data (ARGB Longs/Ints/Floats — no compose or
// android imports): dark-first one-neutral ladder + one ember accent, mono
// numerals. One source of truth; no surface carries a hex or corner of its own.

/**
 * The Lilypad design system: the color ladder, M3 bridge roles, shape pins,
 * type scale, motion constants and the haptic vocabulary as pure Kotlin data
 * (no compose, no android, by contract). Single source of truth:
 * [EditorPalette.Lilypad] maps it onto material3, LilypadTokensTest pins its
 * arithmetic, and no surface may carry a hex or corner of its own.
 */
internal object LilypadTokens {

    // ---- The color ladder (one neutral family + one ember) ----
    // The six neutrals climb strictly by luminance (pinned by test); the ember
    // pair rides on top, spent on interaction alone.

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
     * The material3 BRIDGE hexes: tones a ColorScheme slot needs that the ladder
     * does not carry. Same families, same rules, but they answer to the bridge —
     * they ride [EditorPalette.Lilypad]'s roles, not the surface ladder.
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

    // ---- Shape (depth from lightness; edges from three radii) ----
    // Three corners, no more: a surface is a sheet, a control, or a chip, and
    // its radius says which. No per-surface radii.

    /** The sheet corner: panels, decks, sheets (16dp --- the big soft plate). */
    const val SHEET_CORNER_DP = 16

    /** The control corner: sliders' wells, value pills, cards (12dp). */
    const val CONTROL_CORNER_DP = 12

    /** The chip corner: chips, tags, the smallest plates (8dp). */
    const val CHIP_CORNER_DP = 8

    // ---- Type (four named stops; Manrope reads, mono counts) ----
    // material3's slots fill from them in EditorTheme's LilypadTypography; the
    // VALUE stop is labelMedium's size twin in the mono face (LilypadValueStyle).

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

    // ---- Motion (one spring, two fades, one drift) ----
    // The sheet is physics: one spring (damping/stiffness, never a duration
    // curve) carries every Deck state change. The two durations cover fades
    // only; the parallax drift is a constant offset, not a curve.

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
     * The haptic vocabulary: FOUR primitives — every control picks from this
     * list and invents no amplitude of its own. The names say what the USER
     * learns, not which View API fires.
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
