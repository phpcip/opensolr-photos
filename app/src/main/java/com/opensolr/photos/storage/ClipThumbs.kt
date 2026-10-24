package com.opensolr.photos.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * One picture per clip, kept for good: the one the backend holds when the clip came from it, else the one made
 * here once from the clip. The grid shows it and the backup sends it; nothing is drawn from the clip again.
 */
object ClipThumbs {

    @Volatile private var folder: File? = null
    // which clips have their picture: read from the folder once, then kept as pictures are saved and removed
    @Volatile private var known: MutableSet<String>? = null

    private fun dir(context: Context): File = folder ?: File(context.filesDir, "thumbs").apply { mkdirs() }.also { folder = it }

    fun file(context: Context, id: String): File = File(dir(context), "$id.jpg")

    private fun index(context: Context): MutableSet<String> = known ?: synchronized(this) {
        known ?: java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            dir(context).list()?.forEach { n -> if (n.endsWith(".jpg")) add(n.removeSuffix(".jpg")) }
        }.also { known = it }
    }

    /** Read the folder ahead (off the main thread), so the grid's first question costs nothing. */
    fun warm(context: Context) { index(context) }

    fun has(context: Context, id: String): Boolean = index(context).contains(id)

    fun remove(context: Context, id: String) {
        file(context, id).delete()
        known?.remove(id)
    }

    fun save(context: Context, id: String, bytes: ByteArray) {
        // a black picture (from the backend or made here) is not kept: the clip's own frames are tried instead
        val probe = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 })
        if (com.opensolr.photos.media.VideoFrames.isBlank(probe)) return
        probe?.recycle()
        val target = file(context, id)
        val tmp = File(target.parentFile, "$id.part")
        tmp.writeBytes(bytes)
        if (tmp.renameTo(target)) known?.add(id) else tmp.delete()
    }

    fun save(context: Context, id: String, bitmap: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        save(context, id, out.toByteArray())
    }

    /** The kept picture, read at about [edge] px. */
    fun read(context: Context, id: String, edge: Int): Bitmap? {
        val f = file(context, id)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
