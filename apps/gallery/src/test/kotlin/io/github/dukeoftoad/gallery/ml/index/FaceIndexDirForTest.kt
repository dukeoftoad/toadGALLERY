package io.github.dukeoftoad.gallery.ml.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r56 regression: the app-private face-index directory (`filesDir/face-index/`)
 * MUST exist before the JNI store open (`MlEngine.openFaceIndex(dir)` →
 * `toad-ml-jni::store::open(dir)` → `rusqlite::Connection::open(dir/ml-index.sqlite)`)
 * runs, or SQLite returns SQLITE_CANTOPEN ("unable to open database file").
 *
 * The r55 device failure (both gallery flavors: `io.github.dukeoftoad.gallery.semantic`
 * and `io.github.dukeoftoad.gallery.face`) traced to nobody creating the directory
 * before the open --- on a fresh install AND right after `AiEngineViewModel.resetFaceIndex()`'s
 * `deleteRecursively()`. The fix routes every production call site through the single
 * [faceIndexDirFor] helper, which `mkdirs()`s on access; this test pins that contract
 * so a future caller cannot regress into the same CANTOPEN. The Rust half of the fix
 * lives in `toad-ml-jni::store::open` (`create_dir_all` before the join) for defense in
 * depth and is pinned by `store::tests::store_open_creates_missing_directory`.
 *
 * Pure JVM (no Robolectric, no engine stack): [faceIndexDirFor] takes `filesDir` as a
 * parameter precisely so this test can run against a [TemporaryFolder].
 */
class FaceIndexDirForTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun faceIndexDirFor_creates_the_directory_when_missing() {
        // The fresh-install state: `filesDir/face-index/` does not exist.
        val filesDir = tempFolder.newFolder("files")
        val expected = File(filesDir, FACE_INDEX_DIR_NAME)
        assertFalse("precondition: the face-index dir must not exist yet", expected.exists())

        // The production helper creates it on access.
        val actual = faceIndexDirFor(filesDir)

        assertEquals(expected, actual)
        assertTrue("the face-index dir was not created", actual.exists())
        assertTrue("the face-index dir is not a directory", actual.isDirectory)
    }

    @Test
    fun faceIndexDirFor_recreates_the_directory_after_reset_deletes_it() {
        // The post-Reset-then-Start scenario: a prior Start created the dir,
        // then `AiEngineViewModel.resetFaceIndex()`'s `deleteRecursively()`
        // removed it; the next Start must recreate it before the JNI open.
        val filesDir = tempFolder.newFolder("files")
        // First Start: the helper creates the dir.
        faceIndexDirFor(filesDir)
        // Reset: the directory is removed (mirrors resetFaceIndex's
        // `faceIndexDir().deleteRecursively()`).
        File(filesDir, FACE_INDEX_DIR_NAME).deleteRecursively()
        assertFalse(
            "precondition: the face-index dir was not removed by the simulated reset",
            File(filesDir, FACE_INDEX_DIR_NAME).exists(),
        )

        // Next Start: the helper recreates the dir.
        val recreated = faceIndexDirFor(filesDir)
        assertTrue("the face-index dir was not recreated after reset", recreated.exists())
        assertTrue("the recreated face-index dir is not a directory", recreated.isDirectory)
    }

    @Test
    fun faceIndexDirFor_is_idempotent_on_an_existing_directory() {
        // mkdirs() returns false (no-op) when the dir already exists; the
        // helper must not fail or replace the existing directory (which may
        // already hold a populated ml-index.sqlite from a prior run).
        val filesDir = tempFolder.newFolder("files")
        val dir = faceIndexDirFor(filesDir)
        // Seed a sentinel file that simulates the ml-index.sqlite a prior
        // successful open would have left behind.
        val sentinel = File(dir, "ml-index.sqlite").apply { writeBytes(byteArrayOf(0x53, 0x51, 0x4c)) }
        assertTrue(sentinel.exists())

        // A second helper call must not clobber the existing dir.
        val sameDir = faceIndexDirFor(filesDir)
        assertEquals(dir, sameDir)
        assertTrue(sentinel.exists())
        assertEquals(3, sentinel.readBytes().size)
    }
}
