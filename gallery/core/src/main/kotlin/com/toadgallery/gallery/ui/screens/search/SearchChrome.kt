package com.toadgallery.gallery.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.components.toadTrailingFade
import com.toadgallery.gallery.ui.theme.ToadTheme

/**
 * The r76 Magic Search header: the `24sp` ExtraBold "Magic Search" title
 * (heading semantics) + the `12sp` muted subtitle --- the prototype's
 * "100% on-device semantic & screenshot text (OCR) search" line, rendered
 * only where the semantic pipeline actually exists (the caller gates).
 */
@Composable
fun ToadSearchHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Magic Search",
            color = palette.textMain,
            style = TextStyle(
                fontFamily = ToadDisplayFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp,
            ),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = subtitle,
            color = palette.textMuted,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The prototype's full-pill search bar (`CircleShape`, `bgSurface` fill,
 * `1dp borderStrong` stroke, `primary` leading icon, clear button):
 * an M3 `TextField` with every default color overridden from the palette
 * so the pill renders exactly as drawn (no indicator line, no label
 * float).
 */
@Composable
fun ToadSearchPillField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                text = hint,
                color = palette.textDim,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.bgSurfaceHigh)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = palette.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        },
        textStyle = TextStyle(
            fontFamily = ToadBodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = palette.textMain,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.bgSurface,
            unfocusedContainerColor = palette.bgSurface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = palette.primary,
        ),
    )
}

/**
 * The horizontal prompt-chip strip (`LazyRow` + the trailing fade mask):
 * the prototype's suggested queries --- sunset, receipt, dogs... One tap
 * fills the search field (the caller runs it through the SAME
 * onValueChange pipeline typing uses; the active query renders filled).
 *
 * r77.7: the `1dp borderSubtle` stroke the prototype paints on EVERY
 * prompt chip (`border-color: var(--border-subtle)` in the suggested-
 * prompts markup) --- the first cut dropped it, so the unselected pills
 * floated borderless against the canvas while every other chip in the
 * app carried the hairline.
 */
@Composable
fun ToadPromptChips(
    prompts: List<String>,
    activeQuery: String,
    onPromptTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .toadTrailingFade(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(prompts) { prompt ->
            val active = prompt.equals(activeQuery, ignoreCase = true)
            Text(
                text = prompt,
                color = if (active) palette.onPrimary else palette.textMuted,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) palette.primary else palette.bgSurface)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
                    .clickable { onPromptTap(prompt) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The "CLIP + OCR Active" badge (`bgSurfaceHigh`, `10sp`, `primary`) ---
 * the results header's trailing proof that the on-device pipeline ran.
 */
@Composable
fun ToadClipOcrBadge(modifier: Modifier = Modifier) {
    val palette = ToadTheme.palette
    Text(
        text = "CLIP + OCR Active",
        color = palette.primary,
        style = TextStyle(
            fontFamily = ToadBodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        ),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.bgSurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
