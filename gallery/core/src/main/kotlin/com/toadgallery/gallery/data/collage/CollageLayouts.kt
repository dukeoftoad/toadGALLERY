package com.toadgallery.gallery.data.collage

import kotlin.math.roundToInt

/**
 * One slot of a collage template (r27): a rectangle in the template's
 * NORMALIZED unit square --- [left]/[top]/[right]/[bottom] all in
 * 0.0..1.0, with left < right and top < bottom.
 *
 * Deliberately plain floats and NOT a Compose/Android geometry type:
 * this file is the collage round's PURE layout core (the
 * "pure core + thin Android seam" pattern, ADR-0016), JVM-tested without
 * the Android runtime; the Compose seam scales a slot into pixels with
 * two multiplications and the render seam turns it into a destination
 * rectangle (see [CollageLayouts.slotPixels] and
 * [CollageLayouts.centerCropSource]).
 */
data class CollageSlot(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** The slot's normalized width (right - left). */
    val width: Float get() = right - left

    /** The slot's normalized height (bottom - top). */
    val height: Float get() = bottom - top
}

/**
 * One collage template (r27): a stable [id] plus the [slots] its photos
 * fill, tiling the unit square EXACTLY (every template is built from
 * aligned fractions --- halves, thirds, quarters, fifths --- so the
 * slots neither overlap nor leave gaps; the property is pinned by
 * [CollageLayoutsTest] for every template).
 */
data class CollageTemplate(
    val id: String,
    val slots: List<CollageSlot>,
) {
    /** How many photos the template lays out. */
    val slotCount: Int get() = slots.size
}

/**
 * The collage creator's pure layout core (r27, the MI-style
 * organisational UX round): the template catalogue for 2..9 photos plus
 * the two pieces of render math every surface (the on-screen Compose
 * Canvas preview and the exported bitmap) shares.
 *
 * ## The catalogue
 * Grid-variant templates, two per photo count, all on the unit square:
 * plain row/column grids and one "hero + grid" variant per count (one
 * large cell plus a regular grid filling the rest) --- the MI-gallery
 * collage vocabulary. Every template tiles the square exactly; the tests
 * pin exact coverage, pairwise disjointness, in-bounds coordinates and
 * per-count availability, so a future template that would lie fails the
 * build instead of shipping a broken render.
 *
 * ## The render math (shared, pure)
 *  - [slotPixels] scales a normalized slot onto a concrete
 *    width x height canvas. Every edge rounds with the SAME rule
 *    (round-half-up to whole pixels), so two slots that share a
 *    normalized edge share the exact same pixel boundary --- no
 *    pixel-thin background sliver between neighbors --- and the
 *    outermost edges clamp to the canvas.
 *  - [centerCropSource] is the CENTER-CROP source rectangle for a
 *    bitmap of (w x h) pixels filling a destination of a given aspect:
 *    the largest centered sub-rectangle of the bitmap whose aspect
 *    matches the destination's. A landscape photo in a square slot
 *    keeps its middle; a portrait photo in a wide slot keeps its band
 *    --- the same visual rule [com.toadgallery.gallery.ui.media
 *    .MediaThumb]'s Crop scale applies at cell size.
 *
 * Pure Kotlin, JVM-tested ([CollageLayoutsTest]) per ADR-0016; the
 * Android seam (the Compose Canvas preview and the Bitmap exporter)
 * only converts these numbers into draw calls.
 */
object CollageLayouts {

    /** The fewest photos a collage can lay out (one photo is not a collage). */
    const val MIN_PHOTOS: Int = 2

    /** The most photos the v1 templates cover (the 3x3 grid's cell count). */
    const val MAX_PHOTOS: Int = 9

    /** The exported image's square edge, in pixels (a 1:1 canvas). */
    const val EXPORT_EDGE_PIXELS: Int = 2048

    // ------------------------------------------------------------------
    // The template catalogue. Coordinates are exact fractions (halves,
    // thirds, quarters, fifths); each row/column band's edges repeat
    // verbatim across the slots that touch them, which is what makes
    // "tiles the unit square exactly" a property of the construction.
    // ------------------------------------------------------------------

    /** The v1 templates, in picker order (2..9 photos, two per count). */
    val all: List<CollageTemplate> = listOf(
        template(
            "2-side-by-side",
            listOf(rect(0f, 0f, 1f / 2f, 1f), rect(1f / 2f, 0f, 1f, 1f)),
        ),
        template(
            "2-stacked",
            listOf(rect(0f, 0f, 1f, 1f / 2f), rect(0f, 1f / 2f, 1f, 1f)),
        ),
        template(
            "3-row",
            (0 until 3).map { i -> rect(i / 3f, 0f, (i + 1) / 3f, 1f) },
        ),
        template(
            "3-column",
            (0 until 3).map { i -> rect(0f, i / 3f, 1f, (i + 1) / 3f) },
        ),
        template(
            "3-one-top",
            listOf(
                rect(0f, 0f, 1f, 1f / 2f),
                rect(0f, 1f / 2f, 1f / 2f, 1f),
                rect(1f / 2f, 1f / 2f, 1f, 1f),
            ),
        ),
        template(
            "4-grid",
            listOf(
                rect(0f, 0f, 1f / 2f, 1f / 2f), rect(1f / 2f, 0f, 1f, 1f / 2f),
                rect(0f, 1f / 2f, 1f / 2f, 1f), rect(1f / 2f, 1f / 2f, 1f, 1f),
            ),
        ),
        template(
            "4-one-left",
            listOf(
                rect(0f, 0f, 1f / 2f, 1f),
                rect(1f / 2f, 0f, 1f, 1f / 3f),
                rect(1f / 2f, 1f / 3f, 1f, 2f / 3f),
                rect(1f / 2f, 2f / 3f, 1f, 1f),
            ),
        ),
        template(
            "5-two-three",
            listOf(
                rect(0f, 0f, 1f / 2f, 1f / 2f),
                rect(1f / 2f, 0f, 1f, 1f / 2f),
                rect(0f, 1f / 2f, 1f / 3f, 1f),
                rect(1f / 3f, 1f / 2f, 2f / 3f, 1f),
                rect(2f / 3f, 1f / 2f, 1f, 1f),
            ),
        ),
        template(
            "5-one-four",
            listOf(
                rect(0f, 0f, 1f / 2f, 1f),
                rect(1f / 2f, 0f, 1f, 1f / 4f),
                rect(1f / 2f, 1f / 4f, 1f, 2f / 4f),
                rect(1f / 2f, 2f / 4f, 1f, 3f / 4f),
                rect(1f / 2f, 3f / 4f, 1f, 1f),
            ),
        ),
        template(
            "6-grid-3x2",
            (0 until 6).map { i ->
                rect((i % 3) / 3f, (i / 3) / 2f, (i % 3 + 1) / 3f, (i / 3 + 1) / 2f)
            },
        ),
        template(
            "6-grid-2x3",
            (0 until 6).map { i ->
                rect((i % 2) / 2f, (i / 2) / 3f, (i % 2 + 1) / 2f, (i / 2 + 1) / 3f)
            },
        ),
        template(
            "7-one-six",
            (0 until 7).map { i ->
                if (i == 0) {
                    rect(0f, 0f, 1f, 1f / 3f)
                } else {
                    val cell = i - 1
                    rect(
                        (cell % 3) / 3f,
                        (1 + cell / 3) / 3f,
                        (cell % 3 + 1) / 3f,
                        (2 + cell / 3) / 3f,
                    )
                }
            },
        ),
        template(
            "7-two-five",
            (0 until 7).map { i ->
                if (i < 2) {
                    rect(i / 2f, 0f, (i + 1) / 2f, 1f / 2f)
                } else {
                    val cell = i - 2
                    rect(cell / 5f, 1f / 2f, (cell + 1) / 5f, 1f)
                }
            },
        ),
        template(
            "8-grid-4x2",
            (0 until 8).map { i ->
                rect((i % 4) / 4f, (i / 4) / 2f, (i % 4 + 1) / 4f, (i / 4 + 1) / 2f)
            },
        ),
        template(
            "8-two-six",
            (0 until 8).map { i ->
                if (i < 2) {
                    rect(i / 2f, 0f, (i + 1) / 2f, 1f / 3f)
                } else {
                    val cell = i - 2
                    rect(
                        (cell % 3) / 3f,
                        (1 + cell / 3) / 3f,
                        (cell % 3 + 1) / 3f,
                        (2 + cell / 3) / 3f,
                    )
                }
            },
        ),
        template(
            "9-grid-3x3",
            (0 until 9).map { i ->
                rect((i % 3) / 3f, (i / 3) / 3f, (i % 3 + 1) / 3f, (i / 3 + 1) / 3f)
            },
        ),
        template(
            "9-one-eight",
            (0 until 9).map { i ->
                if (i == 0) {
                    rect(0f, 0f, 1f, 1f / 3f)
                } else {
                    val cell = i - 1
                    rect(
                        (cell % 4) / 4f,
                        (1 + cell / 4) / 3f,
                        (cell % 4 + 1) / 4f,
                        (2 + cell / 4) / 3f,
                    )
                }
            },
        ),
    )

    /**
     * The templates that lay out [photoCount] photos (empty outside
     * [MIN_PHOTOS]..[MAX_PHOTOS] --- an honest empty list, never a
     * nearest-fit lie).
     */
    fun templatesFor(photoCount: Int): List<CollageTemplate> =
        if (photoCount in MIN_PHOTOS..MAX_PHOTOS) {
            all.filter { it.slotCount == photoCount }
        } else {
            emptyList()
        }

    /**
     * The first template of [photoCount] (the picker's default choice),
     * or `null` outside the supported range.
     */
    fun defaultFor(photoCount: Int): CollageTemplate? = templatesFor(photoCount).firstOrNull()

    /**
     * The slot's rectangle on a concrete canvas: normalized coordinates
     * scaled onto [width] x [height] pixels. One rounding rule for every
     * edge (round-half-up) keeps shared normalized edges on the same
     * pixel boundary; the outermost edges clamp to the canvas.
     */
    fun slotPixels(
        slot: CollageSlot,
        width: Int,
        height: Int,
    ): PixelRect = PixelRect(
        left = (slot.left * width).roundToInt().coerceIn(0, width),
        top = (slot.top * height).roundToInt().coerceIn(0, height),
        right = (slot.right * width).roundToInt().coerceIn(0, width),
        bottom = (slot.bottom * height).roundToInt().coerceIn(0, height),
    )

    /**
     * The CENTER-CROP source rectangle for a [bitmapWidth] x
     * [bitmapHeight] bitmap filling a destination with the aspect of
     * [destinationWidth] x [destinationHeight]: the largest centered
     * sub-rectangle of the bitmap that matches the destination's aspect.
     * A zero/negative dimension collapses to the degenerate-but-total
     * answers (empty source for an empty bitmap; the bitmap's full frame
     * for a zero-sized destination --- never a crash from either seam).
     */
    fun centerCropSource(
        bitmapWidth: Int,
        bitmapHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
    ): PixelRect {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return PixelRect(0, 0, 0, 0)
        if (destinationWidth <= 0 || destinationHeight <= 0) {
            return PixelRect(0, 0, bitmapWidth, bitmapHeight)
        }
        val sourceAspect = bitmapWidth.toFloat() / bitmapHeight
        val destinationAspect = destinationWidth.toFloat() / destinationHeight
        return if (sourceAspect > destinationAspect) {
            // Wider than the destination: crop the SIDES, keep full height.
            val cropWidth = (bitmapHeight * destinationAspect).roundToInt()
            val x = (bitmapWidth - cropWidth) / 2
            PixelRect(x, 0, x + cropWidth, bitmapHeight)
        } else {
            // Taller than the destination (or the same aspect): crop
            // TOP/BOTTOM, keep full width.
            val cropHeight = (bitmapWidth / destinationAspect).roundToInt()
            val y = (bitmapHeight - cropHeight) / 2
            PixelRect(0, y, bitmapWidth, y + cropHeight)
        }
    }

    /** The direct constructor (keeps the catalogue above declaration-light). */
    private fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): CollageSlot = CollageSlot(left = left, top = top, right = right, bottom = bottom)

    private fun template(id: String, slots: List<CollageSlot>): CollageTemplate =
        CollageTemplate(id = id, slots = slots)
}

/**
 * A whole-pixel rectangle (the pure seam's answer to
 * `android.graphics.RectF`): [left] <= [right], [top] <= [bottom] by
 * construction at every call site above.
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
