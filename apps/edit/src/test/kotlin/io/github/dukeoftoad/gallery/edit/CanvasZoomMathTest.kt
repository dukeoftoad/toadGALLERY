package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * F-72's JVM pins (ADR-0011: the geometry is pure, so the contract the
 * editor canvas's pinch/pan/double-tap rides on is tested without any
 * Android surface). The properties the UX leans on:
 *
 *  - the zoom BOUNDS: 1x (fit) .. 4x (the proposal's ceiling), both clamps
 *    inclusive, from any raw gesture scale;
 *  - the centroid contract: zooming about a pinch centroid keeps that
 *    content point under the fingers (the pan then shifts it, minus the
 *    bounds);
 *  - the bounds contract: at 1x the offset is exactly zero, and beyond it
 *    each axis parks at the overflow edge instead of escaping;
 *  - double-tap-to-fit: the identity pose is exactly scale=1 + zero offset,
 *    and a full zoom-in-then-out round trip can end there exactly.
 */
class CanvasZoomMathTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.001f) {
        assertTrue("expected ~$expected, got $actual", abs(expected - actual) < eps)
    }

    @Test
    fun `clampScale bounds the raw pinch into the 1x-4x window`() {
        assertEquals(1f, CanvasZoomMath.clampScale(0.2f))
        assertEquals(1f, CanvasZoomMath.clampScale(0.999f))
        assertEquals(2.5f, CanvasZoomMath.clampScale(2.5f))
        assertEquals(4f, CanvasZoomMath.clampScale(9f))
        assertEquals(4f, CanvasZoomMath.clampScale(Float.MAX_VALUE))
    }

    @Test
    fun `clampOffset forces the fit pose at 1x and clamps to the overflow beyond it`() {
        // At the fit pose (or below) nothing may pan.
        assertEquals(0f, CanvasZoomMath.clampOffset(123f, 1f, 1000f))
        assertEquals(0f, CanvasZoomMath.clampOffset(-1f, 0.5f, 1000f))
        // At 2x over a 1000px axis the overflow is 500px per side.
        assertEquals(500f, CanvasZoomMath.clampOffset(9999f, 2f, 1000f))
        assertEquals(-500f, CanvasZoomMath.clampOffset(-9999f, 2f, 1000f))
        assertEquals(137f, CanvasZoomMath.clampOffset(137f, 2f, 1000f))
        // A not-yet-measured canvas (size 0) cannot pan at all.
        assertEquals(0f, CanvasZoomMath.clampOffset(50f, 4f, 0f))
    }

    @Test
    fun `a pure zoom about the canvas center keeps the offset at zero`() {
        // Zoom 2x about the center of a 1000x800 canvas: the symmetric
        // answer is scale 2, offset (0, 0) --- the center never moves.
        val out = CanvasZoomMath.step(
            scale = 1f, offsetX = 0f, offsetY = 0f,
            zoomChange = 2f, panX = 0f, panY = 0f,
            centroidX = 500f, centroidY = 400f,
            widthPx = 1000f, heightPx = 800f,
        )
        assertEquals(2f, out[0])
        assertClose(0f, out[1])
        assertClose(0f, out[2])
    }

    @Test
    fun `a zoom about an off-center centroid keeps that point under the fingers`() {
        // Zoom 2x about the point (250, 200) from the fit pose, no pan.
        // The content point under the centroid before the gesture is at
        // 25% of the width / 25% of the height; after a 2x zoom it sits at
        // center + 2*(25% - 50%) = 0% of the width (the left edge) --- so
        // the offset must carry it back to the finger: offset = centroid -
        // center - 2*(rel) with rel = centroid - offset - center.
        // relX = 250 - 0 - 500 = -250 -> offsetX = 250 - 500 - 2*(-250) = 0... 
        // wait: 250 + 0 - 500 - (2 * -250) = -250 + 500 = 250? The formula:
        // rawX = centroid + pan - center - applied*rel = 250 - 500 - (2*-250)
        //      = -250 + 500 = +250. The point that WAS at screen x=250 now
        // sits at 500 + 2*(0.25-0.5)*1000 + 250 = 0 + 250 = 250. Kept.
        val out = CanvasZoomMath.step(
            scale = 1f, offsetX = 0f, offsetY = 0f,
            zoomChange = 2f, panX = 0f, panY = 0f,
            centroidX = 250f, centroidY = 200f,
            widthPx = 1000f, heightPx = 800f,
        )
        assertEquals(2f, out[0])
        assertClose(250f, out[1])
        assertClose(200f, out[2])
        // And the kept point: screen = center + scale*(content-center) + off
        // with content = (250,200) (the fit pose's content == screen) gives
        // 500 + 2*(-250) + 250 = 250 on x, 400 + 2*(-200) + 200 = 200 on y.
    }

    @Test
    fun `pan rides on top of the zoom and parks at the bounds`() {
        // From a 2x pose already panned to its +x edge (+500 over 1000px):
        // another +pan on x parks at the same edge (clamped), y stays.
        val out = CanvasZoomMath.step(
            scale = 2f, offsetX = 500f, offsetY = 0f,
            zoomChange = 1f, panX = 300f, panY = 0f,
            centroidX = 500f, centroidY = 400f,
            widthPx = 1000f, heightPx = 800f,
        )
        assertEquals(2f, out[0])
        // rawX = 500 + 300 - 500 - 1*(500 - 500 - 500) = 300 + 500 = 800 ->
        // clamped to 500.
        assertEquals(500f, out[1])
        assertClose(0f, out[2])
    }

    @Test
    fun `zooming back out to 1x re-centers exactly (the double-tap endpoint)`() {
        // From the 2x/250/200 pose above, zoom back 0.5x about anywhere:
        // the offset must land at exactly (0, 0) --- the fit pose.
        val out = CanvasZoomMath.step(
            scale = 2f, offsetX = 250f, offsetY = 200f,
            zoomChange = 0.5f, panX = 0f, panY = 0f,
            centroidX = 250f, centroidY = 200f,
            widthPx = 1000f, heightPx = 800f,
        )
        assertEquals(1f, out[0])
        assertEquals(0f, out[1])
        assertEquals(0f, out[2])
        assertTrue(CanvasZoomMath.isIdentity(out[0], out[1], out[2]))
    }

    @Test
    fun `a clamped zoom absorbs the excess at the ceiling without jumping`() {
        // At 4x (the ceiling), a further 2x pinch step keeps scale 4 and
        // the pose legal --- no jump past the bound, no NaN.
        val out = CanvasZoomMath.step(
            scale = 4f, offsetX = 0f, offsetY = 0f,
            zoomChange = 2f, panX = 0f, panY = 0f,
            centroidX = 500f, centroidY = 400f,
            widthPx = 1000f, heightPx = 800f,
        )
        assertEquals(4f, out[0])
        assertClose(0f, out[1])
        assertClose(0f, out[2])
        assertFalse(CanvasZoomMath.isIdentity(out[0], out[1], out[2]))
    }

    @Test
    fun `isIdentity is exactly the fit pose`() {
        assertTrue(CanvasZoomMath.isIdentity(1f, 0f, 0f))
        assertTrue(CanvasZoomMath.isIdentity(1f, 0.005f, -0.005f)) // sub-epsilon
        assertFalse(CanvasZoomMath.isIdentity(1.5f, 0f, 0f))
        assertFalse(CanvasZoomMath.isIdentity(1f, 1f, 0f))
        assertFalse(CanvasZoomMath.isIdentity(1f, 0f, -1f))
    }
}
