package io.github.dukeoftoad.gallery.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow
import io.github.dukeoftoad.gallery.data.geo.EquirectangularProjection
import io.github.dukeoftoad.gallery.data.geo.EquirectangularViewport
import io.github.dukeoftoad.gallery.data.geo.GeoOutline
import io.github.dukeoftoad.gallery.data.geo.MapPoint

/**
 * The offline world map renderer (r16 round): draws the bundled outline's
 * landmasses, a light graticule and the photo-location dots inside the
 * FIXED whole-world equirectangular viewport, theme-aware through the
 * Material color roles.
 *
 * WHERE THE MATH LIVES (ADR-0016): every decision here is a call into a
 * PURE, JVM-tested type --- [EquirectangularProjection] /
 * [EquirectangularViewport] (projection + letterboxing),
 * [MapClusterGrid] (bucket clustering, hit-testing, dot radii) --- and
 * this composable only converts units (normalized/pixel doubles ->
 * [Offset]/[Float] pixels) and issues draw calls. There is no zoom and
 * no pan in v1: the map letterboxes its 2:1 shape into whatever canvas
 * it gets (a portrait phone shows a full-width band), by design ---
 * docs/phase-4/on-device-map.md.
 *
 * THEME (docs/architecture/design-tokens.md): ocean/land/coastline/
 * graticule/dot colors come from the Material roles the theme maps onto
 * the toad palettes, so the map follows light/dark + dynamic color like
 * every other surface instead of hardcoding map-specific pigments.
 *
 * HONEST DEGRADATION: [outline] `null` (asset unreadable) still draws
 * the graticule and the dots --- "no cartography" beats "no map".
 *
 * INTERACTION (v1): one tap gesture. A tap inside a dot (or its generous
 * finger radius) hands the cluster's member rows to [onClusterTapped];
 * a tap in the letterbox band or the open ocean hits nothing.
 *
 * ZERO NETWORK (ADR-0014): no tile fetch, no URL, nothing online --- the
 * only "map data" is the bundled asset's parsed polygons.
 */
@Composable
fun WorldMapCanvas(
    outline: GeoOutline?,
    rows: List<CatalogueLocationRow>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClusterTapped: (List<CatalogueLocationRow>) -> Unit,
) {
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

    // Cluster in NORMALIZED space (pure math, size-independent); the
    // anchors convert to screen pixels at draw and hit-test time.
    val normalizedClusters = remember(rows) {
        MapClusterGrid.cluster(
            rows.map { EquirectangularProjection.project(it.longitude, it.latitude) },
        )
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

    // The theme roles the map consumes (read once per composition, so a
    // theme flip recomposes the draw; the toad palettes map these to the
    // warm-gray + green system, dynamic color included).
    val oceanColor = MaterialTheme.colorScheme.surface
    val landColor = MaterialTheme.colorScheme.secondaryContainer
    val coastlineColor = MaterialTheme.colorScheme.outlineVariant
    val graticuleColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    val dotLabelColor = MaterialTheme.colorScheme.onPrimary

    // The count badge paint (plain interop text on the native canvas ---
    // Compose Canvas has no text primitive). Created once; size and
    // color are set per draw because they depend on the current density
    // and theme.
    val countPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("map_canvas")
            // The canvas is the interactive surface; describe it for
            // TalkBack (the dots' counts ride the sheet instead).
            .semantics { this.contentDescription = contentDescription }
            .onSizeChanged { canvasSize = it }
            .pointerInput(viewport, normalizedClusters) {
                detectTapGestures { tap ->
                    val vp = viewport ?: return@detectTapGestures
                    // A letterbox-band tap is not a map tap.
                    if (vp.fromScreen(tap.x.toDouble(), tap.y.toDouble()) == null) {
                        return@detectTapGestures
                    }
                    // Hit-testing runs in PIXEL space (one unit of
                    // normalized x is twice one of y on the 2:1 map, so
                    // the finger radius is only round here): convert the
                    // anchors, keep the member indexes, ask the pure
                    // grid which cluster (if any) the tap caught.
                    val screenClusters = normalizedClusters.map { cluster ->
                        val anchor = vp.toScreen(MapPoint(cluster.x, cluster.y))
                        cluster.copy(x = anchor.x, y = anchor.y)
                    }
                    val hit = MapClusterGrid.clusterAt(
                        clusters = screenClusters,
                        x = tap.x.toDouble(),
                        y = tap.y.toDouble(),
                        radius = CLUSTER_TAP_RADIUS_DP.dp.toPx().toDouble(),
                    )
                    if (hit != null) {
                        onClusterTapped(hit.memberIndexes.map { rows[it] })
                    }
                }
            },
    ) {
        val vp = viewport ?: return@Canvas
        val mapTopLeft = Offset(vp.left.toFloat(), vp.top.toFloat())
        val mapSize = Size(vp.mapWidth.toFloat(), vp.mapHeight.toFloat())

        // 1. The ocean: the letterboxed map rect itself.
        drawRect(color = oceanColor, topLeft = mapTopLeft, size = mapSize)

        // 2. Graticule: meridians every 30 degrees, parallels every 30,
        // inside the map rect by construction (the projection maps the
        // whole grid into it).
        val graticule = graticuleColor.copy(alpha = 0.35f)
        val strokeWidth = 1.dp.toPx()
        var longitude = -150.0
        while (longitude <= 150.0) {
            val x = vp.toScreen(EquirectangularProjection.project(longitude, 0.0)).x
            drawLine(
                color = graticule,
                start = Offset(x.toFloat(), mapTopLeft.y),
                end = Offset(x.toFloat(), mapTopLeft.y + mapSize.height),
                strokeWidth = strokeWidth,
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
            )
            latitude += GRATICULE_STEP_DEGREES
        }

        // 3. Landmasses: the parsed exterior rings, filled and outlined.
        for (path in landPaths) {
            drawPath(path = path, color = landColor)
            drawPath(path = path, color = coastlineColor, style = Stroke(width = strokeWidth))
        }

        // 4. Photo dots: one circle per cluster at its mean anchor,
        // sized by the pure radius scale, with the member count labeled
        // on multi-photo clusters.
        for (cluster in normalizedClusters) {
            val anchor = vp.toScreen(MapPoint(cluster.x, cluster.y))
            val center = Offset(anchor.x.toFloat(), anchor.y.toFloat())
            val radius = MapClusterGrid.dotRadiusDp(cluster.count).dp.toPx()
            drawCircle(color = dotColor, radius = radius, center = center)
            if (cluster.count > 1) {
                countPaint.textSize = CLUSTER_LABEL_SP.sp.toPx()
                countPaint.color = dotLabelColor.toArgb()
                // Vertically center the glyphs: the baseline sits at
                // half the (ascent + descent) below the circle center.
                val baseline = center.y - (countPaint.ascent() + countPaint.descent()) / 2f
                drawContext.canvas.nativeCanvas.drawText(
                    cluster.count.toString(),
                    center.x,
                    baseline,
                    countPaint,
                )
            }
        }
    }
}

/** Graticule line spacing in degrees (meridians and parallels alike). */
private const val GRATICULE_STEP_DEGREES = 30.0

/** The finger-friendly tap radius around a dot's anchor, in dp. */
private const val CLUSTER_TAP_RADIUS_DP = 24

/** The count-badge text size, in sp. */
private const val CLUSTER_LABEL_SP = 10
