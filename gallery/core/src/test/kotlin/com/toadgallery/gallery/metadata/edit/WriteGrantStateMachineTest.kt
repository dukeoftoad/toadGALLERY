package com.toadgallery.gallery.metadata.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure write-grant escalation machine (Phase 4 write
 * round; ADR-0016 --- the write-path twin of
 * `MediaLocationAccessTest`). Every transition in
 * [WriteGrantStateMachine]'s KDoc table is pinned, including the
 * single-shot retry discipline (a second refusal never loops the dialog)
 * and the stale-signal guards (stray results must not invent state).
 */
class WriteGrantStateMachineTest {

    // ---- onWriteBlocked ----

    @Test
    fun `blocked write from IDLE escalates when the platform allows it`() {
        val machine = WriteGrantStateMachine()
        assertEquals(WriteGrantState.NEEDS_GRANT, machine.onWriteBlocked(escalationPossible = true))
        assertEquals(WriteGrantState.NEEDS_GRANT, machine.current)
    }

    @Test
    fun `blocked write from IDLE on API 29 is READ_ONLY - no dialog exists there`() {
        val machine = WriteGrantStateMachine()
        assertEquals(WriteGrantState.READ_ONLY, machine.onWriteBlocked(escalationPossible = false))
    }

    @Test
    fun `blocked write after a grant is READ_ONLY - the retry is single-shot`() {
        val machine = WriteGrantStateMachine()
        machine.onWriteBlocked(true)
        machine.onRequestLaunched()
        machine.onGrantResult(true)
        assertEquals(WriteGrantState.GRANTED, machine.current)
        assertEquals(WriteGrantState.READ_ONLY, machine.onWriteBlocked(true))
    }

    @Test
    fun `blocked write leaves every non-launch state unchanged`() {
        for (state in listOf(
            WriteGrantState.NEEDS_GRANT,
            WriteGrantState.REQUESTED,
            WriteGrantState.DENIED,
            WriteGrantState.READ_ONLY,
        )) {
            val machine = WriteGrantStateMachine(state)
            assertEquals(state, machine.onWriteBlocked(true))
            assertEquals(state, machine.onWriteBlocked(false))
        }
    }

    // ---- onRequestLaunched ----

    @Test
    fun `request launch moves NEEDS_GRANT to REQUESTED and is ignored elsewhere`() {
        val machine = WriteGrantStateMachine()
        machine.onWriteBlocked(true)
        assertEquals(WriteGrantState.REQUESTED, machine.onRequestLaunched())
        // Ignored in every other state (a stray launch cannot invent state);
        // NEEDS_GRANT is NOT here --- it is the one state that transitions
        // (pinned above), and REQUESTED re-launching is a harmless no-op.
        for (state in listOf(
            WriteGrantState.IDLE,
            WriteGrantState.REQUESTED,
            WriteGrantState.GRANTED,
            WriteGrantState.DENIED,
            WriteGrantState.READ_ONLY,
        )) {
            assertEquals(state, WriteGrantStateMachine(state).onRequestLaunched())
        }
    }

    // ---- onGrantResult ----

    @Test
    fun `grant result answers REQUESTED with GRANTED or DENIED`() {
        for (granted in listOf(true, false)) {
            val machine = WriteGrantStateMachine()
            machine.onWriteBlocked(true)
            machine.onRequestLaunched()
            assertEquals(
                if (granted) WriteGrantState.GRANTED else WriteGrantState.DENIED,
                machine.onGrantResult(granted),
            )
        }
    }

    @Test
    fun `stale grant results are ignored in every other state`() {
        for (state in listOf(
            WriteGrantState.IDLE,
            WriteGrantState.NEEDS_GRANT,
            WriteGrantState.GRANTED,
            WriteGrantState.DENIED,
            WriteGrantState.READ_ONLY,
        )) {
            val machine = WriteGrantStateMachine(state)
            assertEquals(state, machine.onGrantResult(true))
            assertEquals(state, machine.onGrantResult(false))
        }
    }

    // ---- onWriteSucceeded / reset ----

    @Test
    fun `a successful write returns the machine to IDLE from every state`() {
        for (state in WriteGrantState.entries) {
            assertEquals(WriteGrantState.IDLE, WriteGrantStateMachine(state).onWriteSucceeded())
        }
    }

    @Test
    fun `reset starts a fresh session from every state - a denial never poisons the next edit`() {
        for (state in WriteGrantState.entries) {
            assertEquals(WriteGrantState.IDLE, WriteGrantStateMachine(state).reset())
        }
    }

    @Test
    fun `the full session cycle behaves end to end`() {
        val machine = WriteGrantStateMachine()
        // First write blocked, dialog granted, retry lands.
        machine.onWriteBlocked(true)
        machine.onRequestLaunched()
        machine.onGrantResult(true)
        machine.onWriteSucceeded()
        assertEquals(WriteGrantState.IDLE, machine.current)
        // A denial in a later session is terminal for THAT session only.
        machine.onWriteBlocked(true)
        machine.onRequestLaunched()
        machine.onGrantResult(false)
        assertEquals(WriteGrantState.DENIED, machine.current)
        machine.reset()
        assertEquals(WriteGrantState.IDLE, machine.current)
    }
}
