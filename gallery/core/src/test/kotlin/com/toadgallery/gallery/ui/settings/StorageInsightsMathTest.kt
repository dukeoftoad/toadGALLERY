package com.toadgallery.gallery.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the r62 Storage dashboard's pure measurement math
 * (ADR-0016: no emulator, no Robolectric --- the walk and the DB-trio sum
 * are plain java.io, so a temp folder pins them exactly).
 */
class StorageInsightsMathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `directorySize walks nested files`() {
        val dir = tmp.newFolder("store")
        File(dir, "a.bin").writeText("12345")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.bin").writeText("1234567890")
        // 5 + 10 bytes; directories contribute nothing.
        assertEquals(15L, directorySize(dir))
    }

    @Test
    fun `directorySize is zero for absent and non-directory paths`() {
        assertEquals(0L, directorySize(null))
        assertEquals(0L, directorySize(File(tmp.root, "does-not-exist")))
        assertEquals(0L, directorySize(tmp.newFile("plain.txt")))
    }

    @Test
    fun `directorySize skips non-files gracefully`() {
        val dir = tmp.newFolder("mixed")
        File(dir, "data.bin").writeText("123")
        // An unreadable entry (directory shaped like a file) must not throw:
        // walkTopDown only sums isFile entries.
        File(dir, "inner").mkdirs()
        assertEquals(3L, directorySize(dir))
    }

    @Test
    fun `databaseFilesSize sums the db trio and tolerates absent wal shm`() {
        val dbs = tmp.newFolder("databases")
        File(dbs, "media_catalogue.db").writeText("12345")
        File(dbs, "media_catalogue.db-wal").writeText("123")
        // -shm absent: a real state (checkpointed WAL) --- must not throw.
        assertEquals(8L, databaseFilesSize(dbs, "media_catalogue.db"))
    }

    @Test
    fun `databaseFilesSize is zero for a missing directory`() {
        assertEquals(0L, databaseFilesSize(File(tmp.root, "nope"), "media_catalogue.db"))
        assertEquals(0L, databaseFilesSize(null, "media_catalogue.db"))
    }
}
