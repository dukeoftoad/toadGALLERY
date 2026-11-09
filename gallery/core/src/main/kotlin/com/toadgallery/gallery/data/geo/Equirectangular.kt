package com.toadgallery.gallery.data.geo

/**
 * A point in the map's NORMALIZED square: [x] in [0,1] runs west -> east,
 * [y] in [0,1] runs NORTH -> SOUTH (screen convention, so projecting
 * never needs a separate flip at draw time).
 *
 * Produced only by [EquirectangularProjection.project]; consumed by the
 * renderer and by [com.toadgallery.gallery.ui.map.MapClusterGrid].
 */
data class MapPoint(
    /** Normalized west->east coordinate, 0 at -180 degrees. */
    val x: Double,
    /** Normalized north->south coordinate, 0 at the north pole. */
    val y: Double,
)

/**
 * A point in an arbitrary 2D drawing space (pixels), kept as plain
 * doubles so the projection/viewport math stays JVM-pure (ADR-0016);
 * the Compose seam converts to `Offset` with two `toFloat()` calls.
 */
data class ScreenPoint(
    val x: Double,
    val y: Double,
)

/**
 * The equirectangular (plate carree) projection the offline map renders
 * with (r16): longitude maps linearly to [x], latitude linearly to [y].
 *
 * WHY EQUIRECTANGULAR for v1: the whole-world, fixed-viewport map needs
 * only (a) constant-speed forward/inverse math, (b) a 2:1 aspect that
 * letterboxes cleanly into any canvas, and (c) zero library code
 * (ADR-0014's no-new-dependencies rule). Mercator would inflate the
 * poles and demand per-pixel math for nothing the v1 surface shows;
 * sphere-accurate projections are a tile-pack-era concern (see
 * docs/phase-4/on-device-map.md).
 *
 * Normalizations, decided and pinned by tests:
 *  - LATITUDE CLAMPS at the poles: [project] coerces latitude into
 *    -90..90 (a lat of 90.5 is malformed input, and clamping to the pole
 *    is the least-wrong on-screen answer; the EXIF and GeoJSON parsers
 *    already reject out-of-range values, so this is defense in depth).
 *  - LONGITUDE WRAPS (modulo 360 into -180..180): a lon of 190 degrees
 *    is the same place as -170 degrees, and wrapping puts it at the
 *    correct map position, whereas clamping would pile it onto the
 *    antimeridian edge. GeoJSON and EXIF sources are in-range anyway;
 *    the wrap exists so antimeridian-crossing inputs can never silently
 *    teleport to the wrong edge.
 *
 * y runs NORTH -> SOUTH ([MapPoint.y] doc): `y(90) = 0` is the top of
 * the canvas, `y(-90) = 1` the bottom --- the screen convention, applied
 * once here instead of at every draw call.
 *
 * Pure Kotlin; JVM-tested ([EquirectangularTest]) per ADR-0016.
 */
object EquirectangularProjection {

    /** Longitude span (degrees) mapped onto [MapPoint.x] in 0..1. */
    private const val LON_SPAN = 360.0

    /** Latitude span (degrees) mapped onto [MapPoint.y] in 0..1. */
    private const val LAT_SPAN = 180.0

    /** West edge of the map, degrees. */
    private const val LON_MIN = -180.0

    /** North edge of the map, degrees. */
    private val LAT_MAX = 90.0

    /**
     * The whole-globe aspect ratio of an equirectangular map: width is
     * twice the height (360 degrees over 180 degrees). The renderer
     * letterboxes the map into any canvas using this constant.
     */
    const val ASPECT_RATIO: Double = LON_SPAN / LAT_SPAN

    /** Wraps [longitude] into -180..180 (see the class KDoc's decision). */
    fun normalizeLongitude(longitude: Double): Double {
        // ((lon + 180) mod 360) - 180 with a positive modulo: maps any
        // finite input into [-180, 180). The exact edges -180 and 180
        // denote the same meridian; both are returned as-is so in-range
        // values round-trip byte-for-byte.
        if (longitude < -180.0 || longitude > 180.0) {
            val wrapped = ((longitude + 180.0) % LON_SPAN + LON_SPAN) % LON_SPAN - 180.0
            // A full wrap landing on +180 (input 180 exactly, or -540 etc.)
            // is the same meridian as -180; keep the canonical -180.
            return if (wrapped == 180.0) -180.0 else wrapped
        }
        return longitude
    }

    /** Clamps [latitude] into -90..90 (see the class KDoc's decision). */
    fun clampLatitude(latitude: Double): Double = latitude.coerceIn(-90.0, 90.0)

    /** Forward projection: in-range or normalized lon/lat -> [MapPoint]. */
    fun project(longitude: Double, latitude: Double): MapPoint = MapPoint(
        x = (normalizeLongitude(longitude) - LON_MIN) / LON_SPAN,
        y = (LAT_MAX - clampLatitude(latitude)) / LAT_SPAN,
    )

    /** [GeoPosition] overload; the common call shape. */
    fun project(position: GeoPosition): MapPoint =
        project(position.longitude, position.latitude)

    /**
     * Inverse projection: [MapPoint] -> [GeoPosition]. Defined only on
     * [0,1] x [0,1]; inputs outside are coerced to the nearest edge (the
     * tap-conversion seam is allowed to be sloppy rather than branchy).
     * Round-trip guarantee, pinned by tests: for every in-range position,
     * `unproject(project(p))` agrees with `p` to double-precision
     * rounding (the map is affine; the test suite pins the error below
     * 1e-9 degrees --- the divide/multiply pair is not bit-exact for
     * arbitrary doubles, and pretending otherwise would be a lie).
     */
    fun unproject(point: MapPoint): GeoPosition = GeoPosition(
        longitude = normalizeLongitude(
            point.x.coerceIn(0.0, 1.0) * LON_SPAN + LON_MIN,
        ),
        latitude = clampLatitude(
            LAT_MAX - point.y.coerceIn(0.0, 1.0) * LAT_SPAN,
        ),
    )

    /**
     * Whether [point] lies inside the unit square (the map's normalized
     * bounds); the renderer uses this to skip degenerate inputs.
     */
    fun isInBounds(point: MapPoint): Boolean =
        point.x in 0.0..1.0 && point.y in 0.0..1.0 && !point.x.isNaN() && !point.y.isNaN()
}

/**
 * The v1 map's FIXED whole-world viewport: the full equirectangular globe
 * letterboxed (aspect [EquirectangularProjection.ASPECT_RATIO], 2:1)
 * into a canvas of [widthPx] x [heightPx], centered, never cropped ---
 * there is no zoom and no pan in v1, so this class is the entire viewport
 * math and it is PURE (JVM-tested; the Compose layer only converts
 * [ScreenPoint] to `Offset`).
 *
 * Letterboxing decision: the map keeps its 2:1 shape (a stretched
 * equirectangular map is a lie about coastlines) and centers inside the
 * canvas; the leftover bands belong to the canvas background, not the
 * map. On a portrait phone the map is a full-width band around the
 * vertical center; on a wide screen it is a full-height band around the
 * horizontal center.
 */
class EquirectangularViewport(widthPx: Double, heightPx: Double) {

    /** Left edge of the map rect, in canvas pixels (>= 0). */
    val left: Double

    /** Top edge of the map rect, in canvas pixels (>= 0). */
    val top: Double

    /** Width of the map rect, in canvas pixels (> 0). */
    val mapWidth: Double

    /** Height of the map rect, in canvas pixels (> 0). */
    val mapHeight: Double

    init {
        require(widthPx > 0.0 && heightPx > 0.0) {
            "canvas must have positive size (got ${widthPx}x${heightPx})"
        }
        if (widthPx / heightPx >= EquirectangularProjection.ASPECT_RATIO) {
            // Canvas at least twice as wide as tall: the map fills the
            // height, letterboxed horizontally.
            mapHeight = heightPx
            mapWidth = heightPx * EquirectangularProjection.ASPECT_RATIO
        } else {
            // Taller than 2:1 (the portrait-phone case): the map fills
            // the width, letterboxed vertically.
            mapWidth = widthPx
            mapHeight = widthPx / EquirectangularProjection.ASPECT_RATIO
        }
        left = (widthPx - mapWidth) / 2.0
        top = (heightPx - mapHeight) / 2.0
    }

    /**
     * Maps a normalized [MapPoint] to canvas pixels. Points on the unit
     * square land inside the map rect by construction (the affine map is
     * exact; no rounding is applied --- the draw seam rounds to floats).
     */
    fun toScreen(point: MapPoint): ScreenPoint = ScreenPoint(
        x = left + point.x * mapWidth,
        y = top + point.y * mapHeight,
    )

    /**
     * Maps a canvas pixel back to normalized map coordinates, or `null`
     * when the pixel falls OUTSIDE the letterboxed map rect (a tap in the
     * letterbox band is not a map tap; returning null lets the caller
     * treat it as "hit nothing" instead of coercing it to an edge).
     */
    fun fromScreen(x: Double, y: Double): MapPoint? {
        if (x < left || x >= left + mapWidth || y < top || y >= top + mapHeight) return null
        return MapPoint(
            x = (x - left) / mapWidth,
            y = (y - top) / mapHeight,
        )
    }

    /**
     * The letterbox tolerance used by callers comparing pixels to rect
     * edges; exposed for tests that construct near-edge taps.
     */
    fun containsPixel(x: Double, y: Double): Boolean = fromScreen(x, y) != null

    /**
     * Diagnostic shape (the four rect numbers). Deliberately NO custom
     * equals/hashCode: this class is a transient per-layout computation,
     * never a map key or set member, and a tolerance-based `equals` with an
     * exact `hashCode` would violate the general contract silently --- tests
     * assert the four rect properties instead.
     */
    override fun toString(): String =
        "EquirectangularViewport(left=$left, top=$top, width=$mapWidth, height=$mapHeight)"
}
