package com.toadgallery.editor

/**
 * The export preflight: the pure, JVM-tested decision layer for the on-device robustness
 * matrix (large files, low storage, process death).
 *
 *  - DECODE GUARD: refuses a full-resolution decode beyond the pixel cap (the export holds
 *    the pixels twice --- the IntArray and the ARGB_8888 bitmap --- so an uncapped 108 MP
 *    source is a certain OOM on mid-range devices). The cap is generous (48 MP); the honest
 *    failure is ERROR_UNSUPPORTED before any work, never a crash.
 *  - STORAGE PREFLIGHT: refuses to start an insert the device cannot hold. The byte model
 *    is a deliberately generous OVER-estimate (fail early, not predict the encoder);
 *    availability comes from `StorageManager` (advisory --- an unknown value always
 *    proceeds, MediaStore still enforces), and the decision demands a 1.5x headroom.
 *  - PROCESS-DEATH policy: on Q+ the pending row is invisible and MediaStore reclaims it;
 *    pre-Q the legacy insert may leave a visible partial row the editor cannot delete
 *    (no delete permission --- the row is the user's to remove). The sidecar is cleared
 *    only AFTER a successful export, so the edit session restores losslessly on relaunch.
 */
object ExportPreflight {

    /** The single preflight outcome. */
    enum class Decision { PROCEED, INSUFFICIENT }

    /**
     * The full-resolution decode cap (~48 MP; the pipeline holds the
     * pixels twice, so the peak is roughly `2 x 4 bytes x pixels`).
     */
    const val MAX_EXPORT_PIXELS: Long = 48_000_000L

    /**
     * The pixel count assumed for the storage estimate when the source's
     * bounds are unreadable (a typical modern-phone capture).
     */
    const val ASSUMED_PIXELS: Long = 12_000_000L

    /**
     * The encoded-size model: `pixels x 4` (ARGB_8888) scaled per format. PNG stores every
     * pixel losslessly (deflate can't be relied on for photographic content) --- estimate is
     * the raw size. JPEG/WebP scale with quality `(0.05 + 0.01 x q) x raw`, deliberately
     * over-estimating: the preflight fails early rather than predicting exactly.
     */
    fun estimateBytes(pixels: Long, format: ExportFormat, quality: Int): Long {
        require(pixels >= 0) { "pixels must be non-negative" }
        val raw = pixels * 4L
        return when (format) {
            ExportFormat.PNG -> raw
            ExportFormat.JPEG, ExportFormat.WEBP -> {
                val q = quality.coerceIn(1, 100)
                raw * (5L + q) / 100L
            }
        }
    }

    /**
     * The storage decision: proceed only when the allocatable bytes cover
     * the estimate plus a 1.5x headroom (the encoder's scratch, the EXIF
     * pass, and the filesystem's own overhead).
     */
    fun decide(estimatedBytes: Long, allocatableBytes: Long): Decision {
        require(estimatedBytes >= 0 && allocatableBytes >= 0)
        val need = estimatedBytes + estimatedBytes / 2
        return if (allocatableBytes >= need) Decision.PROCEED else Decision.INSUFFICIENT
    }

    /**
     * The large-file decode guard: refuse only a KNOWN over-cap source.
     * Non-positive dimensions mean "unknown bounds" --- the guard passes
     * them through (the decode itself answers the real verdict).
     */
    fun decodeGuard(widthPx: Int, heightPx: Int, maxPixels: Long): Decision {
        if (widthPx <= 0 || heightPx <= 0) return Decision.PROCEED
        val pixels = widthPx.toLong() * heightPx.toLong()
        return if (pixels > maxPixels) Decision.INSUFFICIENT else Decision.PROCEED
    }

    /** What an export interrupted by process death leaves behind. */
    enum class Interruption {
        /** Q+: the pending row is invisible + MediaStore reclaims it; the sidecar restores the session. */
        PENDING_RECLAIMED_BY_MEDIASTORE,

        /** Pre-Q: the legacy insert may leave a visible partial row the editor cannot delete. */
        PARTIAL_ROW_VISIBLE_PRE_Q,
    }

    /**
     * The process-death outcome (pure): the sidecar survives both branches
     * (it is cleared only after a successful export), so the session is
     * never lost --- only the platform row handling differs.
     */
    fun onProcessDeath(api29Plus: Boolean): Interruption =
        if (api29Plus) {
            Interruption.PENDING_RECLAIMED_BY_MEDIASTORE
        } else {
            Interruption.PARTIAL_ROW_VISIBLE_PRE_Q
        }
}
