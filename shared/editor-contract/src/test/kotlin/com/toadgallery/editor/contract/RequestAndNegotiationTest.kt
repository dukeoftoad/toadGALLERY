package com.toadgallery.editor.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity + version-negotiation rules (spec section 1). */
class EditorNegotiationTest {

    // ---- EditorIdentity ----

    @Test
    fun `exact release application id matches`() {
        assertTrue(EditorIdentity.matches("com.toadgallery.editor"))
    }

    @Test
    fun `exact debug dogfood application id matches`() {
        assertTrue(EditorIdentity.matches("com.toadgallery.editor.debug"))
    }

    @Test
    fun `null and empty never match`() {
        assertFalse(EditorIdentity.matches(null))
        assertFalse(EditorIdentity.matches(""))
    }

    @Test
    fun `foreign packages never match`() {
        assertFalse(EditorIdentity.matches("com.example.spoofed"))
        assertFalse(EditorIdentity.matches("com.toadgallery.gallery"))
    }

    @Test
    fun `hostile prefix extensions never match`() {
        // Only the exact id and the exact .debug suffix are accepted; any
        // further suffix is a different (potentially spoofed) package.
        assertFalse(EditorIdentity.matches("com.toadgallery.editor.evil"))
        assertFalse(EditorIdentity.matches("com.toadgallery.editor.debug.extra"))
    }

    // ---- EditorVersionNegotiator ----

    @Test
    fun `declared version at the minimum is ready`() {
        val decision = EditorVersionNegotiator.negotiate(1)
        assertEquals(LaunchDecision.Ready(1), decision)
    }

    @Test
    fun `newer declared version passes through as the negotiated version`() {
        // Additive versioning: a v2 editor must still honour v1 requests.
        val decision = EditorVersionNegotiator.negotiate(2)
        assertEquals(LaunchDecision.Ready(2), decision)
    }

    @Test
    fun `declared version below the minimum is outdated`() {
        val decision = EditorVersionNegotiator.negotiate(0)
        assertEquals(LaunchDecision.Outdated(0), decision)
    }

    @Test
    fun `missing meta-data is refused as missing version`() {
        assertEquals(LaunchDecision.MissingVersionMetadata, EditorVersionNegotiator.negotiate(null))
    }

    @Test
    fun `negative garbage meta-data is refused as outdated`() {
        val decision = EditorVersionNegotiator.negotiate(-3)
        assertEquals(LaunchDecision.Outdated(-3), decision)
    }
}

/** Request validation (spec section 2), shared by both apps. */
class EditRequestValidatorTest {

    private val valid = RawExtras(
        contractVersion = 1,
        sourceUri = "content://media/external/images/media/42",
        operationHint = "adjust",
        exportMode = "save_copy",
        metadataPolicy = "preserve",
    )

    private fun validate(
        raw: RawExtras = valid,
        min: Int = EditContract.CONTRACT_VERSION_MIN,
        max: Int = EditContract.CONTRACT_VERSION,
    ): RequestValidation = EditRequestValidator.validate(raw, min, max)

    @Test
    fun `a fully-formed request validates`() {
        val result = validate()
        val request = (result as RequestValidation.Valid).request
        assertEquals("content://media/external/images/media/42", request.sourceUri)
        assertEquals(1, request.contractVersion)
        assertEquals("save_copy", request.exportMode)
        assertEquals(MetadataPolicy.PRESERVE, request.metadataPolicy)
        assertEquals(OperationHint.ADJUST, request.operationHint)
    }

    @Test
    fun `missing contract version is an ERROR_VERSION refusal`() {
        val result = validate(raw = valid.copy(contractVersion = null))
        val invalid = (result as RequestValidation.Invalid).invalid
        assertEquals(EditContract.ERROR_VERSION, invalid.errorCode)
    }

    @Test
    fun `version outside the accepted window is an ERROR_VERSION refusal`() {
        val tooNew = validate(raw = valid.copy(contractVersion = 2), min = 1, max = 1)
        assertEquals(EditContract.ERROR_VERSION, (tooNew as RequestValidation.Invalid).invalid.errorCode)
        val tooOld = validate(raw = valid.copy(contractVersion = 0))
        assertEquals(EditContract.ERROR_VERSION, (tooOld as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `missing source uri is an ERROR_VERSION refusal`() {
        val result = validate(raw = valid.copy(sourceUri = null))
        assertEquals(EditContract.ERROR_VERSION, (result as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `blank source uri is an ERROR_VERSION refusal`() {
        val result = validate(raw = valid.copy(sourceUri = "   "))
        assertEquals(EditContract.ERROR_VERSION, (result as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `absolute filesystem paths are refused --- the contract carries content uris only`() {
        val fileUri = validate(raw = valid.copy(sourceUri = "file:///storage/emulated/0/DCIM/x.jpg"))
        assertEquals(EditContract.ERROR_VERSION, (fileUri as RequestValidation.Invalid).invalid.errorCode)
        val barePath = validate(raw = valid.copy(sourceUri = "/storage/emulated/0/DCIM/x.jpg"))
        assertEquals(EditContract.ERROR_VERSION, (barePath as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `missing export mode is an ERROR_VERSION refusal`() {
        val result = validate(raw = valid.copy(exportMode = null))
        assertEquals(EditContract.ERROR_VERSION, (result as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `v1 offers exactly save_copy --- anything else is ERROR_UNSUPPORTED`() {
        val result = validate(raw = valid.copy(exportMode = "replace_original"))
        assertEquals(EditContract.ERROR_UNSUPPORTED, (result as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `metadata policy is required and total`() {
        val missing = validate(raw = valid.copy(metadataPolicy = null))
        assertEquals(EditContract.ERROR_VERSION, (missing as RequestValidation.Invalid).invalid.errorCode)
        val unknown = validate(raw = valid.copy(metadataPolicy = "maybe"))
        assertEquals(EditContract.ERROR_VERSION, (unknown as RequestValidation.Invalid).invalid.errorCode)
        assertEquals(
            MetadataPolicy.STRIP_LOCATION,
            (validate(raw = valid.copy(metadataPolicy = "strip_location")) as RequestValidation.Valid)
                .request.metadataPolicy,
        )
        assertEquals(
            MetadataPolicy.STRIP_ALL,
            (validate(raw = valid.copy(metadataPolicy = "strip_all")) as RequestValidation.Valid)
                .request.metadataPolicy,
        )
    }

    @Test
    fun `operation hint is optional but must be known`() {
        val noHint = validate(raw = valid.copy(operationHint = null))
        assertNull((noHint as RequestValidation.Valid).request.operationHint)
        val unknown = validate(raw = valid.copy(operationHint = "teleport"))
        assertEquals(EditContract.ERROR_VERSION, (unknown as RequestValidation.Invalid).invalid.errorCode)
    }

    @Test
    fun `every operation hint wire value round-trips`() {
        for (wire in EditContract.OPERATION_HINTS) {
            assertEquals(wire, OperationHint.fromWire(wire)!!.wireName)
        }
    }

    @Test
    fun `whitespace-padded source uri is trimmed into the request`() {
        val result = validate(raw = valid.copy(sourceUri = "  content://media/external/images/media/7  "))
        assertEquals(
            "content://media/external/images/media/7",
            (result as RequestValidation.Valid).request.sourceUri,
        )
    }
}

/** Launch-extras builder (gallery side). */
class EditLaunchExtrasBuilderTest {

    private val request = EditRequest(
        sourceUri = "content://media/external/images/media/42",
        contractVersion = 1,
        exportMode = "save_copy",
        metadataPolicy = MetadataPolicy.STRIP_LOCATION,
        operationHint = null,
    )

    @Test
    fun `the four required extras ride with typed values`() {
        val extras = EditLaunchExtrasBuilder.build(request).toMap()
        assertEquals(4, extras.size)
        assertEquals(1, extras[EditContract.EXTRA_CONTRACT_VERSION])
        assertEquals("content://media/external/images/media/42", extras[EditContract.EXTRA_SOURCE_URI])
        assertEquals("save_copy", extras[EditContract.EXTRA_EXPORT_MODE])
        assertEquals("strip_location", extras[EditContract.EXTRA_METADATA_POLICY])
    }

    @Test
    fun `an operation hint is appended only when present`() {
        val without = EditLaunchExtrasBuilder.build(request).toMap()
        assertFalse(without.containsKey(EditContract.EXTRA_OPERATION_HINT))
        val with = EditLaunchExtrasBuilder.build(request.copy(operationHint = OperationHint.CROP)).toMap()
        assertEquals("crop", with[EditContract.EXTRA_OPERATION_HINT])
    }

    @Test
    fun `the contract version extra is an Int, per the spec table`() {
        val version = EditLaunchExtrasBuilder.build(request)
            .first { it.first == EditContract.EXTRA_CONTRACT_VERSION }.second
        assertEquals(1, version)
    }
}
