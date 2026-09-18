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
        db.execSQL(EDITS_TABLE)
        db.execSQL(DOCS_TABLE)
        db.execSQL(ACTIONS_TABLE)
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
        if (oldVersion < 6) {
            addColumnIfMissing(db, "edits", "persons_json", "TEXT")
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, "edits", "pending", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "edits", "tags_mode", "TEXT")
            addColumnIfMissing(db, "edits", "persons_mode", "TEXT")
        }
        if (oldVersion < 8) {
            // The phone's copy of the index. Created from the CURRENT definition, so a database
            // coming from far back already has every column a later step would add - which is why
            // each of those steps asks whether the column is there rather than adding it blind
            // (Cip, 2026-09-18: an upgrade from 2.4 died on "duplicate column name: modified").
            db.execSQL(DOCS_TABLE)
            db.execSQL(ACTIONS_TABLE)
        }
        if (oldVersion in 8..8) {
            // Version 8 held only part of a document; the copy now holds the whole of it, so what
            // was stored then is dropped and read from the index again.
            db.execSQL("DROP TABLE IF EXISTS docs")
            db.execSQL(DOCS_TABLE)
        }
        if (oldVersion < 10) {
            addColumnIfMissing(db, "docs", "modified", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "docs", "json", "TEXT")
        }
    }

    /**
     * Adds a column only when the table does not already have it. A table created from the newest
     * definition during an upgrade from an old database already carries the columns that later
     * steps were written to add, and adding one twice takes the whole app down on start.
     */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, type: String) {
        val has = try {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameColumn = c.getColumnIndex("name")
                var found = false
                while (c.moveToNext()) {
                    if (nameColumn >= 0 && c.getString(nameColumn) == column) { found = true; break }
                }
                found
            }
        } catch (e: Exception) {
            true
        }
        if (!has) {
            try {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
            } catch (e: Exception) {
                // Already there after all: nothing to do, and never a reason to stop the app.
            }
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
     * The phone's own copy of one photo's document: everything the index holds about it except
     * the vector and the duplicate keys, which the phone has no use for.
     *
     * This is what makes a sync cheap. With it, the app knows what changed without asking the
     * index anything at all, and the tag and name suggestions are answered from the phone.
     */
    data class Doc(
        val id: String,
        val sizeBytes: Long,
        val indexedAt: Long,
        val takenAt: String?,
        val tags: List<String>,
        val persons: List<String>,
        val meaning: String?,
        val ocr: String?,
        val city: String?,
        val country: String?,
        /** The document itself, exactly as the index holds it, save for the vector. */
        val json: String? = null,
        /**
         * When the file was last written, as the phone's own media store reports it. Together with
         * the size it says whether a file has been touched at all - a photo edited by another app
         * without changing its size would otherwise go unnoticed (Cip, 2026-09-18). 0 = unknown.
         */
        val modified: Long = 0,
    )

    /**
     * Writes (or replaces) the phone's copy of a photo's document.
     */
    fun putDoc(doc: Doc) {
        val values = ContentValues().apply {
            put("id", doc.id)
            put("size_bytes", doc.sizeBytes)
            put("indexed_at", doc.indexedAt)
            if (doc.takenAt == null) putNull("taken_at") else put("taken_at", doc.takenAt)
            put("tags_json", org.json.JSONArray(doc.tags).toString())
            put("persons_json", org.json.JSONArray(doc.persons).toString())
            if (doc.meaning == null) putNull("meaning") else put("meaning", doc.meaning)
            if (doc.ocr == null) putNull("ocr") else put("ocr", doc.ocr)
            if (doc.city == null) putNull("city") else put("city", doc.city)
            if (doc.country == null) putNull("country") else put("country", doc.country)
            if (doc.json == null) putNull("json") else put("json", doc.json)
            put("modified", doc.modified)
        }
        writableDatabase.insertWithOnConflict("docs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * The md5 of the file itself as the index holds it for [id], or null when that photo was
     * written before the app started sending one. It is the last word on whether a file really
     * changed: the size and the time it was written are only the cheap signal that says which
     * photos are worth looking at (Cip, 2026-09-18).
     */
    fun docFileHash(id: String): String? {
        val json = doc(id)?.json ?: return null
        return try {
            org.json.JSONObject(json).optString("file_hash").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The phone's copy of [id], or null when it holds none.
     */
    fun doc(id: String): Doc? {
        readableDatabase.query("docs", DOC_COLUMNS, "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) readDoc(c) else null
        }
    }

    /**
     * Every photo the index holds, by id, with the size it had: the one thing a sync compares
     * the phone's folders against.
     */
    fun docSizes(): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.query("docs", arrayOf("id", "size_bytes", "modified"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) to c.getLong(2)
        }
        return out
    }

    /**
     * When every photo of the library was taken, newest first, out of the phone's own copy of the
     * index: enough to lay out the years, months and days of the whole library without asking the
     * index for a single page (Cip, 2026-09-18). Photos with no date of their own are left out -
     * they sit where the grid puts them when they arrive.
     */
    fun takenTimes(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query("docs", arrayOf("taken_at"), "taken_at IS NOT NULL", null, null, null, null).use { c ->
            while (c.moveToNext()) {
                com.opensolr.photos.ui.Actions.solrDateMillis(c.getString(0))?.let { out += it }
            }
        }
        out.sortDescending()
        return out
    }

    /**
     * The documents taken between two instants, newest first: how the grid is filled while
     * browsing, with nothing asked of the index (Cip, 2026-09-18).
     */
    fun docsBetween(from: Long, to: Long): List<String> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.query("docs", arrayOf("taken_at", "json"), "taken_at IS NOT NULL AND json IS NOT NULL", null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val at = com.opensolr.photos.ui.Actions.solrDateMillis(c.getString(0)) ?: continue
                if (at in from..to) out += at to c.getString(1)
            }
        }
        out.sortByDescending { it.first }
        return out.map { it.second }
    }

    /** How many photos the phone's copy of the index holds. */
    fun docCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM docs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Ids the phone's copy holds with no words on them: the index wrote them before the plan had
     * AI, or the reading failed. They are offered to the server again when there is allowance.
     */
    fun docsWithoutWords(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id", "meaning", "json"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val noMeaning = c.isNull(1) || c.getString(1).isBlank()
                // A photo can carry words kept from before and still have no vector of its own -
                // written while the plan had no AI. It is offered again when the AI is back
                // (Cip, 2026-09-18).
                val noVector = try {
                    val json = if (c.isNull(2)) null else org.json.JSONObject(c.getString(2))
                    json == null || json.optString("embed_model").isBlank()
                } catch (e: Exception) {
                    true
                }
                if (noMeaning || noVector) out += c.getString(0)
            }
        }
        return out
    }

    /**
     * The photos that are paperwork: the ones text has already been read out of, and the ones whose
     * words say they are a receipt, a label, a screenshot - the same list the server runs before it
     * sends a photo to be read at all (Cip, 2026-09-18). Both, because a document whose text came
     * back empty is still a document and must be offered again.
     */
    fun docsLikeDocuments(words: List<String>): List<String> {
        val needles = words.map { it.trim('"').lowercase() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id", "ocr", "meaning"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val hasOcr = !c.isNull(1) && c.getString(1).isNotBlank()
                val meaning = if (c.isNull(2)) "" else c.getString(2).lowercase()
                if (hasOcr || needles.any { meaning.contains(it) }) out += c.getString(0)
            }
        }
        return out
    }

    /** Ids the phone's copy holds that were written before [before] (for "Re-read all"). */
    fun docsIndexedBefore(before: Long): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id"), "indexed_at < ?", arrayOf(before.toString()), null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /**
     * The file of [id] is now [size] bytes. Used right after the app writes the owner's words into
     * a photo: the pixels did not change, only the words in its header, so the sync must not read
     * the picture again over it (Cip, 2026-09-18).
     */
    fun updateDocSize(id: String, size: Long, modified: Long, fileHash: String? = null) {
        if (size <= 0) return
        writableDatabase.execSQL("UPDATE docs SET size_bytes = ?, modified = ? WHERE id = ?", arrayOf<Any>(size, modified, id))
        // The app has just written the owner's words into the file, so the file itself is a
        // different file now: its md5 has to travel with the rest, or every later sync would see a
        // photo whose fingerprint does not match and read the picture again (Cip, 2026-09-18).
        if (fileHash == null) return
        val doc = doc(id) ?: return
        val json = doc.json ?: return
        val updated = try {
            org.json.JSONObject(json).put("file_hash", fileHash).put("size_bytes", size).toString()
        } catch (e: Exception) {
            return
        }
        writableDatabase.execSQL("UPDATE docs SET json = ? WHERE id = ?", arrayOf<Any>(updated, id))
    }

    /** Forgets the phone's copy of [ids] - they are no longer in the index. */
    fun removeDocs(ids: Collection<String>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { writableDatabase.delete("docs", "id = ?", arrayOf(it)) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Empties the phone's copy of the index (a reset, a new index, a rebuild). */
    fun clearDocs() {
        writableDatabase.delete("docs", null, null)
        writableDatabase.delete("actions", null, null)
    }

    /**
     * The owner's words as the phone holds them, over the whole library: every tag and every
     * name with how many photos carry it, and the words the photos were read into.
     */
    fun wordCounts(): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
        val tags = HashMap<String, Int>()
        val persons = HashMap<String, Int>()
        val meanings = HashMap<String, Int>()
        readableDatabase.query("docs", arrayOf("tags_json", "persons_json", "meaning"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                jsonWords(c.getString(0)).forEach { tags[it] = (tags[it] ?: 0) + 1 }
                jsonWords(c.getString(1)).forEach { persons[it] = (persons[it] ?: 0) + 1 }
                if (!c.isNull(2)) {
                    c.getString(2).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { meanings[it] = (meanings[it] ?: 0) + 1 }
                }
            }
        }
        return Triple(tags, persons, meanings)
    }

    /**
     * The same counts over a set of photos only, for the sheet that tags many at once.
     */
    fun wordCountsOf(ids: Collection<String>): Pair<Map<String, Int>, Map<String, Int>> {
        val tags = HashMap<String, Int>()
        val persons = HashMap<String, Int>()
        ids.chunked(400).forEach { batch ->
            val marks = batch.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT tags_json, persons_json FROM docs WHERE id IN ($marks)", batch.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    jsonWords(c.getString(0)).forEach { tags[it] = (tags[it] ?: 0) + 1 }
                    jsonWords(c.getString(1)).forEach { persons[it] = (persons[it] ?: 0) + 1 }
                }
            }
        }
        return tags to persons
    }

    /**
     * Something that has to reach the index. The photo's id is the key, so a photo can only ever
     * have one thing waiting for it: a later action takes the place of an earlier one, except
     * that "gone" beats everything and "read the picture again" beats "the words changed".
     */
    fun queueAction(id: String, kind: String) {
        val current = actionOf(id)
        val winner = when {
            current == null -> kind
            current == ACTION_DELETE || kind == ACTION_DELETE -> ACTION_DELETE
            current == ACTION_INDEX || kind == ACTION_INDEX -> ACTION_INDEX
            else -> kind
        }
        val values = ContentValues().apply {
            put("id", id)
            put("kind", winner)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Queues the same action for many photos at once. */
    fun queueActions(ids: Collection<String>, kind: String) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { queueAction(it, kind) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** What is waiting for [id], or null when nothing is. */
    fun actionOf(id: String): String? {
        readableDatabase.query("actions", arrayOf("kind"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** The photos waiting for [kind], oldest first. */
    fun actions(kind: String, limit: Int = 100000): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("actions", arrayOf("id"), "kind = ?", arrayOf(kind), null, null, "at ASC", limit.toString()).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** How many photos are waiting for anything at all. */
    fun actionCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM actions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Drops the actions of [ids] - they have been done. One at a time as each succeeds, never in
     * one sweep at the end: a run stopped half way must leave the rest waiting (Cip, 2026-09-18).
     */
    fun clearActions(ids: Collection<String>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { writableDatabase.delete("actions", "id = ?", arrayOf(it)) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** The words of a stored JSON array. */
    private fun jsonWords(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One row of the phone's copy of the index. */
    private fun readDoc(c: android.database.Cursor) = Doc(
        id = c.getString(0),
        sizeBytes = c.getLong(1),
        indexedAt = c.getLong(2),
        takenAt = if (c.isNull(3)) null else c.getString(3),
        tags = jsonWords(c.getString(4)),
        persons = jsonWords(c.getString(5)),
        meaning = if (c.isNull(6)) null else c.getString(6),
        ocr = if (c.isNull(7)) null else c.getString(7),
        city = if (c.isNull(8)) null else c.getString(8),
        country = if (c.isNull(9)) null else c.getString(9),
        json = if (c.isNull(10)) null else c.getString(10),
        modified = c.getLong(11),
    )

    /**
     * What the owner wrote about a photo: their tags, and their own wording of what the
     * photo shows when they changed it (null = keep what Opensolr saw).
     *
     */
    data class Edits(val tags: List<String>, val meaning: String?, val persons: List<String>? = null)

    /**
     * The owner's edits of [id], or null when there are none.
     */
    fun getEdits(id: String): Edits? {
        readableDatabase.query("edits", EDIT_COLUMNS, "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return null
            return readEdits(cursor)
        }
    }

    /**
     * Stores the owner's edits of [id] as the settled truth: nothing left to send, no mode. No
     * tags, no wording and no names of their own removes the row. [Edits.persons] null means the
     * names on the file stand; empty means none.
     */
    fun putEdits(id: String, edits: Edits) {
        if (edits.tags.isEmpty() && edits.meaning == null && edits.persons == null) {
            writableDatabase.delete("edits", "id = ?", arrayOf(id))
            return
        }
        val values = ContentValues().apply {
            put("id", id)
            put("tags_json", org.json.JSONArray(edits.tags).toString())
            if (edits.meaning == null) putNull("meaning") else put("meaning", edits.meaning)
            if (edits.persons == null) putNull("persons_json") else put("persons_json", org.json.JSONArray(edits.persons).toString())
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("edits", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * One row of the edits table.
     */
    private fun readEdits(cursor: android.database.Cursor, offset: Int = 0): Edits {
        val array = org.json.JSONArray(cursor.getString(offset))
        val tags = (0 until array.length()).map { array.getString(it) }
        val persons = if (cursor.isNull(offset + 2)) null else org.json.JSONArray(cursor.getString(offset + 2)).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Edits(tags, if (cursor.isNull(offset + 1)) null else cursor.getString(offset + 1), persons)
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
        private const val VERSION = 10

        private const val DOCS_TABLE = "CREATE TABLE IF NOT EXISTS docs (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, indexed_at INTEGER NOT NULL, taken_at TEXT, tags_json TEXT, persons_json TEXT, meaning TEXT, ocr TEXT, city TEXT, country TEXT, json TEXT, modified INTEGER NOT NULL DEFAULT 0)"
        private const val ACTIONS_TABLE = "CREATE TABLE IF NOT EXISTS actions (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL)"

        /** A photo whose picture has to be read again: new, or its file changed. */
        const val ACTION_INDEX = "index"

        /** Only the owner's words changed: no picture is sent, just the text. */
        const val ACTION_WORDS = "words"

        /** The photo is gone from the phone, so it goes from the index. */
        const val ACTION_DELETE = "delete"

        private val DOC_COLUMNS = arrayOf("id", "size_bytes", "indexed_at", "taken_at", "tags_json", "persons_json", "meaning", "ocr", "city", "country", "json", "modified")
        private val EDIT_COLUMNS = arrayOf("tags_json", "meaning", "persons_json")
        private const val EDITS_TABLE = "CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL, persons_json TEXT, pending INTEGER NOT NULL DEFAULT 0, tags_mode TEXT, persons_mode TEXT)"
        private const val SKIPPED_TABLE = "CREATE TABLE IF NOT EXISTS skipped (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, media_id INTEGER NOT NULL, path TEXT NOT NULL, folder TEXT NOT NULL, file_name TEXT NOT NULL, mime TEXT NOT NULL, taken_ms INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)"
        private const val WORD_RETRIES_TABLE = "CREATE TABLE IF NOT EXISTS word_retries (id TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, next_at INTEGER NOT NULL)"
    }
}
