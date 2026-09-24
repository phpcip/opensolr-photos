package com.opensolr.photos.ui

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.search.PhotoHit
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class DateGroup(
    val level: Int,

    val name: String,

    val text: String,
    val count: Int,
    val from: Long,
    val to: Long,
)

data class ResultGroup(
    val level: Int,
    val name: String,
    val text: String,
    val ids: List<String>,
)

enum class GroupBy(val key: String, val labelRes: Int) {
    RELEVANCE("relevance", com.opensolr.photos.R.string.group_relevance),
    DATE("date", com.opensolr.photos.R.string.group_date),
    PLACE("place", com.opensolr.photos.R.string.group_place),
    PEOPLE("people", com.opensolr.photos.R.string.group_people),
    TAGS("tags", com.opensolr.photos.R.string.group_tags),

    FOLDER("folder", com.opensolr.photos.R.string.group_folder),
    CAMERA("camera", com.opensolr.photos.R.string.group_camera);

    companion object {

        fun of(key: String?): GroupBy = entries.firstOrNull { it.key == key } ?: RELEVANCE
    }
}

object Actions {

    const val DASHBOARD_URL = "https://opensolr.com/admin/solr_manager/dashboard"
    const val PROJECT_URL = "https://opensolr.com/opensolr-photos"
    const val PRIVACY_URL = "https://opensolr.com/opensolr-photos-docs/privacy"
    const val DELETE_ACCOUNT_URL = "https://opensolr.com/delete-account"

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, AppText.s(R.string.ac_no_browser), Toast.LENGTH_LONG).show()
        }
    }

    fun indexPanelUrl(indexName: String): String =
        "https://opensolr.com/admin/solr_manager/tools/" + Uri.encode(indexName)

    fun openPhoto(context: Context, hit: PhotoHit) {
        val mediaId = when {
            hit.mediaId > 0 && MediaScanner.exists(context, hit.mediaId) -> hit.mediaId
            else -> MediaScanner.findByPath(context, hit.path)?.mediaId
        }
        if (mediaId == null) {
            Toast.makeText(context, AppText.s(R.string.ac_photo_gone), Toast.LENGTH_LONG).show()
            return
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, hit.mime.ifBlank { "image/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, AppText.s(R.string.ac_no_gallery), Toast.LENGTH_LONG).show()
        }
    }

    fun contentUris(context: Context, hits: List<PhotoHit>): List<Uri> {
        if (hits.isEmpty()) return emptyList()

        val wanted = hits.mapNotNull { it.mediaId.takeIf { id -> id > 0 } }.toSet()
        val alive = MediaScanner.existing(context, wanted)
        return hits.mapNotNull { hit ->
            val mediaId = when {
                hit.mediaId > 0 && hit.mediaId in alive -> hit.mediaId

                else -> MediaScanner.findByPath(context, hit.path)?.mediaId
            }
            mediaId?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        }
    }

    fun contentUrisByPhoto(context: Context, hits: List<PhotoHit>): Map<String, Uri> {
        if (hits.isEmpty()) return emptyMap()
        val wanted = hits.mapNotNull { it.mediaId.takeIf { id -> id > 0 } }.toSet()
        val alive = MediaScanner.existing(context, wanted)
        val out = HashMap<String, Uri>(hits.size)
        hits.forEach { hit ->
            val mediaId = when {
                hit.mediaId > 0 && hit.mediaId in alive -> hit.mediaId
                else -> MediaScanner.findByPath(context, hit.path)?.mediaId
            }
            if (mediaId != null) out[hit.id] = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        }
        return out
    }

    fun sharePhotos(context: Context, hits: List<PhotoHit>) {
        val uris = ArrayList(contentUris(context, hits))
        if (uris.isEmpty()) {
            Toast.makeText(context, AppText.s(R.string.ac_photos_gone), Toast.LENGTH_LONG).show()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "image/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, AppText.s(R.string.ac_share_photos)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, AppText.s(R.string.ac_no_share), Toast.LENGTH_LONG).show()
        }
    }

    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } else {
            uris.forEach {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (e: Exception) {

                }
            }
            null
        }
    }

    fun openMap(context: Context, location: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(location))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, AppText.s(R.string.ac_no_maps), Toast.LENGTH_LONG).show()
        }
    }

    fun formatDate(millis: Long): String = stamp.get()!!.format(millis)

    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US) }
    private val monthYear = LocalFormat("MMM'.' yyyy", "MMMyyyy")
    private val yearOnly = ThreadLocal.withInitial { SimpleDateFormat("yyyy", Locale.US) }
    private val dayKeyFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val monthKeyFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM", Locale.US) }
    private val fullDay = LocalFormat("EEE'.' MMM'.' d yyyy", "EEEMMMdyyyy")

    private class LocalFormat(private val english: String, private val skeleton: String) {
        private val held = ThreadLocal<Pair<Locale, SimpleDateFormat>>()

        fun get(): SimpleDateFormat {
            val locale = Locale.getDefault()
            held.get()?.let { (at, format) -> if (at == locale) return format }
            val format = if (locale.language == "en") SimpleDateFormat(english, Locale.US)
            else SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            held.set(locale to format)
            return format
        }
    }

    fun formatSolrDate(value: String?): String? = solrDateMillis(value)?.let { formatDate(it) }

    fun solrDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null

        if (value.length < 20 || value[4] != '-' || value[7] != '-' || value[10] != 'T' ||
            value[13] != ':' || value[16] != ':'
        ) {
            return legacyDateMillis(value)
        }
        fun digits(from: Int, to: Int): Int {
            var n = 0
            for (i in from until to) {
                val c = value[i]
                if (c < '0' || c > '9') return -1
                n = n * 10 + (c - '0')
            }
            return n
        }
        val year = digits(0, 4)
        val month = digits(5, 7)
        val day = digits(8, 10)
        val hour = digits(11, 13)
        val minute = digits(14, 16)
        val second = digits(17, 19)
        if (year < 0 || month < 1 || month > 12 || day < 1 || day > 31 || hour < 0 || minute < 0 || second < 0) {
            return legacyDateMillis(value)
        }

        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L
    }

    private fun legacyDateMillis(value: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value.replace(Regex("\\.\\d+Z$"), "Z"))?.time
    } catch (e: Exception) {
        null
    }

    fun dateHeading(millis: Long): String {
        val now = Calendar.getInstance()
        val taken = Calendar.getInstance().apply { timeInMillis = millis }
        val startOfToday = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val daysAgo = ((startOfToday.timeInMillis - taken.timeInMillis) / 86_400_000L).toInt()
        return when {
            taken.timeInMillis >= startOfToday.timeInMillis -> AppText.s(R.string.ac_today)
            daysAgo < 1 -> AppText.s(R.string.ac_yesterday)
            daysAgo < 6 -> fullDay.get()!!.format(millis)
            else -> monthYear.get()!!.format(millis)
        }
    }

    fun isRecentDay(millis: Long): Boolean {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return millis >= startOfToday || (startOfToday - millis) / 86_400_000L < 6
    }

    fun dayKey(millis: Long): String = dayKeyFormat.get()!!.format(millis)

    fun dayHeading(millis: Long): String = fullDay.get()!!.format(millis)

    fun yearHeading(millis: Long): String = yearOnly.get()!!.format(millis)

    fun monthHeading(millis: Long): String = monthYear.get()!!.format(millis)

    fun monthKey(millis: Long): String = monthKeyFormat.get()!!.format(millis)

    fun yearSpan(millis: Long): Pair<Long, Long> {
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.YEAR, 1) }
        return start.timeInMillis to end.timeInMillis - 1000
    }

    fun daySpan(millis: Long): Pair<Long, Long> {
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }
        return start.timeInMillis to end.timeInMillis - 1000
    }

    fun monthSpan(millis: Long): Pair<Long, Long> {
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis - 1000
    }

    fun formatCompact(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 10_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).replace(".0K", "K")
        value < 1_000_000 -> "${value / 1_000}K"
        value < 10_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
        else -> "${value / 1_000_000}M"
    }

    fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val tb = gb / 1024.0
        return when {
            tb >= 1 -> String.format(Locale.US, "%.2f TB", tb)
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun formatMb(mb: Double): String =
        if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1000) else String.format(Locale.US, "%.0f MB", mb)
}
