package com.opensolr.photos.storage

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.io.File

/**
 * The photos that are only in the backend: their 720 px copies live in the app's own files, never in the
 * phone's photo folders, so the sync never meets them. The set is kept in memory for the grid.
 */
object CloudFiles {

    val ids = mutableStateMapOf<String, Boolean>()

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val known = com.opensolr.photos.data.PhotoCache.of(context).cloudIds()
            ids.clear()
            known.forEach { ids[it] = true }
            loaded = true
        }
    }

    @Volatile private var folder: File? = null

    // the folder is made once; the grid asks for files of it on every cell
    fun dir(context: Context): File = folder ?: File(context.filesDir, DIR).apply { mkdirs() }.also { folder = it }

    fun file(context: Context, id: String): File = File(dir(context), "$id.jpg")

    fun has(id: String): Boolean = ids.containsKey(id)

    fun add(id: String) { ids[id] = true }

    fun remove(context: Context, id: String) {
        ids.remove(id)
        file(context, id).delete()
    }

    fun clear(context: Context) {
        ids.clear()
        dir(context).deleteRecursively()
        folder = null
    }

    /** What the picture loader opens for [id]: the 720 px copy when the photo is only in the backend, else null. */
    fun model(context: Context, id: String): File? = if (has(id)) file(context, id) else null

    private const val DIR = "cloud"
}
