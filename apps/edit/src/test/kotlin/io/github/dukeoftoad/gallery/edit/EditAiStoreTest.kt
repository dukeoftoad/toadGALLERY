package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The r41 derived-artifact store contract (the contract's artifact-store
 * set): the codec round-trips (mask/fill/depth/boxes), the collision-free
 * append-only keys, the atomic writes, the forgiving reads, the sweep,
 * and the missing-ref tolerance.
 */
class EditAiStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(source: String = "content://media/1"): Pair<EditAiStore, File> {
        val root = tmp.newFolder()
        val store = EditAiStore(root, source)
        return store to root
    }

    private fun handMask(w: Int, h: Int): BooleanArray = BooleanArray(w * h) { i ->
        val x = i % w
        val y = i / w
        x in 2..4 && y in 2..4
    }

    // ---- The codec round-trips ----

    @Test
    fun `a mask round-trips through the store byte-exactly and reconstructs at any grid`() {
        val (store, _) = store()
        val mask = FractionRleMask.fromMaskAt(handMask(6, 6), 6, 6)
        val key = store.keyFor("e1", "inputs", EditAiModels.IDENTITY_SALIENCY)
        assertTrue(store.writeEraseArtifact(key, mask, handPatch()))
        val read = store.readEraseArtifact(key)
        assertTrue(read != null)
        assertEquals(mask.rows, read!!.first.rows)
        assertEquals(mask.workingWidth, read.first.workingWidth)
        // Exact at the working grid (the fraction rule's promise).
        assertTrue(read.first.maskAt(6, 6).contentEquals(handMask(6, 6)))
        // Approximate but sensible at another grid: the same block, coarser.
        val at12 = read.first.maskAt(12, 12)
        assertTrue(at12[5 * 12 + 5]) // (5,5): fraction 5.5/12 in (2.5/6, 4.5/6)
        assertFalse(at12[2 * 12 + 2]) // (2,2): fraction 2.5/12 = 0.208 < 0.4167
    }

    @Test
    fun `a fill patch round-trips and samples identically`() {
        val (store, _) = store()
        val patch = handPatch()
        val key = store.keyFor("e1", "inputs", EditAiModels.IDENTITY_INPAINT)
        assertTrue(store.writeEraseArtifact(key, FractionRleMask.fromMaskAt(handMask(6, 6), 6, 6), patch))
        val read = store.readEraseArtifact(key)!!.second
        assertTrue(read.pixels.contentEquals(patch.pixels))
        assertEquals(patch.left, read.left, 0.0)
        // The nearest sample is a pure function of the stored patch.
        assertEquals(patch.sampleAt(0.5, 0.5), read.sampleAt(0.5, 0.5))
    }

    @Test
    fun `a depth grid round-trips and the bounded downsample caps the long edge`() {
        val (store, _) = store()
        val map = IntArray(518 * 518) { i -> (i * 7) % 256 }
        val grid = DepthGrid.fromMap(map, 518, 518)
        assertTrue(grid.width <= DepthGrid.MAX_LONG_EDGE && grid.height <= DepthGrid.MAX_LONG_EDGE)
        val key = store.keyFor("bokeh", "inputs", EditAiModels.IDENTITY_DEPTH)
        assertTrue(store.writeDepth(key, grid))
        val read = store.readDepth(key)!!
        assertTrue(read.values.contentEquals(grid.values))
        // The nearest sample: the identity mapping when the grids match.
        assertEquals(grid.sampleAt(3, 4, 100, 100), read.sampleAt(3, 4, 100, 100))
    }

    @Test
    fun `face boxes round-trip in detection order`() {
        val (store, _) = store()
        val boxes = FaceBoxes(
            listOf(
                FaceBoxes.Box(0.1, 0.2, 0.3, 0.4),
                FaceBoxes.Box(0.5, 0.6, 0.7, 0.8),
            ),
        )
        val key = store.keyFor("retouch", "inputs", EditAiModels.IDENTITY_FACES)
        assertTrue(store.writeBoxes(key, boxes))
        assertEquals(boxes.boxes, store.readBoxes(key)!!.boxes)
    }

    // ---- The keys: collision-free + append-only ----

    @Test
    fun `keys differ by layer, inputs and model and a re-apply never reuses a key`() {
        val (store, _) = store()
        val a = store.keyFor("e1", "inputsA", EditAiModels.IDENTITY_SALIENCY)
        val b = store.keyFor("e2", "inputsA", EditAiModels.IDENTITY_SALIENCY)
        val c = store.keyFor("e1", "inputsB", EditAiModels.IDENTITY_SALIENCY)
        val d = store.keyFor("e1", "inputsA", EditAiModels.IDENTITY_DEPTH)
        assertTrue(a != b && b != c && c != d && a != c && a != d)
        assertTrue(a.startsWith("e1-"))
        // A changed adjustment changes the pixel hash -> a NEW key.
        val h1 = EditAiCommit.pixelInputs(IntArray(64) { it * 3 }, "")
        val h2 = EditAiCommit.pixelInputs(IntArray(64) { it * 4 }, "")
        assertTrue(h1 != h2)
        assertTrue(store.keyFor("e1", h1, "m") != store.keyFor("e1", h2, "m"))
    }

    @Test
    fun `stores are isolated per source and a re-apply keeps the old artifact`() {
        // The append-only session semantics: a second write under a NEW
        // key never removes the first (the undo stack stays coherent).
        val (store, _) = store()
        val mask = FractionRleMask.fromMaskAt(handMask(6, 6), 6, 6)
        val first = store.keyFor("e1", "inputs1", EditAiModels.IDENTITY_SALIENCY)
        assertTrue(store.writeEraseArtifact(first, mask, handPatch()))
        val second = store.keyFor("e1", "inputs2", EditAiModels.IDENTITY_SALIENCY)
        assertTrue(store.writeEraseArtifact(second, mask, handPatch()))
        assertTrue(store.readEraseArtifact(first) != null) // still present
        assertTrue(store.readEraseArtifact(second) != null)
        // The same source in another store (a different fingerprint dir).
        val root2 = tmp.newFolder()
        val other = EditAiStore(root2, "content://media/2")
        assertTrue(other.readEraseArtifact(first) == null) // isolated
        // And the sweep clears ONLY the owning source's artifacts.
        other.writeSkyMask(other.keyFor("sky", "i", "m"), mask)
        store.sweep()
        assertTrue(store.readEraseArtifact(first) == null)
        assertTrue(store.readEraseArtifact(second) == null)
        assertTrue(other.readSkyMask(other.keyFor("sky", "i", "m")) != null)
    }

    // ---- The reads: forgiving ----

    @Test
    fun `missing and corrupt artifacts read as absent, never thrown`() {
        val (store, root) = store()
        assertNull(store.readEraseArtifact("no-such-key"))
        assertNull(store.readSkyMask("no-such-key"))
        assertNull(store.readDepth("no-such-key"))
        assertNull(store.readBoxes("no-such-key"))
        // Corrupt the bytes of a written artifact.
        val key = store.keyFor("e1", "i", EditAiModels.IDENTITY_SALIENCY)
        assertTrue(store.writeEraseArtifact(key, FractionRleMask.fromMaskAt(handMask(6, 6), 6, 6), handPatch()))
        val dir = File(root, "src-${EditRecipeStore.sha256("content://media/1").take(16)}")
        File(dir, "${key}.mask").writeBytes(ByteArray(32) { 9 })
        assertNull(store.readEraseArtifact(key))
    }

    @Test
    fun `unsafe key characters are stripped from file names`() {
        val (store, root) = store()
        // keyFor derives keys from safe layer ids; force a hostile one
        // through the internal path by writing under a crafted key.
        val dir = File(root, "src-${EditRecipeStore.sha256("content://media/1").take(16)}")
        val mask = FractionRleMask.fromMaskAt(handMask(6, 6), 6, 6)
        store.writeSkyMask("../../evil", mask)
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.none { it.contains("..") || it.contains('/') })
        assertTrue(store.readSkyMask("../../evil") == null)
    }

    // ---- helpers ----

    private fun handPatch(): FillPatch = FillPatch(
        pixels = IntArray(9) { i -> 0xFF000000.toInt() or (i * 1000) },
        width = 3,
        height = 3,
        left = 2.5 / 6.0,
        top = 2.5 / 6.0,
        right = 4.5 / 6.0,
        bottom = 4.5 / 6.0,
    )
}
