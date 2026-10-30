package io.github.dukeoftoad.gallery.data

/**
 * Pure, Android-free logic for the file operations slice (issue #30).
 *
 * Three families of rules live here, deliberately free of every `android.*`
 * import (unlike [MediaStoreSource] and [MediaFileOps]) so they run in plain
 * JVM unit tests --- the binding CI gate per ADR-0016, no Robolectric:
 *
 *  1. **Copy naming** --- the " (copy)" suffix and its collision numbering
 *     ([copyDisplayName]). " (copy)" when free, " (copy 2)", " (copy 3)" …
 *     when earlier slots are taken, extension always preserved.
 *  2. **Rename validation/sanitization** ([checkRenameInput],
 *     [renameDisplayName]): empty names and path separators are rejected
 *     before any `ContentResolver.update` happens; a typed extension is
 *     stripped and the original extension restored, so a `.jpg` can never
 *     silently become extension-less or get a second extension appended.
 *  3. **Batch aggregation** ([summarizeBatch] over [FileOpOutcome]) --- the
 *     per-item outcome kinds the Android layer ([MediaFileOps],
 *     `FileOpsViewModel`) produces and the UI summarizes for snackbars.
 *
 * The Android layer feeds these functions ContentResolver data (e.g. a
 * `DISPLAY_NAME`-equality query behind [copyDisplayName]'s `taken`
 * predicate) and consumes their outputs; every rule is pinned by
 * `FileOpNamingTest`.
 */

/**
 * Which user-facing operation a run/summary belongs to. Drives progress copy
 * ("Moving 3 of 12…") and completion snackbars ("Moved 4 items").
 */
enum class FileOpKind { RENAME, COPY, MOVE, DELETE }

/**
 * Minimal album reference for file operations (issue #30): the bucket key
 * plus its display name. Move targets are addressed by bucket id (the
 * `RELATIVE_PATH` of a row in that bucket is resolved through MediaStore at
 * operation time), and undo payloads remember the source album this way.
 *
 * The picker UI deals in the richer [Album] (cover, count, recency);
 * `MediaFileOps` maps entries to this type so its API --- and the undo
 * payloads built from its results --- never need the full view model shape.
 */
data class AlbumRef(
    val bucketId: Long,
    val bucketName: String,
)

/**
 * Delete semantics for the file-ops layer (issue #30).
 *
 * v1 ships exactly one mode, and [MediaFileOps.delete] *refuses* the other:
 * silently treating `TO_TRASH` as `PERMANENT` would remove the safety the
 * recycle bin is supposed to add, so the seam stays loud until #31 lands.
 */
enum class DeleteMode {
    /**
     * Remove the MediaStore row for good via `ContentResolver.delete`.
     * The only mode honored in v1; the UI says so explicitly
     * (`file_ops_delete_confirm_body`).
     */
    PERMANENT,

    /**
     * RESERVED for issue #31 (recycle bin). Will move the row into the
     * platform trash (`IS_TRASH = 1` update on API 29+) instead of deleting,
     * where the OS supports it, with the fallback policy for older devices
     * decided by that slice. Not implemented here --- passing it to
     * [MediaFileOps.delete] in v1 fails fast.
     */
    TO_TRASH,
}

/** Why a file operation failed; carried by [FileOpOutcome.Failure]. */
enum class FileOpFailureReason {
    /** The MediaStore row vanished (deleted/moved elsewhere mid-batch). */
    NOT_FOUND,

    /** Rename input rejected by [checkRenameInput] or by the provider. */
    INVALID_NAME,

    /** The MediaStore insert for a copy could not be created. */
    INSERT_FAILED,

    /** Byte streaming (`openInputStream`/`openOutputStream`) failed. */
    IO,

    /** Needs user consent (API 29+ recovery request); see `NeedsConsent`. */
    SECURITY,

    /** The provider rejected the op for another reason (see `detail`). */
    PROVIDER,
}

/**
 * Per-item outcome of one file operation. Pure on purpose: the Android
 * wrapper (`FileOpResult` in `MediaFileOps.kt`) pairs one of these with
 * Android anchors (the created copy's `Uri`, the source `AlbumRef`), and
 * [summarizeBatch] + `FileOpNamingTest` operate on this type alone.
 */
sealed interface FileOpOutcome {
    /** The operation fully succeeded. */
    data object Success : FileOpOutcome

    /**
     * The op was blocked because the app did not contribute the item and
     * Android 10+ requires explicit user consent (a
     * `RecoverableSecurityException` was caught). Nothing was changed; the
     * caller builds one `MediaStore.createWriteRequest` /
     * `createDeleteRequest` for the affected items and retries the op after
     * `RESULT_OK`.
     */
    data object NeedsConsent : FileOpOutcome

    /**
     * Move only: the copy into the target album succeeded but the original
     * could not be removed, so both entries exist. The copy is never lost
     * ([deleteReason] explains why the delete phase failed;
     * [FileOpFailureReason.SECURITY] routes into the delete-consent retry,
     * everything else surfaces as an honest "partially moved" message).
     */
    data class Partial(val deleteReason: FileOpFailureReason) : FileOpOutcome

    /** The op failed; nothing (further) was changed. */
    data class Failure(val reason: FileOpFailureReason) : FileOpOutcome
}

/**
 * Aggregate of a batch run, computed purely from the per-item outcomes.
 * `cancelled` is passed in by the caller (a cancelled run only collected a
 * prefix of outcomes, which is exactly what `total` then describes).
 */
data class BatchSummary(
    /** Items the run actually attempted (== full batch size when not cancelled). */
    val total: Int,
    val succeeded: Int,
    /** Moves whose copy landed but whose original delete failed. */
    val partials: Int,
    /** Items awaiting a consent round-trip (nothing changed for them). */
    val needingConsent: Int,
    val failed: Int,
    val cancelled: Boolean,
) {
    /** Items whose content ended up in the intended place (moves count partials here). */
    val changed: Int get() = succeeded + partials
}

/** Reduces per-item outcomes to a [BatchSummary]; see the test for the pinned rules. */
fun summarizeBatch(outcomes: Collection<FileOpOutcome>, cancelled: Boolean = false): BatchSummary =
    BatchSummary(
        total = outcomes.size,
        succeeded = outcomes.count { it is FileOpOutcome.Success },
        partials = outcomes.count { it is FileOpOutcome.Partial },
        needingConsent = outcomes.count { it is FileOpOutcome.NeedsConsent },
        failed = outcomes.count { it is FileOpOutcome.Failure },
        cancelled = cancelled,
    )

// ---------------------------------------------------------------------
// Rename validation & display-name composition
// ---------------------------------------------------------------------

/**
 * Verdict on raw rename text-field input (issue #30). All variants are pure
 * values so the dialog (and the JVM tests) branch exhaustively.
 */
sealed interface RenameCheck {
    /** Usable input, already trimmed, typed extension stripped, trailing dots removed. */
    data class Valid(val baseName: String) : RenameCheck

    /** Blank input --- there is no name to write. */
    data object Empty : RenameCheck

    /** Contains `/` or `\` --- MediaStore names are never paths. */
    data object Separator : RenameCheck

    /** `.` or `..` --- reserved by every filesystem; the provider rejects them. */
    data object DotName : RenameCheck
}

/**
 * Validates and sanitizes rename input.
 *
 * Rules (pinned by `FileOpNamingTest`):
 *  - surrounding whitespace is trimmed;
 *  - empty / whitespace-only input is [RenameCheck.Empty];
 *  - `.` and `..` are [RenameCheck.DotName];
 *  - any `/` or `\` is [RenameCheck.Separator] --- rejected, not mangled,
 *    so the user sees exactly what they typed was unusable;
 *  - everything after the LAST dot is treated as a retyped extension and
 *    dropped --- the original extension is restored later by
 *    [renameDisplayName], so typing "Beach.png" for a `Beach.jpg`-bound
 *    input renames to "Beach.jpg". A LEADING dot does not start an
 *    extension: ".hidden" (and even a typed ".jpg") is taken as the
 *    literal base name, keeping dotfiles possible;
 *  - trailing dots are stripped ("Sunset." -> "Sunset") because FAT/FUSE
 *    filenames may not end with a dot; if that empties the name, the input
 *    is [RenameCheck.Empty].
 */
fun checkRenameInput(raw: String): RenameCheck {
    val cleaned = raw.trim()
    if (cleaned.isEmpty()) return RenameCheck.Empty
    if (cleaned == "." || cleaned == "..") return RenameCheck.DotName
    if (cleaned.contains('/') || cleaned.contains('\\')) return RenameCheck.Separator
    val lastDot = cleaned.lastIndexOf('.')
    val base = (if (lastDot > 0) cleaned.substring(0, lastDot) else cleaned).trimEnd('.')
    if (base.isEmpty()) return RenameCheck.Empty
    return RenameCheck.Valid(base)
}

/** The extension of [displayName], lowercase-source as stored; `""` when there is none. */
fun extensionOf(displayName: String): String {
    val lastDot = displayName.lastIndexOf('.')
    return if (lastDot > 0) displayName.substring(lastDot + 1) else ""
}

/** [displayName] without its extension (a leading dot is part of the name). */
fun baseNameOf(displayName: String): String {
    val lastDot = displayName.lastIndexOf('.')
    return if (lastDot > 0) displayName.substring(0, lastDot) else displayName
}

/** Re-attaches [extension] to [base]; a bare [base] stays bare when there is no extension. */
fun composeName(base: String, extension: String): String =
    if (extension.isEmpty()) base else "$base.$extension"

/**
 * The final `DISPLAY_NAME` value a rename writes: the validated base plus the
 * ORIGINAL item's extension, so an extension can neither be lost nor
 * duplicated. Renames are type-preserving by design in v1 (changing format
 * identity belongs to the editor round trip, ADR-0007).
 */
fun renameDisplayName(oldDisplayName: String, baseName: String): String =
    composeName(baseName, extensionOf(oldDisplayName))

// ---------------------------------------------------------------------
// Copy naming (" (copy)" with collision numbering)
// ---------------------------------------------------------------------

/** How many " (copy n)" slots [copyDisplayName] probes before giving up. */
const val COPY_NAME_MAX_TRIES = 50

/**
 * Builds the display name for a duplicate of [sourceDisplayName]:
 * "name (copy).ext" when free, "name (copy 2).ext", "name (copy 3).ext", …
 * while earlier slots are [taken] (the production predicate is a
 * `DISPLAY_NAME`-equality MediaStore query scoped to the target folder).
 *
 *  - The extension is always preserved exactly as the source's.
 *  - A source with no extension ("Trip") yields "Trip (copy)".
 *  - A leading-dot source (".jpg") keeps the dot as part of the name:
 *    ".jpg (copy)"; only a truly NAMELESS row falls back to the stem
 *    "copy" ("copy (copy).ext"), so the result is never empty.
 *  - If every slot through [COPY_NAME_MAX_TRIES] is taken, the LAST
 *    candidate is returned best-effort: the insert then either still
 *    succeeds (provider-side dedupe) or surfaces as an honest
 *    `INSERT_FAILED` result. Colliding silently was never on the table.
 */
fun copyDisplayName(
    sourceDisplayName: String,
    taken: (String) -> Boolean,
    maxTries: Int = COPY_NAME_MAX_TRIES,
): String {
    val extension = extensionOf(sourceDisplayName)
    val stem = baseNameOf(sourceDisplayName).ifEmpty { "copy" } + " (copy)"
    var candidate = composeName(stem, extension)
    for (n in 2..maxTries.coerceAtLeast(2)) {
        if (!taken(candidate)) return candidate
        candidate = composeName("$stem $n", extension)
    }
    return candidate
}
