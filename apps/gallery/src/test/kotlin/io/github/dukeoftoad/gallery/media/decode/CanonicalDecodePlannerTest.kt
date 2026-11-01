package io.github.dukeoftoad.gallery.media.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Phase 4 canonical decode plan (`docs/phase-4/phase-4-contract-
 * audit-and-ente-ai-plan.md`, change 6): [CanonicalDecodePlanner] is the
 * pure, JVM-testable half of the decode contract, so every rule it
 * encodes --- validity, power-of-two sampling, orientation swap,
 * aspect-preserving target, ARGB_8888 estimate, and the deterministic
 * too-large refusal --- is pinned here before any Android decoder runs.
 * If a rule changes, a test here breaks first; that is the point.
 *
 * The grid tests run with a deliberately generous budget so they probe
 * GEOMETRY (sampling/target math) independently of the budget gate,
 * which has its own dedicated tests below.
 */
class CanonicalDecodePlannerTest {

    /** Budget large enough that no grid case is refused; geometry only. */
    private val generousBudget: Long = 2_000_000_000L

    private val budget: Long = CanonicalDecodePlanner.DEFAULT_MEMORY_BUDGET_BYTES

    // ------------------------------------------------------------------
    // Valid plans: no-op, sampling, orientation
    // ------------------------------------------------------------------

    @Test
    fun `source smaller than the cap is a no-op plan`() {
        val plan = CanonicalDecodePlanner.plan(800, 600, 0)!!
        assertEquals(1, plan.inSampleSize)
        assertEquals(800, plan.targetWidth)
        assertEquals(600, plan.targetHeight)
        assertEquals(0, plan.orientationDegrees)
        assertTrue(!plan.swapDimensionsForUpright)
        assertEquals(800L * 600 * 4, plan.estimatedBitmapBytes)
    }

    @Test
    fun `4000x3000 landscape samples by four for the default 1024 target`() {
        val plan = CanonicalDecodePlanner.plan(4000, 3000, 0)!!
        assertEquals(4, plan.inSampleSize)
        assertEquals(1024, plan.targetWidth)
        assertEquals(768, plan.targetHeight)
        assertTrue(!plan.swapDimensionsForUpright)
        assertEquals(1024L * 768 * 4, plan.estimatedBitmapBytes)
    }

    @Test
    fun `2048x2048 square samples by two`() {
        val plan = CanonicalDecodePlanner.plan(2048, 2048, 0)!!
        assertEquals(2, plan.inSampleSize)
        assertEquals(1024, plan.targetWidth)
        assertEquals(1024, plan.targetHeight)
        assertEquals(1024L * 1024 * 4, plan.estimatedBitmapBytes)
    }

    @Test
    fun `a source one pixel over the cap still samples by two`() {
        // Power-of-two granularity: 1025 cannot sample by one (the decoded
        // intermediate would exceed 2x the needed width), so it drops to 2.
        val plan = CanonicalDecodePlanner.plan(1025, 768, 0)!!
        assertEquals(2, plan.inSampleSize)
        assertEquals(1024, plan.targetWidth)
        assertEquals(767, plan.targetHeight)
    }

    @Test
    fun `90-degree orientation swaps to an upright landscape target`() {
        val plan = CanonicalDecodePlanner.plan(3000, 4000, 90)!!
        assertTrue(plan.swapDimensionsForUpright)
        assertEquals(1024, plan.targetWidth)
        assertEquals(768, plan.targetHeight)
        assertEquals(4, plan.inSampleSize)
        assertEquals(1024L * 768 * 4, plan.estimatedBitmapBytes)
    }

    @Test
    fun `270-degree orientation produces the same upright geometry as 90`() {
        // 90 and 270 swap dimensions the same way; only the rotation the
        // decoder must apply differs --- the plan ECHOES that angle, so
        // equality is asserted on the geometry fields, not the whole plan.
        val quarter = CanonicalDecodePlanner.plan(3000, 4000, 90)!!
        val threeQuarter = CanonicalDecodePlanner.plan(3000, 4000, 270)!!
        assertEquals(90, quarter.orientationDegrees)
        assertEquals(270, threeQuarter.orientationDegrees)
        assertEquals(quarter.inSampleSize, threeQuarter.inSampleSize)
        assertEquals(quarter.targetWidth, threeQuarter.targetWidth)
        assertEquals(quarter.targetHeight, threeQuarter.targetHeight)
        assertEquals(quarter.swapDimensionsForUpright, threeQuarter.swapDimensionsForUpright)
        assertEquals(quarter.estimatedBitmapBytes, threeQuarter.estimatedBitmapBytes)
    }

    @Test
    fun `180-degree orientation keeps dimensions and does not swap`() {
        val plan = CanonicalDecodePlanner.plan(3000, 4000, 180)!!
        assertTrue(!plan.swapDimensionsForUpright)
        assertEquals(768, plan.targetWidth)
        assertEquals(1024, plan.targetHeight)
        assertEquals(4, plan.inSampleSize)
    }

    @Test
    fun `upright source below the cap stays a no-op even when rotated`() {
        val plan = CanonicalDecodePlanner.plan(600, 800, 90)!!
        assertTrue(plan.swapDimensionsForUpright)
        assertEquals(1, plan.inSampleSize)
        assertEquals(800, plan.targetWidth)
        assertEquals(600, plan.targetHeight)
    }

    @Test
    fun `sampling fits the needed dims in ENCODED orientation for a 90-degree plan`() {
        val plan = CanonicalDecodePlanner.plan(3000, 4000, 90)!!
        // Needed width/height are the target dims mapped back through the
        // swap: sampling runs on the encoded bitmap BEFORE rotation.
        val neededWidth = plan.targetHeight
        val neededHeight = plan.targetWidth
        assertTrue(plan.srcWidth / plan.inSampleSize <= neededWidth)
        assertTrue(plan.srcHeight / plan.inSampleSize <= neededHeight)
    }

    @Test
    fun `wide panorama samples to fit its needed dims`() {
        val plan = CanonicalDecodePlanner.plan(8000, 1000, 0)!!
        assertEquals(8, plan.inSampleSize)
        assertEquals(1024, plan.targetWidth)
        assertEquals(128, plan.targetHeight)
        assertEquals(1024L * 128 * 4, plan.estimatedBitmapBytes)
    }

    @Test
    fun `extreme panorama keeps at least a one-pixel short edge`() {
        val plan = CanonicalDecodePlanner.plan(10000, 10, 0)!!
        assertEquals(1024, plan.targetWidth)
        assertEquals(1, plan.targetHeight)
        assertEquals(16, plan.inSampleSize)
        assertEquals(1024L * 4, plan.estimatedBitmapBytes)
    }

    // ------------------------------------------------------------------
    // Sampling invariants: power of two, minimal, never below the need
    // ------------------------------------------------------------------

    @Test
    fun `sample size is always a power of two`() {
        for (width in intArrayOf(17, 100, 768, 1025, 2048, 4000, 7919, 10000)) {
            for (height in intArrayOf(17, 100, 768, 1025, 2048, 3000, 7919, 10000)) {
                val plan = CanonicalDecodePlanner.plan(width, height, 0, 1024, generousBudget)!!
                val sample = plan.inSampleSize
                assertTrue("sample $sample must be a power of two", sample and (sample - 1) == 0)
                assertTrue(sample >= 1)
            }
        }
    }

    @Test
    fun `sample size is the SMALLEST power of two that fits the need`() {
        for (width in intArrayOf(1025, 2048, 4000, 8000, 10000)) {
            for (height in intArrayOf(768, 1024, 3000)) {
                for (orientation in intArrayOf(0, 90, 180, 270)) {
                    val plan = CanonicalDecodePlanner.plan(
                        width,
                        height,
                        orientation,
                        1024,
                        generousBudget,
                    )!!
                    if (plan.inSampleSize == 1) continue
                    val neededWidth =
                        if (plan.swapDimensionsForUpright) plan.targetHeight else plan.targetWidth
                    val neededHeight =
                        if (plan.swapDimensionsForUpright) plan.targetWidth else plan.targetHeight
                    val half = plan.inSampleSize / 2
                    assertTrue(
                        "half sample $half must NOT fit for ${width}x$height@$orientation",
                        plan.srcWidth / half > neededWidth || plan.srcHeight / half > neededHeight,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Memory budget: deterministic refusal, no OOM roulette
    // ------------------------------------------------------------------

    @Test
    fun `budget exceeded at sample one returns null even with a small target`() {
        // 20000x20000 full-resolution ARGB_8888 is ~1.5 GiB: no inSampleSize
        // hint is trustworthy enough to gamble on, so the planner refuses.
        assertNull(CanonicalDecodePlanner.plan(20000, 20000, 0, 1024, budget))
        assertNull(CanonicalDecodePlanner.plan(20000, 20000, 0, 4096, budget))
    }

    @Test
    fun `the default budget refuses the 20000x20000 case without explicit arguments`() {
        assertNull(CanonicalDecodePlanner.plan(20000, 20000, 0))
    }

    @Test
    fun `budget boundary is inclusive`() {
        // 4000x3000x4 = exactly 48,000,000 bytes.
        val atBudget = CanonicalDecodePlanner.plan(4000, 3000, 0, 1024, 48_000_000L)
        assertTrue("exactly-at-budget must plan", atBudget != null)
        assertNull(CanonicalDecodePlanner.plan(4000, 3000, 0, 1024, 47_999_999L))
    }

    @Test
    fun `huge source with a generous budget still plans deterministically`() {
        val plan = CanonicalDecodePlanner.plan(20000, 20000, 0, 1024, generousBudget)!!
        assertEquals(32, plan.inSampleSize)
        assertEquals(1024, plan.targetWidth)
        assertEquals(1024, plan.targetHeight)
    }

    @Test
    fun `estimated bytes are four per target pixel`() {
        val plan = CanonicalDecodePlanner.plan(1000, 500, 0, 512)!!
        assertEquals(512, plan.targetWidth)
        assertEquals(256, plan.targetHeight)
        assertEquals(512L * 256 * 4, plan.estimatedBitmapBytes)
        assertEquals(4, CanonicalDecodePlanner.BYTES_PER_PIXEL)
    }

    // ------------------------------------------------------------------
    // Invalid inputs: every one returns null, never a nonsense plan
    // ------------------------------------------------------------------

    @Test
    fun `non-positive width is rejected`() {
        assertNull(CanonicalDecodePlanner.plan(0, 300, 0))
        assertNull(CanonicalDecodePlanner.plan(-1, 300, 0))
    }

    @Test
    fun `non-positive height is rejected`() {
        assertNull(CanonicalDecodePlanner.plan(300, 0, 0))
        assertNull(CanonicalDecodePlanner.plan(300, -1, 0))
    }

    @Test
    fun `zero-by-zero is rejected`() {
        assertNull(CanonicalDecodePlanner.plan(0, 0, 0))
    }

    @Test
    fun `off-axis orientations are rejected`() {
        for (orientation in intArrayOf(-90, -1, 1, 45, 89, 91, 179, 181, 269, 271, 359, 360)) {
            assertNull(
                "orientation $orientation must be rejected",
                CanonicalDecodePlanner.plan(4000, 3000, orientation),
            )
        }
    }

    @Test
    fun `max dimension below eight is rejected`() {
        assertNull(CanonicalDecodePlanner.plan(64, 64, 0, 7))
        assertNull(CanonicalDecodePlanner.plan(64, 64, 0, 0))
        assertNull(CanonicalDecodePlanner.plan(64, 64, 0, -1024))
    }

    @Test
    fun `max dimension of eight is accepted`() {
        val plan = CanonicalDecodePlanner.plan(64, 64, 0, 8)!!
        assertEquals(8, plan.targetWidth)
        assertEquals(8, plan.targetHeight)
        assertEquals(8, plan.inSampleSize)
        assertEquals(8L * 8 * 4, plan.estimatedBitmapBytes)
    }

    // ------------------------------------------------------------------
    // Target geometry: capped on both edges, aspect within a pixel
    // ------------------------------------------------------------------

    @Test
    fun `upright target never exceeds the cap on either edge`() {
        val sizes = listOf(
            4000 to 3000, 3000 to 4000, 8000 to 1000, 1000 to 8000,
            10000 to 3333, 10000 to 10, 1024 to 1024, 1025 to 768,
            600 to 800, 8 to 8,
        )
        for ((width, height) in sizes) {
            for (orientation in intArrayOf(0, 90, 180, 270)) {
                val plan = CanonicalDecodePlanner.plan(
                    width,
                    height,
                    orientation,
                    1024,
                    generousBudget,
                )!!
                assertTrue(
                    "target width ${plan.targetWidth} over the cap for ${width}x$height@$orientation",
                    plan.targetWidth <= 1024,
                )
                assertTrue(
                    "target height ${plan.targetHeight} over the cap for ${width}x$height@$orientation",
                    plan.targetHeight <= 1024,
                )
                assertTrue(plan.targetWidth >= 1 && plan.targetHeight >= 1)
            }
        }
    }

    @Test
    fun `aspect ratio is preserved within one pixel`() {
        val sizes = listOf(
            4000 to 3000, 3000 to 4000, 8000 to 1000, 1000 to 8000,
            10000 to 3333, 10000 to 10, 1025 to 768, 7919 to 5003,
        )
        for ((width, height) in sizes) {
            for (orientation in intArrayOf(0, 90, 180, 270)) {
                val plan = CanonicalDecodePlanner.plan(
                    width,
                    height,
                    orientation,
                    1024,
                    generousBudget,
                )!!
                val uprightWidth = if (plan.swapDimensionsForUpright) height else width
                val uprightHeight = if (plan.swapDimensionsForUpright) width else height
                val longSide = max(uprightWidth, uprightHeight)
                val shortSide = min(uprightWidth, uprightHeight)
                if (longSide <= 1024) {
                    // No-op plan: the target IS the upright source.
                    assertEquals(uprightWidth, plan.targetWidth)
                    assertEquals(uprightHeight, plan.targetHeight)
                } else {
                    val targetLong = max(plan.targetWidth, plan.targetHeight)
                    val targetShort = min(plan.targetWidth, plan.targetHeight)
                    assertEquals(1024, targetLong)
                    val exactShort = shortSide.toDouble() * 1024 / longSide
                    assertTrue(
                        "short edge $targetShort vs exact $exactShort for ${width}x$height@$orientation",
                        abs(targetShort - exactShort) <= 1.0,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------

    @Test
    fun `default arguments equal an explicit 1024 cap and 48 MiB budget`() {
        assertEquals(
            CanonicalDecodePlanner.plan(4000, 3000, 0),
            CanonicalDecodePlanner.plan(4000, 3000, 0, 1024, 48L * 1024 * 1024),
        )
        assertEquals(1024, CanonicalDecodePlanner.DEFAULT_MAX_DIMENSION)
        assertEquals(48L * 1024 * 1024, CanonicalDecodePlanner.DEFAULT_MEMORY_BUDGET_BYTES)
    }
}
