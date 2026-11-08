package io.github.dukeoftoad.gallery.edit

/**
 * The unified-flavor R compatibility shim (the r63 device fix, F-74
 * follow-through, CI round 3).
 *
 * The unified flavor compiles apps/edit's Kotlin sources in-process under
 * THIS module (apps/gallery, namespace io.github.dukeoftoad.gallery), so
 * the editor's module-local R --- io.github.dukeoftoad.gallery.edit.R, the
 * namespace the standalone edit app generates --- has no generated target
 * here, and every R.string / R.drawable reference in the embedded sources
 * is unresolved (CI run #12's error list: only 'string' and 'drawable'
 * references, nothing else --- the classpath wiring itself is complete).
 *
 * The editor's RESOURCES are merged into the unified APK (the flavor's
 * res.srcDirs), so every one of those resources exists in THIS module's
 * generated R under the same name --- this alias points the editor
 * package's R at it. `android.nonTransitiveRClass=true` does not get in
 * the way: non-transitivity governs LIBRARY dependency resources, and the
 * editor res rides the flavor source set of the module itself, so it is
 * module-OWN and always in its R.
 *
 * Zero source churn in apps/edit: the standalone edit app keeps its real
 * generated R and never sees this file (it lives in the gallery module's
 * unified flavor source dir, compiled by that module only).
 */
typealias R = io.github.dukeoftoad.gallery.R
