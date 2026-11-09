package com.toadgallery.gallery.ui.people

/**
 * The Person detail grid's multi-select state (r25, Workstream 6): which
 * photo file ids are picked for the "Remove from person" correction.
 *
 * PURE Kotlin over plain values (the [MapContentPolicy]/
 * [PeopleContentPolicy] discipline, ADR-0016): the ViewModel stores one
 * immutable instance and every interaction is a copy-returning
 * transition, so the mode's rules --- a long-press ENTERS the mode, taps
 * TOGGLE inside it, exit CLEARS everything, and a refresh can never keep
 * ids whose photos are gone --- are pinned by
 * `PersonDetailSelectionTest` instead of living implicitly in the
 * ViewModel's control flow.
 *
 * Mode-active-but-nothing-selected is a legal state (the user untoggled
 * their way back to zero): the mode stays until the exit action, exactly
 * like the file-ops selection mode on the Photos/album grids.
 */
data class PersonDetailSelection(
    /** Whether the grid is in selection mode at all. */
    val active: Boolean = false,
    /** The picked MediaStore file ids (insertion order preserved). */
    val selectedFileIds: Set<Long> = emptySet(),
) {

    /** How many photos are picked (drives the selection bar's count). */
    val selectedCount: Int
        get() = selectedFileIds.size

    /**
     * Enters selection mode, optionally seeding the first pick (the
     * long-pressed photo). Entering while already active is a no-op
     * returning `this` --- a second long-press must not wipe picks.
     */
    fun entered(firstPick: Long? = null): PersonDetailSelection {
        if (active) return this
        return PersonDetailSelection(
            active = true,
            selectedFileIds = firstPick?.let { linkedSetOf(it) } ?: linkedSetOf(),
        )
    }

    /** Exits selection mode and forgets every pick. */
    fun exited(): PersonDetailSelection = INACTIVE

    /**
     * Toggles [fileId]'s membership while active; a no-op returning `this`
     * when the mode is not active (a plain tap outside the mode never
     * starts a selection --- only long-press does, the file-ops idiom).
     */
    fun toggled(fileId: Long): PersonDetailSelection {
        if (!active) return this
        val ids = if (fileId in selectedFileIds) {
            selectedFileIds - fileId
        } else {
            linkedSetOf<Long>().apply {
                addAll(selectedFileIds)
                add(fileId)
            }
        }
        return PersonDetailSelection(active = true, selectedFileIds = ids)
    }

    /**
     * Clears the picks but KEEPS the mode active (the "deselect all"
     * affordance); a no-op when nothing is picked.
     */
    fun cleared(): PersonDetailSelection {
        if (selectedFileIds.isEmpty()) return this
        return PersonDetailSelection(active = true, selectedFileIds = emptySet())
    }

    /**
     * Drops picks whose photos no longer exist ([liveFileIds] = the ids
     * of the current grid snapshot): a refresh after "Remove from
     * person" (or any live-table change under the screen) must not leave
     * the count bar claiming photos that are gone. Keeps the mode.
     */
    fun syncedTo(liveFileIds: Set<Long>): PersonDetailSelection {
        if (!active || selectedFileIds.all { it in liveFileIds }) return this
        return PersonDetailSelection(
            active = true,
            selectedFileIds = selectedFileIds.filterTo(LinkedHashSet()) { it in liveFileIds },
        )
    }

    companion object {
        /** The resting state: no mode, no picks. */
        val INACTIVE = PersonDetailSelection()
    }
}
