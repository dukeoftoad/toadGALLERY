package io.github.dukeoftoad.gallery.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM tests for the appearance model (roadmap Phase 3: appearance settings
 * slice). No emulator, no Robolectric (ADR-0016) --- the model is pure
 * Kotlin, so the (de)serialization contract the Settings persistence rests
 * on is pinned value-by-value: defaults keep today's COLLAGE/3/NONE render,
 * clamps bound the stepper, and unknown stored values degrade to the
 * defaults instead of crashing.
 */
class AppearanceTest {

    @Test
    fun `defaults keep the current render`() {
        // COLLAGE / 3 columns / NONE --- exactly what the grids rendered
        // before this slice existed; a fresh install must not change pixels.
        assertEquals(LayoutStyle.COLLAGE, Appearance().layout)
        assertEquals(3, Appearance().columns)
        assertEquals(GroupBy.NONE, Appearance().groupBy)
    }

    @Test
    fun `clamp keeps in-range values untouched`() {
        assertEquals(3, clampColumns(3))
        assertEquals(MIN_COLUMNS, clampColumns(MIN_COLUMNS))
        assertEquals(MAX_COLUMNS, clampColumns(MAX_COLUMNS))
    }

    @Test
    fun `clamp lifts values below the minimum`() {
        assertEquals(MIN_COLUMNS, clampColumns(0))
        assertEquals(MIN_COLUMNS, clampColumns(-7))
        assertEquals(MIN_COLUMNS, clampColumns(MIN_COLUMNS - 1))
    }

    @Test
    fun `clamp caps values above the maximum`() {
        assertEquals(MAX_COLUMNS, clampColumns(7))
        assertEquals(MAX_COLUMNS, clampColumns(Int.MAX_VALUE))
        assertEquals(MAX_COLUMNS, clampColumns(MAX_COLUMNS + 1))
    }

    @Test
    fun `parseLayout maps null and unknown values to COLLAGE`() {
        assertEquals(LayoutStyle.COLLAGE, parseLayout(null))
        assertEquals(LayoutStyle.COLLAGE, parseLayout(""))
        // The wire format is the enum NAME --- lower case is NOT a match.
        assertEquals(LayoutStyle.COLLAGE, parseLayout("collage"))
        assertEquals(LayoutStyle.COLLAGE, parseLayout("masonry"))
    }

    @Test
    fun `parseLayout maps known names to their enum values`() {
        assertEquals(LayoutStyle.COLLAGE, parseLayout("COLLAGE"))
        assertEquals(LayoutStyle.GRID, parseLayout("GRID"))
    }

    @Test
    fun `parseGroupBy maps null and unknown values to NONE`() {
        assertEquals(GroupBy.NONE, parseGroupBy(null))
        assertEquals(GroupBy.NONE, parseGroupBy(""))
        assertEquals(GroupBy.NONE, parseGroupBy("week"))
        assertEquals(GroupBy.NONE, parseGroupBy("day"))
    }

    @Test
    fun `parseGroupBy maps known names to their enum values`() {
        assertEquals(GroupBy.NONE, parseGroupBy("NONE"))
        assertEquals(GroupBy.DAY, parseGroupBy("DAY"))
        assertEquals(GroupBy.MONTH, parseGroupBy("MONTH"))
    }

    @Test
    fun `layout serialize and parse round-trip every value`() {
        LayoutStyle.entries.forEach { layout ->
            assertEquals(layout, parseLayout(serializeLayout(layout)))
        }
    }

    @Test
    fun `groupBy serialize and parse round-trip every value`() {
        GroupBy.entries.forEach { groupBy ->
            assertEquals(groupBy, parseGroupBy(serializeGroupBy(groupBy)))
        }
    }

    @Test
    fun `appearance equality follows its fields`() {
        val base = Appearance()
        assertEquals(base, Appearance(LayoutStyle.COLLAGE, 3, GroupBy.NONE))
        assertEquals(base, base.copy()) // data-class copy keeps equality
        assertNotEquals(base, base.copy(layout = LayoutStyle.GRID))
        assertNotEquals(base, base.copy(columns = 4))
        assertNotEquals(base, base.copy(groupBy = GroupBy.DAY))
    }
}
