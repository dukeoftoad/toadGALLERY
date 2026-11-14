package com.toadgallery.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// The history strip: one chip per COMMITTED undo-ring snapshot, labeled by the semantic
// change ("Exposure +35", never a field dump). The pure diff is [LilypadHistoryNames]'s;
// this file only resolves the structured Change into localizable strings. Tap-to-jump,
// deliberately NOT drag-to-scrub: a history walk is not a commit, so the chassis walks its
// own undo/redo (EditSessionEngine.jumpTo) --- the strip can never corrupt the trail.
// A11y: the heading anchors the section; each chip announces its label plus a
// CURRENT/UNDONE stateDescription.

/**
 * [changes]: one entry per ring snapshot in order (index 0 is null --- the launch state reads
 * "Original"); [currentIndex] the pointer in that list; [looks] the look-title registry
 * (unresolvable id degrades to the generic word); [onJump] moves the pointer to the tapped index.
 */
@Composable
internal fun HistoryStrip(
    changes: List<LilypadHistoryNames.Change?>,
    currentIndex: Int,
    looks: Map<String, CubeLut>,
    enabled: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Hairline top edge docking the strip to the canvas.
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.editor_r68_history_heading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            changes.forEachIndexed { index, change ->
                HistoryChip(
                    change = change,
                    isCurrent = index == currentIndex,
                    isFuture = index > currentIndex,
                    looks = looks,
                    enabled = enabled,
                    onJump = { onJump(index) },
                )
            }
        }
    }
}

/**
 * One history chip carrying the entry's resolved label pair. A11y: the visible texts announce
 * as the name; the stateDescription carries the current/undone distinction.
 */
@Composable
private fun HistoryChip(
    change: LilypadHistoryNames.Change?,
    isCurrent: Boolean,
    isFuture: Boolean,
    looks: Map<String, CubeLut>,
    enabled: Boolean,
    onJump: () -> Unit,
) {
    // The label pair resolved composable-side: the human word + the mono value,
    // both from string resources or the chassis's registries.
    val (nameResOrText, valueText) = resolveChipLabel(change, looks)
    val currentWord = stringResource(R.string.editor_r68_history_state_current)
    val undoneWord = stringResource(R.string.editor_r68_history_state_redo)
    Surface(
        onClick = onJump,
        enabled = enabled,
        shape = RoundedCornerShape(LilypadTokens.CHIP_CORNER_DP.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        // Hairline border; the current entry's fill needs no border (the fill IS the mark).
        border = if (isCurrent) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier
            // The redo tail renders dimmed (present, not the state on screen); past entries full strength.
            .alpha(if (isFuture) 0.55f else 1f)
            .semantics {
                stateDescription = if (isCurrent) currentWord else undoneWord
                selected = isCurrent
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = nameResOrText,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            if (valueText != null) {
                Text(
                    text = valueText,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = LilypadValueStyle,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Resolves one [change] into its (label, mono value) pair: sliders name themselves through
 * [ADJUST_SLIDERS]'s spec, the look through the parsed registry's title, families through
 * their strings. A null [change] is the launch entry ("Original", no value).
 */
@Composable
private fun resolveChipLabel(
    change: LilypadHistoryNames.Change?,
    looks: Map<String, CubeLut>,
): Pair<String, String?> = when (change) {
    null -> stringResource(R.string.editor_r68_history_original) to null
    is LilypadHistoryNames.Slider -> {
        val spec = ADJUST_SLIDERS.firstOrNull { it.key == change.key }
        val name = spec?.let { stringResource(it.a11yNameRes) }
            ?: stringResource(R.string.editor_r68_history_edit)
        name to LilypadSliderMath.signedValueText(change.value)
    }
    is LilypadHistoryNames.Straighten -> {
        stringResource(R.string.editor_a11y_straighten) to
            stringResource(R.string.editor_r68_history_deg, change.degrees)
    }
    is LilypadHistoryNames.Curve -> {
        stringResource(R.string.editor_r68_history_curve) to
            stringResource(R.string.editor_r68_history_curve_pts, change.points)
    }
    is LilypadHistoryNames.Look -> {
        val name = when {
            // A deselection reads as the None card's word.
            change.lutId.isEmpty() -> stringResource(R.string.editor_filter_none)
            // An unresolvable id (or pre-load) reads the generic word.
            looks.containsKey(change.lutId) -> looks.getValue(change.lutId).title
            else -> stringResource(R.string.editor_r68_history_look)
        }
        name to stringResource(R.string.editor_r67_percent_value, change.intensity)
    }
    is LilypadHistoryNames.Plain -> familyWord(change.family) to null
}

/** The single-word families' chip labels; new families land here, never at the call site. */
@Composable
private fun familyWord(family: LilypadHistoryNames.Family): String = when (family) {
    LilypadHistoryNames.Family.Crop -> stringResource(R.string.editor_r68_history_crop)
    LilypadHistoryNames.Family.Rotate -> stringResource(R.string.editor_r68_history_rotate)
    LilypadHistoryNames.Family.Flip -> stringResource(R.string.editor_r68_history_flip)
    LilypadHistoryNames.Family.Perspective -> stringResource(R.string.editor_r68_history_perspective)
    LilypadHistoryNames.Family.Color -> stringResource(R.string.editor_r68_history_color)
    LilypadHistoryNames.Family.Text -> stringResource(R.string.editor_r68_history_text)
    LilypadHistoryNames.Family.Stickers -> stringResource(R.string.editor_r68_history_stickers)
    LilypadHistoryNames.Family.Draw -> stringResource(R.string.editor_r68_history_draw)
    LilypadHistoryNames.Family.Regions -> stringResource(R.string.editor_r68_history_regions)
    LilypadHistoryNames.Family.Frame -> stringResource(R.string.editor_r68_history_frame)
    LilypadHistoryNames.Family.Sky -> stringResource(R.string.editor_r68_history_sky)
    LilypadHistoryNames.Family.Bokeh -> stringResource(R.string.editor_r68_history_bokeh)
    LilypadHistoryNames.Family.Retouch -> stringResource(R.string.editor_r68_history_retouch)
    LilypadHistoryNames.Family.Erase -> stringResource(R.string.editor_r68_history_erase)
    LilypadHistoryNames.Family.Edit -> stringResource(R.string.editor_r68_history_edit)
}
