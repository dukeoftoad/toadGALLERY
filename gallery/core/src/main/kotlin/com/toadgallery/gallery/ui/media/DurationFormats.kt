package com.toadgallery.gallery.ui.media

import java.util.Locale

/**
 * Duration formatting for the video overlay (issue #33).
 *
 * Pure and top-level on purpose: like the geometry helper in
 * `com.toadgallery.gallery.data.MediaGeometry`, it depends on no
 * `MediaItem`/`Uri` machinery, so plain JVM unit tests can pin the exact
 * strings (`DurationFormatsTest`) without an emulator or Robolectric.
 *
 * Format: `m:ss` below one hour (`59_000` ms -> `"0:59"`, `84_000` ms ->
 * `"1:24"`), `h:mm:ss` from one hour up (`3_600_000` ms -> `"1:00:00"`,
 * `3_723_000` ms -> `"1:02:03"`). Non-positive durations --- `0` is the data
 * layer's "unknown" sentinel --- render as `"0:00"`, letting the UI hide the
 * overlay entirely. [Locale.US] keeps the digits stable regardless of device
 * locale: a duration is a technical string, not localized prose.
 */
fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours >= 1) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
