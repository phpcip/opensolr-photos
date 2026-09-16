package com.opensolr.photos.media

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

/**
 * Camera metadata of a photo, read on the phone.
 *
 * It has to be read here: the copy sent to Opensolr to be read into words is a small
 * re-encoded JPEG with no metadata at all, so nothing about the camera or the place ever
 * travels with the picture itself.
 */
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

/**
 * Reads photos: their metadata, and a small upright JPEG copy for the CLIP reader.
 */
object PhotoReader {

    /**
     * Long edge of the copy sent to be read into words. CLIP looks at 224x224 pixels, so a
     * 640px copy loses nothing it would use, while sending a fraction of the bytes of the
     * original over the phone's connection.
     */
    private const val CLIP_EDGE_PX = 640
    private const val JPEG_QUALITY = 85

    /**
     * Decodes [uri] at a reduced size, turns it upright according to its EXIF orientation,
     * scales the long edge to [CLIP_EDGE_PX] and encodes it as JPEG. Returns null when the
     * file cannot be decoded (a damaged file, or a format this phone cannot read).
     */
    fun shrinkForClip(context: Context, uri: Uri, rotationDegrees: Int): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CLIP_EDGE_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > CLIP_EDGE_PX) CLIP_EDGE_PX.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val upright = if (matrix.isIdentity) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    /**
     * The copy of [photo] that goes to Opensolr for indexing: the 640 px upright JPEG, carrying
     * the original's EXIF (when, which camera, where, exposure), so the server reads it there
     * and the phone does nothing else with it. Orientation is set upright because the pixels
     * already are. Null when the file cannot be decoded.
     */
    /**
     * The md5 of the photo file itself, lower-case hex, or null when it cannot be read.
     *
     * Of the original bytes, not of the copy sent to be read into words: that copy is
     * re-encoded and carries none of the original's identity. This is what tells two files that
     * are byte for byte the same apart from everything that merely looks alike (Cip,
     * 2026-09-16). Streamed in blocks, so a large photo never sits in memory whole.
     */
    fun fileMd5(context: Context, photo: LocalPhoto): String? = try {
        context.contentResolver.openInputStream(photo.uri)?.use { input ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    fun copyForIngest(context: Context, photo: LocalPhoto): ByteArray? {
        val exif = openExif(context, photo.uri)
        val jpeg = shrinkForClip(context, photo.uri, exif?.rotationDegrees ?: 0) ?: return null
        if (exif == null) return jpeg
        val file = java.io.File.createTempFile("ingest", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg)
            val out = ExifInterface(file.absolutePath)
            for (tag in CARRIED_EXIF) {
                exif.getAttribute(tag)?.let { out.setAttribute(tag, it) }
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

    /**
     * Reads the EXIF metadata of [photo]. GPS is only readable when the user granted
     * "access media location"; without it Android removes the coordinates, and the photo is
     * indexed without a place.
     */
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

    /**
     * Opens the EXIF block, asking for the original bytes (with location) when the permission
     * allows it and falling back to the redacted stream otherwise.
     */
    /** The EXIF tags that travel with the copy: time, camera, exposure, position. */
    private val CARRIED_EXIF = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        // The whole XMP packet, for XMP:PersonInImage - the names of the people in the photo,
        // written by whatever recognised the faces (Google Photos, Lightroom, digiKam). The
        // server reads them into persons_t. Carried whole because ExifInterface has no tag for
        // a single XMP property.
        ExifInterface.TAG_XMP,
    )

    private fun openExif(context: Context, uri: Uri): ExifInterface? {
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

    /**
     * DateTimeOriginal (or DateTime) in millis, honouring the EXIF time-zone offset when the
     * camera wrote one and the phone's own zone otherwise.
     */
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

    /**
     * Solr date format, always UTC.
     */
    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    /**
     * "1/250" for fast shutter speeds, "2s" for long exposures.
     */
    private fun formatExposure(seconds: Double): String =
        if (seconds >= 1) "${(seconds * 10).roundToInt() / 10.0}s" else "1/${(1 / seconds).roundToInt()}"

    /**
     * A coordinate within its range.
     */
    private fun valid(value: Double, limit: Double): Boolean = !value.isNaN() && value >= -limit && value <= limit
}
