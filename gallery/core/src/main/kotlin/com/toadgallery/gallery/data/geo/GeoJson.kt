package com.toadgallery.gallery.data.geo

/**
 * One WGS 84 position in decimal degrees (r16 round, the on-device map
 * view).
 *
 * Ordered LONGITUDE-FIRST (`[longitude, latitude]`) because that is the
 * GeoJSON position order --- RFC 7946 section 3.1.1
 * (https://www.rfc-editor.org/rfc/rfc7946): "A position is an array of
 * numbers ... longitude, latitude". The bundled Natural Earth outline and
 * the EXIF-derived photo coordinates both feed the map through this one
 * canonical shape, so the lon/lat vs lat/lon trap is answered exactly
 * once, here.
 *
 * Range discipline: the parsers that PRODUCE positions enforce the ranges
 * ([GeoJsonParser] rejects non-finite and out-of-range coordinates;
 * [com.toadgallery.gallery.metadata.exif.ExifMath
 * .gpsRationalToDecimalDegrees] already bounds EXIF GPS to -90..90 /
 * -180..180), so a [GeoPosition] carries in-range values by construction.
 */
data class GeoPosition(
    /** Longitude in decimal degrees, -180..180; east positive. */
    val longitude: Double,
    /** Latitude in decimal degrees, -90..90; north positive. */
    val latitude: Double,
)

/**
 * The parsed world outline the offline map renders (r16): the EXTERIOR
 * rings of every land polygon of the bundled asset.
 *
 * Holes are deliberately dropped for v1 (documented scope cut): Natural
 * Earth's holes are inland seas (the Caspian), and at the fixed
 * whole-world zoom of the v1 map a hole smaller than a few pixels of
 * coastline adds draw cost without visible cartography. When a zooming
 * viewport lands (Phase 5+), the parser extension point is
 * [GeoJsonParser.parseOutline] itself --- the ring model below would grow
 * an `interiors` list without any caller changing shape.
 *
 * Pure Kotlin (no Android imports) so [com.toadgallery.gallery
 * .data.geo] and its JVM tests follow ADR-0016 exactly: parsing and
 * validation decisions live here, tested on the JVM; only the asset read
 * (`context.assets.open`) stays at the thin Compose/ViewModel seam.
 *
 * Provenance: produced from Natural Earth's 110m land polygon set
 * (public domain, https://www.naturalearthdata.com/) converted to GeoJSON
 * FeatureCollection (CRS84 lon/lat); the committed asset is
 * `apps/gallery/src/main/assets/maps/world-110m.geojson`.
 */
data class GeoOutline(
    /**
     * One entry per land polygon: the exterior ring's positions in ring
     * order. Rings are CLOSED per RFC 7946 section 3.1.6 (first position
     * equals last); the parser keeps the closing position so callers can
     * assert closure, and the renderer's `Path.close()` treats it as a
     * no-op.
     */
    val polygons: List<List<GeoPosition>>,
) {
    /** Number of parsed polygons; the sanity surface the asset test pins. */
    val polygonCount: Int get() = polygons.size

    /** Total parsed positions across all rings (bounded by the asset). */
    val positionCount: Int get() = polygons.sumOf { it.size }
}

/**
 * Thrown by [GeoJsonParser.parseOutline] on input that is not the GeoJSON
 * shape it reads: invalid JSON, a non-FeatureCollection root, a feature
 * whose coordinates are not numbers, or coordinates outside the WGS 84
 * ranges. One exception type (with the reason in the message) keeps the
 * caller's failure handling a single honest branch --- the v1 renderer
 * treats any parse failure as "no outline" and still draws the graticule
 * and the photo dots.
 */
class GeoJsonParseException(message: String) : Exception(message)

/**
 * The dependency-free GeoJSON reader for the offline map (r16; ADR-0014:
 * zero network code, so no third-party parser artifact --- the bytes come
 * from a bundled asset and the parsing is ~200 lines of plain Kotlin).
 *
 * Scope, v1 (documented): reads a `FeatureCollection` and produces the
 * EXTERIOR rings of its polygonal geometries --- `Polygon` (first ring)
 * and `MultiPolygon` (first ring of each member polygon). Everything else
 * is skipped WITHOUT failing the parse: non-polygon geometries (points,
 * lines), features with a `null` geometry, and per-polygon interior rings
 * (holes --- see [GeoOutline]). The bundled Natural Earth asset is 100%
 * simple `Polygon` features, so the skips are forward-compatibility, not
 * live behaviour.
 *
 * Coordinate validation (fail-fast, [GeoJsonParseException]):
 *  - each position must be a 2+ element array of finite numbers; extra
 *    elements (RFC 7946 allows an optional third altitude) are ignored;
 *  - longitude must lie in -180..180 and latitude in -90..90 (RFC 7946
 *    section 3.5 / WGS 84);
 *  - a ring must carry at least 4 positions (RFC 7946 section 3.1.6: a
 *    linear ring has four or more positions).
 *
 * The JSON reader at the bottom of this file is a plain recursive-descent
 * tokenizer/parser (strings with the standard escapes, numbers, `null`,
 * `true`, `false`, objects, arrays) --- enough for any conforming
 * GeoJSON document and nothing more; it never evaluates anything, so an
 * untrusted asset cannot do anything but parse or fail.
 */
object GeoJsonParser {

    /**
     * Parses [text] (the full GeoJSON document) into a [GeoOutline].
     * @throws GeoJsonParseException on any shape/range violation above.
     */
    fun parseOutline(text: String): GeoOutline {
        val root = JsonParser.parse(text)
        val rootObject = root as? JsonValue.Obj
            ?: throw GeoJsonParseException("root is not a JSON object")
        if (rootObject.members["type"]?.stringValueOrNull() != "FeatureCollection") {
            throw GeoJsonParseException("root type is not FeatureCollection")
        }
        val features = rootObject.members["features"] as? JsonValue.Arr
            ?: throw GeoJsonParseException("features is missing or not an array")
        val polygons = mutableListOf<List<GeoPosition>>()
        for (feature in features.items) {
            val featureObject = feature as? JsonValue.Obj ?: continue
            // A null geometry is legal GeoJSON; nothing to draw.
            val geometry = featureObject.members["geometry"] as? JsonValue.Obj ?: continue
            when (geometry.members["type"]?.stringValueOrNull()) {
                "Polygon" -> {
                    exteriorRing(geometry.members["coordinates"])?.let { polygons.add(it) }
                }
                "MultiPolygon" -> {
                    val members = geometry.members["coordinates"] as? JsonValue.Arr ?: continue
                    for (member in members.items) {
                        exteriorRing(member)?.let { polygons.add(it) }
                    }
                }
                // Points, lines, geometry collections: skipped by v1 scope.
                else -> continue
            }
        }
        if (polygons.isEmpty()) {
            throw GeoJsonParseException("no polygonal geometry in the FeatureCollection")
        }
        return GeoOutline(polygons)
    }

    /**
     * The exterior ring (rings[0]) out of one polygon's `coordinates`
     * value, as validated positions; `null` when the value is not the
     * expected array-of-rings shape (the caller skips the feature).
     * Interior rings exist in the JSON but are not retained.
     */
    private fun exteriorRing(coordinates: JsonValue?): List<GeoPosition>? {
        val rings = coordinates as? JsonValue.Arr ?: return null
        val exterior = rings.items.firstOrNull() as? JsonValue.Arr ?: return null
        val positions = mutableListOf<GeoPosition>()
        for (position in exterior.items) {
            val coords = position as? JsonValue.Arr
                ?: throw GeoJsonParseException("ring position is not an array")
            if (coords.items.size < 2) {
                throw GeoJsonParseException("ring position has fewer than 2 coordinates")
            }
            val longitude = coords.items[0].numberValueOrNull()
                ?: throw GeoJsonParseException("longitude is not a number")
            val latitude = coords.items[1].numberValueOrNull()
                ?: throw GeoJsonParseException("latitude is not a number")
            if (longitude < -180.0 || longitude > 180.0) {
                throw GeoJsonParseException("longitude out of range: $longitude")
            }
            if (latitude < -90.0 || latitude > 90.0) {
                throw GeoJsonParseException("latitude out of range: $latitude")
            }
            positions.add(GeoPosition(longitude, latitude))
        }
        if (positions.size < 4) {
            throw GeoJsonParseException("exterior ring has fewer than 4 positions")
        }
        return positions
    }
}

// --- minimal JSON reader (private to the GeoJSON layer) -------------------

/** The parsed JSON value tree; only what GeoJSON needs. */
private sealed interface JsonValue {
    data class Obj(val members: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue

    data object True : JsonValue

    data object False : JsonValue

    data object Null : JsonValue
}

/** The string of a [JsonValue.Str], or `null` for anything else. */
private fun JsonValue.stringValueOrNull(): String? = (this as? JsonValue.Str)?.value

/** The double of a [JsonValue.Num], or `null` for anything else. */
private fun JsonValue.numberValueOrNull(): Double? = (this as? JsonValue.Num)?.value

/**
 * Recursive-descent JSON parser (RFC 8259 subset: no duplicate-key rules
 * beyond "last wins", no number grammar beyond what Kotlin's `toDouble`
 * accepts, no nesting-depth guard beyond the input's own size --- the
 * committed asset is the only production input).
 */
private object JsonParser {

    fun parse(text: String): JsonValue {
        val parser = ParserState(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw GeoJsonParseException("trailing characters after the JSON document")
        }
        return value
    }
}

private class ParserState(private val text: String) {
    private var index = 0

    fun atEnd(): Boolean = index >= text.length

    fun skipWhitespace() {
        while (index < text.length &&
            text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        ) {
            index++
        }
    }

    fun readValue(): JsonValue {
        if (atEnd()) throw GeoJsonParseException("unexpected end of JSON input")
        return when (val c = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> {
                expectLiteral("true")
                JsonValue.True
            }
            'f' -> {
                expectLiteral("false")
                JsonValue.False
            }
            'n' -> {
                expectLiteral("null")
                JsonValue.Null
            }
            else -> {
                if (c == '-' || c == '+' || c == '.' || c.isDigit()) readNumber()
                else throw GeoJsonParseException("unexpected character '$c' at $index")
            }
        }
    }

    private fun readObject(): JsonValue.Obj {
        index++ // consume '{'
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[index] == '}') {
            index++
            return JsonValue.Obj(members)
        }
        while (true) {
            skipWhitespace()
            if (atEnd() || text[index] != '"') {
                throw GeoJsonParseException("expected an object key at $index")
            }
            val key = readString()
            skipWhitespace()
            if (atEnd() || text[index] != ':') {
                throw GeoJsonParseException("expected ':' at $index")
            }
            index++ // consume ':'
            skipWhitespace()
            members[key] = readValue()
            skipWhitespace()
            if (atEnd()) throw GeoJsonParseException("unterminated object")
            when (text[index]) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(members)
                }
                else -> throw GeoJsonParseException("expected ',' or '}' at $index")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        index++ // consume '['
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[index] == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items.add(readValue())
            skipWhitespace()
            if (atEnd()) throw GeoJsonParseException("unterminated array")
            when (text[index]) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> throw GeoJsonParseException("expected ',' or ']' at $index")
            }
        }
    }

    private fun readString(): String {
        index++ // consume the opening quote
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw GeoJsonParseException("unterminated string")
            when (val c = text[index]) {
                '"' -> {
                    index++
                    return sb.toString()
                }
                '\\' -> {
                    index++
                    if (atEnd()) throw GeoJsonParseException("unterminated escape")
                    when (val escaped = text[index]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 >= text.length) {
                                throw GeoJsonParseException("truncated unicode escape")
                            }
                            val hex = text.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16)
                                ?: throw GeoJsonParseException("bad unicode escape \\u$hex")
                            sb.append(code.toChar())
                            index += 4
                        }
                        else -> throw GeoJsonParseException("bad escape '\\$escaped'")
                    }
                    index++
                }
                else -> {
                    sb.append(c)
                    index++
                }
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        while (!atEnd()) {
            val c = text[index]
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || c.isDigit()) {
                index++
            } else {
                break
            }
        }
        val token = text.substring(start, index)
        val value = token.toDoubleOrNull()
            ?: throw GeoJsonParseException("bad number '$token' at $start")
        if (!value.isFinite()) {
            throw GeoJsonParseException("non-finite number '$token'")
        }
        return JsonValue.Num(value)
    }

    private fun expectLiteral(literal: String) {
        if (index + literal.length > text.length ||
            text.substring(index, index + literal.length) != literal
        ) {
            throw GeoJsonParseException("bad literal at $index (expected $literal)")
        }
        index += literal.length
    }
}
