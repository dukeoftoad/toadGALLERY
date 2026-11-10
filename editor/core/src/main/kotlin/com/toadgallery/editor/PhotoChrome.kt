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

// ---------------------------------------------------------------------------
// The r56 chrome --- the photo editor's navigation shell, extracted verbatim
// from `EditActivity` (the r54 chassis) with the bar's craft pass applied.
//
// The move itself is the point: `EditActivity` had grown into a 4,900-line
// single file whose UI shell (this enum, the tool header, the tab bar) was
// unrenderable in isolation --- which is exactly why the edit app had no
// screenshot harness while the gallery had one since r47. The chrome now
// composes standalone (see `EditChassisScreenshotTest`), and the r54
// contracts it carries are unchanged:
//
// - the Main screen is never a tab (the bar navigates FROM the chassis);
// - the header's check is the chassis's signature, not a fake apply (the
//   photo tools commit LIVE on release --- nothing to apply, nothing to
//   discard);
// - the title is the a11y heading anchor (the r40 convention).
// ---------------------------------------------------------------------------

/**
 * The photo editor's screens; Main is the chassis, the rest are the ten tabs.
 *
 * r63b re-orders the tabs into the approved §4 groups --- tone, canvas,
 * geometry --- while keeping every tool and every string verbatim:
 *
 * - tone: Adjust · Looks · Color
 * - canvas: Crop · AI · Text · Stickers · Draw · Regions
 * - geometry: Frames
 *
 * (The old interleaved r54 order --- Adjust · Color · Looks · Frames ·
 * Text · Stickers · Draw · Regions · AI · Crop --- scattered the tonal
 * tools and buried AI between geometry and layers. Nothing reads the enum
 * declaration order except the tab bar; the panel dispatch `when` is by
 * value. F-73's Regions-into-Draw merge candidate stays OPEN: this round
 * ships the approved order only, no tool merge.)
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
 * The §4 tab groups: the ten tools in their approved runs, separated by
 * hairline group separators after Color (tone ‖ canvas) and after Regions
 * (canvas ‖ geometry). The bar renders THIS model, not the raw enum, so a
 * future re-group is a one-list edit.
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
 * The r54 tool-screen header, r63c-promoted: back on the left, the tool's
 * title (the heading anchor), then the session RESET icon, then the confirm
 * check on the right --- ONE consistent shape for all ten photo tools (the
 * approved section 4 spec row: back 48dp, title weight(1f), Reset icon
 * disabled when committed == entry, confirm check accent 48dp).
 *
 * The Reset promotion (r63c): the value tools' trailing TextButton footer
 * row retired --- Reset moved INTO the header row and now rides EVERY tool
 * screen, not just the five recipe-value tools. Same honest semantics: the
 * photo tools commit LIVE on release, so there is no draft to apply or
 * discard --- Reset reverts the tool session as ONE undoable commit against
 * the entry snapshot, disabled while that snapshot IS the recipe (or during
 * an export, the freeze-everything rule). The a11y name is the old footer
 * button's label (editor_v2_reset), carried as the icon's
 * contentDescription; the back/confirm contracts are unchanged (the check
 * is the chassis's signature, not a fake apply).
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
        // r63c: the session Reset, promoted out of the value tools' footer
        // row into the header --- one chrome shape for all ten tools. The
        // disabled tint is the section 4 spec's disabled-alpha convention
        // (38%), the same language the main header's undo/redo pair uses.
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
 * The bottom tab bar (r54 chassis, r56 craft pass, r63b §4 rebuild): the
 * ten tools, horizontally scrollable, icon over micro-label --- in the §4
 * grouped order ([PhotoTabGroups]) with
 *
 * - 68dp touch rows and 56dp-minimum tab widths;
 * - 22dp glyphs over readable Material `labelSmall` labels;
 * - the selected tab = the accent glyph inside a 30dp accent-pill halo
 *   (14% alpha) --- the §4 selected language, superseding the r56
 *   40x28dp pill + underline;
 * - 1dp outlineVariant group separators after Color and after Regions;
 * - auto-scroll: when a selected tab lands outside the viewport (or the
 *   selection changes), it scrolls to the viewport's horizontal center;
 * - 26dp fade padders: the content keeps 26dp end insets so no tab can
 *   butt the chrome edge, and a 26dp surface-gradient fade overlays each
 *   edge that actually has clipped content (none at the scroll bounds).
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
        // The r56 hairline: the bar is docked, not floating.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        val scrollState = rememberScrollState()
        var viewportWidth by remember { mutableStateOf(0) }
        // Each tab's horizontal CENTER in the scroll content coordinates,
        // reported post-layout (keyed by tab; the auto-scroll reads it).
        val tabCenters = remember { mutableStateMapOf<PhotoScreen, Int>() }
        // The §4 auto-scroll: bring the SELECTED tab to the viewport's
        // horizontal center. Re-runs when the selection changes OR when the
        // selected tab's first position arrives; when a tool is active it
        // stays centered so direct tool-to-tool navigation remains visible.
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
                    // The 26dp end padders: tabs scroll clear of both edges
                    // (the fade overlays sit over this inset).
                    .padding(horizontal = 26.dp),
            ) {
                PhotoTabGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        // The §4 group separator: a 1dp outlineVariant
                        // hairline after Color and after Regions.
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
                            // The §4 selected halo: a 30dp accent pill (14%
                            // alpha) behind the glyph --- the active tab
                            // reads at a glance even before the label does.
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
            // The §4 26dp fade padders: each edge fades ONLY the content
            // actually clipped under it (hidden at the scroll bounds), so
            // the padder never paints a fake gradient over reachable tabs.
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
