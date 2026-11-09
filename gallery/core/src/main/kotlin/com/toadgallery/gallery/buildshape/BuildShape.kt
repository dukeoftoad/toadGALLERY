package com.toadgallery.gallery.buildshape

import com.toadgallery.gallery.BuildConfig

/**
 * The r46 (APK-matrix round) BUILD-SHAPE source of truth: one pure object
 * that reads the per-flavor BuildConfig fields exactly once and exposes
 * the three booleans every engine UI surface gates on.
 *
 * The matrix (apps/gallery/build.gradle.kts, the "engine" flavor
 * dimension; full narrative in docs/phase-11/apk-matrix.md):
 *
 *  - **basic** (`com.toadgallery.gallery`): the engine native
 *    libraries are NOT packaged and [withEngine] is false --- every
 *    engine UI surface (the People and Things album sections, the Search
 *    Content mode, the whole Settings AI section, the People/Person/
 *    Things routes) is hidden or answered with the honest
 *    "not part of this build" card. This APK is the offline-first core
 *    gallery, byte-for-byte the engine-free product the pre-r22 rounds
 *    shipped in spirit.
 *  - **engine / semantic / face** (`.engine` / `.semantic` / `.face`
 *    applicationId suffixes --- they install SEPARATELY from basic, like
 *    the edit app): these carry the `-PwithEngine`-packaged native
 *    libraries (libtoad_ml_jni.so + libonnxruntime.so; the packaging
 *    itself stays property-gated in the build script, r22/ADR-0020) and
 *    gate their index pipelines per flavor: [faceIndex] only on `engine`
 *    and `face`, [semanticIndex] only on `engine` and `semantic`.
 *
 * The derived flags are deliberately CONJUNCTIVE
 * (`withEngine && BuildConfig.INDEX_*`): an index can never be on in a
 * build whose engine library is absent, no matter what a future flavor
 * says --- the invariant is pinned by BuildShapeTest under every flavor.
 *
 * Pure data, no Android dependencies, no laziness: reading [BuildConfig]
 * fields is a constant read, and a `val` initialized once keeps every
 * call site's answer consistent for the whole process lifetime.
 */
object BuildShape {

    /**
     * Whether this build carries the engine library at all (the basic
     * APK does not; the engine family does). The master switch: both
     * index flags below imply this one.
     */
    val withEngine: Boolean = BuildConfig.WITH_ENGINE

    /**
     * Whether the on-device FACE index pipeline (People, Person detail,
     * the face queue and its consent) exists in this build.
     */
    val faceIndex: Boolean = withEngine && BuildConfig.INDEX_FACE

    /**
     * Whether the on-device SEMANTIC index pipeline (Magic Search,
     * Things, the clip queue and its consents) exists in this build.
     */
    val semanticIndex: Boolean = withEngine && BuildConfig.INDEX_SEMANTIC
}
