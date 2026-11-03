package io.github.dukeoftoad.gallery.ui

import io.github.dukeoftoad.gallery.edit.contract.EditorIdentity
import io.github.dukeoftoad.gallery.ui.viewer.EditorCandidate
import io.github.dukeoftoad.gallery.ui.viewer.EditorResolution
import io.github.dukeoftoad.gallery.ui.viewer.EditorResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gallery's editor-resolution rule (r29, Issue #14 gate): pure-JVM
 * tests over [EditorResolver.resolve], the exact identity match + enabled
 * flag + version negotiation. The Android PackageManager adapter is a thin
 * mapper into these candidates (device-level round-trip is the dogfood
 * checklist's section).
 */
class EditorResolverTest {

    private val editor = EditorCandidate(
        packageName = "io.github.dukeoftoad.gallery.edit",
        activityClassName = "io.github.dukeoftoad.gallery.edit.EditActivity",
        declaredVersion = 1,
        enabled = true,
    )

    @Test
    fun `a release editor at the contract version is ready`() {
        val resolution = EditorResolver.resolve(listOf(editor))
        assertEquals(
            EditorResolution.Ready(
                packageName = "io.github.dukeoftoad.gallery.edit",
                activityClassName = "io.github.dukeoftoad.gallery.edit.EditActivity",
                negotiatedVersion = 1,
            ),
            resolution,
        )
    }

    @Test
    fun `the debug dogfood editor resolves too`() {
        val debugEditor = editor.copy(packageName = "io.github.dukeoftoad.gallery.edit.debug")
        val resolution = EditorResolver.resolve(listOf(debugEditor))
        assertEquals(
            EditorResolution.Ready(
                packageName = "io.github.dukeoftoad.gallery.edit.debug",
                activityClassName = "io.github.dukeoftoad.gallery.edit.EditActivity",
                negotiatedVersion = 1,
            ),
            resolution,
        )
    }

    @Test
    fun `no matching package answers not-installed`() {
        val foreign = listOf(
            editor.copy(packageName = "com.example.editor"),
            editor.copy(packageName = "io.github.dukeoftoad.gallery.edit.evil"),
        )
        assertEquals(EditorResolution.NotInstalled, EditorResolver.resolve(foreign))
        assertEquals(EditorResolution.NotInstalled, EditorResolver.resolve(emptyList()))
    }

    @Test
    fun `a disabled editor answers disabled before the version is even read`() {
        val resolution = EditorResolver.resolve(listOf(editor.copy(enabled = false)))
        assertEquals(EditorResolution.Disabled, resolution)
    }

    @Test
    fun `a below-minimum editor is outdated with its declared version`() {
        val resolution = EditorResolver.resolve(listOf(editor.copy(declaredVersion = 0)))
        assertEquals(EditorResolution.Outdated(0), resolution)
    }

    @Test
    fun `a missing meta-data marker is outdated with a null version`() {
        val resolution = EditorResolver.resolve(listOf(editor.copy(declaredVersion = null)))
        assertEquals(EditorResolution.Outdated(null), resolution)
    }

    @Test
    fun `a newer editor passes through as the negotiated version`() {
        val resolution = EditorResolver.resolve(listOf(editor.copy(declaredVersion = 2)))
        assertEquals(
            EditorResolution.Ready(
                packageName = "io.github.dukeoftoad.gallery.edit",
                activityClassName = "io.github.dukeoftoad.gallery.edit.EditActivity",
                negotiatedVersion = 2,
            ),
            resolution,
        )
    }

    @Test
    fun `non-editor candidates are ignored even when present`() {
        val resolution = EditorResolver.resolve(
            listOf(editor.copy(packageName = "com.example.something")),
        )
        assertEquals(EditorResolution.NotInstalled, resolution)
    }

    @Test
    fun `the accepted package name list mirrors the identity rule`() {
        val accepted = EditorIdentity.acceptedPackageNames()
        assertEquals(
            listOf(
                "io.github.dukeoftoad.gallery.edit",
                "io.github.dukeoftoad.gallery.edit.debug",
            ),
            accepted,
        )
        for (name in accepted) {
            assertTrue("'$name' must match", EditorIdentity.matches(name))
        }
    }
}
