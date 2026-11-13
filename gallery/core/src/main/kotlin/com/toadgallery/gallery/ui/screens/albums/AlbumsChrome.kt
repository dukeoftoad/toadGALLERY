package com.toadgallery.gallery.ui.screens.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.theme.ToadTheme

/**
 * The r76 Albums header (the prototype's first block): "Albums" in `24sp`
 * ExtraBold Plus Jakarta Sans with heading semantics, and the `+ New
 * Album` pill (`primaryContainer`, `12sp` bold) on the trailing edge.
 *
 * The New Album action is the honest v1: the gallery has no user-created
 * album store yet (albums are MediaStore buckets), so the pill routes to
 * the same "new collection" affordance the Things section drives ---
 * [onNewAlbum] decides (today: the Things create flow / a toast).
 */
@Composable
fun ToadAlbumsHeader(
    onNewAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Albums",
            color = palette.textMain,
            style = TextStyle(
                fontFamily = ToadDisplayFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.primaryContainer)
                .clickable(onClick = onNewAlbum)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = palette.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "New Album",
                color = palette.onPrimaryContainer,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * A section eyebrow: the `11sp` ExtraBold uppercase label with the
 * optional trailing note (the prototype's section headers: "ON-DEVICE
 * SMART COLLECTIONS • Private AI", "DEVICE FOLDERS", "UTILITIES &
 * PRIVACY"). The trailing note renders `10sp` in [trailingColor] ---
 * the prototype paints "Private AI" in the primary and the plain notes
 * in `textDim`.
 */
@Composable
fun ToadSectionEyebrow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: androidx.compose.ui.graphics.Color? = null,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = palette.textMuted,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                color = trailingColor ?: palette.textDim,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The prototype's "Utilities & Privacy" card, r76.4 restyled EXACTLY as
 * the HTML composes it: a `rounded-3xl` bordered **bgSurface** card (NOT
 * the section-card row stack --- the prototype's rows are inset
 * `rounded-2xl` **bgSurfaceHigh** buttons), each row a 40dp
 * `rounded-xl` icon well (primaryContainer for the vault,
 * secondaryContainer for the bin) + bold title + muted summary +
 * trailing chevron. The card is an IN-FLOW item at the end of the
 * Albums grid --- never a pinned bottom bar.
 */
@Composable
fun ToadUtilitiesCard(
    vaultSummary: String,
    onOpenVault: () -> Unit,
    recycleSummary: String,
    onOpenRecycleBin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        ToadSectionEyebrow(label = "Utilities & Privacy")
        Spacer(Modifier.height(2.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.bgSurface)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(24.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UtilityRow(
                icon = Icons.Filled.Lock,
                iconBackground = palette.primaryContainer,
                iconTint = palette.onPrimaryContainer,
                title = "Hidden Vault",
                summary = vaultSummary,
                onClick = onOpenVault,
            )
            UtilityRow(
                icon = Icons.Filled.Delete,
                iconBackground = palette.secondaryContainer,
                iconTint = palette.textMain,
                title = "Recycle Bin",
                summary = recycleSummary,
                onClick = onOpenRecycleBin,
            )
        }
    }
}

/** One utilities row: the prototype's inset bgSurfaceHigh rounded-2xl button. */
@Composable
private fun UtilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBackground: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bgSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
            Text(
                text = summary,
                color = palette.textMuted,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * r76.4: the prototype's smart-collection card --- the compact 2-column
 * cell under "On-Device Smart Collections": a 48dp `rounded-2xl` (16dp)
 * cover, the bold `12sp` name, the `11sp` muted count line, all inside a
 * `rounded-3xl` (24dp) bordered bgSurface card. [onClick] null renders
 * the disabled shape (the prototype's "Disabled in AI" cards stay
 * visible but inert).
 */
@Composable
fun ToadSmartCollectionCard(
    name: String,
    summary: String,
    cover: Any?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    coverContentDescription: String? = null,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.bgSurface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.bgSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = coverContentDescription,
                    contentScale = ContentScale.Crop,
                    error = ColorPainter(palette.bgSurfaceHigh),
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Collections,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = name,
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                color = palette.textMuted,
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
