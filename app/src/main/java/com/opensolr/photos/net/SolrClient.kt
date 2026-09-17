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
    suspend fun allIds(pageSize: Int = 1000, query: String = "*:*", onPage: suspend (Int) -> Unit = {}): Set<String> {
        val ids = HashSet<String>()
        var start = 0
        while (true) {
            val json = select(
                listOf(
                    "q" to query,
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
     * Walks every photo in the index, [pageSize] at a time, handing each page of (id, size_bytes
     * it was indexed with) to [onPage] together with the index's total. Cursor paging, so a deep
     * page costs the same as the first; the pages arrive in id order. The caller compares each
     * page with the phone's files as it comes, so the whole index is never held in memory.
     */
    suspend fun forEachSizePage(pageSize: Int = 1000, onPage: suspend (total: Long, page: List<Triple<String, Long, Long>>) -> Unit) {
        var cursor = "*"
        while (true) {
            val json = select(
                listOf(
                    "q" to "*:*",
                    "fl" to "id,size_bytes,indexed_at",
                    "sort" to "id asc",
                    "rows" to pageSize.toString(),
                    "cursorMark" to cursor,
                )
            )
            val response = json.getJSONObject("response")
            val docs = response.getJSONArray("docs")
            // Each photo with the size it was indexed with and when it was written (epoch millis,
            // 0 when unknown): the second is what lets "Re-read all" carry on where it stopped.
            val page = ArrayList<Triple<String, Long, Long>>(docs.length())
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val written = runCatching { java.time.Instant.parse(d.optString("indexed_at")).toEpochMilli() }.getOrDefault(0L)
                d.optString("id").takeIf { it.isNotEmpty() }?.let { page += Triple(it, d.optLong("size_bytes", -1L), written) }
            }
            onPage(response.optLong("numFound"), page)
            val next = json.optString("nextCursorMark")
            if (docs.length() < pageSize || next.isEmpty() || next == cursor) break
            cursor = next
        }
    }

    /**
     * Walks every document in the index with the stored [fields], [pageSize] at a time, cursor
     * paging (a deep page costs the same as the first), handing each document to [onDoc].
     */
    suspend fun forEachDoc(fields: String, pageSize: Int = 1000, filters: List<Pair<String, String>> = emptyList(), onDoc: suspend (JSONObject) -> Unit) {
        var cursor = "*"
        while (true) {
            val json = select(
                listOf(
                    "q" to "*:*",
                    "fl" to fields,
                    "sort" to "id asc",
                    "rows" to pageSize.toString(),
                    "cursorMark" to cursor,
                ) + filters
            )
            val docs = json.getJSONObject("response").getJSONArray("docs")
            for (i in 0 until docs.length()) onDoc(docs.getJSONObject(i))
            val next = json.optString("nextCursorMark")
            if (docs.length() < pageSize || next.isEmpty() || next == cursor) break
            cursor = next
        }
    }

    /**
     * Groups of duplicates of one kind, in one request: a facet on the duplicate key [field]
     * (one of SearchRepository.DUPLICATE_FIELDS) keeping values held by two photos or more,
     * with the ids of each. Biggest group first. An index on a configuration without the
     * duplicate keys answers 400.
     */
    suspend fun duplicateGroups(field: String): List<List<String>> {
        val facet = JSONObject().put(
            "groups",
            JSONObject()
                .put("type", "terms")
                .put("field", field)
                .put("mincount", 2)
                .put("limit", -1)
                .put("sort", "count desc")
                .put("facet", JSONObject().put("ids", JSONObject().put("type", "terms").put("field", "id").put("limit", -1))),
        )
        val json = select(listOf("q" to "*:*", "rows" to "0", "json.facet" to facet.toString()))
        val buckets = json.optJSONObject("facets")?.optJSONObject("groups")?.optJSONArray("buckets") ?: return emptyList()
        return (0 until buckets.length()).mapNotNull { i ->
            val ids = buckets.optJSONObject(i)?.optJSONObject("ids")?.optJSONArray("buckets") ?: return@mapNotNull null
            (0 until ids.length()).mapNotNull { k -> ids.optJSONObject(k)?.optString("val")?.takeIf { it.isNotEmpty() } }
                .takeIf { it.size >= 2 }
        }
    }

    /**
     * How many photos the index wrote before [millis] (epoch), in one request with no rows: what
     * "Re-read all" still has to do, so its progress has a real total from the start.
     */
    suspend fun countWrittenBefore(millis: Long): Long {
        val before = java.time.Instant.ofEpochMilli(millis).toString()
        val json = select(listOf("q" to "*:*", "fq" to "indexed_at:[* TO $before}", "rows" to "0"))
        return json.optJSONObject("response")?.optLong("numFound") ?: 0L
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
     * Autocomplete: labels that contain what the user typed so far, best first.
     */
    suspend fun suggest(prefix: String, count: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("suggest.q", prefix)
            // The suggester returns one entry per photo carrying the label; ask for more and
            // keep the distinct labels.
            .add("suggest.count", (count.coerceIn(1, 20) * 10).toString())
            .add("wt", "json")
            .build()
        val json = JSONObject(execute(request("/suggest").post(form).build()))
        val dict = json.optJSONObject("suggest")?.optJSONObject("labels") ?: return@withContext emptyList()
        val entry = dict.keys().asSequence().firstOrNull()?.let { dict.optJSONObject(it) } ?: return@withContext emptyList()
        val array = entry.optJSONArray("suggestions") ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("term")?.takeIf { t -> t.isNotBlank() } }
            .distinctBy { it.lowercase() }.take(count)
    }

    /**
     * Ids of the photos indexed without CLIP words (the monthly AI allowance was used up when
     * they were written), to be read once the allowance is back.
     */
    suspend fun idsWithoutWords(): Set<String> = allIds(query = "*:* AND -clip_model:[* TO *]")

    /**
     * Every document in the index with the stored [fields], [pageSize] at a time, handed to
     * [onDoc] one by one. Used to rebuild the phone's cache from the index.
     */
    suspend fun allDocs(fields: String, pageSize: Int = 200, onDoc: suspend (JSONObject) -> Unit) {
        var start = 0
        while (true) {
            val json = select(
                listOf(
                    "q" to "*:*",
                    "fl" to fields,
                    "sort" to "id asc",
                    "start" to start.toString(),
                    "rows" to pageSize.toString(),
                )
            )
            val docs = json.getJSONObject("response").getJSONArray("docs")
            for (i in 0 until docs.length()) onDoc(docs.getJSONObject(i))
            if (docs.length() < pageSize) break
            start += pageSize
        }
    }

    /**
     * The configuration version the index is running, read from the /opensolr-photos-config
     * handler. 0 when the index has no such handler (a configuration from before versions
     * existed), which counts as older than anything.
     */
    suspend fun configVersion(): Int = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(execute(request("/opensolr-photos-config?wt=json").get().build()))
            json.optJSONObject("responseHeader")?.optJSONObject("params")?.optString("config_version")?.toIntOrNull() ?: 0
        } catch (e: ServiceException) {
            if (e.message?.contains("HTTP 404") == true) 0 else throw e
        }
    }

    /**
     * Empties the index: every document goes, the index itself stays. Used only by a rebuild,
     * after the documents were copied into the phone's cache.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        execute(request("/update?commit=true&wt=json").post("{\"delete\":{\"query\":\"*:*\"}}".toRequestBody(JSON)).build())
    }

    /**
     * Deletes every document that carries printed text, and says how many went.
     *
     * One delete-by-query rather than a walk over the ids: the count is asked for first, so the
     * screen can say what happened, and the delete itself is a single request whatever the
     * number. The photos themselves are untouched; the next sync finds them missing from the
     * index and reads them again.
     */
    suspend fun deleteWithOcr(): Int = withContext(Dispatchers.IO) {
        val found = select(listOf("q" to "*:*", "fq" to "ocr_t:*", "rows" to "0"))
            .optJSONObject("response")?.optInt("numFound") ?: 0
        if (found > 0) {
            execute(request("/update?commit=true&wt=json").post("{\"delete\":{\"query\":\"ocr_t:*\"}}".toRequestBody(JSON)).build())
        }
        found
    }

    /**
     * Adds or replaces [docs]. They become searchable within ten seconds.
     */
    suspend fun add(docs: JSONArray) = withContext(Dispatchers.IO) {
        execute(request("/update?commitWithin=10000&wt=json").post(docs.toString().toRequestBody(JSON)).build())
    }

    /**
     * Deletes the documents with [ids]. [now] commits on the spot instead of within ten
     * seconds: what a person just deleted must not come back on the next refresh.
     */
    suspend fun delete(ids: Collection<String>, now: Boolean = false) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("delete", JSONArray(ids)).toString()
        val query = if (now) "/update?commit=true&wt=json" else "/update?commitWithin=10000&wt=json"
        execute(request(query).post(body.toRequestBody(JSON)).build())
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
