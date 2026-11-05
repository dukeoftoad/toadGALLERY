package io.github.dukeoftoad.gallery.edit.contract

/**
 * The validated launch request, shared by BOTH sides: the gallery builds it
 * from the extras it is about to send, the editor builds it from the extras
 * it received --- one validator, one truth, no drift.
 */
data class EditRequest(
    /** The source media URI, `content://` only (ADR-0006; no absolute paths). */
    val sourceUri: String,
    /** The negotiated contract version (gallery sends; editor validates its own range). */
    val contractVersion: Int,
    /** v1 carries exactly one export mode: [EditContract.EXPORT_MODE_SAVE_COPY]. */
    val exportMode: String,
    /** How (or whether) EXIF metadata rides onto the saved copy. */
    val metadataPolicy: MetadataPolicy,
    /** Optional UI pre-selection hint; unknown values are rejected, not ignored. */
    val operationHint: OperationHint?,
    /**
     * The r42 additive media kind: image (the default, exactly the v1
     * behavior) or video. Never null after validation; the builder omits
     * the extra entirely for image launches so the v1 wire stays identical.
     */
    val mediaKind: MediaKind = MediaKind.IMAGE,
)

/** The raw, unvalidated extras as read off the wire (either direction). */
data class RawExtras(
    val contractVersion: Int?,
    val sourceUri: String?,
    val operationHint: String?,
    val exportMode: String?,
    val metadataPolicy: String?,
    val mediaKind: String? = null,
)

/** Why a raw extras bundle failed contract validation; [errorCode] is what the editor replies. */
data class RequestInvalid(val reason: String, val errorCode: String) {
    init {
        require(errorCode in EditContract.ERROR_CODES) {
            "RequestInvalid.errorCode must be a contract error code, was '$errorCode'"
        }
    }
}

/** Validation outcome: a usable [EditRequest] or a [RequestInvalid] refusal. */
sealed interface RequestValidation {
    data class Valid(val request: EditRequest) : RequestValidation
    data class Invalid(val invalid: RequestInvalid) : RequestValidation
}

/**
 * The single validation gate for contract launch requests.
 *
 * Editor-side reading: `validate(raw, acceptedVersionMin, acceptedVersionMax)`.
 * Gallery-side self-check before launch: the same function with the
 * editor's negotiated version as the (min == max) accepted range.
 */
object EditRequestValidator {

    fun validate(raw: RawExtras, acceptedVersionMin: Int, acceptedVersionMax: Int): RequestValidation {
        // Contract version: present, inside the accepted additive window.
        val version = raw.contractVersion
        if (version == null) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "missing $${EditContract.EXTRA_CONTRACT_VERSION} extra",
                    EditContract.ERROR_VERSION,
                ),
            )
        }
        if (version < acceptedVersionMin || version > acceptedVersionMax) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "contract version $version outside the accepted [$acceptedVersionMin..$acceptedVersionMax] window",
                    EditContract.ERROR_VERSION,
                ),
            )
        }

        // Source URI: present, content://, non-empty. Absolute filesystem
        // paths MUST NOT appear in the contract (spec section 2).
        val source = raw.sourceUri?.trim().orEmpty()
        if (source.isEmpty()) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "missing $${EditContract.EXTRA_SOURCE_URI} extra (and no ClipData item)",
                    EditContract.ERROR_VERSION,
                ),
            )
        }
        if (!source.startsWith("${EditContract.CONTENT_SCHEME}://")) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "source URI must use the ${EditContract.CONTENT_SCHEME}:// scheme, got '$source'",
                    EditContract.ERROR_VERSION,
                ),
            )
        }

        // Export mode: v1 offers exactly one mode, and it is required.
        if (raw.exportMode == null) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "missing $${EditContract.EXTRA_EXPORT_MODE} extra",
                    EditContract.ERROR_VERSION,
                ),
            )
        }
        if (raw.exportMode != EditContract.EXPORT_MODE_SAVE_COPY) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "unsupported export mode '${raw.exportMode}' --- v1 offers only " +
                        "'${EditContract.EXPORT_MODE_SAVE_COPY}'",
                    EditContract.ERROR_UNSUPPORTED,
                ),
            )
        }

        // Metadata policy: required, total parse (spec: editor never silently
        // strips anything; an unknown policy is a contract violation, not a
        // reason to fall back to preserve).
        val policy = MetadataPolicy.fromWire(raw.metadataPolicy)
        if (policy == null) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "missing or unknown $${EditContract.EXTRA_METADATA_POLICY} extra: '${raw.metadataPolicy}'",
                    EditContract.ERROR_VERSION,
                ),
            )
        }

        // Operation hint: optional, but an unknown value is a contract
        // violation (the hint only pre-selects UI, so callers that mistype
        // it should hear about it in development, not ship silent defaults).
        val hint = OperationHint.fromWire(raw.operationHint)
        if (hint == null && raw.operationHint != null) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "unknown $${EditContract.EXTRA_OPERATION_HINT} extra: '${raw.operationHint}'",
                    EditContract.ERROR_VERSION,
                ),
            )
        }

        // Media kind (r42 additive): absent = image (the v1 default); an
        // unknown value is a contract violation, never a silent fallback.
        val mediaKind = MediaKind.fromWire(raw.mediaKind)
        if (mediaKind == null && raw.mediaKind != null) {
            return RequestValidation.Invalid(
                RequestInvalid(
                    "unknown $${EditContract.EXTRA_MEDIA_KIND} extra: '${raw.mediaKind}'",
                    EditContract.ERROR_VERSION,
                ),
            )
        }

        return RequestValidation.Valid(
            EditRequest(
                sourceUri = source,
                contractVersion = version,
                exportMode = raw.exportMode,
                metadataPolicy = policy,
                operationHint = hint,
                mediaKind = mediaKind ?: MediaKind.IMAGE,
            ),
        )
    }
}

/**
 * Builds the launch extras the gallery attaches to the contract intent.
 * Pure: the Android layer copies the entries into the Bundle verbatim
 * (`EXTRA_CONTRACT_VERSION` is an Int, every other value a String). The
 * r42 media-kind extra rides ONLY on video launches --- an image launch's
 * extras are byte-identical to v1 (the additive-versioning rule).
 */
object EditLaunchExtrasBuilder {

    /** Ordered (extra key, typed value) pairs for a validated request. */
    fun build(request: EditRequest): List<Pair<String, Any>> = listOf(
        EditContract.EXTRA_CONTRACT_VERSION to request.contractVersion,
        EditContract.EXTRA_SOURCE_URI to request.sourceUri,
        EditContract.EXTRA_EXPORT_MODE to request.exportMode,
        EditContract.EXTRA_METADATA_POLICY to request.metadataPolicy.wireName,
    ).let { base ->
        val withHint = if (request.operationHint != null) {
            base + (EditContract.EXTRA_OPERATION_HINT to request.operationHint.wireName)
        } else {
            base
        }
        if (request.mediaKind == MediaKind.VIDEO) {
            withHint + (EditContract.EXTRA_MEDIA_KIND to request.mediaKind.wireName)
        } else {
            withHint
        }
    }
}
