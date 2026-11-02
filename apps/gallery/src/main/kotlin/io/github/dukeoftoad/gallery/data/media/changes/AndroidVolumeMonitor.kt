package io.github.dukeoftoad.gallery.data.media.changes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * The volume hot-plug shell (r20 round; the r19-named follow-up): listens
 * for the platform's storage-mount broadcast and hands the signal to the
 * scan driver's [MediaScanDriver.onVolumesChanged]. THIN by ADR-0016 ---
 * an untested adapter of one platform broadcast, with zero policy: which
 * volumes to observe, idempotent registration and the coalesced re-scan
 * all live in the driver; this class only transports the platform's
 * signal to it, exactly like [AndroidMediaStoreChangeFeed] transports
 * ContentObserver signals.
 *
 * WHY A BROADCAST and not a polling timer or a StorageManager callback:
 * `Intent.ACTION_MEDIA_MOUNTED` is the platform's canonical "a storage
 * volume just became available" signal (a protected system intent ---
 * only the system can send it, so a spoofed mount is not a threat model
 * here; see
 * https://developer.android.com/reference/android/content/Intent#ACTION_MEDIA_MOUNTED).
 * It has existed since API 1 and fires for every external volume mount
 * (removable SD, USB OTG), which is precisely the event the pre-r20
 * driver missed: its observers were registered once at app start against
 * the volumes that existed THEN, so a card inserted afterwards had no
 * observer until the next process restart.
 *
 * The filter carries `scheme("file")` because the mount broadcasts carry
 * a `file://` data Uri --- an IntentFilter without the scheme matches
 * nothing for data-carrying broadcasts.
 *
 * Scope honesty, stated in the driver's own KDoc too: below API 29 the
 * row source reports the single classic external volume and there is no
 * removable-volume world to hot-plug, so the receiver simply never sees a
 * mount that changes anything (and costs nothing while idle). Unmounts
 * need no signal: the scan walks only the volumes the row source reports,
 * so an unmounted volume's rows keep their live records until the volume
 * returns --- the tombstone-on-absence trap the reconciler's volume
 * filter exists to avoid.
 *
 * Registration is a resource: [register] returns the unregister function.
 * The app wiring holds it for the process lifetime (the receiver is
 * process state, like the driver itself); nothing forces that --- a test
 * or a future owner could scope it tighter.
 */
class AndroidVolumeMonitor(
    private val context: Context,
    private val onVolumesChanged: () -> Unit,
) {

    private var unregister: (() -> Unit)? = null

    /**
     * Registers the mount receiver on the main dispatcher's handler plane
     * (broadcasts deliver there; the callback only flips the driver's
     * coalescing valve, which is cheap --- the scan itself belongs to the
     * driver's scope, never this thread). Idempotent: a second call while
     * registered is a no-op (the returned unregister from the FIRST call
     * still releases everything).
     */
    fun register(): () -> Unit {
        if (unregister != null) return unregister!!
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_MEDIA_MOUNTED) {
                    onVolumesChanged()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply {
            addDataScheme("file")
        }
        // targetSdk 34+ REQUIRES the exported flag on every
        // context-registered receiver (a missing flag is a lint error and,
        // on U+, a SecurityException at runtime). NOT_EXPORTED is correct
        // here: only the SYSTEM may send ACTION_MEDIA_MOUNTED (a protected
        // broadcast), and system broadcasts still reach not-exported
        // receivers --- no third-party app can spoof a mount into us.
        // Below T the flag does not exist; the plain overload is the only
        // spelling (the lint suppression is scoped to exactly that leg).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") // pre-T has no flag to specify
            context.registerReceiver(receiver, filter)
        }
        val release = {
            context.unregisterReceiver(receiver)
            unregister = null
        }
        unregister = release
        return release
    }
}
