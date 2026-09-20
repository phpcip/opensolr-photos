package com.opensolr.photos.search

import com.opensolr.photos.data.distinctWords

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.WordsItem
import org.json.JSONObject

/**
 * The owner's edits of their photos: tags, the names of the people in them, and their own
 * wording of what a photo shows.
 *
 * Editing never waits for the index. A save is written to the phone - to the owner's edits, to
 * the phone's own copy of the document, and to the queue of things that still have to reach the
 * index - and it is finished there. The sync that follows carries it up, fifty photos per call,
 * with no picture attached: only the words changed, so only the words travel.
 */
class EditRepository(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache.of(context)

    /**
     * One photo's editor: [tags], [persons] and [meaning] are exactly what the photo is to carry
     * from now on, because that form shows the owner the whole list.
     */
    fun saveLocal(id: String, tags: List<String>, meaning: String?, persons: List<String>?) {
        val clean = tags.distinctWords()
        val names = persons?.distinctWords()
        val wording = meaning?.trim()?.take(com.opensolr.photos.media.PhotoReader.MEANING_MAX_CHARS)?.takeIf { it.isNotEmpty() }
        cache.putEdits(id, PhotoCache.Edits(clean, wording, names))
        cache.doc(id)?.let { doc ->
            val finalNames = names ?: doc.persons
            val finalMeaning = wording ?: doc.meaning
            cache.putDoc(
                doc.copy(
                    tags = clean,
                    persons = finalNames,
                    meaning = finalMeaning,
                    json = withWords(doc.json, clean, finalNames, finalMeaning),
                )
            )
        }
        cache.queueAction(id, PhotoCache.ACTION_WORDS)
    }

    /**
     * The stored document with the owner's words put into it, so the phone's copy says the same
     * thing whichever part of it is read. The grid draws a photo from the document, so leaving it
     * as it was meant a tag disappearing again the moment the screen was rebuilt, until the sync
     * had been round (Cip, 2026-09-18).
     */
    private fun withWords(json: String?, tags: List<String>, persons: List<String>, meaning: String?): String? {
        if (json == null) return null
        return try {
            JSONObject(json).apply {
                put("custom_tags", org.json.JSONArray(tags))
                put("persons_ss", org.json.JSONArray(persons))
                put("persons_t", persons.joinToString(", "))
                if (meaning != null) put("meaning", meaning)
            }.toString()
        } catch (e: Exception) {
            json
        }
    }

    /**
     * Tagging many photos at once. [tags] and [persons] either go on top of what each photo has
     * ([tagsReplace] / [personsReplace] false) or become the whole of it; a null list is a field
     * the owner did not touch.
     *
     * What each photo ends up with is worked out here, on the phone, against its own copy of the
     * document - so the index is never asked what a photo carries, and what is queued is the
     * finished answer rather than an instruction to be interpreted later.
     */
    fun queueForAll(ids: Collection<String>, tags: List<String>?, tagsReplace: Boolean, persons: List<String>?, personsReplace: Boolean) {
        if (tags == null && persons == null) return
        val addTags = tags?.distinctWords()
        val addNames = persons?.distinctWords()
        cache.inTransaction {
            ids.forEach { id ->
                val doc = cache.doc(id)
                val edits = cache.getEdits(id)
                val hadTags = doc?.tags ?: edits?.tags ?: emptyList()
                val hadNames = doc?.persons ?: edits?.persons ?: emptyList()
                val finalTags = when {
                    addTags == null -> hadTags
                    tagsReplace -> addTags
                    else -> (hadTags + addTags).distinctWords()
                }
                val finalNames = when {
                    addNames == null -> hadNames
                    personsReplace -> addNames
                    else -> (hadNames + addNames).distinctWords()
                }
                cache.putEdits(id, PhotoCache.Edits(finalTags, edits?.meaning, finalNames))
                doc?.let {
                    cache.putDoc(it.copy(tags = finalTags, persons = finalNames, json = withWords(it.json, finalTags, finalNames, it.meaning)))
                }
            }
            // All of them at once, at the end: queuing each photo on its own asked the database what
            // was already waiting for it, once per photo, which on a whole library is thousands of
            // questions for an answer that is read in one (Cip, 2026-09-18).
            cache.queueActions(ids, PhotoCache.ACTION_WORDS)
        }
    }

    /** How many photos are waiting for anything to reach the index. */
    fun pendingCount(): Int = cache.actionCount()

    /**
     * Carries every waiting change of words up, fifty photos per call and no pictures: the server
     * reads each document, puts the words in, makes the vector again and writes it back. The
     * phone's own copy is brought in step from what comes back, and each photo leaves the queue
     * only once the index has taken it.
     *
     * A photo the index does not have yet is put back in the queue as one to be indexed whole,
     * picture and all. Returns how many photos were written.
     */
    suspend fun sendWords(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        val waiting = cache.actions(PhotoCache.ACTION_WORDS)
        if (waiting.isEmpty()) return 0
        val session = prefs.session ?: return 0
        val connection = prefs.connection ?: return 0
        var written = 0
        waiting.chunked(WORDS_BATCH).forEach { batch ->
            // Places chosen on the map that the index does not have yet, read once per batch.
            val places = cache.setPlaces(batch).filterValues { !it.synced }
            val items = batch.map { id ->
                val edits = cache.getEdits(id)
                // The photo's row is read once and everything taken from it: the fingerprint used to
                // be a second read of the same row, plus taking the whole document apart for one
                // field of it (Cip, 2026-09-18).
                val doc = cache.doc(id)
                WordsItem(
                    id = id,
                    tags = edits?.tags ?: doc?.tags,
                    persons = edits?.persons ?: doc?.persons,
                    meaning = edits?.meaning,
                    fileHash = doc?.fileHash,
                    location = places[id]?.let { it.lat to it.lon },
                )
            }
            val results = api.photosWords(session, connection.indexName, items)
            val done = ArrayList<String>(batch.size)
            // A place counts as delivered only when the written document carries it: a server that
            // does not know the field yet answers yes all the same, and the place would never be
            // sent again.
            val placed = ArrayList<String>()
            batch.forEachIndexed { index, id ->
                val answer = results.getOrNull(index)
                if (answer == null) {
                    // Not in the index (yet): it goes up the ordinary way, with its picture.
                    cache.queueAction(id, PhotoCache.ACTION_INDEX)
                } else {
                    places[id]?.let { place ->
                        val got = com.opensolr.photos.search.parseLatLon(answer.optString("location"))
                        if (got != null && kotlin.math.abs(got.first - place.lat) < 1e-5 && kotlin.math.abs(got.second - place.lon) < 1e-5) placed += id
                    }
                    if (id !in places || id in placed) storeDoc(id, answer)
                    done += id
                    written++
                }
            }
            cache.clearActions(done)
            cache.markPlacesSynced(placed)
            // Not delivered: the words are in, the place waits for the next sync.
            done.filter { it in places && it !in placed }.let { if (it.isNotEmpty()) cache.queueActions(it, PhotoCache.ACTION_WORDS) }
            onProgress(written, waiting.size)
        }
        return written
    }

    /**
     * True when the phone has no copy of the index at all: a fresh install, or the first run of a
     * version that keeps one.
     */
    fun cloneMissing(): Boolean = !prefs.cloneComplete || cache.docCount() == 0

    /**
     * Reads the index once into the phone's own copy of it: after a reinstall, at the first start
     * of a version that keeps such a copy, or after a reset. From then on the copy is kept in step
     * by every write and the index is never walked again (Cip, 2026-09-18).
     */
    suspend fun readIndexIntoCache() {
        val connection = prefs.connection ?: return
        prefs.cloneComplete = false
        com.opensolr.photos.net.SolrClient(connection)
            .forEachDoc(CLONE_FIELDS) { d ->
                val id = d.optString("id")
                if (id.isNotEmpty()) storeDoc(id, d, indexedAt = com.opensolr.photos.ui.Actions.solrDateMillis(d.optString("indexed_at")) ?: 0L)
            }
        // Only a read that reached the end counts: anything less is read again.
        prefs.cloneComplete = true
    }

    /**
     * Keeps the phone's copy of a document in step with what the server says it wrote.
     */
    fun storeDoc(id: String, answer: JSONObject, indexedAt: Long = System.currentTimeMillis()) {
        fun words(field: String): List<String> =
            answer.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
        val persons = words("persons_ss").ifEmpty {
            answer.optString("persons_t").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        // The two values the answer may not carry, worked out first; the phone's own copy is read
        // once, and only when one of them is really missing. It used to be read again for each of
        // them, once per photo of a whole library being brought in step (Cip, 2026-09-18).
        val size = if (answer.has("size_bytes")) answer.optLong("size_bytes") else null
        val modified = com.opensolr.photos.ui.Actions.solrDateMillis(answer.optString("modified_at"))?.div(1000)
        val had = if (size != null && modified != null) null else cache.doc(id)
        cache.putDoc(
            PhotoCache.Doc(
                id = id,
                sizeBytes = size ?: had?.sizeBytes ?: 0L,
                indexedAt = indexedAt,
                takenAt = answer.optString("taken_at").ifBlank { null },
                tags = words("custom_tags"),
                persons = persons,
                meaning = answer.optString("meaning").ifBlank { null },
                ocr = answer.optString("ocr_t").ifBlank { null },
                city = answer.optString("city").ifBlank { null },
                country = answer.optString("country").ifBlank { null },
                region = answer.optString("region").ifBlank { null },
                // Written with the row, from the document already open here, so grouping by
                // folder or by camera is a query on columns (Cip, 2026-09-20).
                folder = SearchFilters.folderPath(answer.optString("folder")),
                camera = SearchFilters.cameraName(answer.optString("camera_make"), answer.optString("camera_model")),
                json = answer.toString(),
                modified = modified ?: had?.modified ?: 0L,
                embedModel = answer.optString("embed_model").ifBlank { null },
                fileHash = answer.optString("file_hash").ifBlank { null },
            )
        )
    }

    private companion object {
        /**
         * Photos per call when only the words changed: the most one embedding call takes, and the
         * most photos_words accepts.
         */
        const val WORDS_BATCH = 50

        /**
         * Every field of a document except the vector and the duplicate keys: the phone's copy has
         * to hold as much as the index does, or browsing could not draw a photo, its details or
         * its place without asking (Cip, 2026-09-18).
         */
        const val CLONE_FIELDS = "id,media_id,path,file_name,folder,mime,size_bytes,file_hash," +
            "taken_at,indexed_at,modified_at,year,month,width,height,orientation," +
            "camera_make,camera_model,lens,iso,exposure,f_number,focal_length,flash," +
            "has_location,location,altitude,city,region,province,community,country,country_code," +
            "labels,meaning,ocr_t,persons_t,persons_ss,custom_tags,clip_model,embed_model"
    }
}
