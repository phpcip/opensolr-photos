package com.opensolr.photos.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import com.opensolr.photos.media.PdfPages
import com.opensolr.photos.search.PhotoHit
import kotlinx.coroutines.sync.withPermit

/** The size a picture is made at is part of its key: one made for the grid is never taken for a larger view. */
internal fun edgeOf(options: Options): String = (options.size.width as? Dimension.Pixels)?.px?.toString() ?: "o"

/** One page of a PDF as something the picture loader can open. */
data class PdfPage(val uri: Uri, val page: Int, val sizeBytes: Long)

class PdfPageFetcher(private val data: PdfPage, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val wanted = ((options.size.width as? Dimension.Pixels)?.px?.coerceAtLeast(64) ?: DEFAULT_EDGE).coerceAtMost(MAX_EDGE)
        val bitmap = Thumbs.get(options.context, "p", data.uri, data.sizeBytes, data.page.toLong(), wanted) {
            PdfPages.render(options.context, data.uri, data.page, wanted)
        } ?: return null
        return DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<PdfPage> {
        override fun create(data: PdfPage, options: Options, imageLoader: ImageLoader): Fetcher = PdfPageFetcher(data, options)
    }

    class Key : Keyer<PdfPage> {
        override fun key(data: PdfPage, options: Options): String = "pdf:${data.uri}:${data.page}:${data.sizeBytes}:${edgeOf(options)}"
    }

    companion object {
        private const val DEFAULT_EDGE = 1024
        private const val MAX_EDGE = 2400
    }
}

/** A photo for the grid: the thumbnail the phone keeps for it, else the photo read small. */
/**
 * A photo for the picture loader. [quick] is the grid in motion: only the thumbnail the phone already keeps
 * is taken, nothing is decoded. It is not part of the photo's identity, so the grid starting or stopping
 * never asks again for a picture already shown.
 */
data class PhotoThumb(val uri: Uri, val stamp: Long, val sizeBytes: Long) {
    var quick: Boolean = false
}

class PhotoThumbFetcher(private val data: PhotoThumb, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val context = options.context
        val wanted = ((options.size.width as? Dimension.Pixels)?.px?.coerceAtLeast(64) ?: 360).coerceAtMost(512)
        // kept on disk once made: the next time, and while the grid moves, it is just read
        val bitmap = Thumbs.get(context, "i", data.uri, data.sizeBytes, data.stamp, wanted) {
            // The phone's own thumbnail, which it keeps for every photo it scanned: cheap, and the same one
            // the system gallery shows. While the grid moves that is all that is tried; decoding the whole
            // file, which is the expensive part, waits for the grid to stop.
            val kept = if (android.os.Build.VERSION.SDK_INT >= 29) {
                runCatching { context.contentResolver.loadThumbnail(data.uri, android.util.Size(wanted, wanted), null) }.getOrNull()
            } else null
            kept ?: if (data.quick) null else runCatching { com.opensolr.photos.media.PhotoReader.uprightBitmap(context, data.uri, wanted) }.getOrNull()
        } ?: return null
        return DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<PhotoThumb> {
        override fun create(data: PhotoThumb, options: Options, imageLoader: ImageLoader): Fetcher = PhotoThumbFetcher(data, options)
    }


    class Key : Keyer<PhotoThumb> {
        override fun key(data: PhotoThumb, options: Options): String = "photo:${data.uri}:${data.stamp}:${edgeOf(options)}"
    }
}

/** A clip as something the picture loader can open: its kept picture, made once from the clip when there is none. */
data class VideoThumb(val id: String, val uri: Uri, val noPicture: Boolean = false)

class VideoThumbFetcher(private val data: VideoThumb, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val context = options.context
        val wanted = ((options.size.width as? Dimension.Pixels)?.px?.coerceAtLeast(64) ?: 720).coerceAtMost(1920)
        // a clip indexed without a picture kept only a stand-in: its real first frame is tried once through the player
        if (data.noPicture && com.opensolr.photos.storage.ClipThumbs.has(context, data.id) && triedPlayer.add(data.id)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.opensolr.photos.media.VideoFrames.playerFrame(context, data.uri, com.opensolr.photos.storage.StorageEngine.RESTORE_EDGE) }?.let { real ->
                com.opensolr.photos.storage.ClipThumbs.save(context, data.id, real)
                real.recycle()
            }
        }
        if (!com.opensolr.photos.storage.ClipThumbs.has(context, data.id)) {
            val made = Thumbs.drawOne { com.opensolr.photos.media.VideoFrames.thumbnail(context, data.uri, com.opensolr.photos.storage.StorageEngine.RESTORE_EDGE) }
                // a clip Android cannot read: the app's own player gives its picture, one clip at a time
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.opensolr.photos.media.VideoFrames.playerFrame(context, data.uri, com.opensolr.photos.storage.StorageEngine.RESTORE_EDGE) }
                ?: return null
            com.opensolr.photos.storage.ClipThumbs.save(context, data.id, made)
            made.recycle()
        }
        var bitmap = com.opensolr.photos.storage.ClipThumbs.read(context, data.id, wanted)
        // one kept before black pictures were refused: made again from the clip
        if (com.opensolr.photos.media.VideoFrames.isBlank(bitmap)) {
            com.opensolr.photos.storage.ClipThumbs.remove(context, data.id)
            val made = Thumbs.drawOne { com.opensolr.photos.media.VideoFrames.thumbnail(context, data.uri, com.opensolr.photos.storage.StorageEngine.RESTORE_EDGE) }
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.opensolr.photos.media.VideoFrames.playerFrame(context, data.uri, com.opensolr.photos.storage.StorageEngine.RESTORE_EDGE) }
                ?: return null
            com.opensolr.photos.storage.ClipThumbs.save(context, data.id, made)
            made.recycle()
            bitmap = com.opensolr.photos.storage.ClipThumbs.read(context, data.id, wanted)
        }
        if (bitmap == null) return null
        return DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher = VideoThumbFetcher(data, options)
    }

    class Key : Keyer<VideoThumb> {
        override fun key(data: VideoThumb, options: Options): String = "clip:${data.id}:${edgeOf(options)}"
    }

    companion object {
        private val triedPlayer: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

/**
 * Grid-size pictures of clips and PDF pages kept on disk once drawn, so a grid of them shows at once the next
 * time instead of each file being read again. Bounded: the oldest go when the folder grows past its cap.
 */
object Thumbs {

    private const val GRID_EDGE = 512
    private const val CAP_BYTES = 256L * 1024 * 1024
    private const val TRIM_TO = 192L * 1024 * 1024
    private val writes = java.util.concurrent.atomic.AtomicInteger(0)

    // a few files read at once: the phone's decoders are shared, and a cell scrolled away gives its turn up
    private val drawing = kotlinx.coroutines.sync.Semaphore(6)

    suspend fun <T> drawOne(draw: () -> T): T = drawing.withPermit { draw() }

    private fun key(kind: String, uri: Uri, sizeBytes: Long, page: Long, edge: Int): String =
        java.security.MessageDigest.getInstance("MD5").digest("$kind:$uri:$sizeBytes:$page:$edge".toByteArray()).joinToString("") { "%02x".format(it) }

    // the pictures on disk: listed once, then kept as they are written and trimmed
    @Volatile private var known: MutableSet<String>? = null

    private fun index(context: Context): MutableSet<String> = known ?: synchronized(this) {
        known ?: java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            java.io.File(context.cacheDir, "thumbs").list()?.forEach { n -> if (n.endsWith(".jpg")) add(n.removeSuffix(".jpg")) }
        }.also { known = it }
    }

    fun warm(context: Context) { index(context) }

    /** Whether the grid picture is already on disk: shown at once, even while the grid moves. */
    fun has(context: Context, kind: String, uri: Uri, sizeBytes: Long, page: Long, edge: Int): Boolean =
        index(context).contains(key(kind, uri, sizeBytes, page, edge))

    suspend fun get(context: Context, kind: String, uri: Uri, sizeBytes: Long, page: Long, edge: Int, draw: suspend () -> android.graphics.Bitmap?): android.graphics.Bitmap? {
        // only grid sizes are kept; the viewer's large pictures are drawn each time
        if (edge > GRID_EDGE) return drawing.withPermit { draw() }
        val dir = java.io.File(context.cacheDir, "thumbs").apply { mkdirs() }
        val key = key(kind, uri, sizeBytes, page, edge)
        val file = java.io.File(dir, "$key.jpg")
        if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return it }
        val bitmap = drawing.withPermit { draw() } ?: return null
        runCatching {
            val tmp = java.io.File(dir, "$key.part")
            tmp.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            if (tmp.renameTo(file)) known?.add(key) else tmp.delete()
        }
        if (writes.incrementAndGet() % 100 == 0) trim(dir)
        return bitmap
    }

    private fun trim(dir: java.io.File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".jpg") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CAP_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= TRIM_TO) break
            total -= f.length()
            f.delete()
            known?.remove(f.name.removeSuffix(".jpg"))
        }
    }
}

object Images {

    /** The picture loader: photos and videos (their first frame) from their content URIs, PDF pages rendered here. */
    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(coil.decode.VideoFrameDecoder.Factory())
            add(PdfPageFetcher.Factory())
            add(PdfPageFetcher.Key())
            add(PhotoThumbFetcher.Factory())
            add(PhotoThumbFetcher.Key())
            add(VideoThumbFetcher.Factory())
            add(VideoThumbFetcher.Key())
        }
        .crossfade(true)
        .build()

    /** What a small picture of [hit] opens (grid, map, sheets): its kept or 720p copy, else the phone's own thumbnail. */
    fun small(context: Context, hit: PhotoHit): Any = model(context, hit).let { m ->
        if (m is Uri && hit.mediaKind == com.opensolr.photos.media.Kind.PHOTO && m.scheme == "content")
            PhotoThumb(m, com.opensolr.photos.ui.screens.EditedPhotos.stamp(hit.mediaId), hit.sizeBytes) else m
    }

    /** Whether [hit]'s grid picture is on this phone already, so it is shown even while the grid moves. */
    fun readyNow(context: Context, hit: PhotoHit, stamp: Long, edge: Int): Boolean = when {
        com.opensolr.photos.storage.CloudFiles.has(hit.id) -> true
        hit.isVideo -> com.opensolr.photos.storage.ClipThumbs.has(context, hit.id)
        hit.isPdfPage -> Thumbs.has(context, "p", hit.uri, hit.sizeBytes, hit.page.toLong(), edge)
        else -> Thumbs.has(context, "i", hit.uri, hit.sizeBytes, stamp, edge)
    }

    /** What the loader opens for [hit]: the 720 px copy when only the backend has it, else the file by its kind. */
    fun model(context: Context, hit: PhotoHit): Any {
        com.opensolr.photos.storage.CloudFiles.model(context, hit.id)?.let { return it }
        return if (hit.isPdfPage) PdfPage(hit.uri, hit.page, hit.sizeBytes) else if (hit.isVideo) VideoThumb(hit.id, hit.uri, hit.noPicture) else hit.uri
    }

    /** "1:05" / "1:02:33" for a clip's length. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
