package com.opensolr.photos.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opensolr.photos.data.SyncSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

data class SyncStatus(
    val running: Boolean,
    val queued: Boolean,
    val phase: String,
    val done: Int,
    val total: Int,
) {

    val busy: Boolean get() = running || queued
}

object SyncScheduler {

    private const val TAG = "opensolr-sync"
    private const val NOW = "opensolr-sync-now"
    private const val PERIODIC = "opensolr-sync-periodic"
    private const val MEDIA = "opensolr-sync-media"
    private const val LATER = "opensolr-sync-later"
    private const val CHARGING = "opensolr-sync-charging"

    const val WATCH_MIN_INTERVAL_MS = 15 * 60 * 1000L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(NOW)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun watchMedia(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(60, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(MEDIA)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MEDIA, ExistingWorkPolicy.KEEP, request)
    }

    fun runLater(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(LATER)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.KEEP, request)
    }

    fun runWhenCharging(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(CHARGING)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, ExistingWorkPolicy.KEEP, request)
    }

    fun isWatchRun(tags: Set<String>): Boolean = MEDIA in tags

    fun mayReadUnlimited(tags: Set<String>): Boolean = CHARGING in tags

    fun applySchedule(context: Context, schedule: SyncSchedule) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(schedule.days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(schedule.days, TimeUnit.DAYS)
            .addTag(TAG)
            .addTag(PERIODIC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    fun restartNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(NOW)
            .build()
        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        watchMedia(context)
    }

    fun stopNow(context: Context) {
        val manager = WorkManager.getInstance(context)

        manager.cancelAllWorkByTag(NOW)
        manager.cancelAllWorkByTag(LATER)
        manager.cancelAllWorkByTag(CHARGING)
        manager.cancelAllWorkByTag(MEDIA)
        watchMedia(context)
    }

    fun status(context: Context): Flow<SyncStatus> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }

            val queuedNow = infos.any { it.state == WorkInfo.State.ENQUEUED && NOW in it.tags }
            SyncStatus(
                running = running != null,
                queued = queuedNow,
                phase = running?.progress?.getString(SyncWorker.KEY_PHASE) ?: "",
                done = running?.progress?.getInt(SyncWorker.KEY_DONE, 0) ?: 0,
                total = running?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0,
            )
        }
}
