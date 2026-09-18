package com.opensolr.photos.search

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.IndexConnection
import com.opensolr.photos.data.SearchCache
import com.opensolr.photos.index.IndexManager
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.QuotaExceededException
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.SolrAuthException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.net.VectorNotAllowedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Filters the user can combine with a search.
 */
data class SearchFilters(
    /** Chosen values per Solr field (year, folder, camera_model, ...); several per field are OR-ed. */
    val fields: Map<String, Set<String>> = emptyMap(),
    val withLocation: Boolean = false,
    /**
     * Photos by whether the owner has tagged them: null is every photo, true only the tagged
     * ones, false only those with no tags of their own (Cip, 2026-09-16). Three states, so a
     * switch would not do.
     */
    val tagged: Boolean? = null,
    val near: NearFilter? = null,
    /** "Taken between these two days", when the year list is not fine enough (Cip, 2026-09-16). */
    val taken: DateRange? = null,
    /** Photos by whether anything was read printed in them; three states, like [tagged]. */
    val hasOcr: Boolean? = null,
    /** Photos by whether anyone is named on them; three states, like [tagged]. */
    val hasPeople: Boolean? = null,
    /**
     * Photos that look like paperwork. Not "has printed text": a document read badly is still a
     * document. The test is the one the server uses to decide whether a photo is worth reading
     * at all, run against the same words - see DOCUMENT_WORDS.
     */
    val documents: Boolean? = null,
) {
    /** Number of active filters, for the filter button badge. */
    val count: Int get() = fields.values.sumOf { it.size } + (if (withLocation) 1 else 0) +
        (if (tagged != null) 1 else 0) + (if (near != null) 1 else 0) + (if (taken != null) 1 else 0) +
        (if (hasOcr != null) 1 else 0) + (if (hasPeople != null) 1 else 0) + (if (documents != null) 1 else 0)

    /** The chosen values of [field]. */
    fun values(field: String): Set<String> = fields[field] ?: emptySet()

    /** The filters with [value] of [field] switched on or off. */
    fun toggled(field: String, value: String): SearchFilters {
        val current = values(field)
        val next = if (value in current) current - value else current + value
        val map = fields.toMutableMap()
        if (next.isEmpty()) map.remove(field) else map[field] = next
        return copy(fields = map)
    }

    companion object {
        /** The facet fields, in the order the filter sheet shows them, with their titles. */
        val FACETS = listOf(
            // No "region": between City and Country it said the same thing a third time
            // (Cip, 2026-09-15). The field is still indexed and still searched by words.
            // No "folder" either (Cip, 2026-09-16): MediaStore names a folder by its whole path,
            // so a library kept in nested folders filled the list with "Documents/photos/..."
            // lines that all looked alike. The field is still indexed and still searched by
            // words, and a photo's details still say which folder it is in.
            // No "camera_make" either (Cip, 2026-09-18): the model already reads as "Nikon Z6",
            // so the make was the same list said twice.
            "year" to "Year", "city" to "City", "country" to "Country",
            "camera_model" to "Camera model", "custom_tags" to "My tags (Albums)",
            "labels" to "Meaning", "orientation" to "Orientation",
            // The names of the people, each whole (persons_ss), instead of only "has people"
            // (Cip, 2026-09-17).
            "persons_ss" to "People",
        )
        /** Facet fields an index on the previous configuration does not have. */
        val NEWER_FACETS = setOf("city", "region", "country", "labels", "custom_tags", "persons_ss")

        /**
         * What makes a photo paperwork, copied from TEXT_FAMILY_WORDS in stream_server.py -
         * the same list the server runs over the same words before sending a photo to be read.
         * Matched against `meaning`, which is those words joined, so the rule is the server's
         * rule expressed as a query rather than a regex; no field and no reindex for it.
         */
        val DOCUMENT_WORDS = listOf(
            "text", "label", "labels", "document", "documents", "receipt", "invoice",
            "certificate", "card", "ticket", "menu", "poster", "brochure", "flyer", "leaflet",
            "banner", "billboard", "guide", "print", "number", "tag", "\"nutrition facts\"",
            "website", "screenshot",
        )
    }
}

/**
 * "Taken between these two days", both ends included. Kept as the millis a date picker hands
 * back (UTC midnight of the chosen day) so nothing is lost between the picker and the query;
 * the query is built on `taken_at`, which every photo already has, so this needs no new field
 * and no reindex.
 */
data class DateRange(val fromUtcMillis: Long, val toUtcMillis: Long) {

    private fun day(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    /** The first instant of the first day, in the form Solr reads. */
    val fromSolr: String get() = day(fromUtcMillis) + "T00:00:00Z"

    /** The last instant of the last day, so the day chosen as the end is itself included. */
    val toSolr: String get() = day(toUtcMillis) + "T23:59:59Z"

    /** Short wording for a chip: "16 Sep 2025 - 4 Jan 2026". */
    val label: String get() {
        val short = SimpleDateFormat("d MMM yyyy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return short.format(Date(fromUtcMillis)) + " – " + short.format(Date(toUtcMillis))
    }
}

/**
 * "Within [radiusKm] of a point": a radius search on the photos' GPS position.
 */
data class NearFilter(val lat: Double, val lon: Double, val radiusKm: Double) {

    /** Short wording for a chip. */
    val label: String get() = "Within " + (if (radiusKm < 1) String.format(Locale.US, "%.1f km", radiusKm) else String.format(Locale.US, "%.0f km", radiusKm))

    companion object {
        /** The radius choices offered on the filter sheet, in km. */
        val RADII = listOf(0.5, 1.0, 5.0, 25.0, 100.0)
    }
}

/**
 * One page of duplicate groups: the photos laid out group after group, the size of each group,
 * how many groups there are in all, and whether this page was the last.
 */
data class DuplicatePage(
    val hits: List<PhotoHit>,
    val sizes: List<Int>,
    val totalGroups: Int,
    val endReached: Boolean,
)

/**
 * A photo with a GPS position, for the map.
 */
data class PhotoPin(val hit: PhotoHit, val lat: Double, val lon: Double)

/**
 * One photo in the results.
 */
/**
 * One album: the photos holding [value] in [field], shown as [title]. [covers] are the media
 * ids of its newest photos, newest first, for the stacked cover.
 */
data class Album(val field: String, val value: String, val title: String, val count: Int, val covers: List<Long>)

/**
 * What the tag field offers: the owner's own tags ([mine]) and, under them, words CLIP read
 * the photos into ([fromMeanings]).
 */
data class TagSuggestions(val mine: List<String>, val fromMeanings: List<String>) {
    val isEmpty: Boolean get() = mine.isEmpty() && fromMeanings.isEmpty()
}

/**
 * A kind of album (tags, things, places, cameras, years) and its albums, biggest first.
 */
data class AlbumSection(val title: String, val albums: List<Album>)

data class PhotoHit(
    val id: String,
    val mediaId: Long,
    val path: String,
    val fileName: String,
    val folder: String,
    val mime: String,
    val takenAt: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lens: String?,
    val iso: Int?,
    val exposure: String?,
    val fNumber: Double?,
    val focalLength: Double?,
    val width: Int?,
    val height: Int?,
    /** The file's size on the phone, for the plate of facts in a photo's details. */
    val sizeBytes: Long = 0,
    val meaning: String,
    /** The words printed IN the photo, read on Opensolr's side; empty when it carries none. */
    val ocrText: String = "",
    /** The people named on the photo, as they were written on the file. */
    val persons: String = "",
    val location: String?,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val labels: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),
    /** The hybrid score, for cutting the strong matches off the vector's near misses; 0 when unsorted by relevance. */
    val score: Double = 0.0,
) {
    /** The GPS position parsed from Solr's "lat,lon", or null without one. */
    val latLon: Pair<Double, Double>? get() = parseLatLon(location)
}

/**
 * Parses Solr's "lat,lon" into a pair, or null when the value is missing or malformed.
 */
fun parseLatLon(value: String?): Pair<Double, Double>? {
    val parts = value?.split(',') ?: return null
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
    return lat to lon
}

/**
 * A facet value and how many photos have it.
 */
data class FacetValue(val value: String, val count: Int)

/**
 * One page of results.
 *
 * @property smart  true when the search used meaning (vectors) as well as words
 * @property notice something the user should know about this search, e.g. the AI allowance ran out
 */
data class SearchPage(
    val hits: List<PhotoHit>,
    val numFound: Long,
    val facets: Map<String, List<FacetValue>>,
    val smart: Boolean,
    val notice: String?,
    val didYouMean: String? = null,
)

/**
 * Searches the phone's index directly.
 *
 * An empty query lists every photo, newest first. A typed query is matched on the photo's words
 * (CLIP labels, file name, folder, camera) and, when the plan includes vector search, also on
 * meaning: the query is turned into a vector and the nearest photos join the results. What the
 * user typed only ever travels as the bound parameter uq, never spliced into query syntax, and
 * every filter value goes through {!term} with a bound value, so no input can change the query.
 */
class SearchRepository(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()

    /**
     * Answers this phone already paid for. Only the reads below go through it; writing,
     * deleting and the sync's walk over the index never do.
     */
    private val cache = SearchCache(context)

    /**
     * Forgets every cached answer. Called by the button in the account screen and by anything
     * in the app that changes the index, so what the owner just did is never missing.
     */
    fun clearCache() = cache.clear()

    /**
     * How many answers are cached right now, for the account screen.
     */
    fun cachedCount(): Int = cache.count()

    /**
     * Runs a search. [start] is the offset of the first result.
     */
    suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int = PAGE, freshBias: Boolean = false, wordsOnly: Boolean = false): SearchPage =
        try {
            search(query, filters, start, rows, legacy = false, freshBias = freshBias, wordsOnly = wordsOnly)
        } catch (e: ServiceException) {
            // An index still on an older configuration (rebuild postponed) lacks the newest
            // fields and answers 400 to them; search it with what it has.
            if (e.message?.contains("HTTP 400") == true) search(query, filters, start, rows, legacy = true, freshBias = freshBias, wordsOnly = wordsOnly) else throw e
        }

    /**
     * The facet values of the words alone: the same query run without the vector leg, for its
     * counts only (rows = 0). What comes back describes the photos whose tags, words, place,
     * file name or camera really do match what was typed - never what the vector dragged along -
     * so the suggestions above the grid can be trusted. Empty when the words match nothing.
     */
    suspend fun queryFacets(query: String, filters: SearchFilters): Map<String, List<FacetValue>> =
        try {
            facetsOnly(query, filters, legacy = false)
        } catch (e: ServiceException) {
            if (e.message?.contains("HTTP 400") == true) {
                try {
                    facetsOnly(query, filters, legacy = true)
                } catch (e2: Exception) {
                    emptyMap()
                }
            } else emptyMap()
        } catch (e: Exception) {
            // Suggestions are a nicety; a search must never fail because of them.
            emptyMap()
        }

    /**
     * The facet lists of the whole library - the years, places, cameras, tags and names the filter
     * sheet offers - in one request that asks for no documents at all.
     *
     * Asked once and then kept on the phone until a sync actually writes something, because that
     * is the only thing that can change them (Cip, 2026-09-18).
     */
    suspend fun browseFacets(): Map<String, List<FacetValue>> {
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "rows" to "0"
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.limit" to "200"
        FACET_FIELDS.forEach { params += "facet.field" to it }
        params += "f.year.facet.sort" to "index"
        return parse(select(params), smart = false, notice = null).facets
    }

    /**
     * One words-only request that asks for no documents, just the facets.
     */
    private suspend fun facetsOnly(query: String, filters: SearchFilters, legacy: Boolean): Map<String, List<FacetValue>> {
        if (query.isBlank()) return emptyMap()
        val (params, _, _) = queryParams(query, filters, legacy, wordsOnly = true)
        params += "fl" to "id"
        params += "start" to "0"
        params += "rows" to "0"
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.limit" to "20"
        FACET_FIELDS.filter { !legacy || it !in SearchFilters.NEWER_FACETS }
            .forEach { params += "facet.field" to "{!ex=$it key=$it}$it" }
        val json = select(params)
        if (json.getJSONObject("response").optLong("numFound") == 0L) return emptyMap()
        return parse(json, false, null).facets
    }

    /**
     * One photo, read out of the document the index (or the phone's own copy of it) holds. The
     * same reading for both, so a photo drawn from the phone is the same photo in every way
     * (Cip, 2026-09-18).
     */
    fun hitOf(d: JSONObject): PhotoHit = PhotoHit(
        id = d.optString("id"),
        mediaId = d.optLong("media_id", -1),
        path = d.optString("path"),
        fileName = d.optString("file_name"),
        folder = d.optString("folder"),
        mime = d.optString("mime", "image/*").ifBlank { "image/*" },
        takenAt = d.optString("taken_at").ifBlank { null },
        cameraMake = d.optString("camera_make").ifBlank { null },
        cameraModel = d.optString("camera_model").ifBlank { null },
        lens = d.optString("lens").ifBlank { null },
        iso = if (d.has("iso")) d.optInt("iso") else null,
        exposure = d.optString("exposure").ifBlank { null },
        fNumber = if (d.has("f_number")) d.optDouble("f_number") else null,
        focalLength = if (d.has("focal_length")) d.optDouble("focal_length") else null,
        width = if (d.has("width")) d.optInt("width") else null,
        height = if (d.has("height")) d.optInt("height") else null,
        sizeBytes = d.optLong("size_bytes"),
        meaning = d.optString("meaning"),
        ocrText = d.optString("ocr_t"),
        persons = d.optString("persons_t"),
        location = d.optString("location").ifBlank { null },
        city = d.optString("city").ifBlank { null },
        region = d.optString("region").ifBlank { null },
        country = d.optString("country").ifBlank { null },
        labels = d.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        customTags = d.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        score = d.optDouble("score", 0.0),
    )

    /**
     * One search request; [legacy] leaves out the fields an older configuration lacks.
     */
    private suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int, legacy: Boolean, freshBias: Boolean, wordsOnly: Boolean): SearchPage {
        val (params, smart, notice) = queryParams(query, filters, legacy, wordsOnly = wordsOnly, freshBias = freshBias)
        val text = query.trim().take(300)
        if (text.isNotEmpty()) {
            // Spellcheck the typed words; the collation is offered as "did you mean".
            params += "spellcheck" to "true"
            params += "spellcheck.q" to text
        }
        params += "fl" to (if (legacy) LEGACY_FIELDS else FIELDS)
        params += "start" to start.coerceAtLeast(0).toString()
        // Up to a few hundred: a reload of a view the owner has scrolled through asks for
        // everything that was on it in one request (Cip, 2026-09-18).
        params += "rows" to rows.coerceIn(1, 1000).toString()
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.limit" to "80"
        FACET_FIELDS.filter { !legacy || it !in SearchFilters.NEWER_FACETS }
            .forEach { params += "facet.field" to "{!ex=$it key=$it}$it" }
        params += "f.year.facet.sort" to "index"
        val page = parse(select(params), smart, notice)
        return page.copy(didYouMean = collation(page, text))
    }

    /**
     * Every photo of the current view taken between [from] and [to] (both in milliseconds), which
     * is exactly one heading on the grid: a month, or a day inside it.
     *
     * The same query and the same filters as the results on screen, narrowed by the dates, asked
     * for ids only - the grid is not touched and its pages are not disturbed. Enough of each photo
     * comes back to reach the file it reads from, so tagging, sharing and deleting work on photos
     * the owner never scrolled to.
     */
    suspend fun photosBetween(
        query: String,
        filters: SearchFilters,
        from: Long,
        to: Long,
        freshBias: Boolean = false,
        wordsOnly: Boolean = false,
        /** Whole documents, for photos that go on the grid; ids only, for ticking a group. */
        full: Boolean = false,
    ): List<PhotoHit> {
        val (params, _, _) = queryParams(query, filters, legacy = false, wordsOnly = wordsOnly, freshBias = freshBias)
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // A range is bound whole, as one parameter: {!field} and {!term} do not read ranges.
        params += "fq" to "{!lucene v=\$group_q}"
        params += "group_q" to "taken_at:[${stamp.format(Date(from))} TO ${stamp.format(Date(to))}]"
        params += "fl" to (if (full) FIELDS else "id,media_id,path,file_name,folder,mime,custom_tags,persons_t")
        params += "start" to "0"
        params += "rows" to GROUP_TICK_MAX.toString()
        params += "sort" to "taken_at desc, id asc"
        val json = select(params)
        if (full) return parse(json, smart = false, notice = null).hits
        val docs = json.getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            PhotoHit(
                id = d.optString("id"),
                mediaId = d.optLong("media_id", -1),
                path = d.optString("path"),
                fileName = d.optString("file_name"),
                folder = d.optString("folder"),
                mime = d.optString("mime", "image/*").ifBlank { "image/*" },
                takenAt = null, cameraMake = null, cameraModel = null, lens = null, iso = null,
                exposure = null, fNumber = null, focalLength = null, width = null, height = null,
                meaning = "", location = null,
                persons = d.optString("persons_t"),
                customTags = d.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
    }

    /**
     * What a set of photos already carries: the names of the people in them and the owner's own
     * tags, each with how many of those photos have it, most used first.
     *
     * Counted by the index over exactly those ids, so the tagging sheet tells the truth about the
     * whole selection and not only about the photos that happen to be on screen.
     */
    suspend fun wordsOf(ids: List<String>): Pair<List<FacetValue>, List<FacetValue>> {
        if (ids.isEmpty()) return emptyList<FacetValue>() to emptyList()
        val people = HashMap<String, Int>()
        val tags = HashMap<String, Int>()
        ids.chunked(WORDS_OF_CHUNK).forEach { batch ->
            val params = ArrayList<Pair<String, String>>()
            params += "q" to "*:*"
            params += "fq" to "{!terms f=id separator=| v=\$wordIds}"
            params += "wordIds" to batch.joinToString("|")
            params += "rows" to "0"
            params += "facet" to "true"
            params += "facet.mincount" to "1"
            params += "facet.sort" to "count"
            params += "facet.limit" to "200"
            params += "facet.field" to "persons_ss"
            params += "facet.field" to "custom_tags"
            val fields = select(params).optJSONObject("facet_counts")?.optJSONObject("facet_fields")
            fun into(field: String, out: HashMap<String, Int>) {
                val pairs = fields?.optJSONArray(field) ?: return
                for (i in 0 until pairs.length() / 2) {
                    val value = pairs.optString(i * 2)
                    if (value.isNotBlank()) out[value] = (out[value] ?: 0) + pairs.optInt(i * 2 + 1)
                }
            }
            into("persons_ss", people)
            into("custom_tags", tags)
        }
        fun sorted(counts: Map<String, Int>) = counts.entries.sortedByDescending { it.value }.map { FacetValue(it.key, it.value) }
        return sorted(people) to sorted(tags)
    }

    /**
     * Duplicates of one kind, [level] 0..10 on the slider (see DUPLICATE_FIELDS): photos that
     * share the chosen duplicate key, made on the server from CLIP's words and the EXIF. The
     * groups are known from ONE facet request, never a walk over the index; only the photos
     * of those groups are then fetched, for the grid and the photo sheet.
     *
     * Returns the photos laid out group after group, with the size of each group.
     */
    suspend fun duplicates(level: Int, groupsFrom: Int = 0, groupsLimit: Int = GROUPS_PAGE): DuplicatePage {
        val session = prefs.session ?: throw ServiceException("Sign in to search")
        val connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        val field = DUPLICATE_FIELDS[level.coerceIn(0, DUPLICATE_FIELDS.size - 1)]
        val groups = try {
            try {
                cachedDuplicateGroups(connection, field)
            } catch (e: SolrAuthException) {
                cachedDuplicateGroups(IndexManager(context, prefs, api).refreshConnection(session), field)
            }
        } catch (e: ServiceException) {
            // An index whose rebuild was postponed has no duplicate keys yet.
            if (e.message?.contains("HTTP 400") == true) throw ServiceException("Finding duplicates needs your index reset for this version of the app. Open the app's photos screen to start it.")
            throw e
        }
        if (groups.isEmpty()) return DuplicatePage(emptyList(), emptyList(), 0, true)

        // Only the groups this page shows. The whole set of ids arrives in the one facet request
        // above and is cheap to hold; the documents are not. Asking for all of them meant 8000
        // photos and forty requests to fill a screen that shows three groups (Cip, 2026-09-16).
        val page = groups.drop(groupsFrom).take(groupsLimit.coerceAtLeast(1))
        if (page.isEmpty()) return DuplicatePage(emptyList(), emptyList(), groups.size, true)

        // The documents themselves, in batches, then laid out in the order of their group.
        val wanted = page.flatten()
        val found = HashMap<String, PhotoHit>(wanted.size)
        wanted.chunked(200).forEach { batch ->
            val params = ArrayList<Pair<String, String>>()
            params += "q" to "*:*"
            params += "fq" to "{!terms f=id separator=| v=\$dupIds}"
            params += "dupIds" to batch.joinToString("|")
            params += "fl" to FIELDS
            params += "rows" to batch.size.toString()
            parse(select(params), false, null).hits.forEach { found[it.id] = it }
        }
        val hits = ArrayList<PhotoHit>(wanted.size)
        val sizes = ArrayList<Int>(page.size)
        page.forEach { group ->
            val present = group.mapNotNull { found[it] }
            if (present.size >= 2) {
                hits += present
                sizes += present.size
            }
        }
        return DuplicatePage(hits, sizes, groups.size, groupsFrom + page.size >= groups.size)
    }

    /**
     * The albums, made from what the index already knows, in ONE request: a JSON facet per
     * kind (the owner's tags, CLIP's twelve most used words, cities, countries, camera models,
     * years), each value held by at least [ALBUM_MIN] photos, with the three newest photos of
     * each album (by taken_at) for its cover. Nothing is read by AI, nothing but counts and
     * ids come back. The current search and filters do not apply: albums are the whole library.
     */
    suspend fun albums(): List<AlbumSection> {
        // The cover: the album's photos by media id, newest taken first, three of them.
        val cover = JSONObject()
            .put("type", "terms").put("field", "media_id").put("limit", 3).put("sort", "t desc")
            .put("facet", JSONObject().put("t", "max(taken_at)"))
        fun kind(field: String, limit: Int, sort: String = "count desc", extra: JSONObject.() -> Unit = {}) = JSONObject()
            .put("type", "terms").put("field", field).put("mincount", ALBUM_MIN).put("limit", limit).put("sort", sort)
            .put("facet", JSONObject().put("cover", cover).apply(extra))
        val facet = JSONObject()
            .put("people", kind("persons_ss", 100))
            .put("tags", kind("custom_tags", 100))
            .put("things", kind("labels", ALBUM_THINGS))
            .put("cities", kind("city", 50))
            .put("countries", kind("country", 50))
            // The make of each model, for a name like "Nikon Z6" rather than a bare "Z6".
            .put("cameras", kind("camera_model", 30) { put("make", JSONObject().put("type", "terms").put("field", "camera_make").put("limit", 1)) })
            .put("years", kind("year", -1, sort = "index desc"))
        val facets = select(listOf("q" to "*:*", "rows" to "0", "json.facet" to facet.toString())).optJSONObject("facets") ?: JSONObject()

        fun albumsOf(key: String, field: String, title: (JSONObject, String) -> String = { _, v -> v }): List<Album> {
            val buckets = facets.optJSONObject(key)?.optJSONArray("buckets") ?: return emptyList()
            return (0 until buckets.length()).mapNotNull { i ->
                val b = buckets.optJSONObject(i) ?: return@mapNotNull null
                val value = b.opt("val")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val covers = b.optJSONObject("cover")?.optJSONArray("buckets")?.let { c ->
                    (0 until c.length()).mapNotNull { k -> c.optJSONObject(k)?.optLong("val", -1L)?.takeIf { it >= 0 } }
                } ?: emptyList()
                Album(field, value, title(b, value), b.optInt("count"), covers)
            }
        }
        val cameraTitle = { b: JSONObject, model: String ->
            val make = b.optJSONObject("make")?.optJSONArray("buckets")?.optJSONObject(0)?.optString("val").orEmpty().trim()
            if (make.isEmpty() || model.lowercase().startsWith(make.lowercase())) model else "$make $model"
        }
        // People, the owner's own tags, then Things, then Years, Places and the rest
        // (Cip, 2026-09-18).
        return listOf(
            AlbumSection("People", albumsOf("people", "persons_ss")),
            AlbumSection("My tags (Albums)", albumsOf("tags", "custom_tags")),
            AlbumSection("Things", albumsOf("things", "labels")),
            AlbumSection("Years", albumsOf("years", "year")),
            AlbumSection("Places", albumsOf("cities", "city") + albumsOf("countries", "country")),
            AlbumSection("Cameras", albumsOf("cameras", "camera_model", cameraTitle)),
        ).filter { it.albums.isNotEmpty() }
    }

    /**
     * Every photo of [albums] (one album, or all the albums of a section), straight from the
     * index and never from the cache, for deleting or sharing them all. The values travel as
     * bound parameters, one terms query per field, joined with OR.
     */
    suspend fun albumPhotos(albums: List<Album>): List<PhotoHit> {
        if (albums.isEmpty()) return emptyList()
        val connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        val params = ArrayList<Pair<String, String>>()
        val clauses = albums.groupBy { it.field }.entries.mapIndexed { i, (field, list) ->
            params += "album_q$i" to "{!terms f=$field separator=\u0001 v=\$album_v$i}"
            params += "album_v$i" to list.joinToString("\u0001") { it.value }
            "should=\$album_q$i"
        }
        params += "fq" to "{!bool ${clauses.joinToString(" ")}}"
        val out = ArrayList<PhotoHit>()
        com.opensolr.photos.net.SolrClient(connection).forEachDoc("id,media_id,path,file_name,folder,mime", filters = params) { d ->
            out += PhotoHit(
                id = d.optString("id"), mediaId = d.optLong("media_id", -1L), path = d.optString("path"),
                fileName = d.optString("file_name"), folder = d.optString("folder"), mime = d.optString("mime"),
                takenAt = null, cameraMake = null, cameraModel = null, lens = null, iso = null, exposure = null,
                fNumber = null, focalLength = null, width = null, height = null, meaning = "", location = null,
            )
        }
        return out
    }

    /**
     * Names of people already in the index, for the People field of a photo: the most used
     * with nothing typed, the ones containing [typed] anywhere, any case, while typing. Read from
     * persons_ss, where every name is kept whole; persons_t is analysed text and gives words.
     */
    suspend fun personSuggestions(typed: String, exclude: Collection<String>): List<String> {
        val text = typed.trim().take(50)
        val limit = if (text.isEmpty()) 5 else 8
        val skip = exclude.map { com.opensolr.photos.data.Words.fold(it) }.toSet()
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "rows" to "0"
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.sort" to "count"
        params += "facet.field" to "persons_ss"
        params += "facet.limit" to (limit + skip.size).toString()
        if (text.isNotEmpty()) {
            params += "facet.contains" to text
            params += "facet.contains.ignoreCase" to "true"
        }
        val pairs = select(params).optJSONObject("facet_counts")?.optJSONObject("facet_fields")?.optJSONArray("persons_ss") ?: return emptyList()
        return (0 until pairs.length() / 2).map { pairs.optString(it * 2) }
            .filter { it.isNotBlank() && com.opensolr.photos.data.Words.fold(it) !in skip }
            .take(limit)
    }

    /**
     * Suggestions while tagging a photo, in ONE request: the owner's own tags first, then words
     * CLIP read the photos into, each ordered by how many photos carry it. With nothing typed,
     * the five most used of each; while typing, the ones containing [typed] anywhere, any case
     * (eight tags, five words). Tags already on the photo ([exclude]) are never offered, and a
     * word already offered as a tag is not repeated below.
     */
    suspend fun tagSuggestions(typed: String, exclude: Collection<String>): TagSuggestions {
        val text = typed.trim().take(50)
        val tagLimit = if (text.isEmpty()) 5 else 8
        val wordLimit = 5
        val skip = exclude.map { com.opensolr.photos.data.Words.fold(it) }.toSet()
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "rows" to "0"
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.sort" to "count"
        params += "facet.field" to "{!key=mine}custom_tags"
        params += "facet.field" to "{!key=words}labels"
        // Room for the values dropped below (already on the photo, already offered as a tag).
        params += "f.custom_tags.facet.limit" to (tagLimit + skip.size).toString()
        params += "f.labels.facet.limit" to (wordLimit + tagLimit + skip.size).toString()
        if (text.isNotEmpty()) {
            // The typed text is a parameter value, never spliced into a query.
            params += "facet.contains" to text
            params += "facet.contains.ignoreCase" to "true"
        }
        val fields = select(params).optJSONObject("facet_counts")?.optJSONObject("facet_fields")
        fun valuesOf(key: String): List<String> {
            val pairs = fields?.optJSONArray(key) ?: return emptyList()
            return (0 until pairs.length() / 2).map { pairs.optString(it * 2) }.filter { it.isNotBlank() }
        }
        val mine = valuesOf("mine").filter { com.opensolr.photos.data.Words.fold(it) !in skip }.take(tagLimit)
        val offered = skip + mine.map { com.opensolr.photos.data.Words.fold(it) }
        val words = valuesOf("words").filter { com.opensolr.photos.data.Words.fold(it) !in offered }.distinctBy { com.opensolr.photos.data.Words.fold(it) }.take(wordLimit)
        return TagSuggestions(mine, words)
    }

    /**
     * Autocomplete for what the user typed so far: labels of the indexed photos.
     */
    suspend fun suggest(prefix: String): List<String> {
        val text = prefix.trim().take(100)
        if (text.length < 2) return emptyList()
        val connection = prefs.connection ?: return emptyList()
        // Autocomplete asks for the same prefixes over and over; a held answer saves the request.
        val key = SearchCache.key(connection.indexName, "/suggest", listOf("suggest.q" to text.lowercase(Locale.ROOT)))
        cache.get(key, prefs.cacheSeconds)?.let { stored ->
            runCatching {
                val array = JSONArray(stored)
                (0 until array.length()).map { array.getString(it) }
            }.getOrNull()?.let { return it }
        }
        return try {
            SolrClient(connection).suggest(text).also { cache.put(key, JSONArray(it).toString()) }
        } catch (e: ServiceException) {
            emptyList()
        }
    }

    /**
     * The spellchecker's collation when it differs from what was typed, else null.
     */
    private fun collation(page: SearchPage, typed: String): String? {
        val suggestion = page.didYouMean ?: return null
        return suggestion.takeIf { it.isNotBlank() && !it.equals(typed, ignoreCase = true) }
    }

    /**
     * The photos with a GPS position that match [query] and [filters], for the map. At most
     * [MAP_ROWS] are returned, best matches first (newest first for an empty query).
     */
    suspend fun pins(query: String, filters: SearchFilters): List<PhotoPin> {
        val (params, _, _) = queryParams(query, filters)
        params += "fq" to "has_location:true"
        params += "fl" to FIELDS
        params += "start" to "0"
        params += "rows" to MAP_ROWS.toString()
        val page = parse(select(params), false, null)
        return page.hits.mapNotNull { hit -> hit.latLon?.let { (lat, lon) -> PhotoPin(hit, lat, lon) } }
    }

    /**
     * The photos that share one photo's duplicate key at [level]: the same slider as duplicates,
     * but anchored to this photo instead of the whole index. Two requests, both cacheable: the
     * photo's own key for that stop, then everything carrying it.
     *
     * Returns the photos and one group holding them all, so the grid draws them exactly as it
     * draws a duplicate group.
     */
    suspend fun similarTo(photoId: String, level: Int): Pair<List<PhotoHit>, List<Int>> {
        val field = DUPLICATE_FIELDS[level.coerceIn(0, DUPLICATE_FIELDS.size - 1)]
        // The duplicate keys are docValues without stored values; a schema of version 1.6 hands
        // them to fl like any stored field.
        val key = try {
            select(
                listOf(
                    "q" to "*:*",
                    "fq" to "{!term f=id v=\$anchorId}",
                    "anchorId" to photoId,
                    "fl" to field,
                    "rows" to "1",
                )
            ).getJSONObject("response").optJSONArray("docs")?.optJSONObject(0)?.optString(field).orEmpty()
        } catch (e: ServiceException) {
            if (e.message?.contains("HTTP 400") == true) throw ServiceException("Finding similar photos needs your index reset for this version of the app. Open the app's photos screen to start it.")
            throw e
        }
        if (key.isBlank()) return emptyList<PhotoHit>() to emptyList()

        // {!field} instead of {!term}: size_bytes is numeric, and {!field} lets the field type
        // read the value, which {!term} does not do for a points field.
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "fq" to "{!field f=$field v=\$anchorKey}"
        params += "anchorKey" to key
        params += "fl" to FIELDS
        params += "rows" to SIMILAR_ROWS.toString()
        // With a tiebreaker: photos taken in the same second must come back in one fixed order.
        params += "sort" to "taken_at desc, id asc"
        // The photo everything is being compared with comes first, where it is expected, instead
        // of sitting somewhere down the list with a label on it (Cip, 2026-09-16). The sort is
        // stable, so the others keep their own order, newest first.
        val hits = parse(select(params), false, null).hits.sortedByDescending { it.id == photoId }
        return hits to if (hits.isEmpty()) emptyList() else listOf(hits.size)
    }

    /**
     * Runs a /select, re-reading the index password once if it was refused.
     */
    private suspend fun select(params: List<Pair<String, String>>): JSONObject {
        val session = prefs.session ?: throw ServiceException("Sign in to search")
        val connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        // The same question asked again inside the owner's chosen seconds costs no bandwidth.
        val key = SearchCache.key(connection.indexName, "/select", params)
        val ttl = prefs.cacheSeconds
        cache.get(key, ttl)?.let { stored ->
            runCatching { JSONObject(stored) }.getOrNull()?.let { return it }
        }
        // The request and the storing of its answer are one uncancellable step. Dragging the
        // duplicates slider cancels the job for every stop it crosses, and the call itself is
        // blocking, so the index answers anyway; with a cancellable step the answer was thrown
        // away on the way out and the next pass asked for it again. Wrapping only the put was
        // not enough: the cancellation is raised inside the call, before the put is reached
        // (Cip, 2026-09-16).
        return withContext(NonCancellable) {
            val json = try {
                SolrClient(connection).select(params)
            } catch (e: SolrAuthException) {
                SolrClient(IndexManager(context, prefs, api).refreshConnection(session)).select(params)
            }
            cache.put(key, json.toString())
            json
        }
    }

    /**
     * The duplicate groups of [field], from the cache when a recent answer is held: the slider
     * walks back and forth over the same few kinds, and each walk is otherwise a full request.
     */
    private suspend fun cachedDuplicateGroups(connection: IndexConnection, field: String): List<List<String>> {
        val key = SearchCache.key(connection.indexName, "/duplicates", listOf("field" to field))
        cache.get(key, prefs.cacheSeconds)?.let { stored ->
            runCatching {
                val outer = JSONArray(stored)
                (0 until outer.length()).map { i ->
                    val inner = outer.getJSONArray(i)
                    (0 until inner.length()).map { k -> inner.getString(k) }
                }
            }.getOrNull()?.let { return it }
        }
        // One uncancellable step, for the same reason as in select(): the slider cancels this job
        // on every stop it crosses, and an answer the index has already given must not be lost
        // between the call and the cache.
        return withContext(NonCancellable) {
            val groups = SolrClient(connection).duplicateGroups(field)
            cache.put(key, JSONArray(groups.map { JSONArray(it) }).toString())
            groups
        }
    }

    /**
     * The query and filter parameters shared by the results grid and the map. Returns the
     * parameters, whether vectors were used, and a notice for the user when they were not.
     */
    private suspend fun queryParams(
        query: String,
        filters: SearchFilters,
        legacy: Boolean = false,
        wordsOnly: Boolean = false,
        freshBias: Boolean = false,
    ): Triple<ArrayList<Pair<String, String>>, Boolean, String?> {
        val session = prefs.session ?: throw ServiceException("Sign in to search")
        val connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        val text = query.trim().take(300)

        var smart = false
        var notice: String? = null
        val params = ArrayList<Pair<String, String>>()

        if (text.isEmpty()) {
            params += "q" to "*:*"
            params += "sort" to "taken_at desc, id asc"
        } else {
            params += "uq" to text
            // The lexical leg: the owner's tags outrank CLIP's words, which outrank the rest.
            // Same edismax shape and the same mm as Opensolr's own site search ("flexible").
            // A single character has no meaning to embed, and the embed endpoint refuses it (404),
            // which used to show "Search by meaning is unavailable" (Cip, 2026-09-17): words only.
            // No vector search at all on a plan without it, or with the month's AI requests used
            // up: words only, even though the index still holds vectors (Cip, 2026-09-17).
            val aiUsable = prefs.account?.let { a -> a.vectorAllowed && (a.maxAiRequests <= 0 || a.aiRequestsUsed < a.maxAiRequests) } == true
            val vector = if (!wordsOnly && aiUsable && text.trim().length >= 2) {
                try {
                    api.embedQuery(session, connection.indexName, text)
                } catch (e: QuotaExceededException) {
                    notice = null
                    null
                } catch (e: VectorNotAllowedException) {
                    notice = null
                    null
                } catch (e: ServiceException) {
                    notice = null
                    null
                }
            } else null

            // Each kind of search weighs the fields its own way (Cip, 2026-09-17): alone, the words
            // carry the whole ranking; blended, they only nudge what the vector already found.
            val qf = when {
                legacy -> LEGACY_QF
                vector != null -> HYBRID_QF
                else -> QF
            }
            params += "lexicalRaw" to "{!edismax qf=\"$qf\" mm=\"$MM\" v=\$uq}"
            val matched = if (vector != null) {
                smart = true
                // Opensolr's {!hybrid} parser, exactly as search.opensolr.com runs it: both legs
                // scored on their own, normalised per query and blended (alpha = the vector's
                // share), candidates from either leg (union).
                params += "vectorQuery" to "{!knn f=embeddings topK=$KNN_TOP_K}" + vector.joinToString(",", "[", "]")
                "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=union alpha=${String.format(Locale.US, "%.2f", 1f - prefs.lexicalWeight)} topN=$KNN_TOP_K}"
            } else {
                "{!bool should=\$lexicalRaw}"
            }
            if (freshBias) {
                // Fresh: recency multiplies the score of the fused query, the way the Fresh toggle
                // works on search.opensolr.com. It reorders, it never filters, and nothing is lost
                // the way a sort by date would lose the best matches among the noise. A bf would
                // reach the lexical leg only, so the boost wraps the whole query instead.
                params += "freshBias" to FRESH_BIAS
                params += "matchedQuery" to matched
                params += "q" to "{!boost b=\$freshBias v=\$matchedQuery}"
            } else {
                params += "q" to matched
            }
            // Relevance first, then the id: two photos scoring the same must come back in one
            // fixed order, or consecutive pages of an infinite scroll overlap and the same photo
            // arrives twice. The grid keys its items by photo id, so a repeat crashed the app as
            // soon as it was drawn (Cip, 2026-09-16). This only settles ties; the ranking itself
            // is untouched.
            params += "sort" to "score desc, id asc"
        }

        // One fq per field with chosen values, OR-ing the values ({!terms}), tagged with the
        // field so that field's facet can exclude it (below) and keep offering every value.
        // Values travel as a bound parameter, separated by "|" (a control character is not
        // accepted as the separator; no facet value carries a pipe).
        filters.fields.forEach { (field, values) ->
            if (values.isEmpty() || field !in FACET_FIELDS) return@forEach
            params += "fq" to "{!terms f=$field tag=$field separator=| v=\$f_$field}"
            params += "f_$field" to values.filter { '|' !in it }.joinToString("|")
        }
        if (filters.withLocation) params += "fq" to "has_location:true"
        // Taken between two days. The ends travel as bound parameters, so nothing the picker
        // produced is ever spliced into the query itself.
        // Fields the older configuration does not have, so an index still on it is left alone
        // rather than asked for something it would refuse.
        if (!legacy) {
            filters.hasOcr?.let { params += "fq" to if (it) "ocr_t:*" else "-ocr_t:*" }
            filters.hasPeople?.let { params += "fq" to if (it) "persons_t:*" else "-persons_t:*" }
            filters.documents?.let { wanted ->
                params += "fq" to if (wanted) "{!lucene v=\$doc_q}" else "-{!lucene v=\$doc_q}"
                params += "doc_q" to "meaning:(" + SearchFilters.DOCUMENT_WORDS.joinToString(" OR ") + ")"
            }
        }
        filters.taken?.let { range ->
            // The whole clause travels as one bound parameter: a range cannot be expressed with
            // {!field} or {!term}, and nothing is spliced into the fq itself. The two ends are
            // formatted from a Long by a fixed pattern, so they can only ever be timestamps.
            params += "fq" to "{!lucene v=\$taken_q}"
            params += "taken_q" to "taken_at:[${range.fromSolr} TO ${range.toSolr}]"
        }
        // Tagged or untagged: the field is only on the newer configuration, so an index still on
        // the old one is left alone rather than being asked something it would refuse.
        if (!legacy) filters.tagged?.let { wanted ->
            params += "fq" to if (wanted) "custom_tags:[* TO *]" else "-custom_tags:[* TO *]"
        }
        filters.near?.let { near ->
            // Radius search on the GPS position; point and distance travel as bound parameters.
            params += "fq" to "{!geofilt sfield=location pt=\$near_pt d=\$near_d}"
            params += "near_pt" to String.format(Locale.US, "%.6f,%.6f", near.lat, near.lon)
            params += "near_d" to String.format(Locale.US, "%.3f", near.radiusKm.coerceIn(0.01, 20000.0))
        }
        return Triple(params, smart, notice)
    }

    /**
     * Turns the Solr answer into a page.
     */
    private fun parse(json: JSONObject, smart: Boolean, notice: String?): SearchPage {
        val response = json.getJSONObject("response")
        val docs = response.getJSONArray("docs")
        val hits = (0 until docs.length()).map { i -> hitOf(docs.getJSONObject(i)) }

        val facets = HashMap<String, List<FacetValue>>()
        json.optJSONObject("facet_counts")?.optJSONObject("facet_fields")?.let { fields ->
            fields.keys().forEach { field ->
                val pairs = fields.optJSONArray(field) ?: return@forEach
                facets[field] = (0 until pairs.length() / 2).map { FacetValue(pairs.optString(it * 2), pairs.optInt(it * 2 + 1)) }
            }
        }
        facets["year"] = facets["year"]?.sortedByDescending { it.value } ?: emptyList()

        // Spellcheck collation: Solr returns "collations" as [ "collation", "<query>", ... ]
        // or, with extended results, as objects carrying "collationQuery".
        var collation: String? = null
        json.optJSONObject("spellcheck")?.let { spell ->
            val collations = spell.optJSONArray("collations")
            if (collations != null) {
                for (i in 0 until collations.length()) {
                    val item = collations.opt(i)
                    if (item is String && item != "collation") { collation = item; break }
                    if (item is JSONObject) { collation = item.optString("collationQuery").ifBlank { null }; if (collation != null) break }
                }
            }
        }
        return SearchPage(hits, response.optLong("numFound"), facets, smart, notice, collation)
    }

    companion object {
        const val PAGE = 60
        /** How many of a photo's words have to match, in order, for two photos to be the same shot. */
        /** Every field the filter sheet facets on. Only these may appear in an fq. */
        private val FACET_FIELDS = SearchFilters.FACETS.map { it.first }.toSet()
        /** Most pins the map asks for at once. */
        const val MAP_ROWS = 1000

        /**
         * Most photos one heading's tick can take in. A month of a big library is thousands of
         * photos and they are asked for as ids only, so this is generous; it is here so that a
         * single tap can never ask the index for an unbounded list.
         */
        const val GROUP_TICK_MAX = 20000

        /** Ids per request when counting what a selection already carries. */
        private const val WORDS_OF_CHUNK = 1000
        /** Candidates of the vector leg and of the fusion, as on search.opensolr.com. */
        private const val KNN_TOP_K = 500
        /**
         * The vector's share of the blended score. Half and half: a photo that matches the
         * words (a tag, a name, a place) always ranks above one that only resembles them in
         * meaning, and among word matches the meaning decides. Opensolr's site search leans
         * further toward meaning (0.85) because product catalogues have no hand-written tags.
         */
        /**
         * Recency as a multiplier: 1.0 for a photo taken today, 0.5 a year later, 0.33 after two.
         * 3.16e-11 is 1/(a year in ms); max(0, ...) guards a date in the future, which would make
         * ms() negative and the reciprocal blow the whole query up.
         */
        private const val FRESH_BIAS = "recip(max(0,ms(NOW,taken_at)),3.16e-11,1,1)"
        /** Minimum-should-match of the lexical leg: Opensolr's "flexible" setting. */
        private const val MM = "2<65% 4<50% 8<40%"
        // ocr_t sits right under meaning: the words printed IN a photo name it as surely as the
        // words it was read into, and a receipt is asked for by what is on it (Cip, 2026-09-16).
        private const val QF = "custom_tags_text^5 meaning^2 ocr_t^3 persons_t^4 text file_name_text folder_text camera_text place_text^1"
        private const val HYBRID_QF = "custom_tags_text^0.5 meaning^0.2 ocr_t^0.4 persons_t^0.3 file_name_text folder_text camera_text place_text^0.1"
        private const val LEGACY_QF = "meaning^3 text file_name_text folder_text camera_text"
        private const val LEGACY_FIELDS = "score,id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,meaning,location,labels"
        /**
         * The duplicate keys the slider walks through, 0..10 (Cip, 2026-09-15): CLIP's first
         * 1..5 words, the EXIF a simple edit keeps, then the EXIF together with the first 1..5
         * words. Made on the server by photos_ingest; the schema's *_hash dynamic field.
         */
        val DUPLICATE_FIELDS = listOf(
            "dup_w1_hash", "dup_w2_hash", "dup_w3_hash", "dup_w4_hash", "dup_w5_hash",
            "dup_exif_hash",
            "dup_exif_w1_hash", "dup_exif_w2_hash", "dup_exif_w3_hash", "dup_exif_w4_hash", "dup_exif_w5_hash",
            // The same file name (without the folder: several folders can be indexed) and the
            // same size in bytes, straight from the stored fields, which have docValues already.
            "file_name", "size_bytes",
            // The strictest stop of all: the md5 of the file itself, so only true copies group
            // together (Cip, 2026-09-16). Same size is not the same file - a camera pads its
            // files to whole blocks, so hundreds of different photos share a size exactly.
            "file_hash",
        )
        /** Groups of duplicates fetched at a time; the rest follow as the grid is scrolled. */
        const val GROUPS_PAGE = 20

        /** Most photos "Show similar photos" brings back for one anchor photo. */
        private const val SIMILAR_ROWS = 200
        /** Photos an album needs before it is shown (Cip, 2026-09-15). */
        private const val ALBUM_MIN = 1
        /** CLIP words offered as albums: only the most used, never the whole vocabulary. */
        private const val ALBUM_THINGS = 12
        private const val FIELDS = "score,id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,size_bytes,meaning,ocr_t,persons_t,location,city,region,country,labels,custom_tags"
    }
}
