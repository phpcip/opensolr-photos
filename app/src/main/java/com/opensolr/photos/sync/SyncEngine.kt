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

    // A sync right after a full index check skips it; if the quick run cannot write, it runs again with the check.
    suspend fun run(onProgress: suspend (Progress) -> Unit): SyncReport {
        val quick = indexRecentlyChecked()
        val report = runOnce(onProgress, quick)
        if (quick && (report.status == "failed" || (report.failed > 0 && report.added == 0))) {
            prefs.indexChecked = null
            return runOnce(onProgress, false)
        }
        return report
    }

    private fun indexRecentlyChecked(): Boolean {
        val connection = prefs.connection ?: return false
        if (prefs.rebuildApproved || prefs.chosenIndexName == null) return false
        val parts = prefs.indexChecked?.split('|') ?: return false
        if (parts.size != 3 || parts[0] != connection.indexName || parts[0] != indexes.indexName) return false
        if (parts[1] != IndexManager.CONFIG_VERSION.toString()) return false
        val at = parts[2].toLongOrNull() ?: return false
        return System.currentTimeMillis() - at in 0..QUICK_CHECK_MS
    }

    private fun noteIndexChecked(connection: IndexConnection) {
        prefs.indexChecked = "${connection.indexName}|${IndexManager.CONFIG_VERSION}|${System.currentTimeMillis()}"
    }

    /** The id a clone document gets under the path-inside-the-volume rule, from what the document says of its file. */
    private fun relativeId(json: String): String? = runCatching {
        val doc = JSONObject(json)
        val folder = doc.optString("folder")
        val name = doc.optString("file_name")
        if (name.isBlank()) null else MediaScanner.photoId(MediaScanner.folderKey(folder) + name)
    }.getOrNull()

    /** The clone moved to the path-inside-the-volume ids: once for what the phone holds, and after every read of an index. */
    private fun migrateIds() {
        cache.migrateIds(::relativeId)
        prefs.idsRelative = true
    }

    private suspend fun runOnce(onProgress: suspend (Progress) -> Unit, quick: Boolean): SyncReport {
        val session = prefs.session ?: return report("sign_in_required", message = AppText.s(R.string.sy_sign_in))
        if (!prefs.idsRelative) migrateIds()
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
        var onPhone: Set<String>? = null

        try {
            val checked = if (quick) prefs.connection else null
            if (checked == null) onProgress(Progress(AppText.s(R.string.sy_checking), 0, 0))
            val prefetched = if (checked != null) null else try {
                api.syncInfo(session, indexes.indexName, prefs.account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val (initialConnection, outcome) = if (checked != null) checked to IndexManager.Outcome.EXISTING
                else indexes.ensure(session, prefetched) { onProgress(Progress(it, 0, 0)) }
            if (checked == null && outcome in setOf(IndexManager.Outcome.EXISTING, IndexManager.Outcome.CREATED, IndexManager.Outcome.RECREATED)) {
                noteIndexChecked(initialConnection)
            }
            recreated = outcome == IndexManager.Outcome.RECREATED
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
            // Android not answering is not "no photos": nothing is compared, nothing is removed
            val local = MediaScanner.scan(context, prefs.folders)
                ?: return report("failed", message = AppText.s(R.string.sy_cannot_read))
            localCount = local.size
            onPhone = local.keys

            val prefetchedAccount = prefetched?.accountFor(connection.indexName)
            if (prefetchedAccount != null) {
                prefs.account = prefetchedAccount
                PlanWatch.notifyNew(context, prefs, prefetchedAccount)
            } else if (checked == null || prefs.account == null) {
                refreshAccount(session, connection)
            }
            val limits = prefs.account
            var vectorAllowed = limits?.vectorAllowed ?: false

            val aiAvailable = vectorAllowed && (limits == null || limits.maxAiRequests <= 0 || limits.aiRequestsUsed < limits.maxAiRequests)
            if (!aiAvailable) quotaHit = true

            // The local clone is the source of truth: an index emptied for a new configuration (or by Reset,
            // prefs.restoreFromClone) is filled again from it, and no photo is sent again.
            var refill = false
            if (rebuild) {
                // the clone is read before the index goes, so the index comes back from it and nothing is sent again
                if (edits.cloneMissing()) {
                    onProgress(Progress(AppText.s(R.string.sy_reading_once), 0, 0))
                    withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { edits.readIndexIntoCache() }
                    migrateIds()
                }
                onProgress(Progress(AppText.s(R.string.sy_resetting), 0, 0))
                solr.deleteAll()
                indexes.applyConfig(session, connection) { onProgress(Progress(it, 0, 0)) }
                noteIndexChecked(connection)
                prefs.rebuildApproved = false
                refill = cache.docCount() > 0
            }
            if (prefs.restoreFromClone) refill = true
            if (refill) {
                // until every document is back, the next sync starts the refill again (a rewrite is harmless)
                prefs.restoreFromClone = true
                withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { restoreFromClone(session, connection, onProgress) }
                prefs.restoreFromClone = false
                prefs.cloneComplete = true
            }
            val rebuilt = rebuild && !refill

            val forced = prefs.resyncIds
            val wordingReset = prefs.wordingResetIds
            forced.forEach { cache.clearWordRetry(it); cache.clearSkipped(it) }
            val skippedSizes = cache.skippedSizes()
            val rereadSince = prefs.rereadAllSince
            val phase = if (rebuilt) AppText.s(R.string.sy_rebuilding) else AppText.s(R.string.sy_indexing)

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
                val people = com.opensolr.photos.search.FaceMatcher.people(cache)
                for (photo in batch) {
                    // the file is read once: its bytes give the md5, one decode gives the copy for the server,
                    // the picture hash and the image the face finder reads
                    val read = try {
                        PhotoReader.readForIngest(context, photo, placed[photo.id] ?: guessed[photo.id])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val jpeg = read?.jpeg
                    if (jpeg == null || read == null) {
                        // a file that is gone from the disk but still listed by Android (a stale MediaStore row)
                        // is not a photo to keep: the row is dropped, and the next sync takes it out of the index
                        if (!PhotoReader.fileExists(context, photo)) {
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(photo.absolutePath), null, null)
                            cache.removeIncoming(listOf(photo.id))
                            continue
                        }
                        cache.markSkipped(photo, PhotoReader.unreadableReason(context, photo))
                        cache.removeIncoming(listOf(photo.id))
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
                    var names = edits?.persons ?: xmp?.persons ?: emptyList()
                    // the faces, read once here for a new or changed picture, go up with it and stay in the index
                    val faces = try { readFaces(photo, people, read.upright) } finally { read.upright?.recycle() }
                    if (faces != null && edits == null && names.isEmpty()) {
                        names = cache.facesOf(photo.id).mapNotNull { it.person }.distinctBy { it.lowercase() }
                    }
                    items += IngestItem(
                        photo, jpeg, tags, meaning, weighed[photo.id] ?: read.md5, names, resetWording = cleared || photo.id in wordingReset,
                        ownerTags = edits != null, ownerPersons = edits?.persons != null, ownerMeaning = !edits?.meaning.isNullOrEmpty(),
                        faces = faces, pixelHash = read.pixelHash,
                    )
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
                                cache.removeIncoming(listOf(item.photo.id))
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
            suspend fun sendPendingEdits() {
                if (edits.pendingCount() == 0) return
                try {
                    edits.sendWords()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }

            // A photo the owner asked to be read again while this sync is running does not wait for
            // the next one, nor behind the thousands still queued: it goes up with the next batch.
            suspend fun readNow() {
                val waiting = (prefs.resyncIds - sent).mapNotNull { local[it] }
                if (waiting.isEmpty()) return
                waiting.chunked(READ_BATCH).forEach { batch ->
                    batch.forEach { sent += it.id; total++ }
                    ingest(batch)
                }
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
                    // Between batches, whatever the owner touched goes up first: their edits, and
                    // the photos they asked to have read again.
                    sendPendingEdits()
                    readNow()
                }
            }

            val toDelete = ArrayList<String>(500)
            var indexedIds: Set<String> = emptySet()
            var indexShort = ""
            if (!rebuilt) {
                if (edits.cloneMissing()) {
                    onProgress(Progress(AppText.s(R.string.sy_reading_once), 0, 0))
                    withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { edits.readIndexIntoCache() }
                    migrateIds()
                }
                // the index against the clone: an emptied index is filled back from the clone, a short one is reported
                val inClone = cache.docCount() + cache.parkedCount()
                val inIndex = withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { solr.count() }
                if (inIndex == 0L && inClone > 0) {
                    prefs.restoreFromClone = true
                    withFreshPassword(session, { connection = it; solr = SolrClient(it) }) { restoreFromClone(session, connection, onProgress) }
                    prefs.restoreFromClone = false
                } else if (inIndex < inClone) {
                    indexShort = AppText.s(R.string.sy_index_short, Actions.formatCount(inIndex), Actions.formatCount(inClone.toLong()))
                }
                // a folder added back brings its kept photos back as they were: nothing is read again
                val roots = com.opensolr.photos.search.SearchFilters.folderRoots(prefs.folders)
                cache.unparkInside(roots)
                cache.parkOutside(roots)
                dropGoneParked(solr)
                val known = cache.docSizes()
                indexedIds = known.keys
                indexCount = known.size
                onProgress(Progress(AppText.s(R.string.sy_comparing), 0, 0))

                // many photos gone at once is a phone that cannot see them, not an owner who deleted them:
                // they stay in the index until the owner says otherwise on the Sync screen
                val missing = known.keys.filter { it !in local }
                if (missing.size * 2 > known.size && !prefs.removeMissingApproved) {
                    prefs.missingHeld = missing.size
                    shortAllowance = AppText.s(R.string.sy_missing_many, Actions.formatCount(missing.size.toLong()))
                } else {
                    prefs.missingHeld = 0
                    prefs.removeMissingApproved = false
                    toDelete += missing
                }

                val waiting = cache.wordRetriesWaiting(System.currentTimeMillis())

                val toIndex = ArrayList<String>()
                val needWords = if (!aiAvailable) emptySet() else cache.docsWithoutWords().toSet() - waiting
                val reread = if (rereadSince > 0) cache.docsIndexedBefore(rereadSince).toSet() else emptySet()
                // a known photo whose document points at another phone's file (or an old MediaStore row): the
                // document takes this phone's id and path, as data, with the next words batch
                val where = cache.docMedia()
                val relocated = ArrayList<String>()

                local.values.forEach { photo ->
                    val stamp = known[photo.id]
                    if (stamp != null) {
                        val was = where[photo.id]
                        if (was != null && (was.first != photo.mediaId || was.second != photo.absolutePath)) {
                            cache.relocateDoc(photo.id, photo.mediaId, photo.absolutePath)
                            relocated += photo.id
                        }
                    }

                    val changed = stamp == null || stamp.first != photo.sizeBytes ||
                        (stamp.second > 0 && stamp.second != photo.modifiedSec)
                    if (changed && stamp != null) {
                        // the file changed: the same picture (its pixels) means only the metadata did, and a photo
                        // is sent again only when the picture itself changed
                        val now = PhotoReader.fileMd5(context, photo)
                        if (now != null) weighed[photo.id] = now
                        val wasPixels = cache.doc(photo.id)?.json?.let { runCatching { org.json.JSONObject(it).optString("pixel_hash") }.getOrNull() }?.ifBlank { null }
                        val nowPixels = if (wasPixels == null) null else PhotoReader.pixelHash(context, photo.uri)
                        val samePicture = (cache.docFileHash(photo.id)?.let { it == now } ?: false) || (wasPixels != null && wasPixels == nowPixels)
                        if (samePicture) {
                            cache.updateDocSize(photo.id, photo.sizeBytes, photo.modifiedSec, now)
                            if (wasPixels != null) takeWordsFromFile(photo)
                            if (photo.id !in forced && photo.id !in needWords && photo.id !in reread) return@forEach
                        }
                    }
                    if (changed || photo.id in forced || photo.id in needWords || photo.id in reread) {
                        toIndex += photo.id
                    }
                }

                cache.queueActions(toIndex, PhotoCache.ACTION_INDEX)
                cache.queueActions(relocated.filter { it !in toIndex }, PhotoCache.ACTION_WORDS)
            } else {
                indexCount = 0

                cache.clearDocs()
                prefs.cloneComplete = true
                cache.queueActions(local.keys, PhotoCache.ACTION_INDEX)
            }

            if (toDelete.isNotEmpty()) {
                // a photo moved or renamed is the same file under a new name: what the owner put on it moves with it
                carryOver(toDelete, local.values.filter { it.id !in indexedIds })
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
            // new photos show in the list at once, marked as syncing, until their document comes back
            val arriving = queued.filter { it.id !in indexedIds && skippedSizes[it.id] != it.sizeBytes }
            if (arriving.isNotEmpty()) {
                val exact = arriving.size <= EXACT_TAKEN_MAX
                cache.putIncoming(arriving) { takenForList(it, exact) }
                progress(phase)
            }
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
            // only what this run actually sent: anything asked for in the meantime stays queued
            if (forced.isNotEmpty() || sent.isNotEmpty()) prefs.resyncIds = prefs.resyncIds - sent
            if (wordingReset.isNotEmpty()) prefs.wordingResetIds = prefs.wordingResetIds - wordingReset
            if (rereadSince > 0) prefs.rereadAllSince = 0L
            cache.removeAllExcept(local.keys)
            cache.keepSkippedOnly(local.keys)
            refreshAccount(session, connection)
            var message = ""
            if (indexShort.isNotEmpty()) message = indexShort
            else if (shortAllowance.isNotEmpty()) message = shortAllowance
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
        } catch (e: RestoreRefusedException) {
            commitQuietly()
            return report("failed", added, deleted, failed, localCount, indexCount, AppText.s(R.string.sy_restore_refused, Actions.formatCount(e.refused.toLong())), recreated)
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
        } finally {
            try {
                cache.pruneIncoming(onPhone)
            } catch (e: Exception) {
            }
        }
    }

    private fun takenForList(photo: LocalPhoto, exact: Boolean): Long =
        (if (exact) PhotoReader.takenAsIndexed(context, photo.uri) else null)
            ?: photo.dateTakenMs.takeIf { it > 0 }
            ?: (photo.addedSec * 1000L).takeIf { it > 0 }
            ?: (photo.modifiedSec * 1000L)

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

    /** Kept photos of removed folders that were deleted from the phone meanwhile leave the index too. */
    private suspend fun dropGoneParked(solr: SolrClient) {
        val gone = ArrayList<String>()
        cache.forEachParkedMedia { rows ->
            val alive = MediaScanner.existing(context, rows.map { it.second }.filter { it > 0 })
            val missing = rows.filter { it.second <= 0 || it.second !in alive }
            // a MediaStore id can change on a rescan: the file itself is looked for before anything is dropped
            val onDisk = MediaScanner.existingPaths(context, missing.map { it.third }.filter { it.isNotEmpty() })
            missing.forEach { (id, _, path) -> if (path !in onDisk) gone += id }
        }
        val parked = cache.parkedCount()
        if (parked > 0 && gone.size * 2 > parked) return
        gone.chunked(500).forEach { batch ->
            solr.delete(batch)
            cache.dropParked(batch)
        }
    }

    /**
     * Photos about to leave the index whose file is still on the phone under another name (same md5): their tags,
     * people, wording and place are given to the new name before it is read, so they are sent and written again.
     */
    private fun carryOver(leaving: Collection<String>, arriving: List<LocalPhoto>) {
        if (arriving.isEmpty()) return
        val byHash = HashMap<String, String>()
        leaving.forEach { id -> cache.docFileHash(id)?.let { byHash[it] = id } }
        if (byHash.isEmpty()) return
        // only files of a size one of the leaving photos had are hashed, so a big import costs nothing extra
        val sizes = leaving.mapNotNull { cache.doc(it)?.sizeBytes }.toHashSet()
        for (photo in arriving) {
            if (photo.sizeBytes !in sizes) continue
            val hash = PhotoReader.fileMd5(context, photo) ?: continue
            val from = byHash[hash] ?: continue
            val doc = cache.doc(from) ?: continue
            val own = cache.getEdits(from)
            // the owner's wording: theirs on this phone, else a meaning that is not just the model's labels joined
            val labels = runCatching { org.json.JSONObject(doc.json ?: "{}").optJSONArray("labels") }.getOrNull()
                ?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
            val wording = own?.meaning ?: doc.meaning?.takeIf { it.isNotBlank() && it != labels }
            cache.putEdits(photo.id, PhotoCache.Edits(own?.tags ?: doc.tags, wording, own?.persons ?: doc.persons))
            val place = cache.setPlaces(listOf(from))[from]
                ?: com.opensolr.photos.search.parseLatLon(runCatching { org.json.JSONObject(doc.json ?: "{}").optString("location") }.getOrNull())
                    ?.let { PhotoCache.SetPlace(photo.id, it.first, it.second, owner = true, written = false) }
            place?.let { cache.putSetPlace(it.copy(id = photo.id, written = false, synced = false)) }
        }
    }

    /**
     * The faces of a photo about to go up: read now unless this phone already has them for this file, the names
     * the file carries put on them, then the people already known matched. As one string for the index.
     */
    private fun readFaces(photo: LocalPhoto, people: Map<String, List<FloatArray>>, upright: android.graphics.Bitmap?): String? {
        cache.adoptFaces(photo.id, photo.mediaId)
        if (!cache.faceScanned(photo.id, photo.sizeBytes)) {
            val bitmap = upright ?: return null
            val found = try { com.opensolr.photos.media.FaceEngine.of(context).analyze(bitmap) } catch (e: Exception) { return null }
            com.opensolr.photos.search.FaceMatcher.fromFile(context, cache, photo.uri, cache.putFaces(photo.id, photo.mediaId, photo.sizeBytes, found))
            com.opensolr.photos.search.FaceMatcher.nameOnlyFace(cache, photo.id)
            val stored = cache.facesWithVectors(photo.id)
            com.opensolr.photos.search.FaceMatcher.autoName(cache, edits, photo.id, stored.map { it.first }, stored.map { it.second }, people, queueWords = false)
        }
        return cache.facesForIndex(photo.id, photo.sizeBytes)
    }

    /** A file whose picture did not change but whose metadata did (tags, people, a place put in by another app): the index takes what is new. */
    private fun takeWordsFromFile(photo: LocalPhoto) {
        val doc = cache.doc(photo.id) ?: return
        val file = runCatching { PhotoReader.xmpWordsIn(context, photo) }.getOrNull() ?: return
        val had = cache.getEdits(photo.id)
        val tags = ((had?.tags ?: doc.tags) + file.tags.orEmpty()).distinctBy { it.lowercase() }
        val persons = ((had?.persons ?: doc.persons) + file.persons).distinctBy { it.lowercase() }
        val meaning = had?.meaning ?: file.meaning?.takeIf { doc.meaning.isNullOrBlank() }
        if (tags.size != (had?.tags ?: doc.tags).size || persons.size != (had?.persons ?: doc.persons).size || (meaning != null && meaning != had?.meaning)) {
            cache.putEdits(photo.id, PhotoCache.Edits(tags, meaning, persons))
            cache.queueAction(photo.id, PhotoCache.ACTION_WORDS)
        }
        if (cache.setPlaces(listOf(photo.id)).isEmpty() && doc.json?.contains("\"location\"") != true) {
            PhotoReader.readMetadata(context, photo).let { m ->
                if (m.latitude != null && m.longitude != null) {
                    cache.putSetPlace(PhotoCache.SetPlace(photo.id, m.latitude, m.longitude, owner = true, written = true, synced = false))
                    cache.queueAction(photo.id, PhotoCache.ACTION_WORDS)
                }
            }
        }
    }

    /** The emptied index filled again from the local clone, in batches; the server makes the search vector and the place names again. */
    private suspend fun restoreFromClone(session: Session, connection: IndexConnection, onProgress: suspend (Progress) -> Unit) {
        val total = cache.docCount()
        var done = 0
        cache.forEachDocJson(RESTORE_BATCH) { batch ->
            if (SyncWorker.stopRequested.get()) throw SyncStoppedException()
            // the clone keeps a document as the server answered it, answer keys included: those are not fields
            val docs = batch.map { json ->
                runCatching { org.json.JSONObject(json).apply { ANSWER_KEYS.forEach { remove(it) } }.toString() }.getOrDefault(json)
            }
            val written = api.photosRestore(session, connection.indexName, docs)
            val refused = written.count { !it } + (docs.size - written.size).coerceAtLeast(0)
            if (refused > 0) throw RestoreRefusedException(refused)
            done += batch.size
            onProgress(Progress(AppText.s(R.string.sy_restoring), done, total))
        }
    }

    private class RestoreRefusedException(val refused: Int) : Exception()

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
        private const val RESTORE_BATCH = 50
        private val ANSWER_KEYS = listOf("status", "msg", "error", "results", "score", "_version_")

        private const val NEAR_PHOTO_MS = 30 * 60 * 1000L

        private const val DEVICE_WINDOW_MS = 2 * 60 * 60 * 1000L

        private const val READ_BATCH = 5

        private const val QUICK_CHECK_MS = 24 * 60 * 60 * 1000L

        private const val EXACT_TAKEN_MAX = 200

        private const val BATTERY_PAUSE_PERCENT = 20

        private const val PACE_SHARE = 0.8
        private const val MINUTE_MS = 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}
