package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Corner-drag pins: corner geometry, nearest-wins hit-test, engine-bounds clamp, identity snap. */
class LilypadCornerMathTest {

    // ---- the corner geometry ----

    @Test
    fun `the identity corners are the grid's own, in reading order`() {
        val ids = LilypadCornerMath.identityCorners(400, 200)
        assertEquals(
            listOf(
                0.0 to 0.0,
                399.0 to 0.0,
                399.0 to 199.0,
                0.0 to 199.0,
            ),
            ids,
        )
    }

    @Test
    fun `the quad corners displace from their identity corners`() {
        val quad = PerspectiveQuad(
            tlX = 10.0, tlY = -5.0,
            trX = 0.0, trY = 0.0,
            brX = 0.0, brY = 0.0,
            blX = 20.0, blY = 0.0,
        )
        val corners = LilypadCornerMath.quadCorners(quad, 400, 200)
        assertEquals(10.0 to -5.0, corners[0])
        assertEquals(399.0 to 0.0, corners[1])
        assertEquals(399.0 to 199.0, corners[2])
        assertEquals(20.0 to 199.0, corners[3])
    }

    // ---- hit-testing ----

    @Test
    fun `the nearest corner inside the radius wins`() {
        val corners = LilypadCornerMath.identityCorners(400, 200)
        // ~30px from TL, far from everything else.
        assertEquals(0, LilypadCornerMath.hitCorner(corners, 25.0, 16.0, 40.0))
        // Nearer to TR than TL proves the nearest (not the first) corner wins.
        assertEquals(1, LilypadCornerMath.hitCorner(corners, 380.0, 10.0, 40.0))
        // Near BR.
        assertEquals(2, LilypadCornerMath.hitCorner(corners, 395.0, 195.0, 40.0))
        // A 100px-out point is out of reach --- the honest -1.
        assertEquals(-1, LilypadCornerMath.hitCorner(corners, 100.0, 0.0, 40.0))
    }

    @Test
    fun `ties go to the lower index and out-of-radius is minus one`() {
        val corners = LilypadCornerMath.identityCorners(400, 200)
        // Exactly between TL (0,0) and nothing --- on TL itself.
        assertEquals(0, LilypadCornerMath.hitCorner(corners, 0.0, 0.0, 40.0))
        // Every corner out of reach (the middle of the frame).
        assertEquals(-1, LilypadCornerMath.hitCorner(corners, 200.0, 100.0, 40.0))
        // A non-positive radius grabs nothing.
        assertEquals(-1, LilypadCornerMath.hitCorner(corners, 0.0, 0.0, 0.0))
    }

    // ---- the drag clamp ----

    @Test
    fun `a drag displaces exactly one corner from its identity corner`() {
        val quad = PerspectiveQuad.IDENTITY
        val dragged = LilypadCornerMath.dragCornerTo(
            quad, QuadCorner.TopRight, 379.0, 20.0, 400, 200,
        )
        // TR identity is (399, 0): displacement (-20, +20).
        assertEquals(-20.0, dragged.trX, 1e-9)
        assertEquals(20.0, dragged.trY, 1e-9)
        // Every other field is untouched.
        assertEquals(0.0, dragged.tlX, 1e-9)
        assertEquals(0.0, dragged.tlY, 1e-9)
        assertEquals(0.0, dragged.brX, 1e-9)
        assertEquals(0.0, dragged.brY, 1e-9)
        assertEquals(0.0, dragged.blX, 1e-9)
        assertEquals(0.0, dragged.blY, 1e-9)
    }

    @Test
    fun `the clamp is the engine's own bounds, never a shown-then-cut displacement`() {
        // w/4 = 100, h/4 = 50. Drag TR far past both.
        val dragged = LilypadCornerMath.dragCornerTo(
            PerspectiveQuad.IDENTITY, QuadCorner.TopRight, 1000.0, -500.0, 400, 200,
        )
        assertEquals(100.0, dragged.trX, 1e-9)
        assertEquals(-50.0, dragged.trY, 1e-9)
        // The clamp agrees with the engine's own: clamping again is a no-op (the rules cannot drift).
        assertEquals(dragged, dragged.clamped(400, 200))
    }

    @Test
    fun `the keystone rows' quad reads back through the drag clamp identically`() {
        // The surfaces compose: a keystone quad dragged at a corner keeps the untouched corners verbatim.
        val keystone = LilypadKeystoneMath.quadFor(verticalPct = 50, horizontalPct = 0, width = 400, height = 200)
        val dragged = LilypadCornerMath.dragCornerTo(
            keystone, QuadCorner.BottomRight, 350.0, 150.0, 400, 200,
        )
        // BR identity (399, 199): displacement (-49, -49).
        assertEquals(-49.0, dragged.brX, 1e-9)
        assertEquals(-49.0, dragged.brY, 1e-9)
        // The keystone's TL/TR pinches survive verbatim.
        assertEquals(keystone.tlX, dragged.tlX, 1e-9)
        assertEquals(keystone.trX, dragged.trX, 1e-9)
    }

    // ---- the identity snap ----

    @Test
    fun `a release inside the deadband snaps the corner home`() {
        // Deadband: 100/160 = 0.625 in x, 50/160 = 0.3125 in y.
        val nearZero = PerspectiveQuad(
            tlX = 0.5, tlY = -0.2, trX = 0.0, trY = 0.0,
            brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0,
        )
        val snapped = LilypadCornerMath.snapOnRelease(nearZero, QuadCorner.TopLeft, 400, 200)
        assertEquals(0.0, snapped.tlX, 0.0)
        assertEquals(0.0, snapped.tlY, 0.0)
    }

    @Test
    fun `a release outside the deadband keeps the displacement verbatim`() {
        val honest = PerspectiveQuad(
            tlX = 5.0, tlY = 0.0, trX = 0.0, trY = 0.0,
            brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0,
        )
        assertEquals(
            honest,
            LilypadCornerMath.snapOnRelease(honest, QuadCorner.TopLeft, 400, 200),
        )
    }

    @Test
    fun `the snap is per corner, never a whole-quad reset`() {
        val mixed = PerspectiveQuad(
            tlX = 0.1, tlY = 0.1, trX = 30.0, trY = 0.0,
            brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0,
        )
        val snapped = LilypadCornerMath.snapOnRelease(mixed, QuadCorner.TopLeft, 400, 200)
        // TL snapped home; TR's honest 30px survives.
        assertEquals(0.0, snapped.tlX, 0.0)
        assertEquals(30.0, snapped.trX, 1e-9)
    }

    // ---- the pins ----

    @Test
    fun `the touch-target floor matches the curve deck's own`() {
        assertEquals(24, LilypadCornerMath.HIT_RADIUS_DP)
    }

    @Test
    fun `a corner-dragged quad is a legal engine input`() {
        // End-to-end: whatever the drag produces, the warp runs it EXACTLY (clamp is a no-op on it).
        var quad = PerspectiveQuad.IDENTITY
        for (corner in QuadCorner.entries) {
            quad = LilypadCornerMath.dragCornerTo(
                quad, corner, 333.0, 111.0, 400, 200,
            )
        }
        assertEquals(quad, quad.clamped(400, 200))
        assertTrue(quad != PerspectiveQuad.IDENTITY)
    }
}
