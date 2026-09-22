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

class SolrClient(private val connection: IndexConnection, private val http: OkHttpClient = Http.client) {

    private val authorization = Credentials.basic(connection.username, connection.password, Charsets.UTF_8)

    suspend fun select(params: List<Pair<String, String>>): JSONObject = JSONObject(selectText(params))

    suspend fun selectText(params: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply {
            params.forEach { (name, value) -> add(name, value) }
            add("wt", "json")
        }.build()
        execute(request("/select").post(form).build())
    }

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

    suspend fun duplicateGroups(field: String, maxGroup: Int, maxGroups: Int, within: String? = null, base: List<Pair<String, String>> = listOf("q" to "*:*")): List<List<String>> {
        fun ids() = JSONObject().put("type", "terms").put("field", "id").put("limit", maxGroup + 1)
        val inner = within?.let {
            JSONObject()
                .put("type", "terms")
                .put("field", it)
                .put("mincount", 2)
                .put("limit", WITHIN_LIMIT)
                .put("sort", "count desc")
                .put("facet", JSONObject().put("ids", ids()))
        }
        val facet = JSONObject().put(
            "groups",
            JSONObject()
                .put("type", "terms")
                .put("field", field)
                .put("mincount", 2)
                .put("limit", maxGroups)
                .put("sort", "count desc")
                .put("facet", if (inner != null) JSONObject().put("within", inner) else JSONObject().put("ids", ids())),
        )
        val json = select(base + listOf("rows" to "0", "json.facet" to facet.toString()))
        val buckets = json.optJSONObject("facets")?.optJSONObject("groups")?.optJSONArray("buckets") ?: return emptyList()

        fun groupOf(bucket: JSONObject?): List<String>? {
            if (bucket == null) return null

            if (bucket.optLong("count") > maxGroup) return null
            val ids = bucket.optJSONObject("ids")?.optJSONArray("buckets") ?: return null
            return (0 until ids.length()).mapNotNull { k -> ids.optJSONObject(k)?.optString("val")?.takeIf { it.isNotEmpty() } }
                .takeIf { it.size >= 2 }
        }
        val out = ArrayList<List<String>>(buckets.length())
        for (i in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(i) ?: continue
            if (within == null) {
                groupOf(bucket)?.let { out += it }
                continue
            }
            val split = bucket.optJSONObject("within")?.optJSONArray("buckets") ?: continue
            for (k in 0 until split.length()) groupOf(split.optJSONObject(k))?.let { out += it }
        }

        return out.sortedByDescending { it.size }
    }

    suspend fun countWrittenBefore(millis: Long): Long {
        val before = java.time.Instant.ofEpochMilli(millis).toString()
        val json = select(listOf("q" to "*:*", "fq" to "indexed_at:[* TO $before}", "rows" to "0"))
        return json.optJSONObject("response")?.optLong("numFound") ?: 0L
    }

    suspend fun count(): Long =
        select(listOf("q" to "*:*", "rows" to "0")).getJSONObject("response").optLong("numFound")

    suspend fun hasPhotoSchema(): Boolean = try {
        select(listOf("q" to "*:*", "rows" to "0", "fq" to "meaning:[* TO *]"))
        true
    } catch (e: ServiceException) {
        if (e.message?.contains("HTTP 400") == true) false else throw e
    }

    suspend fun suggest(prefix: String, count: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("suggest.q", prefix)

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

    suspend fun idsWithoutWords(): Set<String> = allIds(query = "*:* AND -clip_model:[* TO *]")

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

    suspend fun configVersion(): Int = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(execute(request("/opensolr-photos-config?wt=json").get().build()))
            json.optJSONObject("responseHeader")?.optJSONObject("params")?.optString("config_version")?.toIntOrNull() ?: 0
        } catch (e: ServiceException) {
            if (e.message?.contains("HTTP 404") == true) 0 else throw e
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        execute(request("/update?commit=true&wt=json").post("{\"delete\":{\"query\":\"*:*\"}}".toRequestBody(JSON)).build())
    }

    suspend fun deleteWithOcr(): Int = withContext(Dispatchers.IO) {
        val found = select(listOf("q" to "*:*", "fq" to "ocr_t:*", "rows" to "0"))
            .optJSONObject("response")?.optInt("numFound") ?: 0
        if (found > 0) {
            execute(request("/update?commit=true&wt=json").post("{\"delete\":{\"query\":\"ocr_t:*\"}}".toRequestBody(JSON)).build())
        }
        found
    }

    suspend fun add(docs: JSONArray) = withContext(Dispatchers.IO) {
        execute(request("/update?commitWithin=10000&wt=json").post(docs.toString().toRequestBody(JSON)).build())
    }

    suspend fun delete(ids: Collection<String>, now: Boolean = false) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("delete", JSONArray(ids)).toString()
        val query = if (now) "/update?commit=true&wt=json" else "/update?commitWithin=10000&wt=json"
        execute(request(query).post(body.toRequestBody(JSON)).build())
    }

    suspend fun commit() = withContext(Dispatchers.IO) {
        execute(request("/update?commit=true&wt=json").post("{\"commit\":{}}".toRequestBody(JSON)).build())
    }

    private fun request(path: String): Request.Builder =
        Request.Builder().url(connection.baseUrl.trimEnd('/') + path).header("Authorization", authorization)

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

        private const val WITHIN_LIMIT = 50
    }
}
