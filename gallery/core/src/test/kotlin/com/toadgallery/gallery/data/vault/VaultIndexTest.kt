package com.toadgallery.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VaultIndex] (issue #32, ADR-0017).
 *
 * The index serialization is hand-rolled on purpose (the project has no
 * org.json dependency), so this suite pins its whole contract on the plain
 * JVM (ADR-0016): exact round-trips including hostile display names, and
 * honest rejection of every malformed shape the parser can meet. A corrupt
 * index must surface as [VaultIndex.VaultIndexException] --- the vault never
 * silently salvages a prefix, because a "mostly right" catalog would make
 * [VaultStore] narrate media that is not (all) there.
 *
 * Privacy (ADR-0010/ADR-0014): pure in-memory byte shuffling; nothing leaves
 * the process, and the app holds no INTERNET permission anyway.
 */
class VaultIndexTest {

    private val entry = VaultIndexEntry(
        id = 7,
        displayName = "IMG_20261030_101010.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 123_456L,
        addedAtMillis = 1_768_000_000_000L,
        isVideo = false,
    )

    // ---- Round-trips ---------------------------------------------------

    @Test
    fun `empty index round-trips`() {
        assertTrue(VaultIndex.deserialize(VaultIndex.serialize(emptyList())).isEmpty())
    }

    @Test
    fun `entry round-trips with every field intact`() {
        val parsed = VaultIndex.deserialize(VaultIndex.serialize(listOf(entry)))
        assertEquals(listOf(entry), parsed)
    }

    @Test
    fun `multiple entries keep their order`() {
        val entries = listOf(entry, entry.copy(id = 2), entry.copy(id = 30, isVideo = true))
        assertEquals(entries, VaultIndex.deserialize(VaultIndex.serialize(entries)))
    }

    @Test
    fun `hostile display names round-trip through escaping`() {
        val hostile = listOf(
            "name\twith\ttabs.jpg",
            "line\nbreak.png",
            "carriage\rreturn.mp4",
            "back\\slash.gif",
            "mixed \t \\ \n \r end.webm",
            "winkelökse und emoji 🦞.heic",
        )
        val entries = hostile.mapIndexed { index, name ->
            VaultIndexEntry(
                id = (index + 1).toLong(),
                displayName = name,
                mimeType = "image/jpeg",
                sizeBytes = index.toLong(),
                addedAtMillis = index.toLong(),
                isVideo = index % 2 == 0,
            )
        }
        assertEquals(entries, VaultIndex.deserialize(VaultIndex.serialize(entries)))
    }

    @Test
    fun `mimetypes with separators round-trip`() {
        val weird = entry.copy(mimeType = "ima\tge\njpeg")
        assertEquals(listOf(weird), VaultIndex.deserialize(VaultIndex.serialize(listOf(weird))))
    }

    @Test
    fun `a trailing newline after the last entry is optional`() {
        val canonical = VaultIndex.serialize(listOf(entry))
        // Both spellings parse to the same entries.
        assertEquals(
            VaultIndex.deserialize(canonical),
            VaultIndex.deserialize(canonical.copyOfRange(0, canonical.size - 1)),
        )
    }

    // ---- Honest rejection ----------------------------------------------

    @Test
    fun `missing header is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                "7\ta.jpg\timage/jpeg\t1\t1\t0".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `unknown header is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v9\n" + line(id = "7")).toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `empty input is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(ByteArray(0))
        }
    }

    @Test
    fun `wrong field count is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n7\ta.jpg\timage/jpeg\t1\t1").toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `malformed id is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(("toadvault-index-v1\n" + line(id = "x")).toByteArray())
        }
    }

    @Test
    fun `non-positive id is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(("toadvault-index-v1\n" + line(id = "0")).toByteArray())
        }
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7") + "\n" + line(id = "7")).toByteArray(),
            )
        }
    }

    @Test
    fun `empty display name is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", name = "")).toByteArray(),
            )
        }
    }

    @Test
    fun `malformed size is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(("toadvault-index-v1\n" + line(id = "7", size = "x")).toByteArray())
        }
    }

    @Test
    fun `negative size is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", size = "-1")).toByteArray(),
            )
        }
    }

    @Test
    fun `malformed timestamp is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", added = "x")).toByteArray(),
            )
        }
    }

    @Test
    fun `malformed video flag is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", video = "2")).toByteArray(),
            )
        }
    }

    @Test
    fun `unknown escape is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", name = "a\\q.jpg")).toByteArray(),
            )
        }
    }

    @Test
    fun `dangling escape is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n" + line(id = "7", name = "a.jpg\\")).toByteArray(),
            )
        }
    }

    @Test
    fun `an empty entry line is rejected`() {
        assertThrows(VaultIndex.VaultIndexException::class.java) {
            VaultIndex.deserialize(
                ("toadvault-index-v1\n\n").toByteArray(Charsets.UTF_8),
            )
        }
    }

    // ---- Id allocation -------------------------------------------------

    @Test
    fun `nextId of an empty index is one`() {
        assertEquals(1L, VaultIndex.nextId(emptyList()))
    }

    @Test
    fun `nextId is one past the current maximum`() {
        assertEquals(8L, VaultIndex.nextId(listOf(entry, entry.copy(id = 3))))
    }

    /** One well-formed entry line with overridable fields. */
    private fun line(
        id: String,
        name: String = "a.jpg",
        mime: String = "image/jpeg",
        size: String = "1",
        added: String = "1",
        video: String = "0",
    ): String = "$id\t$name\t$mime\t$size\t$added\t$video"
}
