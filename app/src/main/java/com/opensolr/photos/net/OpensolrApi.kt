package com.opensolr.photos.net

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.util.Base64
import com.opensolr.photos.auth.AuthFlow
import com.opensolr.photos.data.AccountLimits
import com.opensolr.photos.data.IndexConnection
import com.opensolr.photos.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ClipResult(val text: String, val labels: List<String>, val model: String)

data class IngestItem(val photo: com.opensolr.photos.media.LocalPhoto, val jpeg: ByteArray, val tags: List<String>?, val meaning: String?, val fileHash: String? = null, val persons: List<String> = emptyList())

data class WordsItem(
    val id: String,
    val tags: List<String>?,
    val persons: List<String>?,
    val meaning: String?,

    val fileHash: String? = null,

    val location: Pair<Double, Double>? = null,
)

data class IngestResult(val words: Boolean, val place: Boolean, val doc: JSONObject? = null)

data class ImageReading(val clip: ClipResult, val vector: FloatArray, val embedModel: String)

data class PlaceInfo(
    val city: String,
    val region: String?,
    val province: String?,
    val community: String?,
    val country: String?,
    val countryCode: String,
)

data class PlaceHit(
    val name: String,

    val province: String?,
    val region: String?,
    val country: String?,
    val countryCode: String,
    val lat: Double,
    val lon: Double,
    val kind: String,

    val population: Int = 0,
) {

    val label: String get() = listOfNotNull(name, province, region, country).distinct().joinToString(", ")

    companion object {

        fun listToJson(hits: List<PlaceHit>): String = org.json.JSONArray().apply {
            hits.forEach { h ->
                val o = JSONObject()
                    .put("place", h.name)
                    .put("country_code", h.countryCode)
                    .put("lat", h.lat)
                    .put("lon", h.lon)
                    .put("kind", h.kind)

                h.province?.let { o.put("province", it) }
                h.region?.let { o.put("region", it) }
                h.country?.let { o.put("country", it) }
                if (h.population > 0) o.put("population", h.population)
                put(o)
            }
        }.toString()

        fun listFromJson(text: String): List<PlaceHit> {
            val array = org.json.JSONArray(text)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("place")
                if (name.isBlank()) return@mapNotNull null
                PlaceHit(
                    name = name,
                    province = o.optString("province").ifBlank { null },
                    region = o.optString("region").ifBlank { null },
                    country = o.optString("country").ifBlank { null },
                    countryCode = o.optString("country_code"),
                    lat = o.optDouble("lat", Double.NaN),
                    lon = o.optDouble("lon", Double.NaN),
                    kind = o.optString("kind").ifBlank { "city" },
                    population = o.optInt("population", 0),
                ).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
            }
        }
    }
}

data class AccountIndex(val name: String, val deviceName: String?, val deviceId: String?, val numDocs: Int, val lastIndex: Long, val created: Long) {

    val isPhotos: Boolean get() = Regex("^photos_[a-f0-9]{1,32}__dense$").matches(name)
}

data class VectorRegion(val environment: String, val country: String, val solrVersion: String)

class OpensolrApi(private val http: OkHttpClient = Http.client) {

    suspend fun exchangeCode(code: String, verifier: String): Pair<Session, AccountLimits> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", verifier)
            .put("client_id", AuthFlow.CLIENT_ID)
            .put("redirect_uri", AuthFlow.REDIRECT_URI)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder().url("$SITE/app/token").post(body).build()
        http.newCall(request).execute().use { response ->
            val json = parseObject(response.body?.string().orEmpty())
            if (response.code == 429) throw RateLimitedException(60)
            if (!json.optBoolean("status")) throw SignInFailedException(json.optString("error", "HTTP ${response.code}"))
            val email = json.optString("email")
            val key = json.optString("api_key")
            if (email.isBlank() || key.isBlank()) throw SignInFailedException("empty answer")
            Session(email, key) to AccountLimits.fromJson(json.optJSONObject("account") ?: JSONObject())
        }
    }

    suspend fun indexNames(session: Session): List<String> = indexes(session).map { it.name }

    suspend fun indexes(session: Session): List<AccountIndex> = withContext(Dispatchers.IO) {
        val text = post(MANAGEMENT + "get_index_list", form(session))
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) throw ServiceException(platformMessage(trimmed))
        val array = JSONArray(trimmed)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("index_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AccountIndex(
                name = name,
                deviceName = o.optString("device_name").ifBlank { null },
                deviceId = o.optString("device_id").ifBlank { null },
                numDocs = o.optInt("num_docs"),
                lastIndex = o.optLong("last_index"),
                created = o.optLong("created"),
            )
        }
    }

    suspend fun vectorRegions(session: Session): List<VectorRegion> = withContext(Dispatchers.IO) {
        val trimmed = post(MANAGEMENT + "vector_regions", form(session)).trim()
        if (!trimmed.startsWith("[")) throw ServiceException(platformMessage(trimmed))
        val array = JSONArray(trimmed)
        (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.let { row ->
                VectorRegion(row.optString("environment"), row.optString("country"), row.optString("solr_version"))
            }
        }.filter { it.environment.isNotBlank() }
    }

    suspend fun createIndex(session: Session, name: String, environment: String, deviceName: String = "", deviceId: String = "") = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "create_index").toHttpUrl().newBuilder()
            .addQueryParameter("core_name", name)
            .addQueryParameter("region", environment)
            .build()

        val json = parseObject(post(url.toString(), form(session) {
            add("core_name", name)
            if (deviceName.isNotBlank()) add("device_name", deviceName.take(120))
            if (deviceId.isNotBlank()) add("device_id", deviceId.take(40))
        }))
        if (!json.optBoolean("status")) {
            val msg = json.optString("msg")
            when {
                msg.startsWith("ERROR_CANNOT_ADD_MORE_THAN_") -> throw IndexLimitException(
                    AppText.s(R.string.err_no_room)
                )
                msg == "ERROR_CORE_NAME_TAKEN_CHOOSE_ANOTHER_CORE_NAME" -> throw ServiceException(
                    AppText.s(R.string.err_name_taken, name)
                )
                else -> throw ServiceException(platformMessage(json.toString()))
            }
        }
    }

    suspend fun uploadConfig(session: Session, name: String, zip: ByteArray) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("email", session.email)
            .addFormDataPart("api_key", session.apiKey)
            .addFormDataPart("core_name", name)
            .addFormDataPart("userfile", "opensolr-photos-conf.zip", zip.toRequestBody("application/zip".toMediaType()))
            .build()
        val text = execute(Request.Builder().url(MANAGEMENT + "upload_zip_config_files").post(body).build())
        val start = text.indexOf('{')
        val json = if (start >= 0) parseObject(text.substring(start)) else JSONObject()
        if (!json.optBoolean("status")) throw ServiceException(AppText.s(R.string.err_config_upload, platformMessage(text)))
    }

    suspend fun connection(session: Session, name: String): IndexConnection = withContext(Dispatchers.IO) {
        val json = parseObject(post(MANAGEMENT + "get_core_info", form(session) { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            if (json.optString("msg") == "NOT_OWNER_ERROR") throw IndexMissingException()
            throw ServiceException(platformMessage(json.toString()))
        }
        val info = json.optJSONObject("msg")?.optJSONObject("info") ?: throw ServiceException("get_core_info returned no connection details")
        val url = info.optString("connection_url")
        if (!url.startsWith("https://")) throw ServiceException("The index does not offer an HTTPS address")
        IndexConnection(
            indexName = name,
            baseUrl = url,
            username = info.optString("auth_username"),
            password = info.optString("auth_password"),
            environment = info.optString("environment_identifier"),
        )
    }

    suspend fun accountSummary(session: Session, name: String, previous: AccountLimits?): AccountLimits = withContext(Dispatchers.IO) {
        val signature = hmacSha256Hex(session.apiKey, name + session.email)
        val json = parseObject(post(MANAGEMENT + "get_account_summary", form(session) {
            add("core_name", name)
            add("signature", signature)
        }))
        if (!json.optBoolean("status")) throw ServiceException(platformMessage(json.toString()))
        AccountLimits.fromJson(json.optJSONObject("msg") ?: JSONObject(), previous)
    }

    suspend fun nearbyPlaces(session: Session, coords: List<String>): Map<String, PlaceInfo?> = withContext(Dispatchers.IO) {
        if (coords.isEmpty()) return@withContext emptyMap()
        val json = parseObject(post(MANAGEMENT + "nearby_places", form(session) {
            add("coords", coords.take(50).joinToString(";") + if (coords.size == 1) ";" else "")
            add("within", "25")
        }))
        if (!json.optBoolean("status")) throw ServiceException(platformMessage(json.toString()))
        val results = json.optJSONObject("results") ?: throw ServiceException("nearby_places returned no results")
        val out = HashMap<String, PlaceInfo?>()
        results.keys().forEach { key ->
            val entry = results.optJSONObject(key) ?: return@forEach
            if (!entry.optBoolean("status")) {
                if (entry.optString("msg") == "ERROR_GEO_NO_DATA") out[key] = null
                return@forEach
            }
            val nearest = entry.optJSONObject("nearest") ?: run { out[key] = null; return@forEach }
            val city = nearest.optString("city").ifBlank { nearest.optString("place") }
            out[key] = if (city.isBlank()) null else PlaceInfo(
                city = city,
                region = nearest.optString("region").ifBlank { null },
                province = nearest.optString("province").ifBlank { null },
                community = nearest.optString("community").ifBlank { null },
                country = nearest.optString("country").ifBlank { null },
                countryCode = nearest.optString("country_code"),
            )
        }
        out
    }

    suspend fun searchPlaces(session: Session, query: String, limit: Int = 10): List<PlaceHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val json = parseObject(post(MANAGEMENT + "place_search", form(session) {
            add("q", query.trim())
            add("limit", limit.toString())
        }))
        if (!json.optBoolean("status")) return@withContext emptyList()
        val places = json.optJSONArray("places") ?: return@withContext emptyList()
        (0 until places.length()).mapNotNull { i ->
            val o = places.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("place")
            if (name.isBlank()) return@mapNotNull null
            PlaceHit(
                name = name,
                province = o.optString("province").ifBlank { null },
                region = o.optString("region").ifBlank { null },
                country = o.optString("country").ifBlank { null },
                countryCode = o.optString("country_code"),
                lat = o.optDouble("lat", Double.NaN),
                lon = o.optDouble("lon", Double.NaN),
                kind = o.optString("kind").ifBlank { "city" },
                population = o.optInt("population", 0),
            ).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
        }
    }

    suspend fun imageClip(session: Session, name: String, jpeg: ByteArray): ClipResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("top_k", 12)
            .put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val request = Request.Builder().url(AI + "image_clip").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code in 400..499) throw PhotoRejectedException(AppText.s(R.string.err_photo_refused, platformMessage(text)))
            if (response.code >= 500) throw ServiceException("The Opensolr AI service answered HTTP ${response.code}")
            val json = parseObject(text)
            if (!json.optBoolean("status")) throw ServiceException(platformMessage(text))
            val array = json.optJSONArray("labels") ?: JSONArray()
            val labels = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("label")?.takeIf { l -> l.isNotBlank() } }
            ClipResult(json.optString("text"), labels, json.optString("model"))
        }
    }

    suspend fun imageIndex(session: Session, name: String, jpegs: List<ByteArray>): List<ImageReading?> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("top_k", 12)
            .put("images", JSONArray(jpegs.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
        val request = Request.Builder().url(AI + "image_index").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code in 400..499) throw PhotoRejectedException(AppText.s(R.string.err_photos_refused, platformMessage(text)))
            if (response.code >= 500) throw ServiceException("The Opensolr AI service answered HTTP ${response.code}")
            val json = parseObject(text)
            if (!json.optBoolean("status")) throw ServiceException(platformMessage(text))
            val results = json.optJSONArray("results") ?: throw ServiceException("The Opensolr AI service answered without results")
            if (results.length() != jpegs.size) throw ServiceException("The Opensolr AI service answered ${results.length()} readings for ${jpegs.size} photos")
            (0 until results.length()).map { i ->
                val item = results.optJSONObject(i) ?: return@map null
                if (!item.optBoolean("status")) return@map null
                val vector = item.optJSONArray("embedding")?.takeIf { it.length() > 0 } ?: return@map null
                val array = item.optJSONArray("labels") ?: JSONArray()
                val labels = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("label")?.takeIf { l -> l.isNotBlank() } }
                ImageReading(ClipResult(item.optString("text"), labels, item.optString("model")), toFloats(vector), item.optString("embed_model"))
            }
        }
    }

    suspend fun photosIngest(session: Session, name: String, items: List<IngestItem>): List<IngestResult?> = withContext(Dispatchers.IO) {
        val photos = JSONArray()
        items.forEach { item ->
            val p = item.photo
            photos.put(JSONObject().apply {
                put("id", p.id)
                put("path", p.absolutePath)
                put("folder", p.folder)
                put("file_name", p.fileName)
                put("media_id", p.mediaId)
                put("mime", p.mime)
                put("size_bytes", p.sizeBytes)

                item.fileHash?.let { put("file_hash", it) }

                if (item.persons.isNotEmpty()) put("persons", JSONArray(item.persons))
                if (p.modifiedSec > 0) put("modified_at", isoUtc(p.modifiedSec * 1000L))
                put("width", p.width)
                put("height", p.height)
                put("image", Base64.encodeToString(item.jpeg, Base64.NO_WRAP))

                item.tags?.let { put("tags", JSONArray(it)) }
                item.meaning?.let { put("meaning", it) }
            })
        }
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("top_k", 12)
            .put("photos", photos)
        val request = Request.Builder().url(AI + "photos_ingest").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code in 400..499) throw PhotoRejectedException(AppText.s(R.string.err_photos_refused, platformMessage(text)))
            if (response.code >= 500) throw ServiceException("The Opensolr AI service answered HTTP ${response.code}")
            val json = parseObject(text)
            if (!json.optBoolean("status")) throw ServiceException(platformMessage(text))
            val results = json.optJSONArray("results") ?: throw ServiceException("The Opensolr AI service answered without results")
            if (results.length() != items.size) throw ServiceException("The Opensolr AI service answered ${results.length()} results for ${items.size} photos")
            (0 until results.length()).map { i ->
                val item = results.optJSONObject(i) ?: return@map null
                if (!item.optBoolean("status")) return@map null
                IngestResult(item.optBoolean("words"), item.optBoolean("place"), item)
            }
        }
    }

    suspend fun photosWords(session: Session, name: String, items: List<WordsItem>): List<JSONObject?> = withContext(Dispatchers.IO) {
        val photos = JSONArray()
        items.forEach { item ->
            photos.put(JSONObject().apply {
                put("id", item.id)
                item.tags?.let { put("tags", JSONArray(it)) }
                item.persons?.let { put("persons", JSONArray(it)) }
                item.meaning?.let { put("meaning", it) }
                item.fileHash?.let { put("file_hash", it) }
                item.location?.let { (lat, lon) -> put("location", JSONObject().put("lat", lat).put("lon", lon)) }
            })
        }
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("photos", photos)
        val request = Request.Builder().url(AI + "photos_words").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code in 400..499) throw PhotoRejectedException(AppText.s(R.string.err_words_refused, platformMessage(text)))
            if (response.code >= 500) throw ServiceException("The Opensolr AI service answered HTTP ${response.code}")
            val json = parseObject(text)
            if (!json.optBoolean("status")) throw ServiceException(platformMessage(text))
            val results = json.optJSONArray("results") ?: throw ServiceException("The Opensolr AI service answered without results")
            val byId = HashMap<String, JSONObject>()
            (0 until results.length()).forEach { i ->
                val r = results.optJSONObject(i) ?: return@forEach
                if (r.optBoolean("status")) byId[r.optString("id")] = r
            }
            items.map { byId[it.id] }
        }
    }

    suspend fun batchEmbed(session: Session, name: String, texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("payloads", JSONArray(texts))
        val text = execute(Request.Builder().url(AI + "batch_embed").post(body.toString().toRequestBody(JSON)).build())
        val json = parseObject(text)
        if (json.optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        val vectors = json.optJSONArray("embeddings") ?: throw ServiceException(json.optString("error").ifBlank { platformMessage(text) })
        if (vectors.length() != texts.size) throw ServiceException("The embedding service returned ${vectors.length()} vectors for ${texts.size} photos")
        (0 until vectors.length()).map { toFloats(vectors.getJSONArray(it)) }
    }

    suspend fun embedQuery(session: Session, name: String, query: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", session.email)
            .put("api_key", session.apiKey)
            .put("index_name", name)
            .put("is_query", "1")
            .put("payload", query)
        val text = execute(Request.Builder().url(AI + "embed").post(body.toString().toRequestBody(JSON)).build()).trim()
        if (text.startsWith("[")) return@withContext toFloats(JSONArray(text))
        val json = parseObject(text)
        if (json.optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        throw ServiceException(platformMessage(text))
    }

    private fun post(url: String, body: FormBody): String =
        execute(Request.Builder().url(url).post(body).build())

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code >= 500) throw ServiceException("Opensolr answered HTTP ${response.code}")
            text
        }

    private fun classify(code: Int, text: String, retryAfter: String?) {
        if (text.contains("ERROR_AUTHENTICATION_FAILED")) throw SignInRequiredException()
        if (text.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
        if (code == 429) {
            if (text.contains("ERROR_AI_MONTHLY_QUOTA_EXCEEDED")) {
                throw QuotaExceededException(runCatching { JSONObject(text).optString("resets_at") }.getOrNull())
            }
            throw RateLimitedException(retryAfter?.toLongOrNull() ?: 60L)
        }
    }

    private fun form(session: Session, extra: FormBody.Builder.() -> Unit = {}): FormBody =
        FormBody.Builder().add("email", session.email).add("api_key", session.apiKey).apply(extra).build()

    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text.trim())
    } catch (e: Exception) {
        JSONObject()
    }

    private fun platformMessage(text: String): String {
        val json = parseObject(text)
        val msg = json.opt("msg")
        return when (msg) {
            is String -> msg
            is JSONObject -> msg.optString("ERROR", msg.toString())
            else -> text.take(160).ifBlank { "empty answer" }
        }
    }

    private fun toFloats(array: JSONArray): FloatArray = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private fun isoUtc(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(millis)

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SITE = "https://opensolr.com"
        const val MANAGEMENT = "https://opensolr.com/solr_manager/api/"
        const val AI = "https://api.opensolr.com/solr_manager/api/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
