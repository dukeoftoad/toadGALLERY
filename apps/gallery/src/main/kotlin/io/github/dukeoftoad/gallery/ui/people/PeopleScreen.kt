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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
 * The People screen (r25, Workstream 6): the full browse surface over the
 * live face clusters, reached from the Albums tab's People section
 * ("See all").
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
 *    user hid every cluster);
 *  - Ready(visible) -> the person grid tagged `people_grid`, with the
 *    corrupt-file note rendered above it when the persisted names were
 *    reset ([PeopleUiState.corruptNote]);
 *  - Error(message) -> [PeopleStateMessage] with the failure's own text
 *    and a retry action, tagged `people_state_error`.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
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
                    PeopleStateMessage(
                        titleRes = R.string.people_state_not_indexed_title,
                        bodyRes = R.string.people_state_not_indexed_body,
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
                    if (content.visible.isEmpty()) {
                        PeopleStateMessage(
                            titleRes = R.string.people_state_empty_title,
                            bodyRes = R.string.people_state_empty_body,
                            tag = "people_state_empty",
                        )
                    } else {
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
