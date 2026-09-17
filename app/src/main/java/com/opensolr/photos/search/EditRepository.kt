package com.opensolr.photos.search

import com.opensolr.photos.data.distinctWords

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.index.IndexManager
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.SolrAuthException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.net.VectorNotAllowedException
import com.opensolr.photos.sync.SyncEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's edits of one photo: tags and their own wording of what it shows.
 *
 * An edit is kept on the phone (the cache's edits table, which every later write of the
 * photo applies, so the owner's words always win over CLIP's) and written to the index at
 * once: the current document is read back from the index, the edits go in, the vector is
 * computed again from the new text on plans with vector search, and the document is
 * replaced. One AI request per save on those plans, none otherwise.
 */
class EditRepository(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache(context)

    /**
     * Saves [tags], [meaning] (null = keep what Opensolr saw) and [persons] (null = leave the
     * names alone) for the photo [id] and updates its document in the index. Returns the
     * document as written.
     */
    suspend fun save(id: String, tags: List<String>, meaning: String?, persons: List<String>? = null): JSONObject {
        val session = prefs.session ?: throw ServiceException("Sign in to edit photos")
        var connection = prefs.connection ?: throw ServiceException("Your index is not set up yet.")
        val clean = tags.distinctWords()
        val wording = meaning?.trim()?.takeIf { it.isNotEmpty() }
        val names = persons?.distinctWords()
        cache.putEdits(id, PhotoCache.Edits(clean, wording, names ?: cache.getEdits(id)?.persons))

        var solr = SolrClient(connection)
        val current = try {
            fetch(solr, id)
        } catch (e: SolrAuthException) {
            connection = IndexManager(context, prefs, api).refreshConnection(session)
            solr = SolrClient(connection)
            fetch(solr, id)
        } ?: throw ServiceException("This photo is not in your index yet. It is added at the next sync, with your edits.")

        current.remove("_version_")
        current.remove("score")
        if (clean.isEmpty()) current.remove("custom_tags") else current.put("custom_tags", JSONArray(clean))
        if (names != null) {
            if (names.isEmpty()) {
                current.remove("persons_t")
                current.remove("persons_ss")
            } else {
                current.put("persons_t", names.joinToString(", "))
                current.put("persons_ss", JSONArray(names))
            }
        } else if (!current.has("persons_ss") && current.optString("persons_t").isNotBlank()) {
            // A photo indexed before persons_ss existed: the names, each whole, for suggestions.
            current.put("persons_ss", JSONArray(current.optString("persons_t").split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        }
        if (wording != null) current.put("meaning", wording)
        else current.optJSONArray("labels")?.let { labels ->
            // Back to what Opensolr saw: the labels joined, as at indexing time.
            current.put("meaning", (0 until labels.length()).map { labels.optString(it) }.filter { it.isNotBlank() }.joinToString(", "))
        }

        var vector: FloatArray? = null
        if (prefs.account?.vectorAllowed == true) {
            try {
                vector = api.batchEmbed(session, connection.indexName, listOf(SyncEngine.embeddingText(current))).first()
                current.put("embeddings", JSONArray().apply { vector.forEach { put(it.toDouble()) } })
                current.put("embed_model", "opensolr-${vector.size}")
            } catch (e: VectorNotAllowedException) {
                current.remove("embeddings")
            }
        }
        solr.add(JSONArray().put(current))
        solr.commit()

        // Keep the cache in step, so a later rewrite carries the same document and vector.
        MediaScanner.findByPath(context, current.optString("path"))?.let { photo ->
            val forCache = JSONObject(current.toString()).apply { remove("embeddings") }
            cache.put(id, photo.sizeBytes, photo.modifiedSec, forCache.toString(), vector)
        }
        current.remove("embeddings")
        return current
    }

    /**
     * Adds [tags] to every photo in [ids], keeping the tags each already has and leaving what
     * the photo shows untouched (Cip, 2026-09-16). Returns how many photos were written.
     *
     * Done in batches, not photo by photo: the documents of a batch are read in one request, the
     * new vectors for the whole batch in one `batch_embed` call, and the batch is written with
     * one update. So this costs one AI request per [EMBED_BATCH] photos, instead of one each.
     * Photos not in the index yet are skipped; their tags are kept on the phone anyway and go up
     * with them at the next sync.
     */
    suspend fun addTagsToAll(ids: List<String>, tags: List<String>, persons: List<String> = emptyList(), onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val session = prefs.session ?: throw ServiceException("Sign in to edit photos")
        var connection = prefs.connection ?: throw ServiceException("Your index is not set up yet.")
        val clean = tags.distinctWords()
        val names = persons.distinctWords()
        if ((clean.isEmpty() && names.isEmpty()) || ids.isEmpty()) return 0

        var solr = SolrClient(connection)
        var written = 0
        ids.chunked(EMBED_BATCH).forEach { batch ->
            val docs = try {
                fetchAll(solr, batch)
            } catch (e: SolrAuthException) {
                connection = IndexManager(context, prefs, api).refreshConnection(session)
                solr = SolrClient(connection)
                fetchAll(solr, batch)
            }
            if (docs.isEmpty()) return@forEach

            docs.forEach { doc ->
                doc.remove("_version_")
                doc.remove("score")
                val existing = doc.optJSONArray("custom_tags")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                val merged = (existing + clean).distinctWords()
                if (merged.isNotEmpty()) doc.put("custom_tags", JSONArray(merged))
                // The names are added the same way, to persons_t (searched) and persons_ss (the
                // People filter, albums and suggestions), keeping the ones already there.
                var mergedNames: List<String>? = null
                if (names.isNotEmpty()) {
                    val had = doc.optJSONArray("persons_ss")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                        ?: doc.optString("persons_t").split(',').map { it.trim() }
                    mergedNames = (had.filter { it.isNotBlank() } + names).distinctWords()
                    doc.put("persons_t", mergedNames.joinToString(", "))
                    doc.put("persons_ss", JSONArray(mergedNames))
                }
                // Kept on the phone too, so a later rewrite of the photo carries them again.
                val id = doc.optString("id")
                if (id.isNotEmpty()) {
                    val edits = cache.getEdits(id)
                    cache.putEdits(id, PhotoCache.Edits(if (clean.isEmpty()) edits?.tags ?: existing else merged, edits?.meaning, mergedNames ?: edits?.persons))
                }
            }

            // One vector per photo, all of them in a single request.
            if (prefs.account?.vectorAllowed == true) {
                try {
                    val vectors = api.batchEmbed(session, connection.indexName, docs.map { SyncEngine.embeddingText(it) })
                    docs.forEachIndexed { index, doc ->
                        vectors.getOrNull(index)?.let { vector ->
                            doc.put("embeddings", JSONArray().apply { vector.forEach { put(it.toDouble()) } })
                            doc.put("embed_model", "opensolr-${vector.size}")
                        }
                    }
                } catch (e: VectorNotAllowedException) {
                    docs.forEach { it.remove("embeddings") }
                }
            }

            solr.add(JSONArray().apply { docs.forEach { put(it) } })
            written += docs.size
            onProgress(written, ids.size)
        }
        solr.commit()
        return written
    }

    private companion object {
        /**
         * Photos per batch when tagging many at once: the most `batch_embed` takes in one call
         * (see OpensolrApi.batchEmbed), so a batch is one request for the vectors and one write.
         */
        const val EMBED_BATCH = 50
    }

    /**
     * The stored documents of [ids] from the index, in one request.
     */
    private suspend fun fetchAll(solr: SolrClient, ids: List<String>): List<JSONObject> {
        val json = solr.select(
            listOf(
                "q" to "*:*",
                "fq" to "{!terms f=id separator=| v=\$bulkIds}",
                "bulkIds" to ids.joinToString("|"),
                "fl" to "*",
                "rows" to ids.size.toString(),
            )
        )
        val docs = json.getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { docs.getJSONObject(it) }
    }

    /**
     * The stored document of [id] from the index, or null when it is not there.
     */
    private suspend fun fetch(solr: SolrClient, id: String): JSONObject? {
        val json = solr.select(listOf("q" to "{!term f=id v=\$doc_id}", "doc_id" to id, "fl" to "*", "rows" to "1"))
        val docs = json.getJSONObject("response").getJSONArray("docs")
        return if (docs.length() > 0) docs.getJSONObject(0) else null
    }
}
