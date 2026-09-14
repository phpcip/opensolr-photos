package com.opensolr.photos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the phone already paid to learn about each photo: the Solr document built for it and
 * its search vector.
 *
 * Reading a photo into words and embedding those words both count against the plan's AI
 * allowance. Keeping the result here means a photo is only ever sent once: when the index is
 * emptied, deleted or recreated, a Re-Sync pushes the cached documents back without a single
 * AI request. An entry is reused only while the file's size and modification time are
 * unchanged, so an edited photo is read again.
 */
class PhotoCache(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    /**
     * A cached photo.
     *
     * @property docJson  the Solr document without the vector, as JSON
     * @property vector   the search vector, or null when the plan had no vector search
     */
    data class Entry(val docJson: String, val vector: FloatArray?)

    /**
     * Creates the tables: the photos, and the places already looked up for a position.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE photos (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "size_bytes INTEGER NOT NULL, " +
                "modified INTEGER NOT NULL, " +
                "doc_json TEXT NOT NULL, " +
                "vector BLOB)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS places (key TEXT PRIMARY KEY NOT NULL, place_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL)")
    }

    /**
     * Versions 2 and 3 only add tables; the photos (paid AI answers) are kept.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS places (key TEXT PRIMARY KEY NOT NULL, place_json TEXT NOT NULL)")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL)")
        }
    }

    /**
     * What the owner wrote about a photo: their tags, and their own wording of what the
     * photo shows when they changed it (null = keep what Opensolr saw).
     */
    data class Edits(val tags: List<String>, val meaning: String?)

    /**
     * The owner's edits of [id], or null when there are none.
     */
    fun getEdits(id: String): Edits? {
        readableDatabase.query("edits", arrayOf("tags_json", "meaning"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return null
            val array = org.json.JSONArray(cursor.getString(0))
            val tags = (0 until array.length()).map { array.getString(it) }
            return Edits(tags, if (cursor.isNull(1)) null else cursor.getString(1))
        }
    }

    /**
     * Stores the owner's edits of [id]; no tags and no wording removes the row.
     */
    fun putEdits(id: String, edits: Edits) {
        if (edits.tags.isEmpty() && edits.meaning == null) {
            writableDatabase.delete("edits", "id = ?", arrayOf(id))
            return
        }
        val values = ContentValues().apply {
            put("id", id)
            put("tags_json", org.json.JSONArray(edits.tags).toString())
            if (edits.meaning == null) putNull("meaning") else put("meaning", edits.meaning)
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("edits", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * True when [id] has a cached document (of any size or time).
     */
    fun has(id: String): Boolean {
        readableDatabase.query("photos", arrayOf("id"), "id = ?", arrayOf(id), null, null, null, "1").use { return it.moveToFirst() }
    }

    /**
     * The place already looked up for [key] (a rounded "lat,lon"), as JSON; null when unknown.
     * An empty string means the position was looked up and has no place.
     */
    fun getPlace(key: String): String? {
        readableDatabase.query("places", arrayOf("place_json"), "key = ?", arrayOf(key), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /**
     * Remembers the place of [key].
     */
    fun putPlace(key: String, placeJson: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("place_json", placeJson)
        }
        writableDatabase.insertWithOnConflict("places", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * The cached entry for [id] when the file still has the same [sizeBytes] and [modified]
     * time, otherwise null.
     */
    fun get(id: String, sizeBytes: Long, modified: Long): Entry? {
        readableDatabase.query(
            "photos", arrayOf("doc_json", "vector"),
            "id = ? AND size_bytes = ? AND modified = ?",
            arrayOf(id, sizeBytes.toString(), modified.toString()),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val blob = if (cursor.isNull(1)) null else cursor.getBlob(1)
            return Entry(cursor.getString(0), blob?.let { toFloats(it) })
        }
    }

    /**
     * The last document known for [id], whatever size or time the file had then: the
     * starting point of a rewrite, so no field the index already holds is lost. Null when
     * the photo was never cached.
     */
    fun getLatest(id: String): Entry? {
        readableDatabase.query("photos", arrayOf("doc_json", "vector"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return null
            val blob = if (cursor.isNull(1)) null else cursor.getBlob(1)
            return Entry(cursor.getString(0), blob?.let { toFloats(it) })
        }
    }

    /**
     * True when [id] was cached for a different [sizeBytes] or [modified] time: the file was
     * edited in place, so the photo must be read again. False when unknown or unchanged.
     */
    fun isStale(id: String, sizeBytes: Long, modified: Long): Boolean {
        readableDatabase.query("photos", arrayOf("size_bytes", "modified"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return false
            return cursor.getLong(0) != sizeBytes || cursor.getLong(1) != modified
        }
    }

    /**
     * Stores or replaces the entry for [id].
     */
    fun put(id: String, sizeBytes: Long, modified: Long, docJson: String, vector: FloatArray?) {
        val values = ContentValues().apply {
            put("id", id)
            put("size_bytes", sizeBytes)
            put("modified", modified)
            put("doc_json", docJson)
            if (vector == null) putNull("vector") else put("vector", toBytes(vector))
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Drops entries of photos that are no longer on the phone.
     */
    fun removeAllExcept(keep: Set<String>) {
        val stale = mutableListOf<String>()
        readableDatabase.query("photos", arrayOf("id"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in keep) stale += id
            }
        }
        if (stale.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.forEach { db.delete("photos", "id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Forgets everything (used on sign-out).
     */
    fun clear() {
        // The owner's edits are their work, not account data: they stay.
        writableDatabase.delete("photos", null, null)
        writableDatabase.delete("places", null, null)
    }

    /**
     * Little-endian float32 packing of a vector.
     */
    private fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Inverse of [toBytes].
     */
    private fun toFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    companion object {
        private const val NAME = "photo_cache.db"
        private const val VERSION = 3
    }
}
