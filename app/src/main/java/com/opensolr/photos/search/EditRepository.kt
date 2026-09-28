package com.opensolr.photos.search

import com.opensolr.photos.data.distinctWords

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.WordsItem
import org.json.JSONObject

class EditRepository(private val context: Context) {

    // how often the bulk edit tells the screen where it is
    private val PROGRESS_EVERY = 25

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache.of(context)

    fun saveLocal(id: String, tags: List<String>, meaning: String?, persons: List<String>?, resetWording: Boolean = false) {
        val clean = tags.distinctWords()
        val names = persons?.distinctWords()
        val wording = meaning?.trim()?.take(com.opensolr.photos.media.PhotoReader.MEANING_MAX_CHARS)?.takeIf { it.isNotEmpty() }
        // no new wording: the owner's earlier one stays. A reset stores "" - the mark that the photo
        // takes no wording from the file either, until the owner writes one again
        val kept = wording ?: if (resetWording) "" else cache.getEdits(id)?.meaning
        cache.putEdits(id, PhotoCache.Edits(clean, kept, names))
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
        // after a reset the photo is read again, which carries the tags and the names too: a words call
        // on top of it would only write the old wording back over what the read produced
        if (resetWording) cache.clearActions(listOf(id)) else cache.queueAction(id, PhotoCache.ACTION_WORDS)
    }

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

    fun queueForAll(ids: Collection<String>, tags: List<String>?, tagsReplace: Boolean, persons: List<String>?, personsReplace: Boolean, auto: Boolean = false, onProgress: (Int) -> Unit = {}) {
        if (tags == null && persons == null) return
        val addTags = tags?.distinctWords()
        val addNames = persons?.distinctWords()
        cache.inTransaction {
            // people the owner puts on are theirs from now on, even if the matcher had put them there first
            if (!auto && addNames != null) cache.ownAutoWords(ids, if (personsReplace) null else addNames)
            var done = 0
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
                done++
                if (done % PROGRESS_EVERY == 0) onProgress(done)
            }
            onProgress(ids.size)

            cache.queueActions(ids, PhotoCache.ACTION_WORDS)
        }
    }

    /** [person] taken off every photo and face that carries it; the photos go to the index as usual. How many photos. */
    fun removePersonEverywhere(person: String, onProgress: (Int) -> Unit = {}): List<String> {
        val ids = cache.idsWithPerson(person)
        if (ids.isEmpty()) return ids
        cache.inTransaction {
            ids.forEachIndexed { i, id ->
                val doc = cache.doc(id)
                val edits = cache.getEdits(id)
                val had = edits?.persons ?: doc?.persons ?: emptyList()
                val names = had.filter { !it.equals(person, ignoreCase = true) }
                val tags = edits?.tags ?: doc?.tags ?: emptyList()
                cache.putEdits(id, PhotoCache.Edits(tags, edits?.meaning, names))
                doc?.let { cache.putDoc(it.copy(persons = names, json = withWords(it.json, tags, names, it.meaning))) }
                if ((i + 1) % PROGRESS_EVERY == 0) onProgress(i + 1)
            }
            cache.unnameFaces(person)
            cache.queueActions(ids, PhotoCache.ACTION_WORDS)
        }
        onProgress(ids.size)
        return ids
    }

    fun pendingCount(): Int = cache.actionCount()


    suspend fun sendWords(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        val waiting = cache.actions(PhotoCache.ACTION_WORDS)
        if (waiting.isEmpty()) return 0
        val session = prefs.session ?: return 0
        val connection = prefs.connection ?: return 0
        var written = 0
        waiting.chunked(WORDS_BATCH).forEach { batch ->

            val places = cache.setPlaces(batch).filterValues { !it.synced }
            val items = batch.map { id ->
                val edits = cache.getEdits(id)

                val doc = cache.doc(id)
                // only what the owner changed travels; a photo queued for its place or its faces alone sends no
                // words, so the server keeps its vector instead of making it again
                WordsItem(
                    id = id,
                    tags = edits?.tags,
                    persons = edits?.persons,
                    meaning = edits?.meaning,
                    fileHash = doc?.fileHash,
                    location = places[id]?.let { it.lat to it.lon },
                    // the faces travel with the words: a name put on one changes both
                    faces = cache.facesForIndex(id, doc?.sizeBytes ?: 0L),
                    // where the file is on this phone, so another phone's document opens here
                    mediaId = doc?.json?.let { runCatching { JSONObject(it).optLong("media_id", 0L) }.getOrNull() }?.takeIf { it > 0 },
                    path = doc?.json?.let { runCatching { JSONObject(it).optString("path") }.getOrNull() }?.takeIf { it.isNotBlank() },
                )
            }
            val results = api.photosWords(session, connection.indexName, items)
            val done = ArrayList<String>(batch.size)

            val placed = ArrayList<String>()
            // a document the index no longer holds is written back from the clone, never read from the file again;
            // only a photo the clone has nothing for goes up as a new one
            val rewrite = ArrayList<String>()
            batch.forEachIndexed { index, id ->
                val answer = results.getOrNull(index)
                if (answer == null || !answer.optBoolean("status")) {
                    val json = cache.doc(id)?.json
                    if (answer?.optString("msg") == "NOT_INDEXED" && json != null) rewrite += json
                    else if (answer == null || answer.optString("msg") == "NOT_INDEXED") cache.queueAction(id, PhotoCache.ACTION_INDEX)
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
            if (rewrite.isNotEmpty()) {
                val docs = rewrite.map { json -> runCatching { JSONObject(json).apply { ANSWER_KEYS.forEach { remove(it) } }.toString() }.getOrDefault(json) }
                api.photosRestore(session, connection.indexName, docs)
                // the words stay queued: the place and the faces go on the rewritten document at the next pass
            }

            done.filter { it in places && it !in placed }.let { if (it.isNotEmpty()) cache.queueActions(it, PhotoCache.ACTION_WORDS) }
            onProgress(written, waiting.size)
        }
        return written
    }

    fun cloneMissing(): Boolean = !prefs.cloneComplete || cache.docCount() == 0

    suspend fun readIndexIntoCache() {
        val connection = prefs.connection ?: return
        prefs.cloneComplete = false
        com.opensolr.photos.net.SolrClient(connection)
            .forEachDoc(CLONE_FIELDS, pageSize = CLONE_PAGE) { d ->
                val id = d.optString("id")
                if (id.isNotEmpty()) storeDoc(id, d, indexedAt = com.opensolr.photos.ui.Actions.solrDateMillis(d.optString("indexed_at")) ?: 0L)
            }
        // photos kept in the index from folders no longer indexed stay parked, never read as deleted
        cache.parkOutside(SearchFilters.folderRoots(prefs.folders))

        prefs.cloneComplete = true
    }

    fun storeDoc(id: String, answer: JSONObject, indexedAt: Long = System.currentTimeMillis()) {
        // the faces the index keeps for this photo: taken as they are when this phone has none for this file
        com.opensolr.photos.data.FaceStore.decode(answer.optString("faces_json").ifBlank { null })?.let { stored ->
            if (!cache.faceScanned(id, stored.sizeBytes)) cache.importFaces(id, answer.optLong("media_id", -1L), stored)
        }
        fun words(field: String): List<String> =
            answer.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
        val persons = words("persons_ss").ifEmpty {
            answer.optString("persons_t").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

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

                folder = SearchFilters.folderPath(answer.optString("folder")),
                camera = SearchFilters.cameraName(answer.optString("camera_make"), answer.optString("camera_model")),
                cameraModel = answer.optString("camera_model").trim().ifBlank { null },
                labels = words("labels").map { it.trim() }.distinct(),
                json = answer.toString(),
                modified = modified ?: had?.modified ?: 0L,
                embedModel = answer.optString("embed_model").ifBlank { null },
                fileHash = answer.optString("file_hash").ifBlank { null },
            )
        )
    }

    private companion object {

        const val WORDS_BATCH = 50
        const val CLONE_PAGE = 10000
        val ANSWER_KEYS = listOf("status", "msg", "error", "results", "score", "_version_")

        const val CLONE_FIELDS = "id,media_id,path,file_name,folder,mime,size_bytes,file_hash," +
            "taken_at,indexed_at,modified_at,year,month,width,height,orientation," +
            "camera_make,camera_model,lens,iso,exposure,f_number,focal_length,flash," +
            "has_location,location,altitude,city,region,province,community,country,country_code," +
            "labels,meaning,ocr_t,persons_t,persons_ss,custom_tags,clip_model,embed_model,faces_json,pixel_hash"
    }
}
