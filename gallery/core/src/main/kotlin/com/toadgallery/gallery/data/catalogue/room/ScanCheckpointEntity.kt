package com.toadgallery.gallery.data.catalogue.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Room row of a per-volume scan checkpoint (audit item "Room catalogue":
 * persist scan checkpoints; one row per volume name).
 *
 * On API 30+ the [generationToken] lets the next scan skip all work when
 * the volume has not moved; on API 26-29 it is `null` --- the
 * ContentObserver signal plus [scannedAtMillis] are all the staleness bound
 * the app has. Untested Android-adjacent code per ADR-0016.
 */
@Entity(tableName = "scan_checkpoints")
data class ScanCheckpointEntity(
    /** Primary key: the MediaStore volume name. */
    @PrimaryKey val volumeName: String,
    /** Volume generation token at scan time; `null` on API < 30. */
    val generationToken: String?,
    /** Epoch millis of the scan's completion. */
    val scannedAtMillis: Long,
)
