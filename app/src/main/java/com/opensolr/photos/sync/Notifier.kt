package com.opensolr.photos.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opensolr.photos.MainActivity
import com.opensolr.photos.R

/**
 * Every notification the app posts.
 *
 * Two channels: a quiet one for sync progress, and an alerts channel for the few things the
 * user has to act on (sign in again, plan limit reached, index recreated). Alerts about plan
 * limits open the Opensolr pricing page in the browser.
 */
object Notifier {

    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_ALERTS = "alerts"
    const val PROGRESS_ID = 1001
    private const val ALERT_SIGN_IN = 2001
    private const val ALERT_LIMIT = 2002
    private const val ALERT_RECREATED = 2003
    const val PRICING_URL = "https://opensolr.com/pricing"

    /**
     * Creates both channels. Safe to call repeatedly.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_sync_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_alerts_description) }
        )
    }

    /**
     * The ongoing notification shown while a sync runs. [total] of 0 shows an indeterminate bar.
     */
    fun progress(context: Context, title: String, text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, done, total <= 0)
            .setContentIntent(openApp(context, MainActivity.DESTINATION_SYNC))
            .build()

    /**
     * The saved sign-in no longer works: the API key was changed or the account was closed.
     */
    fun signInRequired(context: Context) = post(
        context, ALERT_SIGN_IN,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sign in to Opensolr again")
            .setContentText("Your Opensolr sign-in stopped working, so syncing is paused.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
            .build()
    )

    /**
     * The plan ran out of something: disk space, search bandwidth or AI requests. Tapping
     * opens the pricing page.
     */
    fun planLimit(context: Context, title: String, text: String, key: String = "") = post(
        // One notification per situation (key), so a disk warning does not replace an AI one.
        context, if (key.isEmpty()) ALERT_LIMIT else ALERT_LIMIT + 1 + (key.hashCode() and 0x7fff),
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openPricing(context))
            .addAction(0, "Upgrade", openPricing(context))
            .build()
    )

    /**
     * The index had disappeared from the account and was created again, empty.
     */
    fun indexRecreated(context: Context) = post(
        context, ALERT_RECREATED,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your photo index was emptied")
            .setContentText("It was missing from your Opensolr account, so a new one was created and your photos are being synced again.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("It was missing from your Opensolr account, so a new one was created and your photos are being synced again."))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, MainActivity.DESTINATION_SYNC))
            .build()
    )

    /**
     * Posts a notification, silently doing nothing when the user has not allowed notifications.
     */
    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
        }
    }

    /**
     * Opens the app, optionally on a given screen.
     */
    private fun openApp(context: Context, destination: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
        }
        return PendingIntent.getActivity(context, destination.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Opens https://opensolr.com/pricing in the browser.
     */
    private fun openPricing(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRICING_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
