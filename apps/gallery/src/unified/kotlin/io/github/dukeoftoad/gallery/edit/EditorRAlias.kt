package io.github.dukeoftoad.gallery.edit

/**
 * r59 (the unified-app round): the editor sources' R seam for the UNIFIED
 * build. The standalone edit app generates its own
 * `io.github.dukeoftoad.gallery.edit.R` (its module namespace); the unified
 * gallery build compiles the SAME editor sources inside the GALLERY module,
 * whose generated R lives one package up. This typealias makes every bare
 * `R.*` reference in the editor sources resolve to the app's generated R
 * (which contains all the editor resources through the flavor's sourceSets)
 * without touching a single editor source file. The standalone edit build
 * never sees this file (it lives only in the gallery's unified sourceSet),
 * so its own implicit R keeps resolving exactly as before.
 *
 * Member names never clash: the r59 de-collision renamed the editor's
 * launcher resources to editor_*, and every other editor resource name is
 * editor-prefixed; the values-merge is name-disjoint from the gallery's.
 *
 * r63 round 4 (F-74): the alias is PUBLIC now (was `internal`). Same-module
 * visibility should make internal irrelevant, but the CI rounds proved this
 * seam is the fragile layer of the whole in-process embedding --- every
 * variable that could matter is eliminated, one per round.
 */
typealias R = io.github.dukeoftoad.gallery.R
