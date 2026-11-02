package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the composite fan-out's per-hook failure isolation (r16
 * seam, moved to this package by the r20 round). A throwing hook must
 * never starve its sibling and never propagate --- the driver's own
 * catch-all is the second lock; this suite pins the first.
 */
class CompositeScanIndexHookTest {

    private fun record(key: String): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/$key",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
        )

    @Test
    fun `a throwing hook does not starve its sibling`() = runBlocking {
        val seen = mutableListOf<CatalogueRecord>()
        val healthy: ScanIndexHook = ScanIndexHook { added, _ ->
            seen += added
        }
        val throwing: ScanIndexHook = ScanIndexHook { _, _ -> error("boom") }
        val composite = CompositeScanIndexHook(listOf(throwing, healthy))
        val rows = listOf(record("external_primary:1"), record("external_primary:2"))

        // The composite logs the sibling's failure through DiagLog, whose
        // default sink touches android.util.Log --- unmocked on the JVM.
        // Swap it (the DiagLogTest pattern) and restore it no matter how
        // the test ends.
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        try {
            composite.onRecordsApplied(rows, emptyList())
        } finally {
            DiagLog.sink = stockSink
        }

        // The sibling ran despite the throw, with the full batch, and the
        // failure was contained (not propagated --- the scan survives
        // either way; the driver's catch-all is the second lock).
        assertEquals(rows, seen)
    }

    @Test
    fun `an empty composite is a no-op`() = runBlocking {
        val composite = CompositeScanIndexHook(emptyList())
        composite.onRecordsApplied(emptyList(), emptyList())
        assertTrue(true) // reaching here is the contract: no crash, no work
    }
}
