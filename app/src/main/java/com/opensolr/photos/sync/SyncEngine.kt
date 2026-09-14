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
import com.opensolr.photos.net.PlanLimitException
import com.opensolr.photos.net.QuotaExceededException
import com.opensolr.photos.net.RateLimitedException
import com.opensolr.photos.net.SignInRequiredException
import com.opensolr.photos.net.SolrAuthException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.net.VectorNotAllowedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
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

        try {
            onProgress(Progress("Checking your index", 0, 0))
            val (initialConnection, outcome) = indexes.ensure(session) { onProgress(Progress(it, 0, 0)) }
            recreated = outcome == IndexManager.Outcome.RECREATED
            if (recreated) Notifier.indexRecreated(context)
            var connection = initialConnection
            var solr = SolrClient(connection)

            onProgress(Progress("Looking for photos", 0, 0))
            val local = MediaScanner.scan(context, prefs.folders)
            localCount = local.size

            onProgress(Progress("Comparing with your index", 0, 0))
            val remote = withFreshPassword(session, { connection = it; solr = SolrClient(it) }) {
                solr.allIds { onProgress(Progress("Comparing with your index", it, 0)) }
            }
            indexCount = remote.size

            val toDelete = remote - local.keys
            if (toDelete.isNotEmpty()) {
                onProgress(Progress("Removing deleted photos", 0, toDelete.size))
                toDelete.chunked(500).forEach { batch ->
                    solr.delete(batch)
                    deleted += batch.size
                    onProgress(Progress("Removing deleted photos", deleted, toDelete.size))
                }
            }

            val toAdd = local.values.filter { it.id !in remote }
            var vectorAllowed = prefs.account?.vectorAllowed ?: false
            onProgress(Progress("Indexing photos", 0, toAdd.size))

            for (chunk in toAdd.chunked(CHUNK)) {
                val prepared = ArrayList<Prepared>(chunk.size)
                for (photo in chunk) {
                    try {
                        prepare(session, connection, photo, vectorAllowed)?.let { prepared += it } ?: failed++
                    } catch (e: PhotoRejectedException) {
                        failed++
                    }
                }

                if (vectorAllowed) {
                    try {
                        embed(session, connection, prepared)
                    } catch (e: VectorNotAllowedException) {
                        vectorAllowed = false
                        prefs.account = prefs.account?.copy(vectorAllowed = false)
                    }
                }

                if (prepared.isNotEmpty()) {
                    solr.add(JSONArray().apply { prepared.forEach { put(documentFor(it)) } })
                    prepared.forEach { cache.put(it.photo.id, it.photo.sizeBytes, it.photo.modifiedSec, it.doc.toString(), it.vector) }
                    added += prepared.size
                }
                onProgress(Progress("Indexing photos", added + failed, toAdd.size))
            }

            solr.commit()
            cache.removeAllExcept(local.keys)
            refreshAccount(session, connection)
            return report("ok", added, deleted, failed, localCount, indexCount, "", recreated)
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
    private suspend fun prepare(session: Session, connection: IndexConnection, photo: LocalPhoto, vectorAllowed: Boolean): Prepared? {
        val cached = cache.get(photo.id, photo.sizeBytes, photo.modifiedSec)
        if (cached != null) {
            val doc = JSONObject(cached.docJson).put("media_id", photo.mediaId)
            return Prepared(photo, doc, if (vectorAllowed) cached.vector else null)
        }
        val metadata = PhotoReader.readMetadata(context, photo)
        val jpeg = PhotoReader.shrinkForClip(context, photo.uri, metadata.rotationDegrees) ?: return null
        val clip = retrying { api.imageClip(session, connection.indexName, jpeg) }
        return Prepared(photo, buildDocument(photo, metadata, clip), null)
    }

    /**
     * Fills in the vectors of the prepared photos that do not have one yet, in one call.
     */
    private suspend fun embed(session: Session, connection: IndexConnection, prepared: List<Prepared>) {
        val missing = prepared.filter { it.vector == null }
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
    private fun buildDocument(photo: LocalPhoto, meta: PhotoMetadata, clip: ClipResult): JSONObject {
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
            put("meaning", clip.text.ifBlank { clip.labels.joinToString(", ") })
            put("labels", JSONArray(clip.labels))
            put("clip_model", clip.model)
        }
    }

    /**
     * The text embedded for a photo: its CLIP words.
     */
    private fun meaningOf(doc: JSONObject): String = doc.optString("meaning").ifBlank { "photo" }

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
            if (limits.diskFull || limits.bandwidthFull) {
                val what = if (limits.diskFull) "disk space" else "search bandwidth"
                Notifier.planLimit(
                    context, "Your photo index is out of $what",
                    "Your Opensolr plan's $what is used up. Upgrade to keep syncing and searching your photos."
                )
            }
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
    ) = SyncReport(System.currentTimeMillis(), status, added, deleted, failed, localCount, indexCount, message, recreated)

    /**
     * Solr date format in UTC.
     */
    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    companion object {
        /** Photos per round: CLIP one by one, then one embedding call and one index write. */
        private const val CHUNK = 20
    }
}
