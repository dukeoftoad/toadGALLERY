package com.toadgallery.gallery.data.media.access

import com.toadgallery.gallery.data.media.identity.EligiblePublicMedia

/**
 * The opener half of the Phase 4 read boundary (contract change 5): the
 * ONLY production way to obtain a [MediaReadHandle] for an original
 * media item.
 *
 * THE PARAMETER TYPE IS THE VAULT GATE. [open] accepts an
 * [EligiblePublicMedia] --- the type whose sole constructor path runs
 * through `MediaEligibility.asEligible`, which returns `null` for every
 * state except `PUBLIC_INDEXABLE`. There is deliberately NO overload for
 * a bare [com.toadgallery.gallery.data.media.identity.MediaIdentity],
 * no overload for a raw `Uri` string, and no way to hand vault media
 * across: a vault blob, a trashed row or a permission-revoked item is
 * unrepresentable at this call site, so the future Ente-derived ML
 * pipeline cannot read vault bytes even by mistake (ADR-0010 consent
 * boundary + the audit's vault-leakage gate). This is gate 2 of the
 * audit's three (enqueue / OPEN / pre-persist): [open] re-verifies media
 * permission through the injected [MediaPermissionGate] right before it
 * mints a handle, because permission can be revoked between enqueue and
 * read and a stale grant must not leak bytes.
 *
 * `null` means "cannot open": gate refused, blank/unresolvable Uri, or
 * the item vanished. Callers classify and move on --- see
 * [MediaReadHandle]'s KDoc for the honest-degradation contract.
 */
interface MediaAccess {

    /**
     * Opens the ORIGINAL bytes of an eligible public item, or `null` when
     * the permission gate refuses the read-time re-check or the identity
     * carries no usable access Uri. The returned handle is lazy --- no
     * stream is held open between [open] and the first
     * [MediaReadHandle.readBytes], so a queued-but-never-read item costs
     * nothing.
     */
    suspend fun open(media: EligiblePublicMedia): MediaReadHandle?
}

/**
 * Seam for the read-time permission re-check (gate 2 of 3): does this
 * process STILL hold the media read permission that covers [media]?
 *
 * Kept as an interface (ADR-0016) so the production check ---
 * `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` on API 33+, legacy
 * `READ_EXTERNAL_STORAGE` below --- stays a thin Android seam behind
 * which the access CONTRACT is JVM-testable with a fake, and so the
 * future indexing coordinator can be wired with the same fake in its
 * tests. The check is deliberately media-ITEM-shaped rather than
 * permission-constant-shaped: per-item partial photo/video grants
 * (the audit's "partial media permissions" acceptance item) cannot be
 * answered by one global flag.
 */
interface MediaPermissionGate {

    /**
     * True when media read permission still covers [media] at this
     * moment; `false` makes [MediaAccess.open] refuse the read. Must be
     * safe to call from any dispatcher and cheap enough for per-item
     * use during a batch.
     */
    suspend fun stillHasAccess(media: EligiblePublicMedia): Boolean
}
