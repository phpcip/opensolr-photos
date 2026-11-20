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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notice by lazy { RunNotice(this, AppText.s(R.string.sy_syncing_photos), Notifier.PROGRESS_ID, Notifier.SYNC_STATE_ID) }

    // the work, with a watch beside it that speaks up in the notification when nothing moves
    override suspend fun doWork(): Result = kotlinx.coroutines.coroutineScope {
        val watch = launch { notice.watch() }
        try { work() } finally { watch.cancel() }
    }

    private suspend fun work(): Result {
        if (AppPrefs(applicationContext).syncPaused) return Result.success()
        // stopped again and again: left alone, said once, never round and round
        if (notice.triesUsedUp) { notice.gaveUp(); return Result.failure() }
        if (!running.compareAndSet(false, true)) return Result.success()
        stopRequested.set(false)
        var ended = false
        try {
            val prefs = AppPrefs(applicationContext)
            if (SyncScheduler.isWatchRun(tags)) {

                if (!touchesChosenFolders(prefs)) return Result.success()
                // new photos on mobile data with it switched off: said once, synced when Wi-Fi is back
                if (com.opensolr.photos.data.NetHold.heldNow(applicationContext)) {
                    Notifier.waitingWifi(applicationContext)
                    SyncScheduler.runNow(applicationContext)
                    return Result.success()
                }
                val since = System.currentTimeMillis() - prefs.lastWatchSyncAt
                if (since < SyncScheduler.WATCH_MIN_INTERVAL_MS) {
                    SyncScheduler.runLater(applicationContext, SyncScheduler.WATCH_MIN_INTERVAL_MS - since)
                    return Result.success()
                }
                prefs.lastWatchSyncAt = System.currentTimeMillis()
            }
            Notifier.waitingWifiDone(applicationContext)
            notice.began()
            notice.step(AppText.s(R.string.stg_preparing))
            // deletions first: this phone's are told to the account, the other devices' are removed here
            notice.step(AppText.s(R.string.stg_deletions))
            runCatching { com.opensolr.photos.storage.Deletions.push(applicationContext); com.opensolr.photos.storage.Deletions.pull(applicationContext) }
            // and what the other devices changed in the index comes into this phone's copy before anything is sent
            notice.step(AppText.s(R.string.run_other_devices))
            runCatching { com.opensolr.photos.search.IndexChanges.pull(applicationContext) }
            val report = SyncEngine(applicationContext).run { progress ->
                setProgress(workDataOf(KEY_PHASE to progress.phase, KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                notice.step(progress.phase, progress.done, progress.total)
            }
            prefs.lastReport = report
            ended = true

            if (report.status == "retry_later") { notice.retrying(report.message); return Result.retry() }

            if (report.status == "waiting_battery") SyncScheduler.runLater(applicationContext, SyncScheduler.BATTERY_RETRY_MS)
            return Result.success(workDataOf(KEY_STATUS to report.status))
        } finally {
            running.set(false)
            // ended by the system, not by the owner and not by its own end: said, with the reason
            if (!ended && isStopped && !stopRequested.get()) notice.stopped()

            if (AppPrefs(applicationContext).session != null) SyncScheduler.watchMedia(applicationContext)
            runCatching { com.opensolr.photos.storage.LocalFiles.refresh(applicationContext) }
            // after a sync: the people are learned and the newest photos named, nothing is read
            FaceWorker.next(applicationContext)
            // then the storage backend, when the index has one: restore what is missing, send what is new
            com.opensolr.photos.storage.StorageScheduler.runNow(applicationContext)
        }
    }

    private fun touchesChosenFolders(prefs: AppPrefs): Boolean =
        MediaScanner.touchesFolders(applicationContext, triggeredContentUris, prefs.folders, prefs.folderStamp)

    // expedited runs on Android 11 and older start as a foreground service with this
    override suspend fun getForegroundInfo(): ForegroundInfo = notice.info(AppText.s(R.string.stg_preparing))

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STATUS = "status"

        val running = AtomicBoolean(false)

        val stopRequested = AtomicBoolean(false)
    }
}
