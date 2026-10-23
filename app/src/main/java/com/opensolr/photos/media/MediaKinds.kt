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
        val raw = retrieverFrame(context, uri) ?: systemThumbnail(context, uri, edge) ?: exoFrame(context, uri) ?: return null
        val longEdge = max(raw.width, raw.height)
        if (longEdge <= edge) return raw
        val scale = edge.toFloat() / longEdge
        val small = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postScale(scale, scale) }, true)
        if (small !== raw) raw.recycle()
        return small
    }

    private fun retrieverFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (durationMs in 1..1999) durationMs * 500L else 1_000_000L
            runCatching { retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
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
    private fun exoFrame(context: Context, uri: Uri): Bitmap? {
        val thread = android.os.HandlerThread("exo-frame").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Bitmap? = null
        var reader: android.media.ImageReader? = null
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        handler.post {
            try {
                val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).setLooper(thread.looper).build()
                player = p
                p.volume = 0f
                p.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        if (size.width <= 0 || size.height <= 0 || reader != null) return
                        val r = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.PixelFormat.RGBA_8888, 2)
                        reader = r
                        r.setOnImageAvailableListener({ ir ->
                            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                            try {
                                val plane = image.planes[0]
                                val padded = plane.rowStride / plane.pixelStride
                                val full = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
                                full.copyPixelsFromBuffer(plane.buffer)
                                out = Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { if (it !== full) full.recycle() }
                            } catch (e: Exception) {
                            } finally {
                                image.close()
                                latch.countDown()
                            }
                        }, handler)
                        p.setVideoSurface(r.surface)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { latch.countDown() }
                })
                p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                p.prepare()
                p.play()
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        handler.post { runCatching { player?.release() }; runCatching { reader?.close() }; thread.quitSafely() }
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
            }?.takeIf { it > 0 }
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val match = location?.let { Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
            val lat = match?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 90 }
            val lon = match?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 180 }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            Facts(taken, lat, lon, rotation)
        } catch (e: Exception) {
            Facts(null, null, null, 0)
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
