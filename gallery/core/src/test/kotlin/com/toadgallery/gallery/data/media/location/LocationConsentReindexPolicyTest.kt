package com.toadgallery.gallery.data.media.location

import com.toadgallery.gallery.data.media.location.LocationReindexAction.NOTHING_PENDING
import com.toadgallery.gallery.data.media.location.LocationReindexAction.NO_GRANT
import com.toadgallery.gallery.data.media.location.LocationReindexAction.NOT_REQUIRED
import com.toadgallery.gallery.data.media.location.LocationReindexAction.REPASS_USEFUL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the re-index-on-grant decision policy (r16 round; ADR-0016
 * --- [LocationConsentReindexPolicy] is pure Kotlin over three plain
 * inputs, so the whole decision table is pinned here without the Android
 * runtime).
 *
 * What is pinned:
 * - the decision LADDER order: below-API-29 decides first (NOT_REQUIRED,
 *   regardless of anything else --- the permission does not exist there),
 *   then the grant, then the pending count;
 * - the honest v1 semantics this round ships: REPASS_USEFUL is a DECISION,
 *   not a trigger --- v1 wires no automatic re-pass (the policy's KDoc
 *   documents why the existing driver seams cannot express "re-index rows
 *   whose content did not change"; the map screen surfaces the pending
 *   count instead of hiding the limitation).
 */
class LocationConsentReindexPolicyTest {

    @Test
    fun `below api 29 nothing is ever required - the permission does not exist`() {
        // required=false decides FIRST: even granted=true with withheld
        // rows pending cannot ask for a re-pass, because no grant could
        // ever change what those rows' reads produced.
        assertEquals(NOT_REQUIRED, decide(required = false, granted = false, pending = 0))
        assertEquals(NOT_REQUIRED, decide(required = false, granted = false, pending = 42))
        assertEquals(NOT_REQUIRED, decide(required = false, granted = true, pending = 0))
        assertEquals(NOT_REQUIRED, decide(required = false, granted = true, pending = 42))
    }

    @Test
    fun `ungranted on api 29 plus is a no-op - there is no grant to react to`() {
        assertEquals(NO_GRANT, decide(required = true, granted = false, pending = 0))
        assertEquals(NO_GRANT, decide(required = true, granted = false, pending = 99))
    }

    @Test
    fun `granted with nothing pending leaves nothing to re-index`() {
        assertEquals(NOTHING_PENDING, decide(required = true, granted = true, pending = 0))
    }

    @Test
    fun `granted with withheld rows is exactly the re-pass-useful state`() {
        assertEquals(REPASS_USEFUL, decide(required = true, granted = true, pending = 1))
        assertEquals(REPASS_USEFUL, decide(required = true, granted = true, pending = 500))
    }

    @Test
    fun `negative pending counts are treated as none - a count can never be negative`() {
        // Defensive: a broken COUNT query must degrade to the safe side
        // (no re-pass claimed), never to a nonsensical decision.
        assertEquals(NOTHING_PENDING, decide(required = true, granted = true, pending = -1))
        assertEquals(NO_GRANT, decide(required = true, granted = false, pending = -5))
    }

    /** Spell the call out once; the table above stays line-readable. */
    private fun decide(required: Boolean, granted: Boolean, pending: Int): LocationReindexAction =
        LocationConsentReindexPolicy.decide(
            permissionRequired = required,
            granted = granted,
            pendingUngrantedRows = pending,
        )
}
