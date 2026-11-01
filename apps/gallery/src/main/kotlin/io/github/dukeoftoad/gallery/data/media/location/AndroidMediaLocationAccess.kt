package io.github.dukeoftoad.gallery.data.media.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * The production [MediaLocationAccess]: thin OS plumbing over the pure,
 * JVM-tested [MediaLocationStateMachine] (not unit-tested by design,
 * ADR-0016 --- same exemption as `MediaReadPermissionGate`: it is a
 * straight `ContextCompat` check with no branching logic of its own).
 *
 * API contract: `ACCESS_MEDIA_LOCATION` is a runtime permission from API
 * 29 only. [required] returns false below 29 (the permission does not
 * exist there and the manifest declaration is ignored by the OS); the
 * constructor and [refresh] fold the OS truth into the state machine, so
 * a below-29 device lands in [MediaLocationState.NOT_REQUIRED] and stays
 * there (terminal --- see the machine's KDoc).
 *
 * No ContentResolver-driven re-check is needed after a grant: the
 * Compose-side `RequestPermission` result is handed to
 * [onPermissionResult] directly, and [refresh] (one cheap
 * `checkSelfPermission`) covers the "user flipped it in system settings"
 * path. GPS display elsewhere (Details view) observes [state] through
 * [MediaLocationDisplayRules.Default].
 *
 * Privacy (ADR-0014): reads no media data, holds no location, touches no
 * network. The grant governs DISPLAY only.
 */
class AndroidMediaLocationAccess(
    context: Context,
    private val requester: PermissionRequester? = null,
) : MediaLocationAccess {

    private val appContext = context.applicationContext
    private val machine = MediaLocationStateMachine()

    override val state: StateFlow<MediaLocationState> = machine.state

    init {
        machine.onSystemCheck(required(), isGranted())
    }

    override fun required(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun isGranted(): Boolean =
        !required() || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    override fun refresh() {
        machine.onSystemCheck(required(), isGranted())
    }

    override fun onPermissionResult(granted: Boolean) {
        machine.onPermissionResult(granted)
    }

    override suspend fun request(): Boolean {
        if (!required()) return true
        val r = requester ?: return false
        val granted = r.request(Manifest.permission.ACCESS_MEDIA_LOCATION)
        machine.onPermissionResult(granted)
        return granted
    }
}
