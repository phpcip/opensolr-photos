package com.opensolr.photos.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One step of an edit, in pixels of the image it is applied to. */
sealed interface EditOp {
    // steps that change the picture itself
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) : EditOp
    data class Rotate(val clockwise: Boolean) : EditOp
    data class Resize(val width: Int, val height: Int) : EditOp
    /** The marks drawn so far, set into the pixels before the picture is cropped, turned or resized. */
    class Bake(val marks: List<EditOp>) : EditOp

    // marks: drawn over the picture, movable until baked
    class Stroke(val points: FloatArray, val color: Int, val width: Float) : EditOp
    class Shape(val kind: ShapeKind, val points: FloatArray, val color: Int, val width: Float) : EditOp
    data class Label(val text: String, val x: Float, val y: Float, val color: Int, val size: Float) : EditOp

    enum class ShapeKind { LINE, ARROW, POLYGON, ELLIPSE }
}

/**
 * The photo being edited: the picture after its crop/turn/resize steps, and the marks drawn over it.
 * Every action is one state in the history (undo/redo); marks are only coordinates, so moving or undoing them costs nothing,
 * and the picture is rebuilt from the source only when an undo crosses a crop, turn or resize.
 */
class EditSession(private val source: Bitmap) {
    private class State(val steps: Int, val marks: List<EditOp>)

    private var base: Bitmap = PhotoEditor.mutableCopy(source)
    private var baseSteps = 0
    private val steps = ArrayList<EditOp>()
    private val history = arrayListOf(State(0, emptyList()))
    private var at = 0

    /** Grows every time the picture under the marks changes, so the screen redraws it only then. */
    var baseVersion = 0
        private set

    val width: Int get() = base.width
    val height: Int get() = base.height
    val canUndo: Boolean get() = at > 0
    val canRedo: Boolean get() = at < history.size - 1
    val changed: Boolean get() = at > 0
    val marks: List<EditOp> get() = history[at].marks

    fun addMark(mark: EditOp) = push(State(history[at].steps, marks + mark))

    fun moveMark(index: Int, dx: Float, dy: Float) {
        val list = marks
        if (index !in list.indices) return
        push(State(history[at].steps, list.toMutableList().also { it[index] = PhotoEditor.translate(list[index], dx, dy) }))
    }

    /** A crop, turn or resize: the marks so far are set into the pixels first, then the step is applied. */
    fun transform(op: EditOp) {
        val cur = history[at]
        while (steps.size > cur.steps) steps.removeAt(steps.size - 1)
        reach(cur.steps)
        if (cur.marks.isNotEmpty()) {
            val bake = EditOp.Bake(cur.marks)
            steps += bake
            applyStep(bake)
        }
        steps += op
        applyStep(op)
        push(State(steps.size, emptyList()))
    }

    fun undo() {
        if (!canUndo) return
        at--
        reach(history[at].steps)
    }

    fun redo() {
        if (!canRedo) return
        at++
        reach(history[at].steps)
    }

    private fun push(state: State) {
        while (history.size > at + 1) history.removeAt(history.size - 1)
        while (steps.size > state.steps) steps.removeAt(steps.size - 1)
        history += state
        at++
    }

    // the picture after exactly [count] steps: forward from where it is, or again from the source
    private fun reach(count: Int) {
        if (count == baseSteps) return
        if (count < baseSteps) {
            base.recycle()
            base = PhotoEditor.mutableCopy(source)
            baseSteps = 0
        }
        while (baseSteps < count) applyStep(steps[baseSteps])
    }

    private fun applyStep(op: EditOp) {
        val next = PhotoEditor.apply(base, op)
        if (next !== base) base.recycle()
        base = next
        baseSteps++
        baseVersion++
    }

    /** The picture with the marks set into it, as it is saved; the caller recycles it. */
    fun render(): Bitmap {
        val out = PhotoEditor.mutableCopy(base)
        val canvas = Canvas(out)
        marks.forEach { PhotoEditor.drawMark(canvas, it) }
        return out
    }

    /** Bytes the image takes once saved as [format], at [width] x [height] px: the real encoding, counted, nothing written. */
    fun savedBytes(format: Bitmap.CompressFormat, width: Int, height: Int): Long {
        val full = render()
        val sized = if (width == full.width && height == full.height) full else PhotoEditor.apply(full, EditOp.Resize(width, height))
        try {
            return PhotoEditor.encodedBytes(sized, format)
        } finally {
            if (sized !== full) sized.recycle()
            full.recycle()
        }
    }

    /** The picture without the marks, fitted in [edge] px, for the screen. */
    fun preview(edge: Int): Bitmap = PhotoEditor.fit(base, edge)

    fun release() {
        base.recycle()
        source.recycle()
    }
}

object PhotoEditor {

    /** The photo upright and mutable, as big as this phone's memory allows; [originalWidth] x [originalHeight] is the file's own size. */
    class Loaded(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

    fun load(context: Context, uri: Uri): Loaded? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // a bounds-only decode returns no bitmap by design: only the stream missing is a failure
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = PhotoReader.openExif(context, uri)?.rotationDegrees ?: 0
        val budget = pixelBudget()
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > budget) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val upright = if (rotation % 360 == 0) mutableCopyIfNeeded(decoded) else turn(decoded, rotation).also { decoded.recycle() }
        val sideways = rotation % 180 != 0
        return Loaded(
            upright,
            if (sideways) bounds.outHeight else bounds.outWidth,
            if (sideways) bounds.outWidth else bounds.outHeight,
        )
    }

    /** Pixels an edit can hold: the source, the image and one step in between must fit in this app's memory. */
    fun pixelBudget(): Long = (Runtime.getRuntime().maxMemory() / BYTES_PER_PIXEL / WORKING_COPIES).coerceAtMost(MAX_PIXELS)

    fun apply(bitmap: Bitmap, op: EditOp): Bitmap = when (op) {
        is EditOp.Rotate -> turn(bitmap, if (op.clockwise) 90 else 270)
        is EditOp.Crop -> {
            val l = op.left.coerceIn(0, bitmap.width - 1)
            val t = op.top.coerceIn(0, bitmap.height - 1)
            val r = op.right.coerceIn(l + 1, bitmap.width)
            val b = op.bottom.coerceIn(t + 1, bitmap.height)
            val out = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bitmap, Rect(l, t, r, b), Rect(0, 0, out.width, out.height), null)
            out
        }
        is EditOp.Resize -> scale(bitmap, op.width, op.height)
        is EditOp.Bake -> {
            val canvas = Canvas(bitmap)
            op.marks.forEach { drawMark(canvas, it) }
            bitmap
        }
        is EditOp.Stroke, is EditOp.Shape, is EditOp.Label -> {
            drawMark(Canvas(bitmap), op)
            bitmap
        }
    }

    /** One mark drawn on [canvas], in the canvas's own pixels: the same call draws it on screen and into the saved photo. */
    fun drawMark(canvas: Canvas, op: EditOp) {
        when (op) {
            is EditOp.Stroke -> {
                val paint = pen(op.color, op.width)
                if (op.points.size <= 2) canvas.drawPoint(op.points[0], op.points[1], paint)
                else canvas.drawPath(strokePath(op.points), paint)
            }
            is EditOp.Shape -> {
                val paint = pen(op.color, op.width)
                val p = op.points
                when (op.kind) {
                    EditOp.ShapeKind.LINE -> canvas.drawLine(p[0], p[1], p[2], p[3], paint)
                    EditOp.ShapeKind.ARROW -> canvas.drawPath(arrowPath(p[0], p[1], p[2], p[3], op.width), paint)
                    EditOp.ShapeKind.POLYGON -> canvas.drawPath(Path().apply {
                        moveTo(p[0], p[1])
                        var i = 2
                        while (i + 1 < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
                        close()
                    }, paint)
                    EditOp.ShapeKind.ELLIPSE -> {
                        canvas.save()
                        canvas.rotate(p[4], p[0], p[1])
                        canvas.drawOval(p[0] - p[2], p[1] - p[3], p[0] + p[2], p[1] + p[3], paint)
                        canvas.restore()
                    }
                }
            }
            is EditOp.Label -> {
                val layout = labelLayout(op.text, op.color, op.size)
                canvas.save()
                canvas.translate(op.x - layout.width / 2f, op.y - layout.height / 2f)
                layout.draw(canvas)
                canvas.restore()
            }
            else -> Unit
        }
    }

    private fun pen(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Shaft from the tail to the tip and two barbs back from the tip, sized to the line and its thickness. */
    private fun arrowPath(x0: Float, y0: Float, x1: Float, y1: Float, width: Float): Path {
        val len = kotlin.math.hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
        val head = (len * 0.3f).coerceAtMost(max(width * 5f, len * 0.15f))
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val c = kotlin.math.cos(ARROW_BARB)
        val sn = kotlin.math.sin(ARROW_BARB)
        return Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
            moveTo(x1 - head * (ux * c - uy * sn), y1 - head * (uy * c + ux * sn))
            lineTo(x1, y1)
            lineTo(x1 - head * (ux * c + uy * sn), y1 - head * (uy * c - ux * sn))
        }
    }

    /** [op] moved by [dx], [dy] image pixels. */
    fun translate(op: EditOp, dx: Float, dy: Float): EditOp {
        fun shift(points: FloatArray) = FloatArray(points.size) { i -> points[i] + if (i % 2 == 0) dx else dy }
        return when (op) {
            is EditOp.Stroke -> EditOp.Stroke(shift(op.points), op.color, op.width)
            is EditOp.Shape -> if (op.kind == EditOp.ShapeKind.ELLIPSE) {
                EditOp.Shape(op.kind, op.points.copyOf().also { it[0] += dx; it[1] += dy }, op.color, op.width)
            } else {
                EditOp.Shape(op.kind, shift(op.points), op.color, op.width)
            }
            is EditOp.Label -> op.copy(x = op.x + dx, y = op.y + dy)
            else -> op
        }
    }

    /** The topmost mark within [reach] image pixels of ([x], [y]), or -1. */
    fun hitMark(marks: List<EditOp>, x: Float, y: Float, reach: Float): Int {
        for (i in marks.indices.reversed()) {
            val m = marks[i]
            val hit = when (m) {
                is EditOp.Label -> {
                    val layout = labelLayout(m.text, m.color, m.size)
                    abs(x - m.x) <= layout.width / 2f + reach && abs(y - m.y) <= layout.height / 2f + reach
                }
                is EditOp.Stroke -> nearPolyline(m.points, false, x, y, reach + m.width / 2f)
                is EditOp.Shape -> when (m.kind) {
                    EditOp.ShapeKind.ELLIPSE -> nearPolyline(ellipsePoints(m.points), true, x, y, reach + m.width / 2f)
                    EditOp.ShapeKind.POLYGON -> nearPolyline(m.points, true, x, y, reach + m.width / 2f)
                    else -> nearPolyline(m.points, false, x, y, reach + m.width / 2f)
                }
                else -> false
            }
            if (hit) return i
        }
        return -1
    }

    private fun ellipsePoints(p: FloatArray): FloatArray {
        val a = Math.toRadians(p[4].toDouble())
        val out = FloatArray(ELLIPSE_HIT_POINTS * 2)
        for (k in 0 until ELLIPSE_HIT_POINTS) {
            val t = 2 * Math.PI * k / ELLIPSE_HIT_POINTS
            val ex = p[2] * kotlin.math.cos(t)
            val ey = p[3] * kotlin.math.sin(t)
            out[k * 2] = (p[0] + ex * kotlin.math.cos(a) - ey * kotlin.math.sin(a)).toFloat()
            out[k * 2 + 1] = (p[1] + ex * kotlin.math.sin(a) + ey * kotlin.math.cos(a)).toFloat()
        }
        return out
    }

    private fun nearPolyline(p: FloatArray, closed: Boolean, x: Float, y: Float, reach: Float): Boolean {
        if (p.size == 2) return kotlin.math.hypot(x - p[0], y - p[1]) <= reach
        val n = p.size / 2
        val last = if (closed) n else n - 1
        for (k in 0 until last) {
            val ax = p[k * 2]
            val ay = p[k * 2 + 1]
            val bx = p[((k + 1) % n) * 2]
            val by = p[((k + 1) % n) * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
            if (kotlin.math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= reach) return true
        }
        return false
    }

    /** A smooth line through the finger's points, the same on screen and in the saved photo. */
    fun strokePath(points: FloatArray): Path {
        val path = Path()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) {
            val px = points[i - 2]
            val py = points[i - 1]
            val x = points[i]
            val y = points[i + 1]
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            i += 2
        }
        path.lineTo(points[points.size - 2], points[points.size - 1])
        return path
    }

    /** The text laid out on its lines, centred, bold, at [size] px. */
    fun labelLayout(text: String, color: Int, size: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val width = text.split('\n').maxOf { paint.measureText(it) }.let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    fun mutableCopy(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    /** [bitmap] fitted in [edge] px on its long side, a new bitmap. */
    fun fit(bitmap: Bitmap, edge: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= edge) return mutableCopy(bitmap)
        val s = edge.toFloat() / long
        return scale(bitmap, max(1, (bitmap.width * s).roundToInt()), max(1, (bitmap.height * s).roundToInt()))
    }

    private fun mutableCopyIfNeeded(bitmap: Bitmap): Bitmap =
        if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) bitmap else mutableCopy(bitmap).also { bitmap.recycle() }

    private fun turn(bitmap: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        val sideways = d % 180 != 0
        val out = Bitmap.createBitmap(if (sideways) bitmap.height else bitmap.width, if (sideways) bitmap.width else bitmap.height, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postRotate(d.toFloat())
            when (d) {
                90 -> postTranslate(bitmap.height.toFloat(), 0f)
                180 -> postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
                270 -> postTranslate(0f, bitmap.width.toFloat())
            }
        }
        Canvas(out).drawBitmap(bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // big reductions in halves first, so the result is smooth and not jagged
    private fun scale(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        var cur = bitmap
        while (cur.width / 2 >= width && cur.height / 2 >= height) {
            val half = draw(cur, cur.width / 2, cur.height / 2)
            if (cur !== bitmap) cur.recycle()
            cur = half
        }
        if (cur.width == width && cur.height == height) return if (cur === bitmap) mutableCopy(cur) else cur
        val out = draw(cur, width, height)
        if (cur !== bitmap) cur.recycle()
        return out
    }

    private fun draw(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The format the edited photo is written in when it replaces [mime]; null when that kind of file cannot be written back. */
    fun formatFor(mime: String): Bitmap.CompressFormat? = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> Bitmap.CompressFormat.JPEG
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> null
    }

    fun mimeOf(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.JPEG -> "image/jpeg"
        else -> "image/webp"
    }

    private fun extensionOf(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.JPEG -> "jpg"
        else -> "webp"
    }

    fun encodedBytes(bitmap: Bitmap, format: Bitmap.CompressFormat): Long {
        val counter = object : java.io.OutputStream() {
            var count = 0L
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        if (!bitmap.compress(format, QUALITY, counter)) throw java.io.IOException("encode failed")
        return counter.count
    }

    /** The edited image encoded into a file in the app's cache, with the original's date, camera and place carried over. */
    fun encode(context: Context, bitmap: Bitmap, format: Bitmap.CompressFormat, original: Uri): File {
        val dir = File(context.cacheDir, EDIT_DIR).apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "edited." + extensionOf(format))
        file.outputStream().buffered().use { out ->
            if (!bitmap.compress(format, QUALITY, out)) throw java.io.IOException("encode failed")
        }
        val exif = PhotoReader.openExif(context, original)
        runCatching {
            val copy = ExifInterface(file.absolutePath)
            if (exif != null) for (tag in PhotoReader.CARRIED_EXIF) exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) }
            copy.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            copy.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
            copy.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
            copy.saveAttributes()
        }
        return file
    }

    /** [encoded] written over the original's own bytes; the original keeps its name, folder and place in the gallery. */
    fun overwrite(context: Context, original: Uri, encoded: File) {
        val out = context.contentResolver.openOutputStream(original, "wt") ?: throw java.io.IOException("no output stream")
        out.use { stream -> encoded.inputStream().use { it.copyTo(stream, BUFFER) } }
        rescan(context, original)
    }

    /** [encoded] as a new photo in the original's folder (or Pictures, where that folder takes no photos); its folder and name. */
    fun saveCopy(context: Context, original: Uri, originalName: String, originalPath: String, encoded: File, format: Bitmap.CompressFormat): String {
        val name = originalName.substringBeforeLast('.').ifBlank { "photo" } + "-edited." + extensionOf(format)
        val mime = mimeOf(format)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(originalPath).parentFile ?: throw java.io.IOException("no folder")
            var target = File(dir, name)
            var n = 1
            while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " (" + n++ + ")." + extensionOf(format))
            encoded.inputStream().use { input -> target.outputStream().use { input.copyTo(it, BUFFER) } }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            return dir.name + "/" + target.name
        }
        val resolver = context.contentResolver
        var folder = android.os.Environment.DIRECTORY_PICTURES + "/"
        var taken = 0L
        resolver.query(original, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { folder = it }
                taken = c.getLong(1)
            }
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun values(path: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            // the copy sorts next to the original, by the moment the photo was taken
            if (taken > 0) put(MediaStore.Images.Media.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = try {
            resolver.insert(collection, values(folder))
        } catch (e: IllegalArgumentException) {
            null
        } ?: run {
            folder = android.os.Environment.DIRECTORY_PICTURES + "/"
            resolver.insert(collection, values(folder))
        } ?: throw java.io.IOException("insert failed")
        try {
            val out = resolver.openOutputStream(created, "w") ?: throw java.io.IOException("no output stream")
            out.use { stream -> encoded.inputStream().use { it.copyTo(stream, BUFFER) } }
            resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(created, null, null) }
            throw e
        }
        var shown = name
        resolver.query(created, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { shown = it }
        }
        return folder.trimEnd('/') + "/" + shown
    }

    private fun rescan(context: Context, uri: Uri) {
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    fun clearCache(context: Context) {
        File(context.cacheDir, EDIT_DIR).deleteRecursively()
    }

    private const val EDIT_DIR = "edit"
    private const val ARROW_BARB = 0.5f
    private const val ELLIPSE_HIT_POINTS = 48
    private const val QUALITY = 95
    private const val BUFFER = 64 * 1024
    private const val BYTES_PER_PIXEL = 4L
    private const val WORKING_COPIES = 4L
    private const val MAX_PIXELS = 64_000_000L
}
