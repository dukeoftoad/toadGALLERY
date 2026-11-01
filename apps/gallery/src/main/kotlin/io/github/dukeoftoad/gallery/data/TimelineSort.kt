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
 * ## Two orderings, one discipline (r13)
 * The MediaStore paths order by `date_added` ([sortOrder] / [sortItems])
 * and stay exactly as they were --- they are the FALLBACK the timeline
 * keeps while the first scan populates the catalogue, and the order of
 * every MediaStore-fed surface. The catalogue-backed timeline instead
 * orders by the EFFECTIVE instant (resolved capture date, else
 * `dateAdded`); the Room half of that order is the catalogue's
 * `effectiveOrderSql` (owned by the catalogue package), and
 * [effectiveComparator] below is its in-memory twin --- same total-order
 * discipline, pinned on the plain JVM.
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

    /**
     * The catalogue path's in-memory ordering twin (r13): orders by the
     * EFFECTIVE instant ([dateOf], already `captureDateMillis ?:
     * dateAddedSec * 1000`, in MILLIS) with [keyOf] --- the stable local
     * media key --- as the tiebreak, BOTH keys in this direction. This is
     * to the catalogue's `effectiveOrderSql` what [sortItems] is to
     * [sortOrder]: the production catalogue windows arrive pre-ordered by
     * the Room query (that SQL owns the total order), and this comparator
     * is the JVM-pinned statement of the same discipline for tests and any
     * future in-memory catalogue path.
     *
     * The tiebreak discipline is the `_id` rule transposed to the
     * catalogue's identity: many items share one effective instant (burst
     * shots, imports), and a window boundary can fall between them; the
     * local-media-key tiebreak --- not `_id`, which the catalogue key
     * already embeds --- pins every window to the same sequence in BOTH
     * directions (OLDEST_FIRST is the same total order walked backwards,
     * exactly like [sortItems]). Keys are ASCII `volume:id[:page]`
     * strings, so Kotlin's lexicographic [String] comparison and SQLite's
     * BINARY collation agree on every real key.
     *
     * Generic over the element for the same JVM-purity reason as
     * [sortItems]: production instantiates it with catalogue records or
     * their lightweight stand-ins, never with `MediaItem` (which embeds
     * `android.net.Uri`).
     */
    fun <T> effectiveComparator(
        dateOf: (T) -> Long,
        keyOf: (T) -> String,
    ): Comparator<T> = when (this) {
        NEWEST_FIRST -> compareByDescending(dateOf).thenByDescending(keyOf)
        OLDEST_FIRST -> compareBy(dateOf).thenBy(keyOf)
    }
}
