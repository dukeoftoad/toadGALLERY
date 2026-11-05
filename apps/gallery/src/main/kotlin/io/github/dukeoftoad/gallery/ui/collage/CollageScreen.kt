package io.github.dukeoftoad.gallery.ui.collage

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.collage.CollageLayouts
import io.github.dukeoftoad.gallery.data.collage.CollageTemplate
import io.github.dukeoftoad.gallery.data.collage.PixelRect
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import io.github.dukeoftoad.gallery.ui.theme.ToadShapes

/**
 * The collage creator screen (r27, the MI-style organisational UX round):
 * the Photos tab's multi-selection (2..9 photos, handed over through
 * [io.github.dukeoftoad.gallery.data.collage.CollageHandoff]) laid out by
 * a chosen grid-variant template and exportable as one new image.
 *
 * ## Layout
 * Top bar (back, "Collage", Save) over a square PREVIEW canvas and the
 * template picker row. The preview is a Compose [Canvas] drawing each
 * decoded bitmap CENTER-CROPPED into its template slot through the SAME
 * pure math the exporter uses ([CollageLayouts.slotPixels] +
 * [CollageLayouts.centerCropSource]) --- what the preview shows is what
 * the export saves. A photo whose decode has not landed (or honestly
 * failed) renders a muted placeholder rect, and the Save action stays
 * disabled until every slot holds its bitmap (a collage with a gray box
 * is a broken export, not a feature).
 *
 * ## The picker row
 * One mini-canvas per [CollageLayouts.templatesFor] entry (slot outlines
 * drawn at picker size), the chosen template outlined in the theme's
 * primary color. Picking re-lays the preview only --- the photos
 * themselves are the frozen hand-off set.
 *
 * ## The export (the file-ops pipeline, reused)
 * Save runs the render + MediaStore insert through the r27 exporter
 * (`CollageExport.kt`), whose naming rides the pure
 * `FileOpNaming.copyDisplayName` rule ("Collage.jpg", "Collage (2).jpg",
 * …) and whose insert protocol is the Q+ `IS_PENDING` + `RELATIVE_PATH`
 * one `MediaFileOps` uses. Below Android 10 the app holds no write
 * protocol, so the action is hidden (the same `canModify` gate the
 * viewer's rename/delete use). While a run is in flight the action is
 * disabled and a spinner leads the bar; the outcome narrates through a
 * snackbar.
 *
 * ## Honest states
 * [CollageUiState.Missing] (no hand-off payload: process death, a stale
 * route) renders the empty state with a back action --- never a fake
 * collage of stale photos. A failed export re-enables Save (retry is
 * the honest recovery) after its snackbar.
 *
 * Privacy (ADR-0006/ADR-0014): every decode is a local content URI via
 * the app's offline Coil loader; the export writes one new local image
 * into the standard Pictures directory. No network path exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(
    onBack: () -> Unit,
    viewModel: CollageViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val bitmaps by viewModel.bitmaps.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // One-shot narration (the FileOpsEvent shape: ids only; strings
    // resolve against the current configuration at display time).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CollageEvent.ShowSnackbar -> snackbarHostState.showSnackbar(
                    message = context.getString(event.messageRes),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.collage_title),
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
                actions = {
                    val ready = state as? CollageUiState.Ready
                    if (ready != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Disabled while a run is in flight or while any
                        // bitmap is still missing (the KDoc's honest gates).
                        val allDecoded =
                            bitmaps.size == ready.photos.size && bitmaps.all { it != null }
                        if (ready.exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 20.dp)
                                    .size(22.dp),
                            )
                        } else {
                            TextButton(
                                onClick = viewModel::export,
                                enabled = allDecoded,
                                modifier = Modifier.testTag("collage_action_save"),
                            ) {
                                Text(stringResource(R.string.collage_action_save))
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                is CollageUiState.Loading -> LoadingState(tag = "collage_state_loading")

                is CollageUiState.Missing -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("collage_state_empty"),
                ) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_photos,
                        titleRes = R.string.collage_empty_title,
                        bodyRes = R.string.collage_empty_body,
                        actionRes = R.string.collage_empty_action,
                        onAction = onBack,
                    )
                }

                is CollageUiState.Ready -> {
                    val templates = CollageLayouts.templatesFor(s.photos.size)
                    val template = templates.firstOrNull { it.id == s.templateId }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("collage_state_ready"),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (template != null) {
                                CollagePreview(
                                    template = template,
                                    bitmaps = bitmaps,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .testTag("collage_preview"),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.collage_picker_label),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.testTag("collage_picker"),
                        ) {
                            items(
                                items = templates,
                                key = { it.id },
                            ) { candidate ->
                                TemplateCard(
                                    template = candidate,
                                    selected = candidate.id == s.templateId,
                                    onClick = { viewModel.selectTemplate(candidate.id) },
                                )
                            }
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * The preview canvas (r27): the whole template at once, each bitmap
 * center-cropped into its slot by the pure math. Drawn on ONE Canvas so
 * the export and the preview share the exact same rect pipeline (only
 * the draw target differs: this scope vs. the exporter's
 * `android.graphics.Canvas`).
 */
@Composable
private fun CollagePreview(
    template: CollageTemplate,
    bitmaps: List<Bitmap?>,
    modifier: Modifier = Modifier,
) {
    // ImageBitmap conversions cached per bitmap identity (asImageBitmap
    // wraps, but the wrap is still per-call allocation).
    val imageBitmaps = remember(bitmaps) { bitmaps.map { it?.asImageBitmap() } }
    // r47 ux fix: the pending-slot plate reads the theme's tonal surface
    // instead of a hardcoded light-gray literal --- the literal (0xFFDDDDE2)
    // flashed bright against the dark scheme's near-black canvas. Read
    // outside the draw scope: Color is a composable-context lookup.
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val edge = size.width.coerceAtMost(size.height)
        val canvasTopLeft = Offset((size.width - edge) / 2f, (size.height - edge) / 2f)
        drawRect(
            color = Color.White,
            topLeft = canvasTopLeft,
            size = Size(edge, edge),
        )
        template.slots.forEachIndexed { index, slot ->
            val destination = CollageLayouts.slotPixels(slot, edge.toInt(), edge.toInt())
            // The template's rect, translated onto the letterboxed square
            // (canvasTopLeft is the square's offset inside this scope).
            val destinationLeft = (canvasTopLeft.x + destination.left).toInt()
            val destinationTop = (canvasTopLeft.y + destination.top).toInt()
            val topLeft = Offset(destinationLeft.toFloat(), destinationTop.toFloat())
            val destinationSize = Size(destination.width.toFloat(), destination.height.toFloat())
            val image = imageBitmaps.getOrNull(index)
            val source: PixelRect? = image?.let {
                CollageLayouts.centerCropSource(
                    bitmapWidth = it.width,
                    bitmapHeight = it.height,
                    destinationWidth = destination.width,
                    destinationHeight = destination.height,
                )
            }
            if (image != null && source != null) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset(source.left, source.top),
                    srcSize = IntSize(source.width, source.height),
                    dstOffset = IntOffset(destinationLeft, destinationTop),
                    dstSize = IntSize(destination.width, destination.height),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                // Honest placeholder: the slot's decode has not landed (or
                // failed); the Save action stays disabled meanwhile.
                drawRect(
                    color = placeholder,
                    topLeft = topLeft,
                    size = destinationSize,
                )
            }
        }
    }
}

/**
 * One picker card (r27): the template's slot OUTLINES at picker size,
 * the selected template outlined in the primary color. The spoken label
 * carries the photo count (the visual is the differentiator; the count
 * is the shared fact a TalkBack user needs).
 */
@Composable
private fun TemplateCard(
    template: CollageTemplate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val selection = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.cd_collage_template, template.slotCount)
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(ToadShapes.small))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = selection,
                        shape = RoundedCornerShape(ToadShapes.small),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("collage_template_card")
            .padding(6.dp),
    ) {
        val edge = size.width.coerceAtMost(size.height)
        template.slots.forEach { slot ->
            val rect = CollageLayouts.slotPixels(slot, edge.toInt(), edge.toInt())
            drawRect(
                color = if (selected) selection else outline,
                topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
                size = Size(rect.width.toFloat(), rect.height.toFloat()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
