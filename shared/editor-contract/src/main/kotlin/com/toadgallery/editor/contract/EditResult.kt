package com.toadgallery.editor.contract

/**
 * The gallery-side parse of an editor reply (spec section 3): one function, all three terminal
 * states, no exception paths. Unknown codes and malformed OK replies degrade to structured
 * [EditOutcome.Error] values rather than crashes, so the gallery can always narrate honestly.
 */
sealed interface EditOutcome {

    /** The editor saved a copy at [outputUri]; the gallery refreshes via MediaStore observation. */
    data class SavedCopy(
        val outputUri: String,
        /** The policy the editor reported applying; absent on older editors. */
        val metadataPolicyApplied: MetadataPolicy?,
    ) : EditOutcome

    /** The user backed out; nothing was modified anywhere (the gallery stays silent). */
    data object Canceled : EditOutcome

    /** Structured failure; [errorCode] is a contract code, [errorMessage] free-form. */
    data class Error(val errorCode: String, val errorMessage: String) : EditOutcome
}

data class RawResult(
    val resultCode: Int,
    val outputUri: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val metadataPolicyApplied: String?,
)

/**
 * Honesty-UI triggers (contract section 5) over the applied-policy echo: the gallery narrates
 * WHAT was stripped, never a bare "saved". A `null` applied policy (an older editor) makes
 * no claim at all --- both triggers are false and the narration stays generic.
 */
val EditOutcome.SavedCopy.stripsAllMetadata: Boolean
    get() = metadataPolicyApplied == MetadataPolicy.STRIP_ALL

val EditOutcome.SavedCopy.removesLocation: Boolean
    get() = stripsAllMetadata || metadataPolicyApplied == MetadataPolicy.STRIP_LOCATION

object EditResultParser {

    fun parse(result: RawResult): EditOutcome {
        return when (result.resultCode) {
            EditContract.RESULT_OK -> parseOk(result)
            EditContract.RESULT_CANCELED -> EditOutcome.Canceled
            EditContract.RESULT_ERROR -> parseError(result)
            else -> EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "unknown activity result code ${result.resultCode}",
            )
        }
    }

    private fun parseOk(result: RawResult): EditOutcome {
        val output = result.outputUri?.trim().orEmpty()
        if (output.isEmpty()) {
            return EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "RESULT_OK without $${EditContract.EXTRA_OUTPUT_URI}",
            )
        }
        if (!output.startsWith("${EditContract.CONTENT_SCHEME}://")) {
            return EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "RESULT_OK output URI must use the ${EditContract.CONTENT_SCHEME}:// scheme, got '$output'",
            )
        }
        val applied = MetadataPolicy.fromWire(result.metadataPolicyApplied)
        return EditOutcome.SavedCopy(outputUri = output, metadataPolicyApplied = applied)
    }

    private fun parseError(result: RawResult): EditOutcome {
        val code = result.errorCode?.takeIf { it.isNotBlank() } ?: EditContract.ERROR_INTERNAL
        // An unknown code is still a failure --- keep the code as the message prefix but
        // classify as INTERNAL, so narration never claims a guarantee the code does not make.
        if (code !in EditContract.ERROR_CODES) {
            return EditOutcome.Error(
                EditContract.ERROR_INTERNAL,
                "unknown error code '$code': ${result.errorMessage ?: "no message"}",
            )
        }
        return EditOutcome.Error(
            errorCode = code,
            errorMessage = result.errorMessage ?: "",
        )
    }
}
