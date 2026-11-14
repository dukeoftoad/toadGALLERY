package com.toadgallery.editor

/**
 * The bundled sky registry: five PROJECT-AUTHORED PARAMETRIC SKIES — pure
 * deterministic render functions over integers. NO third-party sky bitmaps ship
 * in the APK (sky = 0 model bytes, 0 bitmap bytes; the only graph the sky
 * feature uses is the shared u2netp saliency — the sky pixels are code), so no
 * sha256 pin and no ledger row for sky bitmaps.
 *
 * Render contract: `render(id, w, h)` paints EXACTLY [w] x [h] (the sky stage
 * cover-crops it over the mask's bounding box; at the box's own size the crop
 * is the identity). Vertical banding by integer interpolation; the night
 * field's stars sit on a fixed integer pattern. Pure Kotlin, JVM-tested
 * (BundledSkiesTest).
 */
object BundledSkies {

    /** One bundled sky: registry id + display name (a proper noun, not localized). */
    data class Entry(val id: String, val displayName: String) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }
    }

    /** The chip-row order (the order the UI shows the skies). */
    val ORDERED: List<Entry> = listOf(
        Entry("day-clear", "Day clear"),
        Entry("dusk", "Dusk"),
        Entry("night", "Night"),
        Entry("overcast", "Overcast"),
        Entry("golden", "Golden hour"),
    )

    /** The entry for [id], or null when this build does not bundle it. */
    fun byId(id: String): Entry? = ORDERED.firstOrNull { it.id == id }

    /** The authored render for [id] at [w] x [h]: ARGB IntArray (row-major, opaque); null if unresolvable. */
    fun render(id: String, w: Int, h: Int): IntArray? = when (id) {
        "day-clear" -> gradient(w, h, 0xFF2E7DD1.toInt(), 0xFF9CCBF0.toInt(), stars = false)
        "dusk" -> gradient(w, h, 0xFF3E2A6E.toInt(), 0xFFE8814E.toInt(), stars = false)
        "night" -> gradient(w, h, 0xFF0A0F2E.toInt(), 0xFF27336E.toInt(), stars = true)
        "overcast" -> gradient(w, h, 0xFF9AA3AD.toInt(), 0xFFD6DBE0.toInt(), stars = false)
        "golden" -> gradient(w, h, 0xFFE9B25C.toInt(), 0xFFFDF0D2.toInt(), stars = false)
        else -> null
    }

    /**
     * The one authored primitive: a vertical gradient with integer interpolation
     * (per row t = y * 256 / h, channel = top + (bottom - top) * t / 256, clamped).
     * Stars: pixels where (x * 31 + y * 17) % 211 == 0 read near-white — a fixed
     * integer pattern, deterministic at any size.
     */
    private fun gradient(w: Int, h: Int, top: Int, bottom: Int, stars: Boolean): IntArray {
        val out = IntArray(w * h)
        val topR = (top shr 16) and 0xFF
        val topG = (top shr 8) and 0xFF
        val topB = top and 0xFF
        val botR = (bottom shr 16) and 0xFF
        val botG = (bottom shr 8) and 0xFF
        val botB = bottom and 0xFF
        for (y in 0 until h) {
            val t = y * 256 / h.coerceAtLeast(1)
            val r = topR + (botR - topR) * t / 256
            val g = topG + (botG - topG) * t / 256
            val b = topB + (botB - topB) * t / 256
            val row = y * w
            for (x in 0 until w) {
                var rr = r
                var gg = g
                var bb = b
                if (stars && (x * 31 + y * 17) % 211 == 0) {
                    rr = 236
                    gg = 240
                    bb = 255
                }
                out[row + x] = 0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return out
    }
}
