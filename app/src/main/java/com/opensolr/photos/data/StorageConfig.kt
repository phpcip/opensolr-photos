package com.opensolr.photos.data

import org.json.JSONObject

/** Where the photos of this index are backed up: one backend per index, its fields as the owner typed them. */
data class StorageConfig(
    val kind: String,
    val label: String,
    val quality: String,
    val fields: Map<String, String>,
    val updated: Long = 0L,
) {

    fun field(name: String): String = fields[name].orEmpty().trim()

    val host: String get() = when (kind) {
        KIND_S3 -> field("bucket")
        else -> runCatching { java.net.URI(field("url")).host }.getOrNull().orEmpty()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("label", label)
        .put("quality", quality)
        .put("config", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
        .put("updated", updated)

    companion object {
        const val KIND_S3 = "s3"
        const val KIND_WEBDAV = "webdav"

        const val QUALITY_ORIGINAL = "original"
        const val QUALITY_1080 = "1080"

        /** The same shape the platform answers with: kind, label, quality, config. */
        fun fromJson(json: JSONObject): StorageConfig? {
            val kind = json.optString("kind")
            if (kind !in listOf(KIND_S3, KIND_WEBDAV)) return null
            val config = json.optJSONObject("config") ?: JSONObject()
            val fields = HashMap<String, String>()
            config.keys().forEach { k -> fields[k] = config.optString(k) }
            return StorageConfig(
                kind = kind,
                label = json.optString("label"),
                quality = json.optString("quality").ifBlank { QUALITY_ORIGINAL },
                fields = fields,
                updated = json.optLong("updated"),
            )
        }
    }
}

/** The last backup or restore run, as the Storage screen shows it. */
data class StorageReport(
    val finishedAt: Long,
    val status: String,
    val uploaded: Int,
    val restored: Int,
    val failed: Int,
    val message: String,
) {

    fun toJson(): String = JSONObject()
        .put("finished_at", finishedAt)
        .put("status", status)
        .put("uploaded", uploaded)
        .put("restored", restored)
        .put("failed", failed)
        .put("message", message)
        .toString()

    companion object {
        fun fromJson(raw: String?): StorageReport? = try {
            raw?.let {
                val json = JSONObject(it)
                StorageReport(
                    finishedAt = json.getLong("finished_at"),
                    status = json.getString("status"),
                    uploaded = json.optInt("uploaded"),
                    restored = json.optInt("restored"),
                    failed = json.optInt("failed"),
                    message = json.optString("message"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
