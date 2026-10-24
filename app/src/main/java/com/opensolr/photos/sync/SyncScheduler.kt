package com.opensolr.photos.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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

    const val WATCH_MIN_INTERVAL_MS = 15 * 60 * 1000L

    const val BATTERY_RETRY_MS = 15 * 60 * 1000L

    /** Wi-Fi (any unmetered network) unless the owner allowed mobile data. */
    private fun network(context: Context): NetworkType =
        if (com.opensolr.photos.data.AppPrefs(context).useMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

    private fun constraints(context: Context) = Constraints.Builder().setRequiredNetworkType(network(context)).build()

    /**
     * Everything stopped: the running sync is told to stop and every queued sync and face job is cancelled.
     * With [pause], nothing starts again on its own until the owner presses a button.
     */
    fun haltAll(context: Context, pause: Boolean) {
        if (pause) com.opensolr.photos.data.AppPrefs(context).syncPaused = true
        SyncWorker.stopRequested.set(true)
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        FaceWorker.cancelAll(context)
    }

    /** [haltAll], then waits (bounded) until the sync worker has really finished its last step. */
    suspend fun stopAndWait(context: Context, pause: Boolean, timeoutMs: Long = 90_000L) {
        haltAll(context, pause)
        val until = System.currentTimeMillis() + timeoutMs
        while (SyncWorker.running.get() && System.currentTimeMillis() < until) kotlinx.coroutines.delay(200)
    }

    /** The owner started something: the pause is over. */
    fun resume(context: Context) {
        com.opensolr.photos.data.AppPrefs(context).syncPaused = false
    }

    private fun paused(context: Context): Boolean = com.opensolr.photos.data.AppPrefs(context).syncPaused

    fun runNow(context: Context) {
        if (paused(context)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            // started by the owner or by a new photo: runs at once, not when the system gets to it
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(NOW)
            .build()
        val manager = WorkManager.getInstance(context)
        // a sync in progress is kept; one left waiting (a backoff after the app was killed mid-run) starts now
        Thread {
            val runningNow = runCatching { manager.getWorkInfosForUniqueWork(NOW).get().any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
            manager.enqueueUniqueWork(NOW, if (runningNow) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }.start()
    }

    fun watchMedia(context: Context) {
        if (paused(context)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network(context))
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .apply {
                        val prefs = com.opensolr.photos.data.AppPrefs(context)
                        if (prefs.includeVideos) addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        if (prefs.includePdfs) addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                    }
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
        if (paused(context)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            .setInitialDelay(delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(LATER)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.KEEP, request)
    }

    fun isWatchRun(tags: Set<String>): Boolean = MEDIA in tags

    fun applySchedule(context: Context, schedule: SyncSchedule) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(schedule.days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network(context))
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
        if (paused(context)) return
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
