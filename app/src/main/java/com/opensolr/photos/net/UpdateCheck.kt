package com.opensolr.photos.net

import com.opensolr.photos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object UpdateCheck {

    data class Update(val version: String, val notes: String, val pageUrl: String, val apkUrl: String = APK_LATEST)

    const val APK_LATEST = "https://github.com/phpcip/opensolr-photos/releases/latest/download/opensolr-photos.apk"

    private const val LATEST = "https://api.github.com/repos/phpcip/opensolr-photos/releases/latest"

    suspend fun check(http: OkHttpClient = Http.client): Result<Update?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("GitHub answered ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("GitHub answered with nothing"))
                val json = JSONObject(body)

                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext Result.success(null)
                val tag = json.optString("tag_name").removePrefix("v")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext Result.success(null)
                Result.success(
                    Update(
                        version = tag,
                        notes = plain(json.optString("body")),
                        pageUrl = json.optString("html_url").ifBlank { "https://github.com/phpcip/opensolr-photos/releases/latest" },
                        apkUrl = apkOf(json, tag),
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun apkOf(json: JSONObject, tag: String): String {
        val assets = json.optJSONArray("assets") ?: return APK_LATEST
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            if (a.optString("name") == "opensolr-photos.apk" && url.startsWith("https://github.com/phpcip/opensolr-photos/releases/download/v$tag/")) return url
        }
        return APK_LATEST
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun plain(markdown: String): String = markdown
        .lines()
        .map { it.trim().removePrefix("#").trimStart('#', ' ').replace("**", "").replace("`", "").replace("*", "") }
        .map { if (it.startsWith("- ")) "\u2022 " + it.removePrefix("- ") else it }
        .filter { it.isNotBlank() }
        .take(6)
        .joinToString("\n")

    private fun parts(version: String): List<Int>? =
        version.trim().split('.').map { it.toIntOrNull() ?: return null }
}
