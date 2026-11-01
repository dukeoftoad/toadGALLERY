package io.github.dukeoftoad.gallery.metadata.video

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Pure data summary of one video's CONTAINER metadata (Phase 4,
 * Aves-informed assessment: video timestamps come from the container, not
 * from EXIF --- the audit's "metadata breadth" row).
 *
 * [rotationDegrees] is the container's display rotation (0/90/180/270);
 * [durationMillis] the container duration (`0` = unknown, matching the
 * `MediaItem` convention); [captureDateMillis] the container creation
 * time in epoch millis, `null` when the container carries none or the
 * value is malformed --- it feeds [CaptureDateSources][io.github.dukeoftoad.gallery.metadata.CaptureDateSources]
 * later, never replaces the EXIF/XMP sources.
 */
data class VideoContainerMetadata(
    val rotationDegrees: Int,
    val durationMillis: Long,
    val captureDateMillis: Long?,
)

/**
 * The video-container seam (the `metadata/video` package boundary from
 * the Phase 4 contract's recommended package layout).
 *
 * The interface is Uri-based, NOT bytes-based, and that is a deliberate
 * honesty call: `android.media.MediaMetadataRetriever` has no
 * `setDataSource(ByteArray)` overload, and the only way to feed it bytes
 * would be writing a plaintext temp file --- which the audit's
 * MediaReadHandle contract explicitly forbids ("avoid plaintext temp
 * files", item 5). So the source opens the item's content Uri through a
 * [ContentResolver] `ParcelFileDescriptor` (descriptor-backed, no
 * buffering the whole file, no temp copy) and lets the platform demux the
 * container.
 */
interface VideoMetadataSource {
    /**
     * Reads container metadata for [uri], or `null` when the descriptor
     * cannot be opened (item gone, permission revoked) or the container
     * cannot be demuxed. Individual malformed values (bad rotation,
     * unparseable date) degrade inside the returned object rather than
     * failing the whole read.
     */
    fun read(uri: Uri): VideoContainerMetadata?
}

/**
 * Pure parsing for the string values `MediaMetadataRetriever` returns ---
 * extracted from the Android wrapper so the formats are JVM-tested
 * (ADR-0016 seam pattern; the wrapper class below stays thin).
 */
object VideoMetadataParse {

    /**
     * MMR date format: `yyyyMMdd'T'HHmmss.SSS` plus an optional zone
     * suffix ("Z" or "+HH:MM"/"-HHMM"), e.g. `20240517T093000.000`.
     * Strict resolver so impossible dates are rejected rather than
     * rolled over; `uuuu` because STRICT year-of-era needs an era field.
     */
    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss[.SSS]")
            .withResolverStyle(ResolverStyle.STRICT)

    /** Zone-offset suffix: "Z", "+09:00", "-0530" at the end of a string. */
    private val OFFSET_SUFFIX = Regex("[+-]\\d{2}:?\\d{2}$")

    /**
     * The VIDEO_ROTATION metadata string to 0/90/180/270. Anything else
     * (missing, non-numeric, off-axis values some encoders write) becomes
     * `0` --- an unknown rotation must not crash the pipeline, and `0` is
     * the only safe display assumption.
     */
    fun rotationDegrees(text: String?): Int {
        val value = text?.trim()?.toIntOrNull() ?: return 0
        return when (value) {
            0, 90, 180, 270 -> value
            else -> 0
        }
    }

    /**
     * The DURATION metadata string (milliseconds) to a Long; missing,
     * non-numeric or negative values become `0` ("unknown"), matching the
     * `MediaItem.durationMs` convention the grid overlay already uses.
     */
    fun durationMillis(text: String?): Long {
        val value = text?.trim()?.toLongOrNull() ?: return 0L
        return if (value < 0L) 0L else value
    }

    /**
     * The DATE metadata string to epoch millis, or `null` on anything
     * malformed (null/blank, impossible dates, unparseable offset).
     *
     * Zone handling, documented honestly: a "Z" or numeric-offset suffix
     * is honored; a value with NO suffix is anchored at UTC. Older
     * `MediaMetadataRetriever` builds returned device-local naive times,
     * and without the capture timezone there is nothing better to do ---
     * the same call the EXIF date parser makes, superseded later by the
     * timezone-aware MediaDates resolver (audit item 3).
     */
    fun captureTimestampMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var body = text.trim()
        var offset: ZoneOffset = ZoneOffset.UTC
        if (body.endsWith("Z") || body.endsWith("z")) {
            body = body.dropLast(1)
        } else {
            val suffix = OFFSET_SUFFIX.find(body) ?: return parseBody(body, offset)
            val sign = if (suffix.value[0] == '-') -1 else 1
            val hours = suffix.value.substring(1, 3).toIntOrNull() ?: return null
            val minutes = suffix.value.substring(3).removePrefix(":").toIntOrNull() ?: return null
            if (minutes > 59) return null
            offset = try {
                ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
            } catch (e: IllegalArgumentException) {
                return null
            }
            body = body.dropLast(suffix.value.length)
        }
        return parseBody(body, offset)
    }

    /** Parses the `yyyyMMdd'T'HHmmss[.SSS]` body; `null` when malformed. */
    private fun parseBody(body: String, offset: ZoneOffset): Long? {
        val parsed: LocalDateTime = try {
            LocalDateTime.parse(body, DATE_FORMAT)
        } catch (e: DateTimeParseException) {
            return null
        }
        return parsed.toInstant(offset).toEpochMilli()
    }
}

/**
 * The one Android [VideoMetadataSource]: a thin `MediaMetadataRetriever`
 * wrapper.
 *
 * NOT JVM-unit-tested, by design (ADR-0016): descriptor I/O plus three
 * `extractMetadata` lookups with no branching beyond null handling ---
 * every value conversion and degradation rule lives in
 * [VideoMetadataParse], which carries the JVM suite. The retriever needs
 * the Android runtime, so it cannot load under the plain JUnit runner.
 *
 * Failure behavior: any open/demux failure returns `null` (item-level
 * "no container metadata", paired with the format matrix by the caller);
 * the retriever is always released; and the descriptor is always closed,
 * including on failure.
 */
class MediaMetadataRetrieverVideoMetadataSource(
    private val resolver: ContentResolver,
) : VideoMetadataSource {

    override fun read(uri: Uri): VideoContainerMetadata? {
        val descriptor: ParcelFileDescriptor = try {
            resolver.openFileDescriptor(uri, "r") ?: return null
        } catch (e: Exception) {
            // Item gone, permission revoked, or any provider failure:
            // no container metadata for this item.
            return null
        }
        descriptor.use { fd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(fd.fileDescriptor)
                return VideoContainerMetadata(
                    rotationDegrees = VideoMetadataParse.rotationDegrees(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                    ),
                    durationMillis = VideoMetadataParse.durationMillis(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    ),
                    captureDateMillis = VideoMetadataParse.captureTimestampMillis(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                    ),
                )
            } catch (e: Exception) {
                // The container could not be demuxed: unreadable at the
                // item level, not a crash.
                return null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
