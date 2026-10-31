package io.github.dukeoftoad.gallery.data.diag

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [DiagLog] (W1 vault-diagnostics round).
 *
 * The logger's two seams --- [DiagLog.clock] and [DiagLog.sink] --- are
 * swapped in [setUp] (pinned clock, recording sink) and restored in
 * [tearDown], so every assertion runs against exact ring lines without
 * ever touching `android.util.Log` (unmocked on the JVM; ADR-0016 keeps
 * the unit suite emulator- and Robolectric-free). Plain JUnit4, the same
 * shape as the other `data/` suites.
 *
 * Privacy (ADR-0014): the fixtures log synthetic tags and messages only;
 * the contract under test is the entry SHAPE --- stamps, levels, cause
 * chains --- never any caller secret.
 */
class DiagLogTest {

    /** The seams as this JVM found them (fresh instance per test, like JUnit4 runs them). */
    private val stockClock: () -> Long = DiagLog.clock
    private val stockSink: (Char, String, String) -> Unit = DiagLog.sink

    /** What the swapped sink recorded: one (level, tag, message) per entry. */
    private val sinkCalls = mutableListOf<Triple<Char, String, String>>()

    @Before
    fun setUp() {
        DiagLog.clear()
        DiagLog.clock = { FIXED_MILLIS }
        DiagLog.sink = { level, tag, msg -> sinkCalls += Triple(level, tag, msg) }
    }

    @After
    fun tearDown() {
        DiagLog.clock = stockClock
        DiagLog.sink = stockSink
        DiagLog.clear()
    }

    @Test
    fun `i, w and e render the documented level-tag-message line`() {
        DiagLog.i(TAG, "booting")
        DiagLog.w(TAG, "careful")
        DiagLog.e(TAG, "broken")
        assertEquals(
            listOf(
                "01-01 00:00:00.000 I/ToadVault: booting",
                "01-01 00:00:00.000 W/ToadVault: careful",
                "01-01 00:00:00.000 E/ToadVault: broken",
            ),
            DiagLog.snapshot(),
        )
    }

    @Test
    fun `the ring keeps only the newest CAP entries, evicting oldest first`() {
        repeat(DiagLog.CAP + 25) { n -> DiagLog.i(TAG, "entry-$n") }
        val snap = DiagLog.snapshot()
        assertEquals(DiagLog.CAP, snap.size)
        assertEquals("01-01 00:00:00.000 I/ToadVault: entry-25", snap.first())
        assertEquals(
            "01-01 00:00:00.000 I/ToadVault: entry-${DiagLog.CAP + 24}",
            snap.last(),
        )
    }

    @Test
    fun `exactly CAP entries evict nothing`() {
        repeat(DiagLog.CAP) { n -> DiagLog.i(TAG, "entry-$n") }
        val snap = DiagLog.snapshot()
        assertEquals(DiagLog.CAP, snap.size)
        assertEquals("01-01 00:00:00.000 I/ToadVault: entry-0", snap.first())
        assertEquals(
            "01-01 00:00:00.000 I/ToadVault: entry-${DiagLog.CAP - 1}",
            snap.last(),
        )
    }

    @Test
    fun `clear empties the ring`() {
        DiagLog.i(TAG, "one")
        DiagLog.w(TAG, "two")
        DiagLog.clear()
        assertEquals(emptyList<String>(), DiagLog.snapshot())
    }

    @Test
    fun `a throwable renders itself and its causes as extra lines of one entry`() {
        val root = IOException("provider refused")
        val mid = IllegalStateException("index write failed", root)
        DiagLog.e(TAG, "restore failed", RuntimeException("restore crashed", mid))
        assertEquals(
            "01-01 00:00:00.000 E/ToadVault: restore failed\n" +
                " -> java.lang.RuntimeException: restore crashed\n" +
                " -> java.lang.IllegalStateException: index write failed\n" +
                " -> java.io.IOException: provider refused",
            DiagLog.snapshot().single(),
        )
    }

    @Test
    fun `the cause walk stops after three causes however deep the chain is`() {
        val a = Exception("a")
        val b = Exception("b", a)
        val c = Exception("c", b)
        val d = Exception("d", c)
        DiagLog.w(TAG, "deep chain", Exception("e", d))
        val entry = DiagLog.snapshot().single()
        assertEquals(
            "01-01 00:00:00.000 W/ToadVault: deep chain\n" +
                " -> java.lang.Exception: e\n" +
                " -> java.lang.Exception: d\n" +
                " -> java.lang.Exception: c\n" +
                " -> java.lang.Exception: b",
            entry,
        )
        // The fourth cause is beyond the documented depth cap.
        assertEquals(5, entry.lines().size)
    }

    @Test
    fun `a self-referencing cause terminates the walk`() {
        val cyclic = SelfCauseException("cyclic")
        DiagLog.w(TAG, "wedged", cyclic)
        assertEquals(
            "01-01 00:00:00.000 W/ToadVault: wedged\n" +
                " -> ${cyclic.javaClass.name}: cyclic",
            DiagLog.snapshot().single(),
        )
    }

    @Test
    fun `a mutual cause cycle is bounded by the depth cap instead of hanging`() {
        val a = CycleA()
        val b = CycleB(a)
        a.other = b
        DiagLog.e(TAG, "cyclic pair", a)
        val lines = DiagLog.snapshot().single().lines()
        // Completing at all proves termination; the cap pins the shape: the
        // throwable itself plus AT MOST three causes (b, a, b).
        assertEquals(5, lines.size)
        assertEquals(" -> ${a.javaClass.name}: a", lines[1])
        assertEquals(" -> ${b.javaClass.name}: b", lines[2])
        assertEquals(" -> ${a.javaClass.name}: a", lines[3])
        assertEquals(" -> ${b.javaClass.name}: b", lines[4])
    }

    @Test
    fun `snapshot is a detached copy whose mutation never reaches the ring`() {
        DiagLog.i(TAG, "one")
        DiagLog.i(TAG, "two")
        val snap = DiagLog.snapshot()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST") // proving the copy resists mutation
            (snap as MutableList<String>).clear()
        }
        // Detached: later ring churn leaves the taken copy frozen.
        DiagLog.clear()
        DiagLog.i(TAG, "three")
        assertEquals(2, snap.size)
        assertTrue(snap.none { it.contains("three") })
        assertEquals(1, DiagLog.snapshot().size)
    }

    @Test
    fun `the sink receives level, tag and the unprefixed message`() {
        DiagLog.w(TAG, "plain line")
        DiagLog.e(TAG, "with chain", IllegalStateException("boom"))
        assertEquals(
            listOf(
                Triple('W', "ToadVault", "plain line"),
                Triple(
                    'E',
                    "ToadVault",
                    "with chain\n -> java.lang.IllegalStateException: boom",
                ),
            ),
            sinkCalls,
        )
    }

    @Test
    fun `stamps render in UTC regardless of the host timezone`() {
        DiagLog.clock = { NON_TRIVIAL_MILLIS }
        val hostDefault = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            DiagLog.i(TAG, "utc line")
        } finally {
            TimeZone.setDefault(hostDefault)
        }
        val expectedStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(NON_TRIVIAL_MILLIS))
        assertEquals("$expectedStamp I/ToadVault: utc line", DiagLog.snapshot().single())
    }

    // ---- Helpers -------------------------------------------------------

    /** The `cause == itself` spelling: an overridden getCause pointing at itself. */
    private class SelfCauseException(message: String) : Exception(message) {
        override fun getCause(): Throwable = this
    }

    /** One half of an a <-> b cause loop. */
    private class CycleA : Exception("a") {
        lateinit var other: Throwable
        override fun getCause(): Throwable = other
    }

    /** The other half: created with its partner, points straight back at it. */
    private class CycleB(val a: CycleA) : Exception("b") {
        override fun getCause(): Throwable = a
    }

    private companion object {
        const val TAG = "ToadVault"

        /** Pinned clock: epoch 0 renders as "01-01 00:00:00.000" in UTC. */
        const val FIXED_MILLIS = 0L

        /** A non-trivial instant for the timezone-stability test. */
        const val NON_TRIVIAL_MILLIS = 1_769_950_800_000L
    }
}
