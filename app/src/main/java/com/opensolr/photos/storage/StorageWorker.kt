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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StorageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notice by lazy { com.opensolr.photos.sync.RunNotice(this, AppText.s(R.string.stg_title), Notifier.STORAGE_ID, Notifier.STORAGE_STATE_ID) }

    // the work, with a watch beside it that speaks up in the notification when nothing moves
    override suspend fun doWork(): Result = kotlinx.coroutines.coroutineScope {
        val watch = launch { notice.watch() }
        try { work() } finally { watch.cancel() }
    }

    private suspend fun work(): Result {
        val prefs = AppPrefs(applicationContext)
        if (prefs.storagePaused || prefs.session == null) return Result.success()
        // stopped again and again: left alone, said once, never round and round
        if (notice.triesUsedUp) { notice.gaveUp(); return Result.failure() }
        if (!running.compareAndSet(false, true)) return Result.success()
        stopRequested.set(false)
        var ended = false
        try {
            notice.began()
            notice.step(AppText.s(R.string.stg_deletions))
            runCatching { Deletions.push(applicationContext); Deletions.pull(applicationContext) }
            val report = StorageEngine(applicationContext).run { progress ->
                setProgress(workDataOf(KEY_PHASE to progress.phase, KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                notice.step(progress.phase, progress.done, progress.total)
            }
            prefs.storageReport = report
            ended = true
            notice.step(AppText.s(R.string.stg_checking))
            runCatching { LocalFiles.refresh(applicationContext) }
            when (report.status) {
                "waiting_battery" -> StorageScheduler.runLater(applicationContext, BATTERY_RETRY_MS)
                "waiting_wifi" -> StorageScheduler.runLater(applicationContext, NETWORK_RETRY_MS)
                "failed" -> if (report.uploaded + report.restored > 0) StorageScheduler.runLater(applicationContext, FAIL_RETRY_MS)
            }
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)
            // ended by the system, not by the owner and not by its own end: said, with the reason
            if (!ended && isStopped && !stopRequested.get()) notice.stopped()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notice.info(AppText.s(R.string.stg_preparing))

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

data class StorageStatus(val running: Boolean, val queued: Boolean, val phase: String, val done: Int, val total: Int, val waiting: Boolean = false, val attempt: Int = 0, val nextAt: Long = 0L) {
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
        val manager = WorkManager.getInstance(context)
        // a run in progress is kept; one left waiting (a backoff after the app was killed mid-run) starts now
        Thread {
            val runningNow = runCatching { manager.getWorkInfosForUniqueWork(NOW).get().any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
            manager.enqueueUniqueWork(NOW, if (runningNow || StorageWorker.running.get()) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }.start()
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
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).combine(com.opensolr.photos.data.NetHold.held) { infos, held ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val waitingRun = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED && NOW in it.tags }
            val queuedNow = waitingRun != null
            StorageStatus(
                running = running != null,
                queued = queuedNow && !held,
                waiting = queuedNow && held,
                attempt = waitingRun?.runAttemptCount ?: 0,
                nextAt = waitingRun?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE } ?: 0L,
                phase = running?.progress?.getString(StorageWorker.KEY_PHASE) ?: "",
                done = running?.progress?.getInt(StorageWorker.KEY_DONE, 0) ?: 0,
                total = running?.progress?.getInt(StorageWorker.KEY_TOTAL, 0) ?: 0,
            )
        }
}
