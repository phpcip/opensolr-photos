package com.opensolr.photos.storage

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.ScanOptions

/**
 * Which documents of the index have their file on this phone. On a phone that shares the index (a second phone,
 * a restore) many documents are another phone's: they are shown by a sign and Download, never opened by an id
 * that belongs to the other phone. Rebuilt at start, after a sync and after a storage run.
 */
object LocalFiles {

    val ids = mutableStateMapOf<String, Boolean>()

    /** False until the first build: nothing is called absent before the phone was looked at. */
    val ready = mutableStateOf(false)

    private val lock = Any()

    fun has(id: String): Boolean = ids.containsKey(id)

    fun add(found: Collection<String>) { found.forEach { ids[it] = true } }

    /** The files in the synced folders, plus every document whose path in the clone is a file on this phone. */
    fun refresh(context: Context) {
        synchronized(lock) {
            val prefs = com.opensolr.photos.data.AppPrefs(context)
            val cache = PhotoCache.of(context)
            val present = HashSet<String>()
            MediaScanner.scan(context, prefs.folders, cache.aliases(), ScanOptions.of(context))?.keys?.let { present += it }
            val media = cache.docMedia()
            val paths = media.values.map { it.second }.filter { it.isNotEmpty() && !it.contains("/files/cloud/") }
            val onDisk = MediaScanner.existingPaths(context, paths)
            media.forEach { (id, m) -> if (m.second in onDisk) present += id }
            ids.keys.retainAll(present)
            present.forEach { ids[it] = true }
            ready.value = true
        }
    }
}
