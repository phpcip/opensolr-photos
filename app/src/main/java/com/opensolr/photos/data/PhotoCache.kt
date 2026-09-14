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
     * Creates the single table.
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
    }

    /**
     * Schema changes simply start the cache over; it only ever saves AI requests.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS photos")
        onCreate(db)
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
        writableDatabase.delete("photos", null, null)
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
        private const val VERSION = 1
    }
}
