package io.github.dukeoftoad.gallery.edit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animated WebP muxer (r42): the RIFF framing, the VP8X canvas header,
 * the ANIM loop chunk, the per-frame ANMF fields and the verbatim
 * image-chunk extraction --- pinned over hand-built static-WebP byte
 * arrays (no platform encoder needed on the JVM). The muxer was also
 * cross-validated against Pillow during development.
 */
class AnimatedWebpMuxerTest {

    /** Builds a synthetic static WebP file: RIFF/WEBP + one VP8L chunk. */
    private fun staticWebp(vp8lPayload: ByteArray): ByteArray {
        val out = ByteWriter()
        out.ascii("RIFF")
        // 4 (WEBP) + 8 + payload (+ pad)
        val chunkSize = vp8lPayload.size
        out.leInt(4 + 8 + chunkSize + (chunkSize and 1))
        out.ascii("WEBP")
        out.ascii("VP8L")
        out.leInt(chunkSize)
        out.bytes(vp8lPayload)
        if (chunkSize and 1 == 1) {
            out.byte(0)
        }
        return out.toByteArray()
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int = AnimatedWebpMuxer.readLeInt(bytes, offset)

    // ---- the chunk extraction ----

    @Test
    fun `the image chunk is extracted verbatim from a static webp`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val file = staticWebp(payload)
        val chunks = AnimatedWebpMuxer.extractImageChunks(file)
        assertEquals(1, chunks.size)
        assertEquals("VP8L", chunks[0].first)
        assertTrue(chunks[0].second.contentEquals(payload))
    }

    @Test
    fun `container chunks are skipped and odd payloads keep their pad`() {
        val out = ByteWriter()
        out.ascii("RIFF")
        out.leInt(4 + 8 + 2 + 8 + 3 + 1) // WEBP + ICCP(2) + VP8L(3 + 1 pad)
        out.ascii("WEBP")
        out.ascii("ICCP")
        out.leInt(2)
        out.bytes(byteArrayOf(9, 9))
        out.ascii("VP8L")
        out.leInt(3)
        out.bytes(byteArrayOf(1, 2, 3))
        out.byte(0) // the odd-length pad
        val chunks = AnimatedWebpMuxer.extractImageChunks(out.toByteArray())
        assertEquals(1, chunks.size)
        assertEquals("VP8L", chunks[0].first)
        assertTrue(chunks[0].second.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a non-webp input is refused honestly`() {
        try {
            AnimatedWebpMuxer.extractImageChunks(byteArrayOf(1, 2, 3))
            assertTrue("too short must refuse", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too short"))
        }
        try {
            AnimatedWebpMuxer.extractImageChunks("RIFF1234JUNKxxxxxxxx".toByteArray(Charsets.US_ASCII))
            assertTrue("wrong form type must refuse", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("WEBP form type"))
        }
    }

    // ---- the muxed container ----

    @Test
    fun `the muxed file is a riff webp with vp8x, anim and one anmf per frame`() {
        val frames = listOf(
            AnimatedWebpMuxer.WebpFrame(staticWebp(byteArrayOf(1)), 80),
            AnimatedWebpMuxer.WebpFrame(staticWebp(byteArrayOf(2)), 100),
        )
        val bytes = AnimatedWebpMuxer.mux(frames, 64, 48)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        // the RIFF size covers everything after the size field
        assertEquals(bytes.size - 8, leInt(bytes, 4))
        // VP8X: 10-byte payload, animation flag, canvas 64x48 minus one
        assertEquals("VP8X", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(10, leInt(bytes, 16))
        assertEquals(AnimatedWebpMuxer.VP8X_FLAG_ANIMATION, bytes[20].toInt() and 0xFF)
        // 63 = 64 - 1 in three little-endian bytes at payload[4..6], 47 = 48 - 1
        // at payload[7..9] (flags byte + three reserved bytes come first).
        assertEquals(63, (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8))
        assertEquals(47, (bytes[27].toInt() and 0xFF) or ((bytes[28].toInt() and 0xFF) shl 8))
        // ANIM: opaque background + loop forever
        val animAt = 12 + 8 + 10
        assertEquals("ANIM", String(bytes, animAt, 4, Charsets.US_ASCII))
        assertEquals(6, leInt(bytes, animAt + 4))
        assertEquals(0xFF, bytes[animAt + 11].toInt() and 0xFF) // alpha of the background
        // two ANMF chunks
        var offset = animAt + 8 + 6
        for (i in 0 until 2) {
            assertEquals("ANMF", String(bytes, offset, 4, Charsets.US_ASCII))
            val size = leInt(bytes, offset + 4)
            // 16 fixed field bytes (five uint24 + the flags byte) + the
            // carried chunk header (8) + a 1-byte payload + its odd pad
            val expected = 5 * 3 + 1 + 8 + 1 + 1
            assertEquals(expected, size)
            // the frame duration in the ANMF fields
            val duration = (bytes[offset + 8 + 12].toInt() and 0xFF) or
                ((bytes[offset + 8 + 13].toInt() and 0xFF) shl 8)
            assertEquals(if (i == 0) 80 else 100, duration)
            // the carried image chunk rides verbatim
            assertEquals(
                "VP8L",
                String(bytes, offset + 8 + 16, 4, Charsets.US_ASCII),
            )
            offset += 8 + size
        }
        assertEquals(bytes.size, offset)
    }

    @Test
    fun `the anmf blend flag is the no-blend bit, never a reserved bit`() {
        val bytes = AnimatedWebpMuxer.mux(
            listOf(AnimatedWebpMuxer.WebpFrame(staticWebp(byteArrayOf(1)), 50)),
            10,
            10,
        )
        // the flags byte sits after the six uint24 fields
        val anmfAt = 12 + 8 + 10 + 8 + 6
        val flags = bytes[anmfAt + 8 + 15].toInt() and 0xFF
        assertEquals(0x02, flags)
    }

    // ---- the guards ----

    @Test
    fun `the canvas and duration guards refuse honestly`() {
        try {
            AnimatedWebpMuxer.mux(
                listOf(AnimatedWebpMuxer.WebpFrame(staticWebp(byteArrayOf(1)), 50)),
                0,
                10,
            )
            assertTrue("zero width must refuse", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("canvas width"))
        }
        try {
            AnimatedWebpMuxer.WebpFrame(staticWebp(byteArrayOf(1)), 0)
            assertTrue("zero duration must refuse", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("duration"))
        }
    }

    @Test
    fun `an empty frame list is refused`() {
        try {
            AnimatedWebpMuxer.mux(emptyList(), 10, 10)
            assertTrue("empty must refuse", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one frame"))
        }
    }
}
