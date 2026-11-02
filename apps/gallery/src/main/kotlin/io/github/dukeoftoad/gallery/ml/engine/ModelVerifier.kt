package io.github.dukeoftoad.gallery.ml.engine

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Verifies a model file against its pinned manifest spec, Ente-style: the
 * expected byte size is checked first (cheap rejection), then the SHA-256 is
 * computed streaming (never a full-file memory buffer --- the CLIP image
 * encoder alone is ~143 MB, and the Phase 4 read contract forbids full-file
 * buffering).
 *
 * Verification gates installation: a file that has not passed
 * [verifyFile]/[verifyStream] must never be handed to the inference engine
 * (audit: "Verify every file's SHA-256 and expected size before session
 * creation").
 */
object ModelVerifier {

    /** Why a candidate file was refused. */
    sealed class VerificationError(open val message: String) {
        /** Size differs from the pinned expectation; the file is not what the pin promises. */
        data class SizeMismatch(val expected: Long, val actual: Long) :
            VerificationError("expected $expected bytes, got $actual")

        /** Content hash differs from the pin; refuses inference on tampered/stale bytes. */
        data class HashMismatch(val expected: String, val actual: String) :
            VerificationError("expected sha256 $expected, got $actual")
    }

    /**
     * Verifies [file] against [spec]. Returns the actual SHA-256 on success
     * (which equals [ModelManifest.Model.sha256]) or a [VerificationError].
     * A missing file is a size mismatch (actual = 0) at this layer; callers
     * that care about the distinction check existence first.
     */
    fun verifyFile(file: File, spec: ModelManifest.Model): VerificationError? {
        val actualSize = if (file.exists()) file.length() else 0L
        if (actualSize != spec.sizeBytes) return VerificationError.SizeMismatch(spec.sizeBytes, actualSize)
        return file.inputStream().buffered().use { verifyStream(it, spec) }
    }

    /**
     * Streaming verification of [stream] against [spec]. Size is taken from
     * the bytes actually read; hash is computed incrementally. The stream is
     * NOT closed (the caller owns it).
     */
    fun verifyStream(stream: InputStream, spec: ModelManifest.Model): VerificationError? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                total += read
            }
        }
        if (total != spec.sizeBytes) return VerificationError.SizeMismatch(spec.sizeBytes, total)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        // Constant-time comparison (MessageDigest.isEqual) --- cheap defense
        // in depth even though the pin is public.
        if (!MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII),
                spec.sha256.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return VerificationError.HashMismatch(spec.sha256, actual)
        }
        return null
    }

    /** True when [hex] is a well-formed 64-character lowercase hex digest. */
    fun isWellFormedSha256(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }
}
