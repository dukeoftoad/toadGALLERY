package io.github.dukeoftoad.gallery.ui.photos

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.filters.DerivedCollections
import io.github.dukeoftoad.gallery.data.filters.FilterMediaType
import io.github.dukeoftoad.gallery.data.filters.TimelineFilter
import io.github.dukeoftoad.gallery.data.filters.withFavoritesToggled
import io.github.dukeoftoad.gallery.data.filters.withTypeToggled
import io.github.dukeoftoad.gallery.ui.theme.ToadSpacing

/**
 * The Photos timeline's smart-filter chips row (r17 round, Phase 4
 * still-open item "Aves-style collections and smart-filter chips"):
 * a horizontally scrolling row of Material3 [FilterChip]s under the
 * header bar, narrowing the timeline through the PhotosViewModel's
 * [TimelineFilter] (see `PhotosViewModel` for how each data path applies
 * it --- always inside the window query, never client-side after paging).
 *
 * ## Chip semantics (multi-select where it composes sensibly)
 *  - **All**: selected exactly while the filter is INACTIVE; tapping it
 *    clears every dimension. Together with the trailing Clear action this
 *    is the row's clear-all affordance (both set the same fresh
 *    [TimelineFilter] --- one state, two discoverable affordances).
 *  - **Photos / Videos / GIFs**: independent multi-select toggles. GIFs
 *    are a SUBSET of photos in the engine's reduction
 *    (`FilterTypeCoverage`), so Photos+GIFs reads back as just Photos ---
 *    the engine owns that rule; the chips only toggle membership.
 *  - **Favorites**: an independent toggle AND-composed with the type set.
 *    Hidden below Android 10 (Q): the `is_favorite` provider column is
 *    Q+ (see `Favorites`), so below Q every read maps `isFavorite =
 *    false` and the chip would promise a view that can never fill --- the
 *    same tiering the favorites pseudo-album card and the viewer's
 *    favorite toggle use.
 *
 * The row shows with the plain timeline (it hides in selection mode
 * together with the header bar, exactly like `TimelineSortMenu`) AND with
 * the nothing-matched empty state, where its All/Clear affordances are the
 * way back from an over-narrow filter --- the filter is ViewModel state
 * that survives tab switches, so a vanished row would strand the user.
 * State is hoisted: this composable is purely presentational over
 * (filter, onChange), so the ViewModel's per-session state survives
 * config changes and drives both the chips and the data pipeline from
 * one source.
 *
 * Chips stay text-only (no icons): the core material-icons set has no
 * photo/video glyphs (the extended-icons dependency is deliberately
 * absent), and the codebase's convention is text over mystery icons
 * (see `FileOpsSelectionActions`).
 */
@Composable
fun PhotosFilterChipsRow(
    filter: TimelineFilter,
    onChange: (TimelineFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.testTag("photos_filter_chips"),
        contentPadding = PaddingValues(horizontal = ToadSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(ToadSpacing.sm),
    ) {
        item {
            FilterChip(
                selected = !filter.isActive,
                onClick = { onChange(TimelineFilter()) },
                label = { Text(stringResource(R.string.photos_filter_all)) },
                modifier = Modifier.testTag("photos_filter_chip_all"),
            )
        }
        items(FilterMediaType.entries) { type ->
            FilterChip(
                selected = type in filter.types,
                onClick = { onChange(filter.withTypeToggled(type)) },
                label = { Text(stringResource(type.labelRes)) },
                modifier = Modifier.testTag(
                    "photos_filter_chip_" + type.name.lowercase(),
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item {
                FilterChip(
                    selected = filter.favoritesOnly,
                    onClick = { onChange(filter.withFavoritesToggled()) },
                    label = { Text(stringResource(R.string.photos_filter_favorites)) },
                    modifier = Modifier.testTag("photos_filter_chip_favorites"),
                )
            }
        }
        if (filter.isActive) {
            item {
                // The explicit clear-all affordance: same effect as the All
                // chip, spelled as the action it is. A TextButton (not a
                // chip) reads as an action, not a category.
                TextButton(
                    onClick = { onChange(TimelineFilter()) },
                    modifier = Modifier.testTag("photos_filter_clear"),
                ) {
                    Text(stringResource(R.string.photos_filter_clear))
                }
            }
        }
    }
}

/** The localized label of one media-type chip (UI-layer resource mapping). */
private val FilterMediaType.labelRes: Int
    get() = when (this) {
        FilterMediaType.PHOTOS -> R.string.photos_filter_photos
        FilterMediaType.VIDEOS -> R.string.photos_filter_videos
        FilterMediaType.GIFS -> R.string.photos_filter_gifs
    }

/**
 * The localized label of one derived-collection id (UI-layer resource
 * mapping, shared by the Albums Collections section; null for an unknown
 * id so a future preset the mapping predates renders nothing rather than
 * a wrong label --- `DerivedCollectionsTest` pins today's id set, so
 * adding a preset forces this mapping along).
 */
internal fun collectionLabelRes(id: String): Int? = when (id) {
    DerivedCollections.ID_PHOTOS -> R.string.photos_filter_photos
    DerivedCollections.ID_VIDEOS -> R.string.photos_filter_videos
    DerivedCollections.ID_GIFS -> R.string.photos_filter_gifs
    DerivedCollections.ID_FAVORITES -> R.string.photos_filter_favorites
    else -> null
}
