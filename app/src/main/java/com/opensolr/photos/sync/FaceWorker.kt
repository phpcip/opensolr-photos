package com.opensolr.photos.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.PhotoReader
import java.util.concurrent.TimeUnit

/**
 * Reads the faces of every photo in the chosen folders, on the phone, newest first, and only once per
 * file version. A run stops by itself after a few minutes and hands over to the next, so the system
 * never kills it halfway; on battery below [BATTERY_MIN] it waits for the charger.
 */
class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = AppPrefs(ctx)
        if (prefs.session == null || prefs.folders.isEmpty()) return Result.success()
        val cache = PhotoCache.of(ctx)
        val edits = com.opensolr.photos.search.EditRepository(ctx)
        // a better finder reads every photo again, once
        val sp = ctx.getSharedPreferences("faces", Context.MODE_PRIVATE)
        if (sp.getInt("engine", 0) < FaceEngine.VERSION) {
            cache.rereadAllFaces()
            sp.edit().putInt("engine", FaceEngine.VERSION).apply()
        }
        arrived.set(false)
        val local = MediaScanner.scan(ctx, prefs.folders)
        cache.dropFacesExcept(local.keys)
        val read = cache.faceScannedSizes()
        // what arrived last goes first: a photo without a date in it (WhatsApp, edited copies) never waits behind the rest
        val todo = local.values.filter { read[it.id] != it.sizeBytes }
            .sortedWith(compareByDescending<com.opensolr.photos.media.LocalPhoto> { it.addedSec }.thenByDescending { it.dateTakenMs })
        // what is in the index but not yet in the files (from before, or from another phone) is queued for the files, once
        if (!reconcileFiles(ctx, cache, local.values)) { next(ctx, continuation = true); return Result.success() }
        if (learnPeople(ctx, cache)) {
            // photos read before a person was learned get the name now: the newest ones, a bounded number
            val recent = local.values.sortedByDescending { it.addedSec }.take(RECHECK).map { it.id }
            if (com.opensolr.photos.search.FaceMatcher.nameRecent(cache, edits, recent)) SyncScheduler.runNow(ctx)
        }
        if (todo.isEmpty()) return Result.success()

        val engine = FaceEngine.of(ctx)
        // the people named for sure, read once per run: new faces close enough to one of them get the name
        val people = com.opensolr.photos.search.FaceMatcher.people(cache)
        val until = System.currentTimeMillis() + RUN_MS
        for (photo in todo) {
            // stopped by the system: the next run carries on where this one left off
            if (isStopped) { next(ctx, continuation = true); return Result.success() }
            // a photo arrived while this run works through older ones: the next run starts now, newest first
            if (arrived.getAndSet(false)) { next(ctx, continuation = true); return Result.success() }
            if (System.currentTimeMillis() > until) { next(ctx, continuation = true); return Result.success() }
            if (!charging(ctx) && battery(ctx) < BATTERY_MIN) { next(ctx, continuation = true, delayMinutes = WAIT_MINUTES); return Result.success() }
            val bitmap = try { PhotoReader.uprightBitmap(ctx, photo.uri, FaceEngine.READ_EDGE) } catch (e: Exception) { null }
            val faces = if (bitmap == null) emptyList() else try { engine.analyze(bitmap) } catch (e: Exception) { emptyList() } finally { bitmap.recycle() }
            val rows = cache.putFaces(photo.id, photo.mediaId, photo.sizeBytes, faces)
            if (com.opensolr.photos.search.FaceMatcher.autoName(cache, edits, photo.id, rows, faces.map { it.vector }, people)) SyncScheduler.runNow(ctx)
        }
        return Result.success()
    }


    /**
     * Walks every photo once: people, tags, the owner's own wording or a place that the index has and the file does
     * not are queued to be written into the file. Resumes where it stopped; true when every photo was looked at.
     */
    private fun reconcileFiles(ctx: Context, cache: PhotoCache, photos: Collection<com.opensolr.photos.media.LocalPhoto>): Boolean {
        val sp = ctx.getSharedPreferences("faces", Context.MODE_PRIVATE)
        if (sp.getInt("reconciled", 0) >= RECONCILE_VERSION) return true
        var after = sp.getString("reconcile_after", "") ?: ""
        val until = System.currentTimeMillis() + RECONCILE_MS
        val queue = ArrayList<String>()
        for (photo in photos.sortedBy { it.id }) {
            if (photo.id <= after) continue
            if (isStopped || System.currentTimeMillis() > until) {
                cache.queueFileWrites(queue)
                sp.edit().putString("reconcile_after", after).apply()
                return false
            }
            after = photo.id
            val doc = cache.doc(photo.id) ?: continue
            if (!PhotoReader.canWriteExif(photo.mime)) continue
            val file = runCatching { PhotoReader.xmpWordsIn(ctx, photo) }.getOrNull() ?: continue
            val fileTags = file.tags.orEmpty().map { it.lowercase() }.toSet()
            val filePeople = file.persons.map { it.lowercase() }.toSet()
            var missing = doc.tags.any { it.lowercase() !in fileTags } || doc.persons.any { it.lowercase() !in filePeople }
            // the owner's own wording: a meaning that is not just the labels joined
            val json = runCatching { org.json.JSONObject(doc.json ?: "{}") }.getOrNull()
            val labels = json?.optJSONArray("labels")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
            val own = doc.meaning?.takeIf { it.isNotBlank() && it != labels }
            if (own != null && file.meaning != own && cache.getEdits(photo.id)?.meaning == null) {
                cache.putEdits(photo.id, PhotoCache.Edits(doc.tags, own, doc.persons))
            }
            // a place the index has and the file does not: set by the owner or found by the app, it goes into the file
            val point = com.opensolr.photos.search.parseLatLon(json?.optString("location"))
            if (point != null && PhotoReader.gpsState(ctx, photo.uri) == PhotoReader.GpsState.MISSING && cache.setPlaces(listOf(photo.id)).isEmpty()) {
                cache.putSetPlace(PhotoCache.SetPlace(photo.id, point.first, point.second, owner = true, written = false, synced = true))
            }
            if (missing) queue += photo.id
            if (queue.size >= 500) { cache.queueFileWrites(queue); queue.clear() }
        }
        cache.queueFileWrites(queue)
        sp.edit().putInt("reconciled", RECONCILE_VERSION).remove("reconcile_after").apply()
        return true
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
            com.opensolr.photos.search.FaceSeeder.learn(cache, person)
            sp.edit().putInt(key, count).apply()
            learned = true
        }
        return learned
    }

    companion object {
        private const val WORK = "faces"
        private const val RUN_MS = 4 * 60 * 1000L
        private const val RECONCILE_VERSION = 1
        private const val RECONCILE_MS = 3 * 60 * 1000L
        /** How many of the newest photos are checked again for people when someone is learned. */
        private const val RECHECK = 1000
        private const val BATTERY_MIN = 5
        private const val WAIT_MINUTES = 30L

        /** Set when new photos were synced: a run at work hands over at once so they are read first. */
        private val arrived = java.util.concurrent.atomic.AtomicBoolean(false)

        /** After a sync: new photos get their faces read before anything older. */
        fun photosArrived(context: Context) {
            arrived.set(true)
            next(context)
        }

        /** Reads what is new; a run already waiting or at work is left alone unless this is its own hand-over. */
        fun next(context: Context, continuation: Boolean = false, delayMinutes: Long = 0) {
            val request = OneTimeWorkRequestBuilder<FaceWorker>()
                .apply { if (delayMinutes > 0) setInitialDelay(delayMinutes, TimeUnit.MINUTES) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK, if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request,
            )
        }

        private fun battery(context: Context): Int {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level < 0 || scale <= 0) 100 else level * 100 / scale
        }

        private fun charging(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
}
