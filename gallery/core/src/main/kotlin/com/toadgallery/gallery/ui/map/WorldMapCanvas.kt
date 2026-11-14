package com.toadgallery.gallery.ui.map

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.geo.EquirectangularProjection
import com.toadgallery.gallery.data.geo.EquirectangularViewport
import com.toadgallery.gallery.data.geo.GeoOutline
import com.toadgallery.gallery.data.geo.MapPoint
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.theme.ToadTheme
import kotlin.math.roundToInt

/**
 * The Places Map canvas --- the r77.6 prototype conversion of the r16
 * renderer. The honest offline cartography is UNCHANGED (the bundled
 * outline's landmasses + the graticule inside the FIXED whole-world
 * equirectangular viewport, no tiles, no network --- ADR-0014), but the
 * surface now wears the prototype's vector-map treatment and hosts its
 * THUMBNAIL PINS:
 *
 *  - The backdrop art carries the prototype SVG's `opacity-55`, with the
 *    landmass fill / coastline / graticule tinted by the palette's
 *    `primary` and `textDim` (the prototype tints its whole map scene
 *    with the accent too --- river `primary/0.22`, roads
 *    `primary/0.35`, dashed roads `textDim/0.45`). The graticule draws
 *    with the prototype's dashed-road dash pattern (`6 4`).
 *  - PINS: one 48dp rounded photo thumbnail per grid-bucket cluster
 *    (24x12 cells --- the clustering math is unchanged, ADR-0016
 *    JVM-pinned), framed by a 2dp border (`bgSurfaceHighest` + white,
 *    the prototype's unselected pin), carrying the cluster's count as
 *    the badge chip at its top-right corner (`primary` fill, `onPrimary`
 *    `9sp` ExtraBold --- the prototype's `-top-2 -right-2` badge).
 *  - SELECTION: the pin of the cluster holding [selectedKey] scales to
 *    1.15x (`scale-115`) behind a 2.2s infinite pulse ring (the
 *    prototype's `pulseRing` keyframes: scale 0.95 -> 1.4, opacity
 *    0.7 -> 0 at the 70% mark), and sits above its siblings.
 *  - HEATMAP: the pins step aside for the prototype's multi-color glow
 *    blobs --- radial gradients that run red -> amber for the DENSEST
 *    cluster and green -> blue for the rest (the prototype's two
 *    `.blur-2xl` blobs, vectorized; the thermal hues are semantic, not
 *    theme chrome).
 *
 * INTERACTION: each pin is its own clickable (the r16 canvas-level
 * hit-test is gone --- a tap on a pin hands the cluster's member rows
 * to [onClusterTapped]; the r77.6 screen uses that to SELECT, and the
 * memory card below opens the viewer window). No zoom, no pan in v1
 * (docs/phase-4/on-device-map.md).
 *
 * THE MATH stays pure: [EquirectangularProjection] /
 * [EquirectangularViewport] convert units, [MapClusterGrid] clusters
 * (and still sizes the heatmap glows); this composable only positions
 * and draws.
 *
 * HONEST DEGRADATION: [outline] `null` (asset unreadable) still draws
 * the graticule and the pins --- "no cartography" beats "no map".
 */
@Composable
fun WorldMapCanvas(
    outline: GeoOutline?,
    rows: List<CatalogueLocationRow>,
    clusters: List<MapCluster>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    heatmap: Boolean = false,
    /** The selected row's stable key; its cluster's pin pulses. */
    selectedKey: String? = null,
    onClusterTapped: (List<CatalogueLocationRow>) -> Unit,
) {
    val palette = ToadTheme.palette

    // The canvas size, observed once per layout pass; the viewport (and
    // everything derived from it) rebuilds only when it moves.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val viewport = remember(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            EquirectangularViewport(
                widthPx = canvasSize.width.toDouble(),
                heightPx = canvasSize.height.toDouble(),
            )
        } else {
            null
        }
    }

    // Landmass paths: rebuilt only when the outline or the viewport
    // changes; one Path per exterior ring, positions projected and
    // converted to canvas pixels.
    val landPaths = remember(outline, viewport) {
        if (outline == null || viewport == null) {
            emptyList()
        } else {
            outline.polygons.map { ring ->
                Path().apply {
                    for ((index, position) in ring.withIndex()) {
                        val point = viewport.toScreen(EquirectangularProjection.project(position))
                        if (index == 0) {
                            moveTo(point.x.toFloat(), point.y.toFloat())
                        } else {
                            lineTo(point.x.toFloat(), point.y.toFloat())
                        }
                    }
                    close()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("map_canvas")
            // TalkBack: the pins carry per-cluster descriptions (the
            // counts), the canvas describes the surface itself.
            .semantics { this.contentDescription = contentDescription }
            .onSizeChanged { canvasSize = it },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vp = viewport ?: return@Canvas
            val mapTopLeft = Offset(vp.left.toFloat(), vp.top.toFloat())
            val mapSize = Size(vp.mapWidth.toFloat(), vp.mapHeight.toFloat())

            // 1. Landmasses: the parsed exterior rings, tinted like the
            //    prototype's vector scene (primary fill, primary coast)
            //    under the SVG's 55% opacity. The ocean is the card's
            //    `bgSurface` showing through --- no separate fill.
            for (path in landPaths) {
                drawPath(
                    path = path,
                    color = palette.primary.copy(alpha = LAND_FILL_ALPHA * BACKDROP_OPACITY),
                )
                drawPath(
                    path = path,
                    color = palette.primary.copy(alpha = LAND_COAST_ALPHA * BACKDROP_OPACITY),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // 2. Graticule: the 30-degree grid, drawn as the prototype's
            //    dashed roads (textDim, `6 4` dash), inside the map rect.
            val graticule = palette.textDim.copy(alpha = GRATICULE_ALPHA * BACKDROP_OPACITY)
            val dash = PathEffect.dashPathEffect(
                floatArrayOf(GRATICULE_DASH_ON.dp.toPx(), GRATICULE_DASH_OFF.dp.toPx()),
            )
            val strokeWidth = 1.dp.toPx()
            var longitude = -150.0
            while (longitude <= 150.0) {
                val x = vp.toScreen(EquirectangularProjection.project(longitude, 0.0)).x
                drawLine(
                    color = graticule,
                    start = Offset(x.toFloat(), mapTopLeft.y),
                    end = Offset(x.toFloat(), mapTopLeft.y + mapSize.height),
                    strokeWidth = strokeWidth,
                    pathEffect = dash,
                )
                longitude += GRATICULE_STEP_DEGREES
            }
            var latitude = -60.0
            while (latitude <= 60.0) {
                val y = vp.toScreen(EquirectangularProjection.project(0.0, latitude)).y
                drawLine(
                    color = graticule,
                    start = Offset(mapTopLeft.x, y.toFloat()),
                    end = Offset(mapTopLeft.x + mapSize.width, y.toFloat()),
                    strokeWidth = strokeWidth,
                    pathEffect = dash,
                )
                latitude += GRATICULE_STEP_DEGREES
            }

            // 3. HEATMAP mode: the prototype's glow blobs. The DENSEST
            //    cluster burns red -> amber, the rest glow green ->
            //    blue; radius still scales with the pure dot-radius
            //    formula. (Pins are hidden in this mode --- the glow IS
            //    the density signal, exactly like the prototype's
            //    `pointer-events-none` blobs.)
            if (heatmap && clusters.isNotEmpty()) {
                val hottest = clusters.maxOf { it.count }
                for (cluster in clusters) {
                    val anchor = vp.toScreen(MapPoint(cluster.x, cluster.y))
                    val center = Offset(anchor.x.toFloat(), anchor.y.toFloat())
                    val glow = MapClusterGrid.dotRadiusDp(cluster.count).dp.toPx() * HEAT_RADIUS_FACTOR
                    val colors = if (cluster.count == hottest) HEAT_HOT else HEAT_COOL
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to colors[0].copy(alpha = HEAT_CORE_ALPHA),
                            0.5f to colors[1].copy(alpha = HEAT_MID_ALPHA),
                            HEAT_EDGE_STOP to Color.Transparent,
                            center = center,
                            radius = glow,
                        ),
                        center = center,
                        radius = glow,
                    )
                }
            }
        }

        // 4. The thumbnail pins (PINS mode only): one per cluster, at
        //    its mean anchor, keyed by the representative row's stable
        //    key so catalogue reloads don't rebuild every pin's state.
        if (!heatmap && viewport != null) {
            val selectedClusterIndex = if (selectedKey == null) {
                -1
            } else {
                clusters.indexOfFirst { cluster ->
                    cluster.memberIndexes.any { rows.getOrNull(it)?.localMediaKey == selectedKey }
                }
            }
            clusters.forEachIndexed { index, cluster ->
                val anchor = viewport.toScreen(MapPoint(cluster.x, cluster.y))
                key(rows[cluster.memberIndexes.first()].localMediaKey) {
                    MapThumbnailPin(
                        anchor = Offset(anchor.x.toFloat(), anchor.y.toFloat()),
                        model = rows[cluster.memberIndexes.first()].canonicalUri,
                        count = cluster.count,
                        selected = index == selectedClusterIndex,
                        pinDescription = pluralStringResource(
                            R.plurals.map_sheet_item_count,
                            cluster.count,
                            cluster.count,
                        ),
                        onClick = { onClusterTapped(cluster.memberIndexes.map { rows[it] }) },
                    )
                }
            }
        }
    }
}

/**
 * One geotagged photo pin (the prototype's clustered thumbnail button):
 * a 48dp rounded-2xl frame (`bgSurfaceHighest` + 2dp white border,
 * unselected) holding the representative row's thumbnail, the count
 * badge chip at its top-right corner, and --- when [selected] --- the
 * 1.15x scale-up with the 2.2s `pulseRing` animation behind it. The
 * anchor is the cluster's projected position in PARENT pixels (the pin
 * centers itself on it).
 */
@Composable
private fun MapThumbnailPin(
    anchor: Offset,
    model: String,
    count: Int,
    selected: Boolean,
    pinDescription: String,
    onClick: () -> Unit,
) {
    val palette = ToadTheme.palette
    val scale by animateFloatAsState(
        targetValue = if (selected) PIN_SELECTED_SCALE else 1f,
        animationSpec = tween(durationMillis = PIN_SCALE_MS),
        label = "mapPinScale",
    )
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (anchor.x - PIN_SIZE_DP.dp.toPx() / 2f).roundToInt(),
                    (anchor.y - PIN_SIZE_DP.dp.toPx() / 2f).roundToInt(),
                )
            }
            .zIndex(if (selected) 2f else 1f)
            .size(PIN_SIZE_DP.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
            .semantics { contentDescription = pinDescription },
    ) {
        if (selected) {
            // The prototype's `.pin-pulse::before` ring: a primary
            // circle behind the pin, scaling 0.95 -> 1.4 with opacity
            // 0.7 -> 0 across the 2.2s cycle (the opacity hits zero at
            // the 70% keyframe and stays there).
            val pulse by rememberInfiniteTransition(label = "mapPinPulse")
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = PIN_PULSE_MS, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "mapPinPulseProgress",
                )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val ringScale = PIN_PULSE_SCALE_FROM +
                            (PIN_PULSE_SCALE_TO - PIN_PULSE_SCALE_FROM) * pulse
                        scaleX = ringScale
                        scaleY = ringScale
                        alpha = PIN_PULSE_ALPHA_FROM *
                            (1f - (pulse / PIN_PULSE_FADE_MARK).coerceIn(0f, 1f))
                    }
                    .clip(CircleShape)
                    .background(palette.primary),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) palette.primary else palette.bgSurfaceHighest)
                .border(
                    width = 2.dp,
                    color = if (selected) palette.primary else Color.White,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(2.dp),
        ) {
            AsyncImage(
                model = Uri.parse(model),
                contentDescription = null, // the pin's semantics carry the count
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        if (count > 1) {
            Text(
                text = count.toString(),
                color = palette.onPrimary,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp,
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** The prototype SVG's whole-scene opacity (`opacity-55`). */
private const val BACKDROP_OPACITY = 0.55f

/** Landmass fill: the prototype river blob's `primary` at 0.22. */
private const val LAND_FILL_ALPHA = 0.22f

/** Coastline stroke: the prototype arterial roads' `primary` at 0.35. */
private const val LAND_COAST_ALPHA = 0.35f

/** Graticule: the prototype dashed roads' `textDim` at 0.45. */
private const val GRATICULE_ALPHA = 0.45f

/** The prototype dashed-road dash pattern (`stroke-dasharray: 6 4`). */
private const val GRATICULE_DASH_ON = 6
private const val GRATICULE_DASH_OFF = 4

/** Graticule line spacing in degrees (meridians and parallels alike). */
private const val GRATICULE_STEP_DEGREES = 30.0

/** The pin's frame size, in dp (the prototype's `w-12 h-12`). */
private const val PIN_SIZE_DP = 48

/** The selected pin's scale (the prototype's `scale-115`). */
private const val PIN_SELECTED_SCALE = 1.15f

/** The selected pin's scale transition (the prototype's `duration-300`). */
private const val PIN_SCALE_MS = 300

/** The pulse ring cycle (the prototype's `pulseRing 2.2s`). */
private const val PIN_PULSE_MS = 2200

/** The pulse ring's scale range (the prototype keyframes 0.95 -> 1.4). */
private const val PIN_PULSE_SCALE_FROM = 0.95f
private const val PIN_PULSE_SCALE_TO = 1.4f

/** The pulse ring's starting opacity (the prototype's 0.7). */
private const val PIN_PULSE_ALPHA_FROM = 0.7f

/** Where the pulse ring's opacity reaches zero (the 70% keyframe). */
private const val PIN_PULSE_FADE_MARK = 0.7f

/** The heatmap glow's radius factor over the pure dot-radius formula. */
private const val HEAT_RADIUS_FACTOR = 3f

/** The heatmap gradients (the prototype's two blob color ramps). */
private val HEAT_HOT = listOf(Color(0xFFEF4444), Color(0xFFF59E0B))
private val HEAT_COOL = listOf(Color(0xFF10B981), Color(0xFF3B82F6))

/** The heatmap blobs' core / mid alphas (the prototype's 0.55 / 0.45). */
private const val HEAT_CORE_ALPHA = 0.55f
private const val HEAT_MID_ALPHA = 0.45f

/** Where the heatmap gradient reaches transparent (the 75% stop). */
private const val HEAT_EDGE_STOP = 0.78f
