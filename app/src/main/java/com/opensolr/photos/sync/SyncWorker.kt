package com.opensolr.photos.sync

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.media.MediaScanner
import java.util.concurrent.atomic.AtomicBoolean

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        if (!running.compareAndSet(false, true)) return Result.success()
        stopRequested.set(false)
        try {
            val prefs = AppPrefs(applicationContext)
            if (SyncScheduler.isWatchRun(tags)) {

                if (!touchesChosenFolders(prefs)) return Result.success()
                val since = System.currentTimeMillis() - prefs.lastWatchSyncAt
                if (since < SyncScheduler.WATCH_MIN_INTERVAL_MS) {
                    SyncScheduler.runLater(applicationContext, SyncScheduler.WATCH_MIN_INTERVAL_MS - since)
                    return Result.success()
                }
                prefs.lastWatchSyncAt = System.currentTimeMillis()
            }
            promote("Preparing", 0, 0)
            val report = SyncEngine(applicationContext, unlimited = SyncScheduler.mayReadUnlimited(tags)).run { progress ->
                setProgress(workDataOf(KEY_PHASE to progress.phase, KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                val now = System.currentTimeMillis()
                if (now - lastNotified > 1500) {
                    lastNotified = now
                    val text = if (progress.total > 0) "${progress.phase}: ${progress.done} of ${progress.total}" else progress.phase
                    promote(text, progress.done, progress.total)
                }
            }
            prefs.lastReport = report

            if (report.status == "retry_later") return Result.retry()

            if (report.status == "waiting_charger") SyncScheduler.runWhenCharging(applicationContext)
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)

            if (AppPrefs(applicationContext).session != null) SyncScheduler.watchMedia(applicationContext)
        }
    }

    private fun touchesChosenFolders(prefs: AppPrefs): Boolean =
        MediaScanner.touchesFolders(applicationContext, triggeredContentUris, prefs.folders, prefs.folderStamp)

    private suspend fun promote(text: String, done: Int, total: Int) {
        val notification = Notifier.progress(applicationContext, AppText.s(R.string.sy_syncing_photos), text, done, total)
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

        val running = AtomicBoolean(false)

        val stopRequested = AtomicBoolean(false)
    }
}
