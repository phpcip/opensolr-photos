package com.opensolr.photos.net

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

/**
 * One CLIP reading of a photo.
 *
 * @property text   the labels joined with ", " (the photo's "meaning")
 * @property labels the labels, best first
 * @property model  the CLIP checkpoint that produced them
 */
data class ClipResult(val text: String, val labels: List<String>, val model: String)

/**
 * An Opensolr environment that runs vector search.
 */
data class VectorRegion(val environment: String, val country: String, val solrVersion: String)

/**
 * The Opensolr REST API calls the app makes.
 *
 * Two hosts, as the platform splits them: opensolr.com for account and index management, and
 * api.opensolr.com for the AI endpoints (CLIP and embeddings). Credentials always travel in the
 * request body over HTTPS, never in a URL, so they cannot end up in an access log line.
 */
class OpensolrApi(private val http: OkHttpClient = Http.client) {

    /**
     * Swaps the one-time sign-in [code] and its PKCE [verifier] for the account email, the API
     * key and the plan limits.
     */
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

    /**
     * Names of every index in the account.
     */
    suspend fun indexNames(session: Session): List<String> = withContext(Dispatchers.IO) {
        val text = post(MANAGEMENT + "get_index_list", form(session))
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) throw ServiceException(platformMessage(trimmed))
        val array = JSONArray(trimmed)
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("index_name")?.takeIf { name -> name.isNotBlank() } }
    }

    /**
     * Environments that run vector search.
     */
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

    /**
     * Creates the index [name] in [environment].
     */
    suspend fun createIndex(session: Session, name: String, environment: String) = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "create_index").toHttpUrl().newBuilder()
            .addQueryParameter("core_name", name)
            .addQueryParameter("region", environment)
            .build()
        val json = parseObject(post(url.toString(), form(session) { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            val msg = json.optString("msg")
            when {
                msg.startsWith("ERROR_CANNOT_ADD_MORE_THAN_") -> throw IndexLimitException(
                    "Your Opensolr plan cannot hold another index. Remove an index you no longer use, or upgrade at opensolr.com/pricing."
                )
                msg == "ERROR_CORE_NAME_TAKEN_CHOOSE_ANOTHER_CORE_NAME" -> throw ServiceException(
                    "An index named $name already exists in another Opensolr account."
                )
                else -> throw ServiceException(platformMessage(json.toString()))
            }
        }
    }

    /**
     * Uploads the app's Solr configuration (schema.xml, solrconfig.xml and the analyzer files,
     * bundled as a zip) to the index [name]. The platform reloads the index afterwards.
     */
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
        if (!json.optBoolean("status")) throw ServiceException("The index configuration could not be uploaded: " + platformMessage(text))
    }

    /**
     * Address and HTTP credentials of the index [name].
     */
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

    /**
     * Plan limits and current usage of the index [name], merged over [previous].
     */
    suspend fun accountSummary(session: Session, name: String, previous: AccountLimits?): AccountLimits = withContext(Dispatchers.IO) {
        val signature = hmacSha256Hex(session.apiKey, name + session.email)
        val json = parseObject(post(MANAGEMENT + "get_account_summary", form(session) {
            add("core_name", name)
            add("signature", signature)
        }))
        if (!json.optBoolean("status")) throw ServiceException(platformMessage(json.toString()))
        AccountLimits.fromJson(json.optJSONObject("msg") ?: JSONObject(), previous)
    }

    /**
     * Reads a JPEG into words with CLIP (the image_clip endpoint: no OCR, no barcodes).
     */
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
            if (response.code in 400..499) throw PhotoRejectedException("The photo was refused: " + platformMessage(text))
            if (response.code >= 500) throw ServiceException("The Opensolr AI service answered HTTP ${response.code}")
            val json = parseObject(text)
            if (!json.optBoolean("status")) throw ServiceException(platformMessage(text))
            val array = json.optJSONArray("labels") ?: JSONArray()
            val labels = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("label")?.takeIf { l -> l.isNotBlank() } }
            ClipResult(json.optString("text"), labels, json.optString("model"))
        }
    }

    /**
     * Search vectors for up to 50 texts at once, in the same order.
     */
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

    /**
     * The search vector of a query typed by the user.
     */
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

    /**
     * POSTs a form and returns the body, raising the typed errors.
     */
    private fun post(url: String, body: FormBody): String =
        execute(Request.Builder().url(url).post(body).build())

    /**
     * Runs a request and returns its body, raising the typed errors every endpoint shares.
     */
    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            classify(response.code, text, response.header("Retry-After"))
            if (response.code >= 500) throw ServiceException("Opensolr answered HTTP ${response.code}")
            text
        }

    /**
     * Turns the platform's shared refusals into exceptions: a bad API key, the monthly AI quota,
     * the per-minute and per-hour rate limits, and a plan without vector search.
     */
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

    /**
     * A form carrying the credentials, plus whatever [extra] adds.
     */
    private fun form(session: Session, extra: FormBody.Builder.() -> Unit = {}): FormBody =
        FormBody.Builder().add("email", session.email).add("api_key", session.apiKey).apply(extra).build()

    /**
     * Parses a JSON object, or returns an empty one for anything else.
     */
    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text.trim())
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * The `msg` of a platform answer, or a short excerpt when there is none.
     */
    private fun platformMessage(text: String): String {
        val json = parseObject(text)
        val msg = json.opt("msg")
        return when (msg) {
            is String -> msg
            is JSONObject -> msg.optString("ERROR", msg.toString())
            else -> text.take(160).ifBlank { "empty answer" }
        }
    }

    /**
     * JSON number array to floats.
     */
    private fun toFloats(array: JSONArray): FloatArray = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    /**
     * Lower-case hex HMAC-SHA256, as get_account_summary expects.
     */
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
