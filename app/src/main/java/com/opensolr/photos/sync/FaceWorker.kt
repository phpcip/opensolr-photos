package com.opensolr.photos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.search.EditRepository
import com.opensolr.photos.search.FaceMatcher
import com.opensolr.photos.search.FaceSeeder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Faces in the background, two jobs and nothing else, ever:
 *  - LEARN, after every sync: each tagged person's face is learned from their tagged photos and the newest
 *    photos get the names they match. No photo is read, only fingerprints already on the phone are compared.
 *  - SCAN_ALL, started by the owner from the Sync screen: every photo without faces in the index is read once,
 *    only while the phone is charging, in short runs, with progress, stoppable. The faces go to the index with
 *    the words path, so no phone reads them a second time.
 * New photos get their faces read when they are indexed (SyncEngine), one at a time, as they come.
 */
class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = AppPrefs(ctx)
        if (prefs.session == null || prefs.folders.isEmpty()) return Result.success()
        val cache = PhotoCache.of(ctx)
        val edits = EditRepository(ctx)
        val local = MediaScanner.scan(ctx, prefs.folders)
        cache.dropFacesExcept(local.keys)
        return if (inputData.getBoolean(SCAN_ALL, false)) scanAll(ctx, prefs, cache, edits, local.values) else learn(ctx, cache, edits, local.values)
    }

    private fun learn(ctx: Context, cache: PhotoCache, edits: EditRepository, photos: Collection<com.opensolr.photos.media.LocalPhoto>): Result {
        if (learnPeople(ctx, cache)) {
            // photos read before a person was learned get the name now: the newest ones, a bounded number
            val recent = photos.sortedByDescending { it.addedSec }.take(RECHECK).map { it.id }
            if (FaceMatcher.nameRecent(cache, edits, recent)) SyncScheduler.runNow(ctx)
        }
        return Result.success()
    }

    private suspend fun scanAll(ctx: Context, prefs: AppPrefs, cache: PhotoCache, edits: EditRepository, photos: Collection<com.opensolr.photos.media.LocalPhoto>): Result {
        val read = cache.faceScannedSizes()
        val todo = photos.filter { read[it.id] != it.sizeBytes }.sortedByDescending { it.addedSec }
        val total = photos.size
        var done = total - todo.size
        _progress.value = Progress(true, done, total)
        try {
            if (todo.isEmpty()) return Result.success()
            learnPeople(ctx, cache)
            val engine = FaceEngine.of(ctx)
            val people = FaceMatcher.people(cache)
            val until = System.currentTimeMillis() + RUN_MS
            for (photo in todo) {
                if (isStopped) return Result.success()
                if (System.currentTimeMillis() > until) { next(ctx, scanAll = true, continuation = true); return Result.success() }
                val bitmap = try { PhotoReader.uprightBitmap(ctx, photo.uri, FaceEngine.READ_EDGE) } catch (e: Exception) { null }
                val faces = if (bitmap == null) emptyList() else try { engine.analyze(bitmap) } catch (e: Exception) { emptyList() } finally { bitmap.recycle() }
                // the names the file carries come first; the matcher only names what is left
                FaceMatcher.fromFile(ctx, cache, photo.uri, cache.putFaces(photo.id, photo.mediaId, photo.sizeBytes, faces))
                val stored = cache.facesWithVectors(photo.id)
                FaceMatcher.autoName(cache, edits, photo.id, stored.map { it.first }, stored.map { it.second }, people, queueWords = false)
                // the faces reach the index with the words path, in batches, one embedding call per fifty
                cache.queueAction(photo.id, PhotoCache.ACTION_WORDS)
                done++
                _progress.value = Progress(true, done, total)
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
            SyncScheduler.runNow(ctx)
            return Result.success()
        } finally {
            if (isStopped || done >= total) _progress.value = Progress(false, done, total)
            if (!isStopped && done < total) SyncScheduler.runNow(ctx)
        }
    }

    /**
     * Each tagged person's face is learned from their tagged photos, again whenever more of those photos have had
     * their faces read (a tenth more, or the first time), so the references grow with the reading.
     */
    private fun learnPeople(ctx: Context, cache: PhotoCache): Boolean {
        val sp = ctx.getSharedPreferences("faces", Context.MODE_PRIVATE)
        var learned = false
        cache.taggedPeople().forEach { (person, count) ->
            if (count < 3) return@forEach
            val key = "learned:" + person.lowercase()
            val was = sp.getInt(key, 0)
            if (was > 0 && count < was + maxOf(3, was / 10)) return@forEach
            FaceSeeder.learn(cache, person)
            sp.edit().putInt(key, count).apply()
            learned = true
        }
        return learned
    }

    /** What the Sync screen shows while every photo is read. */
    data class Progress(val running: Boolean, val done: Int, val total: Int)

    companion object {
        private const val WORK_LEARN = "faces"
        private const val WORK_SCAN = "faces_all"
        private const val SCAN_ALL = "scan_all"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val RUN_MS = 4 * 60 * 1000L
        /** How many of the newest photos are checked again for people when someone is learned. */
        private const val RECHECK = 1000

        private val _progress = MutableStateFlow(Progress(false, 0, 0))
        val progress: StateFlow<Progress> = _progress

        /** After a sync: learn the people, name the newest photos. Nothing is read. */
        fun next(context: Context, scanAll: Boolean = false, continuation: Boolean = false) {
            if (!scanAll) {
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_LEARN, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FaceWorker>().build())
                return
            }
            val request = OneTimeWorkRequestBuilder<FaceWorker>()
                .setInputData(workDataOf(SCAN_ALL to true))
                // only on the charger: this is the one job that reads every photo
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_SCAN, if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        /** The owner asked for every photo to be read: starts the scan (it waits for the charger). */
        fun scanAll(context: Context) {
            _progress.value = Progress(true, _progress.value.done, _progress.value.total)
            next(context, scanAll = true)
        }

        fun stopScan(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_SCAN)
            _progress.value = Progress(false, _progress.value.done, _progress.value.total)
        }

        /** Whether a scan is queued or at work, read from WorkManager (the app may have been restarted). */
        fun scanning(context: Context): Boolean = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_SCAN).get()
                .any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
        }.getOrDefault(false)
    }
}
