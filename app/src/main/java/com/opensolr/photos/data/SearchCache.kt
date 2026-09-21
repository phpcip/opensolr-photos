package com.opensolr.photos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class SearchCache private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE responses (" +
                "key TEXT PRIMARY KEY NOT NULL, " +
                "body TEXT NOT NULL, " +
                "stored INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS responses")
        onCreate(db)
    }

    fun get(key: String, ttlSeconds: Int): String? {
        if (ttlSeconds <= 0) return null
        val oldest = System.currentTimeMillis() - ttlSeconds * 1000L
        readableDatabase.query(
            "responses", arrayOf("body"),
            "key = ? AND stored >= ?", arrayOf(key, oldest.toString()),
            null, null, null, "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun put(key: String, body: String) {
        if (body.length > MAX_BODY_CHARS) return
        val values = ContentValues().apply {
            put("key", key)
            put("body", body)
            put("stored", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insertWithOnConflict("responses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        prune(db)
    }

    fun clear() {
        writableDatabase.delete("responses", null, null)
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM responses", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun prune(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM responses WHERE key NOT IN (SELECT key FROM responses ORDER BY stored DESC LIMIT ?)",
            arrayOf(MAX_ROWS.toString()),
        )
    }

    companion object {
        private const val NAME = "search_cache.db"
        private const val VERSION = 1

        @Volatile private var shared: SearchCache? = null

        fun of(context: Context): SearchCache = shared ?: synchronized(this) {
            shared ?: SearchCache(context.applicationContext).also { shared = it }
        }

        private const val MAX_BODY_CHARS = 700_000

        private const val MAX_ROWS = 400

        const val MIN_SECONDS = 60

        const val MAX_SECONDS = 86_400

        const val DEFAULT_SECONDS = 60

        fun key(indexName: String, path: String, params: List<Pair<String, String>>): String {
            val text = buildString {
                append(indexName).append(' ').append(path)
                params.map { (name, value) -> "$name=$value" }.sorted().forEach { append(' ').append(it) }
            }
            val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
