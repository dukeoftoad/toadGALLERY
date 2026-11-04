package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The F-1 progress snapshot parse (follow-up 2026-11-04): the committed
 * progress.json --- the exact bytes the copyProgressJson build task
 * mirrors into the APK assets --- parses into the two independent scopes,
 * and the tolerant rules hold (malformed shapes are null, never a crash).
 */
class ProgressSnapshotTest {

    // ---- The committed source (what the build task mirrors) ----

    private fun committedProgressJson(): File {
        val candidates = listOf(
            File("progress.json"), // working dir = the repo root
            File("../../progress.json"), // working dir = the module (Gradle unit tests)
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("progress.json not found; tried ${candidates.map { it.path }}")
    }

    @Test
    fun `the committed progress json parses into the two scopes`() {
        val snapshot = ProgressSnapshot.parse(committedProgressJson().readText())
        assertNotNull(snapshot)
        snapshot!!
        assertNotNull(snapshot.updatedAt)
        assertTrue(snapshot.project.phases.isNotEmpty())
        assertTrue(snapshot.entePort.phases.isNotEmpty())
        assertTrue(snapshot.project.percent in 0..100)
        assertTrue(snapshot.entePort.percent in 0..100)
        // The two scopes are INDEPENDENT: distinct labels, distinct phase
        // id spaces, each percent its own stored value.
        assertTrue(snapshot.project.label.isNotBlank())
        assertTrue(snapshot.entePort.label.isNotBlank())
        assertTrue(snapshot.project.label != snapshot.entePort.label)
    }

    @Test
    fun `every committed phase carries one of the three known statuses`() {
        // Our own file's hygiene: the dashboard PATCHer only ever writes
        // done | in-progress | pending. A violation here means the file
        // was hand-edited past the tooling.
        val snapshot = ProgressSnapshot.parse(committedProgressJson().readText())!!
        for (phase in snapshot.project.phases + snapshot.entePort.phases) {
            assertTrue(
                "phase ${phase.id} carries status '${phase.status}'",
                phase.status in setOf("done", "in-progress", "pending"),
            )
            assertTrue("phase ${phase.id} must have a label", phase.label.isNotBlank())
        }
    }

    @Test
    fun `each scope has at least one done and one open phase`() {
        // The honest mid-project shape: 0% and 100% snapshots would both
        // render fine, but the committed truth is mid-flight --- pin that
        // the bar + count rows have real content to show on both ends.
        val snapshot = ProgressSnapshot.parse(committedProgressJson().readText())!!
        for (scope in listOf(snapshot.project, snapshot.entePort)) {
            assertTrue(scope.phases.any { it.status == "done" })
            assertTrue(scope.phases.any { it.status != "done" })
        }
    }

    // ---- The tolerant parse ----

    @Test
    fun `a malformed document is null, never a crash`() {
        assertNull(ProgressSnapshot.parse("not json at all"))
        assertNull(ProgressSnapshot.parse("{}"))
        assertNull(ProgressSnapshot.parse("{\"project\":{}}"))
    }

    @Test
    fun `a scope without phases is null and a missing scope is null`() {
        val oneScope = """
            {"project":{"label":"p","percent":50,"phases":[{"id":"p0","label":"Phase 0","status":"done"}]},
             "entePort":{"label":"e","percent":10,"phases":[]}}
        """.trimIndent()
        assertNull(ProgressSnapshot.parse(oneScope))
        val noEnte = """
            {"project":{"label":"p","percent":50,"phases":[{"id":"p0","label":"Phase 0","status":"done"}]}}
        """.trimIndent()
        assertNull(ProgressSnapshot.parse(noEnte))
    }

    @Test
    fun `defaults are honest and unknown statuses ride verbatim`() {
        val text = """
            {"updatedAt":"","project":{"percent":150,"phases":[{"id":"p0","status":"weird-status"}]},
             "entePort":{"percent":-5,"phases":[{"id":"e0","label":"","status":"done","evidence":""}]}}
        """.trimIndent()
        val snapshot = ProgressSnapshot.parse(text)!!
        assertNull(snapshot.updatedAt) // an empty string reads as absent
        // Out-of-range percents clamp.
        assertEquals(100, snapshot.project.percent)
        assertEquals(0, snapshot.entePort.percent)
        // A phase without a label falls back to its id; an unknown status
        // is kept verbatim (the screen shows the file's own word).
        assertEquals("p0", snapshot.project.phases.single().label)
        assertEquals("weird-status", snapshot.project.phases.single().status)
        assertNull(snapshot.project.phases.single().evidence)
        assertNull(snapshot.entePort.phases.single().evidence)
    }

    @Test
    fun `a blank-id phase is skipped, the rest survive`() {
        val text = """
            {"project":{"percent":50,"phases":[{"id":"","status":"done"},{"id":"p1","status":"done"}]},
             "entePort":{"percent":50,"phases":[{"id":"e1","status":"done"}]}}
        """.trimIndent()
        val snapshot = ProgressSnapshot.parse(text)!!
        assertEquals(listOf("p1"), snapshot.project.phases.map { it.id })
        assertEquals(listOf("e1"), snapshot.entePort.phases.map { it.id })
    }
}
