package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): the Geometry (crop) tool panel, extracted
// VERBATIM from `EditActivity.EditorContent`'s `PhotoScreen.Crop` branch.
//
// The move itself is the point (the r56 chrome move's rule): a panel that
// composes in isolation is a panel the screenshot harness can pin. The body
// is the r32 geometry contract, unchanged; the chassis state it read/wrote
// arrives as explicit value + `on...Change` pairs (the state-hoisting
// pattern), so every commit-on-release behavior is bit-identical --- the
// committed Roborazzi chassis baselines are the regression proof.
// ---------------------------------------------------------------------------

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
        // r32 a11y: both flip toggles are FilterChips. A text
        // label inside an IconButton overflows the 48dp target, and
        // a clickable Text was a sub-48dp touch target; a selected
        // chip gives the proper target, the state highlight AND the
        // selected/not-selected announcement next to the on/off text.
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
    // r32: the straighten slider follows the drag live (the label,
    // the thumb, the preview) exactly like the adjustment sliders;
    // it previously held the committed value until release. The
    // value stays a Double so a fractional sidecar straighten (e.g.
    // 7.5) displays exactly, not truncated.
    val straightenLive = draft?.takeIf { it.first == "straighten" }?.second?.toDouble()
        ?: committed.straightenDeg
    val straightenName = stringResource(R.string.editor_a11y_straighten)
    val straightenState = stringResource(R.string.editor_straighten, straightenLive.toInt())
    Text(
        text = straightenState,
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = straightenLive.toFloat(),
        onValueChange = { onDraftChange(Pair("straighten", it.toInt()))},
        onValueChangeFinished = {
            draft?.let { d ->
                if (d.first == "straighten") {
                    commit(committed.copy(straightenDeg = d.second.toDouble()))
                }
            }
            onDraftChange(null)
        },
        valueRange = -15f..15f,
        enabled = !exporting,
        modifier = Modifier.semantics {
            contentDescription = straightenName
            stateDescription = straightenState
        },
    )

    // ---- Crop aspect presets (centered fits over the post-geometry frame) ----
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            null to CropAspect.FREE, // the ratios are locale-neutral; the word is not (r32)
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
                        // r32: the pixel-rect -> normalized conversion
                        // is the tested pure inverse (CropRect.fromPixelRect),
                        // not an inline division that could drift.
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
