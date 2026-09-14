package com.opensolr.photos.sync

import android.content.Context
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

/**
 * What the Sync screen and the discreet indicator need to know about background work.
 *
 * @property running  a sync is running right now
 * @property queued   a forced sync is waiting to start (for example for a network connection)
 */
data class SyncStatus(
    val running: Boolean,
    val queued: Boolean,
    val phase: String,
    val done: Int,
    val total: Int,
) {
    /** True when a new sync must not be started. */
    val busy: Boolean get() = running || queued
}

/**
 * Starts and schedules sync runs through WorkManager.
 */
object SyncScheduler {

    private const val TAG = "opensolr-sync"
    private const val NOW = "opensolr-sync-now"
    private const val PERIODIC = "opensolr-sync-periodic"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Starts a sync now. Does nothing when one is already queued or running under the same name.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(NOW)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Sets the scheduled Re-Sync to run every week or every month, replacing any previous schedule.
     */
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

    /**
     * Cancels every sync, queued or scheduled (used on sign-out).
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    /**
     * Live status of sync work.
     */
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
