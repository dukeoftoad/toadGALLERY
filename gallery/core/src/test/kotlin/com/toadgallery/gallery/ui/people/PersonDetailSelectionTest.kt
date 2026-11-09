package com.toadgallery.gallery.ui.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Person detail grid's multi-select state machine
 * (r25, Workstream 6; ADR-0016): [PersonDetailSelection] is pure
 * Kotlin over plain values, so the mode's rules are pinned here
 * instead of living implicitly in the ViewModel's control flow.
 *
 * What is pinned:
 * - long-press ENTERS the mode (seeding the pressed photo); entering
 *   while already active never wipes the picks;
 * - taps TOGGLE membership only while active --- a plain tap outside
 *   the mode must never start a selection (the file-ops idiom);
 * - exit clears everything; cleared() keeps the MODE but empties the
 *   picks (mode-active-but-zero-picked is legal);
 * - syncedTo() drops picks whose photos left the current snapshot (a
 *   refresh after "Remove from person" must not leave the count bar
 *   claiming photos that are gone) and keeps the mode.
 */
class PersonDetailSelectionTest {

    @Test
    fun `the resting state is inactive with no picks`() {
        val inactive = PersonDetailSelection.INACTIVE
        assertFalse(inactive.active)
        assertEquals(0, inactive.selectedCount)
        assertEquals(emptySet<Long>(), inactive.selectedFileIds)
    }

    @Test
    fun `entering seeds the long-pressed photo and second entry never wipes picks`() {
        val entered = PersonDetailSelection.INACTIVE.entered(firstPick = 7L)
        assertTrue(entered.active)
        assertEquals(setOf(7L), entered.selectedFileIds)
        assertEquals(1, entered.selectedCount)

        val enteredEmpty = PersonDetailSelection.INACTIVE.entered()
        assertTrue(enteredEmpty.active)
        assertEquals(0, enteredEmpty.selectedCount)

        // A second long-press while active is a no-op returning the SAME
        // instance --- it must not reset the picks:
        val picked = entered.toggled(9L)
        assertTrue(picked.entered(firstPick = 1L) === picked)
        assertEquals(setOf(7L, 9L), picked.selectedFileIds)
    }

    @Test
    fun `toggles flip membership only inside the mode`() {
        val selection = PersonDetailSelection.INACTIVE
            .entered(firstPick = 1L)
            .toggled(2L)
            .toggled(3L)
        assertEquals(setOf(1L, 2L, 3L), selection.selectedFileIds)
        assertEquals(3, selection.selectedCount)

        val unToggled = selection.toggled(2L)
        assertEquals(setOf(1L, 3L), unToggled.selectedFileIds)
        // Re-adding after a removal keeps insertion order for the newcomer:
        val reAdded = unToggled.toggled(2L)
        assertEquals(setOf(1L, 3L, 2L), reAdded.selectedFileIds)

        // Outside the mode a toggle is a NO-OP (same instance) --- only
        // long-press starts a selection:
        assertTrue(PersonDetailSelection.INACTIVE.toggled(5L) === PersonDetailSelection.INACTIVE)
    }

    @Test
    fun `exit clears everything and cleared empties the picks but keeps the mode`() {
        val selection = PersonDetailSelection.INACTIVE.entered(1L).toggled(2L)

        val cleared = selection.cleared()
        assertTrue(cleared.active)
        assertEquals(0, cleared.selectedCount)
        // Clearing an already-empty selection is a no-op:
        assertTrue(cleared.cleared() === cleared)

        val exited = cleared.exited()
        assertFalse(exited.active)
        assertEquals(0, exited.selectedCount)
        assertEquals(PersonDetailSelection.INACTIVE, exited)
        // Exiting the resting state is stable:
        assertTrue(exited.exited() === PersonDetailSelection.INACTIVE)
    }

    @Test
    fun `syncing drops gone picks and keeps the mode`() {
        val selection = PersonDetailSelection.INACTIVE
            .entered(1L)
            .toggled(2L)
            .toggled(3L)

        // A refresh after "Remove from person" removed photos 2 and 3:
        val synced = selection.syncedTo(setOf(1L, 9L))
        assertTrue(synced.active)
        assertEquals(setOf(1L), synced.selectedFileIds)

        // Nothing gone -> same instance:
        assertTrue(synced.syncedTo(setOf(1L, 2L)) === synced)

        // Everything gone -> the mode stays, zero picks (the user exits
        // explicitly; the bar shows the honest zero):
        val allGone = synced.syncedTo(emptySet())
        assertTrue(allGone.active)
        assertEquals(0, allGone.selectedCount)

        // Syncing an inactive selection is a no-op:
        assertTrue(
            PersonDetailSelection.INACTIVE.syncedTo(setOf(1L)) ===
                PersonDetailSelection.INACTIVE,
        )
    }
}
