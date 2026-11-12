package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve deck's JVM pins (r68, the photo pro round): the channel
 * accessors, the commit normalization, and the three rules the plot
 * surface's gestures obey (hit-testing, insertion, the drag clamp).
 *
 * Pure JVM, no compose/android (ADR-0011) --- this class is one of the
 * Lilypad-prefixed set the editor CI filter runs.
 */
class LilypadCurveDeckMathTest {

    private val identity = LilypadCurveMath.IDENTITY

    private fun curve(vararg pairs: Pair<Double, Double>): List<CurvePoint> =
        pairs.map { CurvePoint(it.first, it.second) }

    // ---- the channels ----

    @Test
    fun `a null curve state seeds every channel with the identity`() {
        for (channel in CurveChannel.entries) {
            assertEquals(identity, LilypadCurveDeckMath.pointsFor(channel, null))
        }
    }

    @Test
    fun `an empty channel list seeds the identity, a stored list reads back`() {
        val stored = ToneCurves(
            master = curve(0.0 to 0.0, 0.5 to 0.6, 1.0 to 1.0),
            red = curve(0.0 to 0.0, 1.0 to 0.9),
        )
        assertEquals(stored.red, LilypadCurveDeckMath.pointsFor(CurveChannel.Red, stored))
        assertEquals(identity, LilypadCurveDeckMath.pointsFor(CurveChannel.Green, stored))
        assertEquals(stored.master, LilypadCurveDeckMath.pointsFor(CurveChannel.Master, stored))
        // An explicitly-empty channel list seeds the identity too.
        assertEquals(
            identity,
            LilypadCurveDeckMath.pointsFor(CurveChannel.Blue, ToneCurves(emptyList(), blue = emptyList())),
        )
    }

    @Test
    fun `withPoints writes one channel and leaves the others verbatim`() {
        val start = ToneCurves(
            master = curve(0.0 to 0.0, 0.5 to 0.6, 1.0 to 1.0),
            red = curve(0.0 to 0.0, 1.0 to 0.9),
        )
        val next = LilypadCurveDeckMath.withPoints(
            CurveChannel.Green,
            start,
            curve(0.0 to 0.1, 1.0 to 0.8),
        )
        assertEquals(start.master, next.master)
        assertEquals(start.red, next.red)
        assertEquals(curve(0.0 to 0.1, 1.0 to 0.8), next.green)
        // A null base (a clean session) starts from the identity master.
        val fresh = LilypadCurveDeckMath.withPoints(
            CurveChannel.Blue,
            null,
            curve(0.0 to 0.0, 1.0 to 0.7),
        )
        assertEquals(identity, fresh.master)
        assertEquals(curve(0.0 to 0.0, 1.0 to 0.7), fresh.blue)
    }

    // ---- the commit normalization ----

    @Test
    fun `normalize flattens an all-the-way-back curve to null`() {
        // A curve that was played with and dragged back to the diagonal.
        val played = ToneCurves(curve(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0))
        assertNull(LilypadCurveDeckMath.normalize(played))
        // The pristine identity normalizes to null too.
        assertNull(LilypadCurveDeckMath.normalize(ToneCurves(identity)))
    }

    @Test
    fun `normalize flattens identity channel lists to empty`() {
        val curves = ToneCurves(
            master = curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0),
            red = curve(0.0 to 0.0, 1.0 to 1.0), // the identity, dragged back
            blue = curve(0.0 to 0.1, 1.0 to 1.0),
        )
        val normalized = LilypadCurveDeckMath.normalize(curves)!!
        assertEquals(emptyList<CurvePoint>(), normalized.red)
        assertEquals(curves.blue, normalized.blue)
        assertEquals(curves.master, normalized.master)
    }

    @Test
    fun `normalize reads an empty master as the identity diagonal`() {
        // The codec's tolerant shape: a degenerate master (under two
        // surviving points) reads as absent, and the deck's own commit
        // never writes one --- but the normalization is total anyway.
        val normalized = LilypadCurveDeckMath.normalize(ToneCurves(emptyList()))!!
        assertEquals(identity, normalized.master)
        assertNull(LilypadCurveDeckMath.normalize(normalized))
    }

    @Test
    fun `a channel-only curve survives normalization`() {
        // The forward-state contract: a red curve with an identity
        // master is NOT the identity --- the wire keeps it.
        val curves = ToneCurves(identity, red = curve(0.0 to 0.0, 1.0 to 0.9))
        assertEquals(curves, LilypadCurveDeckMath.normalize(curves))
    }

    // ---- hit-testing ----

    @Test
    fun `the nearest handle inside the radius wins, ties to the lower index`() {
        val xs = floatArrayOf(10f, 100f, 200f)
        val ys = floatArrayOf(50f, 50f, 50f)
        assertEquals(0, LilypadCurveDeckMath.hitIndex(xs, ys, 14f, 54f, 24f))
        assertEquals(1, LilypadCurveDeckMath.hitIndex(xs, ys, 110f, 40f, 24f))
        assertEquals(2, LilypadCurveDeckMath.hitIndex(xs, ys, 210f, 66f, 24f))
        // Outside every radius: curve-space.
        assertEquals(-1, LilypadCurveDeckMath.hitIndex(xs, ys, 55f, 50f, 24f))
        // An exact tie between neighbors 0 and 1 at 55: the lower wins.
        assertEquals(0, LilypadCurveDeckMath.hitIndex(xs, ys, 55f, 50f, 46f))
        // A non-positive radius grabs nothing.
        assertEquals(-1, LilypadCurveDeckMath.hitIndex(xs, ys, 10f, 50f, 0f))
        assertEquals(-1, LilypadCurveDeckMath.hitIndex(FloatArray(0), FloatArray(0), 0f, 0f, 24f))
    }

    // ---- insertion ----

    @Test
    fun `a tap between neighbors inserts at the sorted position`() {
        val base = curve(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0)
        val grown = LilypadCurveDeckMath.insertPoint(base, 0.25, 0.3)!!
        assertEquals(4, grown.size)
        assertEquals(CurvePoint(0.25, 0.3), grown[1])
        // The list stays sorted by construction.
        for (i in 0 until grown.size - 1) assertTrue(grown[i].x < grown[i + 1].x)
    }

    @Test
    fun `insertion coerces the tapped y into the domain`() {
        val grown = LilypadCurveDeckMath.insertPoint(identity, 0.5, 1.7)!!
        assertEquals(CurvePoint(0.5, 1.0), grown[1])
    }

    @Test
    fun `a tap at or beyond the ends refuses to insert`() {
        assertNull(LilypadCurveDeckMath.insertPoint(identity, 0.0, 0.5))
        assertNull(LilypadCurveDeckMath.insertPoint(identity, 1.0, 0.5))
        assertNull(LilypadCurveDeckMath.insertPoint(identity, -0.1, 0.5))
        assertNull(LilypadCurveDeckMath.insertPoint(identity, 1.2, 0.5))
        // A curve that does not span the domain refuses taps outside its
        // reach: the ends are the user's to drag.
        val short = curve(0.2 to 0.1, 0.8 to 0.9)
        assertNull(LilypadCurveDeckMath.insertPoint(short, 0.1, 0.5))
        assertNull(LilypadCurveDeckMath.insertPoint(short, 0.9, 0.5))
    }

    @Test
    fun `insertion refuses at the model cap`() {
        val full = (0..11).map { CurvePoint(it / 11.0, it / 22.0) }
        assertEquals(LilypadCurveMath.MAX_POINTS, full.size)
        assertNull(LilypadCurveDeckMath.insertPoint(full, 0.5, 0.5))
        assertEquals(-1, LilypadCurveDeckMath.insertIndex(full, 0.5))
    }

    // ---- the drag clamp ----

    @Test
    fun `a dragged interior point clamps between its neighbors`() {
        val base = curve(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0)
        // The middle point cannot cross either neighbor...
        val left = LilypadCurveDeckMath.movePoint(base, 1, 0.0, 0.9)
        assertEquals(0.0 + LilypadCurveDeckMath.MIN_X_GAP, left[1].x, 1e-12)
        val right = LilypadCurveDeckMath.movePoint(base, 1, 1.0, 0.1)
        assertEquals(1.0 - LilypadCurveDeckMath.MIN_X_GAP, right[1].x, 1e-12)
        // ...and its y clamps to the domain.
        val above = LilypadCurveDeckMath.movePoint(base, 1, 0.5, 1.4)
        assertEquals(1.0, above[1].y, 1e-12)
        val below = LilypadCurveDeckMath.movePoint(base, 1, 0.5, -0.4)
        assertEquals(0.0, below[1].y, 1e-12)
        // An honest interior move lands exactly where the finger is.
        val honest = LilypadCurveDeckMath.movePoint(base, 1, 0.62, 0.31)
        assertEquals(CurvePoint(0.62, 0.31), honest[1])
    }

    @Test
    fun `the outermost points clamp to the domain instead of a neighbor`() {
        val base = curve(0.25 to 0.1, 0.75 to 0.9)
        val first = LilypadCurveDeckMath.movePoint(base, 0, -0.5, 0.2)
        assertEquals(0.0, first[0].x, 1e-12)
        val last = LilypadCurveDeckMath.movePoint(base, 1, 2.0, 0.8)
        assertEquals(1.0, last[1].x, 1e-12)
        // The first point still cannot cross the second.
        val clamped = LilypadCurveDeckMath.movePoint(base, 0, 0.9, 0.2)
        assertEquals(0.75 - LilypadCurveDeckMath.MIN_X_GAP, clamped[0].x, 1e-12)
    }

    @Test
    fun `the clamp never breaks the strictly-increasing invariant`() {
        val bases = listOf(
            identity,
            curve(0.0 to 0.0, 0.2 to 0.3, 0.5 to 0.5, 0.8 to 0.7, 1.0 to 1.0),
            curve(0.1 to 0.9, 0.5 to 0.5, 0.9 to 0.1),
        )
        for (base in bases) {
            for (index in base.indices) {
                for (tappedX in listOf(-0.3, 0.0, 0.37, 0.5, 0.91, 1.0, 1.3)) {
                    for (tappedY in listOf(-0.2, 0.44, 1.2)) {
                        val moved = LilypadCurveDeckMath.movePoint(base, index, tappedX, tappedY)
                        assertEquals(base.size, moved.size)
                        for (i in 0 until moved.size - 1) {
                            assertTrue(
                                "x collapsed at $i dragging $index to ($tappedX, $tappedY)",
                                moved[i].x < moved[i + 1].x,
                            )
                        }
                        for (p in moved) {
                            assertTrue(p.x in 0.0..1.0 && p.y in 0.0..1.0)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an out-of-range index is a no-op`() {
        val base = curve(0.0 to 0.0, 1.0 to 1.0)
        assertEquals(base, LilypadCurveDeckMath.movePoint(base, -1, 0.5, 0.5))
        assertEquals(base, LilypadCurveDeckMath.movePoint(base, 2, 0.5, 0.5))
    }

    // ---- deletion ----

    @Test
    fun `a long-press deletes a point, never below the floor`() {
        val base = curve(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0)
        assertEquals(curve(0.0 to 0.0, 1.0 to 1.0), LilypadCurveDeckMath.deletePoint(base, 1))
        // The floor: a two-point curve is never deletable.
        assertNull(LilypadCurveDeckMath.deletePoint(identity, 0))
        assertNull(LilypadCurveDeckMath.deletePoint(identity, 1))
        // Out of range is a refusal.
        assertNull(LilypadCurveDeckMath.deletePoint(base, 3))
        assertNull(LilypadCurveDeckMath.deletePoint(base, -1))
    }

    // ---- the round trip through the deck's own operations ----

    @Test
    fun `insert then drag then delete round-trips the identity to null`() {
        var points = LilypadCurveDeckMath.pointsFor(CurveChannel.Master, null)
        points = LilypadCurveDeckMath.insertPoint(points, 0.5, 0.5)!!
        points = LilypadCurveDeckMath.movePoint(points, 1, 0.5, 0.62)
        var curves = LilypadCurveDeckMath.withPoints(CurveChannel.Master, null, points)
        assertEquals(3, LilypadCurveDeckMath.normalize(curves)!!.master.size)
        // Drag the point back onto the diagonal and delete it.
        points = LilypadCurveDeckMath.movePoint(points, 1, 0.5, 0.5)
        curves = LilypadCurveDeckMath.withPoints(CurveChannel.Master, curves, points)
        assertNull(LilypadCurveDeckMath.normalize(curves))
        points = LilypadCurveDeckMath.deletePoint(points, 1)!!
        curves = LilypadCurveDeckMath.withPoints(CurveChannel.Master, curves, points)
        assertNull(LilypadCurveDeckMath.normalize(curves))
    }
}
