package io.github.dukeoftoad.gallery.ui.photos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.stories.StorySet
import io.github.dukeoftoad.gallery.data.stories.StorySets
import java.util.Locale

/**
 * The Stories shelf of the Photos surface (r27, the MI-style
 * organisational UX round): a horizontal strip of the RECENT time
 * clusters the pure [StorySets] engine derives from the timeline's own
 * paging snapshot --- derived and zero-persistence, the r17
 * DerivedCollections doctrine (nothing is stored; the next derivation
 * recomputes from whatever the snapshot says then).
 *
 * ## Derivation source (the honest choice)
 * The shelf reads the SAME `LazyPagingItems` snapshot the grid below
 * renders --- one supply, one filter generation, no second query. The
 * stories therefore (a) always match what the user actually sees, and
 * (b) refine live as Paging appends windows (the `derivedStateOf`
 * recomputes on every snapshot change; a story's member count can only
 * grow with the data it is honestly derived from). The effective instant
 * is the r13 rule (`captureDateMillis ?: dateAddedSec * 1000`), the same
 * extractor `buildTimelineRows` uses.
 *
 * ## When the shelf renders
 * Only on the PLAIN timeline with NO active filter: Stories are
 * whole-library recent highlights, and a shelf of "stories of the
 * current chip filter" would be a different (and confusing) product ---
 * the filtered view stays chip+grid, exactly as before. The shelf is
 * absent (not empty-styled) until the first qualifying cluster exists,
 * so a fresh or sparse library sees no dead chrome.
 *
 * ## The tap: reuse, not a fork
 * A story card hands its members to the shell's viewer hand-off with the
 * slideshow ARMED (the shell's `onOpenStory`) --- the story player IS
 * the existing full-screen viewer (the `ui.viewer` package) in its r27
 * slideshow mode with the crossfade transitions; no viewer component is
 * forked, and playback order is the timeline's own (newest first),
 * consistent with every other viewer hand-off.
 *
 * Privacy (ADR-0014): every cover decodes from a local content URI via
 * the app's offline Coil loader; the derivation is pure on-device math.
 */
@Composable
fun StoriesShelf(
    lazyItems: LazyPagingItems<MediaItem>,
    onOpenStory: (window: List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The stories AND the materialized member items of the CURRENT
    // snapshot, re-derived reactively together (the snapshot list is
    // snapshot-state backed; appended windows and refreshes re-run the
    // derivation --- one pass, one list copy per change, the same
    // discipline as MediaPagingGrid's rows+counts derivation). Keyed on
    // the paging instance so a generation swap can never leave a stale
    // closure reading the old snapshot.
    val derived = remember(lazyItems) {
        derivedStateOf {
            val snapshot = lazyItems.itemSnapshotList.filterNotNull()
            val stories = StorySets.stories(
                snapshot,
                // The r13 effective-instant rule, the same extractor the
                // grouped timeline uses.
                { it.captureDateMillis ?: it.dateAddedSec * 1000 },
            )
            stories to snapshot
        }
    }.value
    val stories = derived.first
    val snapshotItems = derived.second
    if (stories.isEmpty()) return

    val locale: Locale = LocalConfiguration.current.locales[0]
    Column(modifier = modifier.testTag("stories_shelf")) {
        Text(
            text = stringResource(R.string.stories_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = stories,
                key = { _, story -> story.id },
            ) { _, story ->
                StoryCard(
                    story = story,
                    cover = snapshotItems.getOrNull(story.coverIndex),
                    locale = locale,
                    onClick = {
                        // The playback window: the story's members in the
                        // timeline's own order (see the file KDoc's
                        // reuse-not-fork section).
                        val window = story.memberIndexes.mapNotNull { index ->
                            snapshotItems.getOrNull(index)
                        }
                        if (window.isNotEmpty()) onOpenStory(window)
                    },
                )
            }
        }
    }
}

/**
 * One story card (r27): the cluster's NEWEST photo as the cover (the
 * same cover rule the Albums tab's folder cards use), the cluster's
 * date-range label, and the member count. A rounded 2:3 card --- the
 * shelf reads as a row of mini-albums, visually distinct from the square
 * grid cells below it.
 */
@Composable
private fun StoryCard(
    story: StorySet,
    cover: MediaItem?,
    locale: Locale,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .padding(bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(RoundedCornerShape(12.dp))
                .testTag("stories_card")
                .then(
                    if (cover != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                ),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover.uri,
                    contentDescription = stringResource(R.string.cd_story_cover),
                    contentScale = coil.compose.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = StorySets.rangeLabel(story, locale),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.stories_card_count,
                story.count,
                story.count,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
