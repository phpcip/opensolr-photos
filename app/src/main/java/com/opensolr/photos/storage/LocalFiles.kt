package com.opensolr.photos.storage

import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.LocalPhoto
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.ScanOptions

/**
 * Which documents of the index have their file on this phone, and under which MediaStore id right now. The id
 * is asked of the phone by the file's path every time, never trusted from what was written earlier: another
 * phone's id, or the id of a copy that is gone, must never be opened. Rebuilt at start, after a sync and before
 * and after a storage run.
 */
object LocalFiles {

    /** document id -> this phone's MediaStore id of its file */
    val ids = mutableStateMapOf<String, Long>()

    /** False until the first build: nothing is called absent before the phone was looked at. */
    val ready = mutableStateOf(false)

    private val lock = Any()

    fun has(id: String): Boolean = ids.containsKey(id)

    fun mediaId(id: String): Long? = ids[id]

    fun add(found: Map<String, Long>) { found.forEach { (k, v) -> ids[k] = v } }

    private val copySuffix = Regex("^(.*) \\(\\d+\\)(\\.[^./]+)$")

    /** [scanned]: the synced folders when the caller has just read them. */
    fun refresh(context: Context, scanned: Map<String, LocalPhoto>? = null) {
        synchronized(lock) {
            val cache = PhotoCache.of(context)
            val present = HashMap<String, Long>()
            val synced = scanned ?: MediaScanner.scan(context, com.opensolr.photos.data.AppPrefs(context).folders, cache.aliases(), ScanOptions.of(context))
            synced?.values?.forEach { present[it.id] = it.mediaId }
            val docs = cache.docFiles().filterKeys { it !in present }
            // where the file can be: the path the clone has, the same without a " (n)", and where a restore writes it
            fun places(d: PhotoCache.DocFile): List<String> {
                val out = ArrayList<String>(3)
                if (d.path.isNotEmpty() && !d.path.contains("/files/cloud/")) {
                    out += d.path
                    copySuffix.matchEntire(d.path)?.let { m -> out += m.groupValues[1] + m.groupValues[2] }
                }
                d.remotePath?.let { out += StorageEngine.placedPaths(it) }
                return out
            }
            val wanted = HashSet<String>()
            docs.values.forEach { wanted += places(it) }
            val files = filesByPath(context, wanted)
            val fixes = ArrayList<Triple<String, Long, String>>()
            docs.forEach { (id, d) ->
                // the same file: a path it can be at, and its size (a different photo of the same name is not it)
                val found = places(d).firstNotNullOfOrNull { path ->
                    files[path]?.takeIf { f -> d.size <= 0 || f.second == d.size || f.second == d.remoteBytes }?.let { path to it.first }
                } ?: return@forEach
                present[id] = found.second
                // the clone follows the file it really is now
                if (found.second != d.mediaId || found.first != d.path) fixes += Triple(id, found.second, found.first)
            }
            fixes.forEach { (id, mid, path) -> runCatching { cache.relocateDoc(id, mid, path) } }
            // only what changed, added in one write: a write per file into the screen's state costs seconds on a big library
            ids.keys.filter { it !in present }.forEach { ids.remove(it) }
            val changed = present.filter { (k, v) -> ids[k] != v }
            if (changed.isNotEmpty()) ids.putAll(changed)
            ready.value = true
        }
    }

    /** path -> (current MediaStore id, size), for every kind of file, in chunks. */
    @Suppress("DEPRECATION")
    private fun filesByPath(context: Context, paths: Collection<String>): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        paths.chunked(400).forEach { batch ->
            val marks = batch.joinToString(",") { "?" }
            runCatching {
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.DATA} IN ($marks)", batch.toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) out[c.getString(1)] = c.getLong(0) to c.getLong(2)
                }
            }
        }
        return out
    }
}
