package com.opensolr.photos.sync

import com.opensolr.photos.AppText
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

object Notifier {

    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_ALERTS = "alerts"
    const val PROGRESS_ID = 1001
    private const val ALERT_SIGN_IN = 2001
    private const val ALERT_LIMIT = 2002
    private const val ACCOUNT_URL = "https://opensolr.com/admin/solr_manager/dashboard"

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

    fun signInRequired(context: Context) = post(
        context, ALERT_SIGN_IN,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppText.s(R.string.nt_signin_title))
            .setContentText(AppText.s(R.string.nt_signin_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
            .build()
    )

    fun planLimit(context: Context, title: String, text: String, key: String = "") = post(

        context, if (key.isEmpty()) ALERT_LIMIT else ALERT_LIMIT + 1 + (key.hashCode() and 0x7fff),
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAccount(context))
            .build()
    )

    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
        }
    }

    private fun openApp(context: Context, destination: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
        }
        return PendingIntent.getActivity(context, destination.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openAccount(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
