package com.toadgallery.editor

/**
 * The frame system: a frame is a SINGLE selection (the LutSelection shape), not
 * a layer list. The bundled set is parametric code (authored geometry, no image
 * assets); imported frames are bitmaps stretched to the canvas. Pure Kotlin,
 * JVM-tested; only the Canvas pass lives on Android (`FrameRender.kt`), through
 * this file's shared layout math for preview and export.
 */

/**
 * The recipe's additive frame selection: absent (null) = no frame; ONE frame at
 * a time.
 */
data class FrameSelection(
    val frameId: String,
    /** 1..100 -> thicknessPx = max(1, widthPct x minEdge / 1000). */
    val widthPct: Int = DEFAULT_WIDTH_PCT,
    /** Full 0xAARRGGBB; the swatches pick it, the render uses it as authored (opaque palette). */
    val colorArgb: Long = DEFAULT_COLOR,
) {
    init {
        require(frameId.isNotBlank()) { "frameId must not be blank" }
        require(frameId.length <= MAX_ID_LENGTH) { "frameId longer than $MAX_ID_LENGTH chars" }
        require(widthPct in 1..100) { "widthPct must be in 1..100" }
        require(colorArgb in 0..0xFFFFFFFFL) { "colorArgb outside the unsigned ARGB range" }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val DEFAULT_WIDTH_PCT = 20
        const val DEFAULT_COLOR = 0xFFFFFFFFL
    }
}

/**
 * The bundled-frame registry: five authored geometry functions, pure over
 * integers, pinned by exact `PixelRect` lists in `BundledFramesTest`.
 */
object BundledFrames {

    /** One bundled frame: registry id + display name (a proper noun, not localized). */
    data class Entry(val id: String, val displayName: String) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }
    }

    /** The chip-row order (the order the UI shows the frames). */
    val ORDERED: List<Entry> = listOf(
        Entry("plain", "Plain"),
        Entry("inner-line", "Inner line"),
        Entry("double-line", "Double line"),
        Entry("film", "Film"),
        Entry("polaroid", "Polaroid"),
    )

    /** The entry for [id], or null when this build does not bundle it. */
    fun byId(id: String): Entry? = ORDERED.firstOrNull { it.id == id }

    /** One frame's geometry: filled rects plus holes (film sprockets punch through to the photo). */
    data class FrameGeometry(val rects: List<PixelRect>, val holes: List<PixelRect>)

    /** Authored geometry for [id]: `(t, w, h) -> rects + holes`; null for an unresolvable id. */
    fun geometry(id: String): ((Int, Int, Int) -> FrameGeometry)? = when (id) {
        "plain" -> ::plain
        "inner-line" -> ::innerLine
        "double-line" -> ::doubleLine
        "film" -> ::film
        "polaroid" -> ::polaroid
        else -> null
    }

    // ---- The authored functions ----

    /** A solid border of thickness t on all four sides. */
    private fun plain(t: Int, w: Int, h: Int): FrameGeometry = FrameGeometry(
        rects = listOf(
            PixelRect(0, 0, w, t), // top
            PixelRect(0, h - t, w, h), // bottom
            PixelRect(0, 0, t, h), // left
            PixelRect(w - t, 0, w, h), // right
        ),
        holes = emptyList(),
    )

    /** A hairline ring (thickness max(1, t/4)) inset by t. */
    private fun innerLine(t: Int, w: Int, h: Int): FrameGeometry {
        val line = maxOf(1, t / 4)
        return FrameGeometry(
            rects = listOf(
                PixelRect(t, t, w - t, t + line), // top
                PixelRect(t, h - t - line, w - t, h - t), // bottom
                PixelRect(t, t, t + line, h - t), // left
                PixelRect(w - t - line, t, w - t, h - t), // right
            ),
            holes = emptyList(),
        )
    }

    /** The plain border + the inner-line ring. */
    private fun doubleLine(t: Int, w: Int, h: Int): FrameGeometry {
        val base = plain(t, w, h)
        val ring = innerLine(t, w, h)
        return FrameGeometry(rects = base.rects + ring.rects, holes = emptyList())
    }

    /** Sprocket bands of height t; holes max(2, t/2) wide every t, centred in the band. */
    private fun film(t: Int, w: Int, h: Int): FrameGeometry {
        val hole = maxOf(2, t / 2)
        val bandOffset = (t - hole) / 2
        val holesTop = ArrayList<PixelRect>()
        var x = t / 2
        while (x + hole <= w - t / 2) {
            holesTop.add(PixelRect(x, bandOffset, x + hole, bandOffset + hole))
            x += t
        }
        val holesBottom = holesTop.map { PixelRect(it.x0, h - t + bandOffset, it.x1, h - t + bandOffset + hole) }
        return FrameGeometry(
            rects = listOf(
                PixelRect(0, 0, w, t),
                PixelRect(0, h - t, w, h),
            ),
            holes = holesTop + holesBottom,
        )
    }

    /** Border t on three sides, a 4t bottom band --- the classic. */
    private fun polaroid(t: Int, w: Int, h: Int): FrameGeometry = FrameGeometry(
        rects = listOf(
            PixelRect(0, 0, w, t), // top
            PixelRect(0, h - 4 * t, w, h), // the 4t bottom band
            PixelRect(0, 0, t, h), // left
            PixelRect(w - t, 0, w, h), // right
        ),
        holes = emptyList(),
    )
}

/**
 * The pure layout pass (JVM-tested): resolves a selection into pixel-space
 * geometry for the compositor (thickness over the canvas min-edge, authored
 * rects at the origin offset). Imported frames are trivial (the full canvas);
 * the compositor stretches the bitmap.
 */
object FrameLayout {

    /** The border thickness: max(1, widthPct x minEdge / 1000), integer floor. */
    fun thicknessPx(widthPct: Int, minEdge: Int): Int = maxOf(1, widthPct * minEdge / 1000)

    /** Parametric geometry for a selection over a [w] x [h] canvas; null when the frameId is unresolvable. */
    fun geometryFor(selection: FrameSelection, w: Int, h: Int): BundledFrames.FrameGeometry? {
        val authored = BundledFrames.geometry(selection.frameId) ?: return null
        val t = thicknessPx(selection.widthPct, minOf(w, h))
        return authored(t, w, h)
    }
}
