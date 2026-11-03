package io.github.dukeoftoad.gallery.edit

/**
 * The full v1 edit parameter state (r29 seeds; r30 completes the Phase 6
 * v1 suite). One immutable snapshot per committed state; the undo/redo
 * stack ([EditSessionState]) walks snapshots of THIS type, so every
 * parameter rides undo/redo uniformly.
 *
 * Parameter groups (matching the pipeline's fixed stage order in
 * [EditPipeline]):
 *  - the ten [-100, 100] ints: brightness, contrast, exposure, highlights,
 *    shadows (LUT stage) and saturation, temperature, tint, vignette,
 *    sharpness (pixel/detail stages);
 *  - [hsl]: the eight-band HSL state;
 *  - geometry: [quarterTurns] (90-degree CCW steps, r29), [straightenDeg]
 *    (clockwise degrees, [-45, 45]), [flipH]/[flipV], [crop] (null = full
 *    frame), [perspective] (identity = no warp).
 */
data class EditParameters(
    // LUT stage
    val brightness: Int = 0,
    val contrast: Int = 0,
    val exposure: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    // Pixel + detail stages
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    val vignette: Int = 0,
    val sharpness: Int = 0,
    // Colour grade
    val hsl: HslBands = HslBands(),
    // Geometry
    val quarterTurns: Int = 0,
    val straightenDeg: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropRect? = null,
    val perspective: PerspectiveQuad = PerspectiveQuad.IDENTITY,
) {
    /** True when nothing deviates from the launch state. */
    val isIdentity: Boolean
        get() = brightness == 0 && contrast == 0 && exposure == 0 && highlights == 0 &&
            shadows == 0 && saturation == 0 && temperature == 0 && tint == 0 &&
            vignette == 0 && sharpness == 0 && hsl.isIdentity &&
            quarterTurns % 4 == 0 && straightenDeg == 0.0 && !flipH && !flipV &&
            crop == null && perspective == PerspectiveQuad.IDENTITY

    /**
     * The range contract, enforced on every commit (and on every recipe
     * load): IllegalArgumentException via require --- the same contract
     * the r29 session carried for brightness/contrast.
     */
    fun validate() {
        for (v in intArrayOf(
            brightness, contrast, exposure, highlights, shadows,
            saturation, temperature, tint, vignette, sharpness,
        )) {
            require(v in -100..100) { "adjustment parameter $v outside [-100, 100]" }
        }
        for (band in hsl.ordered()) {
            require(band.second.hue in -180..180) { "hsl hue ${band.second.hue} outside [-180, 180]" }
            require(band.second.saturation in -100..100) { "hsl saturation outside range" }
            require(band.second.luminance in -100..100) { "hsl luminance outside range" }
        }
        require(quarterTurns in 0..8) { "quarter turns $quarterTurns outside [0, 8]" }
        require(straightenDeg in -45.0..45.0) { "straighten $straightenDeg outside [-45, 45]" }
        require(!straightenDeg.isNaN()) { "straighten must be finite" }
        crop?.let { require(!it.left.isNaN() && !it.top.isNaN() && !it.right.isNaN() && !it.bottom.isNaN()) }
    }
}

/**
 * The in-session undo/redo stack (r29; unchanged in r30 --- the parameter
 * type grew, the stack mechanics did not). Contract 4.2: undo/redo in
 * session; nothing written until explicit export.
 */
class EditSessionState(
    initial: EditParameters = EditParameters(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {

    private val history = ArrayDeque<EditParameters>()
    private var pointer: Int = 0

    init {
        history.addLast(initial)
    }

    val current: EditParameters get() = history[pointer]

    val canUndo: Boolean get() = pointer > 0
    val canRedo: Boolean get() = pointer < history.lastIndex

    /** Commits a new parameter set: truncates any redo tail first (standard editor semantics). */
    fun commit(parameters: EditParameters) {
        parameters.validate()
        while (history.size > pointer + 1) {
            history.removeLast()
        }
        history.addLast(parameters)
        pointer = history.lastIndex
        // Bound the depth: drop the OLDEST entries (the launch state is the
        // first to go, which is honest --- undo cannot outlive the cap).
        while (history.size > maxDepth) {
            history.removeFirst()
            pointer -= 1
        }
    }

    /** Returns the previous state, or null when already at the oldest. */
    fun undo(): EditParameters? {
        if (!canUndo) return null
        pointer -= 1
        return current
    }

    /** Returns the next state, or null when already at the newest. */
    fun redo(): EditParameters? {
        if (!canRedo) return null
        pointer += 1
        return current
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 64
    }
}
