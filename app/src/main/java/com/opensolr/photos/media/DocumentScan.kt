package com.opensolr.photos.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** A page photographed by the scanner, straightened to its corners and saved as a new photo in a folder the app syncs. */
object DocumentScan {

    class NoFolderException : Exception()

    /** Where the camera writes the photo before it is straightened; emptied when the scanner closes. */
    fun captureDir(context: Context): File = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }

    fun clear(context: Context) {
        File(context.cacheDir, CAPTURE_DIR).deleteRecursively()
    }

    /**
     * The folders a scan may go to, in the order they are tried: a synced DCIM folder first (DCIM itself before the
     * folders in it), then every other synced folder alphabetically.
     */
    fun targets(folders: Set<String>): List<String> {
        val keys = folders.map { MediaScanner.folderKey(it) }.distinct()
        if ("" in keys) return listOf(DCIM)
        val sorted = keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val (dcim, rest) = sorted.partition { it.startsWith(DCIM, ignoreCase = true) }
        return dcim + rest
    }

    /** The corners of the page in [upright] (the photo as it is seen), as fractions of it; [seen] the live outline; null when no page shows. */
    fun detect(upright: Bitmap, seen: FloatArray?): FloatArray? {
        val step = DocumentEdges.step(upright.width, upright.height, DETECT_EDGE)
        val small = if (step == 1) upright else Bitmap.createScaledBitmap(upright, upright.width / step, upright.height / step, true)
        val found = DocumentEdges.find(DocumentEdges.grey(small), small.width, small.height, seen)
        if (small !== upright) small.recycle()
        return found
    }

    /**
     * The page inside [corners] (fractions of the photo seen upright, top-left first, clockwise) straightened to a
     * rectangle at the photo's own resolution and saved in the first of [folders] Android accepts a photo in.
     * Returns the folder and name it got.
     */
    fun save(context: Context, capture: File, corners: FloatArray, folders: Set<String>): String {
        val exif = ExifInterface(capture.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL).takeIf { it in 1..8 } ?: 1
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(capture.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("unreadable capture")

        // the photo and the page both have to fit in memory; only a sensor too large for this phone is read smaller
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) * BYTES_PER_PIXEL * WORKING_COPIES > free * MEMORY_SHARE) sample *= 2
        val source = BitmapFactory.decodeFile(capture.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw java.io.IOException("unreadable capture")

        val turned = orientation in 5..8
        val shownW = (if (turned) source.height else source.width).toFloat()
        val shownH = (if (turned) source.width else source.height).toFloat()
        val src = FloatArray(8)
        val seen = FloatArray(8)
        for (i in 0 until 4) {
            val s = corners[i * 2].coerceIn(0f, 1f)
            val t = corners[i * 2 + 1].coerceIn(0f, 1f)
            seen[i * 2] = s * shownW
            seen[i * 2 + 1] = t * shownH
            val (u, v) = stored(orientation, s, t)
            src[i * 2] = u * source.width
            src[i * 2 + 1] = v * source.height
        }
        val outW = max(dist(seen, 0, 1), dist(seen, 3, 2)).roundToInt().coerceAtLeast(1)
        val outH = max(dist(seen, 0, 3), dist(seen, 1, 2)).roundToInt().coerceAtLeast(1)
        val page = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            page.recycle()
            source.recycle()
            throw java.io.IOException("corners do not make a page")
        }
        Canvas(page).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        source.recycle()

        val encoded = File(captureDir(context), "page-" + System.nanoTime() + ".jpg")
        try {
            encoded.outputStream().buffered(BUFFER).use { page.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            page.recycle()
            val now = Date()
            ExifInterface(encoded.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat(EXIF_DATE, Locale.US).format(now))
                setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat(EXIF_DATE, Locale.US).format(now))
                val offset = SimpleDateFormat("XXX", Locale.US).format(now)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
                exif.getAttribute(ExifInterface.TAG_MAKE)?.let { setAttribute(ExifInterface.TAG_MAKE, it) }
                exif.getAttribute(ExifInterface.TAG_MODEL)?.let { setAttribute(ExifInterface.TAG_MODEL, it) }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            val name = "Scan_" + SimpleDateFormat(NAME_DATE, Locale.US).format(now) + ".jpg"
            return store(context, encoded, name, now.time, targets(folders))
        } finally {
            if (!page.isRecycled) page.recycle()
            encoded.delete()
        }
    }

    // the first folder Android takes a photo in; photos may only go to DCIM and Pictures from Android 10 on
    private fun store(context: Context, file: File, name: String, taken: Long, targets: List<String>): String {
        if (targets.isEmpty()) throw NoFolderException()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = Environment.getExternalStorageDirectory()
            for (folder in targets) {
                val dir = File(root, folder)
                if (!dir.isDirectory && !dir.mkdirs()) continue
                var target = File(dir, name)
                var n = 1
                while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " (" + n++ + ").jpg")
                try {
                    file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, BUFFER) } }
                } catch (e: java.io.IOException) {
                    target.delete()
                    continue
                }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME), null)
                return folder + target.name
            }
            throw NoFolderException()
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (folder in targets) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val created = try {
                resolver.insert(collection, values)
            } catch (e: IllegalArgumentException) {
                null
            } ?: continue
            try {
                val out = resolver.openOutputStream(created, "w") ?: throw java.io.IOException("no output stream")
                out.use { stream -> file.inputStream().use { it.copyTo(stream, BUFFER) } }
                resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(created, null, null) }
                throw e
            }
            var shown = name
            resolver.query(created, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { shown = it }
            }
            return folder + shown
        }
        throw NoFolderException()
    }

    // a point of the photo seen upright, as fractions, in the photo as it is stored (EXIF orientation 1 to 8)
    private fun stored(orientation: Int, s: Float, t: Float): Pair<Float, Float> = when (orientation) {
        2 -> (1f - s) to t
        3 -> (1f - s) to (1f - t)
        4 -> s to (1f - t)
        5 -> t to s
        6 -> t to (1f - s)
        7 -> (1f - t) to (1f - s)
        8 -> (1f - t) to s
        else -> s to t
    }

    private fun dist(q: FloatArray, a: Int, b: Int): Float = hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1])

    private const val CAPTURE_DIR = "scan"
    private const val DCIM = "DCIM/"
    private const val MIME = "image/jpeg"
    private const val DETECT_EDGE = 480
    private const val QUALITY = 95
    private const val BUFFER = 64 * 1024
    private const val BYTES_PER_PIXEL = 4L
    private const val WORKING_COPIES = 2L
    private const val MEMORY_SHARE = 0.7
    private const val EXIF_DATE = "yyyy:MM:dd HH:mm:ss"
    private const val NAME_DATE = "yyyyMMdd_HHmmss"
}
