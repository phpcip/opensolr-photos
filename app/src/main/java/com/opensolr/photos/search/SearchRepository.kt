package com.opensolr.photos.search

import android.content.Context
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.index.IndexManager
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.QuotaExceededException
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.SolrAuthException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.net.VectorNotAllowedException
import org.json.JSONObject
import java.util.Locale

/**
 * Filters the user can combine with a search.
 */
data class SearchFilters(
    /** Chosen values per Solr field (year, folder, camera_model, ...); several per field are OR-ed. */
    val fields: Map<String, Set<String>> = emptyMap(),
    val withLocation: Boolean = false,
    val near: NearFilter? = null,
) {
    /** Number of active filters, for the filter button badge. */
    val count: Int get() = fields.values.sumOf { it.size } + (if (withLocation) 1 else 0) + (if (near != null) 1 else 0)

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
            "year" to "Year", "folder" to "Folder", "city" to "City", "region" to "Region", "country" to "Country",
            "camera_make" to "Camera make", "camera_model" to "Camera model", "custom_tags" to "My tags",
            "labels" to "Meaning", "orientation" to "Orientation",
        )
        /** Facet fields an index on the previous configuration does not have. */
        val NEWER_FACETS = setOf("city", "region", "country", "labels", "custom_tags")
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
 * A photo with a GPS position, for the map.
 */
data class PhotoPin(val hit: PhotoHit, val lat: Double, val lon: Double)

/**
 * One photo in the results.
 */
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
    val meaning: String,
    val location: String?,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val labels: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),
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
     * Runs a search. [start] is the offset of the first result.
     */
    suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int = PAGE): SearchPage =
        try {
            search(query, filters, start, rows, legacy = false)
        } catch (e: ServiceException) {
            // An index still on an older configuration (rebuild postponed) lacks the newest
            // fields and answers 400 to them; search it with what it has.
            if (e.message?.contains("HTTP 400") == true) search(query, filters, start, rows, legacy = true) else throw e
        }

    /**
     * One search request; [legacy] leaves out the fields an older configuration lacks.
     */
    private suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int, legacy: Boolean): SearchPage {
        val (params, smart, notice) = queryParams(query, filters, legacy)
        val text = query.trim().take(300)
        if (text.isNotEmpty()) {
            // Spellcheck the typed words; the collation is offered as "did you mean".
            params += "spellcheck" to "true"
            params += "spellcheck.q" to text
        }
        params += "fl" to (if (legacy) LEGACY_FIELDS else FIELDS)
        params += "start" to start.coerceAtLeast(0).toString()
        params += "rows" to rows.coerceIn(1, 100).toString()
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
     * Autocomplete for what the user typed so far: labels of the indexed photos.
     */
    suspend fun suggest(prefix: String): List<String> {
        val text = prefix.trim().take(100)
        if (text.length < 2) return emptyList()
        val connection = prefs.connection ?: return emptyList()
        return try {
            SolrClient(connection).suggest(text)
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
     * Runs a /select, re-reading the index password once if it was refused.
     */
    private suspend fun select(params: List<Pair<String, String>>): JSONObject {
        val session = prefs.session ?: throw ServiceException("Sign in to search")
        val connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        return try {
            SolrClient(connection).select(params)
        } catch (e: SolrAuthException) {
            SolrClient(IndexManager(context, prefs, api).refreshConnection(session)).select(params)
        }
    }

    /**
     * The query and filter parameters shared by the results grid and the map. Returns the
     * parameters, whether vectors were used, and a notice for the user when they were not.
     */
    private suspend fun queryParams(query: String, filters: SearchFilters, legacy: Boolean = false): Triple<ArrayList<Pair<String, String>>, Boolean, String?> {
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
            params += "lexicalRaw" to "{!edismax qf=\"${if (legacy) LEGACY_QF else QF}\" mm=\"$MM\" v=\$uq}"
            val vector = if (prefs.account?.vectorAllowed == true) {
                try {
                    api.embedQuery(session, connection.indexName, text)
                } catch (e: QuotaExceededException) {
                    notice = "Your plan's AI requests for this month are used up, so this search matches words only."
                    null
                } catch (e: VectorNotAllowedException) {
                    notice = "Your plan does not include vector search, so this search matches words only."
                    null
                } catch (e: ServiceException) {
                    notice = "Search by meaning is unavailable right now, so this search matches words only."
                    null
                }
            } else null

            if (vector != null) {
                smart = true
                // Opensolr's {!hybrid} parser, exactly as search.opensolr.com runs it: both legs
                // scored on their own, normalised per query and blended (alpha = the vector's
                // share), candidates from either leg (union).
                params += "vectorQuery" to "{!knn f=embeddings topK=$KNN_TOP_K}" + vector.joinToString(",", "[", "]")
                params += "q" to "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=union alpha=$HYBRID_ALPHA topN=$KNN_TOP_K}"
            } else {
                params += "q" to "{!bool should=\$lexicalRaw}"
            }
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
        val hits = (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            PhotoHit(
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
                meaning = d.optString("meaning"),
                location = d.optString("location").ifBlank { null },
                city = d.optString("city").ifBlank { null },
                region = d.optString("region").ifBlank { null },
                country = d.optString("country").ifBlank { null },
                labels = d.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                customTags = d.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }

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
        /** Every field the filter sheet facets on. Only these may appear in an fq. */
        private val FACET_FIELDS = SearchFilters.FACETS.map { it.first }.toSet()
        /** Most pins the map asks for at once. */
        const val MAP_ROWS = 1000
        /** Candidates of the vector leg and of the fusion, as on search.opensolr.com. */
        private const val KNN_TOP_K = 500
        /**
         * The vector's share of the blended score. Half and half: a photo that matches the
         * words (a tag, a name, a place) always ranks above one that only resembles them in
         * meaning, and among word matches the meaning decides. Opensolr's site search leans
         * further toward meaning (0.85) because product catalogues have no hand-written tags.
         */
        private const val HYBRID_ALPHA = "0.5"
        /** Minimum-should-match of the lexical leg: Opensolr's "flexible" setting. */
        private const val MM = "2<65% 4<50% 8<40%"
        private const val QF = "custom_tags_text^5 meaning^3 text file_name_text folder_text camera_text place_text"
        private const val LEGACY_QF = "meaning^3 text file_name_text folder_text camera_text"
        private const val LEGACY_FIELDS = "id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,meaning,location,labels"
        private const val FIELDS = "id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,meaning,location,city,region,country,labels,custom_tags"
    }
}
