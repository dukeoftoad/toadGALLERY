package io.github.dukeoftoad.gallery.edit

/**
 * The in-session undo/redo stack over the edit parameters (contract section
 * 4.2: "Undo/redo is provided in-session; nothing is written until explicit
 * export"). Pure Kotlin + JVM-tested.
 *
 * The session state is a snapshot stack: every COMMITTED parameter change
 * (slider release, rotation button) pushes one snapshot; undo/redo walk the
 * pointer. Depth is capped --- the oldest snapshots fall off, so memory is
 * bounded regardless of session length (the cap mirrors "reasonable depth"
 * in the roadmap; 64 covers any realistic slider-drag cadence).
 */
data class EditParameters(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val quarterTurns: Int = 0,
) {
    /** True when nothing has been changed from the launch state. */
    val isIdentity: Boolean
        get() = brightness == 0 && contrast == 0 && quarterTurns % 4 == 0
}

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
        require(parameters.brightness in PixelAdjuster.MIN..PixelAdjuster.MAX)
        require(parameters.contrast in PixelAdjuster.MIN..PixelAdjuster.MAX)
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
