package com.opensolr.photos.storage

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.opensolr.photos.auth.AuthFlow
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.SolrClient
import java.io.File

/**
 * One user, one library on every device: an item deleted on one phone is deleted on all of them. The deleting
 * phone removes it from the index and tells the account; every other phone reads the account's list of deletions
 * and removes its own file, its copies and its clone entry. The backend's files go in the next storage run.
 */
object Deletions {

    private const val SP = "opensolr_photos"
    private const val CURSOR = "deleted_cursor"
    private const val WAITING = "delete_waiting"
    // a file written this long after its deletion is a new one with the same name, never removed
    private const val NEWER_MS = 60_000L

    /** Deleted here: remembered, every trace on this phone removed, the account told when it can be. */
    /** [tell] false: the account is told later (by [push]), once what replaces the items is in the index. */
    suspend fun deletedHere(context: Context, ids: Collection<String>, tell: Boolean = true) {
        if (ids.isEmpty()) return
        val cache = PhotoCache.of(context)
        cache.markDeleted(ids, System.currentTimeMillis(), sent = false)
        dropHere(context, ids)
        if (tell) runCatching { push(context) }
    }

    /** A file this app just wrote in [folder] as [name] is a new item: an earlier deletion of that name (and of its PDF pages) no longer holds. */
    fun writtenHere(context: Context, folder: String, name: String, pages: Int = 0) {
        val id = com.opensolr.photos.media.MediaScanner.photoId(com.opensolr.photos.media.MediaScanner.folderKey(folder) + name)
        PhotoCache.of(context).forgetDeleted(listOf(id) + (1..pages).map { com.opensolr.photos.media.MediaScanner.pageId(id, it) })
    }

    /**
     * Deleted items the index holds again, written after their deletion: the same name saved anew (a PDF made again
     * on the web, a file another phone put back). Those are new items: no longer deleted here, their documents read
     * into the clone. Returns them. An index that cannot be read throws: nothing is decided on an answer not had.
     */
    suspend fun revive(context: Context, deleted: Map<String, Long>): Set<String> {
        if (deleted.isEmpty()) return emptySet()
        val prefs = AppPrefs(context)
        val connection = prefs.connection ?: return emptySet()
        val solr = SolrClient(connection)
        val back = HashSet<String>()
        deleted.keys.chunked(200).forEach { batch ->
            val docs = solr.select(listOf(
                "q" to "*:*", "fq" to "{!terms f=id}" + batch.joinToString(","), "fl" to "id,indexed_at", "rows" to batch.size.toString(),
            )).optJSONObject("response")?.optJSONArray("docs") ?: throw java.io.IOException("index answer without documents")
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val id = d.optString("id")
                val at = deleted[id] ?: continue
                val written = com.opensolr.photos.ui.Actions.solrDateMillis(d.optString("indexed_at")) ?: continue
                if (written > at) back += id
            }
        }
        if (back.isEmpty()) return back
        PhotoCache.of(context).forgetDeleted(back)
        com.opensolr.photos.search.EditRepository(context).readDocsIntoCache(back)
        // what the backend holds of them is listed again from the manifests
        prefs.manifestsReadAt = 0L
        com.opensolr.photos.data.CloneChanges.changed()
        return back
    }

    /** Deletions not yet told to the account, sent now. */
    suspend fun push(context: Context) {
        val prefs = AppPrefs(context)
        val session = prefs.session ?: return
        val index = prefs.connection?.indexName ?: return
        val cache = PhotoCache.of(context)
        val api = OpensolrApi()
        val device = AuthFlow.deviceId(context)
        while (true) {
            val batch = cache.unsentDeleted(2000)
            if (batch.isEmpty()) return
            api.photosDeletedAdd(session, index, batch, device)
            cache.markDeletedSent(batch)
            if (batch.size < 2000) return
        }
    }

    /** What the other devices deleted since the last look, removed here too. */
    suspend fun pull(context: Context) {
        val prefs = AppPrefs(context)
        val session = prefs.session ?: return
        val index = prefs.connection?.indexName ?: return
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val key = "$CURSOR:$index"
        var since = sp.getLong(key, 0L)
        val cache = PhotoCache.of(context)
        val api = OpensolrApi()
        while (true) {
            val (all, next, more) = api.photosDeletedSince(session, index, since)
            // an item saved again under the same name after its deletion is not deleted
            val alive = revive(context, all.toMap())
            val rows = all.filter { it.first !in alive }
            if (rows.isNotEmpty()) {
                val byTime = rows.groupBy({ it.second }, { it.first })
                byTime.forEach { (at, ids) -> cache.markDeleted(ids, at, sent = true) }
                // an item that went because its file was moved or renamed on another phone lives on under its new
                // name: this phone's file of it stays, as that item's file
                val moved = keptAsMoved(context, rows.toMap())
                // a file this phone must ask to remove is remembered by its place alone: the item itself is gone
                // from this phone at once, like everywhere else
                removeFiles(context, rows.toMap().filterKeys { it !in moved })
                dropHere(context, rows.map { it.first })
                com.opensolr.photos.data.CloneChanges.changed()
            }
            if (next <= since) break
            since = next
            sp.edit().putLong(key, since).apply()
            if (!more) break
        }
    }

    /**
     * Of the [deleted] items this phone holds a file of, those the index still holds under another id with the very
     * same bytes (the file was moved or renamed elsewhere) and no file of here: this phone's file becomes that
     * item's file, where it is. Returns the deleted ids so kept. An index that cannot be asked throws: nothing is
     * removed on an answer not had.
     */
    private suspend fun keptAsMoved(context: Context, deleted: Map<String, Long>): Set<String> {
        val cache = PhotoCache.of(context)
        val connection = AppPrefs(context).connection ?: return emptySet()
        val seen = cache.seenIds()
        val where = cache.mediaFor(deleted.keys.filter { it in seen })
        data class Old(val id: String, val mediaId: Long, val path: String, val hash: String, val size: Long, val page: Int)
        val olds = where.mapNotNull { (id, place) ->
            val (mediaId, path) = place
            if (path.isEmpty() || path.contains("/files/cloud/") || !(LocalFiles.has(id) || File(path).exists())) return@mapNotNull null
            val doc = cache.doc(id) ?: return@mapNotNull null
            val hash = doc.fileHash ?: return@mapNotNull null
            val page = doc.json?.let { runCatching { org.json.JSONObject(it).optInt("page_i") }.getOrNull() } ?: 0
            Old(id, LocalFiles.mediaId(id) ?: mediaId, path, hash, doc.sizeBytes, page)
        }
        if (olds.isEmpty()) return emptySet()
        val solr = SolrClient(connection)
        val live = ArrayList<org.json.JSONObject>()
        olds.map { it.hash }.distinct().chunked(100).forEach { batch ->
            solr.forEachDoc(com.opensolr.photos.search.EditRepository.CLONE_FIELDS, filters = listOf("fq" to "{!terms f=file_hash}" + batch.joinToString(","))) { live += it }
        }
        if (live.isEmpty()) return emptySet()
        val edits = com.opensolr.photos.search.EditRepository(context)
        @Suppress("DEPRECATION")
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val out = HashSet<String>()
        val given = HashSet<String>()
        for (old in olds) {
            val now = live.firstOrNull { d ->
                val id = d.optString("id")
                id != old.id && id !in deleted && id !in given && d.optString("file_hash") == old.hash &&
                    d.optLong("size_bytes") == old.size && d.optInt("page_i") == old.page && !LocalFiles.has(id)
            } ?: continue
            val id = now.optString("id")
            given += id
            edits.storeDoc(id, now, indexedAt = com.opensolr.photos.ui.Actions.solrDateMillis(now.optString("indexed_at")) ?: System.currentTimeMillis())
            cache.relocateDoc(id, old.mediaId, old.path)
            // the file's own name on this phone answers to the item's id from now on
            val name = old.path.substringAfterLast('/')
            val folder = com.opensolr.photos.media.MediaScanner.folderKey(old.path.removePrefix(root).substringBeforeLast('/', ""))
            val file = com.opensolr.photos.media.MediaScanner.photoId(folder + name)
            cache.putAlias(if (old.page > 0) com.opensolr.photos.media.MediaScanner.pageId(file, old.page) else file, id)
            if (old.mediaId > 0) LocalFiles.add(mapOf(id to old.mediaId))
            out += old.id
        }
        return out
    }

    /** The clone entry, the 720 px copy, the kept clip picture and the on-phone record of each item. */
    private fun dropHere(context: Context, ids: Collection<String>) {
        val cache = PhotoCache.of(context)
        cache.dropDeleted(ids)
        ids.forEach { id ->
            CloudFiles.remove(context, id)
            ClipThumbs.remove(context, id)
            LocalFiles.ids.remove(id)
        }
    }

    /**
     * This phone's own files of items deleted elsewhere, removed. With All files access (or before Android 10) the
     * file itself; otherwise through Android, which lets this app remove its own files at once and asks the owner
     * for the others (those wait for the next time the app is open).
     */
    @Suppress("DEPRECATION")
    private fun removeFiles(context: Context, deleted: Map<String, Long>): Set<String> {
        val cache = PhotoCache.of(context)
        val where = cache.mediaFor(deleted.keys)
        val direct = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager())
        val ask = ArrayList<String>()
        val askMedia = HashMap<String, Long>()
        val done = HashSet<String>()
        // only an item this phone has had as its own file: another phone's item can share a path with a file here
        val seen = cache.seenIds()
        for ((id, at) in deleted) {
            if (id !in seen) continue
            val (mediaId, path) = where[id] ?: continue
            if (path.isEmpty() || path.contains("/files/cloud/") || path in done) continue
            val file = File(path)
            if (!file.exists()) continue
            if (file.lastModified() > at + NEWER_MS) continue
            done += path
            if (direct) {
                if (file.delete()) android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                continue
            }
            val id2 = LocalFiles.mediaId(id) ?: mediaId
            val removed = id2 > 0 && runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id2), null, null) > 0 }.getOrDefault(false)
            if (!removed) { ask += id; if (id2 > 0) askMedia[id] = id2 }
        }
        if (ask.isNotEmpty()) {
            cache.setDeletePrompt(ask, true)
            val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val had = runCatching { org.json.JSONObject(sp.getString(WAITING, "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
            val kept = org.json.JSONObject()
            // only what is still to be asked: an item that came back since is not on the list any more
            cache.promptDeleted().forEach { id -> if (had.has(id)) kept.put(id, had.optLong(id)) }
            askMedia.forEach { (id, media) -> kept.put(id, media) }
            sp.edit().putString(WAITING, kept.toString()).apply()
        }
        return ask.toSet()
    }

    /** The files waiting for the owner's yes to be removed: item id -> this phone's MediaStore id of the file. */
    fun waitingMedia(context: Context): Map<String, Long> {
        val kept = runCatching { org.json.JSONObject(context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(WAITING, "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        return kept.keys().asSequence().associateWith { kept.optLong(it) }.filterValues { it > 0 }
    }

    /** Asked and removed (or the owner said no): the waiting items are dropped either way, never asked again. */
    fun promptsDone(context: Context, ids: Collection<String>) {
        PhotoCache.of(context).setDeletePrompt(ids, false)
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val kept = runCatching { org.json.JSONObject(sp.getString(WAITING, "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        ids.forEach { kept.remove(it) }
        sp.edit().putString(WAITING, kept.toString()).apply()
        dropHere(context, ids)
    }
}
