package com.toadgallery.editor.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The r46 marker-precedence rule (spec section 1, the discovery-hardening
 * round): which of the editor's two `org.toadgallery.contract.EDIT`
 * meta-data levels wins when the gallery reads the declared version.
 */
class EditorVersionMetadataTest {

    @Test
    fun `a valid activity-level value wins over the application level`() {
        // The launch activity is the spec's original home for the marker;
        // when its bundle is readable it governs (the v1 frozen table).
        assertEquals(1, EditorVersionMetadata.pick(activityLevel = 1, applicationLevel = 2))
        assertEquals(2, EditorVersionMetadata.pick(activityLevel = 2, applicationLevel = 2))
    }

    @Test
    fun `a null activity level falls back to the application level`() {
        // The r46 hardened path: the query-result ActivityInfo bundle came
        // back unpopulated (the field-symptom population gap), so the
        // application-level marker answers.
        assertEquals(2, EditorVersionMetadata.pick(activityLevel = null, applicationLevel = 2))
    }

    @Test
    fun `an activity-level value still wins when the application level is absent`() {
        // Older editors (pre-r46) declare ONLY on the activity; the
        // application level reads null and must change nothing.
        assertEquals(1, EditorVersionMetadata.pick(activityLevel = 1, applicationLevel = null))
    }

    @Test
    fun `null only when both levels are absent`() {
        // The honest missing-marker case the negotiator refuses; pick must
        // not manufacture a value from nothing.
        assertNull(EditorVersionMetadata.pick(activityLevel = null, applicationLevel = null))
    }

    @Test
    fun `the rule is precedence-only --- present garbage passes through for the negotiator to refuse`() {
        // Validity is the EditorVersionNegotiator's job (a non-positive
        // declaration is refused as outdated there); pick must not silently
        // prefer the other level when a value IS present.
        assertEquals(0, EditorVersionMetadata.pick(activityLevel = 0, applicationLevel = 2))
        assertEquals(-3, EditorVersionMetadata.pick(activityLevel = -3, applicationLevel = null))
    }
}
