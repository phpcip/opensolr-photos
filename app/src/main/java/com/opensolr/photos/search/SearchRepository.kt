package com.opensolr.photos.search

import com.opensolr.photos.R
import com.opensolr.photos.AppText
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

data class SearchFilters(

    val fields: Map<String, Set<String>> = emptyMap(),
    val withLocation: Boolean = false,

    val tagged: Boolean? = null,
    val near: NearFilter? = null,

    val taken: DateRange? = null,

    val hasOcr: Boolean? = null,

    val hasPeople: Boolean? = null,

    val documents: Boolean? = null,

    val folders: Set<String> = emptySet(),
) {

    val count: Int get() = fields.values.sumOf { it.size } + folders.size + (if (withLocation) 1 else 0) +
        (if (tagged != null) 1 else 0) + (if (near != null) 1 else 0) + (if (taken != null) 1 else 0) +
        (if (hasOcr != null) 1 else 0) + (if (hasPeople != null) 1 else 0) + (if (documents != null) 1 else 0)

    fun values(field: String): Set<String> = fields[field] ?: emptySet()

    fun toggled(field: String, value: String): SearchFilters {
        val current = values(field)
        val next = if (value in current) current - value else current + value
        val map = fields.toMutableMap()
        if (next.isEmpty()) map.remove(field) else map[field] = next
        return copy(fields = map)
    }

    companion object {

        val FACETS = listOf(

            "year" to "Year", "city" to "City", "country" to "Country",
            "camera_model" to "Camera model", "custom_tags" to "My tags (Albums)",
            "labels" to "Meaning", "orientation" to "Orientation",

            "persons_ss" to "People",
        )

        const val FOLDER_ROOTS = "folder_root"

        fun folderPath(path: String?): String? = path?.trim()?.trim('/')?.ifBlank { null }

        fun cameraName(make: String?, model: String?): String? {
            val m = model?.trim()?.ifBlank { null } ?: return null
            val brand = make?.trim()?.ifBlank { null } ?: return m
            return if (m.startsWith(brand, ignoreCase = true)) m else "$brand $m"
        }

        fun folderRoots(chosen: Collection<String>): List<String> {
            val all = chosen.map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }
                .map { if (it.endsWith("/")) it else "$it/" }.distinct()
            return all.filter { root -> all.none { other -> other != root && root.startsWith(other) } }.sorted()
        }

        val NEWER_FACETS = setOf("city", "region", "country", "labels", "custom_tags", "persons_ss")

        val DOCUMENT_WORDS = listOf(
            "text", "label", "labels", "document", "documents", "receipt", "invoice",
            "certificate", "card", "ticket", "menu", "poster", "brochure", "flyer", "leaflet",
            "banner", "billboard", "guide", "print", "number", "tag", "\"nutrition facts\"",
            "website", "screenshot",
        )
    }
}

data class DateRange(val fromUtcMillis: Long, val toUtcMillis: Long) {

    private fun day(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    val fromSolr: String get() = day(fromUtcMillis) + "T00:00:00Z"

    val toSolr: String get() = day(toUtcMillis) + "T23:59:59Z"

    val label: String get() {

        val short = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return short.format(Date(fromUtcMillis)) + " – " + short.format(Date(toUtcMillis))
    }
}

data class NearFilter(val lat: Double, val lon: Double, val radiusKm: Double) {

    val radiusText: String get() = if (radiusKm < 1) String.format(Locale.US, "%.1f km", radiusKm) else String.format(Locale.US, "%.0f km", radiusKm)

    companion object {

        val RADII = listOf(0.5, 1.0, 5.0, 25.0, 100.0)
    }
}

data class DuplicatePage(
    val hits: List<PhotoHit>,
    val sizes: List<Int>,
    val totalGroups: Int,
    val groupsUsed: Int,
    val endReached: Boolean,
    /** Photos in ALL the groups, not only in this page: the count on screen must not grow as you scroll. */
    val totalPhotos: Int = 0,
)

data class PhotoPin(val hit: PhotoHit, val lat: Double, val lon: Double)

data class Album(val field: String, val value: String, val title: String, val count: Int, val covers: List<Long>)

data class TagSuggestions(val mine: List<String>, val fromMeanings: List<String>) {
    val isEmpty: Boolean get() = mine.isEmpty() && fromMeanings.isEmpty()
}

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

    val sizeBytes: Long = 0,
    val meaning: String,

    val ocrText: String = "",

    val persons: String = "",
    val location: String?,
    val city: String? = null,
    val region: String? = null,

    val province: String? = null,
    val country: String? = null,
    val labels: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),

    val score: Double = 0.0,
) {

    val latLon: Pair<Double, Double>? get() = parseLatLon(location)

    val placeLabel: String? get() {
        val names = ArrayList<String>(3)
        val seen = HashSet<String>()
        listOf(city ?: province, region, country).forEach { name ->
            val clean = name?.trim().orEmpty()
            if (clean.isNotEmpty() && seen.add(com.opensolr.photos.data.Words.fold(clean))) names += clean
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
        return latLon?.let { (lat, lon) -> String.format(Locale.US, "%.5f, %.5f", lat, lon) }
    }

    val takenMs: Long? = com.opensolr.photos.ui.Actions.solrDateMillis(takenAt)
}

fun parseLatLon(value: String?): Pair<Double, Double>? {
    val parts = value?.split(',') ?: return null
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
    return lat to lon
}

data class FacetValue(val value: String, val count: Int)

data class SearchPage(
    val hits: List<PhotoHit>,
    val numFound: Long,
    val facets: Map<String, List<FacetValue>>,
    val smart: Boolean,
    val notice: String?,
    val didYouMean: String? = null,
)

class SearchRepository(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()

    private val cache = SearchCache.of(context)

    fun clearCache() {
        cache.clear()

        synchronized(GROUPED) { GROUPED.clear() }
    }

    fun cachedCount(): Int = cache.count()

    suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int = PAGE, freshBias: Boolean = false, wordsOnly: Boolean = false): SearchPage =
        try {
            search(query, filters, start, rows, legacy = false, freshBias = freshBias, wordsOnly = wordsOnly)
        } catch (e: ServiceException) {

            if (e.message?.contains("HTTP 400") == true) search(query, filters, start, rows, legacy = true, freshBias = freshBias, wordsOnly = wordsOnly) else throw e
        }

    data class GroupedHit(
        val id: String,
        val takenMs: Long?,
        val country: String?,
        val region: String?,
        val city: String?,
        val persons: List<String>,
        val tags: List<String>,

        val folder: String? = null,

        val camera: String? = null,
    )

    suspend fun groupedHits(query: String, filters: SearchFilters, freshBias: Boolean = false, wordsOnly: Boolean = false): List<GroupedHit> {
        val (params, _, _) = queryParams(query, filters, legacy = false, wordsOnly = wordsOnly, freshBias = freshBias)
        params += "fl" to "id,taken_at,city,province,region,country,persons_ss,custom_tags,folder,camera_make,camera_model"
        params += "start" to "0"
        params += "rows" to GROUP_TICK_MAX.toString()
        if (params.none { it.first == "sort" }) params += "sort" to "taken_at desc, id asc"
        val docs = select(params).getJSONObject("response").getJSONArray("docs")
        fun list(d: JSONObject, field: String): List<String> =
            d.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("id").ifBlank { return@mapNotNull null }
            GroupedHit(
                id = id,
                takenMs = com.opensolr.photos.ui.Actions.solrDateMillis(d.optString("taken_at")),
                country = d.optString("country").trim().ifBlank { null },
                region = d.optString("region").trim().ifBlank { null },
                city = (d.optString("city").trim().ifBlank { null }) ?: d.optString("province").trim().ifBlank { null },
                persons = list(d, "persons_ss"),
                tags = list(d, "custom_tags"),
                folder = SearchFilters.folderPath(d.optString("folder")),
                camera = SearchFilters.cameraName(d.optString("camera_make"), d.optString("camera_model")),
            )
        }
    }

    suspend fun hitsByIds(ids: Collection<String>): List<PhotoHit> {
        val out = ArrayList<PhotoHit>(ids.size)
        ids.filter { '|' !in it }.chunked(200).forEach { batch ->
            val params = ArrayList<Pair<String, String>>()
            params += "q" to "*:*"
            params += "fq" to "{!terms f=id separator=| v=\$byIds}"
            params += "byIds" to batch.joinToString("|")
            params += "fl" to FIELDS
            params += "rows" to batch.size.toString()
            out += parse(select(params), false, null).hits
        }
        return out
    }

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

            emptyMap()
        }

    suspend fun browseFacets(): Map<String, List<FacetValue>> {
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "rows" to "0"
        params += "facet" to "true"
        params += "facet.mincount" to "1"

        params += "facet.limit" to "-1"
        FACET_FIELDS.forEach { params += "facet.field" to it }
        addFolderFacets(params)
        params += "facet.sort" to "index"
        return parse(select(params), smart = false, notice = null).facets
    }

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
        addFolderFacets(params)
        val json = select(params)
        if (json.getJSONObject("response").optLong("numFound") == 0L) return emptyMap()
        return parse(json, false, null).facets
    }

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
        province = d.optString("province").ifBlank { null },
        country = d.optString("country").ifBlank { null },
        labels = d.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        customTags = d.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        score = d.optDouble("score", 0.0),
    )

    private suspend fun embedOnce(session: com.opensolr.photos.data.Session, indexName: String, text: String): FloatArray {
        val key = "$indexName|$text"
        val now = System.currentTimeMillis()
        synchronized(EMBEDDED) {
            EMBEDDED[key]?.let { (at, vector) -> if (now - at < EMBED_HOLD_MS) return vector }
        }
        val vector = api.embedQuery(session, indexName, text)
        synchronized(EMBEDDED) {
            EMBEDDED[key] = now to vector

            while (EMBEDDED.size > EMBED_HELD) EMBEDDED.remove(EMBEDDED.keys.first())
        }
        return vector
    }

    private suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int, legacy: Boolean, freshBias: Boolean, wordsOnly: Boolean): SearchPage {
        val (params, smart, notice) = queryParams(query, filters, legacy, wordsOnly = wordsOnly, freshBias = freshBias)
        val text = query.trim().take(300)
        // no "did you mean" over deliberate operators: the suggestion would drop them
        if (text.isNotEmpty() && !SearchOperators.parse(text).hasOps) {

            params += "spellcheck" to "true"
            params += "spellcheck.q" to text
        }
        params += "fl" to (if (legacy) LEGACY_FIELDS else FIELDS)
        params += "start" to start.coerceAtLeast(0).toString()

        params += "rows" to rows.coerceIn(1, 1000).toString()

        if (start <= 0) {
            params += "facet" to "true"
            params += "facet.mincount" to "1"

            params += "facet.limit" to "-1"

            params += "facet.sort" to "index"
            FACET_FIELDS.filter { !legacy || it !in SearchFilters.NEWER_FACETS }
                .forEach { params += "facet.field" to "{!ex=$it key=$it}$it" }
            addFolderFacets(params)
        }
        val page = parse(select(params), smart, notice)
        return page.copy(didYouMean = collation(page, text))
    }

    suspend fun photosBetween(
        query: String,
        filters: SearchFilters,
        from: Long,
        to: Long,
        freshBias: Boolean = false,
        wordsOnly: Boolean = false,

        full: Boolean = false,
    ): List<PhotoHit> {
        val (params, _, _) = queryParams(query, filters, legacy = false, wordsOnly = wordsOnly, freshBias = freshBias)
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

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

            params += "facet.limit" to "-1"
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
     * Groups of alike photos, inside what the owner is looking at: the typed words (as words, no
     * vector) and every active filter narrow the groups, so "duplicates" answers about this
     * result set and not about the whole library.
     */
    suspend fun duplicates(level: Int, groupsFrom: Int = 0, groupsLimit: Int = GROUPS_PAGE, query: String = "", filters: SearchFilters = SearchFilters()): DuplicatePage {
        val session = prefs.session ?: throw ServiceException(AppText.s(R.string.err_sign_in_to_search))
        val connection = prefs.connection ?: throw ServiceException(AppText.s(R.string.err_not_set_up))
        val stop = stopOf(level.coerceAtLeast(FIRST_LIBRARY_LEVEL))
        val (asked, _, _) = queryParams(query, filters, legacy = false, wordsOnly = true)
        val base = asked.filter { it.first != "sort" && it.first != "fl" && it.first != "rows" && it.first != "start" }
        val groups = try {
            try {
                cachedDuplicateGroups(connection, stop, base)
            } catch (e: SolrAuthException) {
                cachedDuplicateGroups(IndexManager(context, prefs, api).refreshConnection(session), stop, base)
            }
        } catch (e: ServiceException) {

            if (e.message?.contains("HTTP 400") == true) throw ServiceException(AppText.s(R.string.err_dup_needs_reset))
            throw e
        }
        if (groups.isEmpty()) return DuplicatePage(emptyList(), emptyList(), 0, 0, true)
        val totalPhotos = groups.sumOf { it.size }

        val page = ArrayList<List<String>>(groupsLimit.coerceAtLeast(1))
        var budget = 0
        var at = groupsFrom
        while (at < groups.size && page.size < groupsLimit.coerceAtLeast(1)) {
            val group = groups[at]
            if (page.isNotEmpty() && budget + group.size > DOCS_PAGE) break
            page += group
            budget += group.size
            at++
        }
        val used = at - groupsFrom
        if (page.isEmpty()) return DuplicatePage(emptyList(), emptyList(), groups.size, 0, true, totalPhotos)

        val wanted = page.flatten()
        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        params += "fq" to "{!terms f=id separator=| v=\$dupIds}"
        params += "dupIds" to wanted.joinToString("|")
        params += "fl" to FIELDS
        params += "rows" to wanted.size.toString()
        val found = HashMap<String, PhotoHit>(wanted.size)
        parse(select(params), false, null).hits.forEach { found[it.id] = it }
        val hits = ArrayList<PhotoHit>(wanted.size)
        val sizes = ArrayList<Int>(page.size)
        page.forEach { group ->
            val present = group.mapNotNull { found[it] }
            if (present.size >= 2) {
                hits += present
                sizes += present.size
            }
        }
        return DuplicatePage(hits, sizes, groups.size, used, at >= groups.size, totalPhotos)
    }

    suspend fun albums(): List<AlbumSection> {

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

        return listOf(
            AlbumSection("People", albumsOf("people", "persons_ss")),
            AlbumSection("My tags (Albums)", albumsOf("tags", "custom_tags")),
            AlbumSection("Things", albumsOf("things", "labels")),
            AlbumSection("Years", albumsOf("years", "year")),
            AlbumSection("Places", albumsOf("cities", "city") + albumsOf("countries", "country")),
            AlbumSection("Cameras", albumsOf("cameras", "camera_model", cameraTitle)),
        ).filter { it.albums.isNotEmpty() }
    }

    suspend fun albumPhotos(albums: List<Album>): List<PhotoHit> {
        if (albums.isEmpty()) return emptyList()
        val connection = prefs.connection ?: throw ServiceException(AppText.s(R.string.err_not_set_up))
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

        params += "f.custom_tags.facet.limit" to (tagLimit + skip.size).toString()
        params += "f.labels.facet.limit" to (wordLimit + tagLimit + skip.size).toString()
        if (text.isNotEmpty()) {

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

    suspend fun suggest(prefix: String): List<String> {
        val text = prefix.trim().take(100)
        if (text.length < 2) return emptyList()
        val connection = prefs.connection ?: return emptyList()

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

    private fun collation(page: SearchPage, typed: String): String? {
        val suggestion = page.didYouMean ?: return null
        return suggestion.takeIf { it.isNotBlank() && !it.equals(typed, ignoreCase = true) }
    }

    suspend fun pins(query: String, filters: SearchFilters): List<PhotoPin> {
        val (params, _, _) = queryParams(query, filters)
        params += "fq" to "has_location:true"
        params += "fl" to FIELDS
        params += "start" to "0"
        params += "rows" to MAP_ROWS.toString()
        val page = parse(select(params), false, null)
        return page.hits.mapNotNull { hit -> hit.latLon?.let { (lat, lon) -> PhotoPin(hit, lat, lon) } }
    }

    suspend fun similarTo(photoId: String, level: Int): Pair<List<PhotoHit>, List<Int>> {
        val stop = stopOf(level)
        val field = stop.field
        var anchorCamera: String? = null

        val keys = try {
            val doc = select(
                listOf(
                    "q" to "*:*",
                    "fq" to "{!term f=id v=\$anchorId}",
                    "anchorId" to photoId,
                    "fl" to (if (stop.within != null) "$field,${stop.within}" else field),
                    "rows" to "1",
                )
            ).getJSONObject("response").optJSONArray("docs")?.optJSONObject(0)
            anchorCamera = stop.within?.let { doc?.optString(it)?.trim()?.ifBlank { null } }

            doc?.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOfNotNull(doc?.optString(field))
        } catch (e: ServiceException) {
            if (e.message?.contains("HTTP 400") == true) throw ServiceException(AppText.s(R.string.err_similar_needs_reset))
            throw e
        }.filter { it.isNotBlank() && '|' !in it }
        if (keys.isEmpty()) return emptyList<PhotoHit>() to emptyList()

        val params = ArrayList<Pair<String, String>>()
        params += "q" to "*:*"
        if (keys.size == 1) {
            params += "fq" to "{!field f=$field v=\$anchorKey}"
            params += "anchorKey" to keys[0]
        } else {
            params += "fq" to "{!terms f=$field separator=| v=\$anchorKeys}"
            params += "anchorKeys" to keys.joinToString("|")
        }
        // a stop that asks for the same camera answers only with photos taken by the anchor's own
        if (stop.within != null) {
            if (anchorCamera == null) return emptyList<PhotoHit>() to emptyList()
            params += "fq" to "{!field f=${stop.within} v=\$anchorCamera}"
            params += "anchorCamera" to anchorCamera
        }
        params += "fl" to FIELDS
        params += "rows" to SIMILAR_ROWS.toString()

        params += "sort" to "taken_at desc, id asc"

        val hits = parse(select(params), false, null).hits.sortedByDescending { it.id == photoId }
        return hits to if (hits.isEmpty()) emptyList() else listOf(hits.size)
    }

    private suspend fun select(params: List<Pair<String, String>>): JSONObject {
        val session = prefs.session ?: throw ServiceException(AppText.s(R.string.err_sign_in_to_search))
        val connection = prefs.connection ?: throw ServiceException(AppText.s(R.string.err_not_set_up))

        val key = SearchCache.key(connection.indexName, "/select", params)
        val ttl = prefs.cacheSeconds
        cache.get(key, ttl)?.let { stored ->
            runCatching { JSONObject(stored) }.getOrNull()?.let { return it }
        }

        return withContext(NonCancellable) {

            val body = try {
                SolrClient(connection).selectText(params)
            } catch (e: SolrAuthException) {
                SolrClient(IndexManager(context, prefs, api).refreshConnection(session)).selectText(params)
            }
            cache.put(key, body)
            JSONObject(body)
        }
    }

    private suspend fun cachedDuplicateGroups(connection: IndexConnection, stop: DuplicateStop, base: List<Pair<String, String>>): List<List<String>> {
        val cap = MAX_GROUP
        val field = stop.field
        val within = stop.within

        val key = SearchCache.key(
            connection.indexName,
            "/duplicates",
            listOf("field" to field, "cap" to cap.toString(), "within" to (within ?: "")) + base,
        )
        val ttl = prefs.cacheSeconds

        if (ttl > 0) {
            synchronized(GROUPED) {
                GROUPED[key]?.let { (at, groups) ->
                    if (System.currentTimeMillis() - at < ttl * 1000L) return groups
                }
            }
        }
        cache.get(key, ttl)?.let { stored ->
            runCatching {
                val outer = JSONArray(stored)
                (0 until outer.length()).map { i ->
                    val inner = outer.getJSONArray(i)
                    (0 until inner.length()).map { k -> inner.getString(k) }
                }
            }.getOrNull()?.let { return rememberGroups(key, it) }
        }

        return withContext(NonCancellable) {
            val found = SolrClient(connection).duplicateGroups(field, cap, MAX_GROUPS, within, base)
            val groups = if (field in MULTI_KEY_FIELDS) withoutSharedPhotos(found) else found
            cache.put(key, JSONArray(groups.map { JSONArray(it) }).toString())
            rememberGroups(key, groups)
        }
    }

    private fun withoutSharedPhotos(groups: List<List<String>>): List<List<String>> {
        val taken = HashSet<String>()
        val out = ArrayList<List<String>>(groups.size)
        groups.forEach { group ->
            val own = group.filter { it !in taken }
            if (own.size >= 2) {
                taken += own
                out += own
            }
        }
        return out
    }

    private fun rememberGroups(key: String, groups: List<List<String>>): List<List<String>> {
        synchronized(GROUPED) {
            GROUPED[key] = System.currentTimeMillis() to groups
            while (GROUPED.size > GROUPS_HELD) GROUPED.remove(GROUPED.keys.first())
        }
        return groups
    }

    private fun folderRoots(): List<String> = SearchFilters.folderRoots(prefs.folders)

    private fun addFolderFacets(params: MutableList<Pair<String, String>>) {
        val roots = folderRoots()
        val bound = params.any { it.first == "frv_0" }
        roots.forEachIndexed { i, root ->
            if (!bound) params += "frv_$i" to root
            params += "facet.query" to "{!prefix f=folder v=\$frv_$i key=fr_$i ex=${SearchFilters.FOLDER_ROOTS}}"
        }
    }

    private suspend fun queryParams(
        query: String,
        filters: SearchFilters,
        legacy: Boolean = false,
        wordsOnly: Boolean = false,
        freshBias: Boolean = false,
    ): Triple<ArrayList<Pair<String, String>>, Boolean, String?> {
        val session = prefs.session ?: throw ServiceException(AppText.s(R.string.err_sign_in_to_search))
        val connection = prefs.connection ?: throw ServiceException(AppText.s(R.string.err_not_set_up))
        val text = query.trim().take(300)

        var smart = false
        var notice: String? = null
        val params = ArrayList<Pair<String, String>>()

        if (text.isEmpty()) {
            params += "q" to "*:*"
            params += "sort" to "taken_at desc, id asc"
        } else {
            // +word / -word / +"phrase" / -"phrase": with AI they leave the text and become
            // filters (both legs obey an fq); with nothing left to embed, words only, as typed.
            val ops = SearchOperators.parse(text)
            val embedText = if (ops.hasOps) ops.base else text

            val aiUsable = prefs.account?.let { a -> a.vectorAllowed && (a.maxAiRequests <= 0 || a.aiRequestsUsed < a.maxAiRequests) } == true
            val vector = if (!wordsOnly && aiUsable && embedText.trim().length >= 2) {
                try {
                    embedOnce(session, connection.indexName, embedText)
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

            val qf = when {
                legacy -> LEGACY_QF
                vector != null -> HYBRID_QF
                else -> QF
            }
            if (vector != null && ops.hasOps) {
                params += "uq" to ops.base
                val fields = qf.split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.substringBefore('^') }
                ops.required.forEachIndexed { n, term ->
                    params += "reqQ$n" to term
                    params += "fq" to "{!edismax qf=\"$fields\" mm=\"100%\" v=\$reqQ$n}"
                }
                ops.excluded.forEachIndexed { n, term ->
                    params += "negQ$n" to term
                    params += "fq" to "-{!edismax qf=\"$fields\" mm=\"100%\" v=\$negQ$n}"
                }
            } else {
                params += "uq" to text
            }
            params += "lexicalRaw" to "{!edismax qf=\"$qf\" mm=\"$MM\" v=\$uq}"
            val matched = if (vector != null) {
                smart = true

                params += "vectorQuery" to "{!knn f=embeddings topK=$KNN_TOP_K}" + vector.joinToString(",", "[", "]")
                "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=union alpha=${String.format(Locale.US, "%.2f", 1f - prefs.lexicalWeight)} topN=$KNN_TOP_K}"
            } else {
                "{!bool should=\$lexicalRaw}"
            }
            if (freshBias) {

                params += "freshBias" to FRESH_BIAS
                params += "matchedQuery" to matched
                params += "q" to "{!boost b=\$freshBias v=\$matchedQuery}"
            } else {
                params += "q" to matched
            }

            params += "sort" to "score desc, id asc"
        }

        filters.fields.forEach { (field, values) ->
            if (values.isEmpty() || field !in FACET_FIELDS) return@forEach
            params += "fq" to "{!terms f=$field tag=$field separator=| v=\$f_$field}"
            params += "f_$field" to values.filter { '|' !in it }.joinToString("|")
        }

        val roots = folderRoots()
        val chosenRoots = roots.withIndex().filter { it.value in filters.folders }
        if (chosenRoots.isNotEmpty()) {
            params += "fq" to "{!bool tag=${SearchFilters.FOLDER_ROOTS} " + chosenRoots.joinToString(" ") { "should=\$fr_${it.index}" } + "}"
            chosenRoots.forEach { params += "fr_${it.index}" to "{!prefix f=folder v=\$frv_${it.index}}" }
        }
        roots.forEachIndexed { i, root -> params += "frv_$i" to root }
        if (filters.withLocation) params += "fq" to "has_location:true"

        if (!legacy) {
            filters.hasOcr?.let { params += "fq" to if (it) "ocr_t:*" else "-ocr_t:*" }
            filters.hasPeople?.let { params += "fq" to if (it) "persons_t:*" else "-persons_t:*" }
            filters.documents?.let { wanted ->
                params += "fq" to if (wanted) "{!lucene v=\$doc_q}" else "-{!lucene v=\$doc_q}"
                params += "doc_q" to "meaning:(" + SearchFilters.DOCUMENT_WORDS.joinToString(" OR ") + ")"
            }
        }
        filters.taken?.let { range ->

            params += "fq" to "{!lucene v=\$taken_q}"
            params += "taken_q" to "taken_at:[${range.fromSolr} TO ${range.toSolr}]"
        }

        if (!legacy) filters.tagged?.let { wanted ->
            params += "fq" to if (wanted) "custom_tags:[* TO *]" else "-custom_tags:[* TO *]"
        }
        filters.near?.let { near ->

            params += "fq" to "{!geofilt sfield=location pt=\$near_pt d=\$near_d}"
            params += "near_pt" to String.format(Locale.US, "%.6f,%.6f", near.lat, near.lon)
            params += "near_d" to String.format(Locale.US, "%.3f", near.radiusKm.coerceIn(0.01, 20000.0))
        }
        return Triple(params, smart, notice)
    }

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
        json.optJSONObject("facet_counts")?.optJSONObject("facet_queries")?.let { queries ->
            val roots = folderRoots()
            val counts = roots.mapIndexedNotNull { i, root ->
                queries.optInt("fr_$i", 0).takeIf { it > 0 }?.let { FacetValue(root, it) }
            }
            if (counts.isNotEmpty()) facets[SearchFilters.FOLDER_ROOTS] = counts
        }

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

        private val FACET_FIELDS = SearchFilters.FACETS.map { it.first }.toSet()

        const val MAP_ROWS = 1000

        const val GROUP_TICK_MAX = 20000

        private const val WORDS_OF_CHUNK = 1000

        private val EMBEDDED = LinkedHashMap<String, Pair<Long, FloatArray>>()

        private const val EMBED_HELD = 8

        private val GROUPED = LinkedHashMap<String, Pair<Long, List<List<String>>>>()

        private const val GROUPS_HELD = 4

        private const val EMBED_HOLD_MS = 30 * 60 * 1000L

        private const val KNN_TOP_K = 500

        private const val FRESH_BIAS = "recip(max(0,ms(NOW,taken_at)),3.16e-11,1,1)"

        private const val MM = "2<65% 4<50% 8<40%"

        private const val QF = "custom_tags_text^5 meaning^2 labels_t^2 ocr_t^3 persons_t^4 text file_name_text folder_text camera_text place_text^1"
        private const val HYBRID_QF = "custom_tags_text^0.5 meaning^0.2 labels_t^0.2 ocr_t^0.4 persons_t^0.3 file_name_text folder_text camera_text place_text^0.1"
        private const val LEGACY_QF = "meaning^3 text file_name_text folder_text camera_text"
        private const val LEGACY_FIELDS = "score,id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,meaning,location,labels"

        /** The widest number of labels a stop groups on: the server writes a key for 2..this many.
         *  Three, because an image model that names two or three things has nothing to say past that. */
        const val WORD_STOPS_MAX = 3

        /** One stop of the slider: the key photos are grouped on, and the field they must also share. */
        data class DuplicateStop(val field: String, val within: String? = null)

        // The stops, in both views, from loose to strict: each words stop has its own "and the same
        // camera" twin (Cip, 09/22/2026 - two labels alone pair photos by arithmetic; the camera turns
        // those pairs back into one person photographing one thing), then the sentence, the EXIF and
        // the three file keys.
        val DUPLICATE_STOPS = (2..WORD_STOPS_MAX).flatMap { k ->
            listOf(DuplicateStop("dup_w${k}_hash"), DuplicateStop("dup_w${k}_hash", "camera_model"))
        } + listOf(
            DuplicateStop("dup_desc_hash"),
            DuplicateStop("dup_exif_hash"),

            DuplicateStop("file_name"), DuplicateStop("size_bytes"),

            DuplicateStop("file_hash"),
        )

        fun stopOf(level: Int): DuplicateStop = DUPLICATE_STOPS[level.coerceIn(0, DUPLICATE_STOPS.size - 1)]

        val MULTI_KEY_FIELDS = emptySet<String>()

        const val MAX_GROUP = 50


        const val MAX_GROUPS = 2000

        const val FIRST_LIBRARY_LEVEL = 0

        const val DEFAULT_DUPLICATE_LEVEL = FIRST_LIBRARY_LEVEL

        const val GROUPS_PAGE = 20

        const val DOCS_PAGE = 300

        private const val SIMILAR_ROWS = 200

        private const val ALBUM_MIN = 1

        private const val ALBUM_THINGS = 12
        private const val FIELDS = "score,id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,size_bytes,meaning,ocr_t,persons_t,location,city,region,province,country,labels,custom_tags"
    }
}
