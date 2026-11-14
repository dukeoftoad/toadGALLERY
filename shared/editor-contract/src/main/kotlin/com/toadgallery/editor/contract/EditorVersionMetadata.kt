package com.toadgallery.editor.contract

/**
 * The contract-version marker precedence rule: the editor's declared version is
 * read from the `org.toadgallery.contract.EDIT` meta-data, declared at TWO
 * levels — on the contract launch ACTIVITY (the only marker older editors
 * carry) and on the APPLICATION.
 *
 * Why two levels: some PackageManager paths populate only one bundle —
 * `queryIntentActivities` does not reliably populate `ActivityInfo.metaData`
 * (it needs the `GET_META_DATA` flag), while `getApplicationInfo(pkg,
 * GET_META_DATA)` fills only the application level. A single-bundle resolver
 * can observe an installed, up-to-date editor as "no marker at all".
 *
 * Precedence both apps agree on: a valid ACTIVITY-level value wins (the oldest
 * editors declare only there); otherwise the APPLICATION-level value; `null`
 * only when both are absent — the negotiator refuses it as always. Pure object
 * (no Android types): adapters do the bundle reading and feed the two Ints in.
 */
object EditorVersionMetadata {

    /**
     * Resolves the declared contract version from the two marker levels:
     * activity first, application second, `null` only when both are absent.
     * Precedence ONLY — validity of a present value is the negotiator's job.
     */
    fun pick(activityLevel: Int?, applicationLevel: Int?): Int? =
        activityLevel ?: applicationLevel
}
