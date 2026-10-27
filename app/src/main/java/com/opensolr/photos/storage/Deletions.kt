package com.opensolr.photos.storage

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.opensolr.photos.auth.AuthFlow
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.net.OpensolrApi
import java.io.File

/**
 * One user, one library on every device: an item deleted on one phone is deleted on all of them. The deleting
 * phone removes it from the index and tells the account; every other phone reads the account's list of deletions
 * and removes its own file, its copies and its clone entry. The backend's files go in the next storage run.
 */
object Deletions {

    private const val SP = "opensolr_photos"
    private const val CURSOR = "deleted_cursor"
    // a file written this long after its deletion is a new one with the same name, never removed
    private const val NEWER_MS = 60_000L

    /** Deleted here: remembered, every trace on this phone removed, the account told when it can be. */
    suspend fun deletedHere(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val cache = PhotoCache.of(context)
        cache.markDeleted(ids, System.currentTimeMillis(), sent = false)
        dropHere(context, ids)
        runCatching { push(context) }
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
            val (rows, next, more) = api.photosDeletedSince(session, index, since)
            if (rows.isNotEmpty()) {
                val byTime = rows.groupBy({ it.second }, { it.first })
                byTime.forEach { (at, ids) -> cache.markDeleted(ids, at, sent = true) }
                // a file this phone must ask to remove keeps its clone entry until then: the path is needed
                val waiting = removeFiles(context, rows.toMap())
                dropHere(context, rows.map { it.first }.filter { it !in waiting })
            }
            if (next <= since) break
            since = next
            sp.edit().putLong(key, since).apply()
            if (!more) break
        }
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
            if (!removed) ask += id
        }
        if (ask.isNotEmpty()) cache.setDeletePrompt(ask, true)
        return ask.toSet()
    }

    /** Asked and removed (or the owner said no): the waiting items are dropped either way, never asked again. */
    fun promptsDone(context: Context, ids: Collection<String>) {
        PhotoCache.of(context).setDeletePrompt(ids, false)
        dropHere(context, ids)
    }
}
