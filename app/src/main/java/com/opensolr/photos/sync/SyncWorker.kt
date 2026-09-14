package com.opensolr.photos.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opensolr.photos.data.AppPrefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [SyncEngine] in the background, for the Force Re-Sync button and for the weekly or
 * monthly schedule alike.
 *
 * Only one sync can ever run at a time: WorkManager keeps each unique work name single, and
 * [running] stops a scheduled run from starting while a forced one is still going. While it
 * runs it is a foreground data-sync job with a progress notification, so Android does not stop
 * it half way; if the system refuses to promote it (a scheduled run started while the app is in
 * the background on newer Android), it simply continues as normal background work.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastNotified = 0L

    /**
     * One sync run.
     */
    override suspend fun doWork(): Result {
        if (!running.compareAndSet(false, true)) return Result.success()
        try {
            promote("Preparing", 0, 0)
            val report = SyncEngine(applicationContext).run { progress ->
                setProgress(workDataOf(KEY_PHASE to progress.phase, KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                val now = System.currentTimeMillis()
                if (now - lastNotified > 1500) {
                    lastNotified = now
                    val text = if (progress.total > 0) "${progress.phase}: ${progress.done} of ${progress.total}" else progress.phase
                    promote(text, progress.done, progress.total)
                }
            }
            AppPrefs(applicationContext).lastReport = report
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)
            // Arm the photo watch again: its trigger is consumed by the run it started.
            if (AppPrefs(applicationContext).session != null) SyncScheduler.watchMedia(applicationContext)
        }
    }

    /**
     * Shows or updates the foreground progress notification.
     */
    private suspend fun promote(text: String, done: Int, total: Int) {
        val notification = Notifier.progress(applicationContext, "Syncing your photos", text, done, total)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.PROGRESS_ID, notification)
        }
        try {
            setForeground(info)
        } catch (e: IllegalStateException) {
        }
    }

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STATUS = "status"

        /** True while any sync runs in this process. */
        val running = AtomicBoolean(false)
    }
}
