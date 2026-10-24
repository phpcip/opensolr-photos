package com.opensolr.photos.storage

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.sync.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StorageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val prefs = AppPrefs(applicationContext)
        if (prefs.storagePaused || prefs.session == null) return Result.success()
        if (!running.compareAndSet(false, true)) return Result.success()
        stopRequested.set(false)
        try {
            promote(AppText.s(R.string.stg_preparing), 0, 0)
            val report = StorageEngine(applicationContext).run { progress ->
                setProgress(workDataOf(KEY_PHASE to progress.phase, KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                val now = System.currentTimeMillis()
                if (now - lastNotified > 1500) {
                    lastNotified = now
                    val text = if (progress.total > 0) "${progress.phase}: ${progress.done} of ${progress.total}" else progress.phase
                    promote(text, progress.done, progress.total)
                }
            }
            prefs.storageReport = report
            runCatching { LocalFiles.refresh(applicationContext) }
            when (report.status) {
                "waiting_battery" -> StorageScheduler.runLater(applicationContext, BATTERY_RETRY_MS)
                "waiting_wifi" -> StorageScheduler.runLater(applicationContext, NETWORK_RETRY_MS)
                "failed" -> if (report.uploaded + report.restored > 0) StorageScheduler.runLater(applicationContext, FAIL_RETRY_MS)
            }
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(AppText.s(R.string.stg_preparing), 0, 0)

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val notification = Notifier.progress(applicationContext, AppText.s(R.string.stg_title), text, done, total, id = Notifier.STORAGE_ID)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.STORAGE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.STORAGE_ID, notification)
        }
    }

    private suspend fun promote(text: String, done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(text, done, total))
        } catch (e: IllegalStateException) {
        }
    }

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STATUS = "status"

        private const val BATTERY_RETRY_MS = 15 * 60 * 1000L
        private const val NETWORK_RETRY_MS = 30 * 60 * 1000L
        private const val FAIL_RETRY_MS = 30 * 60 * 1000L

        val running = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
    }
}

data class StorageStatus(val running: Boolean, val queued: Boolean, val phase: String, val done: Int, val total: Int) {
    val busy: Boolean get() = running || queued
}

object StorageScheduler {

    private const val TAG = "opensolr-storage"
    private const val NOW = "opensolr-storage-now"
    private const val LATER = "opensolr-storage-later"
    private const val PERIODIC = "opensolr-storage-periodic"

    /** Wi-Fi (any unmetered network) unless the owner allowed mobile data. */
    fun network(context: Context): NetworkType =
        if (AppPrefs(context).useMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

    private fun constraints(context: Context) = Constraints.Builder().setRequiredNetworkType(network(context)).build()

    fun runNow(context: Context) {
        if (AppPrefs(context).storagePaused) return
        val request = OneTimeWorkRequestBuilder<StorageWorker>()
            .setConstraints(constraints(context))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag(NOW)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun runLater(context: Context, delayMs: Long) {
        if (AppPrefs(context).storagePaused) return
        val request = OneTimeWorkRequestBuilder<StorageWorker>()
            .setConstraints(constraints(context))
            .setInitialDelay(delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag(LATER)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, request)
    }

    /** Once a day on its own, besides the run after every sync. */
    fun applySchedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<StorageWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(context))
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag(TAG).addTag(PERIODIC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun stopNow(context: Context, pause: Boolean) {
        if (pause) AppPrefs(context).storagePaused = true
        StorageWorker.stopRequested.set(true)
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(NOW)
        manager.cancelAllWorkByTag(LATER)
    }

    suspend fun stopAndWait(context: Context, pause: Boolean, timeoutMs: Long = 90_000L) {
        stopNow(context, pause)
        val until = System.currentTimeMillis() + timeoutMs
        while (StorageWorker.running.get() && System.currentTimeMillis() < until) kotlinx.coroutines.delay(200)
    }

    fun resume(context: Context) {
        AppPrefs(context).storagePaused = false
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    fun status(context: Context): Flow<StorageStatus> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            StorageStatus(
                running = running != null,
                queued = infos.any { it.state == WorkInfo.State.ENQUEUED && NOW in it.tags },
                phase = running?.progress?.getString(StorageWorker.KEY_PHASE) ?: "",
                done = running?.progress?.getInt(StorageWorker.KEY_DONE, 0) ?: 0,
                total = running?.progress?.getInt(StorageWorker.KEY_TOTAL, 0) ?: 0,
            )
        }
}
