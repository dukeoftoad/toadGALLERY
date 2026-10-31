package io.github.dukeoftoad.gallery.data

/**
 * The timeline's sort order (issue #66): newest-first (the default the
 * timeline has always used) or oldest-first.
 *
 * Pure and Android-free on purpose (the ADR-0016 / `SearchQuery`
 * convention): the enum maps to the exact MediaStore SQL sort order the
 * windowed queries ([MediaStoreSource.queryTimelineWindow]) and the
 * bucket queries ([MediaStoreSource.queryBucket]) send, and to a total
 * in-memory ordering for the pre-R snapshot paths, so plain JVM tests pin
 * both halves without an emulator or Robolectric.
 *
 * ## The `_id` tiebreak is REQUIRED in both directions
 * Many rows share one `date_added` second (burst shots, screenshots), and a
 * window boundary can fall between them; without a total order the provider
 * would be free to reorder tied rows between windows, silently skipping or
 * duplicating items across pages (see `WindowPagingSource`'s KDoc for the
 * duplicate-LazyGrid-key failure that causes). `OLDEST_FIRST` mirrors the
 * discipline with ASC on both columns --- ascending the timeline is the same
 * total order walked backwards, so paging stays stable in both directions.
 *
 * v1 scope (documented): per-session ViewModel state only --- persisting the
 * choice across app restarts is deliberately out of scope for issue #66.
 */
enum class TimelineSort {
    /** Newest items first (`date_added DESC, _id DESC`) --- the default. */
    NEWEST_FIRST,

    /** Oldest items first (`date_added ASC, _id ASC`). */
    OLDEST_FIRST,

    ;

    /**
     * The MediaStore SQL sort order for this direction, including the `_id`
     * tiebreak. Feeds `ContentResolver.QUERY_ARG_SQL_SORT_ORDER` (the
     * windowed queries) and the string sortOrder parameter (the favorites
     * query); the literals equal the inlined `MediaStore` column constants
     * (`date_added` == `MediaStore.MediaColumns.DATE_ADDED`, `_id` ==
     * `MediaStore.MediaColumns._ID`), spelled out so this file stays JVM-pure
     * (the `SearchQuery` convention).
     */
    val sortOrder: String
        get() = when (this) {
            NEWEST_FIRST -> "date_added DESC, _id DESC"
            OLDEST_FIRST -> "date_added ASC, _id ASC"
        }

    /**
     * Orders [items] in this direction by `date_added` with the `_id`
     * tiebreak --- the in-memory twin of [sortOrder] for the snapshot paths
     * (the pre-R Photos timeline fallback, the bucket detail lists). Returns
     * a new list; the input is untouched.
     *
     * Generic over the element for the same reason `moveTargets` (#64) is:
     * production instantiates it with `MediaItem`, which embeds
     * `android.net.Uri` a plain JVM test cannot construct, so the tests pin
     * the CONTRACT with a lightweight fake.
     */
    fun <T> sortItems(
        items: List<T>,
        dateAddedSec: (T) -> Long,
        id: (T) -> Long,
    ): List<T> = when (this) {
        NEWEST_FIRST -> items.sortedWith(
            compareByDescending(dateAddedSec).thenByDescending(id),
        )
        OLDEST_FIRST -> items.sortedWith(
            compareBy(dateAddedSec).thenBy(id),
        )
    }
}
