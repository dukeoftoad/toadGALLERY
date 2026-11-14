package com.toadgallery.editor.video

/**
 * The animated WebP muxer: assembles the platform lossless WebP encoder's per-frame
 * static WebP files (Bitmap.compress(WEBP_LOSSLESS), Android-side) into a minimal
 * animated container --- RIFF/WEBP + VP8X canvas header + ANIM + one ANMF per frame
 * carrying its ALPH/VP8L chunks verbatim. Pure Kotlin, JVM-tested; the lossless
 * encoder needs API 30+ (the animation preflight refuses WebP below that).
 */
object AnimatedWebpMuxer {

    /** The VP8X animation flag (bit 1 of the flags byte). */
    const val VP8X_FLAG_ANIMATION: Int = 0x02

    /** The 24-bit canvas-size ceiling (a VP8X field stores size - 1). */
    const val MAX_CANVAS_DIMENSION: Int = 16_777_216

    /** One source frame: the complete static WebP file bytes + its duration in ms. */
    class WebpFrame(val fileBytes: ByteArray, val durationMs: Int) {
        init {
            require(durationMs in 1..16_777_215) { "frame duration must be 1..16777215 ms, was $durationMs" }
        }
    }

    /** Muxes the frames into an animated WebP file. */
    fun mux(frames: List<WebpFrame>, canvasWidthPx: Int, canvasHeightPx: Int): ByteArray {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
        require(canvasWidthPx in 1..MAX_CANVAS_DIMENSION) {
            "canvas width must be 1..$MAX_CANVAS_DIMENSION, was $canvasWidthPx"
        }
        require(canvasHeightPx in 1..MAX_CANVAS_DIMENSION) {
            "canvas height must be 1..$MAX_CANVAS_DIMENSION, was $canvasHeightPx"
        }
        val body = ByteWriter()
        // VP8X: flags (animation) + reserved + canvas size - 1, three bytes
        // per dimension, little-endian.
        body.ascii("VP8X")
        body.leInt(10)
        body.byte(VP8X_FLAG_ANIMATION)
        body.byte(0)
        body.byte(0)
        body.byte(0)
        writeUint24(body, canvasWidthPx - 1)
        writeUint24(body, canvasHeightPx - 1)
        // ANIM: background colour (opaque black) + loop count 0 (forever).
        body.ascii("ANIM")
        body.leInt(6)
        body.bytes(byteArrayOf(0, 0, 0, 0xFF.toByte()))
        body.leShort(0)
        for (frame in frames) {
            val imageChunks = extractImageChunks(frame.fileBytes)
            // ANMF: frame x/y (0,0), size - 1, duration, blend flags (frames are opaque,
            // no alpha blend), then the image data chunks verbatim (padded to even RIFF sizes).
            val anmfBody = ByteWriter()
            writeUint24(anmfBody, 0)
            writeUint24(anmfBody, 0)
            writeUint24(anmfBody, canvasWidthPx - 1)
            writeUint24(anmfBody, canvasHeightPx - 1)
            writeUint24(anmfBody, frame.durationMs)
            anmfBody.byte(0x02) // ANMF flags bit 1 = no blend (bit 0 = disposal, stays 0)
            for ((fourcc, payload) in imageChunks) {
                anmfBody.ascii(fourcc)
                anmfBody.leInt(payload.size)
                anmfBody.bytes(payload)
                if (payload.size and 1 == 1) {
                    anmfBody.byte(0) // RIFF chunks are padded to even sizes
                }
            }
            val anmfBytes = anmfBody.toByteArray()
            body.ascii("ANMF")
            body.leInt(anmfBytes.size)
            body.bytes(anmfBytes)
        }
        val out = ByteWriter()
        out.ascii("RIFF")
        out.leInt(4 + body.size())
        out.ascii("WEBP")
        out.bytes(body.toByteArray())
        return out.toByteArray()
    }

    /**
     * Extracts the image-data chunks (ALPH, VP8, VP8L) from a static WebP file, skipping
     * container-level chunks (VP8X, ANIM, ANMF, ICCP, EXIF, XMP). Payloads are copied
     * verbatim --- the muxer never re-encodes frame pixels.
     */
    fun extractImageChunks(webpFileBytes: ByteArray): List<Pair<String, ByteArray>> {
        require(webpFileBytes.size >= 12) { "not a WebP file: too short (${webpFileBytes.size} bytes)" }
        require(String(webpFileBytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "not a WebP file: missing the RIFF header"
        }
        require(String(webpFileBytes, 8, 4, Charsets.US_ASCII) == "WEBP") {
            "not a WebP file: missing the WEBP form type"
        }
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var offset = 12
        while (offset + 8 <= webpFileBytes.size) {
            val fourcc = String(webpFileBytes, offset, 4, Charsets.US_ASCII)
            val length = readLeInt(webpFileBytes, offset + 4)
            val payloadStart = offset + 8
            if (payloadStart + length > webpFileBytes.size) {
                throw IllegalArgumentException("truncated WebP chunk '$fourcc'")
            }
            when (fourcc) {
                "ALPH", "VP8 ", "VP8L" -> {
                    chunks.add(fourcc to webpFileBytes.copyOfRange(payloadStart, payloadStart + length))
                }
                // Container chunks skipped; an already-animated input is a caller bug.
            }
            // chunks are padded to even sizes
            offset = payloadStart + length + (length and 1)
        }
        require(chunks.isNotEmpty()) { "the WebP file carries no image-data chunk" }
        return chunks
    }

    /** Writes a 24-bit little-endian value. */
    internal fun writeUint24(out: ByteWriter, value: Int) {
        require(value in 0..0xFFFFFF) { "uint24 out of range: $value" }
        out.byte(value and 0xFF)
        out.byte((value ushr 8) and 0xFF)
        out.byte((value ushr 16) and 0xFF)
    }

    internal fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
