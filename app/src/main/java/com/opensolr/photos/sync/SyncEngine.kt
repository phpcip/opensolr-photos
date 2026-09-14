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
import com.opensolr.photos.media.PhotoMetadata
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.net.ClipResult
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.OpensolrException
import com.opensolr.photos.net.PhotoRejectedException
import com.opensolr.photos.net.PlaceInfo
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.PlanLimitException
import com.opensolr.photos.net.QuotaExceededException
import com.opensolr.photos.net.RateLimitedException
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
class SyncEngine(private val context: Context) {

    /**
     * Progress of a run. [total] is 0 while the size of the work is not yet known.
     */
    data class Progress(val phase: String, val done: Int, val total: Int)

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache(context)
    private val indexes = IndexManager(context, prefs, api)

    /**
     * A photo ready to be written: its document, and its vector once embedded.
     */
    private class Prepared(val photo: LocalPhoto, val doc: JSONObject, var vector: FloatArray?)

    /** True once this run hit the monthly AI allowance: no more CLIP calls, photos get their metadata only. */
    private var quotaHit = false

    /** Photos written this run without CLIP words, because the allowance was used up. */
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
            var remote = withFreshPassword(session, { connection = it; solr = SolrClient(it) }) {
                solr.allIds { onProgress(Progress("Comparing with your index", it, 0)) }
            }
            indexCount = remote.size

            // Photos the index knows but the phone's cache does not (a reinstall, a cache lost):
            // copy their documents from the index into the cache, so nothing is ever read by
            // CLIP twice. A document whose file changed since is left out, so the photo is
            // read again below.
            val changedSinceIndexed = HashSet<String>()
            val toPull = remote.filter { it in local && !cache.has(it) }.toHashSet()
            if (toPull.isNotEmpty()) {
                onProgress(Progress("Reading your index", 0, toPull.size))
                var pulled = 0
                solr.allDocs(PULL_FIELDS) { doc ->
                    val id = doc.optString("id")
                    val photo = local[id] ?: return@allDocs
                    if (id !in toPull) return@allDocs
                    val indexedModified = doc.optString("modified_at")
                    val localModified = if (photo.modifiedSec > 0) isoUtc(photo.modifiedSec * 1000L) else ""
                    doc.remove("_version_")
                    doc.remove("score")
                    if (indexedModified == localModified && doc.optLong("size_bytes") == photo.sizeBytes) {
                        cache.put(id, photo.sizeBytes, photo.modifiedSec, doc.toString(), null)
                    } else {
                        // The file changed since: cached under the size and time the index knew,
                        // so it does not pass as current, but the rewrite below starts from it
                        // and keeps every field the index holds (the owner's tags first of all).
                        cache.put(id, doc.optLong("size_bytes"), secondsOf(indexedModified), doc.toString(), null)
                        changedSinceIndexed += id
                    }
                    pulled++
                    onProgress(Progress("Reading your index", pulled, toPull.size))
                }
            }

            if (rebuild) {
                // The cache now holds everything the index knew. New configuration in, every
                // document out, every photo written again below.
                onProgress(Progress("Rebuilding your index", 0, 0))
                indexes.applyConfig(session, connection) { onProgress(Progress("Rebuilding your index", 0, 0)) }
                solr.deleteAll()
                remote = emptySet()
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
                it.id !in remote || it.id in withoutPlace || it.id in forced || it.id in needWords || it.id in changedSinceIndexed || cache.isStale(it.id, it.sizeBytes, it.modifiedSec)
            }
            val phase = if (rebuild) "Rebuilding your index" else "Indexing photos"
            // Photos this run will ask CLIP about: not known to the cache as current, or asked
            // to be read again. When the month's allowance covers fewer than that, say so once:
            // the rest stays without words until the allowance resets.
            if (aiAvailable && limits != null) {
                val toRead = toAdd.count { it.id in forced || it.id in needWords || cache.get(it.id, it.sizeBytes, it.modifiedSec) == null }
                val left = limits.photosLeftThisMonth
                if (left != null && left < toRead) shortAllowance = warnShortAllowance(left, toRead)
            }
            onProgress(Progress(phase, 0, toAdd.size))

            for (chunk in toAdd.chunked(CHUNK)) {
                val prepared = ArrayList<Prepared>(chunk.size)
                for (photo in chunk) {
                    try {
                        prepare(session, connection, photo, vectorAllowed, photo.id in forced || photo.id in needWords)?.let { prepared += it } ?: failed++
                    } catch (e: PhotoRejectedException) {
                        failed++
                    }
                }
                addPlaces(session, prepared.map { it.doc })

                if (vectorAllowed && !quotaHit) {
                    try {
                        embed(session, connection, prepared)
                    } catch (e: VectorNotAllowedException) {
                        // The plan has no vector search: everything is indexed and found by words.
                        vectorAllowed = false
                        prefs.account = prefs.account?.copy(vectorAllowed = false)
                    } catch (e: QuotaExceededException) {
                        // Allowance used up mid-run: the rest of the run goes on without vectors.
                        quotaHit = true
                    }
                }

                // Words without a vector are not written: the photo goes in complete otherwise
                // and is picked up as "without words" at the next run with allowance, where CLIP
                // answers from its cache. So an index never holds a half-read photo.
                prepared.filter { it.doc.has("clip_model") && it.vector == null && !it.doc.has("embeddings") }.forEach {
                    it.doc.remove("labels")
                    it.doc.remove("clip_model")
                    if (cache.getEdits(it.photo.id)?.meaning == null) it.doc.remove("meaning")
                    withoutWords++
                }

                if (prepared.isNotEmpty()) {
                    solr.add(JSONArray().apply { prepared.forEach { put(documentFor(it)) } })
                    // A document without words is not "learned": it stays out of the cache so
                    // the photo is read again once the allowance is back.
                    prepared.filter { it.doc.has("clip_model") }.forEach { cache.put(it.photo.id, it.photo.sizeBytes, it.photo.modifiedSec, it.doc.toString(), it.vector) }
                    added += prepared.size
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
     * Builds the document of one photo, from the cache when the file is unchanged, otherwise by
     * reading its metadata and asking CLIP what it shows. Returns null when the file cannot be read.
     */
    private suspend fun prepare(session: Session, connection: IndexConnection, photo: LocalPhoto, vectorAllowed: Boolean, force: Boolean = false): Prepared? {
        val cached = if (force) null else cache.get(photo.id, photo.sizeBytes, photo.modifiedSec)
        if (cached != null) {
            val doc = JSONObject(cached.docJson).put("media_id", photo.mediaId)
            val changed = applyEdits(photo.id, doc)
            // The owner's words changed: the vector has to be made again from them.
            if (changed) {
                doc.remove("embeddings")
                doc.remove("embed_model")
            }
            return Prepared(photo, doc, if (vectorAllowed && !changed) cached.vector else null)
        }
        // A document written to the index is always the whole document. The rewrite starts
        // from the last one known (the cache; filled from the index at start), so a field only
        // the index holds, the owner's tags first of all, is never dropped on the way.
        val previous = cache.getLatest(photo.id)?.let { JSONObject(it.docJson) }
        val metadata = PhotoReader.readMetadata(context, photo)
        // Over the monthly allowance, or on a plan without photo recognition, the photo is
        // indexed with everything the phone knows and no words; it is read into words at a
        // later sync. Nothing is decoded then. One 429 is enough to stop asking for the rest
        // of the run.
        val clip = if (quotaHit) null else {
            val jpeg = PhotoReader.shrinkForClip(context, photo.uri, metadata.rotationDegrees) ?: return null
            try {
                retrying { api.imageClip(session, connection.indexName, jpeg) }
            } catch (e: QuotaExceededException) {
                quotaHit = true
                null
            }
        }
        val doc = buildDocument(photo, metadata, clip)
        previous?.let { old ->
            // Freshly read again: the old words and vector are replaced, never mixed with new ones.
            val stale = if (clip != null) setOf("labels", "meaning", "clip_model", "embeddings", "embed_model") else emptySet()
            old.keys().forEach { key ->
                if (!doc.has(key) && key !in stale && key !in NEVER_CARRIED) doc.put(key, old.get(key))
            }
        }
        if (!doc.has("clip_model")) withoutWords++
        val changed = applyEdits(photo.id, doc)
        if (changed) {
            doc.remove("embeddings")
            doc.remove("embed_model")
        }
        return Prepared(photo, doc, null)
    }

    /**
     * Puts the owner's edits into a document: their tags, and their own wording of what the
     * photo shows when they changed it. The owner's words always win over CLIP's. Returns
     * true when the document changed, so its vector has to be computed again.
     */
    private fun applyEdits(id: String, doc: JSONObject): Boolean {
        // No edits on this phone: the document stays as it is. It may carry tags written by
        // an earlier install and copied back from the index, and those are kept.
        val edits = cache.getEdits(id) ?: return false
        val before = doc.optString("meaning") + "|" + doc.optJSONArray("custom_tags")?.toString()
        if (edits.tags.isEmpty()) doc.remove("custom_tags") else doc.put("custom_tags", JSONArray(edits.tags))
        edits.meaning?.let { doc.put("meaning", it) }
        return before != doc.optString("meaning") + "|" + doc.optJSONArray("custom_tags")?.toString()
    }

    /**
     * Puts the place of each photo's GPS position into its document (city, region, province,
     * community, country) where it is not there yet. Positions are grouped per ~100 m cell,
     * cells already known are served from the cache, and the unknown ones go to Opensolr in
     * one call. A lookup that fails leaves the document without a place, and the next
     * Re-Sync tries again.
     */
    private suspend fun addPlaces(session: Session, docs: List<JSONObject>) {
        val wanting = docs.filter { !it.has("city") && it.optBoolean("has_location") }
        if (wanting.isEmpty()) return
        val keyOf = HashMap<JSONObject, String>()
        val unknown = LinkedHashSet<String>()
        val places = HashMap<String, PlaceInfo?>()
        wanting.forEach { doc ->
            val parts = doc.optString("location").split(',')
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@forEach
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@forEach
            val key = String.format(Locale.US, "%.4f,%.4f", lat, lon)
            keyOf[doc] = key
            if (key in places) return@forEach
            val known = cache.getPlace(key)
            if (known != null) places[key] = if (known.isEmpty()) null else placeFromJson(JSONObject(known)) else unknown += key
        }
        if (unknown.isNotEmpty()) {
            try {
                unknown.chunked(50).forEach { batch ->
                    val found = retrying { api.nearbyPlaces(session, batch) }
                    found.forEach { (key, place) ->
                        places[key] = place
                        cache.putPlace(key, place?.let { placeToJson(it).toString() } ?: "")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServiceException) {
            }
        }
        wanting.forEach { doc ->
            val place = keyOf[doc]?.let { places[it] } ?: return@forEach
            doc.put("city", place.city)
            place.region?.let { doc.put("region", it) }
            place.province?.let { doc.put("province", it) }
            place.community?.let { doc.put("community", it) }
            place.country?.let { doc.put("country", it) }
            doc.put("country_code", place.countryCode)
        }
    }

    /**
     * A place as kept in the cache.
     */
    private fun placeToJson(place: PlaceInfo): JSONObject = JSONObject()
        .put("city", place.city)
        .putOpt("region", place.region)
        .putOpt("province", place.province)
        .putOpt("community", place.community)
        .putOpt("country", place.country)
        .put("country_code", place.countryCode)

    /**
     * Inverse of [placeToJson].
     */
    private fun placeFromJson(json: JSONObject): PlaceInfo = PlaceInfo(
        city = json.optString("city"),
        region = json.optString("region").ifBlank { null },
        province = json.optString("province").ifBlank { null },
        community = json.optString("community").ifBlank { null },
        country = json.optString("country").ifBlank { null },
        countryCode = json.optString("country_code"),
    )

    /**
     * Fills in the vectors of the prepared photos that do not have one yet, in one call.
     */
    private suspend fun embed(session: Session, connection: IndexConnection, prepared: List<Prepared>) {
        // Photos without words (allowance used up) get their vector together with their words later.
        val missing = prepared.filter { it.vector == null && it.doc.has("clip_model") && !it.doc.has("embeddings") }
        if (missing.isEmpty()) return
        val vectors = retrying { api.batchEmbed(session, connection.indexName, missing.map { meaningOf(it.doc) }) }
        missing.forEachIndexed { i, item ->
            item.vector = vectors[i]
            item.doc.put("embed_model", "opensolr-${vectors[i].size}")
        }
    }

    /**
     * The document as written to Solr: the cached document plus the vector and the indexing time.
     */
    private fun documentFor(item: Prepared): JSONObject {
        val doc = JSONObject(item.doc.toString())
        item.vector?.let { vector -> doc.put("embeddings", JSONArray().apply { vector.forEach { put(it.toDouble()) } }) }
        doc.put("indexed_at", isoUtc(System.currentTimeMillis()))
        return doc
    }

    /**
     * The Solr document of a photo. Field names match solr/conf/schema.xml.
     */
    private fun buildDocument(photo: LocalPhoto, meta: PhotoMetadata, clip: ClipResult?): JSONObject {
        val sideways = meta.rotationDegrees == 90 || meta.rotationDegrees == 270
        val width = if (sideways) photo.height else photo.width
        val height = if (sideways) photo.width else photo.height
        return JSONObject().apply {
            put("id", photo.id)
            put("path", photo.absolutePath)
            put("folder", photo.folder)
            put("file_name", photo.fileName)
            put("media_id", photo.mediaId)
            put("mime", photo.mime)
            put("size_bytes", photo.sizeBytes)
            if (width > 0 && height > 0) {
                put("width", width)
                put("height", height)
                put("orientation", when {
                    width > height -> "landscape"
                    height > width -> "portrait"
                    else -> "square"
                })
            }
            meta.takenAtUtc?.let { put("taken_at", it) }
            meta.year?.let { put("year", it) }
            meta.month?.let { put("month", it) }
            if (photo.modifiedSec > 0) put("modified_at", isoUtc(photo.modifiedSec * 1000L))
            meta.cameraMake?.let { put("camera_make", it) }
            meta.cameraModel?.let { put("camera_model", it) }
            meta.lens?.let { put("lens", it) }
            meta.iso?.let { put("iso", it) }
            meta.exposure?.let { put("exposure", it) }
            meta.fNumber?.let { put("f_number", it) }
            meta.focalLength?.let { put("focal_length", it) }
            meta.flash?.let { put("flash", it) }
            val hasLocation = meta.latitude != null && meta.longitude != null
            put("has_location", hasLocation)
            if (hasLocation) put("location", "${meta.latitude},${meta.longitude}")
            meta.altitude?.let { put("altitude", it) }
            // Every CLIP term as its own value (a label that itself carries commas is split
            // too), so autocomplete and the label facet see single terms. Without CLIP (allowance
            // used up) the document carries no words and no clip_model, which marks it for later.
            if (clip != null) {
                val labels = clip.labels.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                put("meaning", clip.text.ifBlank { labels.joinToString(", ") })
                put("labels", JSONArray(labels))
                put("clip_model", clip.model)
            }
        }
    }

    /**
     * The text embedded for a photo: what it shows, then the owner's tags.
     */
    private fun meaningOf(doc: JSONObject): String = embeddingText(doc)

    /**
     * Runs [block], waiting out rate limits (the server says how long) a few times before giving up.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RateLimitedException) {
                if (++attempt > 6) throw e
                delay(e.retryAfterSeconds.coerceIn(5, 120) * 1000L)
            }
        }
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
     * Seconds since the epoch of an ISO instant as the index stores it, 0 when unreadable.
     */
    private fun secondsOf(iso: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time?.div(1000L) ?: 0L
    } catch (e: Exception) {
        0L
    }

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
        /** Photos per round: CLIP one by one, then one embedding call and one index write. */
        private const val CHUNK = 20

        /** Fields of an old document that never travel into a new one: Solr's own, and the write time. */
        private val NEVER_CARRIED = setOf("_version_", "score", "indexed_at")

        /** Stored fields copied from the index into the cache (everything but the vector). */
        const val PULL_FIELDS = "*"

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
