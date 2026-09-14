package com.opensolr.photos.search

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
     * Saves [tags] and [meaning] (null = keep what Opensolr saw) for the photo [id] and
     * updates its document in the index. Returns the document as written.
     */
    suspend fun save(id: String, tags: List<String>, meaning: String?): JSONObject {
        val session = prefs.session ?: throw ServiceException("Sign in to edit photos")
        var connection = prefs.connection ?: throw ServiceException("Your index is not set up yet.")
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val wording = meaning?.trim()?.takeIf { it.isNotEmpty() }
        cache.putEdits(id, PhotoCache.Edits(clean, wording))

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
     * The stored document of [id] from the index, or null when it is not there.
     */
    private suspend fun fetch(solr: SolrClient, id: String): JSONObject? {
        val json = solr.select(listOf("q" to "{!term f=id v=\$doc_id}", "doc_id" to id, "fl" to "*", "rows" to "1"))
        val docs = json.getJSONObject("response").getJSONArray("docs")
        return if (docs.length() > 0) docs.getJSONObject(0) else null
    }
}
