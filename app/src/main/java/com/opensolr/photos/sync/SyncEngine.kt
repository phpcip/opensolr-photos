package com.opensolr.photos.sync

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

/**
 * Sync and Re-Sync, one algorithm.
 *
 * 1. Make sure the phone's index exists (creating it, or recreating it if it vanished).
 * 2. List the photos in the chosen folders; each id is md5 of the absolute path.
 * 3. Download every id the index holds (1000 at a time).
 * 4. Delete from the index every id that is no longer on the phone.
 * 5. Index every photo that is on the phone but not in the index: read it into words (CLIP),
 *    turn the words into a search vector, add the camera metadata, write the document.
 *
 * A first Sync is simply the case where step 3 returns nothing. Photos already paid for are
 * taken from [PhotoCache], so a recreated or emptied index is refilled without AI requests.
 */
class SyncEngine(private val context: Context, private val unlimited: Boolean = false) {

    /** Times of the API calls of this run, for pacing under the account's per-minute and per-hour limits. */
    private val callTimes = ArrayDeque<Long>()


    /**
     * Progress of a run. [total] is 0 while the size of the work is not yet known.
     */
    data class Progress(val phase: String, val done: Int, val total: Int)

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache(context)
    private val indexes = IndexManager(context, prefs, api)

    /** True once this run knows the plan has no AI for it (no vector search, or the month's allowance used up). */
    private var quotaHit = false

    /** Photos indexed this run without their words. */
    private var withoutWords = 0

    /** The warning of this run that the month's allowance covers fewer photos than there are to read, if any. */
    private var shortAllowance = ""

    /** Thrown mid-run when the battery is low with no charger; [photos] are left to read. */
    private class ChargerNeededException(val photos: Int) : Exception()

    /**
     * Runs one sync and returns what happened. Never throws except for cancellation.
     */
    suspend fun run(onProgress: suspend (Progress) -> Unit): SyncReport {
        val session = prefs.session ?: return report("sign_in_required", message = "Sign in to Opensolr to sync.")
        var localCount = 0
        var indexCount = 0
        var added = 0
        var deleted = 0
        var failed = 0
        var recreated = false
        quotaHit = false
        withoutWords = 0
        shortAllowance = ""

        try {
            onProgress(Progress("Checking your index", 0, 0))
            val (initialConnection, outcome) = indexes.ensure(session) { onProgress(Progress(it, 0, 0)) }
            recreated = outcome == IndexManager.Outcome.RECREATED
            if (recreated) Notifier.indexRecreated(context)
            var connection = initialConnection
            var solr = SolrClient(connection)

            if (outcome == IndexManager.Outcome.NEEDS_CHOICE) {
                return report("device_choice", message = "Open Opensolr Photos and say which one of your devices this phone is.")
            }
            if (outcome == IndexManager.Outcome.CONFIG_NEWER) {
                return report("update_app", message = "Your index was set up by a newer version of Opensolr Photos. Update the app to keep syncing.")
            }
            val rebuild = outcome == IndexManager.Outcome.NEEDS_REBUILD
            if (rebuild && !prefs.rebuildApproved) {
                return report("rebuild_required", message = "This version of Opensolr Photos comes with a new index configuration. Your index must be reset and fully re-synced before syncing continues.")
            }

            onProgress(Progress("Looking for photos", 0, 0))
            val local = MediaScanner.scan(context, prefs.folders)
            localCount = local.size

            // The plan as it is now, not as it was at the last look: an upgrade since then
            // (vector search, a new AI allowance) takes effect in this very run.
            refreshAccount(session, connection)
            val limits = prefs.account
            var vectorAllowed = limits?.vectorAllowed ?: false
            // Without vector search on the plan, or with the month's AI requests used up, photos
            // are indexed by what the phone knows and found by those words; nothing is read.
            val aiAvailable = vectorAllowed && (limits == null || limits.maxAiRequests <= 0 || limits.aiRequestsUsed < limits.maxAiRequests)
            if (!aiAvailable) quotaHit = true

            if (rebuild) {
                // A new configuration means a reset and a full re-sync, in this order and no
                // other (Cip, 2026-09-15): the owner's tags and wording are kept on the phone,
                // the index is emptied, and only THEN the new configuration goes up. Every
                // photo is handed to Opensolr again below.
                onProgress(Progress("Resetting your index", 0, 0))
                withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { keepOwnersEdits(solr) }
                solr.deleteAll()
                indexes.applyConfig(session, connection) { onProgress(Progress(it, 0, 0)) }
                // The configuration is live and the index is empty: from here an interrupted run
                // continues as an ordinary sync, and a later version asks the owner again.
                prefs.rebuildApproved = false
            }

            // Photos that go through everything again whatever their size says: those the owner
            // picked for Re-sync, those indexed without words while the plan had no AI (only now
            // that it has), and those with a position whose place lookup failed.
            val forced = prefs.resyncIds
            val needWords = if (rebuild || !aiAvailable) emptySet() else solr.idsWithoutWords()
            val withoutPlace = if (rebuild) emptySet() else withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { solr.idsWithoutPlace() }
            val phase = if (rebuild) "Rebuilding your index" else "Indexing photos"

            // Each photo goes to Opensolr once, five per call, with its EXIF and the owner's
            // edits when this phone has them. The server does the rest (words, vector, place,
            // the document, the index write) and answers what each photo got.
            val sent = HashSet<String>()
            val pending = ArrayList<LocalPhoto>(READ_BATCH)
            var total = 0
            // How many photos this run is expected to read. While the diff is still streaming
            // the exact figure is not known, so the first page's estimate stands in for it -
            // otherwise the progress would read "10 of 10", then "25 of 25", which says nothing
            // (Cip, 2026-09-15). It is only ever raised, never lowered below what is done.
            var expected = 0
            suspend fun progress(phase: String) = onProgress(Progress(phase, added + failed, maxOf(total, expected)))
            suspend fun ingest(batch: List<LocalPhoto>) {
                // However many photos there are, the run goes ahead. It stops only when the
                // battery is low and nothing is charging, and continues by itself once the phone
                // is plugged in (Cip, 2026-09-16). Checked before every batch, so a long run
                // gives up cleanly instead of flattening the phone, and what it already indexed
                // stays indexed. A run that waited for the charger reads without pausing.
                if (!unlimited && !isCharging() && batteryPercent() < BATTERY_PAUSE_PERCENT) {
                    throw ChargerNeededException((maxOf(expected, total) - added).coerceAtLeast(0))
                }
                val items = ArrayList<IngestItem>(batch.size)
                for (photo in batch) {
                    val jpeg = try {
                        PhotoReader.copyForIngest(context, photo)
                    } catch (e: Exception) {
                        null
                    }
                    if (jpeg == null) {
                        failed++
                        continue
                    }
                    val edits = cache.getEdits(photo.id)
                    // The md5 of the file itself goes up with it: the server only sees the small
                    // re-encoded copy, so it could never work this out on its own.
                    items += IngestItem(photo, jpeg, edits?.tags, edits?.meaning, PhotoReader.fileMd5(context, photo), PhotoReader.personsIn(context, photo))
                }
                if (items.isNotEmpty()) {
                    val results = try {
                        retrying { api.photosIngest(session, connection.indexName, items) }
                    } catch (e: PhotoRejectedException) {
                        // The whole batch refused: counted as failed, tried again next time.
                        failed += items.size
                        null
                    } catch (e: ServiceException) {
                        failed += items.size
                        null
                    }
                    results?.let { r ->
                        items.forEachIndexed { k, item ->
                            val result = r.getOrNull(k)
                            if (result == null) {
                                failed++
                            } else {
                                added++
                                if (!result.words) withoutWords++
                            }
                        }
                    }
                }
                progress(phase)
            }
            suspend fun queue(photo: LocalPhoto) {
                if (!sent.add(photo.id)) return
                total++
                pending += photo
                if (pending.size >= READ_BATCH) {
                    val batch = pending.toList()
                    pending.clear()
                    ingest(batch)
                }
            }

            // The diff, page by page: each page of (id, size) the index holds is compared with the
            // phone's files as it arrives, and its deletions and changed photos are handled while
            // the next page downloads. What the phone has and the index does not is known only
            // once every page has been seen: the ids left unticked.
            val unseen = HashSet(local.keys)
            val toDelete = ArrayList<String>(500)
            var pages = 0
            if (!rebuild) {
                onProgress(Progress("Comparing with your index", 0, 0))
                // The pages download in their own coroutine, two ahead at most, while this one
                // compares and acts on the page in hand: the download and the work overlap.
                withFreshPassword(session, { connection = it; solr = SolrClient(it) }) {
                    coroutineScope {
                    val pageChannel = Channel<Pair<Long, List<Pair<String, Long>>>>(capacity = 2)
                    launch {
                        try {
                            solr.forEachSizePage { numFound, page -> pageChannel.send(numFound to page) }
                            pageChannel.close()
                        } catch (e: Throwable) {
                            pageChannel.close(e)
                        }
                    }
                    for ((numFound, page) in pageChannel) {
                        if (pages == 0) {
                            indexCount = numFound.toInt()
                            // Photos the index cannot hold yet, plus those to be read again: the
                            // least this run reads. When the month's allowance covers fewer, say
                            // so once. However many there are, the run starts: it stops only if
                            // the battery gets low with no charger (Cip, 2026-09-16).
                            val atLeast = (local.size - indexCount).coerceAtLeast(0) + forced.size + needWords.size
                            expected = atLeast + withoutPlace.size
                            if (aiAvailable && limits != null) {
                                val left = limits.photosLeftThisMonth
                                if (left != null && left < atLeast) shortAllowance = warnShortAllowance(left, atLeast)
                            }
                        }
                        pages++
                        for ((id, size) in page) {
                            val photo = local[id]
                            if (photo == null) {
                                toDelete += id
                                if (toDelete.size >= 500) {
                                    solr.delete(toDelete)
                                    deleted += toDelete.size
                                    toDelete.clear()
                                }
                            } else {
                                unseen.remove(id)
                                // A different size is a changed photo: everything again, as if new.
                                if (size != photo.sizeBytes || id in forced || id in needWords || id in withoutPlace) queue(photo)
                            }
                        }
                        progress(if (total > 0) phase else "Comparing with your index")
                    }
                    }
                }
                if (toDelete.isNotEmpty()) {
                    solr.delete(toDelete)
                    deleted += toDelete.size
                    toDelete.clear()
                }
            } else {
                indexCount = 0
            }

            // New on the phone (or everything, on a rebuild).
            val newOnes = local.values.filter { it.id in unseen }
            // The diff is over: what is left to read is exactly known, so the estimate goes.
            expected = total + newOnes.size
            for (photo in newOnes) queue(photo)
            if (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                ingest(batch)
            }

            solr.commit()
            if (rebuild) prefs.rebuildApproved = false
            if (forced.isNotEmpty()) prefs.resyncIds = emptySet()
            cache.removeAllExcept(local.keys)
            refreshAccount(session, connection)
            var message = ""
            if (shortAllowance.isNotEmpty()) message = shortAllowance
            else if (withoutWords > 0) {
                // Nothing broke: the photos are in the index and found by their date, camera,
                // place, file name and tags; the words come when the plan allows them.
                val n = "$withoutWords photo${if (withoutWords == 1) "" else "s"}"
                message = if (!vectorAllowed)
                    "$n indexed by date, camera, place, file name and your tags only: your plan does not include photo recognition. Upgrade to have them read into words."
                else
                    "$n indexed without being read into words: the monthly AI requests of your plan are used up. They are read at the first sync after the allowance resets, or now after an upgrade."
            }
            val indexAfter = try { solr.count().toInt() } catch (e: Exception) { indexCount - deleted + added }
            return report("ok", added, deleted, failed, localCount, indexCount, message, recreated, indexAfter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChargerNeededException) {
            commitQuietly()
            return report("waiting_charger", added, deleted, failed, localCount, indexCount,
                "Paused at ${batteryPercent()}% battery after $added photos; ${Actions.formatCount(e.photos.toLong())} are left. It continues on its own when the phone is charging.", recreated)
        } catch (e: RetryLaterException) {
            commitQuietly()
            return report("retry_later", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: SignInRequiredException) {
            prefs.pendingNotice = "Your Opensolr sign-in stopped working. Sign in again to keep your photos in sync."
            Notifier.signInRequired(context)
            return report("sign_in_required", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: QuotaExceededException) {
            commitQuietly()
            val reset = e.resetsAt?.takeIf { it.isNotBlank() }?.let { " It resets on ${it.substringBefore(' ')}." } ?: ""
            Notifier.planLimit(
                context, "Monthly AI requests used up",
                "Opensolr Photos paused after indexing $added photos.$reset Upgrade your plan to continue now."
            )
            return report("stopped_quota", added, deleted, failed, localCount, indexCount, "The monthly AI requests of your plan are used up.$reset", recreated)
        } catch (e: PlanLimitException) {
            Notifier.planLimit(
                context, "Your photo index is full",
                "The index reached the disk space or search bandwidth of your Opensolr plan. Upgrade to keep syncing and searching."
            )
            return report("stopped_plan_limit", added, deleted, failed, localCount, indexCount, e.message ?: "", recreated)
        } catch (e: OpensolrException) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, friendlyMessage(e, "Sync failed"), recreated)
        } catch (e: Exception) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, "Sync stopped: ${friendlyMessage(e, "try again later")}", recreated)
        }
    }

    /**
     * Runs [block], waiting out rate limits (the server says how long) a few times before giving up.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        pace()
        try {
            return block()
        } catch (e: RateLimitedException) {
            // Refused anyway: the run stops here and the scheduler starts it again later, so
            // the phone sleeps in between instead of counting seconds inside the job.
            throw RetryLaterException(e.retryAfterSeconds)
        }
    }

    /**
     * Keeps this run under the account's API rate limits: before a call, when the calls of
     * the last minute or hour are close to the limit, waits just long enough for the oldest
     * to fall out of the window. With five photos per call the limits are far away in normal
     * use; this is for an account whose limits were lowered, or a much faster server.
     */
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
        // A wait past a minute is not worth holding the phone awake for: stop and come back.
        if (waitMs > MINUTE_MS) throw RetryLaterException(waitMs / 1000)
        if (waitMs > 0) delay(waitMs)
        callTimes.addLast(System.currentTimeMillis())
    }

    /** True while the phone is plugged in. */
    /**
     * Before a reset: the owner's tags and own wording that live only in the index (a
     * reinstall, another phone's edits) are copied into this phone's edits, so the full
     * re-sync hands them back to Opensolr with each photo. Edits already on the phone win.
     * Wording counts as the owner's only when it differs from CLIP's labels joined, the same
     * rule the server applies.
     */
    private suspend fun keepOwnersEdits(solr: SolrClient) {
        solr.forEachDoc("id,custom_tags,meaning,labels") { doc ->
            val id = doc.optString("id")
            if (id.isEmpty() || cache.getEdits(id) != null) return@forEachDoc
            val tags = doc.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            val labels = doc.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val meaning = doc.optString("meaning").trim()
            val own = meaning.takeIf { it.isNotEmpty() && it != labels.joinToString(", ") }
            if (tags.isNotEmpty() || own != null) cache.putEdits(id, PhotoCache.Edits(tags, own))
        }
    }

    /**
     * How much battery is left, as a percentage, from the same sticky broadcast the charging
     * check reads. Returns 100 when Android does not say, so an unknown battery never pauses a
     * sync.
     */
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

    /**
     * Runs [block] against the index, and when the index refuses its saved password, reads the
     * current one from the account once and tries again.
     */
    private suspend fun <T> withFreshPassword(session: Session, onRefreshed: (IndexConnection) -> Unit, block: suspend () -> T): T =
        try {
            block()
        } catch (e: SolrAuthException) {
            onRefreshed(indexes.refreshConnection(session))
            block()
        }

    /**
     * Updates the stored plan usage and warns when disk space or bandwidth is used up.
     */
    private suspend fun refreshAccount(session: Session, connection: IndexConnection) {
        try {
            val limits = api.accountSummary(session, connection.indexName, prefs.account)
            prefs.account = limits
            // One notification per situation: 90% and 100% of disk, bandwidth and AI requests,
            // and a plan without photo recognition.
            PlanWatch.notifyNew(context, prefs, limits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /**
     * Best-effort commit after a stopped run, so what was written stays visible.
     */
    private suspend fun commitQuietly() {
        try {
            prefs.connection?.let { SolrClient(it).commit() }
        } catch (e: Exception) {
        }
    }

    /**
     * A report stamped with the current time.
     */
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

    /**
     * Solr date format in UTC.
     */
    /**
     * Warns, once a month, that the remaining allowance covers [left] of the [toRead] photos
     * waiting to be read, and returns the sentence for the sync report.
     */
    private fun warnShortAllowance(left: Int, toRead: Int): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val text = "Your plan's AI requests cover $left of the ${Actions.formatCount(toRead.toLong())} photos waiting to be read this month. The others are indexed by date, camera, place, file name and your tags, and read at the first sync after the allowance resets, or now after an upgrade at opensolr.com/pricing."
        val key = "ai_short_$month"
        if (key !in prefs.warnedKeys) {
            Notifier.planLimit(context, "Only $left photos can be read this month", text, key)
            prefs.warnedKeys = prefs.warnedKeys + key
        }
        return text
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    companion object {
            /** Photos per photos_ingest call, the most the endpoint takes. */
        private const val READ_BATCH = 5

        /**
         * Below this much battery, with no charger, a run stops where it is and continues when
         * the phone is plugged in (Cip, 2026-09-16). However many photos there are, the run
         * starts: the number of them is not a reason to wait, only the battery is.
         */
        private const val BATTERY_PAUSE_PERCENT = 20

        /** Share of a rate limit this run keeps under, so a call is never refused. */
        private const val PACE_SHARE = 0.8
        private const val MINUTE_MS = 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L

        /**
         * The text a photo's vector is made of: what it shows (CLIP's words, or the owner's
         * own wording), followed by the owner's tags. One place, shared with the edit path.
         */
        fun embeddingText(doc: JSONObject): String {
            val tags = doc.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            val meaning = doc.optString("meaning").ifBlank { doc.optString("file_name").substringBeforeLast('.') }
            return if (tags.isEmpty()) meaning else meaning + ", " + tags.joinToString(", ")
        }
    }
}
