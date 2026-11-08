package io.github.dukeoftoad.gallery.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** The photo editor's screens; Main is the chassis, the rest are the ten tabs. */
internal enum class PhotoScreen(val labelRes: Int, val iconRes: Int) {
    /** The main chassis; never a tab (the tab bar shows the ten tools only). */
    Main(R.string.editor_app_name, 0),
    Adjust(R.string.editor_section_adjust, R.drawable.ic_vt_adjust),
    Color(R.string.editor_pt_tab_color, R.drawable.ic_pt_color),
    Looks(R.string.editor_section_filters, R.drawable.ic_pt_looks),
    Frames(R.string.editor_section_frames, R.drawable.ic_pt_frames),
    Text(R.string.editor_section_text, R.drawable.ic_pt_text),
    Stickers(R.string.editor_section_stickers, R.drawable.ic_pt_sticker),
    Draw(R.string.editor_section_drawing, R.drawable.ic_pt_draw),
    Regions(R.string.editor_section_regions, R.drawable.ic_pt_region),
    Ai(R.string.editor_pt_tab_ai, R.drawable.ic_pt_ai),
    Crop(R.string.editor_section_geometry, R.drawable.ic_pt_crop),
}

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
 * The bottom tab bar (r54 chassis, r56 craft pass): the ten tools,
 * horizontally scrollable, icon over label --- with the r56 additions: a
 * hairline top edge (the bar reads as a DOCKED surface instead of floating
 * text), a taller touch column (the 48dp floor plus the underline), and a
 * soft accent pill behind the selected icon (the r53 theme's selected-chip
 * language, carried into the bar). Active = the accent + the underline,
 * inactive = the secondary tint --- unchanged.
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            for (tab in PhotoScreen.entries) {
                if (tab == PhotoScreen.Main) continue
                val selected = tab == current
                val tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .clickable(enabled = enabled) { onSelect(tab) }
                        .padding(top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The r56 selected pill: the accent language behind the
                    // icon, so the active tab reads at a glance even before
                    // the underline lands.
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
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
                        )
                    }
                    Text(
                        text = stringResource(tab.labelRes),
                        color = tint,
                        fontSize = 11.sp,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(24.dp)
                            .height(2.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            ),
                    )
                }
            }
        }
    }
}
