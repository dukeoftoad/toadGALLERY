package com.toadgallery.editor.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r42 additive media-kind extension (v2): absent = image (the v1 wire
 * stays byte-identical for image launches), an unknown value is refused,
 * and the builder carries the extra only on video launches --- the
 * additive-versioning rule the EditorVersionNegotiator governs.
 */
class MediaKindLaunchTest {

    private fun baseExtras(mediaKind: String? = null) = RawExtras(
        contractVersion = 2,
        sourceUri = "content://media/external/video/media/42",
        operationHint = null,
        exportMode = EditContract.EXPORT_MODE_SAVE_COPY,
        metadataPolicy = EditContract.METADATA_POLICY_PRESERVE,
        mediaKind = mediaKind,
    )

    // ---- the enum's total parse ----

    @Test
    fun `the wire names are exactly image and video`() {
        assertEquals("image", MediaKind.IMAGE.wireName)
        assertEquals("video", MediaKind.VIDEO.wireName)
        assertEquals(MediaKind.IMAGE, MediaKind.fromWire("image"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromWire("video"))
        assertNull(MediaKind.fromWire("still"))
        assertNull(MediaKind.fromWire(null))
    }

    // ---- validation: the additive default and the honest refusal ----

    @Test
    fun `an absent media kind is the v1 image default`() {
        val validation = EditRequestValidator.validate(baseExtras(mediaKind = null), 1, 2)
        assertTrue(validation is RequestValidation.Valid)
        assertEquals(MediaKind.IMAGE, (validation as RequestValidation.Valid).request.mediaKind)
    }

    @Test
    fun `a video media kind parses through`() {
        val validation = EditRequestValidator.validate(baseExtras(mediaKind = "video"), 1, 2)
        assertTrue(validation is RequestValidation.Valid)
        assertEquals(MediaKind.VIDEO, (validation as RequestValidation.Valid).request.mediaKind)
    }

    @Test
    fun `an unknown media kind is a contract violation, never a silent fallback`() {
        val validation = EditRequestValidator.validate(baseExtras(mediaKind = "audio"), 1, 2)
        assertTrue(validation is RequestValidation.Invalid)
        val invalid = (validation as RequestValidation.Invalid).invalid
        assertEquals(EditContract.ERROR_VERSION, invalid.errorCode)
        assertTrue(
            "the reason must name the offending value",
            invalid.reason.contains("audio"),
        )
    }

    // ---- the builder: the v1 wire stays byte-identical for images ----

    @Test
    fun `an image launch carries no media-kind extra (v1 wire identical)`() {
        val request = (EditRequestValidator.validate(baseExtras(null), 1, 2)
            as RequestValidation.Valid).request
        val extras = EditLaunchExtrasBuilder.build(request)
        assertFalse(
            "the media-kind extra must not ride image launches",
            extras.any { it.first == EditContract.EXTRA_MEDIA_KIND },
        )
    }

    @Test
    fun `a video launch carries the media-kind extra`() {
        val request = (EditRequestValidator.validate(baseExtras("video"), 1, 2)
            as RequestValidation.Valid).request
        val extras = EditLaunchExtrasBuilder.build(request)
        assertEquals(
            EditContract.EXTRA_MEDIA_KIND to "video",
            extras.lastOrNull { it.first == EditContract.EXTRA_MEDIA_KIND },
        )
    }

    // ---- the version negotiation over v2 ----

    @Test
    fun `the contract version is 2 and still-additive (v1 editors accepted)`() {
        assertEquals(2, EditContract.CONTRACT_VERSION)
        assertEquals(1, EditContract.CONTRACT_VERSION_MIN)
        // A v1 editor still negotiates fine: the additive rule.
        assertEquals(
            LaunchDecision.Ready(1),
            EditorVersionNegotiator.negotiate(1),
        )
        assertEquals(
            LaunchDecision.Ready(2),
            EditorVersionNegotiator.negotiate(2),
        )
    }

    @Test
    fun `a video request against a v1-only accepted window is refused by version`() {
        // An editor compiled against the v1 contract (accepted max 1) must
        // refuse a v2 request through the existing version gate, not
        // silently mis-handle it.
        val validation = EditRequestValidator.validate(baseExtras("video"), 1, 1)
        assertTrue(validation is RequestValidation.Invalid)
    }
}
