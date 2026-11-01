package io.github.dukeoftoad.gallery.data.media.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the PURE parts of the ACCESS_MEDIA_LOCATION flow
 * (Phase 4): the [MediaLocationStateMachine] transitions and the
 * [MediaLocationDisplayRules] gate. No android.* (ADR-0016); the OS
 * half is a thin `ContextCompat` wrapper (`AndroidMediaLocationAccess`)
 * that stays untested by design, same exemption as
 * `MediaReadPermissionGate`.
 *
 * The tables below are exhaustive over [MediaLocationState]: every
 * (from-state, outcome) pair appears, so a new enum value or a forgotten
 * transition shows up as a compile error or a failing row.
 */
class MediaLocationAccessTest {

    // ---- onSystemCheck: seeding / cheap re-check ----

    @Test
    fun `system check collapses every state to NOT_REQUIRED below API 29`() {
        MediaLocationState.values().forEach { from ->
            val machine = MediaLocationStateMachine(from)
            machine.onSystemCheck(required = false, granted = false)
            assertEquals(
                "below 29 the permission does not exist; state must be NOT_REQUIRED " +
                    "regardless of the prior state ($from)",
                MediaLocationState.NOT_REQUIRED,
                machine.current,
            )
            // The `granted` flag is meaningless below 29; either value lands
            // in the same place (the OS ignores the permission there).
            machine.onSystemCheck(required = false, granted = true)
            assertEquals(MediaLocationState.NOT_REQUIRED, machine.current)
        }
    }

    @Test
    fun `system check with grant lands in GRANTED from every state`() {
        MediaLocationState.values().forEach { from ->
            val machine = MediaLocationStateMachine(from)
            machine.onSystemCheck(required = true, granted = true)
            assertEquals(
                "an OS-confirmed grant is GRANTED from $from",
                MediaLocationState.GRANTED,
                machine.current,
            )
        }
    }

    @Test
    fun `system check without grant keeps DENIED and resets the rest to UNKNOWN`() {
        MediaLocationState.values().forEach { from ->
            val machine = MediaLocationStateMachine(from)
            machine.onSystemCheck(required = true, granted = false)
            val expected = when (from) {
                // A refusal must not be silently forgotten by a re-check.
                MediaLocationState.DENIED -> MediaLocationState.DENIED
                // Terminal below-29 state survives a hypothetical re-check.
                MediaLocationState.NOT_REQUIRED -> MediaLocationState.NOT_REQUIRED
                else -> MediaLocationState.UNKNOWN
            }
            assertEquals("re-check without grant from $from", expected, machine.current)
        }
    }

    // ---- onPermissionResult: one runtime request's answer ----

    @Test
    fun `grant outcome lands in GRANTED from every non-NOT_REQUIRED state`() {
        listOf(
            MediaLocationState.UNKNOWN,
            MediaLocationState.GRANTED,
            MediaLocationState.DENIED,
        ).forEach { from ->
            val machine = MediaLocationStateMachine(from)
            machine.onPermissionResult(granted = true)
            assertEquals(
                "a granted request is GRANTED from $from",
                MediaLocationState.GRANTED,
                machine.current,
            )
        }
    }

    @Test
    fun `denial outcome lands in DENIED from every non-NOT_REQUIRED state`() {
        listOf(
            MediaLocationState.UNKNOWN,
            MediaLocationState.GRANTED,
            MediaLocationState.DENIED,
        ).forEach { from ->
            val machine = MediaLocationStateMachine(from)
            machine.onPermissionResult(granted = false)
            assertEquals(
                "a denied request is DENIED from $from (a later refusal overrides " +
                    "an earlier grant in the state holder; refresh() reconciles with the OS)",
                MediaLocationState.DENIED,
                machine.current,
            )
        }
    }

    @Test
    fun `NOT_REQUIRED is terminal - request outcomes below 29 are ignored`() {
        val machine = MediaLocationStateMachine(MediaLocationState.NOT_REQUIRED)
        machine.onPermissionResult(granted = true)
        machine.onPermissionResult(granted = false)
        assertEquals(
            "there is nothing to request below 29; outcomes must not invent state",
            MediaLocationState.NOT_REQUIRED,
            machine.current,
        )
    }

    @Test
    fun `state flow starts at the constructor's initial state`() {
        assertEquals(
            MediaLocationState.UNKNOWN,
            MediaLocationStateMachine().current,
        )
        assertEquals(
            MediaLocationState.DENIED,
            MediaLocationStateMachine(MediaLocationState.DENIED).current,
        )
    }

    // ---- Display gate: the Details-view seam ----

    @Test
    fun `default gate shows GPS exactly when GRANTED or NOT_REQUIRED`() {
        MediaLocationState.values().forEach { state ->
            val expected = state == MediaLocationState.GRANTED ||
                state == MediaLocationState.NOT_REQUIRED
            assertEquals(
                "gate rule for $state",
                expected,
                MediaLocationDisplayRules.Default.shouldDisplayGps(state),
            )
        }
    }

    @Test
    fun `default gate hides GPS for the honest unknown and the refusal`() {
        // The conservative side of the contract: on 29+, an unanswered
        // request is NOT a yes.
        assertFalse(MediaLocationDisplayRules.Default.shouldDisplayGps(MediaLocationState.UNKNOWN))
        assertFalse(MediaLocationDisplayRules.Default.shouldDisplayGps(MediaLocationState.DENIED))
        assertTrue(MediaLocationDisplayRules.Default.shouldDisplayGps(MediaLocationState.GRANTED))
        // Below 29 nothing was ever redactable; the file's own bytes speak.
        assertTrue(
            MediaLocationDisplayRules.Default.shouldDisplayGps(MediaLocationState.NOT_REQUIRED),
        )
    }

    // ---- request() plumbing through the access interface (pure fakes) ----

    @Test
    fun `request below 29 returns true without consulting the requester`() = runBlocking {
        var asked = 0
        val access = FakeAccess(required = false, requester = { asked++; true })
        assertTrue(access.request())
        assertEquals("below 29 nothing is ever requested", 0, asked)
        assertEquals(MediaLocationState.NOT_REQUIRED, access.state.value)
    }

    @Test
    fun `request with no requester installed returns false and leaves state alone`() = runBlocking {
        val access = FakeAccess(required = true, requester = null)
        assertFalse(access.request())
        assertEquals("no ask, no invented outcome", MediaLocationState.UNKNOWN, access.state.value)
    }

    @Test
    fun `request outcome is folded into the state`() = runBlocking {
        var asked = 0
        val access = FakeAccess(required = true, requester = { asked++; true })
        assertTrue(access.request())
        assertEquals(1, asked)
        assertEquals(MediaLocationState.GRANTED, access.state.value)

        val deniedAccess = FakeAccess(required = true, requester = { false })
        assertFalse(deniedAccess.request())
        assertEquals(MediaLocationState.DENIED, deniedAccess.state.value)
    }

    /**
     * Pure [MediaLocationAccess] over the real state machine: the fakes
     * stand in for `ContextCompat`/Activity results so the interface's
     * request contract is testable on the JVM.
     */
    private class FakeAccess(
        private val required: Boolean,
        private val requester: (suspend (String) -> Boolean)?,
    ) : MediaLocationAccess {
        private val machine = MediaLocationStateMachine()

        init {
            // Mirror AndroidMediaLocationAccess: seed the OS truth at
            // construction (required() x not-granted in the fake).
            machine.onSystemCheck(required, granted = false)
        }

        override val state = machine.state
        override fun required(): Boolean = required
        override fun isGranted(): Boolean = false
        override fun refresh() = machine.onSystemCheck(required, granted = false)
        override fun onPermissionResult(granted: Boolean) = machine.onPermissionResult(granted)

        override suspend fun request(): Boolean {
            if (!required) return true
            val r = requester ?: return false
            val granted = r.invoke("android.permission.ACCESS_MEDIA_LOCATION")
            machine.onPermissionResult(granted)
            return granted
        }
    }
}
