package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF encoder (r42): the header structure, the palette mapping, the
 * delay math, the sub-block splitting and --- the load-bearing pin --- a
 * full LZW round-trip through an INDEPENDENT decoder living in this test
 * (written from the decoder-side convention, not from the encoder), plus
 * the adversarial dictionary-growth case that pushes the code size to 12
 * bits and forces at least one CLEAR reset. The encoder was also
 * cross-validated against Pillow during development.
 */
class GifEncoderTest {

    // ---- the palette ----

    @Test
    fun `the palette is 256 entries with the cube then the grey tail`() {
        assertEquals(768, GifEncoder.paletteBytes.size)
        // cube entry 0 is black
        assertEquals(0, GifEncoder.paletteBytes[0].toInt() and 0xFF)
        assertEquals(0, GifEncoder.paletteBytes[1].toInt() and 0xFF)
        assertEquals(0, GifEncoder.paletteBytes[2].toInt() and 0xFF)
        // the last cube entry (5,5,5) is white at index 215
        assertEquals(255, GifEncoder.paletteBytes[215 * 3].toInt() and 0xFF)
        assertEquals(255, GifEncoder.paletteBytes[215 * 3 + 1].toInt() and 0xFF)
        assertEquals(255, GifEncoder.paletteBytes[215 * 3 + 2].toInt() and 0xFF)
        // the grey tail starts at 216
        assertEquals(0, GifEncoder.paletteBytes[216 * 3].toInt() and 0xFF)
        assertEquals(255, GifEncoder.paletteBytes[255 * 3].toInt() and 0xFF)
    }

    @Test
    fun `the palette index is the nearest cube entry per channel`() {
        assertEquals(0, GifEncoder.paletteIndex(0xFF000000.toInt())) // black
        assertEquals(215, GifEncoder.paletteIndex(0xFFFFFFFF.toInt())) // white
        // pure red: level (5, 0, 0)
        assertEquals(5 * 36, GifEncoder.paletteIndex(0xFFFF0000.toInt()))
        // 0x80 = 128: (128 + 25) / 51 = 3 -> level 3 (153), so index 3*36+3*6+3
        assertEquals(3 * 36 + 3 * 6 + 3, GifEncoder.paletteIndex(0xFF808080.toInt()))
        // 0x7F = 127: (127 + 25) / 51 = 2 -> level 2 (102), so index 2*36+2*6+2
        assertEquals(2 * 36 + 2 * 6 + 2, GifEncoder.paletteIndex(0xFF7F7F7F.toInt()))
    }

    // ---- the delay math ----

    @Test
    fun `the delay is the interval in hundredths with the 2cs visibility floor`() {
        assertEquals(3, GifEncoder.delayCsFor(33)) // 30fps
        assertEquals(50, GifEncoder.delayCsFor(500)) // 2fps
        assertEquals(10, GifEncoder.delayCsFor(100))
        assertEquals(2, GifEncoder.delayCsFor(10))
        assertEquals(2, GifEncoder.delayCsFor(1)) // the floor, honestly
    }

    // ---- the file structure ----

    @Test
    fun `the file is a GIF89a with the loop extension and one block per frame`() {
        val argb = IntArray(4 * 4) { 0xFF3366CC.toInt() }
        val bytes = GifEncoder.encode(
            listOf(
                GifEncoder.GifFrame(argb, 4, 4, 5),
                GifEncoder.GifFrame(argb, 4, 4, 5),
            ),
        )
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        // logical screen descriptor: 4x4, packed 0xF7, bg 0, aspect 0
        assertEquals(4, bytes[6].toInt() and 0xFF)
        assertEquals(0, bytes[7].toInt() and 0xFF)
        assertEquals(4, bytes[8].toInt() and 0xFF)
        assertEquals(0, bytes[9].toInt() and 0xFF)
        assertEquals(0xF7, bytes[10].toInt() and 0xFF)
        // the NETSCAPE loop extension rides after the palette: 0x21, 0xFF,
        // block size 0x0B, then the 11-char identifier
        val loopAt = 13 + 768
        assertEquals(0x21, bytes[loopAt].toInt() and 0xFF)
        assertEquals(0xFF, bytes[loopAt + 1].toInt() and 0xFF)
        assertEquals(0x0B, bytes[loopAt + 2].toInt() and 0xFF)
        assertEquals("NETSCAPE2.0", String(bytes, loopAt + 3, 11, Charsets.US_ASCII))
        // trailer at the very end
        assertEquals(0x3B, bytes[bytes.size - 1].toInt() and 0xFF)
        // two image descriptors (0x2C) --- one per frame
        assertEquals(2, bytes.toList().windowed(1).count { (b) -> b.toInt() and 0xFF == 0x2C })
    }

    @Test
    fun `the lzw payload is split into sub-blocks of at most 255 bytes`() {
        val argb = IntArray(64 * 64) { 0xFF000000.toInt() or ((it * 2654435761).toInt() and 0xFFFFFF) }
        val indices = ByteArray(argb.size) { i -> GifEncoder.paletteIndex(argb[i]).toByte() }
        val payload = GifEncoder.lzwEncode(indices)
        val out = ByteWriter()
        GifEncoder.subBlock(out, payload)
        val blocked = out.toByteArray()
        var offset = 0
        var blocks = 0
        while (offset < blocked.size) {
            val length = blocked[offset].toInt() and 0xFF
            assertTrue("sub-block length must be 1..255", length in 1..255)
            offset += 1 + length
            blocks++
        }
        assertEquals(blocked.size, offset)
        assertTrue("a 4096-pixel noise frame must span multiple sub-blocks", blocks > 1)
    }

    // ---- the LZW round-trip (the load-bearing pin) ----

    @Test
    fun `a tiny frame round-trips through the independent decoder`() {
        val argb = intArrayOf(
            0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF123456.toInt(), 0xFF654321.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val decoded = decodeLzw(argbToIndices(argb))
        assertEquals(argbToIndices(argb).toList(), decoded.toList())
    }

    @Test
    fun `a flat frame round-trips`() {
        val argb = IntArray(256) { 0xFF3366AA.toInt() }
        val indices = argbToIndices(argb)
        assertEquals(indices.toList(), decodeLzw(indices).toList())
    }

    @Test
    fun `a dictionary-growth frame round-trips with a forced clear`() {
        // 40960 pseudo-random pixels: the dictionary passes 512/1024/2048
        // entries (code size 10/11/12) and overflows 4096 (a CLEAR reset).
        val seed = 987654321L
        var state = seed
        fun next(): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and 0xFFFFFF)
        }
        val argb = IntArray(40960) { 0xFF000000.toInt() or next() }
        val indices = argbToIndices(argb)
        val decoded = decodeLzw(indices)
        assertEquals(indices.toList(), decoded.toList())
    }

    /** Maps ARGB pixels through the palette like the encoder does. */
    private fun argbToIndices(argb: IntArray): ByteArray =
        ByteArray(argb.size) { i -> GifEncoder.paletteIndex(argb[i]).toByte() }

    /**
     * The independent decoder (the decoder-side convention): reads
     * LSB-first codes, grows its code size when ITS next slot reaches the
     * boundary, handles the first-code-after-clear and the KwKwK case. If
     * the encoder's growth rule were off by one, this round-trip breaks.
     * Consumes the RAW lzw stream (the sub-block framing is a separate
     * pin above).
     */
    private fun decodeLzw(indices: ByteArray): ByteArray {
        val stream = GifEncoder.lzwEncode(indices)
        val clear = GifEncoder.CLEAR_CODE
        val eoi = GifEncoder.END_OF_INFO_CODE
        var codeSize = GifEncoder.MIN_CODE_SIZE + 1
        var nextSlot = GifEncoder.FIRST_FREE_CODE
        // the dictionary: code -> byte string (roots first)
        val dict = HashMap<Int, ByteArray>(4096)
        for (i in 0..255) {
            dict[i] = byteArrayOf(i.toByte())
        }
        var bitPos = 0
        var prev: ByteArray? = null
        val out = mutableListOf<Byte>()

        fun readCode(): Int {
            var code = 0
            for (bit in 0 until codeSize) {
                val byteIndex = (bitPos + bit) / 8
                val bitIndex = (bitPos + bit) % 8
                val b = stream[byteIndex].toInt() and 0xFF
                code = code or (((b ushr bitIndex) and 1) shl bit)
            }
            bitPos += codeSize
            return code
        }

        while (true) {
            val code = readCode()
            if (code == clear) {
                codeSize = GifEncoder.MIN_CODE_SIZE + 1
                nextSlot = GifEncoder.FIRST_FREE_CODE
                prev = null
                dict.clear() // stale entries from the previous segment would
                // answer a re-used code with the WRONG string --- the
                // adversarial round-trip pins exactly this
                for (i in 0..255) {
                    dict[i] = byteArrayOf(i.toByte())
                }
                continue
            }
            if (code == eoi) break
            val entry: ByteArray = when {
                dict.containsKey(code) -> dict[code]!!
                code == nextSlot && prev != null -> prev!! + prev!![0]
                else -> throw IllegalStateException("bad code $code at slot $nextSlot")
            }
            for (b in entry) {
                out.add(b)
            }
            if (prev != null) {
                dict[nextSlot++] = prev!! + entry[0]
                if (nextSlot == (1 shl codeSize) && codeSize < 12) {
                    codeSize++
                }
            }
            prev = entry
        }
        return out.toByteArray()
    }
}
