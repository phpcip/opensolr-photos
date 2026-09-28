package com.opensolr.photos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.tensorflow.lite.Interpreter
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the faces in a photo and gives each one a fingerprint, on the phone, with nothing sent anywhere:
 * YuNet (MIT) finds the face and its five points, SFace (Apache 2.0) turns the aligned face into 128 numbers,
 * both as TensorFlow Lite models run by LiteRT.
 * Two faces of the same person give close fingerprints (cosine), whatever the photo.
 */
class FaceEngine private constructor(context: Context) {

    /** A face in an upright photo: box and points as shares of the photo's width and height, fingerprint unit length. */
    class Face(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val vector: FloatArray)

    private val detector = Interpreter(model(context, "faces/yunet.tflite"), Interpreter.Options().setNumThreads(2))
    private val recognizer = Interpreter(model(context, "faces/sface.tflite"), Interpreter.Options().setNumThreads(2))
    private val lock = Any()

    // buffers reused for every photo: the finder's frame, its outputs, the aligned face and its fingerprint
    private val frameIn = direct(DETECT_SIZE * DETECT_SIZE * 3)
    private val headOut = STRIDES.flatMap { s -> val n = (DETECT_SIZE / s) * (DETECT_SIZE / s); listOf("cls_$s" to n, "obj_$s" to n, "bbox_$s" to n * 4, "kps_$s" to n * 10) }
        .associate { (name, size) -> name to direct(size) }
    private val headIndex = headOut.keys.associateWith { detector.getOutputIndex(it) }
    private val faceIn = direct(ALIGN * ALIGN * 3)
    private val printOut = direct(128)

    /** Every face clear enough to recognise in [photo] (upright, long edge ~[READ_EDGE] px). */
    fun analyze(photo: Bitmap): List<Face> = synchronized(lock) {
        val scale = DETECT_SIZE.toFloat() / max(photo.width, photo.height)
        val found = detect(letterbox(photo, scale))
        found.mapNotNull { f ->
            // back to the photo's own pixels: the fingerprint is read from the sharper, larger image
            val points = FloatArray(10) { f.points[it] / scale }
            val vector = fingerprint(photo, points) ?: return@mapNotNull null
            val x = f.x / scale
            val y = f.y / scale
            val w = f.w / scale
            val h = f.h / scale
            Face(
                (x / photo.width).coerceIn(0f, 1f), (y / photo.height).coerceIn(0f, 1f),
                (w / photo.width).coerceIn(0f, 1f), (h / photo.height).coerceIn(0f, 1f),
                f.score, vector,
            )
        }
    }

    private class Found(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val points: FloatArray)

    private fun letterbox(photo: Bitmap, scale: Float): Bitmap {
        val out = Bitmap.createBitmap(DETECT_SIZE, DETECT_SIZE, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setScale(scale, scale) }
        Canvas(out).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** YuNet on the 640x640 frame: raw BGR in, anchor-free heads at strides 8/16/32 out, then NMS. */
    private fun detect(frame: Bitmap): List<Found> {
        val n = DETECT_SIZE * DETECT_SIZE
        val pixels = IntArray(n)
        frame.getPixels(pixels, 0, DETECT_SIZE, 0, 0, DETECT_SIZE, DETECT_SIZE)
        frame.recycle()
        // NHWC, raw 0-255, blue-green-red: what YuNet was trained on
        val input = frameIn.asFloatBuffer()
        for (i in 0 until n) {
            val px = pixels[i]
            input.put(3 * i, (px and 0xff).toFloat())
            input.put(3 * i + 1, ((px shr 8) and 0xff).toFloat())
            input.put(3 * i + 2, ((px shr 16) and 0xff).toFloat())
        }
        frameIn.rewind()
        val outputs = HashMap<Int, Any>()
        headOut.forEach { (name, buffer) -> buffer.rewind(); outputs[headIndex.getValue(name)] = buffer }
        detector.runForMultipleInputsOutputs(arrayOf<Any>(frameIn), outputs)

        val candidates = ArrayList<Found>()
        fun out(name: String): FloatBuffer = headOut.getValue(name).also { it.rewind() }.asFloatBuffer()
        for (stride in STRIDES) {
            val cols = DETECT_SIZE / stride
            val cls = out("cls_$stride")
            val obj = out("obj_$stride")
            val box = out("bbox_$stride")
            val kps = out("kps_$stride")
            for (i in 0 until cls.limit()) {
                val score = sqrt(cls.get(i).coerceIn(0f, 1f) * obj.get(i).coerceIn(0f, 1f))
                if (score < SCORE_MIN) continue
                val r = i / cols
                val c = i % cols
                val cx = (c + box.get(i * 4)) * stride
                val cy = (r + box.get(i * 4 + 1)) * stride
                val w = exp(box.get(i * 4 + 2)) * stride
                val h = exp(box.get(i * 4 + 3)) * stride
                if (w < FACE_MIN_PX) continue
                val points = FloatArray(10) { k -> (kps.get(i * 10 + k) + if (k % 2 == 0) c else r) * stride }
                candidates += Found(cx - w / 2, cy - h / 2, w, h, score, points)
            }
        }
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Found>()
        for (f in candidates) if (kept.none { overlap(it, f) > NMS_IOU }) kept += f
        return kept
    }

    private fun overlap(a: Found, b: Found): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0f) inter / union else 0f
    }

    /** The face turned and scaled onto the 112x112 template by its five points, then SFace on raw RGB. */
    private fun fingerprint(photo: Bitmap, points: FloatArray): FloatArray? {
        // least-squares similarity from the five points to the template (closed form, no reflection)
        var ms0 = 0f; var ms1 = 0f; var md0 = 0f; var md1 = 0f
        for (k in 0 until 5) { ms0 += points[2 * k]; ms1 += points[2 * k + 1]; md0 += TEMPLATE[2 * k]; md1 += TEMPLATE[2 * k + 1] }
        ms0 /= 5; ms1 /= 5; md0 /= 5; md1 /= 5
        var den = 0f; var na = 0f; var nb = 0f
        for (k in 0 until 5) {
            val sx = points[2 * k] - ms0
            val sy = points[2 * k + 1] - ms1
            val dx = TEMPLATE[2 * k] - md0
            val dy = TEMPLATE[2 * k + 1] - md1
            den += sx * sx + sy * sy
            na += dx * sx + dy * sy
            nb += dy * sx - dx * sy
        }
        if (den <= 0f) return null
        val a = na / den
        val b = nb / den
        val m = Matrix().apply { setValues(floatArrayOf(a, -b, md0 - (a * ms0 - b * ms1), b, a, md1 - (b * ms0 + a * ms1), 0f, 0f, 1f)) }
        val crop = Bitmap.createBitmap(ALIGN, ALIGN, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val n = ALIGN * ALIGN
        val pixels = IntArray(n)
        crop.getPixels(pixels, 0, ALIGN, 0, 0, ALIGN, ALIGN)
        crop.recycle()
        // NHWC, raw 0-255, red-green-blue
        val input = faceIn.asFloatBuffer()
        for (i in 0 until n) {
            val px = pixels[i]
            input.put(3 * i, ((px shr 16) and 0xff).toFloat())
            input.put(3 * i + 1, ((px shr 8) and 0xff).toFloat())
            input.put(3 * i + 2, (px and 0xff).toFloat())
        }
        faceIn.rewind()
        printOut.rewind()
        recognizer.run(faceIn, printOut)
        printOut.rewind()
        val vector = FloatArray(128).also { printOut.asFloatBuffer().get(it) }
        var norm = 0f
        for (v in vector) norm += v * v
        norm = sqrt(norm)
        if (norm <= 0f) return null
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    companion object {
        /** The long edge a photo is read at: the finder works at 640, the fingerprint on this sharper copy. */
        const val READ_EDGE = 1280

        private const val DETECT_SIZE = 640
        private const val ALIGN = 112
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val SCORE_MIN = 0.8f
        private const val NMS_IOU = 0.3f
        /** Smaller faces (at 640) are too blurred to tell people apart and only bring false matches. */
        private const val FACE_MIN_PX = 32f
        /** Where the eyes, nose tip and mouth corners sit in the 112x112 face SFace was trained on. */
        private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

        private fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

        private fun model(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }

        @Volatile private var shared: FaceEngine? = null

        fun of(context: Context): FaceEngine = shared ?: synchronized(this) {
            shared ?: FaceEngine(context.applicationContext).also { shared = it }
        }

        /** Cosine of two unit fingerprints. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
