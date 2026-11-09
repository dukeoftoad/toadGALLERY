package com.toadgallery.gallery.data.catalogue.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Room row of a delete/revoke tombstone (audit item "MediaChangeFeed":
 * "delete/revoke tombstones").
 *
 * A tombstone is written INSTEAD of deleting history: it records "this
 * local media key was absent from an authoritative scan at
 * [tombstonedAtMillis]" so that (a) a merely unmounted removable volume
 * does not lose its indexing state when it comes back, and (b) the AI
 * engines can observe the death and clean their own stores --- a hard
 * delete would leave their rows orphaned with no way to tell "gone" from
 * "never seen". Tombstones are cleared by an upsert of the same key
 * (resurrection) and aged out by `CatalogueDao.deleteTombstonesOlderThan`.
 *
 * Untested Android-adjacent code per ADR-0016; semantics are pinned by the
 * tests of [com.toadgallery.gallery.data.catalogue
 * .InMemoryMediaCatalogue].
 */
@Entity(tableName = "catalogue_tombstones")
data class CatalogueTombstoneEntity(
    /** Primary key: the dead item's local media key. */
    @PrimaryKey val localMediaKey: String,
    /** Epoch millis of the authoritative scan that missed the item. */
    val tombstonedAtMillis: Long,
)
