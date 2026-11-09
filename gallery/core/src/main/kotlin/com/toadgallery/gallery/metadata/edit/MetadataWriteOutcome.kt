package com.toadgallery.gallery.metadata.edit

/**
 * The raw outcome of ONE write attempt through the Android writer shell,
 * BEFORE any platform/grant classification --- the shell only reports
 * WHAT HAPPENED (exception classes collapsed to kinds); every decision
 * about what it MEANS lives in the pure [MetadataWriteFlow].
 */
enum class RawWriteOutcome {
    /** The tags were written and saved into the original file. */
    SUCCESS,

    /**
     * The provider refused the write on ownership grounds
     * (`SecurityException`, which also covers
     * `RecoverableSecurityException` --- its superclass). Whether that is
     * escalatable depends on the API level + grant state, hence raw.
     */
    SECURITY,

    /**
     * Open or save failed (`IOException`, vanished item included) --- and
     * the terminal bucket for UNEXPECTED provider/runtime failures: the
     * writer shell's backstop catch classifies them here (WARN-logged
     * class name) rather than throwing past its classify-never-throw
     * contract, because its caller's launch has no catch of its own (see
     * `MediaMetadataWriter`).
     */
    IO,

    /** The format is outside the pinned write set (defensive re-check). */
    FORMAT_REFUSED,
}

/** The honest reason a write was refused, for the UI's state text. */
enum class WriteRefusalReason {
    /**
     * Below API 30 there is no `createWriteRequest` --- a blocked write
     * (another app's item) has no sanctioned recovery, so the item is
     * read-only for this app on this platform version.
     */
    READ_ONLY_PLATFORM,

    /**
     * The user granted write access (or the retry ran after a grant) and
     * the provider STILL refused --- the single-shot retry is exhausted;
     * the item stays read-only rather than looping dialogs.
     */
    READ_ONLY_STILL_BLOCKED,

    /** The format's EXIF cannot be written by the pinned writer. */
    UNSUPPORTED_FORMAT,

    /** The file could not be opened, parsed or saved (vanished, IO error). */
    IO,
}

/**
 * The classified, UI-facing outcome of one write attempt: the pure
 * mapping from ([RawWriteOutcome], API level, grant state) to the honest
 * next action.
 */
sealed interface MetadataWriteResult {

    /** The write landed in the file. */
    data object Success : MetadataWriteResult

    /**
     * Another app's item: launch the system write-grant dialog
     * (`MediaStore.createWriteRequest`, API 30+), then retry ONCE.
     */
    data object NeedsGrant : MetadataWriteResult

    /** The write was refused; [reason] is the honest why. */
    data class Refused(val reason: WriteRefusalReason) : MetadataWriteResult
}

/**
 * Pure failure-to-state mapping (ADR-0016: the Android shell catches and
 * classifies exceptions into [RawWriteOutcome] kinds; every policy
 * decision below is JVM-pinned in `MetadataWriteOutcomeTest`).
 *
 * The mapping, with its verified platform inputs:
 *  - SUCCESS -> [MetadataWriteResult.Success].
 *  - FORMAT_REFUSED -> Refused(UNSUPPORTED_FORMAT) regardless of
 *    platform/grant (the format check is platform-independent).
 *  - IO -> Refused(IO) likewise.
 *  - SECURITY:
 *     - API 30+ at [WriteGrantState.IDLE] -> [MetadataWriteResult.NeedsGrant]
 *       (the system dialog exists and this attempt has not used it yet).
 *     - API 30+ at [WriteGrantState.GRANTED] -> Refused(READ_ONLY_STILL_BLOCKED)
 *       (the post-grant retry was refused; single-shot, no dialog loop).
 *     - API 30+ in any other grant state (a stray attempt outside the
 *       IDLE/GRANTED discipline) -> Refused(READ_ONLY_STILL_BLOCKED) ---
 *       the conservative side, never a second dialog.
 *     - Below API 30 -> Refused(READ_ONLY_PLATFORM): the escalation
 *       dialog does not exist there, so the refusal is final
 *       (verified API-30 floor: see [WriteGrantStateMachine]'s KDoc).
 */
object MetadataWriteFlow {

    /** `MediaStore.createWriteRequest` exists from API 30 (R) --- verified. */
    const val API_30: Int = 30

    fun classify(
        raw: RawWriteOutcome,
        apiLevel: Int,
        grantState: WriteGrantState,
    ): MetadataWriteResult = when (raw) {
        RawWriteOutcome.SUCCESS -> MetadataWriteResult.Success
        RawWriteOutcome.FORMAT_REFUSED ->
            MetadataWriteResult.Refused(WriteRefusalReason.UNSUPPORTED_FORMAT)
        RawWriteOutcome.IO -> MetadataWriteResult.Refused(WriteRefusalReason.IO)
        RawWriteOutcome.SECURITY -> when {
            apiLevel >= API_30 && grantState == WriteGrantState.IDLE ->
                MetadataWriteResult.NeedsGrant
            apiLevel >= API_30 ->
                MetadataWriteResult.Refused(WriteRefusalReason.READ_ONLY_STILL_BLOCKED)
            else ->
                MetadataWriteResult.Refused(WriteRefusalReason.READ_ONLY_PLATFORM)
        }
    }
}
