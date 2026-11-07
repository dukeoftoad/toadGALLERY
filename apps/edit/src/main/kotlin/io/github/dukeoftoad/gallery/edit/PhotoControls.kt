package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// ---------------------------------------------------------------------------
// The r56 control kit --- the crafted control surfaces for the photo editor's
// tool panels.
//
// Why this file exists: the r54 chassis moved every tool behind its tab but
// re-hosted each panel's CONTROLS verbatim from the r30-r41 rounds, so the
// panels still read as a settings form --- bare label-over-Slider pairs with
// no value readout, text-only chips for the looks/frames/fonts (no visual
// preview of the effect), and no way to get back to a slider's neutral. The
// maintainer's 2026-11-07 chat instruction ("generic and flawed ... fix it
// and improve and redesign if necessary") is this file's mandate.
//
// The r32 a11y contract rides unchanged in every control here: the slider
// announces its NAME (contentDescription) and its formatted VALUE
// (stateDescription); the chips carry the row's single-selection semantics
// through FilterChip itself.
// ---------------------------------------------------------------------------

/** The thumbnail raster every honest preview renders at (3:2, 96x64). */
internal const val THUMB_WIDTH = 96

internal const val THUMB_HEIGHT = 64

/**
 * The reference strip the thumbs render through the REAL pipeline: the top
 * two thirds sweep teal -> violet -> amber under a vertical exposure ramp
 * (the hue + tone response in one glance); the bottom third is a neutral
 * gray ramp (a look's tone curve shows on neutrals, not just on color).
 * Pure deterministic arithmetic over sRGB ints --- JVM-tested in
 * `PhotoControlsTest` so the strip can never silently drift.
 */
internal fun thumbGradientPixels(width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "thumb raster must be non-empty" }
    val pixels = IntArray(width * height)
    // The three sweep stops (sRGB): teal, the editor's own violet, amber.
    val stops = intArrayOf(0xFF2E8B8B.toInt(), 0xFF8B72E8.toInt(), 0xFFE8A04B.toInt())
    val neutralTop = height * 2 / 3
    for (y in 0 until height) {
        for (x in 0 until width) {
            val t = if (width > 1) x.toFloat() / (width - 1) else 0f
            val argb = if (y < neutralTop) {
                // Two-segment lerp across the three stops.
                val seg = t * 2f
                val i = if (seg >= 1f) 1 else 0
                val f = (seg - i).coerceIn(0f, 1f)
                val c0 = stops[i]
                val c1 = stops[i + 1]
                val exposure = if (neutralTop > 1) {
                    1f - 0.45f * (y.toFloat() / (neutralTop - 1))
                } else {
                    1f
                }
                fun channel(shift: Int): Int {
                    val v0 = (c0 shr shift) and 0xFF
                    val v1 = (c1 shr shift) and 0xFF
                    return (v0 + (v1 - v0) * f).toInt().coerceIn(0, 255)
                }
                val r = (channel(16) * exposure).toInt().coerceIn(0, 255)
                val g = (channel(8) * exposure).toInt().coerceIn(0, 255)
                val b = (channel(0) * exposure).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                val gray = (28f + 212f * t).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            pixels[y * width + x] = argb
        }
    }
    return pixels
}

/**
 * The looks' honest thumbnail: the reference strip through the SAME
 * `EditPipeline.apply` the preview and the export use (the lut rides the
 * recipe so the identity short-circuit cannot skip it --- the pipeline's
 * filter gate matches `filter.id == selection.lutId`). What the chip shows
 * is what the look does.
 */
internal fun renderLookThumb(lut: CubeLut): Bitmap {
    val pixels = thumbGradientPixels(THUMB_WIDTH, THUMB_HEIGHT)
    val recipe = EditRecipe(lut = LutSelection(lutId = lut.id, intensity = 100))
    val result = EditPipeline.apply(pixels, THUMB_WIDTH, THUMB_HEIGHT, recipe, lut, null)
    return Bitmap
        .createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height) }
}

/**
 * The frames' honest thumbnail: the reference strip with the REAL parametric
 * geometry composited by the SAME `FrameCompositor.draw` the preview and the
 * export use (resolver = null: the bundled frames resolve internally, and an
 * imported frame never routes here --- its chip shows the imported bitmap).
 */
internal fun renderFrameThumb(frameId: String, colorArgb: Long): Bitmap {
    val bitmap = Bitmap
        .createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(thumbGradientPixels(THUMB_WIDTH, THUMB_HEIGHT), 0, THUMB_WIDTH, 0, 0, THUMB_WIDTH, THUMB_HEIGHT) }
    FrameCompositor.draw(
        bitmap,
        FrameSelection(frameId = frameId, widthPct = 18, colorArgb = colorArgb),
        { null },
        0f,
        0f,
        THUMB_WIDTH.toFloat(),
        THUMB_HEIGHT.toFloat(),
    )
    return bitmap
}

/**
 * The r56 slider row: the label and a LIVE value pill on one line, the
 * slider under them, and a double-tap that returns to a control's neutral
 * (the pill makes the number readable at a glance; the double-tap replaces
 * the "drag back to zero" hunt every stock slider panel punts on).
 *
 * Behavior parity with the r32 pattern it replaces: the value is CALLER-OWNED
 * --- every drag tick calls [onChange] (the caller updates its draft, the
 * live preview re-renders exactly as before) and the release calls
 * [onFinish] (the caller commits its draft and clears it). The a11y contract
 * is verbatim: name = [name], state = [label].
 */
@Composable
internal fun LabeledSlider(
    name: String,
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinish: (() -> Unit)? = null,
    resetValue: Int? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = valueText,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Slider(
            value = value,
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onFinish,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier
                .semantics {
                    contentDescription = name
                    stateDescription = label
                }
                .pointerInput(resetValue, enabled) {
                    if (resetValue != null && enabled) {
                        detectTapGestures(
                            onDoubleTap = {
                                onChange(resetValue)
                                onFinish?.invoke()
                            },
                        )
                    }
                },
        )
    }
}

/**
 * The HSL band's slider (the r32 control, r56-skinned): the drag value stays
 * LOCAL (the band commits once on release --- the r32 contract) while the
 * pill shows the live number during the drag. Signature unchanged, so the
 * Color screen's call sites are untouched.
 */
@Composable
internal fun HslBandSlider(
    name: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var dragValue by remember(label) { mutableStateOf<Float?>(null) }
    val live = dragValue ?: value
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = live.toInt().toString(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Slider(
            value = live,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onCommit(it.toInt()) }
                dragValue = null
            },
            valueRange = range,
            enabled = enabled,
            // r32 a11y: the band control announces "BAND: CONTROL" as its name
            // and the formatted label (with the value) as its state.
            modifier = Modifier.semantics {
                contentDescription = name
                stateDescription = label
            },
        )
    }
}

/**
 * One look chip: the honest pipeline thumb over the look's name, the
 * selected state carried by FilterChip itself (the theme's accent-fill
 * mechanism). `lut == null` renders the None chip: name only, no thumb ---
 * "no look" has nothing honest to preview.
 */
@Composable
internal fun LookThumbChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    lut: CubeLut?,
    onPick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onPick,
        enabled = enabled,
        label = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                if (lut != null) {
                    val thumb = remember(lut.id) { renderLookThumb(lut) }
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(width = 66.dp, height = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    )
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * One frame chip: the composited parametric thumb (or the imported frame's
 * own bitmap) over the frame's name --- same honest-render contract as the
 * look chips. `thumb == null` renders the None chip: name only.
 */
@Composable
internal fun FrameThumbChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    thumb: Bitmap?,
    onPick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onPick,
        enabled = enabled,
        label = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(width = 66.dp, height = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    )
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * One font chip: "Ag" set in the REAL bundled Typeface (the platform object
 * the canvas renderer already uses --- one code path) over the font's name.
 * The preview rides `AndroidView(TextView)`: the compose-ui 1.7 surface has
 * no public android.graphics.Typeface bridge, and the canvas' Typefaces are
 * created from assets (`EditActivity.bundledFonts`) --- reusing them here is
 * both honest and free. A missing typeface (the tolerance contract) renders
 * the name only.
 */
@Composable
internal fun FontPreviewChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    typeface: Typeface?,
    onPick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onPick,
        enabled = enabled,
        label = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                if (typeface != null) {
                    val sampleColor = MaterialTheme.colorScheme.onBackground
                    AndroidView(
                        factory = { context ->
                            TextView(context).apply { text = "Ag" }
                        },
                        update = { view ->
                            view.typeface = typeface
                            view.textSize = 22f
                            view.setTextColor(sampleColor.toArgb())
                        },
                        modifier = Modifier.height(30.dp),
                    )
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.padding(end = 8.dp),
    )
}
