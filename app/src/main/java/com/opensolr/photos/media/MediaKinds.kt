package com.opensolr.photos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.opensolr.photos.data.PhotoCache
import java.security.MessageDigest
import kotlin.math.max

/** PDFs: how many pages, and one page as a picture. */
object PdfPages {

    /** The page count, read once per file and size and kept in the clone; 0 when the file cannot be opened (a password, a broken file). */
    fun count(context: Context, cache: PhotoCache, pdf: LocalPhoto): Int {
        cache.pdfPages(pdf.id, pdf.sizeBytes)?.let { return it }
        val pages = try {
            context.contentResolver.openFileDescriptor(pdf.uri, "r")?.use { fd -> PdfRenderer(fd).use { it.pageCount } } ?: 0
        } catch (e: Exception) {
            0
        }
        cache.putPdfPages(pdf.id, pdf.sizeBytes, pages)
        return pages
    }

    /** Page [page] (1-based) of the PDF at [uri], white background, long edge at most [edge] px; null when it cannot be rendered. */
    fun render(context: Context, uri: Uri, page: Int, edge: Int): Bitmap? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (page < 1 || page > renderer.pageCount) return null
                renderer.openPage(page - 1).use { p ->
                    val scale = edge.toFloat() / max(p.width, p.height).coerceAtLeast(1)
                    val w = max(1, (p.width * scale).toInt())
                    val h = max(1, (p.height * scale).toInt())
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Videos: the frame that stands for the clip, and the date and place the clip carries. */
object VideoFrames {

    class Facts(val takenMs: Long?, val latitude: Double?, val longitude: Double?, val rotation: Int)

    /**
     * A frame that stands for the clip, upright, long edge at most [edge] px. Several ways, in turn, because phones
     * differ in what they can open (iPhone Live Photo clips: HEVC, PCM sound, QuickTime, often under a second):
     * one second in or half way through a short clip, then the first frame, then frame 0 by index, then the
     * thumbnail Android itself makes.
     */
    fun frame(context: Context, uri: Uri, edge: Int): Bitmap? {
        val a = retrieverFrame(context, uri)
        val b = if (a == null) systemThumbnail(context, uri, edge) else null
        // a clip Android cannot read: the app's own player, turned the way the clip is shown
        val c = if (a == null && b == null) playerFrame(context, uri, edge) else null
        val raw = (a ?: b)?.let { upright(context, uri, it) } ?: c ?: return null
        val longEdge = max(raw.width, raw.height)
        if (longEdge <= edge) return raw
        val scale = edge.toFloat() / longEdge
        val small = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postScale(scale, scale) }, true)
        if (small !== raw) raw.recycle()
        return small
    }

    /**
     * For the grid: the thumbnail the phone already keeps for the clip, else one frame read at grid size. Never
     * a player: a grid holds dozens of clips at once.
     */
    fun thumbnail(context: Context, uri: Uri, edge: Int): Bitmap? =
        (systemThumbnail(context, uri, edge)?.takeIf { !isBlank(it) } ?: scaledFrame(context, uri, edge))?.let { upright(context, uri, it) }

    /**
     * A frame Android gave as it is stored (some iPhone clips): turned the way the clip says it is shown. Only a
     * quarter turn can be seen from the picture's shape, so only that is corrected; a frame already upright is kept.
     * One read of the file's header, a few KB.
     */
    private fun upright(context: Context, uri: Uri, frame: Bitmap, stored: Boolean = false): Bitmap {
        val info = Mp4Info.read(context, uri) ?: return frame
        // the app's own decoder gives the picture as stored: a half turn is applied there as well
        if (stored && info.rotation == 180) {
            val half = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(180f) }, true)
            if (half !== frame) frame.recycle()
            return half
        }
        if (frame.width == frame.height) return frame
        if ((info.rotation != 90 && info.rotation != 270) || info.width <= 0 || info.height <= 0 || info.width == info.height) return frame
        // still in the stored shape (wide for a clip shown tall): it was not turned
        if ((frame.width > frame.height) != (info.width > info.height)) return frame
        val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(info.rotation.toFloat()) }, true)
        if (turned !== frame) frame.recycle()
        return turned
    }

    /** True when the picture is all but black (a clip that fades in, a dark first second): never kept as its picture. */
    fun isBlank(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return true
        var brightest = 0
        for (yi in 0 until 12) for (xi in 0 until 12) {
            val c = bitmap.getPixel(bitmap.width * (2 * xi + 1) / 24, bitmap.height * (2 * yi + 1) / 24)
            brightest = maxOf(brightest, (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10)
            if (brightest > 24) return false
        }
        return true
    }

    /** The moments tried in turn for a clip's picture: one second in (half way for a short clip), then a quarter and half of it. */
    private fun moments(durationMs: Long): List<Long> =
        if (durationMs in 1..1999) listOf(durationMs * 500L)
        else listOf(1_000_000L, durationMs * 250L, durationMs * 500L).filter { durationMs <= 0 || it < durationMs * 1000L }.distinct()

    private fun scaledFrame(context: Context, uri: Uri, edge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var found: Bitmap? = null
            for (atUs in moments(durationMs)) {
                val b = if (android.os.Build.VERSION.SDK_INT >= 27) runCatching { retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge) }.getOrNull()
                    else runCatching { retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                if (b != null && !isBlank(b)) { found = b; break }
                b?.recycle()
            }
            found
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun retrieverFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            moments(durationMs).firstNotNullOfOrNull { atUs -> runCatching { retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()?.takeIf { !isBlank(it) } }
                ?: runCatching { retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                ?: runCatching { retriever.getFrameAtTime(-1) }.getOrNull()
                ?: runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) retriever.getFrameAtIndex(0) else null }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Last way: ExoPlayer, which parses QuickTime itself, decodes the first frame onto an ImageReader. Runs on a
     * thread of its own with a looper; gives up after a few seconds.
     */
    /**
     * Last way, for clips Android's own reader refuses (an iPhone Live Photo clip with raw PCM sound): the app's
     * player reads the file itself. One at a time, never several players at once; the picture is kept after.
     */
    @Synchronized
    fun playerFrame(context: Context, uri: Uri, edge: Int): Bitmap? {
        val frame = exoFrame(context, uri) ?: return null
        if (isBlank(frame)) { frame.recycle(); return null }
        // turned the way the clip is shown, unless the decoder already did
        val raw = upright(context, uri, frame, stored = true)
        val longEdge = max(raw.width, raw.height)
        if (longEdge <= edge) return raw
        val scale = edge.toFloat() / longEdge
        val small = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postScale(scale, scale) }, true)
        if (small !== raw) raw.recycle()
        return small
    }

    private fun exoFrame(context: Context, uri: Uri): Bitmap? {
        val thread = android.os.HandlerThread("exo-frame").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Bitmap? = null
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        val known = Mp4Info.read(context, uri)

        // the surface the decoder draws into, read back through an offscreen texture: works whatever the decoder writes
        var grab: GlFrame? = null
        fun attach(p: androidx.media3.exoplayer.ExoPlayer, width: Int, height: Int) {
            if (grab != null || width <= 0 || height <= 0) return
            val g = runCatching { GlFrame(width, height, handler) { bitmap -> if (!isBlank(bitmap)) { out = bitmap; latch.countDown() } } }.getOrNull() ?: return
            grab = g
            p.setVideoSurface(g.surface)
        }

        handler.post {
            try {
                val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).setLooper(thread.looper).build()
                player = p
                p.volume = 0f
                // the picture only: the sound track (raw PCM in Live Photo clips) is not opened at all
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
                p.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { attach(p, size.width, size.height) }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { latch.countDown() }
                })
                // the file's own boxes give the picture's size: the surface is there before the first frame
                if (known != null) attach(p, known.width, known.height)
                p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                p.prepare()
                p.play()
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        handler.post { runCatching { player?.release() }; runCatching { grab?.release() }; thread.quitSafely() }
        return out
    }

    private fun systemThumbnail(context: Context, uri: Uri, edge: Int): Bitmap? =
        if (android.os.Build.VERSION.SDK_INT < 29 || uri.scheme != "content") null
        else runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(edge, edge), null) }.getOrNull()

    fun facts(context: Context, uri: Uri): Facts {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val taken = date?.let { d ->
                listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { f ->
                    runCatching { java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time }.getOrNull()
                }
            }?.takeIf { it > 0 } ?: Mp4Info.read(context, uri)?.createdMs
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val match = location?.let { Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
            val lat = match?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 90 }
            val lon = match?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 180 }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            Facts(taken, lat, lon, rotation)
        } catch (e: Exception) {
            // Android's reader refuses the file: its own boxes still give when it was made
            Facts(Mp4Info.read(context, uri)?.createdMs, null, null, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** The identity of a big file without reading all of it: md5 over its size, its first and its last 4 MB. */
object PartialHash {

    private const val SLICE = 4L * 1024 * 1024

    fun of(context: Context, uri: Uri): String? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val digest = MessageDigest.getInstance("MD5")
            digest.update(size.toString().toByteArray())
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(pfd.fileDescriptor)).use { input ->
                val buffer = ByteArray(64 * 1024)
                var left = minOf(SLICE, size)
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    left -= n
                }
                if (size > 2 * SLICE) {
                    var skip = size - 2 * SLICE
                    while (skip > 0) { val s = input.skip(skip); if (s <= 0) break; skip -= s }
                    left = SLICE
                    while (left > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        left -= n
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }
}
