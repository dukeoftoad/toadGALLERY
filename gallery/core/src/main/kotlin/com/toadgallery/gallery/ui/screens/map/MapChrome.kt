package com.toadgallery.gallery.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.theme.ToadTheme

/** The Map tab's two display modes (the prototype's segmented toggle). */
enum class ToadMapMode {
    /** Clustered thumbnail pins over the offline world map. */
    PINS,

    /** Radial thermal density gradients instead of pins. */
    HEATMAP,
}

/**
 * The r76 Places Map header: the `20sp` ExtraBold "Places Map" title
 * (heading semantics), the `11sp` muted geotagged-count line, and the
 * segmented Pins/Heatmap pill toggle (`bgSurface` track, `borderSubtle`
 * hairline, the active segment a `primary` fill).
 */
@Composable
fun ToadMapHeader(
    geotaggedCount: Int,
    mode: ToadMapMode,
    onModeChange: (ToadMapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Places Map",
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadDisplayFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    letterSpacing = (-0.3).sp,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "$geotaggedCount geotagged memories • Local EXIF only",
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
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.bgSurface)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToadMapModeSegment(
                label = "Pins",
                active = mode == ToadMapMode.PINS,
                onClick = { onModeChange(ToadMapMode.PINS) },
            )
            ToadMapModeSegment(
                label = "Heatmap",
                active = mode == ToadMapMode.HEATMAP,
                onClick = { onModeChange(ToadMapMode.HEATMAP) },
            )
        }
    }
}

/** One segment of the Pins/Heatmap toggle. */
@Composable
private fun ToadMapModeSegment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = ToadTheme.palette
    Text(
        text = label,
        color = if (active) palette.onPrimary else palette.textMuted,
        style = TextStyle(
            fontFamily = ToadBodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        ),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * The prototype's Selected Location Memory Card: `24dp` rounded
 * `bgSurface` + `borderStrong` card with a `64dp` rounded thumbnail, the
 * uppercase `primary` location label, the ExtraBold title, the date /
 * "Tap to inspect EXIF" line, and the trailing expand button
 * (`primaryContainer` circle) that opens the lightbox.
 */
@Composable
fun ToadMapMemoryCard(
    thumbnailUri: String?,
    locationLabel: String,
    title: String,
    dateLine: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.bgSurface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnailUri != null) {
            AsyncImage(
                model = thumbnailUri,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.bgSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = palette.textDim,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = locationLabel.uppercase(),
                color = palette.primary,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadDisplayFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dateLine,
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
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.OpenInFull,
                contentDescription = null,
                tint = palette.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The prototype's "Map Missing GPS Quick Helper Pill" (r77.6): a
 * `bgGlass` rounded-full chip at the canvas card's top-right with the
 * compass icon (`primary`) and an `11sp` SemiBold `textMain` label ---
 * here the SELECTED pin's real coordinates (`17.43°N • 78.48°E`; the
 * prototype's Hyderabad label is demo fiction, no reverse geocoding
 * exists on-device and none is invented). [onClick] `null` renders the
 * informational chip; non-null (the pending-reindex backlog case) makes
 * it a real button that narrates through the shell's one toast banner.
 */
@Composable
fun ToadMapPill(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.bgGlass)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Explore,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            color = palette.textMain,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
