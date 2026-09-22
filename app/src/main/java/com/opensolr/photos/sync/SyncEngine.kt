package com.opensolr.photos.sync

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.IndexConnection
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.data.Session
import com.opensolr.photos.data.SyncReport
import com.opensolr.photos.index.IndexManager
import com.opensolr.photos.media.LocalPhoto
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.net.IngestItem
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.OpensolrException
import com.opensolr.photos.net.friendlyMessage
import com.opensolr.photos.net.PhotoRejectedException
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.PlanLimitException
import com.opensolr.photos.net.QuotaExceededException
import com.opensolr.photos.net.RateLimitedException
import com.opensolr.photos.net.RetryLaterException
import com.opensolr.photos.net.SignInRequiredException
import com.opensolr.photos.net.SolrAuthException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.net.VectorNotAllowedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.TimeZone

class SyncEngine(private val context: Context, private val unlimited: Boolean = false) {

    private val callTimes = ArrayDeque<Long>()

    data class Progress(val phase: String, val done: Int, val total: Int)

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache.of(context)
    private val indexes = IndexManager(context, prefs, api)
    private val edits = com.opensolr.photos.search.EditRepository(context)

    private var quotaHit = false

    private var withoutWords = 0

    private var shortAllowance = ""

    private var runFix: android.location.Location? = null
    private var runFixAsked = false

    private suspend fun newPhotoPlaces(batch: List<LocalPhoto>, already: Set<String>): Map<String, PhotoCache.SetPlace> {
        val since = prefs.autoPlaceSince
        if (since <= 0L) return emptyMap()

        val wanted = batch.filter { it.id !in already && it.addedSec * 1000L >= since }
            .filter { PhotoReader.gpsState(context, it.uri) == PhotoReader.GpsState.MISSING }
            .associateWith { photo ->
                val real = photo.dateTakenMs.takeIf { t -> t > 0 } ?: (photo.addedSec * 1000L)
                real to listOfNotNull(real, PhotoReader.takenAsIndexed(context, photo.uri)).distinct()
            }
        if (wanted.isEmpty()) return emptyMap()
        val readings = wanted.values.flatMap { it.second }
        val near = cache.positionsBetween(readings.min() - NEAR_PHOTO_MS, readings.max() + NEAR_PHOTO_MS)
        val now = System.currentTimeMillis()
        val out = HashMap<String, PhotoCache.SetPlace>()
        for ((photo, times) in wanted) {
            val (moment, tries) = times
            val fromPhoto = near.map { at -> at to tries.minOf { kotlin.math.abs(at.first - it) } }
                .filter { it.second <= NEAR_PHOTO_MS }
                .minByOrNull { it.second }
                ?.first?.let { it.second to it.third }
            val position = fromPhoto ?: if (now - moment in 0..DEVICE_WINDOW_MS) {
                if (!runFixAsked) {
                    runFixAsked = true
                    runFix = com.opensolr.photos.media.DevicePlace.now(context)
                }
                runFix?.let { it.latitude to it.longitude }
            } else null
            position ?: continue
            out[photo.id] = PhotoCache.SetPlace(photo.id, position.first, position.second, owner = false, written = false)
        }
        if (out.isNotEmpty()) cache.inTransaction { out.values.forEach { cache.putSetPlace(it) } }
        return out
    }

    private class ChargerNeededException(val photos: Int) : Exception()

    private class SyncStoppedException : Exception()

    suspend fun run(onProgress: suspend (Progress) -> Unit): SyncReport {
        val session = prefs.session ?: return report("sign_in_required", message = AppText.s(R.string.sy_sign_in))
        var localCount = 0
        var indexCount = 0
        var added = 0
        var deleted = 0
        var failed = 0
        var recreated = false

        val weighed = HashMap<String, String>()
        quotaHit = false
        withoutWords = 0
        shortAllowance = ""
        runFix = null
        runFixAsked = false

        try {
            onProgress(Progress(AppText.s(R.string.sy_checking), 0, 0))
            val prefetched = try {
                api.syncInfo(session, indexes.indexName, prefs.account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val (initialConnection, outcome) = indexes.ensure(session, prefetched) { onProgress(Progress(it, 0, 0)) }
            recreated = outcome == IndexManager.Outcome.RECREATED
            if (recreated) Notifier.indexRecreated(context)
            var connection = initialConnection
            var solr = SolrClient(connection)

            if (outcome == IndexManager.Outcome.NEEDS_CHOICE) {
                return report("device_choice", message = AppText.s(R.string.sy_device))
            }
            if (outcome == IndexManager.Outcome.CONFIG_NEWER) {
                return report("update_app", message = AppText.s(R.string.sy_newer))
            }
            val rebuild = outcome == IndexManager.Outcome.NEEDS_REBUILD
            if (rebuild && !prefs.rebuildApproved) {
                return report("rebuild_required", message = AppText.s(R.string.sy_rebuild))
            }

            onProgress(Progress(AppText.s(R.string.sy_looking), 0, 0))

            val foldersBefore = MediaScanner.folderStamp(context, prefs.folders)
            val local = MediaScanner.scan(context, prefs.folders)
            localCount = local.size

            val prefetchedAccount = prefetched?.accountFor(connection.indexName)
            if (prefetchedAccount != null) {
                prefs.account = prefetchedAccount
                PlanWatch.notifyNew(context, prefs, prefetchedAccount)
            } else {
                refreshAccount(session, connection)
            }
            val limits = prefs.account
            var vectorAllowed = limits?.vectorAllowed ?: false

            val aiAvailable = vectorAllowed && (limits == null || limits.maxAiRequests <= 0 || limits.aiRequestsUsed < limits.maxAiRequests)
            if (!aiAvailable) quotaHit = true

            if (rebuild) {

                onProgress(Progress(AppText.s(R.string.sy_resetting), 0, 0))
                withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { keepOwnersEdits(solr) }
                solr.deleteAll()
                indexes.applyConfig(session, connection) { onProgress(Progress(it, 0, 0)) }

                prefs.rebuildApproved = false
            }

            val forced = prefs.resyncIds
            val wordingReset = prefs.wordingResetIds
            forced.forEach { cache.clearWordRetry(it); cache.clearSkipped(it) }
            val skippedSizes = cache.skippedSizes()
            val rereadSince = prefs.rereadAllSince
            val phase = if (rebuild) AppText.s(R.string.sy_rebuilding) else AppText.s(R.string.sy_indexing)

            val sent = HashSet<String>()
            val pending = ArrayList<LocalPhoto>(READ_BATCH)
            var total = 0

            var expected = 0
            suspend fun progress(phase: String) = onProgress(Progress(phase, added + failed, maxOf(total, expected)))
            suspend fun ingest(batch: List<LocalPhoto>) {

                if (!unlimited && !isCharging() && batteryPercent() < BATTERY_PAUSE_PERCENT) {
                    throw ChargerNeededException((maxOf(expected, total) - added).coerceAtLeast(0))
                }

                if (SyncWorker.stopRequested.get()) {
                    throw SyncStoppedException()
                }
                val items = ArrayList<IngestItem>(batch.size)

                val placed = cache.setPlaces(batch.map { it.id })
                val guessed = newPhotoPlaces(batch, placed.keys)
                for (photo in batch) {
                    val jpeg = try {
                        PhotoReader.copyForIngest(context, photo, placed[photo.id] ?: guessed[photo.id])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (jpeg == null) {
                        cache.markSkipped(photo, PhotoReader.unreadableReason(context, photo))
                        failed++
                        continue
                    }
                    val edits = cache.getEdits(photo.id)

                    val xmp = if (edits?.tags != null && edits.meaning != null && edits.persons != null) null
                        else PhotoReader.xmpWordsIn(context, photo)
                    val tags = edits?.tags ?: xmp?.tags

                    // "" = the owner reset their wording: neither theirs nor the file's is sent any more
                    val cleared = edits?.meaning == ""
                    val meaning = if (cleared) null else edits?.meaning ?: xmp?.meaning
                    val names = edits?.persons ?: xmp?.persons ?: emptyList()
                    items += IngestItem(photo, jpeg, tags, meaning, weighed[photo.id] ?: PhotoReader.fileMd5(context, photo), names, resetWording = cleared || photo.id in wordingReset)
                }
                if (items.isNotEmpty()) {
                    val results = try {
                        retrying { api.photosIngest(session, connection.indexName, items) }
                    } catch (e: PhotoRejectedException) {

                        failed += items.size
                        null
                    } catch (e: ServiceException) {
                        failed += items.size
                        null
                    }
                    results?.let { r ->

                      cache.inTransaction {
                        items.forEachIndexed { k, item ->
                            val result = r.getOrNull(k)
                            if (result == null) {
                                failed++
                            } else {
                                added++
                                cache.clearSkipped(item.photo.id)

                                result.doc?.let { edits.storeDoc(item.photo.id, it) }
                                cache.clearActions(listOf(item.photo.id))

                                if (item.photo.id in placed) cache.markPlacesSynced(listOf(item.photo.id))
                                if (result.words) {
                                    cache.clearWordRetry(item.photo.id)
                                } else {
                                    withoutWords++
                                    if (aiAvailable) cache.noteWordsMissing(item.photo.id, System.currentTimeMillis())
                                }
                            }
                        }
                      }
                    }
                }
                progress(phase)
            }
            suspend fun queue(photo: LocalPhoto) {
                if (skippedSizes[photo.id] == photo.sizeBytes) return
                if (!sent.add(photo.id)) return
                total++
                pending += photo
                if (pending.size >= READ_BATCH) {
                    val batch = pending.toList()
                    pending.clear()
                    ingest(batch)
                }
            }

            val toDelete = ArrayList<String>(500)
            if (!rebuild) {
                if (edits.cloneMissing()) {
                    onProgress(Progress(AppText.s(R.string.sy_reading_once), 0, 0))
                    withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { edits.readIndexIntoCache() }
                }
                val known = cache.docSizes()
                indexCount = known.size
                onProgress(Progress(AppText.s(R.string.sy_comparing), 0, 0))

                known.keys.filter { it !in local }.forEach { toDelete += it }

                val waiting = cache.wordRetriesWaiting(System.currentTimeMillis())

                val queuedWords = cache.actions(PhotoCache.ACTION_WORDS).toSet()
                val toIndex = ArrayList<String>()
                val needWords = if (!aiAvailable) emptySet() else cache.docsWithoutWords().toSet() - waiting
                val reread = if (rereadSince > 0) cache.docsIndexedBefore(rereadSince).toSet() else emptySet()

                local.values.forEach { photo ->
                    val stamp = known[photo.id]

                    val changed = stamp == null || stamp.first != photo.sizeBytes ||
                        (stamp.second > 0 && stamp.second != photo.modifiedSec)
                    val waitingWords = photo.id in queuedWords
                    if (changed && waitingWords && stamp != null) {

                        cache.updateDocSize(photo.id, photo.sizeBytes, photo.modifiedSec, PhotoReader.fileMd5(context, photo))
                        return@forEach
                    }
                    if (changed && stamp != null) {

                        val was = cache.docFileHash(photo.id)

                        val now = PhotoReader.fileMd5(context, photo)
                        if (now != null) weighed[photo.id] = now
                        if (was != null && was == now) {
                            cache.updateDocSize(photo.id, photo.sizeBytes, photo.modifiedSec)
                            if (photo.id !in forced && photo.id !in needWords && photo.id !in reread) return@forEach
                        }
                    }
                    if (changed || photo.id in forced || photo.id in needWords || photo.id in reread) {
                        toIndex += photo.id
                    }
                }

                cache.queueActions(toIndex, PhotoCache.ACTION_INDEX)
            } else {
                indexCount = 0

                cache.clearDocs()
                prefs.cloneComplete = true
                cache.queueActions(local.keys, PhotoCache.ACTION_INDEX)
            }

            if (toDelete.isNotEmpty()) {
                toDelete.chunked(500).forEach { batch ->
                    solr.delete(batch)
                    cache.removeDocs(batch)
                    cache.clearActions(batch)
                    deleted += batch.size
                }
                toDelete.clear()
            }

            val queued = cache.actions(PhotoCache.ACTION_INDEX).mapNotNull { local[it] }
            expected = queued.size
            for (photo in queued) queue(photo)
            if (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                ingest(batch)
            }

            solr.commit()

            try {
                edits.sendWords { done, all ->
                    onProgress(Progress(AppText.s(R.string.sy_saving_words, Actions.formatCount(done.toLong()), Actions.formatCount(all.toLong())), done, all))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {

            }
            if (rebuild) prefs.rebuildApproved = false
            if (forced.isNotEmpty()) prefs.resyncIds = emptySet()
            if (wordingReset.isNotEmpty()) prefs.wordingResetIds = prefs.wordingResetIds - wordingReset
            if (rereadSince > 0) prefs.rereadAllSince = 0L
            cache.removeAllExcept(local.keys)
            cache.keepSkippedOnly(local.keys)
            refreshAccount(session, connection)
            var message = ""
            if (shortAllowance.isNotEmpty()) message = shortAllowance
            else if (withoutWords > 0) {

                val n = AppText.p(R.plurals.sy_n_photos, withoutWords, Actions.formatCount(withoutWords.toLong()))
                message = if (!vectorAllowed)
                    AppText.s(R.string.sy_no_recognition, n)
                else
                    AppText.s(R.string.sy_no_words, n)
            }
            val indexAfter = cache.docCount()
            prefs.folderStamp = foldersBefore
            return report("ok", added, deleted, failed, localCount, indexCount, message, recreated, indexAfter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncStoppedException) {

            commitQuietly()
            return report("stopped", added, deleted, failed, localCount, indexCount,
                AppText.p(R.plurals.sy_stopped_after, added, Actions.formatCount(added.toLong())), recreated)
        } catch (e: ChargerNeededException) {
            commitQuietly()
            return report("waiting_charger", added, deleted, failed, localCount, indexCount,
                AppText.s(R.string.sy_charger, batteryPercent().toString(), Actions.formatCount(added.toLong()), Actions.formatCount(e.photos.toLong())), recreated)
        } catch (e: RetryLaterException) {
            commitQuietly()
            return report("retry_later", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: SignInRequiredException) {
            prefs.pendingNotice = AppText.s(R.string.sy_signin_notice)
            Notifier.signInRequired(context)
            return report("sign_in_required", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: QuotaExceededException) {
            commitQuietly()
            val reset = e.resetsAt?.takeIf { it.isNotBlank() }?.let { AppText.s(R.string.sy_resets_on, it.substringBefore(' ')) } ?: ""
            Notifier.planLimit(
                context, AppText.s(R.string.sy_quota_title),
                AppText.s(R.string.sy_quota_text, Actions.formatCount(added.toLong()), reset)
            )
            return report("stopped_quota", added, deleted, failed, localCount, indexCount, AppText.s(R.string.sy_quota_report, reset), recreated)
        } catch (e: PlanLimitException) {
            Notifier.planLimit(
                context, AppText.s(R.string.sy_full_title),
                AppText.s(R.string.sy_full_text)
            )
            return report("stopped_plan_limit", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: OpensolrException) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, friendlyMessage(e, AppText.s(R.string.sy_failed)), recreated)
        } catch (e: Exception) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, AppText.s(R.string.sy_stopped_err, friendlyMessage(e, AppText.s(R.string.sy_try_later))), recreated)
        }
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        pace()
        try {
            return block()
        } catch (e: RateLimitedException) {

            throw RetryLaterException(e.retryAfterSeconds)
        }
    }

    private suspend fun pace() {
        val limits = prefs.account ?: return
        val now = System.currentTimeMillis()
        while (callTimes.isNotEmpty() && now - callTimes.first() > HOUR_MS) callTimes.removeFirst()
        val lastMinute = callTimes.count { now - it <= MINUTE_MS }
        var waitMs = 0L
        if (lastMinute >= (limits.maxPerMinute * PACE_SHARE).toInt().coerceAtLeast(1)) {
            val oldestInMinute = callTimes.first { now - it <= MINUTE_MS }
            waitMs = maxOf(waitMs, MINUTE_MS - (now - oldestInMinute) + 500)
        }
        if (callTimes.size >= (limits.maxPerHour * PACE_SHARE).toInt().coerceAtLeast(1)) {
            waitMs = maxOf(waitMs, HOUR_MS - (now - callTimes.first()) + 500)
        }

        if (waitMs > MINUTE_MS) throw RetryLaterException(waitMs / 1000)
        if (waitMs > 0) delay(waitMs)
        callTimes.addLast(System.currentTimeMillis())
    }

    private suspend fun keepOwnersEdits(solr: SolrClient) {

        val alreadyMine = cache.editedIds()
        solr.forEachDoc("id,custom_tags,meaning,labels") { doc ->
            val id = doc.optString("id")
            if (id.isEmpty() || id in alreadyMine) return@forEachDoc
            val tags = doc.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            val labels = doc.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val meaning = doc.optString("meaning").trim()
            val own = meaning.takeIf { it.isNotEmpty() && it != labels.joinToString(", ") }
            if (tags.isNotEmpty() || own != null) cache.putEdits(id, PhotoCache.Edits(tags, own))
        }
    }

    private fun batteryPercent(): Int {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return 100
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) 100 else (level * 100 / scale)
    }

    private fun isCharging(): Boolean {
        val status = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private suspend fun <T> withFreshPassword(session: Session, onRefreshed: (IndexConnection) -> Unit, block: suspend () -> T): T =
        try {
            block()
        } catch (e: SolrAuthException) {
            onRefreshed(indexes.refreshConnection(session))
            block()
        }

    private suspend fun refreshAccount(session: Session, connection: IndexConnection) {
        try {
            val limits = api.accountSummary(session, connection.indexName, prefs.account)
            prefs.account = limits

            PlanWatch.notifyNew(context, prefs, limits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    private suspend fun commitQuietly() {
        try {
            prefs.connection?.let { SolrClient(it).commit() }
        } catch (e: Exception) {
        }
    }

    private fun report(
        status: String,
        added: Int = 0,
        deleted: Int = 0,
        failed: Int = 0,
        localCount: Int = 0,
        indexCount: Int = 0,
        message: String = "",
        recreated: Boolean = false,
        indexAfter: Int = indexCount,
    ) = SyncReport(System.currentTimeMillis(), status, added, deleted, failed, localCount, indexCount, message, recreated, indexAfter)

    private fun warnShortAllowance(left: Int, toRead: Int): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val text = AppText.s(R.string.sy_short_text, Actions.formatCount(left.toLong()), Actions.formatCount(toRead.toLong()))
        val key = "ai_short_$month"
        if (key !in prefs.warnedKeys) {
            Notifier.planLimit(context, AppText.s(R.string.sy_short_title, Actions.formatCount(left.toLong())), text, key)
            prefs.warnedKeys = prefs.warnedKeys + key
        }
        return text
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    companion object {

        private const val NEAR_PHOTO_MS = 30 * 60 * 1000L

        private const val DEVICE_WINDOW_MS = 2 * 60 * 60 * 1000L

        private const val READ_BATCH = 5

        private const val BATTERY_PAUSE_PERCENT = 20

        private const val PACE_SHARE = 0.8
        private const val MINUTE_MS = 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}
