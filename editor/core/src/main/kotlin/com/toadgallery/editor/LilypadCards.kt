package com.toadgallery.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// The r67 Lilypad foundation round (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- C3: the Looks deck becomes a VISUAL
// SHELF, "a shelf of results to see, not a list of words to read" (the
// rebirth audit's B2 fix).
//
// THE HONEST-RENDER CONTRACT: every card is a RESULT. [renderLookCardThumb]
// renders the editor's real preview base through the SAME EditPipeline the
// preview and the export use, at full intensity --- what the card shows is
// what the look does to THIS photo, not to a stock swatch. The None card
// and the press-and-hold COMPARE render the plain base
// ([renderPlainCardThumb]); a null base (screenshot fixtures, pre-decode)
// degrades the look cards to the r56 reference strip (renderLookThumb) ---
// honest in both cases, never a bitmap asset, never a second rendering
// path, never a placeholder that lies.
//
// Every line here is an original implementation for this GPL-3.0 project;
// the interaction pattern is re-derived from behavior, not ported.
// ---------------------------------------------------------------------------

/** The card raster width (px): the honest-preview resolution. */
internal const val CARD_THUMB_WIDTH = 160

/**
 * The look's card thumbnail: the [base] photo (the editor's preview base,
 * when the chassis has one) scaled to the card raster and rendered through
 * the SAME EditPipeline the preview and the export use, at full intensity —
 * what the card shows is what the look does to THIS photo. A null [base]
 * (screenshot fixtures, pre-decode) falls back to the reference strip
 * (renderLookThumb) — honest in both cases, never a placeholder.
 */
internal fun renderLookCardThumb(lut: CubeLut, base: Bitmap?): Bitmap {
    if (base == null) return renderLookThumb(lut)
    // The raster prep: an ARGB_8888 working copy (a HARDWARE preview base
    // cannot be read pixel-wise; a matching config is used as-is --- the
    // read below never mutates), scaled to the card width preserving the
    // photo's aspect --- a look's response is photo-shaped, not 3:2-shaped.
    val source =
        if (base.config == Bitmap.Config.ARGB_8888) base else base.copy(Bitmap.Config.ARGB_8888, false)
    val scaledHeight = (source.height * CARD_THUMB_WIDTH / source.width).coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, CARD_THUMB_WIDTH, scaledHeight, true)
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    // The SAME recipe shape renderLookThumb uses (the lut rides the recipe
    // so the pipeline's identity short-circuit cannot skip it), the SAME
    // resolved-look pairing, the SAME null AI bundle: one pipeline, one
    // truth.
    val recipe = EditRecipe(lut = LutSelection(lutId = lut.id, intensity = 100))
    val result = EditPipeline.apply(pixels, scaled.width, scaled.height, recipe, lut, null)
    return Bitmap
        .createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height) }
}

/** The plain raster: [base] scaled, no pipeline — what "no look" looks like. Null base -> null (the None card degrades to name-only). */
internal fun renderPlainCardThumb(base: Bitmap?): Bitmap? {
    if (base == null) return null
    val source =
        if (base.config == Bitmap.Config.ARGB_8888) base else base.copy(Bitmap.Config.ARGB_8888, false)
    val scaledHeight = (source.height * CARD_THUMB_WIDTH / source.width).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, CARD_THUMB_WIDTH, scaledHeight, true)
}

/**
 * One shelf card (r67 C3): the look's honest preview with its name under
 * it --- a result to SEE, not a word to read. [thumb] is the pipeline
 * render (null renders the quiet placeholder: "no look" has nothing to
 * preview, but the card keeps the grid's shape); [pressedThumb] is the
 * COMPARE affordance --- the plain original shown while the card is held,
 * so auditioning a look is a finger-press against "before" (null disables
 * the compare: the None card has nothing earlier to show).
 *
 * The pressed state rides THE interactionSource route: one gesture owner
 * (the clickable itself, fed the shared source) with the pressed flag read
 * off the SAME interaction stream that drives the indication ---
 * `collectIsPressedAsState`, the canonical Compose press pattern (stable
 * public API since compose 1.0 and unchanged in this build's foundation).
 * The alternative (a second `detectTapGestures(onPress = ...)` stacked
 * beside the clickable) was rejected on behavior grounds: two tap detectors
 * on one element must negotiate down/up CONSUMPTION, a version-subtle
 * interplay that can silently kill either the pick or the compare; one
 * owner has nothing to negotiate.
 *
 * The a11y contract is the TuneRailChip pattern: radio selection semantics
 * (clickable's [Role.RadioButton] + [selected]) with the label as the
 * card's single name.
 */
@Composable
internal fun LookCard(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    thumb: Bitmap?,          // null = name-only card (the None card without a base)
    pressedThumb: Bitmap? = null,  // shown while pressed (the compare affordance)
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A local with a distinct name: inside the semantics {} lambda the
    // receiver's own `selected` property shadows this parameter, so an
    // unqualified `selected = selected` reads as self-assignment (the
    // ColorSwatch note, verbatim).
    val isSelected = selected
    // THE COMPARE: pressed off the shared interaction stream; the image
    // source is the plain original while the card is held (and only when
    // there IS one), the pipeline render otherwise.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shownThumb = if (pressed && pressedThumb != null) pressedThumb else thumb
    // The quiet chrome + the one ember rule: the SELECTED card's border
    // burns (primary, 2dp); every other card reads a hairline
    // (outlineVariant, 1dp). Depth from lightness, never a shadow.
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else 1.dp
    val corner = RoundedCornerShape(LilypadTokens.CONTROL_CORNER_DP.dp)
    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onPick,
            )
            .semantics {
                this.selected = isSelected
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // THE IMAGE AREA: a 96dp-tall plate filling the card width ---
        // the result itself, cropped to the shelf's rhythm. A null thumb
        // renders the quiet placeholder (same footprint, surfaceVariant,
        // same border) so the grid's shape survives even "nothing to
        // preview".
        if (shownThumb != null) {
            Image(
                bitmap = shownThumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(corner)
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = corner,
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(corner)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = corner,
                    ),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
