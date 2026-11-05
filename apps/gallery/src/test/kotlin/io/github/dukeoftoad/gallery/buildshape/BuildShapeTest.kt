package io.github.dukeoftoad.gallery.buildshape

import io.github.dukeoftoad.gallery.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r46 (APK-matrix round) BUILD-SHAPE invariants, as plain JUnit4 so
 * the suite runs GREEN under EVERY flavor variant (testBasicDebugUnitTest
 * AND its engine/semantic/face siblings --- the flavor flags differ, the
 * algebra must not):
 *
 *  - [BuildShape.withEngine] mirrors `BuildConfig.WITH_ENGINE` exactly;
 *  - an index flag can never be on in a build whose engine is off
 *    (`faceIndex -> withEngine`, `semanticIndex -> withEngine`);
 *  - each index flag is exactly the conjunction of the engine master
 *    switch and its own `BuildConfig.INDEX_*` field --- the
 *    "index without engine" misconfiguration a future flavor edit could
 *    introduce is caught here instead of shipping.
 *
 * Per-flavor EXPECTED values are deliberately NOT asserted (they would
 * hold only in one flavor's test task); the algebra above holds for all
 * four, which is the contract the four APKs share. The concrete matrix
 * values (basic = false/false/false etc.) are pinned where the values
 * live: apps/gallery/build.gradle.kts, enforced end-to-end by the
 * release pipeline's packaging gate.
 */
class BuildShapeTest {

    @Test
    fun `withEngine mirrors the WITH_ENGINE build config field`() {
        assertEquals(BuildConfig.WITH_ENGINE, BuildShape.withEngine)
    }

    @Test
    fun `faceIndex implies withEngine`() {
        if (BuildShape.faceIndex) {
            assertTrue(
                "faceIndex must imply withEngine --- an index without the engine library is a " +
                    "matrix violation.",
                BuildShape.withEngine,
            )
        }
    }

    @Test
    fun `semanticIndex implies withEngine`() {
        if (BuildShape.semanticIndex) {
            assertTrue(
                "semanticIndex must imply withEngine --- an index without the engine library is " +
                    "a matrix violation.",
                BuildShape.withEngine,
            )
        }
    }

    @Test
    fun `faceIndex is exactly withEngine AND INDEX_FACE`() {
        assertEquals(BuildShape.withEngine && BuildConfig.INDEX_FACE, BuildShape.faceIndex)
    }

    @Test
    fun `semanticIndex is exactly withEngine AND INDEX_SEMANTIC`() {
        assertEquals(BuildShape.withEngine && BuildConfig.INDEX_SEMANTIC, BuildShape.semanticIndex)
    }

    @Test
    fun `a build without the engine has neither index`() {
        if (!BuildShape.withEngine) {
            assertFalse(BuildShape.faceIndex)
            assertFalse(BuildShape.semanticIndex)
        }
    }
}
