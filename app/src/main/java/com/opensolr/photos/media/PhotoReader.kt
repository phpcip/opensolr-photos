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
    private const val JPEG_QUALITY = 85

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

    fun fileMd5(context: Context, photo: LocalPhoto): String? = fileMd5(context, photo.uri)

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

    fun copyForIngest(context: Context, photo: LocalPhoto, place: com.opensolr.photos.data.PhotoCache.SetPlace? = null): ByteArray? {
        val exif = openExif(context, photo.uri)
        val jpeg = shrinkForClip(context, photo.uri, exif?.rotationDegrees ?: 0) ?: return null

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

    fun canWriteExif(mime: String): Boolean = mime in setOf("image/jpeg", "image/png", "image/webp")

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

    fun writeGps(context: Context, uri: Uri, mime: String, lat: Double, lon: Double): Boolean {
        if (!canWriteExif(mime)) return false
        if (!valid(lat, 90.0) || !valid(lon, 180.0)) return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(lat, lon)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                exif.saveAttributes()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
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

    private val CARRIED_EXIF = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    private val XMP_LI = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)

    fun writeXmp(
        context: Context,
        uri: Uri,
        mime: String,
        persons: List<String>?,
        tags: List<String>?,
        meaning: String? = null,
        clearMeaning: Boolean = false,
    ): Boolean {
        if (mime !in setOf("image/jpeg", "image/png", "image/webp")) return false
        if (persons == null && tags == null && meaning == null && !clearMeaning) return true
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                var packet = exif.getAttributeBytes(ExifInterface.TAG_XMP)?.let { String(it, Charsets.UTF_8) }
                if (persons != null) {
                    packet = withProperty(packet, XMP_PERSONS, bag("Iptc4xmpExt:PersonInImage", "xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\"", persons))
                }
                if (tags != null) {
                    packet = withProperty(packet, XMP_SUBJECT, bag("dc:subject", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"", tags))
                    packet = withProperty(packet, XMP_OPENSOLR_TAGS, bag("opensolr:Tags", "xmlns:opensolr=\"$OPENSOLR_NS\"", tags))
                }

                if (meaning != null || clearMeaning) {
                    val text = meaning?.trim()?.take(MEANING_MAX_CHARS).orEmpty()
                    val property = if (text.isEmpty()) "" else "<opensolr:Meaning xmlns:opensolr=\"$OPENSOLR_NS\">${escapeXml(text)}</opensolr:Meaning>"
                    packet = withProperty(packet, XMP_OPENSOLR_MEANING, property)
                }
                exif.setAttribute(ExifInterface.TAG_XMP, packet)
                exif.saveAttributes()
                if (mime == "image/jpeg" && packet != null) syncJpegXmpSegment(pfd.fileDescriptor, packet)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ExifInterface reads a JPEG's standalone XMP segment but writes only the EXIF copy: the standalone one gets the same packet
    private fun syncJpegXmpSegment(fd: java.io.FileDescriptor, packet: String) {
        val input = java.io.FileInputStream(fd).channel
        input.position(0)
        val bytes = ByteArray(input.size().toInt())
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining() && input.read(buffer) >= 0) { }
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        var at = 2
        while (at + 4 <= bytes.size && bytes[at] == 0xFF.toByte()) {
            val marker = bytes[at + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return
            val length = ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val end = at + 2 + length
            if (length < 2 || end > bytes.size) return
            val isXmp = marker == 0xE1 && length - 2 >= header.size &&
                header.indices.all { bytes[at + 4 + it] == header[it] }
            if (isXmp) {
                val payload = header + packet.toByteArray(Charsets.UTF_8)
                if (payload.size + 2 > 0xFFFF) return
                val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) + payload
                val rewritten = bytes.copyOfRange(0, at) + segment + bytes.copyOfRange(end, bytes.size)
                val output = java.io.FileOutputStream(fd).channel
                output.truncate(0)
                output.position(0)
                val out = java.nio.ByteBuffer.wrap(rewritten)
                while (out.hasRemaining()) output.write(out)
                output.force(true)
                return
            }
            at = end
        }
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

    private fun bag(name: String, namespace: String, values: List<String>): String =
        if (values.isEmpty()) "" else
            "<$name $namespace><rdf:Bag>" + values.joinToString("") { "<rdf:li>${escapeXml(it)}</rdf:li>" } + "</rdf:Bag></$name>"

    private fun withProperty(packet: String?, existing: Regex, property: String): String {
        val base = packet?.takeIf { it.contains("</rdf:Description>") }
            ?: return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\">$property</rdf:Description></rdf:RDF></x:xmpmeta>"
        val cleared = existing.replace(base, "")
        val at = cleared.indexOf("</rdf:Description>")
        return cleared.substring(0, at) + property + cleared.substring(at)
    }

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

    private val XMP_PERSONS = Regex("<([A-Za-z0-9_]+:)?PersonInImage[^>]*>(.*?)</([A-Za-z0-9_]+:)?PersonInImage>", RegexOption.DOT_MATCHES_ALL)

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
