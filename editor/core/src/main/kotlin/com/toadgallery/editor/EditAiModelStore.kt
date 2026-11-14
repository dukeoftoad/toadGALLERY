package com.toadgallery.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The on-device AI model store: the four ONNX graphs live in app-private storage
 * (`filesDir/models/`), downloaded on demand from the pinned release URLs (never
 * bundled in the APK). Every file is verified against the [EditAiManifest] pins
 * (byte size at every use, SHA-256 after every download); a failed or cancelled
 * download leaves no partial file behind.
 */
class EditAiModelStore(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, "models")

    /** The file backing a manifest model. */
    fun fileFor(model: EditAiManifest.ModelFile): File = File(root, model.fileName)

    /** Presence + byte-size check (the per-inference gate; the digest check runs on download). */
    fun present(model: EditAiManifest.ModelFile): Boolean {
        val file = fileFor(model)
        return file.isFile && file.length() == model.bytes
    }

    /** True only when every listed model passes [present]. */
    fun presentAll(models: List<EditAiManifest.ModelFile>): Boolean = models.all { present(it) }

    /** The inference seam: opens `models/<name>` from the store root, null when absent. */
    fun openRelative(path: String): InputStream? {
        return try {
            val file = File(root, path)
            if (!file.isFile) return null
            FileInputStream(file)
        } catch (_: RuntimeException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Downloads one model into the store root: streamed to a `.part` file, verified
     * (byte size + SHA-256), then atomically renamed into place. Throws [IOException]
     * on any network or verification failure (the caller owns the user messaging);
     * cancellation surfaces as coroutine cancellation and still cleans the `.part`.
     */
    suspend fun download(
        model: EditAiManifest.ModelFile,
        onBytes: (Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        if (present(model)) return@withContext
        root.mkdirs()
        val part = File(root, model.fileName + ".part")
        try {
            val connection = URL(model.downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("the model download answered HTTP ${connection.responseCode}")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var counted = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            counted += read
                            onBytes(read)
                        }
                    }
                }
                if (counted != model.bytes) {
                    throw IOException("the model size mismatch: $counted != ${model.bytes}")
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != model.sha256) throw IOException("the model digest mismatch")
                if (!part.renameTo(target)) throw IOException("the model file rename failed")
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            part.delete()
            throw error
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
