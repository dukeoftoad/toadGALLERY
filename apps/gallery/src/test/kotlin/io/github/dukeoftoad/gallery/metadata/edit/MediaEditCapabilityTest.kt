package io.github.dukeoftoad.gallery.metadata.edit

import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure edit-capability gate (Phase 4 write round; no
 * android.*, no Robolectric --- ADR-0016). Pins the transition table of
 * [MediaEditGate.capabilityFor] exactly as its KDoc documents it ---
 * including the PRECEDENCE of refusals (eligibility > field > format >
 * platform > consent) --- and the [ExifWriteFormats] pinned write set.
 *
 * The verified platform inputs behind the table live in the gate's KDoc
 * (androidx.exifinterface 1.4.1 writes JPEG/PNG/WebP only;
 * `MediaStore.createWriteRequest` exists from API 30; the app holds no
 * write permission below Q).
 */
class MediaEditCapabilityTest {

    /** A well-formed eligible public JPEG identity (the happy-path input). */
    private fun eligible(): EligiblePublicMedia? =
        MediaEligibility.PUBLIC_INDEXABLE.asEligible(
            MediaIdentity(
                volumeName = "external_primary",
                mediaStoreId = 42L,
                canonicalUri = "content://media/external_primary/images/media/42",
            ),
        )

    private fun capability(
        eligible: EligiblePublicMedia? = eligible(),
        mimeType: String = "image/jpeg",
        field: MediaEditField = MediaEditField.DATE_TIME,
        apiLevel: Int = 34,
        locationState: MediaLocationState = MediaLocationState.GRANTED,
    ): MediaEditCapability = MediaEditGate.capabilityFor(
        eligible = eligible,
        mimeType = mimeType,
        field = field,
        apiLevel = apiLevel,
        locationState = locationState,
    )

    // ---- NOT_ELIGIBLE: the type-level gate outranks everything ----

    @Test
    fun `ineligible item is NOT_ELIGIBLE for every field regardless of everything else`() {
        // Vault / trashed / revoked arrive here as null (unrepresentable
        // through the read gate); even a writable JPEG on a new device with
        // every consent granted is refused --- and the refusal outranks the
        // field, the format AND the platform tier in the same call.
        for (field in MediaEditField.entries) {
            assertEquals(MediaEditCapability.NOT_ELIGIBLE, capability(eligible = null, field = field))
        }
    }

    @Test
    fun `ineligible outranks unsupported field format and platform`() {
        assertEquals(
            MediaEditCapability.NOT_ELIGIBLE,
            capability(
                eligible = null,
                mimeType = "image/heic",
                field = MediaEditField.COLOUR_LABEL,
                apiLevel = 28,
            ),
        )
    }

    // ---- UNSUPPORTED_FIELD: the pinned writer's field set ----

    @Test
    fun `keywords rating and colour label are UNSUPPORTED_FIELD on every format and platform`() {
        for (field in listOf(
            MediaEditField.KEYWORDS,
            MediaEditField.RATING,
            MediaEditField.COLOUR_LABEL,
        )) {
            assertEquals(MediaEditCapability.UNSUPPORTED_FIELD, capability(field = field))
            assertEquals(
                MediaEditCapability.UNSUPPORTED_FIELD,
                capability(field = field, mimeType = "image/heic", apiLevel = 28),
            )
        }
    }

    @Test
    fun `writer pinned fields are exactly datetime gps copyright and comment`() {
        assertEquals(
            setOf(
                MediaEditField.DATE_TIME,
                MediaEditField.GPS,
                MediaEditField.COPYRIGHT,
                MediaEditField.COMMENT,
            ),
            MediaEditGate.WRITER_PINNED_FIELDS,
        )
    }

    // ---- UNSUPPORTED_FORMAT: the pinned write set ----

    @Test
    fun `heic is UNSUPPORTED_FORMAT even though the read matrix parses it`() {
        // androidx.exifinterface 1.4.1 READS heic on API 28+ but
        // saveAttributes writes JPEG/PNG/WebP only (verified): the write
        // gate says the honest format refusal, never a crash on save.
        assertEquals(
            MediaEditCapability.UNSUPPORTED_FORMAT,
            capability(mimeType = "image/heic"),
        )
    }

    @Test
    fun `png is UNSUPPORTED_FORMAT this round because the pinned reader cannot show the result`() {
        // The library CAN write PNG, but MetadataFormatMatrix declares the
        // PNG EXIF read ABSENT this round; the write set is the
        // intersection, so a PNG edit cannot be observed in this app.
        assertEquals(
            MediaEditCapability.UNSUPPORTED_FORMAT,
            capability(mimeType = "image/png"),
        )
    }

    @Test
    fun `format refusal outranks the platform tier`() {
        assertEquals(
            MediaEditCapability.UNSUPPORTED_FORMAT,
            capability(mimeType = "image/heic", apiLevel = 28),
        )
    }

    @Test
    fun `videos and unknown types are UNSUPPORTED_FORMAT`() {
        assertEquals(
            MediaEditCapability.UNSUPPORTED_FORMAT,
            capability(mimeType = "video/mp4"),
        )
        assertEquals(
            MediaEditCapability.UNSUPPORTED_FORMAT,
            capability(mimeType = "image/x-adobe-dng"),
        )
    }

    // ---- READ_ONLY: the below-Q platform tier ----

    @Test
    fun `below Q the item is READ_ONLY for writable formats`() {
        // The app declares no WRITE_EXTERNAL_STORAGE and no consent
        // protocol exists below API 29 --- the same tiering that keeps the
        // whole file-ops surface hidden below Q.
        assertEquals(MediaEditCapability.READ_ONLY, capability(apiLevel = 28))
    }

    @Test
    fun `platform refusal outranks the GPS consent dimension`() {
        // NOT_REQUIRED is the below-29 consent state; the platform tier is
        // consulted first, so GPS on a pre-Q device is READ_ONLY, not
        // consent-passed (the precedence keeps the answer deterministic).
        assertEquals(
            MediaEditCapability.READ_ONLY,
            capability(
                field = MediaEditField.GPS,
                apiLevel = 28,
                locationState = MediaLocationState.NOT_REQUIRED,
            ),
        )
    }

    // ---- CONSENT_REQUIRED: the GPS consent dimension ----

    @Test
    fun `gps without location consent is CONSENT_REQUIRED on 29 plus`() {
        for (state in listOf(MediaLocationState.UNKNOWN, MediaLocationState.DENIED)) {
            assertEquals(
                MediaEditCapability.CONSENT_REQUIRED,
                capability(field = MediaEditField.GPS, locationState = state),
            )
        }
    }

    @Test
    fun `gps with a grant is EDITABLE on 29 plus`() {
        assertEquals(
            MediaEditCapability.EDITABLE,
            capability(field = MediaEditField.GPS, locationState = MediaLocationState.GRANTED),
        )
    }

    @Test
    fun `non-gps fields ignore the location consent state`() {
        // Only the GPS field carries the ACCESS_MEDIA_LOCATION dimension
        // (the platform redacts EXIF location on ungranted reads of 29+);
        // copyright editing is not gated on it.
        assertEquals(
            MediaEditCapability.EDITABLE,
            capability(field = MediaEditField.COPYRIGHT, locationState = MediaLocationState.DENIED),
        )
    }

    @Test
    fun `gps editable consent states are exactly granted and not required`() {
        assertEquals(
            setOf(MediaLocationState.GRANTED, MediaLocationState.NOT_REQUIRED),
            MediaEditGate.GPS_EDITTABLE_CONSENT,
        )
    }

    // ---- EDITABLE: the happy path ----

    @Test
    fun `jpeg on Q plus with consent is EDITABLE for every pinned field`() {
        for (field in MediaEditGate.WRITER_PINNED_FIELDS) {
            assertEquals(MediaEditCapability.EDITABLE, capability(field = field))
        }
    }

    @Test
    fun `webp is writable this round`() {
        assertEquals(MediaEditCapability.EDITABLE, capability(mimeType = "image/webp"))
    }

    // ---- ExifWriteFormats normalization ----

    @Test
    fun `isWritable normalizes case whitespace and parameter suffixes`() {
        assertTrue(ExifWriteFormats.isWritable("image/jpeg"))
        assertTrue(ExifWriteFormats.isWritable("IMAGE/JPEG"))
        assertTrue(ExifWriteFormats.isWritable("  image/jpeg; charset=binary  "))
        assertTrue(ExifWriteFormats.isWritable("image/webp"))
        assertFalse(ExifWriteFormats.isWritable("image/png"))
        assertFalse(ExifWriteFormats.isWritable("image/heic"))
        assertFalse(ExifWriteFormats.isWritable("video/mp4"))
        assertFalse(ExifWriteFormats.isWritable(""))
    }
}
