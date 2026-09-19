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
        stopRequested.set(false)
        try {
            val prefs = AppPrefs(applicationContext)
            if (SyncScheduler.isWatchRun(tags)) {
                // Woken by a change in the phone's pictures. Only the owner's folders matter:
                // a screenshot, a chat picture or a gallery thumbnail elsewhere costs nothing,
                // not even a request. And a stream of changes runs one sync per interval, not
                // one every few minutes; the change waits, it is not lost.
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
            // Rate limited: stop now, sleep, and let WorkManager start the run again with its
            // backoff, instead of counting seconds with the phone held awake.
            if (report.status == "retry_later") return Result.retry()
            // The battery ran low with no charger: the run continues once the phone is plugged in.
            if (report.status == "waiting_charger") SyncScheduler.runWhenCharging(applicationContext)
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)
            // Arm the photo watch again: its trigger is consumed by the run it started.
            if (AppPrefs(applicationContext).session != null) SyncScheduler.watchMedia(applicationContext)
        }
    }

    /**
     * True when at least one of the pictures that woke this run is in a folder the owner
     * chose, or when that cannot be told. See [MediaScanner.touchesFolders].
     */
    private fun touchesChosenFolders(prefs: AppPrefs): Boolean =
        MediaScanner.touchesFolders(applicationContext, triggeredContentUris, prefs.folders, prefs.folderStamp)

    /**
     * Shows or updates the foreground progress notification.
     */
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

        /** True while any sync runs in this process. */
        val running = AtomicBoolean(false)

        /**
         * Set when the owner presses Stop. WorkManager's own cancellation cannot interrupt a
         * batch that is already uploading, so the run checks this between batches and gives up
         * cleanly, keeping everything it has already written (Cip, 2026-09-16).
         */
        val stopRequested = AtomicBoolean(false)
    }
}
