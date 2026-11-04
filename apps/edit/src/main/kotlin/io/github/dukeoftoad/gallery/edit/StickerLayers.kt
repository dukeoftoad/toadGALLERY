package io.github.dukeoftoad.gallery.edit

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sticker-layer system (r35, Phase 7 v2; the design contract is
 * `docs/phase-7/stickers.md`, decision set 1-6).
 *
 * Pure Kotlin, JVM-tested (the repo's ADR-0011 convention: the model and
 * layout are pure; only the Canvas/Paint pass lives on the Android side,
 * in `StickerRender.kt`, and shares THIS object's layout math for the
 * preview and the export --- one code path, no second renderer).
 *
 * The tolerance contract mirrors r33/r34: a pre-r35 sidecar (or a clean
 * session) has no `stickerLayers` key and loads byte-clean; an
 * UNRESOLVABLE `stickerId` round-trips verbatim (the recipe is the user's
 * data) and renders NOTHING at render time --- there is no default
 * sticker to degrade to, and faking one would draw something the user
 * never chose. Never a load failure, never a crash.
 */
data class StickerLayer(
    val id: String,
    val stickerId: String,
    /** 0..300, percent of the OUTPUT canvas min-edge; the design box maps to a square of that edge. */
    val sizePct: Int = DEFAULT_SIZE_PCT,
    /** 0..100 (a sticker carries no authored color alpha --- the layer's opacity owns transparency). */
    val opacity: Int = 100,
    /** The layer's OWN angle in degrees, -180..180 (never re-rotated by geometry). */
    val rotationDeg: Int = 0,
    /** 0..1 fraction of the output-canvas width. */
    val centerX: Double = 0.5,
    /** 0..1 fraction of the output-canvas height. */
    val centerY: Double = 0.5,
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "layer id longer than $MAX_ID_LENGTH chars" }
        require(stickerId.isNotBlank()) { "stickerId must not be blank" }
        require(stickerId.length <= MAX_ID_LENGTH) { "stickerId longer than $MAX_ID_LENGTH chars" }
        require(sizePct in 0..300) { "sizePct must be in 0..300" }
        require(opacity in 0..100) { "opacity must be in 0..100" }
        require(rotationDeg in -180..180) { "rotationDeg must be in -180..180" }
        require(centerX in 0.0..1.0) { "centerX must be in 0..1" }
        require(centerY in 0.0..1.0) { "centerY must be in 0..1" }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val DEFAULT_SIZE_PCT = 15

        /**
         * The next free session id over `existing` ("s1", "s2", ...): the
         * r34 `newId` convention, the `s` prefix keeping the families
         * collision-free by construction.
         */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("s").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "s${max + 1}"
        }
    }
}

/**
 * The recipe's additive sticker container: absent (null) = a pre-r35
 * sidecar or a clean session; empty is normalised BACK to absent by the
 * codec (the r34 `TextLayers` rule).
 */
data class StickerLayers(val layers: List<StickerLayer>) {
    init {
        require(layers.size == layers.map { it.id }.toSet().size) { "duplicate sticker layer id" }
    }

    val isIdentity: Boolean get() = layers.isEmpty()

    companion object {
        val EMPTY = StickerLayers(emptyList())
    }
}

/**
 * One authored sticker: filled subpaths in the 0..100 design box (y
 * down, screen convention). Project-authored code --- the authorship
 * and licensing record is `docs/phase-7/bundled-stickers.md`; there is
 * no asset file, no sha256 pin and no §5 ledger row by design.
 */
data class StickerShape(val parts: List<Part>) {
    init {
        require(parts.isNotEmpty()) { "a sticker needs at least one part" }
    }

    /** One fill: a color plus one or more closed subpaths (draw order = list order, top last). */
    data class Part(val colorArgb: Long, val ops: List<Op>) {
        init {
            require(colorArgb in 0..0xFFFFFFFFL) { "part color outside the unsigned ARGB range" }
            // The palette carries no authored alpha: the layer's opacity
            // slider owns transparency, so a part's alpha byte is 0xFF.
            require((colorArgb ushr 24) == 0xFFL) { "part colors must be opaque (alpha 0xFF)" }
            require(ops.isNotEmpty()) { "a part needs at least one subpath" }
        }
    }

    /** A subpath op in the 0..100 design box (y down, screen convention). */
    sealed interface Op {
        data class MoveTo(val x: Int, val y: Int) : Op
        data class LineTo(val x: Int, val y: Int) : Op
        data class CubicTo(
            val c1x: Int,
            val c1y: Int,
            val c2x: Int,
            val c2y: Int,
            val x: Int,
            val y: Int,
        ) : Op

        data object Close : Op
    }

    init {
        for (part in parts) {
            var open = false
            var geometry = 0
            var length = 0
            for (op in part.ops) {
                when (op) {
                    is Op.MoveTo -> {
                        require(!open) { "a subpath must Close before the next MoveTo" }
                        requireInBox(op.x, op.y)
                        open = true
                        geometry = 0
                        length = 1
                    }
                    is Op.LineTo -> {
                        require(open) { "a geometry op outside a subpath" }
                        requireInBox(op.x, op.y)
                        geometry += 1
                        length += 1
                    }
                    is Op.CubicTo -> {
                        require(open) { "a geometry op outside a subpath" }
                        requireInBox(op.c1x, op.c1y)
                        requireInBox(op.c2x, op.c2y)
                        requireInBox(op.x, op.y)
                        geometry += 1
                        length += 1
                    }
                    is Op.Close -> {
                        require(open) { "Close outside a subpath" }
                        require(geometry >= 1) { "a subpath needs at least one curve/line op" }
                        require(length >= 3) { "a subpath needs at least 3 ops (MoveTo, geometry, Close)" }
                        open = false
                    }
                }
            }
            require(!open) { "a part must end every subpath with Close" }
        }
    }

    private fun requireInBox(x: Int, y: Int) {
        require(x in 0..DESIGN_BOX) { "op x=$x outside the 0..100 design box" }
        require(y in 0..DESIGN_BOX) { "op y=$y outside the 0..100 design box" }
    }

    companion object {
        /** The design box: authored coordinates live in 0..100. */
        const val DESIGN_BOX: Int = 100
    }
}

/**
 * The bundled-sticker registry (r35; the `BundledFonts` pattern adapted
 * for authored code). PURE: the platform side turns definitions into
 * `Path` fills (see `StickerRender.kt`); the authorship record is
 * `docs/phase-7/bundled-stickers.md`, and `BundledStickersTest` pins the
 * structural invariants plus spot coordinates, so a change to any
 * formula fails the build, not the user.
 */
object BundledStickers {

    /** One bundled sticker: the registry id and its display name (a proper noun --- not localized, the BundledFonts convention). */
    data class Entry(val id: String, val displayName: String) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }
    }

    /** The chip-row order (the order the UI shows the stickers). */
    val ORDERED: List<Entry> = listOf(
        Entry("heart", "Heart"),
        Entry("star", "Star"),
        Entry("smiley", "Smiley"),
        Entry("bubble", "Speech bubble"),
        Entry("bolt", "Lightning"),
        Entry("flower", "Flower"),
        Entry("pin", "Location pin"),
        Entry("crown", "Crown"),
    )

    /** The entry for [id], or null when this build does not bundle it. */
    fun byId(id: String): Entry? = ORDERED.firstOrNull { it.id == id }

    /** The authored definition for [id], or null (the tolerance contract's unresolvable case). */
    fun shape(id: String): StickerShape? = definitions[id]

    private val definitions: Map<String, StickerShape> = mapOf(
        "heart" to heart(),
        "star" to star(),
        "smiley" to smiley(),
        "bubble" to bubble(),
        "bolt" to bolt(),
        "flower" to flower(),
        "pin" to pin(),
        "crown" to crown(),
    )

    // ---- The authorship layer (the formulas ARE the shapes; docs/phase-7/bundled-stickers.md) ----

    private const val RED = 0xFFE53935L
    private const val YELLOW = 0xFFFDD835L
    private const val PINK = 0xFFF06292L
    private const val INK = 0xFF212121L
    private const val WHITE = 0xFFFFFFFFL

    /** A regular N-gon approximating a circle: MoveTo(first), LineTo(rest), Close. */
    private fun ngon(cx: Int, cy: Int, r: Int, n: Int, startDeg: Int = 0): List<StickerShape.Op> {
        require(n >= 3) { "an N-gon needs n >= 3" }
        val ops = ArrayList<StickerShape.Op>(n + 2)
        for (k in 0 until n) {
            val theta = Math.toRadians(startDeg + 360.0 * k / n)
            val x = (cx + r * cos(theta)).roundToInt()
            val y = (cy + r * sin(theta)).roundToInt()
            if (k == 0) ops.add(StickerShape.Op.MoveTo(x, y)) else ops.add(StickerShape.Op.LineTo(x, y))
        }
        ops.add(StickerShape.Op.Close)
        return ops
    }

    /** An open polyline along a circle between two angles; the caller decides how it closes. */
    private fun arcPts(
        cx: Int,
        cy: Int,
        r: Int,
        fromDeg: Int,
        toDeg: Int,
        steps: Int,
    ): List<StickerShape.Op> {
        require(steps >= 1) { "an arc needs at least one step" }
        val ops = ArrayList<StickerShape.Op>(steps + 2)
        for (k in 0..steps) {
            val t = fromDeg + (toDeg - fromDeg) * k.toDouble() / steps
            val theta = Math.toRadians(t)
            val x = (cx + r * cos(theta)).roundToInt()
            val y = (cy + r * sin(theta)).roundToInt()
            if (k == 0) ops.add(StickerShape.Op.MoveTo(x, y)) else ops.add(StickerShape.Op.LineTo(x, y))
        }
        return ops
    }

    /** An explicit polygon subpath: MoveTo(first), LineTo(rest), Close. */
    private fun poly(vararg points: Pair<Int, Int>): List<StickerShape.Op> {
        require(points.size >= 3) { "a polygon needs at least 3 vertices" }
        val ops = ArrayList<StickerShape.Op>(points.size + 1)
        for ((index, point) in points.withIndex()) {
            if (index == 0) ops.add(StickerShape.Op.MoveTo(point.first, point.second))
            else ops.add(StickerShape.Op.LineTo(point.first, point.second))
        }
        ops.add(StickerShape.Op.Close)
        return ops
    }

    private fun heart(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(
                RED,
                listOf(
                    StickerShape.Op.MoveTo(50, 90),
                    StickerShape.Op.CubicTo(20, 70, 6, 44, 20, 28),
                    StickerShape.Op.CubicTo(30, 18, 44, 24, 50, 36),
                    StickerShape.Op.CubicTo(56, 24, 70, 18, 80, 28),
                    StickerShape.Op.CubicTo(94, 44, 80, 70, 50, 90),
                    StickerShape.Op.Close,
                ),
            ),
        ),
    )

    private fun star(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(
                YELLOW,
                poly(
                    50 to 2, 62 to 34, 96 to 35, 69 to 44, 78 to 89,
                    50 to 70, 22 to 89, 31 to 44, 4 to 35, 38 to 34,
                ),
            ),
        ),
    )

    private fun smiley(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(YELLOW, ngon(50, 50, 46, 24)),
            StickerShape.Part(INK, ngon(36, 40, 6, 12)),
            StickerShape.Part(INK, ngon(64, 40, 6, 12)),
            // The smile: the arc from 20°..160° (through the bottom at
            // 90°), Close chords it into a half-disc.
            StickerShape.Part(INK, arcPts(50, 50, 30, 20, 160, 7) + StickerShape.Op.Close),
        ),
    )

    private fun bubble(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(
                WHITE,
                poly(
                    28 to 18, 72 to 18, 82 to 28, 82 to 54,
                    72 to 64, 28 to 64, 18 to 54, 18 to 28,
                ),
            ),
            StickerShape.Part(WHITE, poly(38 to 63, 58 to 63, 40 to 88)),
        ),
    )

    private fun bolt(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(
                YELLOW,
                poly(60 to 4, 24 to 54, 44 to 54, 36 to 96, 76 to 40, 54 to 40),
            ),
        ),
    )

    private fun flower(): StickerShape {
        // Five petal centres at radius 22 about (50,50), 72° apart; one
        // pink part, five subpaths (a Part carries any number of closed
        // subpaths); the yellow centre draws last, on top.
        val petalCentres = listOf(
            50 to 72, 29 to 57, 37 to 32, 63 to 32, 71 to 57,
        )
        val petalOps = petalCentres.flatMap { (cx, cy) -> ngon(cx, cy, 18, 12) }
        return StickerShape(
            listOf(
                StickerShape.Part(PINK, petalOps),
                StickerShape.Part(YELLOW, ngon(50, 50, 13, 12)),
            ),
        )
    }

    private fun pin(): StickerShape {
        // The teardrop: the 24-gon head about (50,40), its vertex list
        // continuing to the tip at (50,96) before the Close.
        val head = ngon(50, 40, 26, 24)
        val withTip = head.dropLast(1) +
            StickerShape.Op.LineTo(50, 96) +
            StickerShape.Op.Close
        return StickerShape(
            listOf(
                StickerShape.Part(RED, withTip),
                StickerShape.Part(WHITE, ngon(50, 40, 11, 12)),
            ),
        )
    }

    private fun crown(): StickerShape = StickerShape(
        listOf(
            StickerShape.Part(
                YELLOW,
                poly(26 to 74, 22 to 36, 38 to 54, 50 to 28, 62 to 54, 78 to 36, 74 to 74),
            ),
            StickerShape.Part(
                WHITE,
                ngon(22, 31, 5, 12) + ngon(50, 23, 5, 12) + ngon(78, 31, 5, 12),
            ),
        ),
    )
}

/**
 * The pure layout pass (JVM-tested): turns the recipe's fraction-space
 * layers into pixel-space draw specs IN LIST ORDER (the draw order:
 * layers render in list order, top LAST --- the r34 rule). Resolution
 * of the stickerId does NOT happen here (a bundled vector and an
 * imported bitmap resolve on the platform side, in `StickerRender.kt`);
 * this pass owns the geometry both kinds share: the size box, the
 * centre anchor and the paint alpha.
 */
object StickerLayout {

    /** One layer resolved into pixel space, ready to draw (verbatim layer included). */
    data class Placed(
        val layer: StickerLayer,
        /** The design box's target edge in output pixels. */
        val sizePx: Float,
        /** The anchor's pixel x within the drawing surface. */
        val centerX: Float,
        /** The anchor's pixel y within the drawing surface. */
        val centerY: Float,
        /** 0..255 paint alpha: the opacity slider alone (no authored color byte to multiply). */
        val alpha: Int,
    )

    /** The box edge: percent of the canvas min-edge, the text em-size convention. */
    fun sizePxFor(sizePct: Int, canvasMinEdge: Float): Float = sizePct / 100f * canvasMinEdge

    /**
     * The paint alpha: the opacity slider mapped to 0..255, rounded
     * half-up ((opacity*255 + 50) / 100 --- 100% stays 255; 50% is 128,
     * not 127). The r34 alpha math with the color byte pinned at 0xFF.
     */
    fun alphaFor(layer: StickerLayer): Int = (layer.opacity * 255 + 50) / 100

    fun place(
        layers: List<StickerLayer>,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ): List<Placed> {
        require(canvasW > 0f && canvasH > 0f) { "canvas must have positive dimensions" }
        val minEdge = minOf(canvasW, canvasH)
        return layers.map { layer ->
            Placed(
                layer = layer,
                sizePx = sizePxFor(layer.sizePct, minEdge),
                centerX = (originX + layer.centerX * canvasW).toFloat().coerceIn(originX, originX + canvasW),
                centerY = (originY + layer.centerY * canvasH).toFloat().coerceIn(originY, originY + canvasH),
                alpha = alphaFor(layer),
            )
        }
    }
}
