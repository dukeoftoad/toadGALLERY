package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy

/**
 * The per-export metadata-policy choice (contract 4.4: "metadata policy respected exactly ---
 * GPS removal is opt-in, explicit, and reported"). The launch request's policy is the PRIVACY
 * FLOOR: the export sheet may strip MORE for this one export (an explicit user opt-in), never
 * LESS; the policy actually applied is echoed back on RESULT_OK for the gallery's honesty UI.
 * The choice is per-export state ONLY: it never enters [EditRecipe] and never persists to the
 * sidecar --- a privacy decision must not ride the recipe into a future re-edit. Pure, JVM-tested.
 */
object ExportPolicyChoice {

    /** The stripping-strength rank behind the floor comparison: preserve < strip-location < strip-all. */
    fun stripRank(policy: MetadataPolicy): Int = when (policy) {
        MetadataPolicy.PRESERVE -> 0
        MetadataPolicy.STRIP_LOCATION -> 1
        MetadataPolicy.STRIP_ALL -> 2
    }

    /**
     * The policy actually applied: the user's per-export choice when it strips at least as
     * much as the request (the floor), else the request. `null` choice = no override.
     */
    fun effectivePolicy(requested: MetadataPolicy, chosen: MetadataPolicy?): MetadataPolicy {
        if (chosen == null) return requested
        return if (stripRank(chosen) >= stripRank(requested)) chosen else requested
    }

    /** Whether the sheet must DISABLE a choice: it would strip less than the gallery asked for. */
    fun isBlockedByFloor(requested: MetadataPolicy, chosen: MetadataPolicy): Boolean =
        stripRank(chosen) < stripRank(requested)

    /** Whether the choice UPGRADED the stripping beyond the request (an opt-in the honesty narration may surface). */
    fun isUserUpgrade(requested: MetadataPolicy, chosen: MetadataPolicy?): Boolean =
        chosen != null && stripRank(chosen) > stripRank(requested)

    /** Whether the applied policy removes geographic position (the "location removed" honesty trigger). */
    fun removesLocation(policy: MetadataPolicy): Boolean =
        MetadataPolicyApplier.removedLocation(policy)
}
