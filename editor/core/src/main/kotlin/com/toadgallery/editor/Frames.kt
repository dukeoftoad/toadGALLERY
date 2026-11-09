package com.toadgallery.editor

/**
 * The frame system (r38, Phase 7 v2 --- the campaign's closing round;
 * the design contract is `docs/phase-7/frames.md`, decision set 1-6).
 *
 * A frame is a SINGLE selection like the look (the `LutSelection`
 * shape), not a layer list: one mounting at a time. The bundled set is
 * PARAMETRIC CODE (the r35 verdict carried: authored in code, no image
 * asset); imported frames are bitmaps stretched to the canvas.
 *
 * Pure Kotlin, JVM-tested (the repo's ADR-0011 convention): the
 * geometry functions are pure over integers; only the Canvas pass
 * lives on the Android side, in `FrameRender.kt`, through THIS object's
 * layout math for the preview and the export --- one code path.
 */

/**
 * The recipe's additive frame selection: absent (null) = no frame (a
 * pre-r38 sidecar or a clean session); ONE frame at a time.
 */
data class FrameSelection(
    val frameId: String,
    /** 1..100 -> thicknessPx = max(1, widthPct x minEdge / 1000), the r37 kernels' formula. */
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
 * The bundled-frame registry (r38; the `BundledStickers` pattern for
 * parametric code): five authored geometry functions, pure over
 * integers, pinned by exact `PixelRect` lists in `BundledFramesTest`.
 */
object BundledFrames {

    /** One bundled frame: the registry id and its display name (a proper noun --- not localized). */
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

    /**
     * One frame's geometry: filled rects plus holes (the film sprockets
     * punch through to the photo). Pure over integers; the holes list
     * is empty for every def but `film`.
     */
    data class FrameGeometry(val rects: List<PixelRect>, val holes: List<PixelRect>)

    /**
     * The authored geometry for [id] at thickness [t] over a [w] x [h]
     * canvas: `(t, w, h) -> rects + holes`. Null for an unresolvable id
     * (the tolerance contract: the caller degrades to no frame).
     */
    fun geometry(id: String): ((Int, Int, Int) -> FrameGeometry)? = when (id) {
        "plain" -> ::plain
        "inner-line" -> ::innerLine
        "double-line" -> ::doubleLine
        "film" -> ::film
        "polaroid" -> ::polaroid
        else -> null
    }

    // ---- The authored functions (the geometry IS the specification) ----

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

    /**
     * Sprocket bands of height t top and bottom; holes max(2, t/2) wide
     * every t (first at t/2), centred in the band --- the holes punch
     * through to the photo.
     */
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
 * The pure layout pass (JVM-tested): resolves a selection into the
 * pixel-space geometry the compositor executes --- the thickness over
 * the canvas min-edge, the authored rects at the origin offset. For an
 * imported frame the layout is trivial (the full canvas) and the
 * compositor stretches the bitmap --- the resolution split is the
 * sticker round's (`StickerCompositor.Resolved`).
 */
object FrameLayout {

    /** The border thickness: max(1, widthPct x minEdge / 1000), integer floor --- the r37 kernels' formula. */
    fun thicknessPx(widthPct: Int, minEdge: Int): Int = maxOf(1, widthPct * minEdge / 1000)

    /**
     * The parametric geometry for a selection over a [w] x [h] canvas:
     * null when the frameId is unresolvable (the tolerance contract ---
     * the caller renders no frame).
     */
    fun geometryFor(selection: FrameSelection, w: Int, h: Int): BundledFrames.FrameGeometry? {
        val authored = BundledFrames.geometry(selection.frameId) ?: return null
        val t = thicknessPx(selection.widthPct, minOf(w, h))
        return authored(t, w, h)
    }
}
