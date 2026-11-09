package com.toadgallery.gallery.ml.things

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One Things dynamic collection (r39, the ente-port exit round): a NAME
 * the user gave it plus the QUERY the CLIP text encoder embeds to derive
 * its membership from the live semantic index ("Dogs" over "dogs";
 * "Seaside" over "a beach at sunset with waves"). NOTHING ELSE persists
 * --- membership is a DERIVED view over the same `ml-index.sqlite` CLIP
 * rows Magic Search queries (the DerivedCollections doctrine: a
 * collection is a named view, never a stored set), recomputed on every
 * read so it never goes stale against the queue's index refreshes.
 *
 * [collectionId] is OUR OWN stable identity (a slug derived from the
 * name at creation, collision-suffixed), deliberately unlike the
 * People feature's engine-assigned cluster ids: it never changes on
 * rename (the id is the identity; the name is the label), so the
 * detail route's nav argument and the store's entries stay valid for
 * the collection's whole life.
 */
data class ThingsCollection(
    val collectionId: String,
    val name: String,
    val query: String,
    val createdAtMillis: Long,
)

/**
 * The r39 Things user state: the collections the user created, in
 * creation order (the section renders that order; a later round may
 * sort --- the order is the store's, never the UI's to mutate).
 *
 * All of it lives in ONE small JSON file (`things-state.json`, schema
 * version 1) inside the SAME app-private face-index directory the ML
 * store (`ml-index.sqlite`), the r24/r26 queues' bookkeeping and the
 * r25 people state share --- so the Settings reset that recursively
 * deletes the directory also erases the collections together with the
 * index they are views over (the same never-outlive-your-data rule
 * `people-state.json` pins; there is no orphan-collection shape).
 *
 * The mutation helpers ([withCollectionAdded], [withCollectionRenamed],
 * [withCollectionQueried], [withCollectionRemoved]) are PURE over
 * immutable copies and JVM-tested; [ThingsStateFile] is the production
 * persistence (atomic temp-then-move, forgiving parse --- the
 * `PeopleStateFile` discipline verbatim).
 */
data class ThingsState(
    val collections: List<ThingsCollection> = emptyList(),
) {
    companion object {
        /** The empty state: no collections. */
        val EMPTY = ThingsState()
    }
}

/**
 * One [ThingsStateStore.load] result: the state plus the honest note of
 * whether the file existed but could not be parsed
 * ([recoveredFromCorruptFile] --- the user's collections were RESET,
 * and the UI must say so instead of silently pretending nothing was
 * lost; the `PeopleStateLoad` contract verbatim).
 */
data class ThingsStateLoad(
    val state: ThingsState,
    val recoveredFromCorruptFile: Boolean,
)

/** The persistence seam [ThingsRepository] reads and writes things state through. */
interface ThingsStateStore {

    /** The persisted state (empty + [ThingsStateLoad.recoveredFromCorruptFile] when corrupt). */
    suspend fun load(): ThingsStateLoad

    /** Persists [state] as the WHOLE things state (an atomic full rewrite). */
    suspend fun save(state: ThingsState)
}

/**
 * Derives a collection-id slug from a raw name: lowercase, every
 * non-alphanumeric run folded to one '-', trimmed of '-'. Returns null
 * for a name with no alphanumeric characters at all --- not a valid
 * collection (the refused-create shape, never a fallback id).
 */
fun collectionIdSlug(rawName: String): String? {
    val slug = rawName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return slug.ifEmpty { null }
}

/**
 * The id the NEXT collection named [slug] would take against [this]
 * state: the bare slug when free, else the lowest `-N` suffix (N >= 2)
 * that is ("dogs", "dogs-2", "dogs-3", ...). Collision-suffixed against
 * the EXISTING ids only --- derived BEFORE the add so the caller can
 * know the id without re-deriving it afterwards.
 */
fun ThingsState.nextCollectionId(slug: String): String {
    val existing = collections.map { it.collectionId }.toSet()
    if (slug !in existing) return slug
    var n = 2
    while ("$slug-$n" in existing) n += 1
    return "$slug-$n"
}

/**
 * Returns [this] with one collection added for [rawName]/[rawQuery]
 * (both trimmed; a BLANK name is REFUSED as null --- the id-derivation
 * failure --- and a blank query falls back to the name itself, the
 * "Dogs over dogs" default). The id is the slug of the name,
 * collision-suffixed via [nextCollectionId]; the creation timestamp is
 * the caller's [nowMillis] (the store never reads a clock).
 */
fun ThingsState.withCollectionAdded(
    rawName: String,
    rawQuery: String,
    nowMillis: Long,
): ThingsState? {
    val name = rawName.trim()
    if (name.isEmpty()) return null
    val slug = collectionIdSlug(name) ?: return null
    val query = rawQuery.trim().ifEmpty { name }
    val id = nextCollectionId(slug)
    return copy(
        collections = collections + ThingsCollection(
            collectionId = id,
            name = name,
            query = query,
            createdAtMillis = nowMillis,
        ),
    )
}

/**
 * Returns [this] with [collectionId]'s NAME set to [rawName] (trimmed).
 * A BLANK name is a no-op (same instance --- the refused-rename shape;
 * the id stays the identity and a blank label is not a name). The id is
 * DELIBERATELY unchanged: the id is the identity, the name is the label
 * (the People feature's unstable-id lesson, applied by construction).
 */
fun ThingsState.withCollectionRenamed(collectionId: String, rawName: String): ThingsState {
    val name = rawName.trim()
    if (name.isEmpty()) return this
    val index = collections.indexOfFirst { it.collectionId == collectionId }
    if (index < 0) return this
    val current = collections[index]
    if (current.name == name) return this
    return copy(collections = collections.toMutableList().apply { set(index, current.copy(name = name)) })
}

/**
 * Returns [this] with [collectionId]'s QUERY set to [rawQuery]
 * (trimmed); a BLANK query resets to the collection's own NAME (the
 * "Dogs over dogs" default). A no-op (same instance) for an unknown id
 * or an unchanged query.
 */
fun ThingsState.withCollectionQueried(collectionId: String, rawQuery: String): ThingsState {
    val index = collections.indexOfFirst { it.collectionId == collectionId }
    if (index < 0) return this
    val current = collections[index]
    val query = rawQuery.trim().ifEmpty { current.name }
    if (current.query == query) return this
    return copy(collections = collections.toMutableList().apply { set(index, current.copy(query = query)) })
}

/**
 * Returns [this] with [collectionId]'s collection dropped. A no-op
 * (same instance) when no such collection exists.
 */
fun ThingsState.withCollectionRemoved(collectionId: String): ThingsState {
    val kept = collections.filterNot { it.collectionId == collectionId }
    if (kept.size == collections.size) return this
    return copy(collections = kept)
}

/** [this]'s collection with [collectionId], or null. */
fun ThingsState.collectionWith(collectionId: String): ThingsCollection? =
    collections.firstOrNull { it.collectionId == collectionId }

/**
 * The production [ThingsStateStore]: one JSON file
 * (`things-state.json`) in the face-index directory.
 *
 * Write path mirrors `PeopleStateFile` exactly: serialize to
 * `<file>.tmp`, then an atomic NIO move over the previous file (same
 * directory = same filesystem = atomic on POSIX; a plain-filesystem
 * fallback keeps exotic mount layouts honest). A crash mid-write can
 * therefore never destroy the previous contents.
 *
 * ## Schema version 1
 * `{"version":1,"collections":[{"id":"dogs","name":"Dogs",
 * "query":"dogs","createdAt":1730000000000},…]}`. The read is
 * FORGIVING but HONEST (the `PeopleStateFile` contract verbatim): a
 * missing file is the empty state; a file that EXISTS but cannot be
 * parsed (corrupt, partially written, hand-edited, or a future schema
 * version) degrades to empty --- flagged with
 * [ThingsStateLoad.recoveredFromCorruptFile], because this file holds
 * USER-AUTHORED data (names and queries they typed), and losing it
 * silently would be exactly the masked-failure shape the honest-states
 * doctrine forbids. An individual entry with a blank id or name, or a
 * non-object shape, is SKIPPED (the forgiving-parse rule: a bad entry
 * degrades to "not present", never a thrown parse error); the ids the
 * loader accepts are passed through verbatim (they were derived by
 * [collectionIdSlug] at write time, but the reader re-validates
 * nothing --- the write path is the only writer).
 *
 * The save runs on [Dispatchers.IO] through [withContext] (the file is
 * tiny, but it is still disk); the parse is pure `org.json` over plain
 * values, JVM-tested in `ThingsStateStoreTest`.
 */
class ThingsStateFile(
    private val file: File,
) : ThingsStateStore {

    override suspend fun load(): ThingsStateLoad = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext ThingsStateLoad(ThingsState.EMPTY, recoveredFromCorruptFile = false)
        }
        try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) {
                return@withContext ThingsStateLoad(ThingsState.EMPTY, recoveredFromCorruptFile = true)
            }
            val collections = ArrayList<ThingsCollection>()
            val array = root.optJSONArray(COLLECTIONS_KEY)
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val id = entry.optString(ID_KEY)
                    val name = entry.optString(NAME_KEY)
                    // The forgiving-parse rule: a blank id or name is not a
                    // collection; skip the entry, keep the rest.
                    if (id.isEmpty() || name.isBlank()) continue
                    val query = entry.optString(QUERY_KEY).ifEmpty { name }
                    collections.add(
                        ThingsCollection(
                            collectionId = id,
                            name = name,
                            query = query,
                            createdAtMillis = entry.optLong(CREATED_AT_KEY, 0L),
                        ),
                    )
                }
            }
            ThingsStateLoad(ThingsState(collections), recoveredFromCorruptFile = false)
        } catch (_: Exception) {
            // Corrupt / partially-written / hand-edited: the honest state is
            // "reset" plus the flag that says the user's collections were
            // lost --- never a thrown parse error into the Things pipeline.
            ThingsStateLoad(ThingsState.EMPTY, recoveredFromCorruptFile = true)
        }
    }

    override suspend fun save(state: ThingsState) {
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            state.collections.forEach { collection ->
                array.put(
                    JSONObject()
                        .put(ID_KEY, collection.collectionId)
                        .put(NAME_KEY, collection.name)
                        .put(QUERY_KEY, collection.query)
                        .put(CREATED_AT_KEY, collection.createdAtMillis),
                )
            }
            val root = JSONObject()
                .put(VERSION_KEY, SUPPORTED_VERSION)
                .put(COLLECTIONS_KEY, array)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Layouts without atomic rename support still get the durable
                // temp-write + replace ordering; only the atomicity window widens.
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    private companion object {
        const val VERSION_KEY = "version"
        const val COLLECTIONS_KEY = "collections"
        const val ID_KEY = "id"
        const val NAME_KEY = "name"
        const val QUERY_KEY = "query"
        const val CREATED_AT_KEY = "createdAt"

        const val SUPPORTED_VERSION = 1
    }
}

/** The things state file's name, inside the face-index directory (r39). */
const val THINGS_STATE_FILE_NAME = "things-state.json"
