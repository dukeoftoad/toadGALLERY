package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected

// The control kit for the editor's tool panels; sliders announce name
// (contentDescription) + formatted value (stateDescription), chips carry selection semantics.

/** The thumbnail raster every honest preview renders at (3:2, 96x64). */
internal const val THUMB_WIDTH = 96

internal const val THUMB_HEIGHT = 64

/**
 * The reference strip thumbs render through the REAL pipeline: top two thirds sweep
 * teal -> violet -> amber under a vertical exposure ramp; bottom third a neutral
 * gray ramp. Deterministic sRGB int arithmetic, JVM-tested in `PhotoControlsTest`.
 */
internal fun thumbGradientPixels(width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "thumb raster must be non-empty" }
    val pixels = IntArray(width * height)
    // Sweep stops (sRGB): teal, violet, amber (violet is a hue-wheel reference, not the chrome accent).
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
 * The look thumbnail: reference strip through the SAME `EditPipeline.apply` the preview
 * and export use (lut rides the recipe so the identity short-circuit cannot skip it).
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
 * The frame thumbnail: reference strip through the SAME `FrameCompositor.draw` the
 * preview and export use; resolver = null (bundled frames resolve internally).
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
 * The bundled sky thumbnail through the SAME `BundledSkies.render` the sky stage
 * blends; an unresolvable id (tolerance contract) returns null and the chip degrades to its name.
 */
internal fun renderSkyThumb(skyId: String): Bitmap? {
    val pixels = BundledSkies.render(skyId, THUMB_WIDTH, THUMB_HEIGHT) ?: return null
    return Bitmap
        .createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(pixels, 0, THUMB_WIDTH, 0, 0, THUMB_WIDTH, THUMB_HEIGHT) }
}

/**
 * Slider row: label + live value pill, double-tap resets to the neutral. Value is
 * caller-owned: every tick calls [onChange], release calls [onFinish]; a11y name =
 * [name], state = [label]. [neutralTick] draws a center notch for ranges symmetric
 * around zero --- bipolar controls only (unipolar 0..100 rows must not opt in);
 * asymmetric ranges draw nothing even if true.
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
    neutralTick: Boolean = false,
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
        Box {
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
            // Neutral notch: drawn only for a range symmetric around zero (thumb's 0 at track center).
            if (neutralTick && valueRange.start + valueRange.endInclusive == 0f) {
                NeutralNotch(modifier = Modifier.matchParentSize())
            }
        }
    }
}

/**
 * The neutral notch: 2dp hairline at the track's center, outline role, hosted as a
 * `matchParentSize` overlay (paints only). Purely decorative --- no semantics.
 */
@Composable
internal fun NeutralNotch(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = 6.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(cx, cy - half),
            end = Offset(cx, cy + half),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * One frame chip: the composited thumb (or the imported frame's bitmap) over the name;
 * `thumb == null` renders the None chip (name only).
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
 * One sky chip: [bundled] renders through `renderSkyThumb` (remembered per id);
 * an imported sky shows its own bitmap. Both null renders the None chip (name only).
 */
@Composable
internal fun SkyThumbChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    bundled: BundledSkies.Entry?,
    imported: Bitmap?,
    onPick: () -> Unit,
) {
    val thumb: Bitmap? = when {
        bundled != null -> remember(bundled.id) { renderSkyThumb(bundled.id) }
        imported != null -> imported
        else -> null
    }
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
 * One font chip: "Ag" in the real bundled Typeface over the name, via
 * `AndroidView(TextView)` (no compose-ui android.graphics.Typeface bridge). A missing
 * typeface renders the name only.
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

/**
 * The swatch palette: fixed, named, TalkBack-labelled (one-of-N radio semantics).
 * ARGB Longs; the layer keeps the exact value it picked.
 */
internal val TEXT_COLORS: List<Pair<Long, Int>> = listOf(
    0xFFFFFFFFL to R.string.editor_color_white,
    0xFF111111L to R.string.editor_color_black,
    0xFFF44336L to R.string.editor_color_red,
    0xFFFF9800L to R.string.editor_color_orange,
    0xFFFDD835L to R.string.editor_color_yellow,
    0xFF43A047L to R.string.editor_color_green,
    0xFF00ACC1L to R.string.editor_color_cyan,
    0xFF1E88E5L to R.string.editor_color_blue,
)

/** One color swatch: a bordered circle with radio selection semantics. */
@Composable
internal fun ColorSwatch(
    colorArgb: Long,
    selected: Boolean,
    name: String,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    // Renamed local: the semantics receiver's `selected` would shadow the parameter.
    val isSelected = selected
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            // 48dp meets the touch-target floor (WCAG 2.5.5).
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(colorArgb.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            // toggleable is unavailable in this foundation build; clickable + explicit
            // semantics (role + selected) carries the same a11y contract.
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onPick() },
            )
            .semantics {
                this.selected = isSelected
                contentDescription = name
            },
    )
}
