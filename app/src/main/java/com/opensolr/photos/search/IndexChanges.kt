package com.opensolr.photos.search

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.net.SolrClient

/**
 * What any device changed in the shared index since this phone last looked (edits, places, new items) read into
 * this phone's clone, so an edit made on one phone is the one every phone keeps and sends. Walked by the index's
 * own version numbers, a page at a time; an item with a change of this phone still waiting is left alone.
 */
object IndexChanges {

    private const val SP = "opensolr_photos"
    private const val PAGE = 1000

    suspend fun pull(context: Context) {
        val prefs = AppPrefs(context)
        val connection = prefs.connection ?: return
        if (!prefs.cloneComplete) return
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val key = "version_cursor:" + connection.indexName
        val solr = SolrClient(connection)
        var cursor = sp.getLong(key, 0L)
        if (cursor <= 0L) {
            // the first time: the clone was just read whole, so only what changes from now on is followed
            val top = solr.select(listOf("q" to "*:*", "rows" to "1", "fl" to "_version_", "sort" to "_version_ desc"))
            val v = top.optJSONObject("response")?.optJSONArray("docs")?.optJSONObject(0)?.optLong("_version_") ?: 0L
            if (v > 0) sp.edit().putLong(key, v).apply()
            return
        }
        val cache = PhotoCache.of(context)
        val edits = EditRepository(context)
        while (true) {
            val answer = solr.select(listOf(
                "q" to "*:*", "fq" to "{!frange l=$cursor incl=false}_version_",
                "fl" to "id,_version_", "sort" to "_version_ asc", "rows" to PAGE.toString(),
            ))
            val docs = answer.optJSONObject("response")?.optJSONArray("docs") ?: break
            if (docs.length() == 0) break
            val ids = ArrayList<String>(docs.length())
            var top = cursor
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                d.optString("id").takeIf { it.isNotEmpty() }?.let { ids += it }
                top = maxOf(top, d.optLong("_version_"))
            }
            val waiting = cache.pendingAmong(ids)
            edits.readDocsIntoCache(ids.filter { it !in waiting })
            cursor = top
            sp.edit().putLong(key, cursor).apply()
            if (docs.length() < PAGE) break
        }
    }
}
