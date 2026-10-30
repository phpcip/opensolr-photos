package com.opensolr.photos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The text printed in a photo, read on the phone from the picture itself: PP-OCR's text finder marks the lines,
 * PP-OCRv5's Latin reader (Apache 2.0, 32 languages) reads each one. Nothing is sent anywhere; the text goes to
 * the index with the photo, and the server reads that photo no more. Loaded on first use, closed with [close].
 */
class TextReader(context: Context) {

    private val app = context.applicationContext
    private val finder = MagicEraser(app)
    private var reader: Interpreter? = null
    private val labels: List<String> by lazy {
        // the class order of the model: 0 = blank, then the dictionary's characters, then the space
        val chars = app.assets.open(DICT).bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
        listOf("") + chars + " "
    }
    private val input by lazy { MagicEraser.direct(3 * HEIGHT * WIDTH) }
    private val output by lazy { MagicEraser.direct(STEPS * CLASSES) }

    /** The lines read in [photo], top to bottom and left to right, one per line; "" when none was read with confidence. */
    fun read(photo: Bitmap): String {
        val boxes = finder.findText(photo, reading = true).filter { it.width() >= MIN_SIDE && it.height() >= MIN_SIDE }
        if (boxes.isEmpty()) return ""
        val net = reader ?: Interpreter(MagicEraser.model(app, MODEL), MagicEraser.options(THREADS)).also { reader = it }
        val read = ArrayList<Pair<RectF, String>>(boxes.size)
        for (box in boxes.take(MAX_BOXES)) {
            val text = readLine(net, photo, box)
            if (text.isNotBlank()) read += box to text
        }
        return inLines(read)
    }

    /** One box read: cropped from the picture, upright when it stands on its side, scaled to the reader's height. */
    private fun readLine(net: Interpreter, photo: Bitmap, box: RectF): String {
        val x0 = box.left.roundToInt().coerceIn(0, photo.width - 1)
        val y0 = box.top.roundToInt().coerceIn(0, photo.height - 1)
        val x1 = box.right.roundToInt().coerceIn(x0 + 1, photo.width)
        val y1 = box.bottom.roundToInt().coerceIn(y0 + 1, photo.height)
        var crop = Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0)
        // a column of text (a spine, a vertical label) is read turned a quarter, as PP-OCR does
        if (crop.height >= crop.width * VERTICAL_RATIO) {
            val m = android.graphics.Matrix().apply { postRotate(-90f) }
            val turned = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)
            if (turned !== crop) crop.recycle()
            crop = turned
        }
        // the line at the reader's height; one longer than the frame is narrowed to fit, as PP-OCR does
        val w = min(WIDTH, max(1, ceil(HEIGHT * crop.width / crop.height.toFloat()).toInt()))
        val scaled = Bitmap.createScaledBitmap(crop, w, HEIGHT, true)
        if (scaled !== crop) crop.recycle()
        val px = IntArray(w * HEIGHT)
        scaled.getPixels(px, 0, w, 0, 0, w, HEIGHT)
        scaled.recycle()
        // channels first, B, G, R as PaddleOCR reads them, scaled to -1..1; the rest of the frame left at 0
        val fb = input.asFloatBuffer()
        for (i in 0 until 3 * HEIGHT * WIDTH) fb.put(i, 0f)
        val plane = HEIGHT * WIDTH
        for (y in 0 until HEIGHT) for (x in 0 until w) {
            val c = px[y * w + x]
            val at = y * WIDTH + x
            fb.put(at, ((c and 0xFF) / 255f - 0.5f) / 0.5f)
            fb.put(plane + at, (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f)
            fb.put(2 * plane + at, (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        input.rewind()
        output.rewind()
        net.run(input, output)
        output.rewind()
        return decode(output.asFloatBuffer(), min(STEPS, ceil(w / STRIDE.toFloat()).toInt() + 1))
    }

    /** Greedy CTC: the likeliest class of each step, repeats and blanks dropped; nothing when the reader is unsure. */
    private fun decode(probs: java.nio.FloatBuffer, steps: Int): String {
        val sb = StringBuilder()
        var last = 0
        var sum = 0f
        var n = 0
        for (t in 0 until steps) {
            var best = 0
            var p = probs.get(t * CLASSES)
            for (c in 1 until CLASSES) {
                val v = probs.get(t * CLASSES + c)
                if (v > p) { p = v; best = c }
            }
            if (best != 0 && best != last && best < labels.size) {
                sb.append(labels[best])
                sum += p
                n++
            }
            last = best
        }
        if (n == 0 || sum / n < MIN_CONFIDENCE) return ""
        return sb.toString().trim()
    }

    /** The pieces read, as the page reads: grouped into lines by height, each line left to right. */
    private fun inLines(read: List<Pair<RectF, String>>): String {
        val byTop = read.sortedBy { it.first.centerY() }
        val lines = ArrayList<MutableList<Pair<RectF, String>>>()
        for (piece in byTop) {
            val line = lines.lastOrNull()
            val ref = line?.first()?.first
            if (ref != null && abs(piece.first.centerY() - ref.centerY()) < min(piece.first.height(), ref.height()) * SAME_LINE) line.add(piece)
            else lines += mutableListOf(piece)
        }
        return lines.joinToString("\n") { l -> l.sortedBy { it.first.left }.joinToString(" ") { it.second } }
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    fun close() {
        reader?.close()
        reader = null
        finder.close()
    }

    companion object {
        private const val MODEL = "ocr/latin_rec.tflite"
        private const val DICT = "ocr/latin_dict.txt"
        private const val HEIGHT = 48
        private const val WIDTH = 960
        private const val STRIDE = 8
        private const val STEPS = WIDTH / STRIDE
        private const val CLASSES = 504
        private const val THREADS = 4
        private const val MIN_SIDE = 6f
        private const val MAX_BOXES = 300
        private const val VERTICAL_RATIO = 1.5f
        private const val MIN_CONFIDENCE = 0.5f
        private const val SAME_LINE = 0.5f
    }
}
