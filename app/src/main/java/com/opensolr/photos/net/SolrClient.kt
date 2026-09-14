package com.opensolr.photos.net

import com.opensolr.photos.data.IndexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the phone's Opensolr Index directly, over HTTPS with its HTTP Basic credentials:
 * /select to search and to list ids, /update to write and delete.
 *
 * Searches are always POSTed as a form, never put in a URL: query vectors are too long for a
 * URL, and a POST body keeps the search terms out of access logs.
 */
class SolrClient(private val connection: IndexConnection, private val http: OkHttpClient = Http.client) {

    private val authorization = Credentials.basic(connection.username, connection.password, Charsets.UTF_8)

    /**
     * Runs /select with [params] (a name may repeat, e.g. several fq) and returns the parsed answer.
     */
    suspend fun select(params: List<Pair<String, String>>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply {
            params.forEach { (name, value) -> add(name, value) }
            add("wt", "json")
        }.build()
        JSONObject(execute(request("/select").post(form).build()))
    }

    /**
     * Every document id in the index, fetched [pageSize] at a time with start/rows, sorted by id
     * so that consecutive pages line up. [onPage] reports how many ids arrived so far.
     */
    suspend fun allIds(pageSize: Int = 1000, onPage: suspend (Int) -> Unit = {}): Set<String> {
        val ids = HashSet<String>()
        var start = 0
        while (true) {
            val json = select(
                listOf(
                    "q" to "*:*",
                    "fl" to "id",
                    "sort" to "id asc",
                    "start" to start.toString(),
                    "rows" to pageSize.toString(),
                )
            )
            val docs = json.getJSONObject("response").getJSONArray("docs")
            for (i in 0 until docs.length()) {
                docs.getJSONObject(i).optString("id").takeIf { it.isNotEmpty() }?.let { ids += it }
            }
            onPage(ids.size)
            if (docs.length() < pageSize) break
            start += pageSize
        }
        return ids
    }

    /**
     * Number of documents in the index.
     */
    suspend fun count(): Long =
        select(listOf("q" to "*:*", "rows" to "0")).getJSONObject("response").optLong("numFound")

    /**
     * True when the index carries this app's schema. An index without it answers 400 for the
     * unknown field.
     */
    suspend fun hasPhotoSchema(): Boolean = try {
        select(listOf("q" to "*:*", "rows" to "0", "fq" to "meaning:[* TO *]"))
        true
    } catch (e: ServiceException) {
        if (e.message?.contains("HTTP 400") == true) false else throw e
    }

    /**
     * Adds or replaces [docs]. They become searchable within ten seconds.
     */
    suspend fun add(docs: JSONArray) = withContext(Dispatchers.IO) {
        execute(request("/update?commitWithin=10000&wt=json").post(docs.toString().toRequestBody(JSON)).build())
    }

    /**
     * Deletes the documents with [ids].
     */
    suspend fun delete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("delete", JSONArray(ids)).toString()
        execute(request("/update?commitWithin=10000&wt=json").post(body.toRequestBody(JSON)).build())
    }

    /**
     * Hard commit, so everything written so far is durable and visible.
     */
    suspend fun commit() = withContext(Dispatchers.IO) {
        execute(request("/update?commit=true&wt=json").post("{\"commit\":{}}".toRequestBody(JSON)).build())
    }

    /**
     * A request to [path] under the index, with its credentials.
     */
    private fun request(path: String): Request.Builder =
        Request.Builder().url(connection.baseUrl.trimEnd('/') + path).header("Authorization", authorization)

    /**
     * Runs a request and returns the body, mapping the index's refusals: 401 is a changed
     * password, 403 is a plan limit (Opensolr closes an index that is over its disk space or
     * bandwidth), anything else non-2xx is a service error carrying the status.
     */
    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 -> throw SolrAuthException()
                response.code == 403 -> throw PlanLimitException()
                !response.isSuccessful -> throw ServiceException("The index answered HTTP ${response.code}")
            }
            text
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
