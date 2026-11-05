package io.github.dukeoftoad.gallery.edit.contract

/**
 * The r46 contract-version marker precedence rule (spec section 1, the
 * discovery-hardening round): the editor's declared contract version is
 * read from the `org.toadgallery.contract.EDIT` meta-data, which editor
 * builds declare at TWO levels --- on the contract launch ACTIVITY (the
 * original r29 declaration, the only marker older editors carry) and,
 * since r46, also on the APPLICATION (the hardened fallback).
 *
 * WHY two levels exist: some PackageManager paths populate only one of the
 * two bundles. The `ActivityInfo` handed back by `queryIntentActivities`
 * is not guaranteed to carry its `metaData` across Android versions and
 * query-flag combinations (the documented flag for meta-data population is
 * `GET_META_DATA`, which the gallery's query historically omitted), while
 * a direct `getApplicationInfo(pkg, GET_META_DATA)` reliably fills only
 * the application level. A resolver that reads a single bundle can
 * therefore observe an installed, up-to-date editor as "no marker at all"
 * --- the exact field symptom the r46 round closes ("Edit is outdated for
 * this gallery's contract" with both current APKs installed).
 *
 * The precedence BOTH apps agree on:
 *  - a valid (non-null) ACTIVITY-level value wins: the oldest editors
 *    declare only here, and the launch activity is the spec's original
 *    home for the declaration (the v1 frozen discovery table);
 *  - otherwise the APPLICATION-level value (the r46 hardened fallback);
 *  - null only when BOTH are absent --- an honest missing marker, which
 *    [EditorVersionNegotiator] refuses exactly as it always has.
 *
 * This object is deliberately PURE (no Android types, like the rest of the
 * module): the Android adapters (the gallery's `EditorLaunch.kt`) do the
 * bundle reading and feed the two nullable Ints in; the precedence
 * decision lives here so both apps and the JVM test suite share one rule.
 */
object EditorVersionMetadata {

    /**
     * Resolves the editor's declared contract version from the two
     * possible marker levels. Precedence: activity level first,
     * application level second, `null` only when both are absent. The
     * rule is precedence-ONLY: validity of a present value (e.g. a
     * non-positive garbage declaration) is the negotiator's job, not
     * this function's.
     */
    fun pick(activityLevel: Int?, applicationLevel: Int?): Int? =
        activityLevel ?: applicationLevel
}
