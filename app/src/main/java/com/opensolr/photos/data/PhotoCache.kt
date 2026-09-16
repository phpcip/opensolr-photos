package com.opensolr.photos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Everything the phone keeps about its own photos, in three tables of one database:
 *
 * - **photos**: the document Opensolr built for a photo and its search vector. Reading a photo
 *   into words and turning those words into a vector both count against the plan's AI
 *   allowance, so keeping the answer here means a photo is only ever paid for once: when the
 *   index is emptied, deleted or recreated, a Re-Sync pushes these documents back without a
 *   single AI request. An entry is reused only while the file's size and modification time are
 *   unchanged, so an edited photo is read again.
 * - **places**: the place already looked up for a rounded position, so the same spot is never
 *   asked for twice. An empty answer means "looked up, and there is no place there".
 * - **edits**: the tags and the wording the owner gave a photo. Every later write of that photo
 *   puts them back over what Opensolr saw, so the owner's words always win.
 * - **word_retries**: photos Opensolr wrote without words although the plan has AI (the AI
 *   server was restarting, a photo it cannot read). Each is asked again only after a pause
 *   that grows with every miss (1 h, 4 h, 16 h, then daily), so a photo that never gets words
 *   can never keep the sync busy.
 * - **skipped**: photos the phone itself cannot open or decode, so they never reach Opensolr.
 *   Each is remembered with its file size and tried again only when the file changes; the
 *   Photos screen lists them.
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
     * Creates the three tables: the photos, the places already looked up for a position, and
     * the owner's edits.
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
        db.execSQL(WORD_RETRIES_TABLE)
        db.execSQL(SKIPPED_TABLE)
    }

    /**
     * Versions 2, 3, 4 and 5 only add tables; the photos (paid AI answers) are kept.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS places (key TEXT PRIMARY KEY NOT NULL, place_json TEXT NOT NULL)")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL)")
        }
        if (oldVersion < 4) {
            db.execSQL(WORD_RETRIES_TABLE)
        }
        if (oldVersion < 5) {
            db.execSQL(SKIPPED_TABLE)
        }
    }

    /**
     * A photo the phone could not read, as the Photos screen lists it.
     *
     * @property reason  why, in words for the owner
     */
    data class Skipped(
        val id: String,
        val sizeBytes: Long,
        val mediaId: Long,
        val path: String,
        val folder: String,
        val fileName: String,
        val mime: String,
        val takenMs: Long,
        val reason: String,
    )

    /**
     * Remembers that [photo] could not be read, for [reason], at its current size.
     */
    fun markSkipped(photo: com.opensolr.photos.media.LocalPhoto, reason: String) {
        val values = ContentValues().apply {
            put("id", photo.id)
            put("size_bytes", photo.sizeBytes)
            put("media_id", photo.mediaId)
            put("path", photo.absolutePath)
            put("folder", photo.folder)
            put("file_name", photo.fileName)
            put("mime", photo.mime)
            put("taken_ms", if (photo.dateTakenMs > 0) photo.dateTakenMs else photo.modifiedSec * 1000L)
            put("reason", reason)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("skipped", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * The size each skipped photo had when it failed, by id: a photo is tried again only once
     * its size is different.
     */
    fun skippedSizes(): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.query("skipped", arrayOf("id", "size_bytes"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getLong(1)
        }
        return out
    }

    /**
     * Every skipped photo, newest first.
     */
    fun skipped(): List<Skipped> {
        val out = ArrayList<Skipped>()
        readableDatabase.query(
            "skipped",
            arrayOf("id", "size_bytes", "media_id", "path", "folder", "file_name", "mime", "taken_ms", "reason"),
            null, null, null, null, "taken_ms DESC",
        ).use { c ->
            while (c.moveToNext()) {
                out += Skipped(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7), c.getString(8))
            }
        }
        return out
    }

    /**
     * How many photos are skipped right now.
     */
    fun skippedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM skipped", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Forgets [id] as skipped: it was read after all, or the owner asked for it again.
     */
    fun clearSkipped(id: String) {
        writableDatabase.delete("skipped", "id = ?", arrayOf(id))
    }

    /**
     * Drops the skipped photos that are no longer among [onPhone] (deleted, or out of the
     * chosen folders).
     */
    fun keepSkippedOnly(onPhone: Set<String>) {
        val gone = skippedSizes().keys.filter { it !in onPhone }
        gone.forEach { clearSkipped(it) }
    }

    /**
     * Ids of the photos still waiting out their pause after being written without words, at
     * [now] (epoch millis). The sync leaves these out of its "read again" list.
     */
    fun wordRetriesWaiting(now: Long): Set<String> {
        val out = HashSet<String>()
        readableDatabase.query("word_retries", arrayOf("id"), "next_at > ?", arrayOf(now.toString()), null, null, null).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getString(0)
        }
        return out
    }

    /**
     * Records that [id] came back without words once more and sets when it may be asked
     * again: 1 h after the first miss, 4 h after the second, 16 h after the third, then 24 h.
     */
    fun noteWordsMissing(id: String, now: Long) {
        var attempts = 0
        readableDatabase.query("word_retries", arrayOf("attempts"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) attempts = cursor.getInt(0)
        }
        attempts++
        val hours = if (attempts >= 4) 24L else 1L shl (2 * (attempts - 1))
        val values = ContentValues().apply {
            put("id", id)
            put("attempts", attempts)
            put("next_at", now + hours * 3_600_000L)
        }
        writableDatabase.insertWithOnConflict("word_retries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Forgets the pause of [id]: it got its words, or the owner asked for it explicitly.
     */
    fun clearWordRetry(id: String) {
        writableDatabase.delete("word_retries", "id = ?", arrayOf(id))
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
     * The size and modification time every cached photo was stored with, in one query: the
     * sync compares thousands of photos against these from memory instead of asking the
     * database once per photo.
     */
    fun allStamps(): Map<String, Stamp> {
        val out = HashMap<String, Stamp>()
        readableDatabase.query("photos", arrayOf("id", "size_bytes", "modified"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = Stamp(cursor.getLong(1), cursor.getLong(2))
        }
        return out
    }

    /** What a cached photo was stored as: the file's size and modification time then. */
    data class Stamp(val sizeBytes: Long, val modified: Long)

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
        writableDatabase.delete("word_retries", null, null)
        writableDatabase.delete("skipped", null, null)
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
        private const val VERSION = 5
        private const val SKIPPED_TABLE = "CREATE TABLE IF NOT EXISTS skipped (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, media_id INTEGER NOT NULL, path TEXT NOT NULL, folder TEXT NOT NULL, file_name TEXT NOT NULL, mime TEXT NOT NULL, taken_ms INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)"
        private const val WORD_RETRIES_TABLE = "CREATE TABLE IF NOT EXISTS word_retries (id TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, next_at INTEGER NOT NULL)"
    }
}
