package com.toadgallery.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure file-ops logic (issue #30): copy naming with
 * collision numbering, rename validation/sanitization + extension
 * preservation, and batch result aggregation.
 *
 * Runs in plain `testDebugUnitTest` (ADR-0016 --- the binding CI gate): no
 * emulator, no Robolectric, which is exactly why everything under test
 * lives in `FileOpNaming.kt` free of android.* imports --- the Android
 * layer (`MediaFileOps`) feeds these functions ContentResolver data and
 * consumes their outputs.
 */
class FileOpNamingTest {

    // ------------------------------------------------------------------
    // copyDisplayName: " (copy)" suffix + collision numbering
    // ------------------------------------------------------------------

    @Test
    fun `copy name appends (copy) and keeps the extension`() {
        assertEquals(
            "first duplicate of Sunset.jpg",
            "Sunset (copy).jpg",
            copyDisplayName("Sunset.jpg", taken = { false }),
        )
    }

    @Test
    fun `copy name numbers the second slot when (copy) is taken`() {
        assertEquals(
            "Sunset (copy).jpg occupied -> copy 2",
            "Sunset (copy 2).jpg",
            copyDisplayName("Sunset.jpg", taken = { name -> name == "Sunset (copy).jpg" }),
        )
    }

    @Test
    fun `copy name keeps counting through occupied slots`() {
        val takenNames = setOf("Report (copy).pdf", "Report (copy 2).pdf", "Report (copy 3).pdf")
        assertEquals(
            "first three slots occupied -> copy 4",
            "Report (copy 4).pdf",
            copyDisplayName("Report.pdf", taken = { it in takenNames }),
        )
    }

    @Test
    fun `copy name works without an extension`() {
        assertEquals(
            "extension-less source stays extension-less",
            "Trip (copy)",
            copyDisplayName("Trip", taken = { false }),
        )
    }

    @Test
    fun `copy name of a leading-dot source keeps the dot`() {
        // Absurd edge: a file literally named ".jpg". The leading dot is
        // part of the name (dotfile rule), and the name has no extension to
        // preserve, so the suffix lands before the trailing extension would.
        assertEquals(
            ".jpg (copy)",
            copyDisplayName(".jpg", taken = { false }),
        )
    }

    @Test
    fun `copy name falls back to the last candidate when every slot is taken`() {
        // Bounded best-effort: with maxTries = 3 and all slots taken the
        // last candidate is returned --- the insert then either still
        // succeeds (provider-side dedupe) or surfaces an honest failure.
        assertEquals(
            "stem 3 candidate after 3 exhausted tries",
            "Sunset (copy 3).jpg",
            copyDisplayName("Sunset.jpg", taken = { _ -> true }, maxTries = 3),
        )
    }

    @Test
    fun `copy name collision probe is scoped to the taken predicate only`() {
        // The predicate decides collisions (production: a DISPLAY_NAME query
        // scoped to the target folder); different-case names are whatever
        // the predicate says, never re-checked by the pure logic.
        val exactMatchOnly = setOf("sunset (copy).jpg") // lowercase
        assertEquals(
            "case-sensitive predicates probe exactly what they hold",
            "Sunset (copy).jpg",
            copyDisplayName("Sunset.jpg", taken = { it in exactMatchOnly }),
        )
    }

    // ------------------------------------------------------------------
    // Rename: extension preservation (strip/restore)
    // ------------------------------------------------------------------

    @Test
    fun `rename composes the original extension onto the typed base`() {
        val check = checkRenameInput("Beach")
        assertTrue("plain base is valid", check is RenameCheck.Valid)
        assertEquals(
            "base + original .jpg",
            "Beach.jpg",
            renameDisplayName("Sunset.jpg", (check as RenameCheck.Valid).baseName),
        )
    }

    @Test
    fun `rename strips a retyped extension and restores the original`() {
        val check = checkRenameInput("Beach.png") // typed a DIFFERENT extension
        assertEquals(
            "typed .png is stripped, original .jpg restored",
            "Beach.jpg",
            renameDisplayName("Sunset.jpg", (check as RenameCheck.Valid).baseName),
        )
    }

    @Test
    fun `rename keeps a source without extension extension-free`() {
        val check = checkRenameInput("Day 1")
        assertEquals(
            "no original extension -> nothing to restore",
            "Day 1",
            renameDisplayName("IMG_20260101", (check as RenameCheck.Valid).baseName),
        )
    }

    @Test
    fun `rename trailing dots are sanitized away`() {
        val check = checkRenameInput("Sunset.")
        assertEquals(
            "trailing dot dropped, extension restored",
            "Sunset.jpg",
            renameDisplayName("Sunset.jpg", (check as RenameCheck.Valid).baseName),
        )
    }

    @Test
    fun `extension helpers split names the way MediaStore does`() {
        assertEquals("jpg", extensionOf("Sunset.jpg"))
        assertEquals("gz", extensionOf("archive.tar.gz")) // last dot wins
        assertEquals("", extensionOf("noext"))
        assertEquals("", extensionOf(".hidden")) // leading dot is the name
        assertEquals("Sunset", baseNameOf("Sunset.jpg"))
        assertEquals("archive.tar", baseNameOf("archive.tar.gz"))
        assertEquals(".hidden", baseNameOf(".hidden"))
        assertEquals("noext", composeName("noext", ""))
        assertEquals("base.ext", composeName("base", "ext"))
    }

    // ------------------------------------------------------------------
    // Rename validation: illegal characters + empty input
    // ------------------------------------------------------------------

    @Test
    fun `empty and blank renames are rejected`() {
        assertEquals(RenameCheck.Empty, checkRenameInput(""))
        assertEquals(RenameCheck.Empty, checkRenameInput("   "))
    }

    @Test
    fun `a leading dot is part of the name, not an extension`() {
        // ".jpg" typed as a whole input keeps its leading dot as the base
        // (the same rule that keeps dotfiles possible); the original
        // extension composes after it.
        val check = checkRenameInput(".jpg")
        assertTrue("dot-only input is still a name", check is RenameCheck.Valid)
        assertEquals(
            ".jpg.jpg",
            renameDisplayName("Sunset.jpg", (check as RenameCheck.Valid).baseName),
        )
    }

    @Test
    fun `path separators are rejected, not mangled`() {
        assertEquals(RenameCheck.Separator, checkRenameInput("a/b"))
        assertEquals(RenameCheck.Separator, checkRenameInput("folder\\name"))
        // The separator check fires before extension stripping, so a name
        // that would "hide" a separator behind an extension is still caught.
        assertEquals(RenameCheck.Separator, checkRenameInput("a/b.jpg"))
    }

    @Test
    fun `dot-only names are rejected`() {
        assertEquals(RenameCheck.DotName, checkRenameInput("."))
        assertEquals(RenameCheck.DotName, checkRenameInput(".."))
    }

    @Test
    fun `rename input is trimmed and a valid base extracted`() {
        val check = checkRenameInput("  Beach  ")
        assertEquals("trimmed base", RenameCheck.Valid("Beach"), check)
    }

    // ------------------------------------------------------------------
    // Batch aggregation
    // ------------------------------------------------------------------

    @Test
    fun `a fully successful batch summarizes to all-success`() {
        val summary = summarizeBatch(
            listOf(FileOpOutcome.Success, FileOpOutcome.Success, FileOpOutcome.Success),
        )
        assertEquals(3, summary.total)
        assertEquals(3, summary.succeeded)
        assertEquals(0, summary.partials)
        assertEquals(0, summary.needingConsent)
        assertEquals(0, summary.failed)
        assertTrue(!summary.cancelled)
        assertEquals("changed counts successes", 3, summary.changed)
    }

    @Test
    fun `move partials count as changed but not plain successes`() {
        val summary = summarizeBatch(
            listOf(
                FileOpOutcome.Success,
                FileOpOutcome.Partial(FileOpFailureReason.SECURITY),
                FileOpOutcome.Partial(FileOpFailureReason.IO),
                FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND),
                FileOpOutcome.NeedsConsent,
            ),
        )
        assertEquals(5, summary.total)
        assertEquals(1, summary.succeeded)
        assertEquals(2, summary.partials)
        assertEquals(1, summary.needingConsent)
        assertEquals(1, summary.failed)
        assertEquals("successes + partials landed where intended", 3, summary.changed)
    }

    @Test
    fun `an empty batch summarizes to zeros without crashing`() {
        val summary = summarizeBatch(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.changed)
    }

    @Test
    fun `a cancelled batch reports the collected prefix with the flag set`() {
        // A cancel keeps the honest prefix (say 2 collected of a 12-item
        // run); `cancelled` is caller-provided, never inferred.
        val summary = summarizeBatch(
            listOf(FileOpOutcome.Success, FileOpOutcome.Success),
            cancelled = true,
        )
        assertEquals(2, summary.total)
        assertEquals(2, summary.succeeded)
        assertTrue(summary.cancelled)
    }

    @Test
    fun `mixed batch distinguishes every outcome kind`() {
        val summary = summarizeBatch(
            listOf(
                FileOpOutcome.Success,                                       // copied
                FileOpOutcome.NeedsConsent,                                  // delete consent
                FileOpOutcome.Partial(FileOpFailureReason.SECURITY),          // move half-done
                FileOpOutcome.Failure(FileOpFailureReason.INVALID_NAME),      // rename refused
                FileOpOutcome.Failure(FileOpFailureReason.IO),                // stream broke
                FileOpOutcome.Success,
            ),
        )
        assertEquals(6, summary.total)
        assertEquals(2, summary.succeeded)
        assertEquals(1, summary.partials)
        assertEquals(1, summary.needingConsent)
        assertEquals(2, summary.failed)
    }
}
