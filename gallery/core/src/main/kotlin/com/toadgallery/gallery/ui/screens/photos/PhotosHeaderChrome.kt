package com.toadgallery.gallery.ui.screens.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadMotionSpec
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.components.ToadGlassSurface
import com.toadgallery.gallery.ui.theme.ToadTheme

/**
 * The r76 Photos sticky frosted header (the prototype's first screen
 * block): the `28dp` rounded `primary` brand badge with a bold "t" +
 * "toadGALLERY" in `20sp` ExtraBold Plus Jakarta Sans on the left, and on
 * the right the quick layout toggle (`bgSurfaceHigh` circular button:
 * `GridView` when Collage is active, `Apps` when Grid is active --- tapping
 * flips the Appearance preference live) plus the "Clean Feed" / "No
 * Screenshots" pill toggle (`primaryContainer` when active) that hides
 * screenshots from the timeline.
 *
 * Below the title row sits the filter-chip strip inside a `LazyRow` with
 * the trailing-edge alpha fade mask (`88% -> 100%`), so scrolled chips
 * dissolve instead of clipping sharply. The chips themselves are the
 * EXISTING `PhotosFilterChipsRow`'s ViewModel semantics (one tap = one
 * `TimelineFilter` change = one fresh paging generation), restyled as the
 * prototype's pill chips; the folder chip and its picker sheet ride the
 * same row (the r19b plumbing, unchanged).
 */
@Composable
fun ToadPhotosHeader(
    layoutIsCollage: Boolean,
    onToggleLayout: () -> Unit,
    hideScreenshots: Boolean,
    onToggleHideScreenshots: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    ToadGlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The brand badge + wordmark (the prototype's `t` tile).
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "t",
                        color = palette.onPrimary,
                        style = TextStyle(
                            fontFamily = ToadDisplayFont,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "toadGALLERY",
                    color = palette.textMain,
                    style = TextStyle(
                        fontFamily = ToadDisplayFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.3).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                // The quick layout toggle (Grid <-> Collage).
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(palette.bgSurfaceHigh)
                        .clickable(onClick = onToggleLayout),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (layoutIsCollage) Icons.Filled.GridView else Icons.Filled.Apps,
                        contentDescription = if (layoutIsCollage) {
                            "Switch to uniform grid"
                        } else {
                            "Switch to aspect collage"
                        },
                        tint = palette.textMain,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                // The Clean Feed / No Screenshots declutter pill.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (hideScreenshots) palette.primaryContainer else palette.bgSurfaceHigh,
                        )
                        .border(
                            width = 1.dp,
                            color = if (hideScreenshots) palette.primary else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(50),
                        )
                        .clickable(onClick = onToggleHideScreenshots)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (hideScreenshots) Icons.Filled.Smartphone else Icons.Filled.ViewDay,
                        contentDescription = null,
                        tint = if (hideScreenshots) palette.onPrimaryContainer else palette.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (hideScreenshots) "No Screenshots" else "Clean Feed",
                        color = if (hideScreenshots) palette.onPrimaryContainer else palette.textMuted,
                        style = TextStyle(
                            fontFamily = ToadBodyFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The prototype's floating date scrubber: a right-edge vertical thumb
 * (3dp-wide, 48dp-tall `primary` capsule) with a floating `primary` pill
 * showing the active month/year while the timeline scrolls or the thumb
 * drags, fading out [ToadMotionSpec.SCRUBBER_IDLE_MS] after the
 * interaction goes idle.
 *
 * The month label comes from the caller ([monthLabel]); the scrubber is
 * display-only chrome over the grid (the timeline itself is the scroll
 * source of truth --- a drag maps the thumb's ABSOLUTE position onto the
 * grid's scroll fraction through [onScrub]: the thumb IS the position
 * indicator, the prototype's `cursor-ns-resize` track semantics).
 *
 * r76 polish: the thumb no longer paints a FIXED 120dp-tall touch slab
 * centered in a 40dp column --- it tracks the drag's absolute Y inside
 * the scrubber column (the prototype's `justify-center` track), and the
 * drag consumes its changes so a scrub never simultaneously scrolls the
 * grid beneath the finger (the stale-`row_`-key fallback keys made the
 * old fraction->index math land on wrong months; the caller now derives
 * the label from the first visible MEDIA row and maps the fraction onto
 * the grid's own item count).
 */
@Composable
fun ToadDateScrubber(
    monthLabel: String,
    visible: Boolean,
    onScrub: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    Box(
        modifier = modifier
            .width(40.dp)
            .padding(end = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ToadMotionSpec.TOAST_MS)),
            exit = fadeOut(tween(ToadMotionSpec.TOAST_MS)),
        ) {
            Text(
                text = monthLabel,
                color = palette.onPrimary,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.primary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // The drag track: the whole column height is the gesture surface
        // (the prototype's full-height `justify-center` track). The visual
        // thumb is a STATIC centered capsule --- the prototype's handle ---
        // while the drag's absolute Y maps onto the scroll fraction; the
        // month pill narrates the position. Consume the changes so the grid
        // underneath never scrolls in the same gesture.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .draggableVertical { fraction -> onScrub(fraction) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.primary.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * The vertical-drag helper behind the scrubber thumb: reports the drag's
 * ABSOLUTE position as a `0f..1f` fraction of the track height (the
 * thumb-position-is-the-target semantics), consuming every change so the
 * gesture never leaks into the grid's scroll beneath.
 */
private fun Modifier.draggableVertical(onFraction: (Float) -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    // A touch-down anywhere on the track jumps the position
                    // there first (the prototype's track semantics).
                    onFraction(
                        (offset.y / size.height.toFloat()).coerceIn(0f, 1f),
                    )
                },
            ) { change, _ ->
                change.consume()
                val fraction = (change.position.y / size.height.toFloat())
                    .coerceIn(0f, 1f)
                onFraction(fraction)
            }
        },
    )
