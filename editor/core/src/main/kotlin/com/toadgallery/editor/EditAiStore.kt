package com.toadgallery.editor

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Derived-artifact store: `edit-ai/src-<sha256-of-source-uri-16>/` beside the
 * sidecar directory; no URI text or filesystem path ever becomes a filename.
 * Inference persists derived artifacts here; every render/export replays
 * deterministically from the artifact + layer parameters — no model ever runs
 * during render or export.
 *
 * Sidecar discipline: atomic tmp-then-rename writes; forgiving reads (missing
 * or corrupt artifact renders as absent — the stage renders nothing, the UI
 * says degraded, never a crash); keys = layer id + content hash of (source,
 * inputs, model) so re-inference writes a NEW artifact and never mutates one
 * in place (the undo stack stays coherent); append-only per session, swept
 * together with the sidecar on export/clear.
 *
 * Encodings: masks = RLE rows over output-canvas fractions (exact at the
 * working grid, nearest-row elsewhere); depth = 8-bit grids, larger = closer;
 * face boxes = normalised rects; fill = working-resolution patch + placement
 * fractions. Pure Kotlin: deterministic integer math, JVM-testable.
 */

/**
 * A mask artifact: one row per working-grid row (empty rows included — this
 * makes reconstruction exact at the working grid), in-spans as pixel-edge
 * fractions of the output canvas. A column is IN when its centre fraction
 * (x + 0.5) / w lies strictly inside a span. Exact at the working grid;
 * nearest-row elsewhere (ties keep the lower row).
 */
class FractionRleMask(
    val workingWidth: Int,
    val workingHeight: Int,
    rows: List<MaskRow>,
) {
    val rows: List<MaskRow> = rows.sortedBy { it.yFrac }

    /** One stored row: the row's y fraction (the centre convention) + its in-spans. */
    data class MaskRow(val yFrac: Double, val spans: List<Pair<Double, Double>>) {
        init {
            require(yFrac in 0.0..1.0) { "row y fraction must be in 0..1" }
            for ((a, b) in spans) require(a in 0.0..1.0 && b in 0.0..1.0 && a < b) { "a span must be a non-empty in-canvas fraction interval" }
        }
    }

    /** The mask reconstructed over a [w] x [h] grid (the fraction rule above). */
    fun maskAt(w: Int, h: Int): BooleanArray {
        require(w > 0 && h > 0) { "the grid must have positive dimensions" }
        val out = BooleanArray(w * h)
        if (rows.isEmpty()) return out
        for (y in 0 until h) {
            val yF = (y + 0.5) / h
            // Nearest stored row; ties keep the lower row (strict < keeps the first in y order).
            var best = rows[0]
            var bestDist = kotlin.math.abs(yF - best.yFrac)
            for (r in rows) {
                val d = kotlin.math.abs(yF - r.yFrac)
                if (d < bestDist) {
                    best = r
                    bestDist = d
                }
            }
            if (best.spans.isEmpty()) continue
            for (x in 0 until w) {
                val xF = (x + 0.5) / w
                for ((a, b) in best.spans) {
                    if (xF > a && xF < b) {
                        out[y * w + x] = true
                        break
                    }
                }
            }
        }
        return out
    }

    /** The tight pixel bounding box of the mask over a [w] x [h] grid, or null when empty. */
    fun boundsAt(w: Int, h: Int): PixelRect? {
        val mask = maskAt(w, h)
        var minX = -1
        var minY = -1
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                if (minX < 0 || x < minX) minX = x
                if (minY < 0 || y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (minX < 0) return null
        return PixelRect(minX, minY, maxX + 1, maxY + 1)
    }

    fun toBytes(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeUTF("EDAIMASK1")
        out.writeInt(workingWidth)
        out.writeInt(workingHeight)
        out.writeInt(rows.size)
        for (row in rows) {
            out.writeDouble(row.yFrac)
            out.writeInt(row.spans.size)
            for ((a, b) in row.spans) {
                out.writeDouble(a)
                out.writeDouble(b)
            }
        }
        out.flush()
        return buffer.toByteArray()
    }

    companion object {
        /** Encodes a mask computed at its working grid into the fraction form. */
        fun fromMaskAt(mask: BooleanArray, workingWidth: Int, workingHeight: Int): FractionRleMask {
            val rowsOut = ArrayList<MaskRow>(workingHeight)
            for (y in 0 until workingHeight) {
                val yF = (y + 0.5) / workingHeight
                val spans = ArrayList<Pair<Double, Double>>()
                var x = 0
                while (x < workingWidth) {
                    if (!mask[y * workingWidth + x]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < workingWidth && mask[y * workingWidth + x]) x++
                    // Pixel-edge fractions: the span covers [start, x) so the
                    // strict-inside reconstruction includes exactly those columns.
                    spans.add(start / workingWidth.toDouble() to x / workingWidth.toDouble())
                }
                rowsOut.add(MaskRow(yF, spans))
            }
            return FractionRleMask(workingWidth, workingHeight, rowsOut)
        }

        fun fromBytes(bytes: ByteArray): FractionRleMask? = try {
            val input = DataInputStream(java.io.ByteArrayInputStream(bytes))
            if (input.readUTF() != "EDAIMASK1") return null
            val w = input.readInt()
            val h = input.readInt()
            val rowCount = input.readInt()
            val rows = ArrayList<MaskRow>(rowCount)
            for (i in 0 until rowCount) {
                val yFrac = input.readDouble()
                val spanCount = input.readInt()
                val spans = ArrayList<Pair<Double, Double>>(spanCount)
                for (j in 0 until spanCount) {
                    spans.add(input.readDouble() to input.readDouble())
                }
                rows.add(MaskRow(yFrac, spans))
            }
            FractionRleMask(w, h, rows)
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }
}

/**
 * The inpaint-fill artifact: the migan result cropped to the refined mask's
 * bounding box at the working resolution, with placement fractions (paste maps
 * the patch over its placement at any target grid).
 */
class FillPatch(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(width > 0 && height > 0) { "the patch must have positive dimensions" }
        require(pixels.size == width * height) { "the patch pixels must match its dimensions" }
        require(left in 0.0..1.0 && right in 0.0..1.0 && left < right) { "the patch's x placement must be a non-empty fraction span" }
        require(top in 0.0..1.0 && bottom in 0.0..1.0 && top < bottom) { "the patch's y placement must be a non-empty fraction span" }
    }

    /** Patch sample for an output-canvas centre fraction ([fx], [fy]): nearest, floor mapping, clamped. */
    fun sampleAt(fx: Double, fy: Double): Int {
        val u = ((fx - left) / (right - left)).coerceIn(0.0, 1.0)
        val v = ((fy - top) / (bottom - top)).coerceIn(0.0, 1.0)
        val sx = (u * width).toInt().coerceIn(0, width - 1)
        val sy = (v * height).toInt().coerceIn(0, height - 1)
        return pixels[sy * width + sx]
    }

    fun toBytes(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeUTF("EDAIFILL1")
        out.writeDouble(left)
        out.writeDouble(top)
        out.writeDouble(right)
        out.writeDouble(bottom)
        out.writeInt(width)
        out.writeInt(height)
        for (c in pixels) out.writeInt(c)
        out.flush()
        return buffer.toByteArray()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): FillPatch? = try {
            val input = DataInputStream(java.io.ByteArrayInputStream(bytes))
            if (input.readUTF() != "EDAIFILL1") return null
            val l = input.readDouble()
            val t = input.readDouble()
            val r = input.readDouble()
            val b = input.readDouble()
            val w = input.readInt()
            val h = input.readInt()
            val px = IntArray(w * h)
            for (i in px.indices) px[i] = input.readInt()
            FillPatch(px, w, h, l, t, r, b)
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }
}

/**
 * The depth artifact: quantised 8-bit single-channel grid at a bounded working
 * resolution (long edge caps at [MAX_LONG_EDGE]); LARGER = CLOSER (min-max
 * normalised depth). Sampled nearest at any target grid (floor mapping).
 */
class DepthGrid(
    val values: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "the grid must have positive dimensions" }
        require(values.size == width * height) { "the grid values must match its dimensions" }
    }

    /** The 0..255 sample for a pixel of a [w] x [h] target grid. */
    fun sampleAt(x: Int, y: Int, w: Int, h: Int): Int {
        val gx = (x * width / w).coerceIn(0, width - 1)
        val gy = (y * height / h).coerceIn(0, height - 1)
        return values[gy * width + gx].toInt() and 0xFF
    }

    fun toBytes(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeUTF("EDAIDEPTH1")
        out.writeInt(width)
        out.writeInt(height)
        out.write(values)
        out.flush()
        return buffer.toByteArray()
    }

    companion object {
        /** The bounded working resolution's long-edge cap. */
        const val MAX_LONG_EDGE: Int = 256

        /**
         * Downsamples a full-resolution depth map (0..255, larger = closer) to
         * a grid capped at [MAX_LONG_EDGE] by floor-division nearest sampling.
         * The divisor is the CEILING of the long-edge ratio so the result can
         * never exceed the cap (a floor divisor would: 518/2 = 259 > 256).
         */
        fun fromMap(map: IntArray, mapW: Int, mapH: Int): DepthGrid {
            val longEdge = maxOf(mapW, mapH)
            val downscale = ((longEdge + MAX_LONG_EDGE - 1) / MAX_LONG_EDGE).coerceAtLeast(1)
            val gw = (mapW + downscale - 1) / downscale
            val gh = (mapH + downscale - 1) / downscale
            val out = ByteArray(gw * gh)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val sx = (gx * downscale).coerceAtMost(mapW - 1)
                    val sy = (gy * downscale).coerceAtMost(mapH - 1)
                    out[gy * gw + gx] = map[sy * mapW + sx].coerceIn(0, 255).toByte()
                }
            }
            return DepthGrid(out, gw, gh)
        }

        fun fromBytes(bytes: ByteArray): DepthGrid? = try {
            val input = DataInputStream(java.io.ByteArrayInputStream(bytes))
            if (input.readUTF() != "EDAIDEPTH1") return null
            val w = input.readInt()
            val h = input.readInt()
            val body = ByteArray(w * h)
            input.readFully(body)
            DepthGrid(body, w, h)
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }
}

/**
 * The face-boxes artifact: normalised rects, one per face, in detection order;
 * retouch kernels apply per box in list order.
 */
class FaceBoxes(val boxes: List<Box>) {
    init {
        for (b in boxes) {
            require(b.left in 0.0..1.0 && b.right in 0.0..1.0 && b.left < b.right) { "a box's x span must be a non-empty fraction" }
            require(b.top in 0.0..1.0 && b.bottom in 0.0..1.0 && b.top < b.bottom) { "a box's y span must be a non-empty fraction" }
        }
    }

    /** One detected face: fractions of the output canvas. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    fun toBytes(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeUTF("EDAIBOXES1")
        out.writeInt(boxes.size)
        for (b in boxes) {
            out.writeDouble(b.left)
            out.writeDouble(b.top)
            out.writeDouble(b.right)
            out.writeDouble(b.bottom)
        }
        out.flush()
        return buffer.toByteArray()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): FaceBoxes? = try {
            val input = DataInputStream(java.io.ByteArrayInputStream(bytes))
            if (input.readUTF() != "EDAIBOXES1") return null
            val count = input.readInt()
            val out = ArrayList<Box>(count)
            for (i in 0 until count) {
                out.add(
                    Box(
                        input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                    ),
                )
            }
            FaceBoxes(out)
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }
}

/**
 * The store: `edit-ai/src-<source-hash-16>/` under the given root, following
 * the sidecar discipline. One instance per session (per source); artifact
 * keys embed the layer id and content hash.
 */
class EditAiStore(private val root: File, sourceUri: String) {

    private val sourceFingerprint: String = EditRecipeStore.sha256(sourceUri)

    private val sourceDir: File = File(root, "src-${sourceFingerprint.take(16)}")

    init {
        if (!sourceDir.exists()) sourceDir.mkdirs()
    }

    /**
     * Artifact key: layer id + `-` + first 8 hex of SHA-256 over (source hash
     * + NUL + inputs + NUL + model identity). Different inputs derive a
     * different key, so writes never mutate an artifact in place (undo stays
     * coherent).
     */
    fun keyFor(layerId: String, inputs: String, modelIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceFingerprint\u0000$inputs\u0000$modelIdentity".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return "$layerId-$digest"
    }

    /** Whether an artifact file exists for [key] of this [kind] (presence check, never a read). */
    fun contains(kind: ArtifactKind, key: String): Boolean = fileFor(kind, key)?.isFile == true

    /** Writes the refined erase mask + fill under one key: the pair lands or neither does. */
    fun writeEraseArtifact(key: String, mask: FractionRleMask, patch: FillPatch): Boolean =
        write(fileFor(ArtifactKind.MASK, key), mask.toBytes()) &&
            write(fileFor(ArtifactKind.FILL, key), patch.toBytes())

    /** Reads the erase pair; null when absent or corrupt (forgiving, never thrown). */
    fun readEraseArtifact(key: String): Pair<FractionRleMask, FillPatch>? {
        val mask = FractionRleMask.fromBytes(read(fileFor(ArtifactKind.MASK, key)) ?: return null) ?: return null
        val fill = FillPatch.fromBytes(read(fileFor(ArtifactKind.FILL, key)) ?: return null) ?: return null
        return mask to fill
    }

    fun writeSkyMask(key: String, mask: FractionRleMask): Boolean =
        write(fileFor(ArtifactKind.MASK, key), mask.toBytes())

    fun readSkyMask(key: String): FractionRleMask? =
        FractionRleMask.fromBytes(read(fileFor(ArtifactKind.MASK, key)) ?: return null)

    fun writeDepth(key: String, grid: DepthGrid): Boolean =
        write(fileFor(ArtifactKind.DEPTH, key), grid.toBytes())

    fun readDepth(key: String): DepthGrid? =
        DepthGrid.fromBytes(read(fileFor(ArtifactKind.DEPTH, key)) ?: return null)

    fun writeBoxes(key: String, boxes: FaceBoxes): Boolean =
        write(fileFor(ArtifactKind.BOXES, key), boxes.toBytes())

    fun readBoxes(key: String): FaceBoxes? =
        FaceBoxes.fromBytes(read(fileFor(ArtifactKind.BOXES, key)) ?: return null)

    /** Sweeps this source's artifacts on export/clear (append-only until then; the only delete). */
    fun sweep() {
        val files = sourceDir.listFiles() ?: return
        for (file in files) {
            try {
                file.delete()
            } catch (_: RuntimeException) {
                // Best-effort; a leftover file self-heals on the next sweep.
            }
        }
        try {
            sourceDir.delete()
        } catch (_: RuntimeException) {
        }
    }

    // ---- internals ----

    enum class ArtifactKind(val extension: String) { MASK("mask"), FILL("fill"), DEPTH("depth"), BOXES("boxes") }

    /**
     * The file for [key]: null when hostile (would not survive sanitisation —
     * path separators, dot walks, over-length). A hostile key writes nothing
     * and reads as absent: never a filesystem escape.
     */
    private fun fileFor(kind: ArtifactKind, key: String): File? {
        val safe = key.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }.take(96)
        if (safe != key) return null
        return File(sourceDir, "$safe.${kind.extension}")
    }

    /** The atomic write: tmp-then-rename, the sidecar pattern (a rename failure falls back to a copy). */
    private fun write(target: File?, bytes: ByteArray): Boolean {
        if (target == null) return false
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) {
                true
            } else {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
                target.isFile
            }
        } catch (_: RuntimeException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }

    /** The forgiving read: absent or unreadable answers null, never throws. */
    private fun read(file: File?): ByteArray? {
        if (file == null || !file.isFile) return null
        return try {
            file.readBytes()
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }
}
