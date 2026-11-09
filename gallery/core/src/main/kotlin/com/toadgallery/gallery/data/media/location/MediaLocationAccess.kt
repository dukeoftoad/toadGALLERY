package com.toadgallery.gallery.data.media.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ACCESS_MEDIA_LOCATION consent state, Phase 4 (roadmap still-open item;
 * audit decision #1 tracks this flow --- see `ExifReader`'s KDoc).
 *
 * Four states, exhaustively:
 *  - [NOT_REQUIRED]: the device is below API 29. The permission does not
 *    EXIST there and the platform has no EXIF-location redaction mechanism
 *    either --- `ACCESS_MEDIA_LOCATION` is never requested, and GPS data
 *    read from a file's own bytes was never redactable in the first place.
 *  - [UNKNOWN]: API 29+, not granted, never answered a request from this
 *    app. The honest "we have not asked you yet" state.
 *  - [GRANTED]: the user said yes (or the install-time state says so).
 *  - [DENIED]: the user said no. NOTE, stated honestly: Android's
 *    "don't ask again" is NOT distinguishable from a plain denial through
 *    the public result contract --- `shouldShowRequestPermissionRationale`
 *    needs an Activity, which this pure state machine deliberately does
 *    not touch. After a denial the Settings row still offers the request
 *    button (the OS simply no-ops it when permanently denied), so the UI
 *    never claims a distinction it cannot actually know.
 */
enum class MediaLocationState {
    NOT_REQUIRED,
    UNKNOWN,
    GRANTED,
    DENIED,
}

/**
 * The PURE transition core of the location-permission flow: no android.*
 * imports, exhaustive states, JVM-table-tested in
 * `MediaLocationAccessTest` (ADR-0016: every rule that can be tested
 * without the Android runtime IS tested there).
 *
 * Transitions:
 *  - [onSystemCheck] seeds/re-checks from the OS (`required()` x
 *    `granted()`). Below 29 the state collapses to [NOT_REQUIRED] from
 *    anywhere. On 29+: granted -> [GRANTED]; not granted keeps an earned
 *    [DENIED] (a re-check must not silently forget a refusal) and yields
 *    [UNKNOWN] otherwise.
 *  - [onPermissionResult] records the answer of ONE runtime request.
 *    Grant -> [GRANTED] from any non-[NOT_REQUIRED] state; denial ->
 *    [DENIED]. A [NOT_REQUIRED] state is terminal: there is nothing to
 *    request below 29 and the outcome of an impossible request is
 *    ignored rather than invented.
 */
class MediaLocationStateMachine(
    initial: MediaLocationState = MediaLocationState.UNKNOWN,
) {
    private val _state = MutableStateFlow(initial)

    /** Observable state; the UI collects this (Settings row, Details gate). */
    val state: StateFlow<MediaLocationState> = _state.asStateFlow()

    /** The current value without collecting (cheap, synchronous). */
    val current: MediaLocationState get() = _state.value

    /** Seed / re-check from the OS: `required()` x `granted()`. */
    fun onSystemCheck(required: Boolean, granted: Boolean) {
        _state.value = when {
            !required -> MediaLocationState.NOT_REQUIRED
            granted -> MediaLocationState.GRANTED
            else -> when (_state.value) {
                MediaLocationState.DENIED -> MediaLocationState.DENIED
                MediaLocationState.NOT_REQUIRED -> MediaLocationState.NOT_REQUIRED
                else -> MediaLocationState.UNKNOWN
            }
        }
    }

    /** The user answered one runtime request. */
    fun onPermissionResult(granted: Boolean) {
        when (_state.value) {
            MediaLocationState.NOT_REQUIRED -> return
            else -> _state.value =
                if (granted) MediaLocationState.GRANTED else MediaLocationState.DENIED
        }
    }
}

/**
 * The GPS display gate for the Details view (the seam the parallel
 * `ui/details/` work observes): given the consent [MediaLocationState],
 * may GPS coordinates be SHOWN?
 *
 * THE CONTRACT, and why it is display-side rather than read-side
 * (ADR-0014: location never leaves the device, and no network surface
 * exists that could carry it):
 *
 *  - Below API 29 the permission does not exist and the platform never
 *    redacted anything --- GPS from the file's own bytes is shown.
 *    `ExifReader`'s KDoc documents the 29+ half of the same split: on
 *    Android 10+ the platform redacts EXIF location on MediaStore reads
 *    unless `ACCESS_MEDIA_LOCATION` is granted, and the reader
 *    deliberately does not work around it. Gating DISPLAY on the user's
 *    informed choice is the honest contract: the consent decision governs
 *    exactly what the user chose to see --- the app never presents consent
 *    as a switch over data it is *secretly* keeping back (or leaking).
 *  - On API 29+ WITHOUT a grant, GPS stays hidden ([UNKNOWN] and
 *    [DENIED] both hide). This matches the platform behaviour the reader
 *    reports (ungranted reads come back absent) and is the conservative
 *    side of an honest unknown.
 *  - With a grant, GPS is shown.
 */
fun interface MediaLocationDisplayGate {
    fun shouldDisplayGps(state: MediaLocationState): Boolean
}

/** The one production rule, JVM-tested in `MediaLocationAccessTest`. */
object MediaLocationDisplayRules {

    /** Grant required on 29+ ([GRANTED]); always allowed below ([NOT_REQUIRED]). */
    val Default: MediaLocationDisplayGate = MediaLocationDisplayGate { state ->
        state == MediaLocationState.GRANTED || state == MediaLocationState.NOT_REQUIRED
    }
}

/**
 * The location-permission state holder (Phase 4, roadmap still-open item).
 *
 * WHY this exists: `ExifReader`'s KDoc records that on Android 10+ the
 * platform redacts EXIF location unless `ACCESS_MEDIA_LOCATION` is granted,
 * and audit decision #1 tracks the consent flow separately. GPS is already
 * modelled honestly in the metadata layer (`MetadataValue<GpsCoordinates>`
 * availability semantics); this interface owns only the PERMISSION half:
 * whether the user has been asked, what they answered, and whether GPS may
 * be displayed.
 *
 * Privacy posture (ADR-0014): the grant exists ONLY to decide whether GPS
 * coordinates are shown in the UI. Coordinates never leave the device and
 * no network surface exists that could carry them; the permission is a
 * display-gate and an OS read-consent, nothing more.
 */
interface MediaLocationAccess {

    /** Observable consent state (see [MediaLocationState]). */
    val state: StateFlow<MediaLocationState>

    /**
     * Whether this Android version has the permission at all (API 29+).
     * False below 29: the permission does not exist there and must never
     * be requested (the manifest declaration is inert on such devices;
     * the runtime gate lives HERE).
     */
    fun required(): Boolean

    /** Cheap synchronous OS check (no resolver I/O, safe on any thread). */
    fun isGranted(): Boolean

    /**
     * Cheap re-check after an external grant/revoke (e.g. returning from
     * system settings). One `checkSelfPermission`, no ContentResolver
     * round-trip.
     */
    fun refresh()

    /**
     * Record the answer of one Activity-result request (the Compose side
     * launches `ActivityResultContracts.RequestPermission` and hands the
     * outcome here).
     */
    fun onPermissionResult(granted: Boolean)

    /**
     * Run a runtime request through the installed [PermissionRequester]
     * and fold the outcome into [state]. Returns the outcome; `true`
     * without asking when `required()` is false (nothing to ask for).
     * With no requester installed (headless contexts) returns `false`
     * without touching state.
     */
    suspend fun request(): Boolean
}

/**
 * Ask the OS for a permission and report the grant (the Activity-side
 * seam: production wires `ActivityResultContracts.RequestPermission` from
 * a real Activity behind this one method).
 */
fun interface PermissionRequester {
    suspend fun request(permission: String): Boolean
}
