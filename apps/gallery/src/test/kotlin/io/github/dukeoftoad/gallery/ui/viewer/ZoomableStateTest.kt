package io.github.dukeoftoad.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ZoomableState] (issue #50 follow-up slice).
 *
 * No emulator, no Robolectric (ADR-0016) --- [ZoomableState] is deliberately
 * a zero-import, pure-float class, so the whole zoom/pan contract is pinned
 * here with exact, hand-computed viewports and content sizes. The cases
 * mirror how the viewer actually drives the state: pinches around a
 * centroid, single-finger pans, and double taps, across the two
 * `ContentScale.Fit` letterbox orientations (landscape content in a portrait
 * viewport, and the reverse) plus content larger than the viewport on both
 * axes. The [TOLERANCE] only absorbs float rounding; every clamping
 * assertion is exact by construction.
 */
class ZoomableStateTest {

    @Test
    fun `fresh state is the identity transform`() {
        val state = ZoomableState()
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `zoom constants are the gallery-standard picks`() {
        // The slice's pinned contract: a 5x pinch ceiling and a 2.5x
        // double-tap boost, both exposed as consts for the UI and tests.
        assertEquals(1f, ZoomableState.MIN_SCALE, TOLERANCE)
        assertEquals(5f, ZoomableState.MAX_SCALE, TOLERANCE)
        assertEquals(2.5f, ZoomableState.DOUBLE_TAP_BOOST, TOLERANCE)
    }

    @Test
    fun `centroid pinch zooms within bounds and keeps the finger point stable`() {
        val state = ZoomableState()
        // 2000x2000 content fitted into a 1000x1000 viewport fills it
        // exactly, so at scale s the content may pan up to 500 * (s - 1)
        // per axis. Zooming 2x around (300, 700) must keep the content
        // point under the fingers there.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 2f,
            centroidX = 300f,
            centroidY = 700f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 2f, offsetX = 200f, offsetY = -200f)
        assertEquals(300f, state.mappedX(viewportWidth = 1000f, x = 300f), TOLERANCE)
        assertEquals(700f, state.mappedY(viewportHeight = 1000f, y = 700f), TOLERANCE)
        assertTrue(state.isZoomed)

        // Compounding 2x -> 3x on the same anchor keeps that point stable
        // while the offsets grow to keep it so.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 1.5f,
            centroidX = 300f,
            centroidY = 700f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 3f, offsetX = 400f, offsetY = -400f)
        assertEquals(300f, state.mappedX(viewportWidth = 1000f, x = 300f), TOLERANCE)
        assertEquals(700f, state.mappedY(viewportHeight = 1000f, y = 700f), TOLERANCE)
    }

    @Test
    fun `zooming out below 1x clamps to rest and forces offsets to zero`() {
        val state = ZoomableState()
        // Landscape content in a portrait viewport: fitted 1000x500, so at
        // 2x the horizontal pan range is 500 and the vertical one is 0.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 2f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        state.onTransform(
            panX = -600f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(state, scale = 2f, offsetX = -500f, offsetY = 0f)

        // Pinching in to a nominal 0.5x cannot leave the 1x floor, and the
        // floor always re-centers: offsets are forced to zero.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 0.25f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `zooming past the ceiling clamps at MAX_SCALE`() {
        val state = ZoomableState()
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 10f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertEquals(ZoomableState.MAX_SCALE, state.scale, TOLERANCE)
        assertEquals(5f, state.scale, TOLERANCE)
        assertTransform(state, scale = 5f, offsetX = 0f, offsetY = 0f)

        // A further spread changes nothing: the clamped zoom ratio is 1, so
        // not even the centroid compensation fires.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 2f,
            centroidX = 300f,
            centroidY = 700f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 5f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `pan at 1x is locked`() {
        val state = ZoomableState()
        // A fitted photo always fits inside the viewport, so at 1x there is
        // nowhere legal to pan to: both offsets stay zero no matter the drag.
        state.onTransform(
            panX = 500f,
            panY = 700f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `landscape content in a portrait viewport pans to the edge and locks vertically at 2x`() {
        // Fitted 1000x500: at 2x the scaled content is 2000x1000 inside a
        // 1000x2000 viewport --- horizontally pannable to 500, vertically
        // still fully on screen, so the vertical pan is locked to 0.
        val pannedLeft = zoomedState(scale = 2f)
        pannedLeft.onTransform(
            panX = -600f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(pannedLeft, scale = 2f, offsetX = -500f, offsetY = 0f)

        val pannedRight = zoomedState(scale = 2f)
        pannedRight.onTransform(
            panX = 600f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(pannedRight, scale = 2f, offsetX = 500f, offsetY = 0f)

        val verticalLocked = zoomedState(scale = 2f)
        verticalLocked.onTransform(
            panX = 0f,
            panY = -700f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(verticalLocked, scale = 2f, offsetX = 0f, offsetY = 0f)

        // At MAX_SCALE the scaled height (2500) finally exceeds the 2000px
        // viewport, so vertical panning unlocks with a 250 range.
        val maxZoom = zoomedState(scale = 5f)
        maxZoom.onTransform(
            panX = 0f,
            panY = -600f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
        )
        assertTransform(maxZoom, scale = 5f, offsetX = 0f, offsetY = -250f)
    }

    @Test
    fun `portrait content in a landscape viewport pans to the edge and locks horizontally at 2x`() {
        // The mirrored letterbox: fitted 500x1000 inside a 2000x1000
        // viewport --- vertical pan range 500, horizontal locked to 0.
        val pannedUp = zoomedState(scale = 2f, landscapeViewport = true)
        pannedUp.onTransform(
            panX = 0f,
            panY = -600f,
            zoomFactor = 1f,
            centroidX = 1000f,
            centroidY = 500f,
            viewportWidth = 2000f,
            viewportHeight = 1000f,
            contentWidth = 1000f,
            contentHeight = 2000f,
        )
        assertTransform(pannedUp, scale = 2f, offsetX = 0f, offsetY = -500f)

        val pannedDown = zoomedState(scale = 2f, landscapeViewport = true)
        pannedDown.onTransform(
            panX = 0f,
            panY = 600f,
            zoomFactor = 1f,
            centroidX = 1000f,
            centroidY = 500f,
            viewportWidth = 2000f,
            viewportHeight = 1000f,
            contentWidth = 1000f,
            contentHeight = 2000f,
        )
        assertTransform(pannedDown, scale = 2f, offsetX = 0f, offsetY = 500f)

        val horizontalLocked = zoomedState(scale = 2f, landscapeViewport = true)
        horizontalLocked.onTransform(
            panX = -700f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 1000f,
            centroidY = 500f,
            viewportWidth = 2000f,
            viewportHeight = 1000f,
            contentWidth = 1000f,
            contentHeight = 2000f,
        )
        assertTransform(horizontalLocked, scale = 2f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `content larger than the viewport on both axes stays bounded while panning`() {
        // 2000x2000 content in a 1000x1000 viewport: both axes have a
        // 500 * (s - 1) range, so an absurd drag still stops at the edge.
        val state = zoomedState(scale = 3f)
        state.onTransform(
            panX = -5000f,
            panY = 5000f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 3f, offsetX = -1000f, offsetY = 1000f)

        val maxZoom = zoomedState(scale = 5f)
        maxZoom.onTransform(
            panX = -5000f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(maxZoom, scale = 5f, offsetX = -2000f, offsetY = 0f)
    }

    @Test
    fun `double tap at 1x zooms to the boost anchored on the tap point`() {
        val state = ZoomableState()
        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
            tapX = 300f,
            tapY = 800f,
        )
        // The 2.5x boost anchored on (300, 800): the content under the tap
        // stays under the tap, exactly what a gallery viewer promises.
        assertTransform(state, scale = 2.5f, offsetX = 300f, offsetY = -450f)
        assertEquals(300f, state.mappedX(viewportWidth = 1000f, x = 300f), TOLERANCE)
        assertEquals(800f, state.mappedY(viewportHeight = 1000f, y = 800f), TOLERANCE)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `double tap anchor clamps so the letterboxed content stays on screen`() {
        val state = ZoomableState()
        // Landscape content in a portrait viewport: at 2.5x the scaled
        // height is 1250 inside a 2000px viewport, so the requested vertical
        // anchor of 450 is impossible and clamps to centered 0; the
        // horizontal anchor of 150 is legal and survives.
        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 2000f,
            contentHeight = 1000f,
            tapX = 400f,
            tapY = 700f,
        )
        assertTransform(state, scale = 2.5f, offsetX = 150f, offsetY = 0f)
    }

    @Test
    fun `double tap while zoomed resets to 1x centered`() {
        val state = zoomedState(scale = 2f)
        state.onTransform(
            panX = -600f,
            panY = 0f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 2f, offsetX = -500f, offsetY = 0f)

        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
            tapX = 300f,
            tapY = 700f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `double tap zooms in from pinch noise just above 1x`() {
        val state = ZoomableState()
        // A pinch that settles at 1.005x is visually at rest (below the
        // zoomed epsilon), so a double tap must zoom IN, and it lands on the
        // boost factor even from the noisy starting scale.
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 1.005f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertEquals(1.005f, state.scale, TOLERANCE)
        assertFalse(state.isZoomed)

        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
            tapX = 500f,
            tapY = 500f,
        )
        assertEquals(ZoomableState.DOUBLE_TAP_BOOST, state.scale, TOLERANCE)
        assertTransform(state, scale = 2.5f, offsetX = 0f, offsetY = 0f)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `double tap resets once meaningfully above the zoomed epsilon`() {
        val state = ZoomableState()
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 1.02f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertEquals(1.02f, state.scale, TOLERANCE)
        assertTrue(state.isZoomed)

        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
            tapX = 500f,
            tapY = 500f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `reset from a zoomed and panned state returns to the identity transform`() {
        val state = ZoomableState()
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 3f,
            centroidX = 300f,
            centroidY = 700f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        state.onTransform(
            panX = -2000f,
            panY = 2000f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 3f, offsetX = -1000f, offsetY = 1000f)
        assertTrue(state.isZoomed)

        state.reset()
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `gestures before layout (a zero viewport) are no-ops`() {
        // The gesture surface reports 0x0 until the first layout pass; the
        // transform math must not divide by zero or invent offsets then.
        val state = ZoomableState()
        state.onTransform(
            panX = 100f,
            panY = 100f,
            zoomFactor = 2f,
            centroidX = 300f,
            centroidY = 300f,
            viewportWidth = 0f,
            viewportHeight = 0f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)

        state.doubleTap(
            viewportWidth = 1000f,
            viewportHeight = 0f,
            contentWidth = 2000f,
            contentHeight = 2000f,
            tapX = 300f,
            tapY = 300f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `non-finite zoom factor is ignored`() {
        // A degenerate two-finger spread can hand the detector a NaN or
        // infinite ratio; poisoning the whole transform with it would freeze
        // the photo mid-gesture, so such increments are dropped.
        val state = ZoomableState()
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = Float.NaN,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)

        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = Float.POSITIVE_INFINITY,
            centroidX = 500f,
            centroidY = 500f,
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentWidth = 2000f,
            contentHeight = 2000f,
        )
        assertTransform(state, scale = 1f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `unknown content dimensions degrade to the viewport bounds`() {
        // MediaStore rows can lack the width column (0 = unknown): the clamp
        // then treats the unknown axis as viewport-sized, permissive for
        // letterboxed content but never letting the photo escape the screen.
        val state = ZoomableState()
        state.onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = 2f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 0f,
            contentHeight = 1000f,
        )
        state.onTransform(
            panX = -600f,
            panY = -600f,
            zoomFactor = 1f,
            centroidX = 500f,
            centroidY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            contentWidth = 0f,
            contentHeight = 1000f,
        )
        assertTransform(state, scale = 2f, offsetX = -500f, offsetY = 0f)
    }

    /**
     * A fresh state pinched straight to [scale] around the viewport center,
     * so pan-clamp tests start from a deterministic zoom with zero offsets.
     * Default sizes: 2000x2000 content in a 1000x2000 portrait viewport
     * (landscape content, the primary photo case); pass
     * [landscapeViewport] for the mirrored 2000x1000/1000x2000 letterbox.
     */
    private fun zoomedState(scale: Float, landscapeViewport: Boolean = false): ZoomableState =
        ZoomableState().apply {
            onTransform(
                panX = 0f,
                panY = 0f,
                zoomFactor = scale,
                centroidX = if (landscapeViewport) 1000f else 500f,
                centroidY = if (landscapeViewport) 500f else 1000f,
                viewportWidth = if (landscapeViewport) 2000f else 1000f,
                viewportHeight = if (landscapeViewport) 1000f else 2000f,
                contentWidth = if (landscapeViewport) 1000f else 2000f,
                contentHeight = if (landscapeViewport) 2000f else 1000f,
            )
            check(offsetX == 0f && offsetY == 0f) { "test helper: centered pinch must not pan" }
        }

    /** Asserts the whole transform triple; see [TOLERANCE] for the delta. */
    private fun assertTransform(
        state: ZoomableState,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        assertEquals(scale, state.scale, TOLERANCE)
        assertEquals(offsetX, state.offsetX, TOLERANCE)
        assertEquals(offsetY, state.offsetY, TOLERANCE)
    }

    /**
     * The viewport X that the content-space point [x] lands on under the
     * current transform: `center + (x - center) * scale + offsetX`, i.e.
     * exactly what `Modifier.graphicsLayer` with the default
     * `TransformOrigin.Center` renders. Anchoring tests use it to verify the
     * point under a finger or tap stays under it.
     */
    private fun ZoomableState.mappedX(viewportWidth: Float, x: Float): Float =
        viewportWidth / 2f + (x - viewportWidth / 2f) * scale + offsetX

    /** The vertical twin of [mappedX]. */
    private fun ZoomableState.mappedY(viewportHeight: Float, y: Float): Float =
        viewportHeight / 2f + (y - viewportHeight / 2f) * scale + offsetY

    private companion object {
        /** Float-rounding slack only; clamping assertions are exact by construction. */
        const val TOLERANCE = 0.001f
    }
}
