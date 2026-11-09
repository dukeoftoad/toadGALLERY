package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy

/**
 * The per-export metadata-policy choice (contract section 4.4: "metadata
 * policy respected exactly --- GPS removal is opt-in, explicit, and
 * reported").
 *
 * The launch request's policy is the PRIVACY FLOOR. The export sheet may
 * strip MORE for this one export (an explicit, user-opted-in upgrade), but
 * it can never strip LESS: whatever the gallery requested always happens.
 * The policy the editor ACTUALLY applied is echoed back on RESULT_OK
 * (`EXTRA_METADATA_POLICY_APPLIED`) so the gallery's honesty UI narrates
 * the real outcome.
 *
 * The choice is per-export state ONLY: it never enters [EditRecipe] and
 * never persists to the ADR-0011 sidecar --- a privacy decision must not
 * ride the recipe into a future re-edit of the source.
 *
 * Pure: every decision here is JVM-tested ([ExportPolicyTest]).
 */
object ExportPolicyChoice {

    /**
     * The stripping-strength rank behind the floor comparison:
     * preserve (nothing removed) < strip-location (GPS removed) <
     * strip-all (every metadata tag removed).
     */
    fun stripRank(policy: MetadataPolicy): Int = when (policy) {
        MetadataPolicy.PRESERVE -> 0
        MetadataPolicy.STRIP_LOCATION -> 1
        MetadataPolicy.STRIP_ALL -> 2
    }

    /**
     * Resolves the policy actually applied for this export: the user's
     * per-export choice when it strips at least as much as the request
     * (the floor), else the request itself. A `null` choice means "no
     * override" --- the request rides as-is.
     */
    fun effectivePolicy(requested: MetadataPolicy, chosen: MetadataPolicy?): MetadataPolicy {
        if (chosen == null) return requested
        return if (stripRank(chosen) >= stripRank(requested)) chosen else requested
    }

    /**
     * Whether the export sheet must DISABLE a choice: selecting it would
     * weaken the request's floor, and the editor respects the requested
     * policy exactly (it never strips less than the gallery asked for).
     */
    fun isBlockedByFloor(requested: MetadataPolicy, chosen: MetadataPolicy): Boolean =
        stripRank(chosen) < stripRank(requested)

    /**
     * Whether the user's choice UPGRADED the stripping beyond the request
     * (an explicit opt-in the honesty narration may surface).
     */
    fun isUserUpgrade(requested: MetadataPolicy, chosen: MetadataPolicy?): Boolean =
        chosen != null && stripRank(chosen) > stripRank(requested)

    /**
     * Whether the applied policy removes geographic position (the
     * contract's "location removed" honesty-UI trigger).
     */
    fun removesLocation(policy: MetadataPolicy): Boolean =
        MetadataPolicyApplier.removedLocation(policy)
}
