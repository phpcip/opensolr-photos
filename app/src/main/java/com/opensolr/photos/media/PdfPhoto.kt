package com.opensolr.photos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * A photo as one PDF page, at most 300 dpi on the A4-long page (3508 px on its long edge, what OCR wants): a JPEG
 * that is not larger goes in byte for byte (its metadata left out), a larger photo is scaled down to that once, any
 * other picture goes in as JPEG bands of its own pixels; the page turns it the way its EXIF says.
 */
object PdfPhoto {

    /** False when the photo cannot be read (gone, not a picture); a failed write throws. */
    fun add(context: Context, uri: Uri, pdf: PdfWriter): Boolean {
        val resolver = context.contentResolver
        val orientation = PhotoReader.openExif(context, uri)
            ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?.takeIf { it in 1..8 } ?: 1
        val frame = try {
            resolver.openInputStream(uri)?.use { Jpeg.frame(it) }
        } catch (e: Exception) {
            null
        }
        if (frame != null && maxOf(frame.width, frame.height) > MAX_EDGE) return addScaled(context, uri, orientation, pdf)
        if (frame != null) {
            val input = try {
                resolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            } ?: return false
            return input.use { stream ->
                pdf.addPage(frame.width, frame.height, orientation) {
                    jpeg(0, frame.height, frame.width, frame.components) { out -> Jpeg.copyWithoutMetadata(stream, out) }
                    true
                }
            }
        }
        return addDecoded(context, uri, orientation, pdf)
    }

    // PNG, WebP, HEIF and the JPEGs a PDF cannot show as they are: decoded band by band at full size, each band a JPEG
    private fun addDecoded(context: Context, uri: Uri, orientation: Int, pdf: PdfWriter): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        } catch (e: Exception) {
            return false
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        if (maxOf(width, height) > MAX_EDGE) return addScaled(context, uri, orientation, pdf)
        val region = try {
            resolver.openInputStream(uri)?.use { stream ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(stream)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(stream, false)
            }
        } catch (e: Exception) {
            null
        }
        if (region == null) {
            // a format the band decoder does not take is decoded whole, when it fits
            if (width.toLong() * height > BAND_PIXELS) return false
            val whole = try {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            } ?: return false
            val jpeg = encode(whole)
            return pdf.addPage(width, height, orientation) {
                jpeg(0, height, width, 3) { it.write(jpeg) }
                true
            }
        }
        return try {
            val bandRows = ((BAND_PIXELS / width).toInt() / BAND_STEP * BAND_STEP).coerceIn(BAND_STEP, height)
            val bands = ArrayList<IntArray>()
            var top = 0
            while (top < height) {
                val rows = minOf(bandRows, height - top)
                bands += intArrayOf(top, rows)
                top += rows
            }
            pdf.addPage(width, height, orientation) {
                for ((start, rows) in bands) {
                    // every band after the first repeats the row above it, so no viewer shows a seam
                    val from = if (start > 0) start - 1 else start
                    val count = rows + (start - from)
                    val band = try {
                        region.decodeRegion(Rect(0, from, width, from + count), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    } catch (e: Exception) {
                        null
                    } ?: return@addPage false
                    val jpeg = encode(band)
                    jpeg(from, count, width, 3) { it.write(jpeg) }
                }
                true
            }
        } finally {
            region.recycle()
        }
    }

    // a photo larger than 300 dpi on the page: read at the smallest power-of-two size still above it, then scaled to it exactly
    private fun addScaled(context: Context, uri: Uri, orientation: Int, pdf: PdfWriter): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        } catch (e: Exception) {
            return false
        }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return false
        var sample = 1
        while (long / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false
        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            val w = (decoded.width * scale).toInt().coerceAtLeast(1)
            val h = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val width = sized.width
        val height = sized.height
        val jpeg = encode(sized)
        return pdf.addPage(width, height, orientation) {
            jpeg(0, height, width, 3) { it.write(jpeg) }
            true
        }
    }

    // transparent pixels on white, the way a page shows them; the bitmap is recycled
    private fun encode(bitmap: Bitmap): ByteArray {
        val opaque = if (bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            flat
        } else bitmap
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        opaque.recycle()
        return out.toByteArray()
    }

    private const val QUALITY = 85
    // 300 dpi along the long side of an A4 page (11.69 in)
    private const val MAX_EDGE = 3508
    private const val BAND_PIXELS = 12_000_000L
    private const val BAND_STEP = 16
}
