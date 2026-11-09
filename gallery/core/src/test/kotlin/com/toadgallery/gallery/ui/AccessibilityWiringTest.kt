package com.toadgallery.gallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r40 accessibility-wiring conformance suite (the Phase 10 hardening
 * round's executable half of the a11y sweep).
 *
 * These are SOURCE-SCAN tests in the established pure-JVM style of
 * `PrivacyPolicyTest` (which pins the manifest permission set by reading
 * the source tree): the sweep's fixes are Compose semantics WIRING, which
 * no emulator-free harness can execute --- but the wiring itself is text in
 * the tree, and the r37 lesson ("CI compiles what's there --- it cannot
 * test for absence") is exactly what a source-scan test is for. The suite
 * pins, per screen file:
 *
 *  - every primary screen title carries `semantics { heading() }` (the
 *    TalkBack heading anchor the r40 sweep landed);
 *  - every dynamic count/progress surface carries a polite live region
 *    (`liveRegion = LiveRegionMode.Polite`) --- selection app bars, the
 *    batch-op progress dialogs, the vault progress dialog and the viewer
 *    counter;
 *  - the Things chip's remove control keeps NO explicit size shrink (the
 *    r40 touch-target fix: the 32dp override that broke the 48dp
 *    `ToadTouchTarget.minimum` floor is pinned ABSENT);
 *  - the editor surface (apps/edit EditActivity) carries the same heading
 *    convention on its top-bar title.
 *
 * A failing assertion here means an a11y wiring was removed or a new screen
 * landed without the convention --- restore the wiring or extend this suite
 * with the new screen; do not relax it.
 */
class AccessibilityWiringTest {

    /** The module-relative prefix of every gallery screen source file. */
    private val GALLERY_SRC =
        "src/main/kotlin/io/github/dukeoftoad/gallery/"

    /** The edit module's one touched source file (full module-relative path). */
    private val EDIT_ACTIVITY =
        "src/main/kotlin/io/github/dukeoftoad/gallery/edit/EditActivity.kt"

    /**
     * Unit-test working dir is the module dir under Gradle (apps/gallery)
     * but is the repo root under the pre-push harness; be tolerant of both
     * (the PrivacyPolicyTest moduleFile pattern, generalized to both apps).
     * The THIRD candidate is the sibling-module case --- the gallery test
     * task locating the EDIT app's file (apps/gallery cwd -> apps/edit);
     * the merge-gate's second catch: the first draft had
     * `cwd.parent + apps/<app>/...`, which resolves `apps/apps/edit/...`
     * and never exists.
     */
    private fun sourceFile(app: String, moduleRelative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, moduleRelative),
            File(cwd, "apps/$app/$moduleRelative"),
            File(cwd.parentFile, "$app/$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate apps/$app/$moduleRelative from ${cwd.absolutePath}; " +
                    "checked: " + candidates.joinToString { it.path },
            )
    }

    private fun sourceOf(app: String, moduleRelative: String): String =
        sourceFile(app, moduleRelative).readText()

    private fun assertHasHeading(app: String, moduleRelative: String, label: String) {
        val source = sourceOf(app, moduleRelative)
        assertTrue(
            "$label must carry the r40 heading semantics on its screen title " +
                "(Modifier.semantics { heading() }); the sweep's convention.",
            source.contains(".semantics { heading() }"),
        )
    }

    private fun assertHasLiveRegion(app: String, moduleRelative: String, label: String) {
        val source = sourceOf(app, moduleRelative)
        assertTrue(
            "$label must carry a polite live region on its dynamic surface " +
                "(semantics { liveRegion = LiveRegionMode.Polite }).",
            source.contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    // ---- The heading convention: every primary screen title ----

    @Test
    fun `every gallery screen title carries heading semantics`() {
        val screens = listOf(
            GALLERY_SRC + "ui/photos/PhotosScreen.kt" to "the Photos screen",
            GALLERY_SRC + "ui/trash/TrashScreen.kt" to "the Trash screen",
            GALLERY_SRC + "ui/details/DetailsScreen.kt" to "the Details screen",
            GALLERY_SRC + "ui/vault/VaultScreen.kt" to "the Vault screen",
            GALLERY_SRC + "ui/places/PlacesScreen.kt" to "the Places screen",
            GALLERY_SRC + "ui/places/PlaceDetailScreen.kt" to "the place detail screen",
            GALLERY_SRC + "ui/people/PeopleScreen.kt" to "the People screen",
            GALLERY_SRC + "ui/people/PersonDetailScreen.kt" to "the person detail screen",
            GALLERY_SRC + "ui/things/ThingsDetailScreen.kt" to "the Things collection detail screen",
            GALLERY_SRC + "ui/albums/AlbumDetailScreen.kt" to "the album detail screen",
            GALLERY_SRC + "ui/albums/AlbumsScreen.kt" to "the Albums section headers",
            GALLERY_SRC + "ui/picker/PickedPhotosScreen.kt" to "the picked-photos screen",
            GALLERY_SRC + "ui/collage/CollageScreen.kt" to "the collage creator",
            GALLERY_SRC + "ui/settings/SettingsScreen.kt" to "the Settings screen + section headers",
            GALLERY_SRC + "ui/settings/AboutScreen.kt" to "the About screen",
            // r64: ui/settings/ProgressScreen.kt is REMOVED (the maintainer's
            // "remove development progress" directive) --- the product UI no
            // longer carries the project's dev dashboard.
        )
        screens.forEach { (file, label) -> assertHasHeading("gallery", file, label) }
    }

    @Test
    fun `the editor top-bar title carries the heading convention`() {
        assertHasHeading("edit", EDIT_ACTIVITY, "the editor surface")
    }

    // ---- The live-region convention: dynamic count/progress surfaces ----

    @Test
    fun `every selection app bar count is a polite live region`() {
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/photos/PhotosScreen.kt", "the Photos selection bar",
        )
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/albums/AlbumDetailScreen.kt",
            "the album-detail selection bar",
        )
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/people/PersonDetailScreen.kt",
            "the person selection bar",
        )
    }

    @Test
    fun `every batch progress surface is a polite live region`() {
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/photos/PhotosScreen.kt",
            "the file-ops + vault progress dialogs (Photos side)",
        )
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/albums/AlbumDetailScreen.kt",
            "the file-ops progress dialog (album-detail side)",
        )
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/vault/VaultScreen.kt", "the vault busy-progress dialog",
        )
    }

    @Test
    fun `the viewer position counter is a polite live region`() {
        assertHasLiveRegion(
            "gallery", GALLERY_SRC + "ui/viewer/ViewerScreen.kt", "the viewer counter",
        )
    }

    // ---- The touch-target floor: the r40 chip fix stays fixed ----

    @Test
    fun `the things chip remove control carries no sub-floor size shrink`() {
        val source = sourceOf("gallery", GALLERY_SRC + "ui/albums/AlbumsScreen.kt")
        // The r40 fix removed the .size(32.dp) shrink directly above the
        // chip remove button's testTag (IconButton's own
        // minimumInteractiveComponentSize enforces the 48dp floor). The pin
        // is LOCAL: any OTHER .size( in the file is a different surface
        // (e.g. the favorites badge), pinned by its own convention.
        val shrinkAboveRemove = Regex(
            "\\.size\\(\\d+\\.dp\\)\\s*\\n\\s*\\.testTag\\(\"albums_things_chip_remove\"\\)",
        )
        assertFalse(
            "The Things chip's remove IconButton must keep no explicit size " +
                "override below the 48dp ToadTouchTarget.minimum floor (the " +
                "r40 fix removed the .size(32.dp) shrink).",
            shrinkAboveRemove.containsMatchIn(source),
        )
    }

    @Test
    fun `the design token floor is still the 48dp minimum the sweep relies on`() {
        val source = sourceOf("gallery", GALLERY_SRC + "ui/theme/DesignTokens.kt")
        assertTrue(
            "ToadTouchTarget.minimum is the a11y floor the sweep relies on; " +
                "DesignTokensTest pins the value, this pins the object exists.",
            source.contains("val minimum: Dp = 48.dp"),
        )
    }
}
