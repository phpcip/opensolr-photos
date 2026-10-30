package com.opensolr.photos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Takes things out of a photo on the phone, with nothing sent anywhere: MI-GAN (MIT) paints the marked
 * area from what is around it, PP-OCR's text finder (Apache 2.0) marks the text in the photo.
 * Both as TensorFlow Lite models run by LiteRT; loaded on first use, closed with [close].
 */
class MagicEraser(context: Context) {

    private val app = context.applicationContext
    private var painter: Interpreter? = null
    private var finder: Interpreter? = null

    /**
     * The text in [photo], as boxes in its pixels, grown a little past the letters so erasing them leaves no
     * edges behind.
     */
    fun findText(photo: Bitmap, reading: Boolean = false): List<RectF> {
        val net = finder ?: Interpreter(model(app, "eraser/text.tflite"), options(FIND_THREADS)).also { finder = it }
        val k = FIND_SIZE.toFloat() / max(photo.width, photo.height)
        val w = max(1, (photo.width * k).roundToInt())
        val h = max(1, (photo.height * k).roundToInt())
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        // the frame is FIND_SIZE square, the photo top-left, the rest left at 0; channels B, G, R as PP-OCR was trained
        val input = direct(FIND_SIZE * FIND_SIZE * 3)
        val fb = input.asFloatBuffer()
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            val at = (y * FIND_SIZE + x) * 3
            fb.put(at, (((c and 0xFF) / 255f) - MEAN_B) / STD_B)
            fb.put(at + 1, (((c shr 8 and 0xFF) / 255f) - MEAN_G) / STD_G)
            fb.put(at + 2, (((c shr 16 and 0xFF) / 255f) - MEAN_R) / STD_R)
        }
        val output = direct(FIND_SIZE * FIND_SIZE)
        net.run(input, output)
        // the run leaves the buffer's position at its end: read it from the start
        output.rewind()
        val prob = output.asFloatBuffer()
        val boxes = ArrayList<RectF>()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || prob.get((start / w) * FIND_SIZE + start % w) < TEXT_ON) continue
            // one region of text: its box, its size and how sure the finder is of it
            var top = 0
            stack[top++] = start
            seen[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            var sum = 0f
            var count = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                sum += prob.get(y * FIND_SIZE + x)
                count++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && on(i - 1, w, prob, seen)) stack[top++] = i - 1
                if (x < w - 1 && on(i + 1, w, prob, seen)) stack[top++] = i + 1
                if (y > 0 && on(i - w, w, prob, seen)) stack[top++] = i - w
                if (y < h - 1 && on(i + w, w, prob, seen)) stack[top++] = i + w
            }
            if (count < TEXT_MIN_PIXELS || sum / count < TEXT_SURE) continue
            // the finder marks the middle of the letters: the box is grown back out as PP-OCR does (area x 1.5 / perimeter)
            val bw = (x1 - x0 + 1).toFloat()
            val bh = (y1 - y0 + 1).toFloat()
            // plus a share of the line's height, for accents, commas under letters and punctuation at the ends
            // for reading, only PP-OCR's own growth: the extra margin would reach into the next line
            val grow = if (reading) bw * bh * UNCLIP / (2f * (bw + bh)) else bw * bh * UNCLIP / (2f * (bw + bh)) + TEXT_MARGIN + min(bw, bh) * TEXT_EXTRA
            boxes += RectF(
                ((x0 - grow) / k).coerceAtLeast(0f),
                ((y0 - grow) / k).coerceAtLeast(0f),
                ((x1 + 1 + grow) / k).coerceAtMost(photo.width.toFloat()),
                ((y1 + 1 + grow) / k).coerceAtMost(photo.height.toFloat()),
            )
        }
        return boxes
    }

    // a text pixel not reached yet: marked as reached
    private fun on(i: Int, w: Int, prob: java.nio.FloatBuffer, seen: BooleanArray): Boolean {
        if (seen[i] || prob.get((i / w) * FIND_SIZE + i % w) < TEXT_ON) return false
        seen[i] = true
        return true
    }

    /**
     * The letters inside [boxes] marked in [mask]: the text's own colour is told from the box's background
     * (two colour groups, the text being the one rare on the box's edge), and only pixels of that colour, a little
     * grown, are marked. A small hole fills far better than the whole box. A box whose text colour is all over
     * its edge (text on a busy picture) is marked whole.
     */
    fun markText(photo: Bitmap, boxes: List<RectF>, mask: Bitmap) {
        val canvas = Canvas(mask)
        for (box in boxes) {
            val x0 = box.left.roundToInt().coerceIn(0, photo.width - 1)
            val y0 = box.top.roundToInt().coerceIn(0, photo.height - 1)
            val x1 = box.right.roundToInt().coerceIn(x0 + 1, photo.width)
            val y1 = box.bottom.roundToInt().coerceIn(y0 + 1, photo.height)
            val w = x1 - x0
            val h = y1 - y0
            val px = IntArray(w * h)
            photo.getPixels(px, 0, w, x0, y0, w, h)
            val edge = ArrayList<Int>(2 * (w + h))
            for (x in 0 until w) { edge += px[x]; edge += px[(h - 1) * w + x] }
            for (y in 0 until h) { edge += px[y * w]; edge += px[y * w + w - 1] }
            fun channel(list: List<Int>, shift: Int): Float { val v = list.map { (it shr shift) and 0xFF }.sorted(); return v[v.size / 2].toFloat() }
            val bg = floatArrayOf(channel(edge, 16), channel(edge, 8), channel(edge, 0))
            fun dist(c: Int, to: FloatArray): Float = max(abs(((c shr 16) and 0xFF) - to[0]), max(abs(((c shr 8) and 0xFF) - to[1]), abs((c and 0xFF) - to[2])))
            val text = px.maxByOrNull { dist(it, bg) }?.let { floatArrayOf(((it shr 16) and 0xFF).toFloat(), ((it shr 8) and 0xFF).toFloat(), (it and 0xFF).toFloat()) } ?: continue
            repeat(TEXT_ROUNDS) {
                val st = FloatArray(3); val sb = FloatArray(3)
                var nt = 0; var nb = 0
                for (c in px) {
                    val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
                    if (dist(c, text) < dist(c, bg)) { st[0] += r; st[1] += g; st[2] += b; nt++ } else { sb[0] += r; sb[1] += g; sb[2] += b; nb++ }
                }
                if (nt > 0) for (i in 0..2) text[i] = st[i] / nt
                if (nb > 0) for (i in 0..2) bg[i] = sb[i] / nb
            }
            val near = max(TEXT_NEAR_MIN, max(abs(text[0] - bg[0]), max(abs(text[1] - bg[1]), abs(text[2] - bg[2]))) * TEXT_NEAR_SHARE)
            val onEdge = edge.count { dist(it, text) < near }.toFloat() / edge.size
            val out = IntArray(w * h)
            if (onEdge > TEXT_EDGE_SHARE) {
                out.fill(0xFF000000.toInt())
            } else {
                val on = BooleanArray(w * h) { dist(px[it], text) < near }
                val grown = grow(on, w, h, max(2, (h * TEXT_GROW).roundToInt()))
                for (i in out.indices) if (grown[i]) out[i] = 0xFF000000.toInt()
            }
            val marks = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(marks, x0.toFloat(), y0.toFloat(), null)
            marks.recycle()
        }
    }

    // a square of [r] pixels around every marked pixel marked too: rows first, then columns, each in one pass
    private fun grow(on: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val rows = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -r - 1
            for (x in 0 until w) if (on[y * w + x]) last = x else if (x - last <= r) rows[y * w + x] = true
            last = w + r + 1
            for (x in w - 1 downTo 0) {
                if (on[y * w + x]) { last = x; rows[y * w + x] = true } else if (last - x <= r) rows[y * w + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -r - 1
            for (y in 0 until h) if (rows[y * w + x]) { last = y; out[y * w + x] = true } else if (y - last <= r) out[y * w + x] = true
            last = h + r + 1
            for (y in h - 1 downTo 0) if (rows[y * w + x]) last = y else if (last - y <= r) out[y * w + x] = true
        }
        return out
    }

    /**
     * [photo] with the parts set in [mask] (same size, any alpha above 0 = take out) painted over from what is
     * around them. Each separate part is painted on its own, a long part piece by piece, each piece with room
     * around it and seeing the pieces already painted, so the model always fills a small hole at full sharpness.
     * Returns the changed pixels as one patch over the parts' bounds, or null when the mask is empty.
     */
    fun erase(photo: Bitmap, mask: Bitmap): EditOp.Patch? {
        val parts = areas(mask)
        if (parts.isEmpty()) return null
        val bounds = Rect(parts[0])
        parts.forEach { bounds.union(it) }
        val work = photo.copy(Bitmap.Config.ARGB_8888, true)
        try {
            for (part in parts) for (piece in pieces(part)) paint(work, mask, piece)
            return EditOp.Patch(bounds.left, bounds.top, Bitmap.createBitmap(work, bounds.left, bounds.top, bounds.width(), bounds.height()))
        } finally {
            work.recycle()
        }
    }

    // the mask's separate parts, found on a small copy, as boxes in the photo's pixels
    private fun areas(mask: Bitmap): List<Rect> {
        val k = min(1f, AREA_GRID.toFloat() / max(mask.width, mask.height))
        val w = max(1, (mask.width * k).roundToInt())
        val h = max(1, (mask.height * k).roundToInt())
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(small).drawBitmap(mask, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val bytes = ByteArray(w * h)
        val buf = ByteBuffer.allocate(small.rowBytes * h)
        small.copyPixelsToBuffer(buf)
        val row = small.rowBytes
        small.recycle()
        for (y in 0 until h) for (x in 0 until w) bytes[y * w + x] = buf.get(y * row + x)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val found = ArrayList<Rect>()
        for (start in 0 until w * h) {
            if (seen[start] || bytes[start].toInt() == 0) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && !seen[i - 1] && bytes[i - 1].toInt() != 0) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < w - 1 && !seen[i + 1] && bytes[i + 1].toInt() != 0) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && !seen[i - w] && bytes[i - w].toInt() != 0) { seen[i - w] = true; stack[top++] = i - w }
                if (y < h - 1 && !seen[i + w] && bytes[i + w].toInt() != 0) { seen[i + w] = true; stack[top++] = i + w }
            }
            found += Rect(
                ((x0 - 1) / k).toInt().coerceAtLeast(0), ((y0 - 1) / k).toInt().coerceAtLeast(0),
                ((x1 + 2) / k).roundToInt().coerceAtMost(mask.width), ((y1 + 2) / k).roundToInt().coerceAtMost(mask.height),
            )
        }
        return found
    }

    // a long part cut along its length into pieces about as long as twice its thickness
    private fun pieces(part: Rect): List<Rect> {
        val wide = part.width() >= part.height()
        val long = if (wide) part.width() else part.height()
        val thick = max(1, if (wide) part.height() else part.width())
        val step = max(PIECE_MIN, thick * PIECE_RATIO)
        val count = max(1, (long + step - 1) / step)
        return (0 until count).map { i ->
            val a = (if (wide) part.left else part.top) + long * i / count
            val b = (if (wide) part.left else part.top) + long * (i + 1) / count
            if (wide) Rect(a, part.top, b, part.bottom) else Rect(part.left, a, part.right, b)
        }
    }

    // the part of the photo the model sees for one piece: the piece with as much again around it, square where it fits
    private fun window(piece: Rect, width: Int, height: Int): Rect {
        val side = max(PAINT_SIZE / 2, (max(piece.width(), piece.height()) * CONTEXT).roundToInt())
        val sw = min(side, width)
        val sh = min(side, height)
        val l = (piece.centerX() - sw / 2).coerceIn(0, width - sw)
        val t = (piece.centerY() - sh / 2).coerceIn(0, height - sh)
        return Rect(l, t, l + sw, t + sh)
    }

    // one piece painted into [work]: the model sees the window with every marked pixel in it as a hole, only the piece is kept
    private fun paint(work: Bitmap, mask: Bitmap, piece: Rect) {
        val win = window(piece, work.width, work.height)
        val size = PAINT_SIZE
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val img = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(img).drawBitmap(work, win, Rect(0, 0, size, size), filter)
        val holes = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Canvas(holes).drawBitmap(mask, win, Rect(0, 0, size, size), filter)
        val px = IntArray(size * size)
        img.getPixels(px, 0, size, 0, 0, size, size)
        img.recycle()
        val hb = ByteBuffer.allocate(holes.rowBytes * size)
        holes.copyPixelsToBuffer(hb)
        val hrow = holes.rowBytes
        holes.recycle()
        // MI-GAN's input: the known part (1) and the hole (0) as -0.5..0.5, then the photo in -1..1 with the hole blacked out
        val input = direct(size * size * 4)
        val fb = input.asFloatBuffer()
        for (y in 0 until size) for (x in 0 until size) {
            val keep = if (hb.get(y * hrow + x).toInt() != 0) 0f else 1f
            val c = px[y * size + x]
            val at = (y * size + x) * 4
            fb.put(at, keep - 0.5f)
            fb.put(at + 1, ((c shr 16 and 0xFF) * 2f / 255f - 1f) * keep)
            fb.put(at + 2, ((c shr 8 and 0xFF) * 2f / 255f - 1f) * keep)
            fb.put(at + 3, ((c and 0xFF) * 2f / 255f - 1f) * keep)
        }
        val output = direct(size * size * 3)
        val net = painter ?: Interpreter(model(app, "eraser/migan.tflite"), options(PAINT_THREADS)).also { painter = it }
        net.run(input, output)
        // the run leaves the buffer's position at its end: read it from the start
        output.rewind()
        val ob = output.asFloatBuffer()
        for (i in 0 until size * size) {
            fun ch(v: Float) = ((v * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(ob.get(i * 3)) shl 16) or (ch(ob.get(i * 3 + 1)) shl 8) or ch(ob.get(i * 3 + 2))
        }
        val painted = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        val back = Bitmap.createScaledBitmap(painted, win.width(), win.height(), true)
        painted.recycle()
        // back at the photo's own size, only inside the piece and the mask: every other pixel stays as it is
        val w = piece.width()
        val h = piece.height()
        val made = IntArray(w * h)
        back.getPixels(made, 0, w, piece.left - win.left, piece.top - win.top, w, h)
        back.recycle()
        val now = IntArray(w * h)
        work.getPixels(now, 0, w, piece.left, piece.top, w, h)
        val m = Bitmap.createBitmap(mask, piece.left, piece.top, w, h)
        val mb = ByteBuffer.allocate(m.rowBytes * h)
        m.copyPixelsToBuffer(mb)
        val mrow = m.rowBytes
        if (m !== mask) m.recycle()
        for (y in 0 until h) for (x in 0 until w) {
            val a = mb.get(y * mrow + x).toInt() and 0xFF
            if (a == 0) continue
            val at = y * w + x
            val n = made[at]
            if (a == 0xFF) {
                now[at] = n
            } else {
                // the mask's soft edge blends the painted pixels into the photo
                val o = now[at]
                val r = (((o shr 16) and 0xFF) * (255 - a) + ((n shr 16) and 0xFF) * a) / 255
                val g = (((o shr 8) and 0xFF) * (255 - a) + ((n shr 8) and 0xFF) * a) / 255
                val b = ((o and 0xFF) * (255 - a) + (n and 0xFF) * a) / 255
                now[at] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        work.setPixels(now, 0, w, piece.left, piece.top, w, h)
    }

    fun close() {
        painter?.close()
        painter = null
        finder?.close()
        finder = null
    }

    companion object {
        private const val PAINT_SIZE = 512
        private const val FIND_SIZE = 960
        private const val PAINT_THREADS = 4
        private const val FIND_THREADS = 2
        private const val AREA_GRID = 1024
        private const val CONTEXT = 2.5f
        private const val PIECE_RATIO = 2
        private const val PIECE_MIN = 32
        private const val TEXT_ON = 0.3f
        private const val TEXT_SURE = 0.6f
        private const val TEXT_MIN_PIXELS = 4
        private const val UNCLIP = 1.5f
        private const val TEXT_MARGIN = 2f
        private const val TEXT_EXTRA = 0.3f
        private const val TEXT_ROUNDS = 6
        private const val TEXT_NEAR_MIN = 24f
        private const val TEXT_NEAR_SHARE = 0.45f
        private const val TEXT_EDGE_SHARE = 0.5f
        private const val TEXT_GROW = 0.1f
        private const val MEAN_R = 0.406f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.485f
        private const val STD_R = 0.225f
        private const val STD_G = 0.224f
        private const val STD_B = 0.229f

        internal fun options(threads: Int): Interpreter.Options {
            val o = Interpreter.Options().setNumThreads(threads)
            val emulator = android.os.Build.HARDWARE.let { it.contains("ranchu") || it.contains("goldfish") }
            if (emulator) o.setUseXNNPACK(false)
            return o
        }

        internal fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

        internal fun model(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
