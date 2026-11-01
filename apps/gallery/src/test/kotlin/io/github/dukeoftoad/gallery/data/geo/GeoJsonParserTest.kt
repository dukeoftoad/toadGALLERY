package io.github.dukeoftoad.gallery.data.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * JVM tests for the offline map's GeoJSON reader (r16 round; ADR-0016 ---
 * no android.*, plain JUnit). The inline fixtures pin the v1 scope
 * decisions ([GeoJsonParser]: FeatureCollection in, EXTERIOR rings of
 * Polygon/MultiPolygon out, everything else skipped; fail-fast coordinate
 * validation), and one test parses the REAL committed asset through a
 * plain [File] read so the shipped bytes are what the pins run against
 * (the same discipline as `PrivacyPolicyTest`/`BackupRulesTest` reading
 * main-source files).
 */
class GeoJsonParserTest {

    // --- fixtures -----------------------------------------------------------

    /** A closed square ring around (10, 20) --- the minimal legal ring. */
    private val squareRing = "[[10,20],[11,20],[11,21],[10,21],[10,20]]"

    private fun polygonFeature(rings: String): String =
        """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":$rings}}"""

    private fun featureCollection(features: String): String =
        """{"type":"FeatureCollection","features":[$features]}"""

    // --- the happy paths -----------------------------------------------------

    @Test
    fun `a minimal polygon FeatureCollection parses with its exterior ring kept`() {
        val outline = GeoJsonParser.parseOutline(featureCollection(polygonFeature("[$squareRing]")))

        assertEquals(1, outline.polygonCount)
        assertEquals(5, outline.positionCount)
        val ring = outline.polygons.single()
        // Positions preserved verbatim, longitude-first (RFC 7946 3.1.1).
        assertEquals(GeoPosition(10.0, 20.0), ring.first())
        assertEquals(GeoPosition(11.0, 21.0), ring[2])
        // The closing position is kept so callers can assert closure.
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun `multipolygons contribute one exterior ring per member and interior rings are dropped`() {
        val hole = "[[12,12],[13,12],[13,13],[12,13],[12,12]]"
        val feature =
            """{"type":"Feature","properties":{},"geometry":{"type":"MultiPolygon",""" +
                """"coordinates":[[$squareRing, $hole], [$hole]]}}"""
        val outline = GeoJsonParser.parseOutline(featureCollection(feature))

        // Two members -> two exterior rings; the hole contributes nothing
        // to the count and nothing to the position total.
        assertEquals(2, outline.polygonCount)
        assertEquals(10, outline.positionCount)
    }

    @Test
    fun `non-polygon features and null geometries are skipped without failing`() {
        val point = """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]}}"""
        val line =
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}}"""
        val noGeometry = """{"type":"Feature","geometry":null}"""
        val outline = GeoJsonParser.parseOutline(
            featureCollection("$point, $line, $noGeometry, ${polygonFeature("[$squareRing]")}"),
        )

        assertEquals(1, outline.polygonCount)
    }

    // --- the failure paths (fail fast, one honest branch) ---------------------

    @Test
    fun `the root must be a FeatureCollection with a features array`() {
        // Not a FeatureCollection root.
        assertRejected("""{"type":"Feature","geometry":null}""")
        // Wrong root type entirely.
        assertRejected("""[1,2,3]""")
        // FeatureCollection without a features member.
        assertRejected("""{"type":"FeatureCollection"}""")
        // features present but not an array.
        assertRejected("""{"type":"FeatureCollection","features":null}""")
    }

    @Test
    fun `malformed json is rejected`() {
        assertRejected("") // empty document
        assertRejected("""{"type":"FeatureCollection","features":[""") // unterminated
        assertRejected("""{"type":"FeatureCollection"} trailing""") // trailing characters
        assertRejected("""{"type":"FeatureCollection","features":[{]}""") // broken object
    }

    @Test
    fun `out-of-range and non-numeric coordinates and short rings are rejected`() {
        // Longitude past +180 (RFC 7946 3.5 / WGS 84 range).
        assertRejected(
            featureCollection(
                polygonFeature("[[[10,20],[181,20],[11,21],[10,21],[10,20]]]"),
            ),
        )
        // Latitude past -90.
        assertRejected(
            featureCollection(
                polygonFeature("[[[10,20],[11,-91],[11,21],[10,21],[10,20]]]"),
            ),
        )
        // A string where a number belongs.
        assertRejected(
            featureCollection(
                polygonFeature("[[[10,20],[\"11\",20],[11,21],[10,21],[10,20]]]"),
            ),
        )
        // Fewer than the four positions a linear ring requires (RFC 7946 3.1.6).
        assertRejected(
            featureCollection(
                polygonFeature("[[[10,20],[11,20],[10,20]]]"),
            ),
        )
        // A position with only one coordinate.
        assertRejected(
            featureCollection(
                polygonFeature("[[[10],[11,20],[11,21],[10,21],[10,20]]]"),
            ),
        )
    }

    @Test
    fun `a FeatureCollection with no polygonal geometry is rejected`() {
        val point = """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]}}"""
        assertRejected(featureCollection(point))
        assertRejected(featureCollection(""))
    }

    // --- the committed asset (the bytes the renderer really draws) ------------

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    @Test
    fun `the committed world asset parses - closed rings, finite in-range coordinates`() {
        val text = moduleFile("src/main/assets/maps/world-110m.geojson").readText()
        val outline = GeoJsonParser.parseOutline(text)

        // Natural Earth 110m land: a real, non-trivial outline.
        assertTrue("expected land polygons, got ${outline.polygonCount}", outline.polygonCount > 0)
        assertTrue("expected real position counts, got ${outline.positionCount}", outline.positionCount > 0)
        for (ring in outline.polygons) {
            assertTrue("a ring with fewer than 4 positions slipped through", ring.size >= 4)
            assertTrue("ring is not closed (first != last)", ring.first() == ring.last())
            for (position in ring) {
                assertTrue(
                    "longitude out of range: ${position.longitude}",
                    position.longitude in -180.0..180.0,
                )
                assertTrue(
                    "latitude out of range: ${position.latitude}",
                    position.latitude in -90.0..90.0,
                )
            }
        }
    }

    @Test
    fun `a third altitude element is legal and ignored`() {
        val outline = GeoJsonParser.parseOutline(
            featureCollection(
                polygonFeature("[[[10,20,300],[11,20,300],[11,21,300],[10,21,300],[10,20,300]]]"),
            ),
        )
        // RFC 7946 allows an optional third (altitude) element; the parser
        // must accept the document and keep 2-D positions.
        assertEquals(1, outline.polygonCount)
        assertEquals(GeoPosition(10.0, 20.0), outline.polygons.single().first())
    }

    // --- helper ----------------------------------------------------------------

    private fun assertRejected(text: String) {
        try {
            GeoJsonParser.parseOutline(text)
            fail("expected GeoJsonParseException for: ${text.take(80)}")
        } catch (expected: GeoJsonParseException) {
            // The single honest failure branch: any parse failure is this
            // exception, message included.
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }
}
