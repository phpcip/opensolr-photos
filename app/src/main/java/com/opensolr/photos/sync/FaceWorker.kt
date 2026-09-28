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
        // a better finder reads every photo again, once
        val sp = ctx.getSharedPreferences("faces", Context.MODE_PRIVATE)
        if (sp.getInt("engine", 0) < FaceEngine.VERSION) {
            cache.rereadAllFaces()
            sp.edit().putInt("engine", FaceEngine.VERSION).apply()
        }
        val local = MediaScanner.scan(ctx, prefs.folders)
        cache.dropFacesExcept(local.keys)
        val read = cache.faceScannedSizes()
        // what arrived last goes first: a photo without a date in it (WhatsApp, edited copies) never waits behind the rest
        val todo = local.values.filter { read[it.id] != it.sizeBytes }
            .sortedWith(compareByDescending<com.opensolr.photos.media.LocalPhoto> { it.addedSec }.thenByDescending { it.dateTakenMs })
        if (todo.isEmpty()) return Result.success()

        val engine = FaceEngine.of(ctx)
        // the people named for sure, read once per run: new faces close enough to one of them get the name
        val people = cache.facePeople().keys.associateWith { cache.personVectors(it, REFERENCES) }.filterValues { it.isNotEmpty() }
        val until = System.currentTimeMillis() + RUN_MS
        for (photo in todo) {
            // stopped by the system: the next run carries on where this one left off
            if (isStopped) { next(ctx, continuation = true); return Result.success() }
            if (System.currentTimeMillis() > until) { next(ctx, continuation = true); return Result.success() }
            if (!charging(ctx) && battery(ctx) < BATTERY_MIN) { next(ctx, continuation = true, delayMinutes = WAIT_MINUTES); return Result.success() }
            val bitmap = try { PhotoReader.uprightBitmap(ctx, photo.uri, FaceEngine.READ_EDGE) } catch (e: Exception) { null }
            val faces = if (bitmap == null) emptyList() else try { engine.analyze(bitmap) } catch (e: Exception) { emptyList() } finally { bitmap.recycle() }
            val rows = cache.putFaces(photo.id, photo.mediaId, photo.sizeBytes, faces)
            // a photo with one face and one name already on it: that face is that person, free to learn from
            if (rows.size == 1 && rows[0].person == null) {
                val names = cache.doc(photo.id)?.persons.orEmpty()
                if (names.size == 1) { cache.setFacePerson(listOf(rows[0].fid), names[0]); continue }
            }
            autoName(ctx, cache, photo.id, rows, faces, people)
        }
        return Result.success()
    }


    /** Unnamed faces very close to a person named for sure take that name, and the photo gets the person. */
    private fun autoName(
        ctx: Context, cache: PhotoCache, photoId: String, rows: List<PhotoCache.FaceRow>,
        faces: List<FaceEngine.Face>, people: Map<String, List<FloatArray>>,
    ) {
        if (people.isEmpty()) return
        val taken = rows.mapNotNull { it.person?.lowercase() }.toMutableSet()
        val named = ArrayList<String>()
        rows.forEachIndexed { i, row ->
            if (row.person != null) return@forEachIndexed
            val vector = faces.getOrNull(i)?.vector ?: return@forEachIndexed
            var bestName: String? = null
            var best = AUTO
            for ((name, refs) in people) {
                if (name.lowercase() in taken) continue
                for (r in refs) { val s = FaceEngine.similarity(r, vector); if (s >= best) { best = s; bestName = name } }
            }
            bestName?.let { name ->
                cache.setFacePerson(listOf(row.fid), name, sure = false)
                taken += name.lowercase()
                named += name
            }
        }
        if (named.isNotEmpty()) {
            com.opensolr.photos.search.EditRepository(ctx).queueForAll(listOf(photoId), null, false, named, false)
            // the name reaches the index now, not when this run ends (a sync already queued takes it along)
            SyncScheduler.runNow(ctx)
        }
    }

    companion object {
        /** A face this close to a person named for sure is taken as that person without asking. */
        const val AUTO = 0.6f
        private const val REFERENCES = 64
        private const val WORK = "faces"
        private const val RUN_MS = 4 * 60 * 1000L
        private const val BATTERY_MIN = 5
        private const val WAIT_MINUTES = 30L

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
