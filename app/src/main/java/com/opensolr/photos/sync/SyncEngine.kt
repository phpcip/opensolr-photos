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
                return report("rebuild_required", message = "This version of Opensolr Photos comes with a newer index configuration. Your index must be rebuilt before syncing continues.")
            }

            onProgress(Progress("Looking for photos", 0, 0))
            val local = MediaScanner.scan(context, prefs.folders)
            localCount = local.size

            onProgress(Progress("Comparing with your index", 0, 0))
            // The index's side of the diff: every photo it holds, with the file size it had.
            var sizes = withFreshPassword(session, { connection = it; solr = SolrClient(it) }) {
                solr.allSizes { onProgress(Progress("Comparing with your index", it, 0)) }
            }
            var remote: Set<String> = sizes.keys
            indexCount = remote.size

            // A photo in both places with a different size is a changed photo: it goes through
            // everything again, as if new, whatever the index still holds about it.
            val changed = local.values.filter { p -> sizes[p.id].let { it != null && it != p.sizeBytes } }.map { it.id }.toHashSet()

            if (rebuild) {
                // New configuration in, every document out, every photo handed to Opensolr
                // again below; CLIP and the embedder answer from their caches, so it costs no
                // AI requests, only the upload.
                onProgress(Progress("Rebuilding your index", 0, 0))
                indexes.applyConfig(session, connection) { onProgress(Progress("Rebuilding your index", 0, 0)) }
                solr.deleteAll()
                remote = emptySet()
                sizes = emptyMap()
            }

            val toDelete = remote - local.keys
            if (toDelete.isNotEmpty()) {
                onProgress(Progress("Removing deleted photos", 0, toDelete.size))
                toDelete.chunked(500).forEach { batch ->
                    solr.delete(batch)
                    deleted += batch.size
                    onProgress(Progress("Removing deleted photos", deleted, toDelete.size))
                }
            }

            // Photos whose place lookup did not succeed last time are written again (from the
            // cache, so without AI requests) so the place words get another chance.
            val withoutPlace = if (rebuild) emptySet() else withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { solr.idsWithoutPlace() }
            // The plan as it is now, not as it was at the last look: an upgrade since then
            // (vector search, a new AI allowance) takes effect in this very run.
            refreshAccount(session, connection)
            val limits = prefs.account
            var vectorAllowed = limits?.vectorAllowed ?: false
            // Without vector search on the plan, photos are not read into words either: they
            // are indexed by what the phone knows and found by those words. Same when the
            // month's AI requests are already used up. Nothing is decoded or asked for then.
            val aiAvailable = vectorAllowed && (limits == null || limits.maxAiRequests <= 0 || limits.aiRequestsUsed < limits.maxAiRequests)
            if (!aiAvailable) quotaHit = true
            // Photos indexed without their words (no AI at the time) are read by CLIP now, and
            // only now: when the plan allows it. Otherwise they stay as they are, untouched.
            val needWords = if (rebuild || !aiAvailable) emptySet() else solr.idsWithoutWords()
            // Photos the user asked to read again, and photos edited in place (same path, so
            // the same id, but a different size or time): both go through CLIP again.
            val forced = prefs.resyncIds
            val toAdd = if (rebuild) local.values.toList() else local.values.filter {
                it.id !in remote || it.id in changed || it.id in withoutPlace || it.id in forced || it.id in needWords
            }
            val phase = if (rebuild) "Rebuilding your index" else "Indexing photos"
            // Photos this run will ask CLIP about: not known to the cache as current, or asked
            // to be read again. When the month's allowance covers fewer than that, say so once:
            // the rest stays without words until the allowance resets.
            val toRead = if (!aiAvailable) 0 else toAdd.count { it.id !in remote || it.id in changed || it.id in forced || it.id in needWords }
            if (aiAvailable && limits != null) {
                val left = limits.photosLeftThisMonth
                if (left != null && left < toRead) shortAllowance = warnShortAllowance(left, toRead)
            }
            // Reading thousands of photos takes hours of background work: on battery, that
            // waits for the charger. The run that follows carries the charger tag and reads
            // everything; small runs are never held back.
            if (!unlimited && toRead > CHARGER_THRESHOLD && !isCharging()) {
                return report("waiting_charger", 0, deleted, 0, localCount, indexCount,
                    "${Actions.formatCount(toRead.toLong())} photos are waiting to be read. That takes a while, so it runs when the phone is charging.", recreated)
            }
            onProgress(Progress(phase, 0, toAdd.size))

            // Each photo goes to Opensolr once, five per call, with its EXIF and the owner's
            // edits when this phone has them. The server does the rest (words, vector, place,
            // the document, the index write) and answers what each photo got.
            for (batch in toAdd.chunked(READ_BATCH)) {
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
                    items += IngestItem(photo, jpeg, edits?.tags, edits?.meaning)
                }
                if (items.isNotEmpty()) {
                    val results = try {
                        retrying { api.photosIngest(session, connection.indexName, items) }
                    } catch (e: PhotoRejectedException) {
                        // The whole batch refused: counted as failed, tried again next time.
                        failed += items.size
                        onProgress(Progress(phase, added + failed, toAdd.size))
                        continue
                    } catch (e: ServiceException) {
                        failed += items.size
                        onProgress(Progress(phase, added + failed, toAdd.size))
                        continue
                    }
                    items.forEachIndexed { k, item ->
                        val result = results.getOrNull(k)
                        if (result == null) {
                            failed++
                        } else {
                            added++
                            if (!result.words) withoutWords++
                        }
                    }
                }
                onProgress(Progress(phase, added + failed, toAdd.size))
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
            return report("failed", added, deleted, failed, localCount, indexCount, e.message ?: "Sync failed", recreated)
        } catch (e: Exception) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, "Sync stopped: ${e.message ?: e.javaClass.simpleName}", recreated)
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

        /** More photos to read than this, and a run on battery waits for the charger. */
        private const val CHARGER_THRESHOLD = 500

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
