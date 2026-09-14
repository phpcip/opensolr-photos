package com.opensolr.photos.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.search.PhotoHit
import java.text.NumberFormat
import java.text.SimpleDateFormat
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
    fun formatSolrDate(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(value.replace(Regex("\\.\\d+Z$"), "Z"))
            parsed?.let { formatDate(it.time) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 12,345
     */
    fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

    /**
     * Megabytes as "812 MB" or "1.4 GB".
     */
    fun formatMb(mb: Double): String =
        if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1000) else String.format(Locale.US, "%.0f MB", mb)
}
