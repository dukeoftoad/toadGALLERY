package io.github.dukeoftoad.gallery.edit

/**
 * The in-session undo/redo stack over the edit recipe (contract section
 * 4.2: "Undo/redo is provided in-session; nothing is written until explicit
 * export"). Pure Kotlin + JVM-tested.
 *
 * The session state is a snapshot stack over WHOLE [EditRecipe]s: every
 * COMMITTED change (slider release, geometry button, band edit) pushes one
 * snapshot; undo/redo walk the pointer. Depth is capped --- the oldest
 * snapshots fall off, so memory is bounded regardless of session length
 * (64 covers any realistic slider-drag cadence).
 */
class EditSessionState(
    initial: EditRecipe = EditRecipe(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {

    private val history = ArrayDeque<EditRecipe>()
    private var pointer: Int = 0

    init {
        history.addLast(initial)
    }

    val current: EditRecipe get() = history[pointer]

    val canUndo: Boolean get() = pointer > 0
    val canRedo: Boolean get() = pointer < history.lastIndex

    /** Commits a new recipe: truncates any redo tail first (standard editor semantics). */
    fun commit(recipe: EditRecipe) {
        while (history.size > pointer + 1) {
            history.removeLast()
        }
        history.addLast(recipe)
        pointer = history.lastIndex
        // Bound the depth: drop the OLDEST entries (the launch state is the
        // first to go, which is honest --- undo cannot outlive the cap).
        // maxDepth counts the UNDO STEPS (the tests pin 9,8,7,6 for
        // maxDepth 4), so the history holds at most maxDepth+1 recipes
        // (the current one plus the undoable trail).
        while (history.size > maxDepth + 1) {
            history.removeFirst()
            pointer -= 1
        }
    }

    /** Returns the previous state, or null when already at the oldest. */
    fun undo(): EditRecipe? {
        if (!canUndo) return null
        pointer -= 1
        return current
    }

    /** Returns the next state, or null when already at the newest. */
    fun redo(): EditRecipe? {
        if (!canRedo) return null
        pointer += 1
        return current
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 64
    }
}
