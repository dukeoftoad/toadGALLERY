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

// ---------------------------------------------------------------------------
// The r67 Lilypad foundation round (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md): the Deck --- the three-state bottom sheet
// that retires the r54 chassis's fixed panel window (the `heightIn(max =
// 300.dp)` scroll column in EditActivity; LilypadFlags.decksEnabled is the
// revert seam that swaps this in as the default).
//
// THE CHASSIS CONTRACT. One sheet, three states, the state CALLER-OWNED
// (EditorContent remembers it; this composable is otherwise stateless):
//
//   Collapsed --- a 64dp peek: the drag handle plus a sliver of the first
//                row, the photo unobstructed;
//   Half       --- the working surface, 45% of the available height;
//   Full       --- the immersive surface, 78% (Phase 2's curve deck; no
//                call site references it in Phase 1).
//
// The heights are pure arithmetic in LilypadDeckMath (JVM-pinned in
// LilypadDeckMathTest) and animate through ONE spring (damping 0.85,
// stiffness 380 --- LilypadTokens' sheet motion): there is no live
// finger-following this round, the spring carries the feel.
//
// THE SURFACE. The Pad color (colorScheme.background under the Lilypad
// palette) with 16dp top corners (LilypadTokens.SHEET_CORNER_DP) and a
// hairline top border in outlineVariant --- depth comes from lightness
// steps and hairlines, never shadows. The content scrolls vertically
// inside, so panels of any height work; the r54 column's 16/8dp padding
// and 4dp spacing ride verbatim (the panels' rhythm is unchanged).
//
// THE HANDLE. Tap toggles (LilypadDeckMath.tapToggle); a vertical drag
// accumulates and snaps ONE step past 48dp scaled to px on release
// (LilypadDeckMath.snapAfterDrag). The r32 a11y contract: the handle
// announces its NAME through a state-DEPENDENT contentDescription
// ("Expand tools" when collapsed, "Collapse tools" when half/full --- the
// label IS the state announcement, per strings_r67.xml) and carries its
// action through clickable's Role.Button.
// ---------------------------------------------------------------------------

/**
 * The height used when the chassis hands the Deck an unbounded height
 * constraint (a finite maxHeight is the norm in this chassis; the fallback
 * keeps the sheet sane inside, say, an unmeasured scroll host instead of
 * letting NaN arithmetic eat the spring.
 */
private const val DECK_UNBOUNDED_FALLBACK_HEIGHT_DP = 640

/**
 * The three-state bottom sheet (see the file header). [state] is caller-
 * owned --- every interaction reports through [onStateChange] --- and
 * [content] is hosted in the sheet's scrolling column (the r54 panel
 * rhythm: 16/8dp padding, 4dp spacing), so panels of any height work. The
 * sheet springs to [LilypadDeckMath]'s target for [state] with the
 * Lilypad sheet motion; the drag handle carries the tap/drag interactions
 * and the r32 a11y contract.
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
    // Fresh-at-read refs for the drag-end decision: the filmstrip
    // precedent (rememberUpdatedState over a stable pointerInput) --- the
    // snap must judge the CURRENT state without restarting the gesture
    // detector mid-drag, and a parent's newer lambda must be the one
    // called.
    val currentState by rememberUpdatedState(state)
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    // The handle drag accumulator: session-local UI state, reset at every
    // drag end/cancel (never recipe state, never caller-visible).
    var dragAccumulated by remember { mutableStateOf(0f) }
    // The handle's a11y NAME, resolved OUTSIDE any semantics{} lambda
    // (stringResource is composable; semantics{} is not): collapsed reads
    // expand, expanded reads collapse --- the label itself announces the
    // state (strings_r67.xml's contract).
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
        // The available height this sheet may occupy. The chassis gives a
        // finite height constraint; an unbounded one falls back to a sane
        // 640dp rather than NaN arithmetic.
        val maxHeightPx = with(density) {
            if (constraints.hasBoundedHeight()) {
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
            // The sheet's own column: the hairline top border, the handle,
            // and the scrolling content. The hairline draws here --- INSIDE
            // the Surface, over its background, clipped by the 16dp
            // corners --- because a drawBehind on the Surface's own
            // modifier chain would paint UNDER the surface background and
            // vanish.
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
                // 1. THE HANDLE: full-width, 48dp tall (the WCAG 2.5.5
                // touch-target floor, the r47 swatch precedent --- the
                // visual is still the quiet 32x4dp pill centered in the
                // band), tap toggles the working toggle; a vertical drag
                // accumulates and snaps one step on release.
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
                        // Keyed on the density scale alone: the snap
                        // threshold is density-scaled, so a configuration
                        // change must restart the detector with the fresh
                        // scale --- while state changes flow through
                        // currentState above without breaking a gesture.
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
                        // r32 a11y: the NAME (state-dependent --- see the
                        // file header); the button role and toggle action
                        // ride on the clickable above it.
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
                // 2. THE CONTENT: the scrolling panel surface --- the r54
                // column's padding and spacing, verbatim. In the Collapsed
                // peek only the handle plus a sliver of the first row show
                // (the intended peek, not a bug): weight(1f) hands the
                // scroll whatever height the sheet's current state leaves.
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
