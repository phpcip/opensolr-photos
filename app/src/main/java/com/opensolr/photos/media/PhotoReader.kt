package com.opensolr.photos.media

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

data class PhotoMetadata(
    val takenAtUtc: String?,
    val year: Int?,
    val month: Int?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lens: String?,
    val iso: Int?,
    val exposure: String?,
    val fNumber: Double?,
    val focalLength: Double?,
    val flash: Boolean?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val rotationDegrees: Int,
)

object PhotoReader {

    private const val CLIP_EDGE_PX = 1024
    private const val PIXEL_HASH_EDGE = 256
    private const val JPEG_QUALITY = 85
    private const val SHARE_JPEG_QUALITY = 92

    fun shrinkForClip(context: Context, uri: Uri, rotationDegrees: Int, edge: Int = CLIP_EDGE_PX, quality: Int = JPEG_QUALITY): ByteArray? {
        val upright = decodeUpright(context, uri, rotationDegrees, edge) ?: return null
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
        upright.recycle()
        return out.toByteArray()
    }

    /** The photo upright, long edge at most [edge] px, as the face finder reads it. */
    fun uprightBitmap(context: Context, uri: Uri, edge: Int): Bitmap? =
        decodeUpright(context, uri, openExif(context, uri)?.rotationDegrees ?: 0, edge)

    private fun decodeUpright(context: Context, uri: Uri, rotationDegrees: Int, edge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > edge) edge.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        if (matrix.isIdentity) return decoded
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    /**
     * The md5 of the picture itself (its pixels, upright, at a small size), never of its metadata: the same photo
     * with tags or a place written into it by any app hashes the same, an edited or cropped one does not.
     */
    fun pixelHash(context: Context, uri: Uri): String? = try {
        val big = uprightBitmap(context, uri, FaceEngine.READ_EDGE)
        if (big == null) null else try { pixelHashOf(big) } finally { big.recycle() }
    } catch (e: Exception) {
        null
    }

    /** The picture hash of an upright decode: always taken from the same reduction, so every path agrees. */
    fun pixelHashOf(upright: Bitmap): String? = try {
        val bitmap = scaled(upright, PIXEL_HASH_EDGE)
        run {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = java.nio.ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // only the top bits of each channel: a re-save of the same picture must not count as a change
            pixels.forEach { buffer.putInt(it and 0x00F0F0F0) }
            digest.update(byteArrayOf((w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte()))
            digest.update(buffer.array())
            if (bitmap !== upright) bitmap.recycle()
            hex(digest.digest())
        }
    } catch (e: Exception) {
        null
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** The file's identity: md5 of a photo or PDF, the partial hash of a video (its size, first and last 4 MB). */
    fun fileMd5(context: Context, photo: LocalPhoto): String? = when (photo.kind) {
        Kind.VIDEO -> PartialHash.of(context, photo.uri)
        Kind.PDF -> pdfMd5(context, photo)
        else -> fileMd5(context, photo.uri)
    }

    // the pages of one PDF share its md5: computed once per file and size while the pages go through
    private val pdfHashes = object : java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }

    private fun pdfMd5(context: Context, photo: LocalPhoto): String? {
        val key = photo.uri.toString() + "|" + photo.sizeBytes
        synchronized(pdfHashes) { pdfHashes[key]?.let { return it } }
        val md5 = fileMd5(context, photo.uri) ?: return null
        synchronized(pdfHashes) { pdfHashes[key] = md5 }
        return md5
    }

    /**
     * The picture that stands for [photo] at [edge] px, as JPEG: the photo itself, a video's first frame, one page
     * of a PDF. The copies in the storage backend and the restore copies are made of this.
     */
    fun representativeJpeg(context: Context, photo: LocalPhoto, edge: Int, quality: Int = SHARE_JPEG_QUALITY): ByteArray? {
        val bitmap = when (photo.kind) {
            Kind.VIDEO -> VideoFrames.frame(context, photo.uri, edge)
            Kind.PDF -> PdfPages.render(context, photo.uri, photo.page, edge)
            else -> return shrinkForClip(context, photo.uri, openExif(context, photo.uri)?.rotationDegrees ?: 0, edge, quality)
        } ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    fun fileMd5(context: Context, uri: android.net.Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }

            val bytes = digest.digest()
            val hex = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xff
                hex[i * 2] = HEX[v ushr 4]
                hex[i * 2 + 1] = HEX[v and 0x0f]
            }
            String(hex)
        }
    } catch (e: Exception) {
        null
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** A smaller copy to send: upright JPEG, long edge [edge] px, the original's date, camera and place kept. */
    fun shareCopy(context: Context, uri: Uri, out: java.io.File, edge: Int): Boolean {
        val exif = openExif(context, uri)
        val jpeg = shrinkForClip(context, uri, exif?.rotationDegrees ?: 0, edge, SHARE_JPEG_QUALITY) ?: return false
        out.writeBytes(jpeg)
        if (exif != null) runCatching {
            val copy = ExifInterface(out.absolutePath)
            for (tag in CARRIED_EXIF) exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) }
            copy.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            copy.saveAttributes()
        }
        return true
    }

    /** What one read of a file gives the sync: the copy for the server, the file md5, the picture hash and the upright image for faces. */
    /** [noRead]: the picture could not be decoded on this phone; [jpeg] is a plain stand-in and the file is indexed by its facts only. */
    class IngestRead(val jpeg: ByteArray, val md5: String, val pixelHash: String?, val upright: Bitmap?, val noRead: Boolean = false)

    /** A plain stand-in picture for a file whose own picture cannot be decoded: dark, with a sign of its kind. */
    fun placeholderJpeg(kind: Kind, edge: Int = 640): ByteArray {
        val w = edge
        val h = edge * 3 / 4
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFF2A2A2A.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDDDDD.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = w / 64f; strokeJoin = android.graphics.Paint.Join.ROUND }
        val cx = w / 2f
        val cy = h / 2f
        val r = h / 6f
        val path = android.graphics.Path()
        when (kind) {
            Kind.VIDEO -> { path.moveTo(cx - r * 0.7f, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx - r * 0.7f, cy + r); path.close() }
            Kind.PDF -> { path.addRect(cx - r * 0.8f, cy - r, cx + r * 0.8f, cy + r, android.graphics.Path.Direction.CW) }
            else -> { path.addRect(cx - r * 1.2f, cy - r * 0.9f, cx + r * 1.2f, cy + r * 0.9f, android.graphics.Path.Direction.CW) }
        }
        canvas.drawPath(path, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /**
     * The file read once into memory: md5 over its bytes, one decode (upright, long edge [FaceEngine.READ_EDGE])
     * from which the server copy, the picture hash and the face image are all taken. Null when it cannot be read.
     */
    fun readForIngest(context: Context, photo: LocalPhoto, place: com.opensolr.photos.data.PhotoCache.SetPlace? = null): IngestRead? {
        if (photo.kind != Kind.PHOTO) return readOtherForIngest(context, photo, place)
        val bytes = context.contentResolver.openInputStream(photo.uri)?.use { it.readBytes() } ?: return null
        val md5 = hex(java.security.MessageDigest.getInstance("MD5").digest(bytes))
        val exif = openExif(context, photo.uri)
        // a picture this phone cannot decode is still a photo: indexed by its facts, backed up, listed
        val upright = decodeUprightBytes(bytes, exif?.rotationDegrees ?: 0, FaceEngine.READ_EDGE)
            ?: return IngestRead(withCarriedExif(context, placeholderJpeg(Kind.PHOTO), exif, place), md5, null, null, noRead = true)
        val pixelHash = pixelHashOf(upright)
        val small = scaled(upright, CLIP_EDGE_PX)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (small !== upright) small.recycle()
        val jpeg = withCarriedExif(context, out.toByteArray(), exif, place)
        return IngestRead(jpeg, md5, pixelHash, upright)
    }

    /**
     * A video or a PDF page for the index: the picture is the first frame or the rendered page; the file's
     * identity is its (partial) hash; the date and place a video carries go into the copy's EXIF, so the server
     * reads them as it reads a photo's.
     */
    private fun readOtherForIngest(context: Context, photo: LocalPhoto, place: com.opensolr.photos.data.PhotoCache.SetPlace?): IngestRead? {
        val md5 = fileMd5(context, photo) ?: return null
        val decoded = when (photo.kind) {
            Kind.VIDEO -> VideoFrames.frame(context, photo.uri, FaceEngine.READ_EDGE)
            else -> PdfPages.render(context, photo.uri, photo.page, FaceEngine.READ_EDGE)
        }
        val noRead = decoded == null
        // no frame or page can be drawn here: a stand-in picture carries the facts, the file is still indexed
        val stand = if (decoded == null) placeholderJpeg(photo.kind) else null
        val upright = decoded ?: BitmapFactory.decodeByteArray(stand, 0, stand!!.size)
        val pixelHash = if (noRead) null else pixelHashOf(upright)
        val small = scaled(upright, CLIP_EDGE_PX)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (small !== upright) small.recycle()
        var jpeg = out.toByteArray()
        val facts = if (photo.kind == Kind.VIDEO) VideoFrames.facts(context, photo.uri) else null
        val taken = facts?.takenMs ?: photo.dateTakenMs.takeIf { it > 0 } ?: (photo.modifiedSec * 1000L).takeIf { it > 0 }
        val latLon = facts?.let { f -> if (f.latitude != null && f.longitude != null) f.latitude to f.longitude else null }
            ?: place?.let { it.lat to it.lon }
        if (taken != null || latLon != null) {
            val file = java.io.File.createTempFile("ingest", ".jpg", context.cacheDir)
            try {
                file.writeBytes(jpeg)
                val exif = ExifInterface(file.absolutePath)
                taken?.let { exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(it)); exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00") }
                latLon?.let { exif.setLatLong(it.first, it.second) }
                exif.saveAttributes()
                jpeg = file.readBytes()
            } catch (e: Exception) {
            } finally {
                file.delete()
            }
        }
        return IngestRead(jpeg, md5, pixelHash, if (noRead) null.also { upright.recycle() } else upright, noRead)
    }

    /** [bitmap] with its long edge at most [edge] px: the same bitmap when it already is. */
    private fun scaled(bitmap: Bitmap, edge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= edge) return bitmap
        val scale = edge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
    }

    private fun decodeUprightBytes(bytes: ByteArray, rotationDegrees: Int, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > edge) edge.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        if (matrix.isIdentity) return decoded
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    fun copyForIngest(context: Context, photo: LocalPhoto, place: com.opensolr.photos.data.PhotoCache.SetPlace? = null): ByteArray? {
        val exif = openExif(context, photo.uri)
        val jpeg = shrinkForClip(context, photo.uri, exif?.rotationDegrees ?: 0) ?: return null
        return withCarriedExif(context, jpeg, exif, place)
    }

    /** The server copy with the original's EXIF carried over and the place put in, as the copy always was. */
    private fun withCarriedExif(context: Context, jpeg: ByteArray, exif: ExifInterface?, place: com.opensolr.photos.data.PhotoCache.SetPlace?): ByteArray {

        val placed = place?.takeIf { it.owner || exif == null || usableLatLong(exif) == null }
        if (exif == null && placed == null) return jpeg
        val file = java.io.File.createTempFile("ingest", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg)
            val out = ExifInterface(file.absolutePath)
            if (exif != null) {
                for (tag in CARRIED_EXIF) {
                    exif.getAttribute(tag)?.let { out.setAttribute(tag, it) }
                }
            }
            placed?.let {
                out.setLatLong(it.lat, it.lon)
                out.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                out.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
            }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.saveAttributes()
            return file.readBytes()
        } catch (e: Exception) {
            return jpeg
        } finally {
            file.delete()
        }
    }

    fun takenAsIndexed(context: Context, uri: Uri): Long? {
        val exif = openExif(context, uri) ?: return null
        val raw = (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.trim() ?: return null
        val offset = (exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
            ?.takeIf { Regex("^[+-]\\d{2}:\\d{2}$").matches(it) } ?: "+00:00"
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse(raw + offset)?.time?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }



    enum class GpsState {

        USABLE,

        MISSING,

        UNKNOWN,
    }

    fun gpsState(context: Context, uri: Uri): GpsState {
        val exif = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> try {
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            } catch (e: Exception) {
                null
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED -> null
            else -> try {
                context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { ExifInterface(it) }
            } catch (e: Exception) {
                null
            }
        } ?: return GpsState.UNKNOWN
        return if (usableLatLong(exif) != null) GpsState.USABLE else GpsState.MISSING
    }

    private fun usableLatLong(exif: ExifInterface): Pair<Double, Double>? {
        val latLong = exif.latLong ?: return null
        val lat = latLong.getOrNull(0) ?: return null
        val lon = latLong.getOrNull(1) ?: return null
        if (!valid(lat, 90.0) || !valid(lon, 180.0) || (lat == 0.0 && lon == 0.0)) return null
        return lat to lon
    }

    /** Whether the photo's file can be opened at all (false for a MediaStore row whose file was deleted). */
    fun fileExists(context: Context, photo: LocalPhoto): Boolean = try {
        context.contentResolver.openInputStream(photo.uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun unreadableReason(context: Context, photo: LocalPhoto): String {
        if (photo.sizeBytes <= 0L) return AppText.s(R.string.md_empty)
        val opened = try {
            context.contentResolver.openInputStream(photo.uri)?.use { it.read() >= 0 } ?: false
        } catch (e: Exception) {
            false
        }
        return if (opened) AppText.s(R.string.md_undecodable) else AppText.s(R.string.md_unopenable)
    }

    fun readMetadata(context: Context, photo: LocalPhoto): PhotoMetadata {
        val exif = openExif(context, photo.uri)
        val taken = exif?.let { takenAt(it) } ?: photo.dateTakenMs.takeIf { it > 0 } ?: (photo.modifiedSec * 1000L)
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = taken }
        val latLong = exif?.latLong

        return PhotoMetadata(
            takenAtUtc = isoUtc(taken),
            year = calendar.get(java.util.Calendar.YEAR),
            month = calendar.get(java.util.Calendar.MONTH) + 1,
            cameraMake = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }?.let { formatExposure(it) },
            fNumber = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 },
            focalLength = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 },
            flash = exif?.let { if (it.hasAttribute(ExifInterface.TAG_FLASH)) (it.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) == 1 else null },
            latitude = latLong?.getOrNull(0)?.takeIf { valid(it, 90.0) && !(latLong[0] == 0.0 && latLong[1] == 0.0) },
            longitude = latLong?.getOrNull(1)?.takeIf { valid(it, 180.0) && !(latLong[0] == 0.0 && latLong[1] == 0.0) },
            altitude = exif?.let { if (it.hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) it.getAltitude(0.0) else null },
            rotationDegrees = exif?.rotationDegrees ?: 0,
        )
    }

    internal val CARRIED_EXIF = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    private val XMP_LI = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)

    /** A named face in the upright photo: box as shares of its width and height. */
    data class FaceArea(val name: String, val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * The named face areas written in the file (MWG regions, by this app or any other), turned upright: box as
     * shares of the upright photo. Empty when there are none.
     */
    fun faceAreasIn(context: Context, uri: Uri): List<FaceArea> = try {
        val exif = openExif(context, uri)
        val packet = exif?.getAttributeBytes(ExifInterface.TAG_XMP)?.let { String(it, Charsets.UTF_8) }
        val block = packet?.let { XMP_REGIONS.find(it)?.groupValues?.get(2) }
        if (block == null) emptyList() else {
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            fun value(item: String, name: String): Float? =
                (Regex("$name=\"([-0-9.eE]+)\"").find(item) ?: Regex("<$name>([-0-9.eE]+)</$name>").find(item))?.groupValues?.get(1)?.toFloatOrNull()
            fun upright(x: Float, y: Float): Pair<Float, Float> = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> (1 - x) to y
                ExifInterface.ORIENTATION_ROTATE_180 -> (1 - x) to (1 - y)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to (1 - y)
                ExifInterface.ORIENTATION_TRANSPOSE -> y to x
                ExifInterface.ORIENTATION_ROTATE_90 -> (1 - y) to x
                ExifInterface.ORIENTATION_TRANSVERSE -> (1 - y) to (1 - x)
                ExifInterface.ORIENTATION_ROTATE_270 -> y to (1 - x)
                else -> x to y
            }
            XMP_LI.findAll(block).mapNotNull { li ->
                val item = li.groupValues[1]
                val name = Regex("<([A-Za-z0-9_-]+:)?Name>(.*?)</([A-Za-z0-9_-]+:)?Name>").find(item)?.groupValues?.get(2)?.let { unescapeXml(it).trim() }
                if (name.isNullOrEmpty()) return@mapNotNull null
                val type = Regex("<([A-Za-z0-9_-]+:)?Type>(.*?)</").find(item)?.groupValues?.get(2)
                if (type != null && !type.equals("Face", ignoreCase = true)) return@mapNotNull null
                val cx = value(item, "stArea:x") ?: return@mapNotNull null
                val cy = value(item, "stArea:y") ?: return@mapNotNull null
                val w = value(item, "stArea:w") ?: return@mapNotNull null
                val h = value(item, "stArea:h") ?: return@mapNotNull null
                val a = upright(cx - w / 2, cy - h / 2)
                val b = upright(cx + w / 2, cy + h / 2)
                FaceArea(name, minOf(a.first, b.first), minOf(a.second, b.second), kotlin.math.abs(b.first - a.first), kotlin.math.abs(b.second - a.second))
            }.toList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun tagsIn(context: Context, uri: Uri): List<String> = try {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            ?.getAttributeBytes(ExifInterface.TAG_XMP)
            ?.let { String(it, Charsets.UTF_8) }
            ?.let { packet ->
                XMP_SUBJECT.find(packet)?.groupValues?.get(2)?.let { bag ->
                    XMP_LI.findAll(bag).map { unescapeXml(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.distinct().toList()
                }
            } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    data class XmpWords(val tags: List<String>?, val meaning: String?, val persons: List<String>)

    fun xmpWordsIn(context: Context, photo: LocalPhoto): XmpWords = try {
        val packet = openExif(context, photo.uri)
            ?.getAttributeBytes(ExifInterface.TAG_XMP)
            ?.let { String(it, Charsets.UTF_8) }
        if (packet == null) XmpWords(null, null, emptyList()) else XmpWords(
            tags = XMP_OPENSOLR_TAGS.find(packet)?.groupValues?.get(1)?.let { liValues(it) },
            meaning = XMP_OPENSOLR_MEANING.find(packet)?.groupValues?.get(1)
                ?.let { unescapeXml(it).trim().take(MEANING_MAX_CHARS) }?.takeIf { it.isNotEmpty() },
            persons = XMP_PERSONS.find(packet)?.groupValues?.get(2)?.let { liValues(it) } ?: emptyList(),
        )
    } catch (e: Exception) {
        XmpWords(null, null, emptyList())
    }

    private fun liValues(bag: String): List<String> =
        XMP_LI.findAll(bag).map { unescapeXml(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.distinct().toList()

    private fun escapeXml(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            when {
                cp == '&'.code -> append("&amp;")
                cp == '<'.code -> append("&lt;")
                cp == '>'.code -> append("&gt;")
                cp > 127 -> append("&#x").append(Integer.toHexString(cp)).append(';')
                else -> appendCodePoint(cp)
            }
        }
    }

    private fun unescapeXml(text: String): String =
        Regex("&#x([0-9a-fA-F]+);|&#([0-9]+);|&amp;|&lt;|&gt;|&quot;|&apos;").replace(text) { m ->
            when {
                m.groupValues[1].isNotEmpty() -> String(Character.toChars(m.groupValues[1].toInt(16)))
                m.groupValues[2].isNotEmpty() -> String(Character.toChars(m.groupValues[2].toInt()))
                m.value == "&amp;" -> "&"
                m.value == "&lt;" -> "<"
                m.value == "&gt;" -> ">"
                m.value == "&quot;" -> "\""
                else -> "'"
            }
        }

    private const val OPENSOLR_NS = "https://opensolr.com/ns/photos/1.0/"

    const val MEANING_MAX_CHARS = 2000

    private val XMP_OPENSOLR_MEANING = Regex("<opensolr:Meaning(?:\\s[^>]*)?>(.*?)</opensolr:Meaning>", RegexOption.DOT_MATCHES_ALL)

    private val XMP_OPENSOLR_TAGS = Regex("<opensolr:Tags(?:\\s[^>]*)?>(.*?)</opensolr:Tags>", RegexOption.DOT_MATCHES_ALL)

    private val XMP_SUBJECT = Regex("<([A-Za-z0-9_]+:)?subject(?:\\s[^>]*)?>(.*?)</([A-Za-z0-9_]+:)?subject>", RegexOption.DOT_MATCHES_ALL)

    private val XMP_REGIONS = Regex("<([A-Za-z0-9_]+:)?Regions(?:\\s[^>]*)?>(.*?)</([A-Za-z0-9_]+:)?Regions>", RegexOption.DOT_MATCHES_ALL)

    private val XMP_PERSONS = Regex("<([A-Za-z0-9_]+:)?PersonInImage[^>]*>(.*?)</([A-Za-z0-9_]+:)?PersonInImage>", RegexOption.DOT_MATCHES_ALL)

    internal fun openExif(context: Context, uri: Uri): ExifInterface? {
        val resolver = context.contentResolver
        val canReadLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (canReadLocation) {
            try {
                resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { return ExifInterface(it) }
            } catch (e: Exception) {
            }
        }
        return try {
            resolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun takenAt(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        return try {
            if (offset != null && Regex("^[+-]\\d{2}:\\d{2}$").matches(offset)) {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse(raw.trim() + offset)?.time
            } else {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())?.time
            }
        } catch (e: Exception) {
            null
        }?.takeIf { it > 0 }
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    private fun formatExposure(seconds: Double): String =
        if (seconds >= 1) "${(seconds * 10).roundToInt() / 10.0}s" else "1/${(1 / seconds).roundToInt()}"

    private fun valid(value: Double, limit: Double): Boolean = !value.isNaN() && value >= -limit && value <= limit
}
