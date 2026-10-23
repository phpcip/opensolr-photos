package com.opensolr.photos.storage

import com.opensolr.photos.data.StorageConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Any S3 bucket (AWS, Backblaze B2, Cloudflare R2, Wasabi, Hetzner, MinIO) over Signature V4, path-style,
 * with plain OkHttp: endpoint (blank = AWS for the region), region, bucket, access key, secret key, prefix.
 */
class S3Backend(private val config: StorageConfig) : Backend {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val region = config.field("region").ifBlank { "us-east-1" }
    private val bucket = config.field("bucket")
    private val accessKey = config.field("access_key")
    private val secretKey = config.field("secret_key")
    private val base: HttpUrl by lazy {
        val endpoint = config.field("endpoint").ifBlank { "https://s3.$region.amazonaws.com" }
        (endpoint.trimEnd('/')).toHttpUrlOrNull() ?: throw BackendException("Bad endpoint")
    }

    override fun connect() {
        if (bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) throw BackendException("Bucket, access key and secret key are needed")
        if (!Regex("^[a-z0-9.-]{3,63}$").matches(bucket)) throw BackendException("Bad bucket name")
        // one listing proves the endpoint, the bucket and the keys
        list("")
    }

    override fun mkdirs(path: String) {}

    override fun put(path: String, size: Long, source: () -> InputStream) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                source().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                }
            }
        }
        send("PUT", path, body = body, payloadHash = UNSIGNED).use { r ->
            if (!r.isSuccessful) throw BackendException("PUT ${r.code}: ${reason(r)}")
        }
    }

    override fun get(path: String, sink: OutputStream): Long = send("GET", path).use { r ->
        if (r.code == 404) throw NotFoundException(path)
        if (!r.isSuccessful) throw BackendException("GET ${r.code}: ${reason(r)}")
        r.body?.byteStream()?.copyTo(sink, BUFFER) ?: 0L
    }

    override fun stat(path: String): Long? = send("HEAD", path).use { r ->
        when {
            r.code == 404 -> null
            !r.isSuccessful -> throw BackendException("HEAD ${r.code}")
            else -> r.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else path.trimEnd('/') + "/"
        val out = ArrayList<String>()
        listObjects(prefix, "/") { key, isPrefix ->
            val name = key.removePrefix(prefix).trimEnd('/')
            if (name.isNotEmpty()) out += name
        }
        return out
    }

    override fun delete(path: String) {
        send("DELETE", path).use { r ->
            if (!r.isSuccessful && r.code != 404) throw BackendException("DELETE ${r.code}: ${reason(r)}")
        }
    }

    override fun deleteTree(path: String) {
        val prefix = path.trimEnd('/') + "/"
        val keys = ArrayList<String>()
        listObjects(prefix, null) { key, isPrefix -> if (!isPrefix) keys += key }
        keys.forEach { delete(it) }
    }

    override fun close() {}

    /** Every object (or, with [delimiter], every object and common prefix) under [prefix], page by page. */
    private fun listObjects(prefix: String, delimiter: String?, each: (key: String, isPrefix: Boolean) -> Unit) {
        var token: String? = null
        do {
            val query = LinkedHashMap<String, String>()
            query["list-type"] = "2"
            query["max-keys"] = "1000"
            if (prefix.isNotEmpty()) query["prefix"] = prefix
            if (delimiter != null) query["delimiter"] = delimiter
            if (token != null) query["continuation-token"] = token
            val xml = send("GET", "", query = query).use { r ->
                if (!r.isSuccessful) throw BackendException("LIST ${r.code}: ${reason(r)}")
                r.body?.string().orEmpty()
            }
            token = null
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            var tag = ""
            var inPrefixes = false
            var truncated = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "CommonPrefixes") inPrefixes = true
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "CommonPrefixes") inPrefixes = false
                        tag = ""
                    }
                    XmlPullParser.TEXT -> when (tag) {
                        "Key" -> each(parser.text, false)
                        "Prefix" -> if (inPrefixes) each(parser.text, true)
                        "IsTruncated" -> truncated = parser.text.trim() == "true"
                        "NextContinuationToken" -> token = parser.text.trim()
                    }
                }
                event = parser.next()
            }
            if (!truncated) token = null
        } while (token != null)
    }

    private fun send(method: String, key: String, query: Map<String, String> = emptyMap(), body: RequestBody? = null, payloadHash: String = EMPTY_SHA256): okhttp3.Response {
        // the path goes out encoded exactly as it is signed, so what the bucket sees is what was signed
        val segments = (listOf(bucket) + key.split('/').filter { it.isNotEmpty() }).map { uriEncode(it, true) }
        val url = base.newBuilder().apply {
            segments.forEach { addEncodedPathSegment(it) }
            query.forEach { (k, v) -> addEncodedQueryParameter(uriEncode(k, true), uriEncode(v, true)) }
        }.build()
        val now = java.util.Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val date = amzDate.substring(0, 8)
        val hostHeader = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        val canonicalUri = "/" + segments.joinToString("/")
        val canonicalQuery = query.entries.map { uriEncode(it.key, true) to uriEncode(it.value, true) }.sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
        val canonicalHeaders = "host:$hostHeader\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$date/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" + hex(sha256(canonical.toByteArray()))
        var k = hmac("AWS4$secretKey".toByteArray(), date)
        k = hmac(k, region)
        k = hmac(k, "s3")
        k = hmac(k, "aws4_request")
        val signature = hex(hmac(k, toSign))
        val request = Request.Builder().url(url)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            .method(method, body)
            .build()
        return try {
            http.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw BackendException(e.message ?: "Network error", e)
        }
    }

    private fun reason(r: okhttp3.Response): String =
        runCatching { r.body?.string().orEmpty() }.getOrDefault("").let { body ->
            Regex("<Message>(.*?)</Message>").find(body)?.groupValues?.get(1) ?: Regex("<Code>(.*?)</Code>").find(body)?.groupValues?.get(1) ?: r.message
        }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private const val BUFFER = 64 * 1024
        private const val UNSIGNED = "UNSIGNED-PAYLOAD"
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** AWS's own encoding: unreserved characters kept, everything else %XX, the slash kept in paths. */
        private fun uriEncode(raw: String, encodeSlash: Boolean): String {
            val out = StringBuilder()
            for (b in raw.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (ch == '/' && !encodeSlash)) out.append(ch)
                else out.append('%').append("%02X".format(c))
            }
            return out.toString()
        }
    }
}
