package com.toadgallery.gallery.ui.overlays

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.theme.ToadTheme
import kotlinx.coroutines.delay

/** One story slide: the media plus its caption. */
data class ToadStorySlide(
    val item: MediaItem,
    val caption: String,
)

/**
 * The r76 STORY VIEWER OVERLAY (the prototype's `#story-modal`): a
 * full-screen story player with segmented top progress bars (one segment
 * per slide, the ACTIVE one filling over its 5s window), the story title
 * + count row, the close button, the full-bleed photo with the bottom
 * caption scrim, and the tap-anywhere-to-advance gesture.
 *
 * The playback engine: a plain `LaunchedEffect(currentIndex)` advancing
 * every [slideDurationMs]; a tap advances immediately (and on the LAST
 * slide closes --- the caller's [onClose]). Swiping between slides is
 * deliberately absent: the prototype advances by TAP only.
 */
@Composable
fun StoryViewerOverlay(
    title: String,
    subtitle: String,
    slides: List<ToadStorySlide>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    slideDurationMs: Long = 5_000L,
) {
    if (slides.isEmpty()) return
    var currentIndex by remember { mutableIntStateOf(0) }
    // The active segment's fill fraction (0..1), re-animated per slide.
    var progressFraction by remember { mutableFloatStateOf(0f) }
    val fill by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(200),
        label = "storyFill",
    )

    LaunchedEffect(currentIndex) {
        // The slide's window start, captured at effect launch (a plain
        // val --- LaunchedEffect bodies are not composable scope).
        val started = System.currentTimeMillis()
        while (true) {
            delay(100)
            val elapsed = System.currentTimeMillis() - started
            progressFraction = (elapsed.toFloat() / slideDurationMs).coerceIn(0f, 1f)
            if (elapsed >= slideDurationMs) break
        }
        // Advance (or close on the last slide).
        if (currentIndex < slides.lastIndex) {
            currentIndex += 1
            progressFraction = 0f
        } else {
            onClose()
        }
    }

    val palette = ToadTheme.palette
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF2000000)),
    ) {
        // ---- The segmented progress bars ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slides.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x4DFFFFFF)),
                    ) {
                        val fraction = when {
                            index < currentIndex -> 1f
                            index == currentIndex -> fill
                            else -> 0f
                        }
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(palette.primary),
                        )
                    }
                }
            }
            // ---- The title + close row ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = TextStyle(
                            fontFamily = ToadBodyFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
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
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x26FFFFFF))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close story",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // ---- The full-bleed slide (tap-to-advance) ----
        val slide = slides[currentIndex]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 96.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (currentIndex < slides.lastIndex) {
                        currentIndex += 1
                        progressFraction = 0f
                    } else {
                        onClose()
                    }
                },
        ) {
            AsyncImage(
                model = slide.item.uri.toString(),
                contentDescription = slide.caption,
                modifier = Modifier.fillMaxSize(),
            )
            // The bottom caption scrim.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE6000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = slide.caption,
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Tap anywhere to advance",
                    color = Color(0xB3FFFFFF),
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    }
}
