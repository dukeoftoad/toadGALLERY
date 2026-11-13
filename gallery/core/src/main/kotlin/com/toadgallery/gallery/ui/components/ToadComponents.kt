package com.toadgallery.gallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.gallery.R
import com.toadgallery.gallery.ui.theme.ToadTheme
import com.toadgallery.gallery.ui.theme.ToadThemePalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay

/**
 * The r76 redesign's TYPE SYSTEM --- the prototype's two Google families,
 * bundled as OFL static TTFs (res/font/): Plus Jakarta Sans (600/700/800)
 * for display/heading text, Inter (400/500/600/700) for everything else.
 *
 * A [FontFamily] per weight set lets the small expressive typography
 * (10-24sp, per-component) pick exact weights without a Typography
 * overhaul --- the redesign's components specify their own styles inline
 * (the prototype's exact `text-[Npx] font-*` Tailwind classes), while
 * library widgets keep [com.toadgallery.gallery.ui.theme.ToadTypography]'s
 * baseline for the roles they already use.
 */
val ToadDisplayFont = FontFamily(
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

val ToadBodyFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Eyebrow style: `10sp` bold uppercase --- the sub-page headers' "SETTINGS". */
fun toadEyebrowStyle(color: Color) = TextStyle(
    fontFamily = ToadBodyFont,
    fontWeight = FontWeight.Bold,
    fontSize = 10.sp,
    letterSpacing = 1.2.sp,
    color = color,
)

/** Title style: `18sp` ExtraBold display --- the sub-page headers' titles. */
fun toadTitleStyle(color: Color) = TextStyle(
    fontFamily = ToadDisplayFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 18.sp,
    color = color,
)

/** The prototype's motion easing + durations, named for reuse. */
object ToadMotionSpec {
    /** The prototype's `cubic-bezier(0.2, 0.9, 0.3, 1.1)` switch spring. */
    val ExpressiveEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.1f)

    /** Switch thumb/track transition (250ms, the CSS `transition: all`). */
    const val SWITCH_MS = 250

    /** View enter (fadeSlideUp 240ms). */
    const val VIEW_ENTER_MS = 240

    /** Sub-page slide (slideInRight 220ms). */
    const val SUBPAGE_MS = 220

    /** Toast settle/hide (300ms). */
    const val TOAST_MS = 300

    /** Date-scrubber fade-out delay after scroll idle (900ms). */
    const val SCRUBBER_IDLE_MS = 900L
}

/**
 * The M3 Expressive switch, pixel-matched to the prototype's `.m3-switch`:
 * a `48x28dp` CircleShape track (`bgSurfaceHighest` + `2dp borderStrong`
 * when off, `primary` when on) with a spring-animated thumb (`16dp`
 * `textMuted` at `4dp` offset when off -> `20dp` `onPrimary` at `22dp`
 * offset when on).
 *
 * NOT a Material `Switch` --- the prototype's geometry is its own, and the
 * custom track/thumb pair reproduces it exactly (M3's Switch would impose
 * the library's padding, colors and thumb-inset conventions).
 */
@Composable
fun ToadExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    val thumbSize by animateFloatAsState(
        targetValue = if (checked) 20f else 16f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 600f,
        ),
        label = "thumbSize",
    )
    val thumbX by animateFloatAsState(
        targetValue = if (checked) 22f else 4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 600f,
        ),
        label = "thumbX",
    )
    // The prototype's exact top offsets: 4dp unchecked, 2dp checked.
    val thumbY by animateFloatAsState(
        targetValue = if (checked) 2f else 4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 600f,
        ),
        label = "thumbY",
    )
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) palette.primary else palette.bgSurfaceHighest)
            .border(
                width = 2.dp,
                color = if (checked) palette.primary else palette.borderStrong,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.dp.roundToPx(), thumbY.dp.roundToPx()) }
                .size(thumbSize.dp)
                .clip(CircleShape)
                .background(if (checked) palette.onPrimary else palette.textMuted),
        )
    }
}

/**
 * A frosted-glass container: `bgGlass` fill over a `hazeChild` backdrop
 * blur, with an optional hairline border. THE glass recipe every sticky
 * header, the bottom navigation bar and the map's floating pills use ---
 * one definition so blur radius/tint/border never drift between surfaces.
 *
 * [hazeState] is optional: pass the shell's shared state to blur real
 * content beneath (the scrollable sits behind); without it the surface
 * still renders the translucent tint (an honest fallback --- blur needs
 * a backing haze source in the same hierarchy).
 */
@Composable
fun ToadGlassSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    border: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = ToadTheme.palette
    val base = if (hazeState != null) {
        Modifier.hazeChild(state = hazeState)
    } else {
        Modifier
    }
    Box(
        modifier = base
            .background(palette.bgGlass)
            .then(
                if (border) {
                    Modifier.border(1.dp, palette.borderSubtle, RectangleShape)
                } else {
                    Modifier
                },
            )
            .then(modifier),
    ) {
        content()
    }
}

/**
 * A settings SECTION card: `RoundedCornerShape(24.dp)`, `bgSurface` fill,
 * `1dp borderSubtle` stroke --- the prototype's `.rounded-3xl` groups.
 * Hosts [ToadSectionRow]s; each row draws its own top hairline via
 * [ToadSectionRow.topDivider] (pass `false` on the FIRST row).
 */
@Composable
fun ToadSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = ToadTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(palette.bgSurface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(24.dp)),
    ) {
        content()
    }
}

/**
 * One row inside a [ToadSectionCard]: a full-width content block with the
 * card's standard `14dp/12dp` row padding and an optional `1dp`
 * `borderSubtle` TOP divider (the prototype's `.divide-y`; `false` for the
 * card's first row). [backgroundColor] covers the highlighted drill-down
 * rows (`bgSurfaceHigh`).
 */
@Composable
fun ToadSectionRow(
    topDivider: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val palette = ToadTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (backgroundColor != null) Modifier.background(backgroundColor) else Modifier)
            .then(
                if (topDivider) {
                    Modifier.border(width = 1.dp, color = palette.borderSubtle)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/**
 * The sub-page sticky header: frosted glass, a `36dp` circular back button
 * (`bgSurface` + `borderSubtle`), the uppercase `10sp` `primary` eyebrow
 * ("SETTINGS"), an `18sp` ExtraBold title, and an optional trailing status
 * pill (`bgSurfaceHigh`) --- the prototype's `renderSubPageHeader` verbatim.
 */
@Composable
fun ToadSubPageTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = "SETTINGS",
    statusPill: String? = null,
    hazeState: HazeState? = null,
) {
    val palette = ToadTheme.palette
    ToadGlassSurface(modifier = modifier.fillMaxWidth(), hazeState = hazeState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.bgSurface)
                    .border(1.dp, palette.borderSubtle, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.textMain,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = eyebrow, style = toadEyebrowStyle(palette.primary), maxLines = 1)
                Text(
                    text = title,
                    style = toadTitleStyle(palette.textMain),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (statusPill != null) {
                ToadStatusPill(text = statusPill)
            }
        }
    }
}

/**
 * The trailing status pill (`bgSurfaceHigh`, `10sp` bold, `textMuted`) ---
 * the sub-page headers' "Material 3" / "Encrypted" chips.
 */
@Composable
fun ToadStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color? = null,
    textColor: Color? = null,
) {
    val palette = ToadTheme.palette
    Text(
        text = text,
        color = textColor ?: palette.textMuted,
        style = TextStyle(
            fontFamily = ToadBodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        ),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background ?: palette.bgSurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The floating toast banner: a pill (`RoundedCornerShape(16.dp)`,
 * `bgSurfaceHighest`, `1dp borderStrong`, `primary` sparkle icon + `12sp`
 * Medium message) that fades/slides in above the bottom bar and auto-hides
 * after 2.4s --- the prototype's `#toast-banner` + `showToast` verbatim.
 *
 * [ToadToastHost] (below) owns the state; this is the dumb view.
 */
@Composable
fun ToadToastBanner(
    message: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ToadMotionSpec.TOAST_MS)) +
            slideInVertically(tween(ToadMotionSpec.TOAST_MS)) { it / 2 },
        exit = fadeOut(tween(ToadMotionSpec.TOAST_MS)) +
            slideOutVertically(tween(ToadMotionSpec.TOAST_MS)) { it / 2 },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(palette.bgSurfaceHighest)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The toast state holder the shell renders once --- `rememberToast()`
 * gives any descendant a `show(message)` lambda through a simple holder
 * object; the 2.4s auto-hide timer is keyed to a monotonic display
 * GENERATION (incremented by every `show` call) instead of the message
 * text --- two toasts with the same message back-to-back, or a
 * recomposition of an unrelated state read, can never restart or skip
 * the timer (the prototype's `clearTimeout(toastTimer)` discipline).
 */
class ToadToastState {
    internal var message by mutableStateOf("")
    internal var visible by mutableStateOf(false)
    internal var generation by mutableIntStateOf(0)

    fun show(text: String) {
        message = text
        visible = true
        generation += 1
    }
}

/** Creates the [ToadToastState] and starts its auto-hide loop. */
@Composable
fun rememberToadToast(): ToadToastState {
    val state = remember { ToadToastState() }
    LaunchedEffect(state.generation) {
        if (state.visible) {
            delay(2400)
            state.visible = false
        }
    }
    return state
}

/**
 * The horizontal edge-fade mask the prototype applies to every chip strip
 * (`.chip-scroll-mask`: `linear-gradient(to right, black 88%, transparent
 * 100%)`): drawn as a DST-OUT gradient so the trailing 12% of the strip
 * DISSOLVES the content underneath --- chips scrolling out fade instead
 * of clipping sharply.
 *
 * r76.1: the DstOut blend runs under [CompositingStrategy.Offscreen] ---
 * without an offscreen layer some GPU paths render the destination-erase
 * as a solid black slab over the strip's trailing edge (the field report's
 * black box riding the Photos filter row).
 */
fun Modifier.toadTrailingFade(): Modifier = this.graphicsLayer(
    compositingStrategy = CompositingStrategy.Offscreen,
).drawWithContent {
    drawContent()
    val fadeStart = size.width * 0.88f
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startX = fadeStart,
            endX = size.width,
        ),
        blendMode = BlendMode.DstOut,
    )
}

/**
 * A rounded icon well (the settings rows' leading 36-40dp containers).
 */
@Composable
fun ToadIconWell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    cornerRadius: Dp = 12.dp,
    background: Color? = null,
    tint: Color? = null,
) {
    val palette = ToadTheme.palette
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(background ?: palette.bgSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: palette.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * The pulsing halo the map's selected pin carries (the prototype's
 * `.pin-pulse::before` keyframes: scale 0.95->1.4, opacity 0.7->0, 2.2s
 * infinite). Rendered BEHIND the pin by the caller (Box with this first).
 */
@Composable
fun ToadPulsingHalo(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pinPulse")
    val scale by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "haloScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "haloAlpha",
    )
    val palette = ToadTheme.palette
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(palette.primary, CircleShape),
    )
}

/**
 * A pill-shaped action button (the prototype's `rounded-full` CTAs):
 * filled (`primary`/`onPrimary`) or outlined (`borderStrong`/`textMain`).
 */
@Composable
fun ToadPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
) {
    val palette = ToadTheme.palette
    val container = when {
        danger && filled -> palette.dangerBg
        danger -> Color.Transparent
        filled -> palette.primary
        else -> Color.Transparent
    }
    val contentColor = when {
        danger -> palette.dangerText
        filled -> palette.onPrimary
        else -> palette.textMain
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(
                if (!filled) {
                    Modifier.border(
                        1.dp,
                        if (danger) palette.dangerText else palette.borderStrong,
                        RoundedCornerShape(50),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = contentPadding, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The r77.7 CHIP VOCABULARY --- one answer to "what does a selectable chip
 * look like", transcribed from the prototype's filter/prompt chip markup:
 *
 *  - **selected**: a solid `primary` pill with an `onPrimary` label (the
 *    prototype's `background: var(--primary); color: var(--on-primary)`),
 *    no stroke (the fill IS the edge --- the prototype's border-color
 *    equals the fill);
 *  - **unselected**: a `bgSurface` pill with a `textMuted` label and a
 *    `1dp borderSubtle` stroke (the prototype's `background:
 *    var(--bg-surface); color: var(--text-muted); border-color:
 *    var(--border-subtle)`).
 *
 * The stock Material 3 [androidx.compose.material3.FilterChip] paints the
 * selected state with `secondaryContainer`/`onSecondaryContainer` --- on
 * the toad palettes a near-black green pill whose label barely clears the
 * fill --- which is exactly the divergence this vocabulary retires. The
 * M3 COMPONENT stays (ripple, semantics, the 48dp touch floor through
 * `LocalMinimumInteractiveComponentSize`); only the color/border slots
 * are re-pointed at the palette.
 *
 * [toadChipColors] is the PURE mapping (JVM-pinned by `ToadChipColorsTest`
 * for every palette); [ToadChipDefaults] adapts it to the M3 chip slots at
 * composition time.
 */
data class ToadChipColors(
    val container: Color,
    val content: Color,
    /** `null` when the fill carries the edge (the selected pill). */
    val stroke: Color?,
)

/** The pure chip-vocabulary mapping for one palette + selection state. */
fun toadChipColors(palette: ToadThemePalette, selected: Boolean): ToadChipColors =
    if (selected) {
        ToadChipColors(
            container = palette.primary,
            content = palette.onPrimary,
            stroke = null,
        )
    } else {
        ToadChipColors(
            container = palette.bgSurface,
            content = palette.textMuted,
            stroke = palette.borderSubtle,
        )
    }

/**
 * The M3 chip-slot adapter over [toadChipColors]: feed [ToadChipDefaults]
 * to any `FilterChip` and both of its states render the prototype's
 * vocabulary (the chip's own `selected` state picks the pair, so call
 * sites never duplicate the selection truth).
 */
object ToadChipDefaults {

    /** The container/label colors for BOTH chip states. */
    @Composable
    fun colors(): SelectableChipColors {
        val palette = ToadTheme.palette
        return FilterChipDefaults.filterChipColors(
            containerColor = toadChipColors(palette, selected = false).container,
            labelColor = toadChipColors(palette, selected = false).content,
            selectedContainerColor = toadChipColors(palette, selected = true).container,
            selectedLabelColor = toadChipColors(palette, selected = true).content,
        )
    }

    /** The `1dp borderSubtle` stroke while unselected; none while selected. */
    @Composable
    fun border(): BorderStroke? {
        val stroke = toadChipColors(ToadTheme.palette, selected = false).stroke
        return stroke?.let { BorderStroke(1.dp, it) }
    }
}
