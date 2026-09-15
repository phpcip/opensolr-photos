package com.opensolr.photos.data

import org.json.JSONObject

/**
 * The signed-in Opensolr account: the email and the account API key returned by /app/token.
 */
data class Session(val email: String, val apiKey: String)

/**
 * Where the phone's Opensolr Index answers and the credentials it wants.
 *
 * @property indexName    photos_<ANDROID_ID>__dense
 * @property baseUrl      connection_url from get_core_info, e.g. https://fi.solrcluster.com:443/solr/photos_x__dense
 * @property username     HTTP Basic user of the index
 * @property password     HTTP Basic password of the index
 * @property environment  the Opensolr environment (region) the index lives in
 */
data class IndexConnection(
    val indexName: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val environment: String,
)

/**
 * Plan limits and usage of the account, as reported by /app/token and get_account_summary.
 *
 * Sizes are megabytes, exactly as the platform reports them. A limit of 0 for AI requests
 * means the plan has no monthly cap.
 */
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
    /** What the plan costs per [recurrence], 0 when free or corporate. */
    val price: Double = 0.0,
    /** The billing period as the platform names it: "1 Month", "1 Year", "3 Months". */
    val recurrence: String = "",
    /** The account's API rate limits, so the app paces itself instead of being refused. */
    val maxPerMinute: Int = 120,
    val maxPerHour: Int = 1200,
) {

    /**
     * The plan as shown to the owner: the price and its period when there is one, "€2,815 / month",
     * otherwise the plan's name.
     */
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

    /**
     * AI requests one new photo costs: one, for its words and their vector together
     * (image_index). Editing a photo's tags or words costs one more, for the new vector.
     */
    val requestsPerPhoto: Int get() = 1

    /**
     * How many photos the monthly AI allowance covers, or null when the plan has no cap.
     */
    val photosPerMonth: Int? get() = if (maxAiRequests <= 0) null else maxAiRequests / requestsPerPhoto

    /**
     * How many more photos this month's remaining allowance covers, or null when uncapped.
     */
    val photosLeftThisMonth: Int? get() =
        if (maxAiRequests <= 0) null else ((maxAiRequests - aiRequestsUsed).coerceAtLeast(0)) / requestsPerPhoto

    /**
     * True once disk space of the index is used up.
     */
    val diskFull: Boolean get() = diskLimitMb > 0 && diskUsedMb >= diskLimitMb

    /**
     * True once the monthly search bandwidth of the index is used up.
     */
    val bandwidthFull: Boolean get() = bandwidthLimitMb > 0 && bandwidthUsedMb >= bandwidthLimitMb

    /**
     * Serialises to the JSON kept in preferences.
     */
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

        /**
         * Reads the limits from a platform JSON object (the `account` block of /app/token, the
         * `msg` of get_account_summary, or [toJson] output). Missing fields keep [previous]'s
         * value, so a summary that carries no index count does not erase the one from sign-in.
         */
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
            // The platform formats the price with thousands separators ("2,815.20"): a number again here.
            price = if (json.has("price")) json.optString("price").replace(",", "").toDoubleOrNull() ?: previous?.price ?: 0.0 else previous?.price ?: 0.0,
            recurrence = if (json.has("recurrence")) json.optString("recurrence") else previous?.recurrence ?: "",
            maxPerMinute = if (json.has("max_per_minute")) json.optInt("max_per_minute").coerceAtLeast(1) else previous?.maxPerMinute ?: 120,
            maxPerHour = if (json.has("max_per_hour")) json.optInt("max_per_hour").coerceAtLeast(1) else previous?.maxPerHour ?: 1200,
        )
    }
}

/**
 * How often the scheduled Re-Sync runs.
 */
enum class SyncSchedule(val days: Long) {
    DAILY(1),
    WEEKLY(7),
    MONTHLY(30),
}

/**
 * The result of the last sync run, kept so the Sync screen can show it after the app restarts.
 *
 * @property finishedAt  wall-clock millis when the run ended
 * @property status      ok, failed, stopped_quota, stopped_plan_limit, sign_in_required
 * @property added       photos written to the index
 * @property deleted     documents removed because the photo is no longer on the phone
 * @property failed      photos that could not be read and were skipped
 * @property localCount  photos found in the chosen folders
 * @property indexCount  documents the index held before the run
 * @property indexAfter  documents the index holds after the run (what the Sync screen shows)
 * @property message     human-readable detail for failures
 * @property recreated   true when the index had disappeared and was created again
 */
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

    /**
     * Serialises to the JSON kept in preferences.
     */
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

        /**
         * Parses [toJson] output; null when the value is missing or damaged.
         */
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
