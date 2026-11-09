package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-export metadata-policy choice (r31; contract section 4.4): the
 * launch request's policy is the PRIVACY FLOOR --- the export sheet may
 * strip more for one export, never less; the applied policy is what the
 * result echoes; and the choice never rides the recipe/sidecar.
 */
class ExportPolicyTest {

    // ---- effectivePolicy: the floor resolution ----

    @Test
    fun `a null choice rides the request as-is`() {
        assertEquals(
            MetadataPolicy.STRIP_LOCATION,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_LOCATION, null),
        )
        assertEquals(
            MetadataPolicy.PRESERVE,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.PRESERVE, null),
        )
    }

    @Test
    fun `an equal-strength choice applies as chosen`() {
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_ALL, MetadataPolicy.STRIP_ALL),
        )
        assertEquals(
            MetadataPolicy.PRESERVE,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.PRESERVE, MetadataPolicy.PRESERVE),
        )
    }

    @Test
    fun `a stronger choice applies as chosen (the explicit opt-in)`() {
        assertEquals(
            MetadataPolicy.STRIP_LOCATION,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.PRESERVE, MetadataPolicy.STRIP_LOCATION),
        )
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.PRESERVE, MetadataPolicy.STRIP_ALL),
        )
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_LOCATION, MetadataPolicy.STRIP_ALL),
        )
    }

    @Test
    fun `a weaker choice is held at the request's floor`() {
        // Every downgrade attempt resolves to the REQUEST, never the choice.
        assertEquals(
            MetadataPolicy.STRIP_LOCATION,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_LOCATION, MetadataPolicy.PRESERVE),
        )
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_ALL, MetadataPolicy.STRIP_LOCATION),
        )
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_ALL, MetadataPolicy.PRESERVE),
        )
    }

    // ---- isBlockedByFloor: the sheet's disabled-chip matrix ----

    @Test
    fun `the sheet blocks exactly the choices below the floor`() {
        val policies = MetadataPolicy.entries
        for (requested in policies) {
            for (chosen in policies) {
                val blocked = ExportPolicyChoice.isBlockedByFloor(requested, chosen)
                assertEquals(
                    "floor($requested) vs choice($chosen)",
                    ExportPolicyChoice.stripRank(chosen) < ExportPolicyChoice.stripRank(requested),
                    blocked,
                )
            }
        }
        // Spot pins: preserve floor blocks nothing; strip-all floor blocks both.
        assertFalse(ExportPolicyChoice.isBlockedByFloor(MetadataPolicy.PRESERVE, MetadataPolicy.PRESERVE))
        assertFalse(ExportPolicyChoice.isBlockedByFloor(MetadataPolicy.PRESERVE, MetadataPolicy.STRIP_ALL))
        assertTrue(ExportPolicyChoice.isBlockedByFloor(MetadataPolicy.STRIP_ALL, MetadataPolicy.PRESERVE))
        assertTrue(ExportPolicyChoice.isBlockedByFloor(MetadataPolicy.STRIP_ALL, MetadataPolicy.STRIP_LOCATION))
        assertFalse(ExportPolicyChoice.isBlockedByFloor(MetadataPolicy.STRIP_ALL, MetadataPolicy.STRIP_ALL))
    }

    // ---- isUserUpgrade: the opt-in narration flag ----

    @Test
    fun `only a strictly stronger choice counts as an upgrade`() {
        assertTrue(ExportPolicyChoice.isUserUpgrade(MetadataPolicy.PRESERVE, MetadataPolicy.STRIP_ALL))
        assertTrue(ExportPolicyChoice.isUserUpgrade(MetadataPolicy.STRIP_LOCATION, MetadataPolicy.STRIP_ALL))
        assertFalse(ExportPolicyChoice.isUserUpgrade(MetadataPolicy.PRESERVE, null))
        assertFalse(ExportPolicyChoice.isUserUpgrade(MetadataPolicy.PRESERVE, MetadataPolicy.PRESERVE))
        assertFalse(ExportPolicyChoice.isUserUpgrade(MetadataPolicy.STRIP_ALL, MetadataPolicy.PRESERVE))
    }

    // ---- removesLocation: the honesty-UI trigger ----

    @Test
    fun `location removal follows the APPLIED policy`() {
        assertFalse(ExportPolicyChoice.removesLocation(MetadataPolicy.PRESERVE))
        assertTrue(ExportPolicyChoice.removesLocation(MetadataPolicy.STRIP_LOCATION))
        assertTrue(ExportPolicyChoice.removesLocation(MetadataPolicy.STRIP_ALL))
    }

    // ---- The result echo carries the APPLIED policy ----

    @Test
    fun `the result echo carries the applied policy, not the request`() {
        // A preserve request upgraded to strip-all must echo strip-all ---
        // the gallery's honesty UI narrates what actually happened.
        val applied = ExportPolicyChoice.effectivePolicy(
            MetadataPolicy.PRESERVE,
            MetadataPolicy.STRIP_ALL,
        )
        assertEquals(MetadataPolicy.STRIP_ALL.wireName, ExportPlan.resultPolicy(applied))
        // And the no-override path echoes the request verbatim.
        val untouched = ExportPolicyChoice.effectivePolicy(MetadataPolicy.STRIP_LOCATION, null)
        assertEquals(MetadataPolicy.STRIP_LOCATION.wireName, ExportPlan.resultPolicy(untouched))
    }

    // ---- Per-export state: the policy never rides the recipe/sidecar ----

    @Test
    fun `the recipe serialization carries no policy key`() {
        // The ADR-0011 sidecar must not inherit a privacy decision: a
        // re-edit of the source starts from the request's floor again.
        val recipe = EditRecipe(quarterTurns = 1, saturation = 40)
        val json = EditRecipeCodec.toJson(recipe)
        assertFalse("policy leaked into the recipe", json.contains("polic", ignoreCase = true))
        // The sheet's default is "no override" --- the request rides as-is.
        assertNull(ExportChoice().metadataPolicy)
    }
}
