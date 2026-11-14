package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The photo editor's navigation shell, composable standalone for the screenshot harness.
// Main is never a tab; the header check is the chassis signature (tools commit live).

/**
 * The photo editor's screens; Main is the chassis, the rest are the ten tabs, in the
 * approved groups: tone (Adjust · Looks · Color), canvas (Crop · AI · Text · Stickers ·
 * Draw · Regions), geometry (Frames). Only the tab bar reads declaration order; the
 * panel dispatch `when` is by value.
 */
internal enum class PhotoScreen(val labelRes: Int, val iconRes: Int) {
    /** The main chassis; never a tab (the tab bar shows the ten tools only). */
    Main(R.string.editor_app_name, 0),
    Adjust(R.string.editor_section_adjust, R.drawable.ic_vt_adjust),
    Looks(R.string.editor_section_filters, R.drawable.ic_pt_looks),
    Color(R.string.editor_pt_tab_color, R.drawable.ic_pt_color),
    Crop(R.string.editor_section_geometry, R.drawable.ic_pt_crop),
    Ai(R.string.editor_pt_tab_ai, R.drawable.ic_pt_ai),
    Text(R.string.editor_section_text, R.drawable.ic_pt_text),
    Stickers(R.string.editor_section_stickers, R.drawable.ic_pt_sticker),
    Draw(R.string.editor_section_drawing, R.drawable.ic_pt_draw),
    Regions(R.string.editor_section_regions, R.drawable.ic_pt_region),
    Frames(R.string.editor_section_frames, R.drawable.ic_pt_frames),
}

/**
 * The tab groups the bar renders (not the raw enum): hairline separators after
 * Color (tone ‖ canvas) and after Regions (canvas ‖ geometry).
 */
internal val PhotoTabGroups: List<List<PhotoScreen>> = listOf(
    listOf(PhotoScreen.Adjust, PhotoScreen.Looks, PhotoScreen.Color),
    listOf(
        PhotoScreen.Crop,
        PhotoScreen.Ai,
        PhotoScreen.Text,
        PhotoScreen.Stickers,
        PhotoScreen.Draw,
        PhotoScreen.Regions,
    ),
    listOf(PhotoScreen.Frames),
)

/**
 * The tool-screen header: back, title (the heading anchor), session Reset, then the
 * confirm check --- one shape for all ten tools. Reset reverts the tool session as ONE
 * undoable commit against the entry snapshot, disabled while that snapshot IS the
 * recipe (or during an export); the check is the chassis signature, not a fake apply.
 */
@Composable
internal fun PhotoToolHeader(
    title: String,
    backA11y: String,
    confirmA11y: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    resetA11y: String,
    onReset: () -> Unit,
    resetEnabled: Boolean,
    // Copy-paste edits pair, optional for source compatibility; the Adjust tool carries it.
    copyA11y: String? = null,
    onCopy: () -> Unit = {},
    copyEnabled: Boolean = true,
    pasteA11y: String? = null,
    onPaste: () -> Unit = {},
    pasteEnabled: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backA11y },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        // Session Reset in the header: disabled tint is the 38% disabled-alpha convention.
        IconButton(
            onClick = onReset,
            enabled = resetEnabled,
            modifier = Modifier.semantics { contentDescription = resetA11y },
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = if (resetEnabled) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                },
            )
        }
        // Copy lifts this session's grade into the chassis clipboard; Paste lands it as
        // ONE undoable commit via [EditTransfer.pasteGrade] (geometry/scene layers stay own).
        if (copyA11y != null) {
            IconButton(
                onClick = onCopy,
                enabled = copyEnabled,
                modifier = Modifier.semantics { contentDescription = copyA11y },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy_edits),
                    contentDescription = null,
                    tint = if (copyEnabled) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                    },
                )
            }
        }
        if (pasteA11y != null) {
            IconButton(
                onClick = onPaste,
                enabled = pasteEnabled,
                modifier = Modifier.semantics { contentDescription = pasteA11y },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_paste_edits),
                    contentDescription = null,
                    tint = if (pasteEnabled) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                    },
                )
            }
        }
        IconButton(
            onClick = onConfirm,
            modifier = Modifier.semantics { contentDescription = confirmA11y },
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The bottom tab bar: ten tools, horizontally scrollable, icon over micro-label,
 * in the grouped order ([PhotoTabGroups]):
 *
 * - 68dp touch rows, 56dp-minimum tab widths, 22dp glyphs over `labelSmall` labels;
 * - selected tab: glyph inside a 30dp accent-pill halo (14% alpha);
 * - 1dp outlineVariant group separators after Color and after Regions;
 * - auto-scroll centers the selected tab on selection or first position;
 * - 26dp fade padders: a surface-gradient fade overlays only edges with clipped content.
 */
@Composable
internal fun PhotoTabBar(
    current: PhotoScreen,
    enabled: Boolean,
    onSelect: (PhotoScreen) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // The hairline: the bar is docked, not floating.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        val scrollState = rememberScrollState()
        var viewportWidth by remember { mutableStateOf(0) }
        // Each tab's horizontal center in scroll-content coords, post-layout (auto-scroll reads it).
        val tabCenters = remember { mutableStateMapOf<PhotoScreen, Int>() }
        // Auto-scroll: bring the selected tab to the viewport's horizontal center; re-runs
        // on selection change or first position, keeping tools centered during navigation.
        LaunchedEffect(current, tabCenters[current]) {
            val center = tabCenters[current] ?: return@LaunchedEffect
            scrollState.animateScrollTo((center - viewportWidth / 2).coerceAtLeast(0))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .onSizeChanged { viewportWidth = it.width },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
                    // 26dp end padding: tabs scroll clear of both edges (fades overlay this inset).
                    .padding(horizontal = 26.dp),
            ) {
                PhotoTabGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        // Group separator: 1dp outlineVariant hairline between groups.
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .width(1.dp)
                                .height(28.dp)
                                .align(Alignment.CenterVertically)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    for (tab in group) {
                        val selected = tab == current
                        val isCurrent = selected
                        val tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier
                                .widthIn(min = 56.dp)
                                .fillMaxHeight()
                                .clickable(enabled = enabled, role = Role.Tab) { onSelect(tab) }
                                .semantics { this.selected = isCurrent }
                                .onGloballyPositioned { coords ->
                                    tabCenters[tab] = (coords.positionInParent().x +
                                        coords.size.width / 2f).roundToInt()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Selected halo: 30dp accent pill (14% alpha) behind the glyph.
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = stringResource(tab.labelRes),
                                color = tint,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            // Edge fades paint only over content actually clipped (hidden at the scroll
            // bounds), never a fake gradient over reachable tabs.
            val fadeColor = MaterialTheme.colorScheme.surface
            if (scrollState.value > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(26.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(fadeColor, Color.Transparent),
                            ),
                        ),
                )
            }
            if (scrollState.value < scrollState.maxValue) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(26.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, fadeColor),
                            ),
                        ),
                )
            }
        }
    }
}
