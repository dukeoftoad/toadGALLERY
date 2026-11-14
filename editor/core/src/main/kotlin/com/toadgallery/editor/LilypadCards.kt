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

// The Looks deck as a visual shelf: every card is a RESULT rendered through the SAME
// EditPipeline the preview and export use --- never a bitmap asset, never a placeholder.

/** The card raster width (px): the honest-preview resolution. */
internal const val CARD_THUMB_WIDTH = 160

/**
 * The look's card thumbnail: [base] (the editor's preview base) scaled to the card
 * raster, rendered through the SAME EditPipeline at full intensity --- what the card
 * shows is what the look does to THIS photo. A null [base] falls back to the reference
 * strip ([renderLookThumb]).
 */
internal fun renderLookCardThumb(lut: CubeLut, base: Bitmap?): Bitmap {
    if (base == null) return renderLookThumb(lut)
    // ARGB_8888 working copy (HARDWARE bitmaps can't be read pixel-wise; read never mutates),
    // scaled to the card width preserving the photo's aspect (a look's response is photo-shaped).
    val source =
        if (base.config == Bitmap.Config.ARGB_8888) base else base.copy(Bitmap.Config.ARGB_8888, false)
    val scaledHeight = (source.height * CARD_THUMB_WIDTH / source.width).coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, CARD_THUMB_WIDTH, scaledHeight, true)
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    // Same recipe shape as renderLookThumb (lut rides the recipe so the identity short-circuit can't skip it).
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
 * One shelf card: the look's preview with its name --- a result to SEE, not a word to
 * read. [thumb] null renders the quiet placeholder (same footprint, keeps the grid's
 * shape); [pressedThumb] is the COMPARE affordance --- the plain original shown while
 * held (null disables it; the None card has nothing earlier to show). Pressed state
 * rides the [MutableInteractionSource] stream --- ONE gesture owner, so the pick and
 * the compare cannot consume each other's events. A11y: radio selection semantics
 * ([Role.RadioButton] + [selected]), the label as the card's single name.
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
    // Renamed local: the semantics receiver's `selected` would shadow the parameter.
    val isSelected = selected
    // Compare: pressed off the shared stream; plain original while held, pipeline render otherwise.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shownThumb = if (pressed && pressedThumb != null) pressedThumb else thumb
    // Selected card's border burns (primary, 2dp); others read a hairline (outlineVariant, 1dp).
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
        // The image area: a 96dp plate, cropped. A null thumb renders the quiet
        // placeholder (same footprint, surfaceVariant) so the grid's shape survives.
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
