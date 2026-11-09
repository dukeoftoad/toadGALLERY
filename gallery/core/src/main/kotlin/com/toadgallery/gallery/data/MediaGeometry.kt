package com.toadgallery.gallery.data

/**
 * Pure geometry for the aspect-ratio-aware staggered grid (issue #34).
 *
 * The math lives in a top-level function instead of on [MediaItem] itself so
 * that it runs in plain JVM unit tests --- [MediaItem] carries an
 * `android.net.Uri`, which a JVM test cannot construct without Robolectric,
 * whereas this function depends on nothing but two [Int]s. The
 * [MediaItem.displayAspectRatio] extension glues the pure function onto the
 * data class for the UI.
 *
 * Unknown dimensions (`width <= 0` or `height <= 0`) yield `1f`: a missing
 * MediaStore column must degrade to the square-cell fallback, never collapse
 * a grid cell to zero height.
 *
 * The ratio is clamped to `[0.5f, 2.0f]` (roughly 1:2 through 2:1): without
 * it, an extremely tall capture would stretch one cell across its entire
 * staggered column, while an extreme panorama would collapse to a sliver
 * band --- either extreme destroys the layout's visual rhythm. The bounds
 * cap every cell at twice as tall as it is wide, and vice versa.
 */
fun displayAspectRatioOf(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) {
        1f
    } else {
        (width.toFloat() / height).coerceIn(0.5f, 2.0f)
    }

/**
 * The clamped display aspect ratio of this item, ready for
 * `Modifier.aspectRatio(...)` in the staggered grid (issue #34); `1f` when
 * the dimensions are unknown.
 */
val MediaItem.displayAspectRatio: Float
    get() = displayAspectRatioOf(width, height)
