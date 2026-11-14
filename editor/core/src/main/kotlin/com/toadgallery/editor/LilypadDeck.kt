package com.toadgallery.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Lilypad Deck: the caller-owned three-state bottom sheet (peek/half/full)
// replacing the fixed panel window; math in LilypadDeckMath, one spring.

/**
 * Height used when the chassis hands the Deck an unbounded height constraint
 * (keeps the sheet sane instead of NaN arithmetic eating the spring).
 */
private const val DECK_UNBOUNDED_FALLBACK_HEIGHT_DP = 640

/**
 * The three-state bottom sheet. [state] is caller-owned — every interaction
 * reports through [onStateChange]; [content] hosts in the sheet's scrolling
 * column (16/8dp padding, 4dp spacing), so panels of any height work. Springs
 * to [LilypadDeckMath]'s target for [state]; the handle carries tap/drag + a11y.
 */
@Composable
internal fun LilypadDeck(
    state: LilypadDeckState,
    onStateChange: (LilypadDeckState) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val densityScale = density.density
    // Fresh-at-read refs: the snap judges the CURRENT state without restarting
    // the gesture detector mid-drag.
    val currentState by rememberUpdatedState(state)
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    // Handle drag accumulator: session-local, reset at every drag end/cancel.
    var dragAccumulated by remember { mutableStateOf(0f) }
    // Handle a11y name, resolved outside semantics{} (stringResource is
    // composable): the label itself announces the state.
    val handleA11y = stringResource(
        if (state == LilypadDeckState.Collapsed) {
            R.string.editor_r67_deck_handle_expand
        } else {
            R.string.editor_r67_deck_handle_collapse
        },
    )
    val padColor = MaterialTheme.colorScheme.background
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val pillColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    BoxWithConstraints {
        // Unbounded height falls back to 640dp (DECK_UNBOUNDED_FALLBACK_HEIGHT_DP).
        val maxHeightPx = with(density) {
            if (constraints.hasBoundedHeight) {
                maxHeight.toPx()
            } else {
                DECK_UNBOUNDED_FALLBACK_HEIGHT_DP.dp.toPx()
            }
        }
        val targetHeight = with(density) {
            LilypadDeckMath.targetHeightPx(state, maxHeightPx, densityScale).toDp()
        }
        val animatedHeight by animateDpAsState(
            targetValue = targetHeight,
            animationSpec = spring(
                dampingRatio = LilypadTokens.MOTION_SHEET_DAMPING,
                stiffness = LilypadTokens.MOTION_SHEET_STIFFNESS,
            ),
            label = "deckHeight",
        )
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(animatedHeight),
            color = padColor,
            shape = RoundedCornerShape(
                topStart = LilypadTokens.SHEET_CORNER_DP.dp,
                topEnd = LilypadTokens.SHEET_CORNER_DP.dp,
            ),
        ) {
            // Hairline draws INSIDE the Surface (clipped by the corners): a
            // drawBehind on the Surface's modifier chain would paint under the
            // background and vanish.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawLine(
                            color = hairline,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            ) {
                // 1. The handle: full-width 48dp touch-target band (visual is
                // the 32x4dp pill); tap toggles, drag snaps one step on release.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(
                            enabled = true,
                            role = Role.Button,
                            onClick = {
                                currentOnStateChange(LilypadDeckMath.tapToggle(currentState))
                            },
                        )
                        // Keyed on density scale alone: state changes flow
                        // through currentState without breaking a gesture.
                        .pointerInput(densityScale) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    dragAccumulated += dragAmount
                                },
                                onDragEnd = {
                                    currentOnStateChange(
                                        LilypadDeckMath.snapAfterDrag(
                                            currentState,
                                            dragAccumulated,
                                            densityScale,
                                        ),
                                    )
                                    dragAccumulated = 0f
                                },
                                onDragCancel = { dragAccumulated = 0f },
                            )
                        }
                        // a11y name (state-dependent); role + action ride on the clickable above.
                        .semantics { contentDescription = handleA11y },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(pillColor),
                    )
                }
                // 2. The content: scrolling panel surface. In the Collapsed peek
                // only the handle + a sliver show (intended); weight(1f) hands
                // the scroll whatever height the current state leaves.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    content()
                }
            }
        }
    }
}
