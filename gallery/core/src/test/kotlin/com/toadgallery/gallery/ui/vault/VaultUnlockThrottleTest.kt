package com.toadgallery.gallery.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the D-1 (r51) wrong-PIN unlock throttle: the exponential
 * backoff ladder (free first failure, then 1 s doubling to the 60 s cap),
 * the decay/reset semantics of [VaultUnlockThrottle.remainingLockoutMs],
 * and the persisted state file's discipline --- the repo's atomic
 * temp-then-move write contract (the `ThingsStateFile` / `PeopleStateFile`
 * pattern), including the corrupt-file behavior: a rate-limit counter
 * loads as FRESH on garbage, never crashes the unlock flow.
 *
 * Pure JVM (ADR-0016): the throttle itself has no `android.*` dependency,
 * so the ladder and the persistence are pinned here without any Android
 * machinery. Timestamps are explicit longs --- the ladder is deterministic;
 * no clock seams are needed.
 */
class VaultUnlockThrottleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- The ladder -----------------------------------------------------

    @Test
    fun `the backoff ladder is free first, then doubles to the cap`() {
        // 1st failure: 0 (honest typo forgiveness) ...
        assertEquals(0L, VaultUnlockThrottle.lockoutDelayMs(0))
        assertEquals(0L, VaultUnlockThrottle.lockoutDelayMs(1))
        // ... then 1 s, 2 s, 4 s, 8 s, 16 s, 32 s ...
        assertEquals(1_000L, VaultUnlockThrottle.lockoutDelayMs(2))
        assertEquals(2_000L, VaultUnlockThrottle.lockoutDelayMs(3))
        assertEquals(4_000L, VaultUnlockThrottle.lockoutDelayMs(4))
        assertEquals(8_000L, VaultUnlockThrottle.lockoutDelayMs(5))
        assertEquals(16_000L, VaultUnlockThrottle.lockoutDelayMs(6))
        assertEquals(32_000L, VaultUnlockThrottle.lockoutDelayMs(7))
        // ... the 8th would be 64 s but the ladder caps at 60 s, and every
        // further failure stays there. An absurd counter must not overflow.
        assertEquals(60_000L, VaultUnlockThrottle.lockoutDelayMs(8))
        assertEquals(60_000L, VaultUnlockThrottle.lockoutDelayMs(9))
        assertEquals(60_000L, VaultUnlockThrottle.lockoutDelayMs(100))
        assertEquals(60_000L, VaultUnlockThrottle.lockoutDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `the first wrong pin locks nothing and the second starts the countdown`() {
        val throttle = VaultUnlockThrottle() // in-memory; ladder only

        throttle.registerFailure(nowMs = 1_000L)
        assertEquals(0L, throttle.remainingLockoutMs(1_000L))

        throttle.registerFailure(nowMs = 2_000L)
        assertEquals(1_000L, throttle.remainingLockoutMs(2_000L))
    }

    @Test
    fun `each new failure re-arms and grows the countdown`() {
        val throttle = VaultUnlockThrottle()

        // Two failures by t=10_000: 1 s lockout, half-decayed at t=10_500.
        throttle.registerFailure(9_000L)
        throttle.registerFailure(10_000L)
        assertEquals(1_000L, throttle.remainingLockoutMs(10_000L))
        assertEquals(500L, throttle.remainingLockoutMs(10_500L))
        assertEquals(0L, throttle.remainingLockoutMs(11_000L))

        // A THIRD failure at t=11_000 (the moment the old lockout expired)
        // grows the ladder to 2 s and re-arms from now.
        throttle.registerFailure(11_000L)
        assertEquals(2_000L, throttle.remainingLockoutMs(11_000L))
        assertEquals(1L, throttle.remainingLockoutMs(12_999L))
        assertEquals(0L, throttle.remainingLockoutMs(13_000L))
    }

    @Test
    fun `a clock running backwards keeps the full lockout - fail safe`() {
        val throttle = VaultUnlockThrottle()

        throttle.registerFailure(50_000L)
        throttle.registerFailure(50_000L)
        throttle.registerFailure(50_000L)
        // System.currentTimeMillis is not monotonic; a backwards step must
        // never SHORTEN a lockout (the full 2 s delay is kept).
        assertEquals(2_000L, throttle.remainingLockoutMs(45_000L))
    }

    @Test
    fun `a successful unlock resets the ladder and the next failure starts over`() {
        val throttle = VaultUnlockThrottle()

        repeat(5) { throttle.registerFailure(100_000L) }
        assertTrue(throttle.remainingLockoutMs(100_000L) > 0L)

        assertTrue(throttle.registerSuccess(100_500L))
        assertEquals(0L, throttle.remainingLockoutMs(100_500L))

        // Fresh ladder after the reset: one failure locks nothing again.
        throttle.registerFailure(101_000L)
        assertEquals(0L, throttle.remainingLockoutMs(101_000L))
        throttle.registerFailure(101_500L)
        assertEquals(1_000L, throttle.remainingLockoutMs(101_500L))
    }

    // ---- Persistence: the atomic state-file discipline ------------------

    @Test
    fun `the fail counter round-trips through the state file`() {
        val file = File(tmp.newFolder(), "unlock-throttle.json")
        val first = VaultUnlockThrottle(stateFile = file)

        // Three consecutive failures at a controlled instant.
        repeat(3) { first.registerFailure(1_000_000L) }
        assertEquals(2_000L, first.remainingLockoutMs(1_000_000L))

        // A FRESH instance (process death) restores the same lockout.
        val revived = VaultUnlockThrottle(stateFile = file)
        revived.load()
        assertEquals(2_000L, revived.remainingLockoutMs(1_000_000L))
        assertEquals(1_500L, revived.remainingLockoutMs(1_000_500L))
        assertEquals(0L, revived.remainingLockoutMs(1_002_000L))
    }

    @Test
    fun `the atomic write leaves no temp file behind`() {
        val dir = tmp.newFolder()
        val file = File(dir, "unlock-throttle.json")
        val throttle = VaultUnlockThrottle(stateFile = file)

        assertTrue(throttle.registerFailure(10L))
        assertTrue(throttle.registerFailure(20L))
        assertTrue(throttle.registerSuccess(30L))

        assertEquals(
            setOf("unlock-throttle.json"),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a successful unlock persists the reset across instances`() {
        val file = File(tmp.newFolder(), "unlock-throttle.json")
        val first = VaultUnlockThrottle(stateFile = file)
        repeat(4) { first.registerFailure(5_000L) }
        assertTrue(first.registerSuccess(6_000L))

        val revived = VaultUnlockThrottle(stateFile = file)
        revived.load()
        assertEquals(0L, revived.remainingLockoutMs(6_000L))
        // And the ladder truly starts over, not from the stale count.
        revived.registerFailure(7_000L)
        assertEquals(0L, revived.remainingLockoutMs(7_000L))
    }

    @Test
    fun `a missing file is a clean fresh counter`() {
        val file = File(tmp.newFolder(), "unlock-throttle.json")
        val throttle = VaultUnlockThrottle(stateFile = file)

        throttle.load()
        assertEquals(0L, throttle.remainingLockoutMs(Long.MAX_VALUE / 2))
        // First write to the missing file succeeds.
        assertTrue(throttle.registerFailure(1L))
    }

    @Test
    fun `a corrupt or future-version file loads fresh instead of crashing`() {
        val dir = tmp.newFolder()
        val file = File(dir, "unlock-throttle.json")

        // Garbage bytes (a crash mid-write is impossible by the atomic
        // discipline, but hand edits and bit rot are not):
        file.writeText("not json at all")
        var throttle = VaultUnlockThrottle(stateFile = file)
        throttle.load()
        assertEquals(0L, throttle.remainingLockoutMs(1_000L))
        // The fresh counter still WORKS (one failure locks nothing).
        assertTrue(throttle.registerFailure(1_000L))
        assertEquals(0L, throttle.remainingLockoutMs(1_000L))

        // A future schema version is not ours to interpret:
        file.writeText("""{"version":99,"failCount":7,"lastFailureAtMs":900}""")
        throttle = VaultUnlockThrottle(stateFile = file)
        throttle.load()
        assertEquals(0L, throttle.remainingLockoutMs(1_000L))
    }

    @Test
    fun `hand-edited absurd values are clamped to sane ladder semantics`() {
        val file = File(tmp.newFolder(), "unlock-throttle.json")

        // A negative counter is absence:
        file.writeText("""{"version":1,"failCount":-5,"lastFailureAtMs":1000}""")
        var throttle = VaultUnlockThrottle(stateFile = file)
        throttle.load()
        assertEquals(0L, throttle.remainingLockoutMs(1_000L))

        // An absurd counter saturates at the cap, not at 2^31 seconds:
        file.writeText("""{"version":1,"failCount":2000000000,"lastFailureAtMs":1000}""")
        throttle = VaultUnlockThrottle(stateFile = file)
        throttle.load()
        assertEquals(60_000L, throttle.remainingLockoutMs(1_000L))
        assertEquals(59_000L, throttle.remainingLockoutMs(2_000L))
    }

    @Test
    fun `a failed persist returns false but the in-memory ladder still throttles`() {
        // The state file's parent is a regular FILE: every write must fail.
        val blocker = tmp.newFile()
        val file = File(blocker, "unlock-throttle.json")
        val throttle = VaultUnlockThrottle(stateFile = file)

        assertFalse(throttle.registerFailure(10L))
        assertFalse(throttle.registerFailure(10L))
        // Best-effort persistence: this session is throttled anyway.
        assertEquals(1_000L, throttle.remainingLockoutMs(10L))
        assertFalse(throttle.registerSuccess(20L))
        assertEquals(0L, throttle.remainingLockoutMs(20L))
    }
}
