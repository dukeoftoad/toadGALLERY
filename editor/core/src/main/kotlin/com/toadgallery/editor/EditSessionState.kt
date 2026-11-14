package com.toadgallery.editor

/**
 * The in-session undo/redo stack over the edit recipe (contract 4.2: "Undo/redo is provided
 * in-session; nothing is written until explicit export"). Pure Kotlin + JVM-tested.
 *
 * A snapshot stack over WHOLE [EditRecipe]s: every COMMITTED change pushes one snapshot;
 * undo/redo walk the pointer. Depth is capped --- the oldest snapshots fall off, so memory
 * is bounded regardless of session length (64 covers any realistic slider-drag cadence).
 * [depth]/[pointerIndex]/[entryAt] are READ-ONLY views for the history strip; the pointer's
 * only moves stay undo/redo/commit, so a labeling reader can never corrupt the trail.
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

    /**
     * The number of SNAPSHOTS the ring holds: always >= 1 (the current recipe is always
     * held), at most `maxDepth + 1` (the cap [commit] enforces by dropping the OLDEST
     * entries first, the launch snapshot included). Distinct from [maxDepth], which
     * counts UNDO STEPS.
     */
    val depth: Int get() = history.size

    /**
     * The pointer's position in the ring: 0 is the oldest snapshot still held (the launch
     * state until the cap pushes it off), [depth] - 1 the newest. [undo]/[redo] move it
     * by one; nothing else ever does.
     */
    val pointerIndex: Int get() = pointer

    /**
     * The snapshot at [index], or null outside 0..[depth] - 1: a READ-ONLY view for labeling
     * the strip; the ring's only moves stay [undo]/[redo]/[commit].
     */
    fun entryAt(index: Int): EditRecipe? = history.getOrNull(index)

    /** Commits a new recipe: truncates any redo tail first (standard editor semantics). */
    fun commit(recipe: EditRecipe) {
        while (history.size > pointer + 1) {
            history.removeLast()
        }
        history.addLast(recipe)
        pointer = history.lastIndex
        // Bound the depth: maxDepth counts UNDO STEPS, so the history holds at most
        // maxDepth+1 recipes. Drop the OLDEST entries (the launch state goes first).
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
