package com.toadgallery.gallery.data.media.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.toadgallery.gallery.data.media.identity.EligiblePublicMedia

/**
 * The production [MediaPermissionGate]: does this process still hold the
 * media read permission at this moment.
 *
 * Scope, stated honestly: the platform's read grant for MediaStore rows is
 * process-level (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` on API 33+, legacy
 * `READ_EXTERNAL_STORAGE` below --- exactly the manifest's declared set).
 * The interface's item shape exists so the CONTRACT stays JVM-testable and
 * so per-item nuance has somewhere to live later; this implementation
 * answers the process-level grant and deliberately delegates the per-item
 * nuance to the read itself:
 *
 * - Per-item partial grants (API 34+ photo-picker style "selected photos
 *   access") narrow which ROWS the resolver returns, not which rows a
 *   granted process may open: a row visible to the walk is openable, so a
 *   global check cannot produce a false yes for a walk-surfaced item.
 * - A row that VANISHES or whose grant is revoked between the walk and the
 *   read fails inside [ContentResolverMediaAccess.open] (null handle /
 *   failed read) and is classified UNREADABLE downstream --- the same
 *   honest-degradation outcome a per-item check would produce, one step
 *   later and with no extra resolver round-trip per item.
 *
 * Cheap by design (one permission check, no resolver I/O): the scan
 * pipeline's index pass calls it per item during a batch, and it must stay
 * safe to call from any dispatcher.
 *
 * Privacy (ADR-0014): reads no data, holds no state, touches no network.
 */
class MediaReadPermissionGate(
    private val context: Context,
) : MediaPermissionGate {

    override suspend fun stillHasAccess(media: EligiblePublicMedia): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
