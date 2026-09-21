package com.opensolr.photos.data

import org.json.JSONObject

data class Session(val email: String, val apiKey: String)

data class IndexConnection(
    val indexName: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val environment: String,
)

data class AccountLimits(
    val plan: String,
    val vectorAllowed: Boolean,
    val maxAiRequests: Int,
    val aiRequestsUsed: Int,
    val indexLimit: Int,
    val indexesUsed: Int,
    val diskLimitMb: Double,
    val diskUsedMb: Double,
    val bandwidthLimitMb: Double,
    val bandwidthUsedMb: Double,
    val indexedDocs: Long,
    val refreshedAt: Long,

    val price: Double = 0.0,

    val recurrence: String = "",

    val maxPerMinute: Int = 120,
    val maxPerHour: Int = 1200,

    val photosPerRequest: Int = 10,
) {

    val planLabel: String get() {
        if (price <= 0.0) return plan.ifBlank { "Opensolr" }
        val amount = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(Math.round(price))
        val period = recurrence.trim().lowercase().let { r ->
            when {
                r.isBlank() -> "month"
                r.startsWith("1 ") -> r.removePrefix("1 ")
                else -> r
            }
        }
        return "€$amount / $period"
    }

    val photosPerMonth: Int? get() = if (maxAiRequests <= 0) null else maxAiRequests * photosPerRequest

    val photosLeftThisMonth: Int? get() =
        if (maxAiRequests <= 0) null else ((maxAiRequests - aiRequestsUsed).coerceAtLeast(0)) * photosPerRequest

    val diskFull: Boolean get() = diskLimitMb > 0 && diskUsedMb >= diskLimitMb

    val bandwidthFull: Boolean get() = bandwidthLimitMb > 0 && bandwidthUsedMb >= bandwidthLimitMb

    fun toJson(): String = JSONObject()
        .put("plan", plan)
        .put("vector_allowed", vectorAllowed)
        .put("max_ai_requests", maxAiRequests)
        .put("ai_requests_used", aiRequestsUsed)
        .put("index_limit", indexLimit)
        .put("indexes_used", indexesUsed)
        .put("disk_limit_mb", diskLimitMb)
        .put("disk_used_mb", diskUsedMb)
        .put("bandwidth_limit_mb", bandwidthLimitMb)
        .put("bandwidth_used_mb", bandwidthUsedMb)
        .put("indexed_docs", indexedDocs)
        .put("refreshed_at", refreshedAt)
        .toString()

    companion object {

        fun fromJson(json: JSONObject, previous: AccountLimits? = null): AccountLimits = AccountLimits(
            plan = json.optString("plan", previous?.plan ?: ""),
            vectorAllowed = if (json.has("vector_allowed")) json.optBoolean("vector_allowed") else previous?.vectorAllowed ?: false,
            maxAiRequests = if (json.has("max_ai_requests")) json.optInt("max_ai_requests") else previous?.maxAiRequests ?: 0,
            aiRequestsUsed = if (json.has("ai_requests_used")) json.optInt("ai_requests_used") else previous?.aiRequestsUsed ?: 0,
            indexLimit = if (json.has("index_limit")) json.optInt("index_limit") else previous?.indexLimit ?: 0,
            indexesUsed = if (json.has("indexes_used")) json.optInt("indexes_used") else previous?.indexesUsed ?: 0,
            diskLimitMb = if (json.has("disk_limit_mb")) json.optDouble("disk_limit_mb") else previous?.diskLimitMb ?: 0.0,
            diskUsedMb = if (json.has("disk_used_mb")) json.optDouble("disk_used_mb") else previous?.diskUsedMb ?: 0.0,
            bandwidthLimitMb = if (json.has("bandwidth_limit_mb")) json.optDouble("bandwidth_limit_mb") else previous?.bandwidthLimitMb ?: 0.0,
            bandwidthUsedMb = if (json.has("bandwidth_used_mb")) json.optDouble("bandwidth_used_mb") else previous?.bandwidthUsedMb ?: 0.0,
            indexedDocs = if (json.has("indexed_docs")) json.optLong("indexed_docs") else previous?.indexedDocs ?: 0L,
            refreshedAt = if (json.has("refreshed_at")) json.optLong("refreshed_at") else System.currentTimeMillis(),

            price = if (json.has("price")) json.optString("price").replace(",", "").toDoubleOrNull() ?: previous?.price ?: 0.0 else previous?.price ?: 0.0,
            recurrence = if (json.has("recurrence")) json.optString("recurrence") else previous?.recurrence ?: "",
            maxPerMinute = if (json.has("max_per_minute")) json.optInt("max_per_minute").coerceAtLeast(1) else previous?.maxPerMinute ?: 120,
            maxPerHour = if (json.has("max_per_hour")) json.optInt("max_per_hour").coerceAtLeast(1) else previous?.maxPerHour ?: 1200,

            photosPerRequest = if (json.has("photos_per_request")) json.optInt("photos_per_request").coerceAtLeast(1) else previous?.photosPerRequest ?: 10,
        )
    }
}

enum class SyncSchedule(val days: Long) {
    DAILY(1),
    WEEKLY(7),
    MONTHLY(30),
}

data class SyncReport(
    val finishedAt: Long,
    val status: String,
    val added: Int,
    val deleted: Int,
    val failed: Int,
    val localCount: Int,
    val indexCount: Int,
    val message: String,
    val recreated: Boolean,
    val indexAfter: Int = 0,
) {

    fun toJson(): String = JSONObject()
        .put("finished_at", finishedAt)
        .put("status", status)
        .put("added", added)
        .put("deleted", deleted)
        .put("failed", failed)
        .put("local_count", localCount)
        .put("index_count", indexCount)
        .put("message", message)
        .put("recreated", recreated)
        .put("index_after", indexAfter)
        .toString()

    companion object {

        fun fromJson(raw: String?): SyncReport? = try {
            raw?.let {
                val json = JSONObject(it)
                SyncReport(
                    finishedAt = json.getLong("finished_at"),
                    status = json.getString("status"),
                    added = json.optInt("added"),
                    deleted = json.optInt("deleted"),
                    failed = json.optInt("failed"),
                    localCount = json.optInt("local_count"),
                    indexCount = json.optInt("index_count"),
                    message = json.optString("message"),
                    recreated = json.optBoolean("recreated"),
                    indexAfter = json.optInt("index_after"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
