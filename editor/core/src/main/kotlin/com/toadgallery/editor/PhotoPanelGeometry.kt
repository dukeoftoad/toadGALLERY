package com.toadgallery.editor

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The Geometry (crop) tool panel, state-hoisted as value + on...Change pairs. The straighten
// dial and keystone sliders keep the live-draft contract: tick drafts, release commits ONE recipe.

@Composable
internal fun CropPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    previewBase: Bitmap?,
    commit: (EditRecipe) -> Unit,
) {
    // ---- Geometry row ----
    Text(
        text = stringResource(R.string.editor_section_geometry),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        IconButton(
            onClick = {
                commit(
                    committed.copy(
                        quarterTurns = (committed.quarterTurns + 1) % 4,
                    ),
                )
            },
            enabled = !exporting,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.editor_rotate_left),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        // Flip toggles are FilterChips for a11y: an IconButton's text label overflows the 48dp
        // target; a chip gives the proper target, the state highlight and the a11y announcement.
        FilterChip(
            selected = committed.horizontalFlip,
            onClick = {
                commit(committed.copy(horizontalFlip = !committed.horizontalFlip))
            },
            enabled = !exporting,
            label = {
                Text(
                    text = stringResource(
                        R.string.editor_flip_horizontal_toggle,
                        if (committed.horizontalFlip) {
                            stringResource(R.string.editor_on)
                        } else {
                            stringResource(R.string.editor_off)
                        },
                    ),
                )
            },
            modifier = Modifier.padding(end = 8.dp),
        )
        FilterChip(
            selected = committed.verticalFlip,
            onClick = {
                commit(committed.copy(verticalFlip = !committed.verticalFlip))
            },
            enabled = !exporting,
            label = {
                Text(
                    text = stringResource(
                        R.string.editor_flip_vertical_toggle,
                        if (committed.verticalFlip) {
                            stringResource(R.string.editor_on)
                        } else {
                            stringResource(R.string.editor_off)
                        },
                    ),
                )
            },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    // The straighten dial follows the drag live (label, readout, preview). The value
    // stays a Double so a fractional sidecar straighten (e.g. 7.5) displays exactly,
    // not truncated; the range is the recipe's +/-45 validation window. Live-draft:
    // tick writes the "straighten" draft, release commits.
    val straightenLive = draft?.takeIf { it.first == "straighten" }?.second?.toDouble()
        ?: committed.straightenDeg
    LilypadDial(
        name = stringResource(R.string.editor_a11y_straighten),
        label = stringResource(R.string.editor_a11y_straighten),
        state = stringResource(R.string.editor_straighten, straightenLive.toInt()),
        valueText = stringResource(
            R.string.editor_r68_dial_deg,
            LilypadSliderMath.signedValueText(straightenLive.toInt()),
        ),
        value = straightenLive.toFloat(),
        valueRange = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
        enabled = !exporting,
        onChange = { onDraftChange(Pair("straighten", it.toInt())) },
        onFinish = {
            draft?.let { d ->
                if (d.first == "straighten") {
                    commit(committed.copy(straightenDeg = d.second.toDouble()))
                }
            }
            onDraftChange(null)
        },
    )

    // ---- Perspective: the two keystone rows over the quad ----
    // The rows read their percents back out of the committed quad (the recipe stores the
    // QUAD, not the knobs), drafted against the PREVIEW grid the commit builds on. The
    // corner overlay owns the canvas's drag surface; the rows keep the model's a11y path.
    Text(
        text = stringResource(R.string.editor_r68_section_perspective),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    if (LilypadFlags.cornerDragEnabled) {
        Text(
            text = stringResource(R.string.editor_r72_corner_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    /**
     * ONE recipe per release: the quad built from the drafted knob and the other knob's
     * committed read-back, on the preview grid. A draft with no decoded base drops (never
     * a silently invented quad).
     */
    fun commitKeystone(key: String) {
        val d = draft
        val base = previewBase
        if (d != null && d.first == key && base != null) {
            val vertical = if (key == KEYSTONE_VERTICAL) {
                d.second
            } else {
                LilypadKeystoneMath.verticalPctOf(committed.perspective, base.width)
            }
            val horizontal = if (key == KEYSTONE_HORIZONTAL) {
                d.second
            } else {
                LilypadKeystoneMath.horizontalPctOf(committed.perspective, base.height)
            }
            commit(
                committed.copy(
                    perspective = LilypadKeystoneMath.quadFor(
                        verticalPct = vertical,
                        horizontalPct = horizontal,
                        width = base.width,
                        height = base.height,
                    ),
                ),
            )
        }
        onDraftChange(null)
    }
    val baseWidth = previewBase?.width ?: 0
    val baseHeight = previewBase?.height ?: 0
    val keystoneRange = LilypadKeystoneMath.KEYSTONE_MIN.toFloat()..
        LilypadKeystoneMath.KEYSTONE_MAX.toFloat()
    val verticalLive = if (draft?.first == KEYSTONE_VERTICAL) {
        draft.second
    } else {
        LilypadKeystoneMath.verticalPctOf(committed.perspective, baseWidth)
    }
    val horizontalLive = if (draft?.first == KEYSTONE_HORIZONTAL) {
        draft.second
    } else {
        LilypadKeystoneMath.horizontalPctOf(committed.perspective, baseHeight)
    }
    ProSlider(
        name = stringResource(R.string.editor_r68_keystone_vertical),
        label = stringResource(R.string.editor_r68_keystone_vertical),
        state = stringResource(R.string.editor_r68_keystone_vertical_state, verticalLive),
        valueText = LilypadSliderMath.signedValueText(verticalLive),
        value = verticalLive.toFloat(),
        valueRange = keystoneRange,
        neutral = 0f,
        enabled = !exporting,
        onChange = { v -> onDraftChange(Pair(KEYSTONE_VERTICAL, v.toInt())) },
        onFinish = { commitKeystone(KEYSTONE_VERTICAL) },
    )
    ProSlider(
        name = stringResource(R.string.editor_r68_keystone_horizontal),
        label = stringResource(R.string.editor_r68_keystone_horizontal),
        state = stringResource(R.string.editor_r68_keystone_horizontal_state, horizontalLive),
        valueText = LilypadSliderMath.signedValueText(horizontalLive),
        value = horizontalLive.toFloat(),
        valueRange = keystoneRange,
        neutral = 0f,
        enabled = !exporting,
        onChange = { v -> onDraftChange(Pair(KEYSTONE_HORIZONTAL, v.toInt())) },
        onFinish = { commitKeystone(KEYSTONE_HORIZONTAL) },
    )

    // ---- Crop aspect presets (centered fits over the post-geometry frame) ----
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            null to CropAspect.FREE, // the ratios are locale-neutral; the word is not
            "1:1" to CropAspect.SQUARE,
            "4:3" to CropAspect.RATIO_4_3,
            "3:4" to CropAspect.RATIO_3_4,
            "16:9" to CropAspect.RATIO_16_9,
            "9:16" to CropAspect.RATIO_9_16,
        ).forEach { (label, aspect) ->
            AssistChip(
                onClick = {
                    if (aspect == CropAspect.FREE) {
                        commit(committed.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0)))
                    } else {
                        // The preset fits over the post-geometry
                        // frame (the same plan the overlay draws).
                        val plan = EditGeometryPlan.plan(
                            committed.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0)),
                            previewBase?.width ?: 1,
                            previewBase?.height ?: 1,
                        )
                        val fitted = aspect.fit(plan.cropPixelRect, plan.postGeometryWidth, plan.postGeometryHeight)
                        // The pixel-rect -> normalized conversion is the tested pure
                        // inverse (CropRect.fromPixelRect), not an inline division.
                        commit(
                            committed.copy(
                                crop = CropRect.fromPixelRect(
                                    fitted,
                                    plan.postGeometryWidth,
                                    plan.postGeometryHeight,
                                ),
                            ),
                        )
                    }
                },
                label = {
                    Text(label ?: stringResource(R.string.editor_crop_free))
                },
                modifier = Modifier.padding(end = 8.dp),
                enabled = !exporting,
            )
        }
    }
}

// Keystone draft keys: one constant per knob, declared beside their only writers/readers
// (the panel and the chassis's effectiveRecipe branch).

/** The vertical keystone's draft key (the "straighten" slot's sibling). */
internal const val KEYSTONE_VERTICAL = "keystone-v"

/** The horizontal keystone's draft key. */
internal const val KEYSTONE_HORIZONTAL = "keystone-h"
