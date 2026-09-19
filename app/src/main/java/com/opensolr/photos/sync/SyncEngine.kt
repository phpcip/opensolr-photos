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
    private val cache = PhotoCache.of(context)
    private val indexes = IndexManager(context, prefs, api)
    private val edits = com.opensolr.photos.search.EditRepository(context)

    /** True once this run knows the plan has no AI for it (no vector search, or the month's allowance used up). */
    private var quotaHit = false

    /** Photos indexed this run without their words. */
    private var withoutWords = 0

    /** The warning of this run that the month's allowance covers fewer photos than there are to read, if any. */
    private var shortAllowance = ""

    /** The phone's position this run already looked up for new photos, and whether it did. */
    private var runFix: android.location.Location? = null
    private var runFixAsked = false

    /**
     * Places for the new photos of [batch] that came without a position (Cip, 2026-09-19), for a
     * camera with no GPS whose photos are copied onto the phone. Each is kept on the phone and
     * returned by photo id.
     *
     * New means it appeared after the owner switched this on, so nothing already on the phone is
     * ever touched; without a position means its original EXIF was read and holds none that can be
     * used - a position that exists is never replaced. For each such photo, in this order:
     * - the place of the phone's own photo taken closest in time, within [NEAR_PHOTO_MS]: the
     *   phone was there when the other camera was, whenever the photos are copied over;
     * - the phone's position now, only for a photo taken at most [DEVICE_WINDOW_MS] ago: later
     *   than that the owner may be far from where it was taken. Asked for once a run;
     * - nothing.
     * One query on the phone's copy of the index for the whole batch.
     */
    private suspend fun newPhotoPlaces(batch: List<LocalPhoto>, already: Set<String>): Map<String, PhotoCache.SetPlace> {
        val since = prefs.autoPlaceSince
        if (since <= 0L) return emptyMap()
        // When each was taken, really (MediaStore, or when it arrived), and the ways the index may
        // hold the time of the phone's own photos: a camera that writes no time zone is stored at
        // its wall-clock time as if it were UTC, so both readings are tried against them.
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

    /** Thrown mid-run when the battery is low with no charger; [photos] are left to read. */
    private class ChargerNeededException(val photos: Int) : Exception()

    /** Thrown mid-run when the owner pressed Stop; everything written so far stays written. */
    private class SyncStoppedException : Exception()

    /**
     * Runs one sync and returns what happened. Never throws except for cancellation.
     */
    suspend fun run(onProgress: suspend (Progress) -> Unit): SyncReport {
        val session = prefs.session ?: return report("sign_in_required", message = AppText.s(R.string.sy_sign_in))
        var localCount = 0
        var indexCount = 0
        var added = 0
        var deleted = 0
        var failed = 0
        var recreated = false
        // The fingerprints worked out while comparing, kept for the photos that go on to be sent:
        // 32 characters per changed photo, and it saves reading each of those files whole a second
        // time (Cip, 2026-09-18).
        val weighed = HashMap<String, String>()
        quotaHit = false
        withoutWords = 0
        shortAllowance = ""
        runFix = null
        runFixAsked = false

        try {
            onProgress(Progress(AppText.s(R.string.sy_checking), 0, 0))
            val (initialConnection, outcome) = indexes.ensure(session) { onProgress(Progress(it, 0, 0)) }
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
            // Taken before the scan, so a change made while this run works is newer than it and
            // the next look at the folders still sees it.
            val foldersBefore = MediaScanner.folderStamp(context, prefs.folders)
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
                onProgress(Progress(AppText.s(R.string.sy_resetting), 0, 0))
                withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { keepOwnersEdits(solr) }
                solr.deleteAll()
                indexes.applyConfig(session, connection) { onProgress(Progress(it, 0, 0)) }
                // The configuration is live and the index is empty: from here an interrupted run
                // continues as an ordinary sync, and a later version asks the owner again.
                prefs.rebuildApproved = false
            }

            // Photos that go through everything again whatever their size says: those the owner
            // picked for Re-sync, and those indexed without words while the plan had no AI (only
            // now that it has), except the ones still pausing after a miss. A missing place
            // never sends a photo again.
            val forced = prefs.resyncIds
            forced.forEach { cache.clearWordRetry(it); cache.clearSkipped(it) }
            val skippedSizes = cache.skippedSizes()
            val rereadSince = prefs.rereadAllSince
            val phase = if (rebuild) AppText.s(R.string.sy_rebuilding) else AppText.s(R.string.sy_indexing)

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
                // Stop was pressed. Checked here rather than left to WorkManager: cancelling the
                // work cannot interrupt a batch already on its way, so a run of ten thousand
                // photos would carry on for hours after the owner asked it to stop.
                if (SyncWorker.stopRequested.get()) {
                    throw SyncStoppedException()
                }
                val items = ArrayList<IngestItem>(batch.size)
                // The places this app gave the batch's photos, read in one query for the batch,
                // and places for the new ones that came without.
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
                    // The md5 of the file itself goes up with it: the server only sees the small
                    // re-encoded copy, so it could never work this out on its own.
                    // Tags edited on this phone first; otherwise the ones this app wrote into the file
                    // (opensolr:Tags only, never another app's keywords), so a reinstall keeps them.
                    // The file's own header is opened once for all three, and not at all when this
                    // phone already holds every one of them (Cip, 2026-09-18).
                    val xmp = if (edits?.tags != null && edits.meaning != null && edits.persons != null) null
                        else PhotoReader.xmpWordsIn(context, photo)
                    val tags = edits?.tags ?: xmp?.tags
                    // The same for the owner's wording of what the photo shows (opensolr:Meaning).
                    val meaning = edits?.meaning ?: xmp?.meaning
                    val names = edits?.persons ?: xmp?.persons ?: emptyList()
                    items += IngestItem(photo, jpeg, tags, meaning, weighed[photo.id] ?: PhotoReader.fileMd5(context, photo), names)
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
                      // One commit for the batch, not four per photo: writing a photo's document,
                      // dropping it from the queue and clearing its two retry marks were four
                      // separate transactions each (Cip, 2026-09-18).
                      cache.inTransaction {
                        items.forEachIndexed { k, item ->
                            val result = r.getOrNull(k)
                            if (result == null) {
                                failed++
                            } else {
                                added++
                                cache.clearSkipped(item.photo.id)
                                // The document as the server wrote it goes straight into the
                                // phone's own copy of the index, so nothing has to be asked back.
                                result.doc?.let { edits.storeDoc(item.photo.id, it) }
                                cache.clearActions(listOf(item.photo.id))
                                // Its place, if this app gave it one, went up on the copy.
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

            // The diff, entirely on the phone. The index is never walked: this app is the only
            // thing that ever writes to that index, so a copy of what it holds - id, size, the
            // words, the owner's tags and names - is kept here and brought in step by every write
            // (Cip, 2026-09-18). After a reinstall that copy is empty and is read back once.
            val toDelete = ArrayList<String>(500)
            if (!rebuild) {
                if (edits.cloneMissing()) {
                    onProgress(Progress(AppText.s(R.string.sy_reading_once), 0, 0))
                    withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { edits.readIndexIntoCache() }
                }
                val known = cache.docSizes()
                indexCount = known.size
                onProgress(Progress(AppText.s(R.string.sy_comparing), 0, 0))
                // Gone from the phone, so gone from the index.
                known.keys.filter { it !in local }.forEach { toDelete += it }
                // Photos with no words although the plan has AI, unless they are still pausing
                // after a miss; and, for "Re-read all", everything written before it was asked for.
                val waiting = cache.wordRetriesWaiting(System.currentTimeMillis())
                // What is already queued, read once: asking the database per photo meant one query
                // for every photo in the library, every sync (Cip, 2026-09-18).
                val queuedWords = cache.actions(PhotoCache.ACTION_WORDS).toSet()
                val toIndex = ArrayList<String>()
                val needWords = if (!aiAvailable) emptySet() else cache.docsWithoutWords().toSet() - waiting
                val reread = if (rereadSince > 0) cache.docsIndexedBefore(rereadSince).toSet() else emptySet()
                // Everything this run has to read, known before it starts: no estimate needed.
                local.values.forEach { photo ->
                    val stamp = known[photo.id]
                    // Touched at all - a different size, or written at a different time - means the
                    // picture goes through Opensolr again. An unknown time (0) is not a change:
                    // photos indexed before the phone kept one must not all be read again.
                    val changed = stamp == null || stamp.first != photo.sizeBytes ||
                        (stamp.second > 0 && stamp.second != photo.modifiedSec)
                    val waitingWords = photo.id in queuedWords
                    if (changed && waitingWords && stamp != null) {
                        // The app itself wrote this file moments ago, putting the owner's words in
                        // its header. Only the words go up; the phone's copy takes the file as it
                        // now stands, md5 included (Cip, 2026-09-18).
                        cache.updateDocSize(photo.id, photo.sizeBytes, photo.modifiedSec, PhotoReader.fileMd5(context, photo))
                        return@forEach
                    }
                    if (changed && stamp != null) {
                        // A photo that looks touched is weighed exactly, and only then: the md5 of
                        // the file, which the index already holds for it. Same md5 - the picture is
                        // the same one and only its header was written (the owner's words, by this
                        // app), so nothing is read again and the phone's copy takes the file as it
                        // now stands. Neither the size nor the time it was written can be trusted
                        // on their own (Cip, 2026-09-18).
                        val was = cache.docFileHash(photo.id)
                        // Weighed once. The photo that turns out to have really changed is sent a
                        // moment later and its fingerprint travels with it, so reading the whole
                        // file a second time for the same number is pure waste (Cip, 2026-09-18).
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
                // One transaction for the lot, not one write per photo.
                cache.queueActions(toIndex, PhotoCache.ACTION_INDEX)
            } else {
                indexCount = 0
                // A rebuild empties the index, so the phone's copy of it goes with it and every
                // photo is written again.
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

            // The photos waiting to be read, picture and all.
            val queued = cache.actions(PhotoCache.ACTION_INDEX).mapNotNull { local[it] }
            expected = queued.size
            for (photo in queued) queue(photo)
            if (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                ingest(batch)
            }

            solr.commit()
            // Every photo is in the index by now, so the words the owner changed - on one photo or
            // on a thousand at once - are carried up here, fifty per call and no pictures.
            try {
                edits.sendWords { done, all ->
                    onProgress(Progress(AppText.s(R.string.sy_saving_words, Actions.formatCount(done.toLong()), Actions.formatCount(all.toLong())), done, all))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The photos are indexed; the words stay queued and go up at the next sync.
            }
            if (rebuild) prefs.rebuildApproved = false
            if (forced.isNotEmpty()) prefs.resyncIds = emptySet()
            if (rereadSince > 0) prefs.rereadAllSince = 0L
            cache.removeAllExcept(local.keys)
            cache.keepSkippedOnly(local.keys)
            refreshAccount(session, connection)
            var message = ""
            if (shortAllowance.isNotEmpty()) message = shortAllowance
            else if (withoutWords > 0) {
                // Nothing broke: the photos are in the index and found by their date, camera,
                // place, file name and tags; the words come when the plan allows them.
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
            // Asked to stop: what is written stays written, and the next sync carries on from
            // there, because the diff is on what the index holds rather than on a position.
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
        // Which photos already have edits on this phone, read once: asking per document meant a
        // query for every photo in the index (Cip, 2026-09-18).
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
        /** How far in time the phone's own photo may be from the other camera's. */
        private const val NEAR_PHOTO_MS = 30 * 60 * 1000L

        /** How old a photo may be and still get the phone's position at the moment it arrives. */
        private const val DEVICE_WINDOW_MS = 2 * 60 * 60 * 1000L

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
         * The text a photo's vector is made of, in order of importance (Cip, 2026-09-17): the
         * people in it, the owner's tags, what it shows (CLIP's words, or the owner's own
         * wording), then the country, city and region. The server builds the same text at
         * indexing (Api_lib::_photos_embedding_text). One place, shared with the edit path.
         */
        fun embeddingText(doc: JSONObject): String {
            fun list(field: String) = doc.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val persons = list("persons_ss").ifEmpty { doc.optString("persons_t").split(',') }
            val meaning = doc.optString("meaning").ifBlank { doc.optString("file_name").substringBeforeLast('.') }
            return (persons + list("custom_tags") + listOf(meaning, doc.optString("country"), doc.optString("city"), doc.optString("region")))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        }
    }
}
