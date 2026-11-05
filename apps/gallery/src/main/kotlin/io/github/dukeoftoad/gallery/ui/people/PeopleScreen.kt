package io.github.dukeoftoad.gallery.ui.people

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ml.people.PeopleContentState
import io.github.dukeoftoad.gallery.ml.people.VisiblePerson
import io.github.dukeoftoad.gallery.ui.media.LoadingState

/**
 * The People screen (r25, Workstream 6; the r26 follow-ups round adds
 * the hidden section): the full browse surface over the live face
 * clusters, reached from the Albums tab's People section ("See all").
 *
 * A sub-screen without the bottom navigation bar (hidden by the shell
 * for non-top-level routes): top app bar with the title + back arrow.
 * Every content state of the PURE [io.github.dukeoftoad.gallery.ml.people
 * .PeopleContentPolicy] renders fully --- no dead ends:
 *  - Loading -> [LoadingState] tagged `people_state_loading`;
 *  - ConsentOff -> [PeopleStateMessage] with an "Open Settings" action
 *    (the `peopleAlbumEnabled` toggle lives in the Settings AI section,
 *    ADR-0010's opt-in row) tagged `people_state_consent`;
 *  - EngineMissing -> the honest build-shape state tagged
 *    `people_state_engine_missing`;
 *  - NotIndexed -> the honest "people appear after the background index
 *    runs" state (including the queue still draining) tagged
 *    `people_state_not_indexed`;
 *  - Ready(empty) -> the honest "everyone is hidden" state tagged
 *    `people_state_empty` (distinct from NotIndexed: the index RAN, the
 *    user hid every cluster) --- with the r26 unhide section right
 *    below it, the way back that no longer costs an index reset;
 *  - Ready(visible) -> the person grid tagged `people_grid`, with the
 *    corrupt-file note rendered above it when the persisted names were
 *    reset ([PeopleUiState.corruptNote]);
 *  - Error(message) -> [PeopleStateMessage] with the failure's own text
 *    and a retry action, tagged `people_state_error`.
 *
 * Every Ready state renders the r26 HIDDEN section
 * ([HiddenPeopleSection], tagged `people_hidden_section`) when any
 * hidden person exists: one row of compact cards, each with its own
 * Unhide action ([PeopleViewModel.unhide] --- names and other edits
 * stay; the r25 way back, the index-resetting r24 "Reset face index",
 * is no longer the only one). An unhide failure surfaces as the honest
 * one-shot snackbar, never a silent no-op.
 *
 * Tapping a person card opens the `PERSON_DETAIL` route. Person cards
 * ([PersonCard]) are shared with the Albums tab's People section so the
 * two surfaces render identical chrome; the cover is the cluster's own
 * cover file thumbnail (a CIRCULAR avatar), with an honest glyph avatar
 * when the engine could not pick a cover --- never a fake thumbnail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PeopleViewModel = viewModel(),
) {
    val uiState by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PeopleEvent.ShowSnackbar -> {
                    val message = if (event.pluralCount >= 0) {
                        context.resources.getQuantityString(
                            event.messageRes, event.pluralCount, event.pluralCount,
                        )
                    } else if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.people_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val content = uiState.content) {
                is PeopleContentState.Loading ->
                    LoadingState(tag = "people_state_loading")

                is PeopleContentState.ConsentOff ->
                    PeopleStateMessage(
                        titleRes = R.string.people_state_consent_title,
                        bodyRes = R.string.people_state_consent_body,
                        actionRes = R.string.people_state_consent_action,
                        onAction = onOpenSettings,
                        tag = "people_state_consent",
                    )

                is PeopleContentState.EngineMissing ->
                    PeopleStateMessage(
                        titleRes = R.string.people_state_engine_title,
                        bodyRes = R.string.people_state_engine_body,
                        tag = "people_state_engine_missing",
                    )

                is PeopleContentState.NotIndexed ->
                    // r50 ux fix: the NotIndexed card was the ONLY people state
                    // without an action --- the prose pointed at Settings and
                    // the user walked there by hand. The ConsentOff card's own
                    // "open Settings" affordance, same destination.
                    PeopleStateMessage(
                        titleRes = R.string.people_state_not_indexed_title,
                        bodyRes = R.string.people_state_not_indexed_body,
                        actionRes = R.string.state_action_open_settings,
                        onAction = onOpenSettings,
                        tag = "people_state_not_indexed",
                    )

                is PeopleContentState.Error ->
                    PeopleStateMessage(
                        titleRes = R.string.people_state_error_title,
                        // The failure's own text, surfaced verbatim (never
                        // paraphrased away) --- the honest-states doctrine.
                        body = content.message,
                        actionRes = R.string.state_action_retry,
                        onAction = viewModel::refresh,
                        tag = "people_state_error",
                    )

                is PeopleContentState.Ready ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (content.visible.isEmpty()) {
                            // Everyone is hidden: the honest empty grid PLUS
                            // the unhide way back right below it (r26 --- the
                            // message points at the section, not at the
                            // index-erasing r24 Reset).
                            Box(modifier = Modifier.weight(1f)) {
                                PeopleStateMessage(
                                    titleRes = R.string.people_state_empty_title,
                                    bodyRes = R.string.people_state_empty_body,
                                    tag = "people_state_empty",
                                )
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (uiState.corruptNote) {
                                        Text(
                                            text = stringResource(R.string.people_corrupt_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                    }
                                    PeopleGrid(
                                        visible = content.visible,
                                        onOpenPerson = onOpenPerson,
                                    )
                                }
                            }
                        }
                        HiddenPeopleSection(
                            hidden = uiState.hidden,
                            onUnhide = viewModel::unhide,
                        )
                    }
            }
        }
    }
}

/**
 * The person grid (r25): three columns of [PersonCard]s, the seam's
 * face-count-descending order preserved (the policy never re-sorts).
 * Keys on the cluster id (identity for recomposition; a re-cluster
 * changes ids by contract, which correctly re-keys the cards).
 */
@Composable
private fun PeopleGrid(
    visible: List<VisiblePerson>,
    onOpenPerson: (clusterId: String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .testTag("people_grid"),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = visible,
            key = { it.clusterId },
        ) { person ->
            PersonCard(
                person = person,
                onClick = { onOpenPerson(person.clusterId) },
            )
        }
    }
}

/**
 * The r26 hidden section: every hidden person in one horizontal row of
 * compact [HiddenPersonCard]s, each carrying its own Unhide action.
 * Rendered only when at least one hidden person exists --- absence IS
 * the honest empty state (a permanent "no hidden people" line would be
 * noise, not honesty). The order is the seam's own
 * face-count-descending one ([PeopleContentPolicy.hiddenPersons] ---
 * mapping, not sorter), keys on the cluster id like the main grid.
 */
@Composable
private fun HiddenPeopleSection(
    hidden: List<VisiblePerson>,
    onUnhide: (clusterId: String) -> Unit,
) {
    if (hidden.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("people_hidden_section"),
    ) {
        Text(
            text = stringResource(R.string.people_hidden_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.people_hidden_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
                .testTag("people_hidden_row"),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Count-based items: the GRID items extension is imported for
            // the main grid and the LazyList list-overload shares its name
            // (the same trick the Albums tab's People row uses).
            items(
                count = hidden.size,
                key = { index -> hidden[index].clusterId },
            ) { index ->
                val person = hidden[index]
                HiddenPersonCard(
                    person = person,
                    onUnhide = { onUnhide(person.clusterId) },
                )
            }
        }
    }
}

/**
 * One hidden person (r26): the same circular cover avatar as
 * [PersonCard] (the honest glyph when no cover exists) with the name
 * or "Unnamed" and the live face count --- but NOT tappable (the
 * person detail is a visible-person surface) and carrying the Unhide
 * action instead: one tap, [PeopleViewModel.unhide], no index reset,
 * names and every other edit intact.
 */
@Composable
private fun HiddenPersonCard(
    person: VisiblePerson,
    onUnhide: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(width = 88.dp, height = 148.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HiddenPersonAvatar(person = person)
        Text(
            text = person.name ?: stringResource(R.string.people_unnamed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.people_face_count,
                person.faceCount,
                person.faceCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onUnhide,
            modifier = Modifier.testTag("people_unhide_${person.clusterId}"),
        ) {
            Text(stringResource(R.string.people_unhide))
        }
    }
}

/** The hidden card's avatar: the shared cover-or-glyph circle ([PersonAvatar]'s exact shape). */
@Composable
private fun HiddenPersonAvatar(person: VisiblePerson) {
    val coverUri = personCoverUri(person.coverFileId)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null, // decorative; the card's name carries meaning
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null, // decorative; the card's name carries meaning
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * One person card (r25, Workstream 6) --- shared by this screen's grid
 * and the Albums tab's People section so both surfaces render identical
 * chrome: a CIRCULAR cover avatar (the cluster's cover file thumbnail,
 * [personCoverUri]), the user's name or the honest "Unnamed" when no
 * name is stored, and the live face count (plurals).
 *
 * When the engine could not pick a cover ([VisiblePerson.coverFileId]
 * null), the avatar is a tonal circle with the core-set `Person` glyph
 * --- an honest "no cover" avatar, not a fake thumbnail and not
 * placeholder junk. The thumbnail decodes from a local content URI
 * only (ADR-0006/ADR-0014: no INTERNET permission, no network surface).
 */
@Composable
fun PersonCard(
    person: VisiblePerson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonAvatar(person = person)
        Text(
            text = person.name ?: stringResource(R.string.people_unnamed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.people_face_count,
                person.faceCount,
                person.faceCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The circular cover avatar of a [PersonCard] (r25): the cluster's cover
 * thumbnail when one exists, else the honest tonal glyph avatar.
 */
@Composable
private fun PersonAvatar(person: VisiblePerson) {
    val coverUri = personCoverUri(person.coverFileId)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null, // decorative; the card's name carries meaning
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null, // decorative; the card's name carries meaning
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Maps a cluster's cover file id to its thumbnail content URI (r25):
 * `ContentUris.withAppendedId(MediaStore.Images.Media
 * .EXTERNAL_CONTENT_URI, id)` --- the ADR-0006 URI-first construction
 * every other cover in the app uses, over the external-primary volume
 * the r23/r24 pipeline indexes. Null stays null (the glyph avatar).
 */
fun personCoverUri(coverFileId: Long?): Uri? =
    coverFileId?.let {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
    }

/**
 * The People screens' shared state message (r25): the honest layout for
 * every non-grid state --- centered `headlineSmall` title, `bodyMedium`
 * body (a resource or the failure's own text), optional `TextButton`
 * action. Internal: [PeopleScreen] and [PersonDetailScreen] render
 * their non-grid states through it so both stay token-identical.
 *
 * Deliberately NOT `StateScreen`: that frozen component requires a
 * `@DrawableRes` icon and the app's drawable set has no people glyph,
 * while this round follows the r19a precedent (see `MediaThumb`'s error
 * plate) of inventing no binary or vector resources --- the copy alone
 * carries these states, and the tokens stay the theme's own.
 */
@Composable
internal fun PeopleStateMessage(
    titleRes: Int,
    bodyRes: Int? = null,
    body: String? = null,
    actionRes: Int? = null,
    onAction: (() -> Unit)? = null,
    tag: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        val bodyText = when {
            body != null -> body
            bodyRes != null -> stringResource(bodyRes)
            else -> null
        }
        if (bodyText != null) {
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        if (actionRes != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(actionRes))
            }
        }
    }
}
