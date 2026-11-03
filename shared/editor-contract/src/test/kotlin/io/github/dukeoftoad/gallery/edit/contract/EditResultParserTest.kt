package io.github.dukeoftoad.gallery.edit.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Result parsing (spec section 3): all three terminal states, no exception paths. */
class EditResultParserTest {

    private val ok = RawResult(
        resultCode = EditContract.RESULT_OK,
        outputUri = "content://media/external/images/media/99",
        errorCode = null,
        errorMessage = null,
        metadataPolicyApplied = "preserve",
    )

    @Test
    fun `a well-formed OK parses into SavedCopy with the applied policy`() {
        val outcome = EditResultParser.parse(ok)
        assertEquals(
            EditOutcome.SavedCopy(
                outputUri = "content://media/external/images/media/99",
                metadataPolicyApplied = MetadataPolicy.PRESERVE,
            ),
            outcome,
        )
    }

    @Test
    fun `an absent applied-policy extra is tolerated --- pre-r29 editors`() {
        val outcome = EditResultParser.parse(ok.copy(metadataPolicyApplied = null))
        val saved = outcome as EditOutcome.SavedCopy
        assertNull(saved.metadataPolicyApplied)
    }

    @Test
    fun `OK without an output uri degrades to a structured internal error`() {
        val outcome = EditResultParser.parse(ok.copy(outputUri = null))
        assertEquals(
            EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "RESULT_OK without $${EditContract.EXTRA_OUTPUT_URI}",
            ),
            outcome,
        )
    }

    @Test
    fun `OK with a file-scheme output uri is refused --- content uris only`() {
        val outcome = EditResultParser.parse(ok.copy(outputUri = "file:///sdcard/x.jpg"))
        assertEquals(EditContract.ERROR_INTERNAL, (outcome as EditOutcome.Error).errorCode)
    }

    @Test
    fun `canceled parses into Canceled regardless of stray extras`() {
        val outcome = EditResultParser.parse(
            ok.copy(resultCode = EditContract.RESULT_CANCELED, errorCode = EditContract.ERROR_IO),
        )
        assertEquals(EditOutcome.Canceled, outcome)
    }

    @Test
    fun `a structured error parses through`() {
        val outcome = EditResultParser.parse(
            ok.copy(
                resultCode = EditContract.RESULT_ERROR,
                errorCode = EditContract.ERROR_UNSUPPORTED,
                errorMessage = "cannot decode image/x-jxl",
            ),
        )
        assertEquals(
            EditOutcome.Error(EditContract.ERROR_UNSUPPORTED, "cannot decode image/x-jxl"),
            outcome,
        )
    }

    @Test
    fun `error without a message parses with an empty message`() {
        val outcome = EditResultParser.parse(
            ok.copy(resultCode = EditContract.RESULT_ERROR, errorCode = EditContract.ERROR_IO),
        )
        assertEquals(EditOutcome.Error(EditContract.ERROR_IO, ""), outcome)
    }

    @Test
    fun `error without a code classifies as internal`() {
        val outcome = EditResultParser.parse(ok.copy(resultCode = EditContract.RESULT_ERROR))
        assertEquals(EditContract.ERROR_INTERNAL, (outcome as EditOutcome.Error).errorCode)
    }

    @Test
    fun `an unknown error code is preserved in the message but classified internal`() {
        val outcome = EditResultParser.parse(
            ok.copy(
                resultCode = EditContract.RESULT_ERROR,
                errorCode = "ERROR_TELEPORT",
                errorMessage = "something else went wrong",
            ),
        )
        val error = outcome as EditOutcome.Error
        assertEquals(EditContract.ERROR_INTERNAL, error.errorCode)
        assertEquals("unknown error code 'ERROR_TELEPORT': something else went wrong", error.errorMessage)
    }

    @Test
    fun `an unknown result code degrades to a structured internal error`() {
        val outcome = EditResultParser.parse(ok.copy(resultCode = 42))
        assertEquals(
            EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "unknown activity result code 42",
            ),
            outcome,
        )
    }

    @Test
    fun `the contract result-code constants mirror the platform values`() {
        // RESULT_OK / RESULT_CANCELED must stay the platform values; RESULT_ERROR
        // is deliberately RESULT_FIRST_USER + 1 (never mistakable for either).
        assertEquals(-1, EditContract.RESULT_OK)
        assertEquals(0, EditContract.RESULT_CANCELED)
        assertEquals(2, EditContract.RESULT_ERROR)
    }
}
