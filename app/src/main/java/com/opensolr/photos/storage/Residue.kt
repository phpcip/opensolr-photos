package com.opensolr.photos.storage

import android.content.Context
import com.opensolr.photos.data.PhotoCache
import java.io.File

/**
 * Everything the app leaves behind goes: temporary copies of runs cut short, half-written files, kept pictures
 * of items no longer in the library. Run as the app opens and before every storage run. Only the app's own private folders are touched.
 */
object Residue {

    private val lock = Any()

    fun sweep(context: Context) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val stale = now - STALE_MS
            val old = now - ORPHAN_MS
            // temporary copies of uploads, downloads, manifests and reads cut short
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < stale && TEMP_PREFIXES.any { f.name.startsWith(it) }) f.delete()
            }
            // half-written files in the app's picture folders
            listOf(File(context.filesDir, "cloud"), File(context.filesDir, "thumbs"), File(context.cacheDir, "thumbs")).forEach { dir ->
                dir.listFiles { f -> f.name.endsWith(".part") && f.lastModified() < stale }?.forEach { it.delete() }
            }
            // working folders of a share, a PDF export, an edit or a scan that ended long ago
            WORK_DIRS.forEach { name ->
                val dir = File(context.cacheDir, name)
                if (dir.exists() && (dir.listFiles()?.all { it.lastModified() < old } != false)) dir.deleteRecursively()
            }
            LEFTOVER_FILES.forEach { File(context.filesDir, it).delete() }
            val cache = PhotoCache.of(context)
            // 720 px copies of items no longer only in the backend
            val cloudDir = File(context.filesDir, "cloud")
            val cloudFiles = cloudDir.listFiles { f -> f.name.endsWith(".jpg") && f.lastModified() < old }.orEmpty()
            if (cloudFiles.isNotEmpty()) {
                val kept = cache.cloudIds()
                cloudFiles.forEach { f -> if (f.name.removeSuffix(".jpg") !in kept) f.delete() }
            }
            // kept pictures of clips no longer in the library
            val clipDir = File(context.filesDir, "thumbs")
            val clipFiles = clipDir.listFiles { f -> f.name.endsWith(".jpg") && f.lastModified() < old }.orEmpty()
            if (clipFiles.isNotEmpty()) {
                val ids = clipFiles.map { it.name.removeSuffix(".jpg") }
                val known = cache.docIdsAmong(ids)
                clipFiles.forEach { f -> val id = f.name.removeSuffix(".jpg"); if (id !in known) ClipThumbs.remove(context, id) }
            }
        }
    }

    private const val STALE_MS = 10 * 60_000L
    private const val ORPHAN_MS = 24 * 60 * 60_000L
    private val TEMP_PREFIXES = listOf("original", "restore", "main", "manifest", "ingest")
    private val WORK_DIRS = listOf("share", "pdf_sources", "edit", "scan")
    private val LEFTOVER_FILES = listOf("pdf_trace.txt")
}
