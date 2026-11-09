package com.toadgallery.gallery.data

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Human-readable byte sizes for the recycle bin UI (issue #31): the Settings
 * entry shows "n items · 123 MB", the Trash screen header shows the same
 * aggregate.
 *
 * Pure JVM (like [FileOpNaming]) so `DataSizeFormatsTest` pins the format
 * contract without an emulator (ADR-0016). Decimal (SI) units --- 1 MB =
 * 1000 KB --- matching what Android's own storage UIs show; units are
 * locale-stable ASCII ("B", "KB", "MB", "GB", "TB"), values use
 * [Locale.ROOT] so no locale turns the decimal point into a comma in a
 * size-only string.
 */
object DataSizeFormats {

    /** 1024^4 fits in Long; the TB step is the practical ceiling. */
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /**
     * Formats [bytes] with decimal units: plain integers for bytes
     * ("999 B"), integers at 100+ of a unit ("340 MB"), one decimal below
     * that ("4.5 KB"), plain "0 B" for non-positive input. Never throws on
     * absurd values; Long.MAX_VALUE lands in TB.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val digitGroups = (log10(bytes.toDouble()) / log10(1000.0)).toInt()
        val group = digitGroups.coerceAtMost(units.size - 1)
        val scaled = bytes / 1000.0.pow(group.toDouble())
        val pattern = when {
            group == 0 -> "%.0f %s" // bytes: never "1.0 B"
            scaled >= 100 -> "%.0f %s"
            else -> "%.1f %s"
        }
        return String.format(Locale.ROOT, pattern, scaled, units[group])
    }
}
