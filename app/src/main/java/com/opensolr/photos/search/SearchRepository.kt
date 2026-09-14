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

/**
 * Filters the user can combine with a search.
 */
data class SearchFilters(
    val year: String? = null,
    val folder: String? = null,
    val camera: String? = null,
    val withLocation: Boolean = false,
    val orientation: String? = null,
) {
    /** Number of active filters, for the filter button badge. */
    val count: Int get() = listOfNotNull(year, folder, camera, orientation).size + if (withLocation) 1 else 0
}

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
)

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
    suspend fun search(query: String, filters: SearchFilters, start: Int, rows: Int = PAGE): SearchPage {
        val session = prefs.session ?: throw ServiceException("Sign in to search")
        var connection = prefs.connection ?: throw ServiceException("Your index is not set up yet. Open Sync and start a sync.")
        val text = query.trim().take(300)

        var smart = false
        var notice: String? = null
        val params = ArrayList<Pair<String, String>>()

        if (text.isEmpty()) {
            params += "q" to "*:*"
            params += "sort" to "taken_at desc, id asc"
        } else {
            params += "uq" to text
            params += "lex" to "{!edismax qf='meaning^3 text file_name_text folder_text camera_text' mm=1 v=\$uq}"
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
                params += "vec" to "{!knn f=embeddings topK=$KNN_TOP_K}" + vector.joinToString(",", "[", "]")
                params += "q" to "{!bool should=\$lex should=\$vec}"
            } else {
                params += "q" to "{!bool should=\$lex}"
            }
        }

        filters.year?.let { params += "fq" to "{!term f=year v=\$f_year}"; params += "f_year" to it }
        filters.folder?.let { params += "fq" to "{!term f=folder v=\$f_folder}"; params += "f_folder" to it }
        filters.camera?.let { params += "fq" to "{!term f=camera_model v=\$f_camera}"; params += "f_camera" to it }
        filters.orientation?.let { params += "fq" to "{!term f=orientation v=\$f_orientation}"; params += "f_orientation" to it }
        if (filters.withLocation) params += "fq" to "has_location:true"

        params += "fl" to FIELDS
        params += "start" to start.coerceAtLeast(0).toString()
        params += "rows" to rows.coerceIn(1, 100).toString()
        params += "facet" to "true"
        params += "facet.mincount" to "1"
        params += "facet.limit" to "80"
        params += "facet.field" to "year"
        params += "facet.field" to "folder"
        params += "facet.field" to "camera_model"
        params += "facet.field" to "orientation"
        params += "f.year.facet.sort" to "index"

        val json = try {
            SolrClient(connection).select(params)
        } catch (e: SolrAuthException) {
            connection = IndexManager(context, prefs, api).refreshConnection(session)
            SolrClient(connection).select(params)
        }
        return parse(json, smart, notice)
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
        return SearchPage(hits, response.optLong("numFound"), facets, smart, notice)
    }

    companion object {
        const val PAGE = 60
        private const val KNN_TOP_K = 60
        private const val FIELDS = "id,media_id,path,file_name,folder,mime,taken_at,camera_make,camera_model,lens,iso,exposure,f_number,focal_length,width,height,meaning,location"
    }
}
