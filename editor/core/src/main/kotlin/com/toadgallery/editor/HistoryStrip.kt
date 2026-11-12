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

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the HISTORY STRIP, the scrubber the
// design doc's third promise asks for ("everything live, everything
// undoable ... history you can scrub").
//
// THE SHAPE. A bottom-anchored horizontal strip, one chip per COMMITTED
// snapshot in the undo ring, labeled by what that commit DID --- the
// semantic change, not the field dump ("Exposure +35", never
// "exposure: 0 -> 35"). The pure diff is [LilypadHistoryNames]'s (this
// phase's engine commit, JVM-pinned); THIS file only resolves the
// structured Change into strings --- composable-side, the ADR-0011 split,
// so the naming layer stays localizable and the strip stays dumb.
//
// THE CHIPS. Pad-raised plates with a hairline border, 8dp corners
// (LilypadTokens' chip radius); the label reads in reed, the mono value in
// the value style. The CURRENT entry is the ember-filled pill (the r52
// selection mechanism, the ProSlider live-pill language): one accent, and
// it spends itself on where you are. Entries in the REDO TAIL (undone,
// still reachable forward) render at a dimmed alpha --- present, honest
// about being the future, one tap from being real again.
//
// THE JUMP. Tap-to-jump, deliberately NOT drag-to-scrub this round: the
// draft machinery's contract is "drag = zero-ms draft, release = ONE
// commit", and a history walk is not a commit --- each intermediate
// position would need its own ring move, and pretending a scrub is a
// draft would smuggle per-tick ring mutations under a contract written
// for recipe edits. The honest v1 is the discrete jump: the chassis walks
// its OWN undo/redo repeatedly (EditSessionEngine.jumpTo --- the ring's
// only sanctioned moves, no new pointer path), which is why the strip can
// never corrupt the trail it renders.
//
// A11Y: the strip's heading anchors the section (the r40 convention);
// each chip is a button whose visible label announces itself, with a
// stateDescription that says CURRENT or UNDONE --- the r32 name + state
// pair, applied to navigation.
// ---------------------------------------------------------------------------

/**
 * The r68 history strip (see the file header): [changes] is one entry per
 * ring snapshot in order (index 0 is null --- the launch state has no
 * predecessor and reads "Original"), [currentIndex] the pointer's position
 * in that list, [looks] the parsed look registry the Look chips resolve
 * their titles through (empty before the IO load --- an unresolvable id
 * degrades to the generic word, never a crash), and [onJump] asks the
 * chassis to move the pointer to the tapped index.
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
            // The docked-strip language: chips floating on the pond the
            // canvas leaves behind (the photo stays the hero; the strip
            // claims no sheet territory of its own), under a hairline top
            // edge that docks it to the canvas the way the tab bar's rule
            // docks the bar to the deck.
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
 * One history chip: the pad-raised plate (or the ember pill, when
 * [isCurrent]) carrying the entry's resolved label pair. The a11y pair is
 * the r32 one --- the visible texts announce as the name, the
 * stateDescription carries the current/undone distinction a sighted user
 * reads from the fill and the alpha.
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
    // The label pair, resolved composable-side (the naming layer's
    // contract): the human word + the mono value, both from string
    // resources or the runtime registries the chassis owns.
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
        // The hairline border the pad-raised chips wear (the current
        // entry's ember fill needs no border --- the fill IS the mark).
        border = if (isCurrent) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier
            // The redo tail reads dimmed: present and reachable, honestly
            // not the state on screen. Past entries sit at full strength.
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
 * Resolves one [change] into its (label, mono value) pair at the surface:
 * the adjustment sliders name themselves through [ADJUST_SLIDERS]'s own
 * spec (one source of truth for a slider's name), the look through the
 * parsed registry's title, and every family word through its r68 string.
 * A null [change] is the launch entry --- "Original", no value.
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
            // The registry's own title; before the IO load lands (or for
            // an id it cannot resolve) the generic word never lies.
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
