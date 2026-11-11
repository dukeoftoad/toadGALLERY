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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // The three sweep stops (sRGB): teal, violet, amber. The violet stop is
    // a HUE-WHEEL reference (teal -> violet -> amber maximizes the hue
    // coverage the LUT response shows in one glance) --- it is CONTENT, not
    // the chrome accent, and it is NOT the r53 chrome violet that the r63
    // re-pin retired from every theme role (see the r63 proposal's §3: only
    // EditorTheme.kt + VideoEditTheme carry accent hexes now).
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
 * The bundled sky's honest thumbnail (r66): the parametric render at the
 * reference strip's own raster, through the SAME `BundledSkies.render`
 * the sky stage blends through at commit --- what the chip shows is what
 * the stage paints. An unresolvable id (the tolerance contract) returns
 * null and the chip degrades to its name, like the None chip.
 */
internal fun renderSkyThumb(skyId: String): Bitmap? {
    val pixels = BundledSkies.render(skyId, THUMB_WIDTH, THUMB_HEIGHT) ?: return null
    return Bitmap
        .createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(pixels, 0, THUMB_WIDTH, 0, 0, THUMB_WIDTH, THUMB_HEIGHT) }
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
 *
 * r66: [neutralTick] opts a bipolar control into the NEUTRAL NOTCH --- a
 * hairline vertical mark at the track's exact center, where the thumb
 * sits at 0 for any range symmetric around zero ([-100, 100], [-15, 15]).
 * The double-tap-to-reset affordance becomes DISCOVERABLE (the mark is
 * what you double-tap back to); unipolar controls (0..100
 * strength/opacity rows) must NOT opt in --- their zero is the track's
 * end, not its center. The guard is honest: an asymmetric range draws
 * nothing even if a caller passes true.
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
            // r66: the neutral notch, drawn only for the honest case ---
            // a range symmetric around zero puts the thumb's 0 at the
            // track's exact center (the M3 travel insets are symmetric).
            if (neutralTick && valueRange.start + valueRange.endInclusive == 0f) {
                NeutralNotch(modifier = Modifier.matchParentSize())
            }
        }
    }
}

/**
 * The HSL band's slider (the r32 control, r56-skinned): the drag value stays
 * LOCAL (the band commits once on release --- the r32 contract) while the
 * pill shows the live number during the drag. Signature unchanged, so the
 * Color screen's call sites are untouched.
 *
 * r66: the neutral notch rides the track (see [LabeledSlider]'s neutralTick
 * note) --- every band row is bipolar with a zero neutral (hue -180..180,
 * saturation/luminance -100..100), so the mark needs no opt-in here; the
 * symmetric-zero guard still keeps a hypothetical asymmetric band honest.
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
        Box {
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
            if (range.start + range.endInclusive == 0f) {
                NeutralNotch(modifier = Modifier.matchParentSize())
            }
        }
    }
}

/**
 * The r66 neutral notch: a 2dp hairline at the track's exact center,
 * half the track height tall, in the outline role. The caller hosts it
 * as a `matchParentSize` overlay INSIDE the same Box as the Slider (the
 * Box is what keeps the slider's layout byte-identical --- the overlay
 * only paints). Purely decorative: no semantics, nothing for TalkBack
 * to read that the slider's own state does not already say.
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
 * One sky chip (r66): the honest thumb over the sky's name, the
 * LookThumbChip/FrameThumbChip contract applied to the last text-only
 * picker row. [bundled] renders through `renderSkyThumb` (the same
 * `BundledSkies.render` the sky stage blends through, remembered per
 * sky id); an imported sky shows [imported] --- its own bitmap (the
 * imported-frame precedent: the chip shows the pixels the stage would
 * paste). Both null renders the None chip: name only --- "no sky" has
 * nothing honest to preview.
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

// ---------------------------------------------------------------------------
// The r57 move: the r34 swatch palette + its swatch control live in the
// control kit now (their only readers are the extracted tool panels; same
// package, internal --- the panel call sites are unchanged).
// ---------------------------------------------------------------------------

/**
 * The r34 swatch palette: fixed, named, TalkBack-labelled (one of N radio
 * semantics). ARGB Longs; the layer keeps the exact value it picked.
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
    // A local with a distinct name: inside the semantics {} lambda the
    // receiver's own `selected` property shadows this parameter, so an
    // unqualified `selected = selected` reads as self-assignment.
    val isSelected = selected
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            // r47 ux fix: 36dp was under the 48dp touch-target floor
            // (design-tokens.md section 2.5, WCAG 2.5.5); the swatch circle
            // grows to meet it.
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(colorArgb.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            // toggleable is not on this foundation build's API surface
            // (unresolved at the r34 head's first compile); clickable +
            // explicit semantics carries the same a11y contract --- the
            // repo-wide clickable convention, role + selected stated here.
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
