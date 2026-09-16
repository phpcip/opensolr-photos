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
    fun contentUris(context: Context, hits: List<PhotoHit>): List<Uri> = hits.mapNotNull { hit ->
        val mediaId = when {
            hit.mediaId > 0 && MediaScanner.exists(context, hit.mediaId) -> hit.mediaId
            else -> MediaScanner.findByPath(context, hit.path)?.mediaId
        }
        mediaId?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
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
    fun formatDate(millis: Long): String =
        SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US).format(millis)

    /**
     * mm/dd/yyyy hh:mm:ss in the phone's time zone, from a Solr UTC date.
     */
    fun formatSolrDate(value: String?): String? = solrDateMillis(value)?.let { formatDate(it) }

    /**
     * A Solr UTC date as epoch millis, or null when it is missing or malformed.
     */
    fun solrDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(value.replace(Regex("\\.\\d+Z$"), "Z"))?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The heading a photo taken at [millis] belongs under, in the phone's time zone: the day
     * for the last week ("Today", "Yesterday", "Monday"), the month before that ("June 2026"),
     * and the month with no year while it is the current one.
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
            daysAgo < 6 -> SimpleDateFormat("EEEE", Locale.US).format(millis)
            taken.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("MMMM", Locale.US).format(millis)
            else -> SimpleDateFormat("MMMM yyyy", Locale.US).format(millis)
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
    fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(millis)

    /**
     * A day inside a month: "Tuesday 16". The month is already written above it, so it is not
     * repeated here.
     */
    fun dayHeading(millis: Long): String =
        SimpleDateFormat("EEEE d", Locale.US).format(millis)

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
