package com.toadgallery.editor.video

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Project-authored animated GIF encoder: GIF89a, fixed 256-colour global palette
 * (216-colour web cube + 40-step grey tail), NETSCAPE2.0 loop extension,
 * per-frame graphic control (delay in hundredths, no transparency), standard
 * LZW image data at min code size 8. Pure Kotlin, JVM-tested (independent LZW
 * decoder + structural pins in the test suite).
 *
 * The quantizer is deliberately simple: nearest cube entry per channel
 * (round(v / 51)); a median-cut palette is a recorded follow-up.
 */
object GifEncoder {

    /** One animation frame: straight ARGB pixels plus a delay in hundredths of a second. */
    class GifFrame(val argb: IntArray, val width: Int, val height: Int, val delayCs: Int) {
        init {
            require(argb.size == width * height) {
                "frame pixels ${argb.size} != width x height ($width x $height)"
            }
            require(width > 0 && height > 0) { "frame must have positive bounds, was $width x $height" }
            require(delayCs > 0) { "frame delay must be positive, was $delayCs" }
        }
    }

    /** The GIF LZW constants at min code size 8 (a 256-entry palette). */
    const val MIN_CODE_SIZE: Int = 8
    const val CLEAR_CODE: Int = 256
    const val END_OF_INFO_CODE: Int = 257
    const val FIRST_FREE_CODE: Int = 258
    const val MAX_CODE: Int = 4095

    /**
     * Frame delay (hundredths of a second) for a millisecond interval:
     * round(ms / 10), clamped UP to 2cs — GIF renders sub-2cs delays unreliably.
     */
    fun delayCsFor(frameIntervalMs: Long): Int {
        require(frameIntervalMs > 0) { "frame interval must be positive, was $frameIntervalMs" }
        return ((frameIntervalMs + 5) / 10).toInt().coerceAtLeast(2)
    }

    /** The palette index for an ARGB pixel: nearest cube entry per channel. */
    fun paletteIndex(argb: Int): Int {
        val r = (((argb ushr 16) and 0xFF) + 25) / 51
        val g = (((argb ushr 8) and 0xFF) + 25) / 51
        val b = ((argb and 0xFF) + 25) / 51
        return r * 36 + g * 6 + b
    }

    /** The 256-entry palette as 768 bytes (r, g, b per entry). */
    val paletteBytes: ByteArray = buildPalette()

    private fun buildPalette(): ByteArray {
        val out = ByteArray(256 * 3)
        // entries 0..215: the 6x6x6 cube, level v -> 51*v
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    val index = r * 36 + g * 6 + b
                    out[index * 3] = (51 * r).toByte()
                    out[index * 3 + 1] = (51 * g).toByte()
                    out[index * 3 + 2] = (51 * b).toByte()
                }
            }
        }
        // entries 216..255: the grey tail, step 255/39
        for (i in 0..39) {
            val index = 216 + i
            val level = (i * 255.0 / 39.0).roundToInt().toByte()
            out[index * 3] = level
            out[index * 3 + 1] = level
            out[index * 3 + 2] = level
        }
        return out
    }

    /** Encodes the frames into a complete GIF89a file. */
    fun encode(frames: List<GifFrame>): ByteArray {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
        val width = frames[0].width
        val height = frames[0].height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share bounds $width x $height"
        }
        val out = ByteWriter()
        // header + logical screen descriptor (global table, 256 entries, 8-bit)
        out.ascii("GIF89a")
        out.leShort(width)
        out.leShort(height)
        out.byte(0xF7) // global color table, colour resolution 7, size 7 -> 256 entries
        out.byte(0) // background colour index
        out.byte(0) // pixel aspect ratio (unspecified)
        out.bytes(paletteBytes)
        // the NETSCAPE2.0 loop extension: loop forever
        out.byte(0x21)
        out.byte(0xFF)
        out.byte(0x0B)
        out.ascii("NETSCAPE2.0")
        out.byte(0x03)
        out.byte(0x01)
        out.leShort(0)
        out.byte(0x00)
        for (frame in frames) {
            // graphic control extension: delay, no disposal, no transparency
            out.byte(0x21)
            out.byte(0xF9)
            out.byte(0x04)
            out.byte(0x00)
            out.leShort(frame.delayCs)
            out.byte(0x00)
            out.byte(0x00)
            // image descriptor: full frame, no local table, no interlace
            out.byte(0x2C)
            out.leShort(0)
            out.leShort(0)
            out.leShort(width)
            out.leShort(height)
            out.byte(0x00)
            // the LZW image data
            out.byte(MIN_CODE_SIZE)
            val indices = ByteArray(frame.argb.size)
            for (i in frame.argb.indices) {
                indices[i] = paletteIndex(frame.argb[i]).toByte()
            }
            subBlock(out, lzwEncode(indices))
            out.byte(0x00)
        }
        out.byte(0x3B) // trailer
        return out.toByteArray()
    }

    /** Splits the payload into GIF sub-blocks (max 255 bytes each). */
    internal fun subBlock(out: ByteWriter, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val length = minOf(payload.size - offset, 255)
            out.byte(length)
            out.bytes(payload.copyOfRange(offset, offset + length))
            offset += length
        }
    }

    /**
     * The GIF LZW encoder: clear at the start, code size grows 9..12 as the
     * dictionary fills, a clear when it hits 4096. Write-time rule: before
     * writing a code, if the next free code no longer fits the current size,
     * grow first (the decoder is one dictionary entry behind the encoder and
     * grows at the same boundary).
     */
    internal fun lzwEncode(indices: ByteArray): ByteArray {
        val bits = BitWriter()
        var codeSize = MIN_CODE_SIZE + 1
        var nextCode = FIRST_FREE_CODE
        val dictionary = HashMap<Long, Int>(4096)
        bits.write(CLEAR_CODE, codeSize)
        var w = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (w.toLong() shl 12) or k.toLong()
            val existing = dictionary[key]
            if (existing != null) {
                w = existing
            } else {
                // The next emitted code may reference up to nextCode — grow the size first on overflow.
                if (nextCode > (1 shl codeSize) && codeSize < 12) {
                    codeSize++
                }
                bits.write(w, codeSize)
                if (nextCode <= MAX_CODE) {
                    dictionary[key] = nextCode
                    nextCode++
                } else {
                    // the dictionary is full: reset it the standard way.
                    if (codeSize < 12 && nextCode > (1 shl codeSize)) {
                        codeSize++
                    }
                    bits.write(CLEAR_CODE, codeSize)
                    dictionary.clear()
                    nextCode = FIRST_FREE_CODE
                    codeSize = MIN_CODE_SIZE + 1
                }
                w = k
            }
        }
        if (nextCode > (1 shl codeSize) && codeSize < 12) {
            codeSize++
        }
        bits.write(w, codeSize)
        bits.write(END_OF_INFO_CODE, codeSize)
        return bits.toByteArray()
    }
}

/** A little-endian byte accumulator. */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun byte(value: Int) {
        out.write(value and 0xFF)
    }

    fun bytes(values: ByteArray) {
        out.write(values)
    }

    fun ascii(value: String) {
        for (c in value) {
            out.write(c.code)
        }
    }

    fun leShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    fun leInt(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    fun size(): Int = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** LSB-first bit packing (the GIF LZW convention). */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var bitCount = 0

    fun write(code: Int, codeSize: Int) {
        current = current or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            out.write(current and 0xFF)
            current = current ushr 8
            bitCount -= 8
        }
    }

    fun toByteArray(): ByteArray {
        if (bitCount > 0) {
            out.write(current and 0xFF)
            current = 0
            bitCount = 0
        }
        return out.toByteArray()
    }
}
