package com.toadgallery.gallery.ui.overlays

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.theme.ToadTheme
import com.toadgallery.gallery.ui.viewer.ViewerInfoFormat

/**
 * The r76 PHOTO LIGHTBOX OVERLAY (the prototype's `#lightbox-modal`): a
 * full-screen dark viewer with the top bar (Back, Date + Location
 * respecting the EXIF privacy toggle, the favorite heart toggle), the
 * pinch-to-zoom preview, the title pill, and the frosted bottom sheet
 * carrying `Tags & OCR`, the `Camera / Source` EXIF line, and the three
 * quick actions (`Hide` to Vault, `Edit`, `Delete` to Recycle Bin).
 *
 * PURE PRESENTATION: the caller supplies every value and callback. The
 * zoom gesture itself rides [ToadZoomableImage] (a local
 * transform-gesture wrapper); the actions route to the caller's real
 * flows (the file-ops / vault / editor hands-off the grid screens use).
 */
@Composable
fun PhotoLightboxOverlay(
    item: MediaItem,
    dateLine: String,
    locationLine: String?,
    isFavorite: Boolean,
    tagsLine: String?,
    exifLine: String?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHideToVault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    // The sheet's measured height (the actions row + the info lines + the
    // paddings) --- the image area reserves it instead of painting beneath
    // it: the old fillMaxSize().padding(bottom = 200.dp) made the zoom
    // gesture surface extend UNDER the sheet (taps on Hide/Edit/Delete
    // also pinched) and hard-coded a 200dp guess that clipped the sheet's
    // content on dense fonts. The sheet is composed first and measured via
    // onSizeChanged; the image box takes the remaining space.
    var sheetHeight by remember { mutableStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
    ) {
        // ---- The media preview (zoomable, centered, ABOVE the sheet) ----
        ToadZoomableImage(
            model = item.uri.toString(),
            contentDescription = ViewerInfoFormat.nameFromUri(item.uri.toString()),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(bottom = with(LocalDensity.current) { sheetHeight.toDp() }),
        )

        // ---- The top bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_lightbox_back),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = dateLine,
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (locationLine != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = locationLine,
                            color = Color(0xB3FFFFFF),
                            style = TextStyle(
                                fontFamily = ToadBodyFont,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // The favorite heart.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(
                            if (isFavorite) {
                                R.string.cd_lightbox_unfavorite
                            } else {
                                R.string.cd_lightbox_favorite
                            },
                        ),
                    tint = if (isFavorite) Color(0xFFF43F5E) else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ---- The frosted bottom sheet ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { sheetHeight = it.height }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(palette.bgGlass)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The title pill.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = ViewerInfoFormat.nameFromUri(item.uri.toString()) ?: "Photo",
                    color = palette.onPrimaryContainer,
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Tags & OCR.
            if (tagsLine != null) {
                LightboxInfoLine(label = "Tags & OCR", value = tagsLine)
            }
            // Camera / Source EXIF.
            if (exifLine != null) {
                LightboxInfoLine(label = "Camera / Source", value = exifLine)
            }
            // The three quick actions.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LightboxAction(
                    icon = Icons.Filled.Lock,
                    label = "Hide",
                    modifier = Modifier.weight(1f),
                    onClick = onHideToVault,
                )
                LightboxAction(
                    icon = Icons.Filled.Edit,
                    label = "Edit",
                    modifier = Modifier.weight(1f),
                    onClick = onEdit,
                )
                LightboxAction(
                    icon = Icons.Filled.Delete,
                    label = "Delete",
                    modifier = Modifier.weight(1f),
                    danger = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** One label/value row in the lightbox's frosted sheet. */
@Composable
private fun LightboxInfoLine(label: String, value: String) {
    val palette = ToadTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = palette.textDim,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = palette.textMuted,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One quick-action pill in the lightbox sheet. */
@Composable
private fun LightboxAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) palette.dangerBg else palette.bgSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) palette.dangerText else palette.textMain,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (danger) palette.dangerText else palette.textMain,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The lightbox's zoomable image: a self-contained pinch/pan wrapper over
 * Coil's `AsyncImage` (transform gestures + graphicsLayer scale/offset)
 * with ContentScale.Fit (the whole photo always visible --- the prototype's
 * `object-contain`), a double-tap toggle between 1x and 2.5x, and PAN
 * CLAMPING to the zoomed image's own bounds so a pan can never push the
 * photo fully off-screen. Deliberately NOT the viewer's
 * [com.toadgallery.gallery.ui.viewer.ZoomableState]: that one is coupled to
 * the viewer pager's scroll locking; the lightbox is a single-item surface
 * with its own (simpler) clamping math.
 */
@Composable
private fun ToadZoomableImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // The box's own size, for the pan clamp (the zoomed photo may exceed
    // the box in one axis only under Fit; the clamp caps the pan to the
    // overflow in each axis --- a Fit-scaled photo has no overflow, so pan
    // is a no-op below zoom 1, exactly like every platform gallery).
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTransformGestures(
                    onGesture = { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        // The per-axis overflow: half the difference
                        // between the zoomed content and the box.
                        val overflowX = (boxSize.width * (newScale - 1f)) / 2f
                        val overflowY = (boxSize.height * (newScale - 1f)) / 2f
                        val newOffsetX = if (newScale > 1f) {
                            (offsetX + pan.x).coerceIn(-overflowX, overflowX)
                        } else {
                            0f
                        }
                        val newOffsetY = if (newScale > 1f) {
                            (offsetY + pan.y).coerceIn(-overflowY, overflowY)
                        } else {
                            0f
                        }
                        scale = newScale
                        offsetX = newOffsetX
                        offsetY = newOffsetY
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
    )
}
