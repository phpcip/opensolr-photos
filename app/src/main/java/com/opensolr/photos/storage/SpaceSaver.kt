package com.opensolr.photos.storage

import android.content.Context
import android.net.Uri
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.PhotoReader
import java.io.File

/**
 * Room back on the phone: a photo the backend already holds at full size does not have to sit here at full
 * size too. Its 720 px copy is written into the app's own files, exactly as a restore writes one, and the
 * full file is then let go from the phone's photo folders.
 *
 * Only photos. A clip or a PDF has no smaller copy to fall back on, so neither is ever touched. The full
 * file is never the last one: a photo is offered only when the backend already has it, and Download all
 * originals brings it back whenever the owner wants it.
 */
object SpaceSaver {

    /** What a clean-up would move, and what it would leave behind. */
    data class Survey(val count: Int, val localBytes: Long, val copyBytes: Long) {
        val savedBytes: Long get() = (localBytes - copyBytes).coerceAtLeast(0L)
    }

    /** How many photos are measured to see how big a 720 px copy comes out for this library. */
    private const val SAMPLE = 3

    /** How many are moved in one go, so a huge library does not sit behind one endless prompt. */
    const val BATCH = 200

    /** How many photos are in that state, how much room they take, and what the copies would cost. */
    fun survey(context: Context): Survey {
        val cache = PhotoCache.of(context)
        // which files are really here is what the phone says, not what the clone wrote earlier
        LocalFiles.refresh(context)
        val here = LocalFiles.ids.keys.toList()
        val totals = cache.spaceSaverTotals(here)
        val count = totals.first
        val localBytes = totals.second
        if (count == 0) return Survey(0, 0L, 0L)

        // The copies already here from a restore say what one costs; without any, a few are made to find out
        val made = CloudFiles.dir(context).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
        val perCopy = if (made.isNotEmpty()) {
            made.take(20).map { it.length() }.average()
        } else {
            val sample = cache.spaceSaverIds(here, SAMPLE).mapNotNull { id -> copySize(context, id) }
            if (sample.isEmpty()) 0.0 else sample.average()
        }
        return Survey(count, localBytes, (perCopy * count).toLong())
    }

    /** The size a 720 px copy of one photo comes out at, or null when it cannot be made. */
    private fun copySize(context: Context, id: String): Long? {
        val uri = uriOf(context, id) ?: return null
        val tmp = File.createTempFile("measure", ".jpg", context.cacheDir)
        return try {
            if (PhotoReader.shareCopy(context, uri, tmp, StorageEngine.RESTORE_EDGE)) tmp.length() else null
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** The photos a clean-up would move, newest first. */
    fun candidates(context: Context, limit: Int): List<String> {
        LocalFiles.refresh(context)
        return PhotoCache.of(context).spaceSaverIds(LocalFiles.ids.keys.toList(), limit)
    }

    /** Of what is ticked, the ones that can be moved. Empty when even one of them cannot. */
    fun movable(context: Context, ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val here = LocalFiles.ids.keys
        val present = ids.filter { it in here }
        return if (present.isEmpty()) emptySet() else PhotoCache.of(context).spaceSaverAmong(present)
    }

    /**
     * Writes the 720 px copy of each photo into the app's own files, WITHOUT letting go of the full file:
     * what comes back are the ones ready for the phone's own delete prompt. Nothing counts as moved until
     * [commit] is told which files really went.
     */
    fun prepare(context: Context, ids: List<String>): List<String> {
        val ready = ArrayList<String>(ids.size)
        val dir = CloudFiles.dir(context)
        for (id in ids) {
            val uri = uriOf(context, id) ?: continue
            val part = File(dir, "$id.part")
            try {
                if (PhotoReader.shareCopy(context, uri, part, StorageEngine.RESTORE_EDGE)) ready += id else part.delete()
            } catch (e: Exception) {
                part.delete()
            }
        }
        return ready
    }

    /** The files the phone agreed to let go: their copies take over, and the library shows them from here on. */
    fun commit(context: Context, ids: Collection<String>) {
        val cache = PhotoCache.of(context)
        val dir = CloudFiles.dir(context)
        for (id in ids) {
            val part = File(dir, "$id.part")
            val target = File(dir, "$id.jpg")
            if (!part.exists()) continue
            if (!part.renameTo(target)) { part.delete(); continue }
            cache.putCloud(id, target.length())
            CloudFiles.add(id)
            LocalFiles.ids.remove(id)
        }
    }

    /** Nothing was let go after all: the copies made for it go, and the library is as it was. */
    fun rollback(context: Context, ids: Collection<String>) {
        val dir = CloudFiles.dir(context)
        for (id in ids) File(dir, "$id.part").delete()
    }

    /** The phone's own address of a photo, for reading it and for the delete prompt. */
    fun uriOf(context: Context, id: String): Uri? {
        val media = LocalFiles.mediaId(id) ?: PhotoCache.of(context).mediaFor(listOf(id))[id]?.first
        if (media == null || media <= 0L) return null
        return android.content.ContentUris.withAppendedId(android.provider.MediaStore.Files.getContentUri("external"), media)
    }

    /** The addresses of a set of photos, in the order given. */
    fun urisOf(context: Context, ids: List<String>): List<Uri> = ids.mapNotNull { uriOf(context, it) }
}
