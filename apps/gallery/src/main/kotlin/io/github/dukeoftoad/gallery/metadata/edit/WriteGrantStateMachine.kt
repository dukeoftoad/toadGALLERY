package io.github.dukeoftoad.gallery.metadata.edit

/**
 * The Android 10+ write-grant escalation flow for a metadata edit, as a
 * pure state machine (the write-path twin of
 * [io.github.dukeoftoad.gallery.data.media.location.MediaLocationStateMachine]).
 *
 * THE PLATFORM CONTRACT (verified):
 *  - Opening a media item this app did not contribute for WRITING throws
 *    a recoverable `SecurityException` on API 29+
 *    (https://developer.android.com/training/data-storage/shared/media,
 *    "Manage groups of media files").
 *  - `MediaStore.createWriteRequest` --- the system "allow editing these
 *    items?" dialog --- exists from API level 30 (R) ONLY
 *    (https://developer.android.com/reference/android/provider/MediaStore#createWriteRequest(android.content.ContentResolver,java.util.Collection%3Candroid.net.Uri%3E));
 *    the same fact is pinned in this repo by
 *    `MediaFileOps.createWriteConsentRequest`'s `@RequiresApi(R)`. On API
 *    29 exactly, a blocked write is UNRECOVERABLE --- the app reports the
 *    honest read-only outcome instead of looping a dialog that cannot
 *    exist (the same discipline `FileOpsViewModel` applies to favorites).
 *  - Below API 29 there is no consent protocol at all and the app holds
 *    only read permissions; the capability gate refuses those devices up
 *    front, so the machine never sees them in production.
 *
 * Transitions (exhaustive; JVM-table-tested in
 * `WriteGrantStateMachineTest`):
 *  - [onWriteBlocked]: a write attempt was refused by the provider.
 *    From [IDLE]: escalation possible (R+) -> [NEEDS_GRANT]; not possible
 *    (API 29) -> [READ_ONLY]. From [GRANTED] (this was the single retry
 *    after a grant) -> [READ_ONLY]: the retry round is single-shot, so a
 *    second refusal surfaces as the honest read-only outcome instead of
 *    looping dialogs. From any other state: unchanged (attempts only
 *    launch from IDLE/GRANTED; a stray signal must not invent state).
 *  - [onRequestLaunched]: the IntentSender was handed to the activity
 *    result launcher. [NEEDS_GRANT] -> [REQUESTED]; ignored elsewhere.
 *  - [onGrantResult]: the system dialog answered. [REQUESTED] ->
 *    [GRANTED] / [DENIED]; a stale result in any other state is ignored.
 *  - [onWriteSucceeded]: the write (or the post-grant retry) landed;
 *    every state -> [IDLE].
 *  - [reset]: a NEW editor session starts fresh from [IDLE] --- a denial
 *    in one edit never poisons the next (the system dialog can be shown
 *    again per request; it is not a one-shot runtime permission).
 *
 * [DENIED] is kept distinct from [READ_ONLY] (the honest reason differs:
 * "the user declined" vs "the platform/grant could not deliver access"),
 * and both are terminal for the session that produced them.
 */
enum class WriteGrantState {
    IDLE,
    NEEDS_GRANT,
    REQUESTED,
    GRANTED,
    DENIED,
    READ_ONLY,
}

/**
 * The pure transition core of the write-grant escalation: no android.*
 * imports, plain mutable state (the UI-facing half lives in
 * `DetailsViewModel`, which drives it with `Build.VERSION.SDK_INT` and
 * the activity-result launcher). Mirrors
 * `MediaLocationStateMachine`'s shape minus the StateFlow --- this
 * machine is scoped to one editor session, nothing else observes it.
 */
class WriteGrantStateMachine(initial: WriteGrantState = WriteGrantState.IDLE) {

    /** The current state. */
    var current: WriteGrantState = initial
        private set

    /**
     * A write attempt was refused by the provider. [escalationPossible]
     * is the platform fact (API 30+: `MediaStore.createWriteRequest`
     * exists). Returns the new state.
     */
    fun onWriteBlocked(escalationPossible: Boolean): WriteGrantState {
        current = when (current) {
            WriteGrantState.IDLE ->
                if (escalationPossible) WriteGrantState.NEEDS_GRANT else WriteGrantState.READ_ONLY
            WriteGrantState.GRANTED -> WriteGrantState.READ_ONLY
            WriteGrantState.NEEDS_GRANT,
            WriteGrantState.REQUESTED,
            WriteGrantState.DENIED,
            WriteGrantState.READ_ONLY,
            -> current
        }
        return current
    }

    /** The consent IntentSender was launched; a stray launch cannot invent state. */
    fun onRequestLaunched(): WriteGrantState {
        if (current == WriteGrantState.NEEDS_GRANT) {
            current = WriteGrantState.REQUESTED
        }
        return current
    }

    /** The system dialog answered; stale results are ignored, never replayed. */
    fun onGrantResult(granted: Boolean): WriteGrantState {
        if (current == WriteGrantState.REQUESTED) {
            current = if (granted) WriteGrantState.GRANTED else WriteGrantState.DENIED
        }
        return current
    }

    /** The write landed; the machine returns to rest for the next session. */
    fun onWriteSucceeded(): WriteGrantState {
        current = WriteGrantState.IDLE
        return current
    }

    /** A new editor session starts fresh --- refusals never poison it. */
    fun reset(): WriteGrantState {
        current = WriteGrantState.IDLE
        return current
    }
}
