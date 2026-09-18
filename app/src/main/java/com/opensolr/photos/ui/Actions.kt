package com.opensolr.photos.ui

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

/**
 * Things the UI hands off to other apps, and the formats it shows numbers and dates in.
 */
/**
 * One group of the grid as the phone knows it from its own copy of the index: a year, a month in
 * it, or a day in that month, with how many photos it holds and the stretch of time it covers.
 */
data class DateGroup(
    val level: Int,
    /** What the group is, unchanging, and what its folded state is kept by. */
    val name: String,
    /** What the heading says. */
    val text: String,
    val count: Int,
    val from: Long,
    val to: Long,
)

object Actions {

    const val PRICING_URL = "https://opensolr.com/pricing"
    const val DASHBOARD_URL = "https://opensolr.com/admin/solr_manager/dashboard"
    const val PROJECT_URL = "https://opensolr.com/opensolr-photos"

    /**
     * Opens [url] in the default browser, in a new tab.
     */
    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser is installed.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The control panel page of the index on opensolr.com.
     */
    fun indexPanelUrl(indexName: String): String =
        "https://opensolr.com/admin/solr_manager/tools/" + Uri.encode(indexName)

    /**
     * Opens a result in the phone's gallery app (Google Photos, or the phone's own gallery).
     * When the MediaStore id stored in the index went stale, the photo is looked up again by
     * its path; when it is gone from the phone, the user is told the next Re-Sync removes it.
     */
    fun openPhoto(context: Context, hit: PhotoHit) {
        val mediaId = when {
            hit.mediaId > 0 && MediaScanner.exists(context, hit.mediaId) -> hit.mediaId
            else -> MediaScanner.findByPath(context, hit.path)?.mediaId
        }
        if (mediaId == null) {
            Toast.makeText(context, "This photo is no longer on your phone. The next Re-Sync removes it from your index.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, hit.mime.ifBlank { "image/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No gallery app on this phone can open photos.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The MediaStore content URIs of [hits] that are still on the phone, looked up by path when
     * the id stored in the index went stale.
     */
    fun contentUris(context: Context, hits: List<PhotoHit>): List<Uri> {
        if (hits.isEmpty()) return emptyList()
        // Which of the remembered media ids still exist, asked in ONE query for the whole batch,
        // not one per photo: tagging a thousand photos used to mean a thousand round trips to
        // MediaStore before a single word was written (Cip, 2026-09-18).
        val wanted = hits.mapNotNull { it.mediaId.takeIf { id -> id > 0 } }.toSet()
        val alive = MediaScanner.existing(context, wanted)
        return hits.mapNotNull { hit ->
            val mediaId = when {
                hit.mediaId > 0 && hit.mediaId in alive -> hit.mediaId
                // Only a photo whose row has moved costs a lookup of its own, by path.
                else -> MediaScanner.findByPath(context, hit.path)?.mediaId
            }
            mediaId?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        }
    }

    /**
     * The same as [contentUris], but keyed by photo, for a caller that has to write to each file in
     * turn and must not look each one up on its own.
     */
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

    /**
     * Hands the selected photos to whatever the user shares with. The photos themselves are
     * shared, straight from the phone; nothing goes through Opensolr.
     */
    fun sharePhotos(context: Context, hits: List<PhotoHit>) {
        val uris = ArrayList(contentUris(context, hits))
        if (uris.isEmpty()) {
            Toast.makeText(context, "These photos are no longer on your phone.", Toast.LENGTH_LONG).show()
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
            context.startActivity(Intent.createChooser(intent, "Share photos").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nothing on this phone can share photos.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asks Android to delete [uris]. From Android 11 the system itself shows the confirmation
     * and does the deleting, so the app never removes a photo on its own; below that, the app
     * deletes what it has permission to and returns how many went.
     */
    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } else {
            uris.forEach {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (e: Exception) {
                    // A photo another app owns cannot be deleted without the system dialog.
                }
            }
            null
        }
    }

    /**
     * Opens a "lat,lon" location in the maps app.
     */
    fun openMap(context: Context, location: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(location))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app is installed.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * mm/dd/yyyy hh:mm:ss in the phone's time zone, from epoch millis.
     */
    fun formatDate(millis: Long): String = stamp.get()!!.format(millis)

    /** One formatter per thread, kept: building one per call showed up on the grid. */
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US) }
    private val monthYear = ThreadLocal.withInitial { SimpleDateFormat("MMM'.' yyyy", Locale.US) }
    private val yearOnly = ThreadLocal.withInitial { SimpleDateFormat("yyyy", Locale.US) }
    private val dayKeyFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val monthKeyFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM", Locale.US) }
    private val fullDay = ThreadLocal.withInitial { SimpleDateFormat("EEE'.' MMM'.' d yyyy", Locale.US) }

    /**
     * mm/dd/yyyy hh:mm:ss in the phone's time zone, from a Solr UTC date.
     */
    fun formatSolrDate(value: String?): String? = solrDateMillis(value)?.let { formatDate(it) }

    /**
     * A Solr UTC date as epoch millis, or null when it is missing or malformed.
     */
    fun solrDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        // Read digit by digit instead of through a date formatter. This is called for every photo
        // on the grid, on every rebuild of the rows, and a formatter built and thrown away each
        // time was the single most expensive thing in drawing a big library (Cip, 2026-09-18).
        // Shape: yyyy-MM-ddTHH:mm:ssZ, with optional fractional seconds before the Z.
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
        // Days since the epoch, by the civil-from-days algorithm: no calendar object, no time zone
        // lookup, no allocation.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L
    }

    /**
     * The slow path, for a date that is not in the shape above: a formatter, as before.
     */
    private fun legacyDateMillis(value: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value.replace(Regex("\\.\\d+Z$"), "Z"))?.time
    } catch (e: Exception) {
        null
    }

    /**
     * The heading a photo taken at [millis] belongs under, in the phone's time zone: the day
     * for the last week ("Today", "Yesterday", "Mon. Sep. 14 2026") and the month before that
     * ("Jun. 2026"). Every heading carries its year, so none can be read as another year's.
     */
    fun dateHeading(millis: Long): String {
        val now = Calendar.getInstance()
        val taken = Calendar.getInstance().apply { timeInMillis = millis }
        val startOfToday = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val daysAgo = ((startOfToday.timeInMillis - taken.timeInMillis) / 86_400_000L).toInt()
        return when {
            taken.timeInMillis >= startOfToday.timeInMillis -> "Today"
            daysAgo < 1 -> "Yesterday"
            daysAgo < 6 -> fullDay.get()!!.format(millis)
            else -> monthYear.get()!!.format(millis)
        }
    }

    /**
     * True when [millis] falls in the stretch [dateHeading] already names by the day itself -
     * Today, Yesterday, or a weekday in the last six days. Those headings are days, so they get
     * no days of their own underneath.
     */
    fun isRecentDay(millis: Long): Boolean {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return millis >= startOfToday || (startOfToday - millis) / 86_400_000L < 6
    }

    /**
     * The day a photo belongs to, as a key that never changes wording: 2025-09-16. The heading
     * on screen is [dayHeading]; this is what the folded/selected state is kept by, because a
     * key built from the words on screen breaks the moment the words change.
     */
    fun dayKey(millis: Long): String = dayKeyFormat.get()!!.format(millis)

    /**
     * A day, whole: "Sun. Mar. 15 2026" (Cip, 2026-09-18). A heading scrolled far from its
     * month and year still says which day it is.
     */
    fun dayHeading(millis: Long): String = fullDay.get()!!.format(millis)

    /**
     * The year a photo belongs to, as the heading says it and as its key: "2016".
     */
    fun yearHeading(millis: Long): String = yearOnly.get()!!.format(millis)

    /**
     * A month with its year: "Jun. 2026" (Cip, 2026-09-18).
     */
    fun monthHeading(millis: Long): String = monthYear.get()!!.format(millis)

    /**
     * The month a photo belongs to, as a key that never changes wording: 2016-01.
     */
    fun monthKey(millis: Long): String = monthKeyFormat.get()!!.format(millis)

    /**
     * The whole year [millis] falls in, by the same reckoning as [daySpan].
     */
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

    /**
     * The whole day [millis] falls in, from its first instant to its last, in the phone's own time
     * zone - the same one the headings are written in, so a tick on "Tuesday 16" takes exactly the
     * photos written under it.
     */
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

    /**
     * The whole month [millis] falls in, by the same reckoning as [daySpan].
     */
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

    /**
     * A count short enough to always fit a line of buttons: 842, 1.2K, 23K, 1.4M (Cip, 2026-09-17).
     */
    fun formatCompact(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 10_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).replace(".0K", "K")
        value < 1_000_000 -> "${value / 1_000}K"
        value < 10_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
        else -> "${value / 1_000_000}M"
    }

    /**
     * 12,345
     */
    fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

    /**
     * A file's size in the unit that suits it, kilobytes at the smallest: "812 KB", "4.2 MB",
     * "1.49 GB". Never "10000 KB" (Cip, 2026-09-16).
     */
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

    /**
     * Megabytes as "812 MB" or "1.4 GB".
     */
    fun formatMb(mb: Double): String =
        if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1000) else String.format(Locale.US, "%.0f MB", mb)
}
