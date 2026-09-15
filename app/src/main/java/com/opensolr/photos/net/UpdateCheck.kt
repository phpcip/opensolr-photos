package com.opensolr.photos.net

import com.opensolr.photos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Asks GitHub for the latest release of the app and says whether it is newer than the one
 * running. Public API, no credentials, one small GET; the caller decides how often.
 */
object UpdateCheck {

    /** A release newer than the installed app. */
    data class Update(val version: String, val notes: String, val pageUrl: String)

    private const val LATEST = "https://api.github.com/repos/phpcip/opensolr-photos/releases/latest"

    /**
     * Asks GitHub once: success carries the newer release, or null when the installed version is
     * already the latest; failure means the question could not be answered at all. Kept apart so a
     * check the user asked for never reports "up to date" after a network error.
     */
    suspend fun check(http: OkHttpClient = Http.client): Result<Update?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("GitHub answered ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("GitHub answered with nothing"))
                val json = JSONObject(body)
                // A draft or a pre-release is not offered to anyone.
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext Result.success(null)
                val tag = json.optString("tag_name").removePrefix("v")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext Result.success(null)
                Result.success(
                    Update(
                        version = tag,
                        notes = plain(json.optString("body")),
                        pageUrl = json.optString("html_url").ifBlank { "https://github.com/phpcip/opensolr-photos/releases/latest" },
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * True when [candidate] is a higher version than [current], compared number by number
     * (1.10.0 is newer than 1.9.2). Anything that is not digits and dots is not a version.
     */
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

    /**
     * Release notes as plain text for a notice: markdown headings, emphasis and code marks
     * dropped, bullets kept, the first lines only.
     */
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
