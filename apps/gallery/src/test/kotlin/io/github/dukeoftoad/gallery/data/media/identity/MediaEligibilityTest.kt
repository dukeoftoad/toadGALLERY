package io.github.dukeoftoad.gallery.data.media.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 4 eligibility gate
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, item 7).
 *
 * The audit's vault-leakage requirement is structural: no API should let
 * a caller accidentally pass a vault item, a trashed row or a
 * permission-revoked item into the AI pipeline. That is only true if
 * [MediaEligibility.asEligible] is non-null for PUBLIC_INDEXABLE and
 * ONLY for it, and if [EligiblePublicMedia] really does carry the wrapped
 * [MediaIdentity] downstream code needs.
 */
class MediaEligibilityTest {

    private val identity = MediaIdentity(
        volumeName = "external_primary",
        mediaStoreId = 42,
        canonicalUri = "content://media/external/external_primary/images/42",
    )

    @Test
    fun `the contract has exactly the six audited states`() {
        assertEquals(
            listOf(
                MediaEligibility.State.PUBLIC_INDEXABLE,
                MediaEligibility.State.VAULT_EXCLUDED,
                MediaEligibility.State.TRASHED,
                MediaEligibility.State.PERMISSION_REVOKED,
                MediaEligibility.State.UNSUPPORTED,
                MediaEligibility.State.CORRUPT,
            ),
            MediaEligibility.State.entries,
        )
    }

    @Test
    fun `public indexable media passes the gate`() {
        val eligible = MediaEligibility.PUBLIC_INDEXABLE.asEligible(identity)
        assertNotNull(eligible)
        // The wrapper must expose the identity it vouches for --- that is
        // the handle every downstream read/decode/index call consumes.
        assertEquals(identity, eligible!!.identity)
    }

    @Test
    fun `every excluded state is rejected by the gate`() {
        val excluded = listOf(
            MediaEligibility.VAULT_EXCLUDED,
            MediaEligibility.TRASHED,
            MediaEligibility.PERMISSION_REVOKED,
            MediaEligibility(MediaEligibility.State.UNSUPPORTED),
            MediaEligibility(MediaEligibility.State.CORRUPT),
        )
        excluded.forEach { eligibility ->
            assertNull(
                "state ${eligibility.state} must not pass the gate",
                eligibility.asEligible(identity),
            )
        }
    }

    @Test
    fun `isPublicIndexable mirrors the state`() {
        assertTrue(MediaEligibility.PUBLIC_INDEXABLE.isPublicIndexable)
        listOf(
            MediaEligibility.VAULT_EXCLUDED,
            MediaEligibility.TRASHED,
            MediaEligibility.PERMISSION_REVOKED,
            MediaEligibility.unsupported("image/x-tiff"),
            MediaEligibility.corrupt("moov"),
        ).forEach { eligibility ->
            assertFalse(eligibility.isPublicIndexable)
        }
    }

    @Test
    fun `unsupported records the offending format as the reason`() {
        val eligibility = MediaEligibility.unsupported("image/x-adobe-dng")
        assertEquals(MediaEligibility.State.UNSUPPORTED, eligibility.state)
        assertEquals("image/x-adobe-dng", eligibility.reason)
    }

    @Test
    fun `corrupt records the failing stage as the reason`() {
        val eligibility = MediaEligibility.corrupt("moov atom missing")
        assertEquals(MediaEligibility.State.CORRUPT, eligibility.state)
        assertEquals("moov atom missing", eligibility.reason)
    }

    @Test
    fun `the reason is diagnostics-only and never part of the gate decision`() {
        // A reason attached to an eligible item (e.g. a note from the
        // scanner) must not block it, and a reasonless exclusion must
        // still exclude --- only the state decides.
        assertTrue(
            MediaEligibility(MediaEligibility.State.PUBLIC_INDEXABLE, reason = "scanned")
                .asEligible(identity) != null,
        )
        assertNull(
            MediaEligibility(MediaEligibility.State.VAULT_EXCLUDED)
                .asEligible(identity),
        )
    }

    @Test
    fun `the gate is per-item and never leaks state between calls`() {
        // Two identities asked in sequence: a rejected one must not poison
        // a later eligible one, and vice versa.
        assertNull(MediaEligibility.TRASHED.asEligible(identity))
        assertNotNull(MediaEligibility.PUBLIC_INDEXABLE.asEligible(identity))
        assertNull(MediaEligibility.TRASHED.asEligible(identity.copy(mediaStoreId = 43)))
    }
}
