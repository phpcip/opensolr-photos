package com.opensolr.photos.storage

import com.opensolr.photos.data.StorageConfig
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A WebDAV folder (Nextcloud, ownCloud, a NAS): the folder's URL, user and password. Paths are full URLs. */
class WebDavBackend(private val config: StorageConfig) : Backend {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val root = config.field("url").trimEnd('/')
    private val auth: String? = config.field("user").takeIf { it.isNotEmpty() }?.let { Credentials.basic(it, config.field("password")) }

    override fun connect() {
        if (root.toHttpUrlOrNull() == null || !root.startsWith("https://")) throw BackendException("The URL must start with https://")
        // the folder itself must answer, with the user and password given
        request("PROPFIND", "$root/", header = "Depth" to "0", body = PROPFIND_BODY.toRequestBody(XML)).use { r ->
            if (r.code == 401 || r.code == 403) throw BackendException("The user or password is not accepted")
            if (r.code == 404) throw BackendException("The folder does not exist")
            if (!r.isSuccessful) throw BackendException("PROPFIND ${r.code}")
        }
    }

    override fun mkdirs(path: String) {
        val below = path.trimEnd('/').removePrefix(root).trim('/')
        if (below.isEmpty()) return
        var current = root
        for (part in below.split('/')) {
            current = "$current/$part"
            request("MKCOL", "$current/").use { r ->
                // 405: already there
                if (!r.isSuccessful && r.code != 405) throw BackendException("MKCOL ${r.code}")
            }
        }
    }

    override fun put(path: String, size: Long, source: () -> InputStream) {
        mkdirs(path.substringBeforeLast('/'))
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
        request("PUT", path, body = body).use { r ->
            if (!r.isSuccessful) throw BackendException("PUT ${r.code}")
        }
    }

    override fun get(path: String, sink: OutputStream): Long = request("GET", path).use { r ->
        if (r.code == 404) throw NotFoundException(path)
        if (!r.isSuccessful) throw BackendException("GET ${r.code}")
        r.body?.byteStream()?.copyTo(sink, BUFFER) ?: 0L
    }

    override fun stat(path: String): Long? = request("HEAD", path).use { r ->
        when {
            r.code == 404 -> null
            !r.isSuccessful -> throw BackendException("HEAD ${r.code}")
            else -> r.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    override fun list(path: String): List<String> {
        val dir = path.trimEnd('/') + "/"
        val xml = request("PROPFIND", dir, header = "Depth" to "1", body = PROPFIND_BODY.toRequestBody(XML)).use { r ->
            if (r.code == 404) return emptyList()
            if (!r.isSuccessful) throw BackendException("PROPFIND ${r.code}")
            r.body?.string().orEmpty()
        }
        val self = dir.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()
        val out = ArrayList<String>()
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var inHref = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> inHref = parser.name.equals("href", ignoreCase = true)
                XmlPullParser.END_TAG -> inHref = false
                XmlPullParser.TEXT -> if (inHref) {
                    val href = parser.text.trim()
                    val decodedPath = runCatching { java.net.URI(href).path ?: href }.getOrDefault(href).trimEnd('/')
                    val selfDecoded = runCatching { java.net.URLDecoder.decode(self, "UTF-8") }.getOrDefault(self)
                    if (decodedPath != selfDecoded && decodedPath.startsWith("$selfDecoded/")) {
                        val name = decodedPath.removePrefix("$selfDecoded/")
                        if (name.isNotEmpty() && !name.contains('/')) out += name
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    override fun delete(path: String) {
        request("DELETE", path).use { r ->
            if (!r.isSuccessful && r.code != 404) throw BackendException("DELETE ${r.code}")
        }
    }

    override fun deleteTree(path: String) {
        request("DELETE", path.trimEnd('/') + "/").use { r ->
            if (!r.isSuccessful && r.code != 404) throw BackendException("DELETE ${r.code}")
        }
    }

    override fun close() {}

    private fun request(method: String, url: String, header: Pair<String, String>? = null, body: RequestBody? = null): okhttp3.Response {
        val target = encodeUrl(url).toHttpUrlOrNull() ?: throw BackendException("Bad URL")
        val request = Request.Builder().url(target).apply {
            auth?.let { header("Authorization", it) }
            header?.let { header(it.first, it.second) }
            method(method, body)
        }.build()
        return try {
            http.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw BackendException(e.message ?: "Network error", e)
        }
    }

    /** Spaces and other characters a file name may carry, percent-encoded; the root is kept as typed. */
    private fun encodeUrl(url: String): String {
        if (!url.startsWith(root)) return url
        val rest = url.removePrefix(root)
        val encoded = rest.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%7E", "~")
        }
        return root + encoded
    }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val BUFFER = 64 * 1024
        private const val PROPFIND_BODY = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"
    }
}
