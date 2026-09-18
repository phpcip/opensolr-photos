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
class PhotoCache private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

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
        db.execSQL(DOCS_TAKEN_INDEX)
        db.execSQL(DOCS_INDEXED_INDEX)
        db.execSQL(DOCS_STAMP_INDEX)
        db.execSQL(DOCS_WORDLESS_INDEX)
        db.execSQL(WORDS_TABLE)
        db.execSQL(WORDS_INDEX)
        db.execSQL(ACTIONS_TABLE)
        db.execSQL(ACTIONS_KIND_INDEX)
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
        if (oldVersion < 11) {
            // The date a photo was taken, kept as a number and indexed: the grid asks "which photos
            // are in this month" constantly, and reading it out of the text of every row meant a
            // pass over the whole library for every group opened (Cip, 2026-09-18).
            addColumnIfMissing(db, "docs", "taken_ms", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL(DOCS_TAKEN_INDEX)
            backfillTakenMs(db)
        }
        if (oldVersion < 12) {
            addColumnIfMissing(db, "docs", "embed_model", "TEXT")
            db.execSQL(DOCS_INDEXED_INDEX)
            db.execSQL(WORDS_TABLE)
            db.execSQL(WORDS_INDEX)
            backfillWords(db)
        }
        if (oldVersion < 13) {
            // The fingerprint of the file, out of the document and into a column of its own: the
            // sync asks for it once per photo it suspects, and reading it meant fetching and taking
            // apart the whole document each time (Cip, 2026-09-18).
            addColumnIfMissing(db, "docs", "file_hash", "TEXT")
            db.execSQL(DOCS_STAMP_INDEX)
            db.execSQL(ACTIONS_KIND_INDEX)
            backfillFileHash(db)
            // Built after the fingerprints and the models are in, so it starts out holding only the
            // photos that really have no words.
            db.execSQL(DOCS_WORDLESS_INDEX)
        }
    }

    /**
     * Fills the fingerprint, and the vector's model for a database that never got one, out of the
     * documents already stored.
     *
     * The model matters as much as the fingerprint: a photo whose `embed_model` is empty counts as
     * one the server still owes words, so leaving the column unfilled would send the whole library
     * back through the paid reader on the next sync (Cip, 2026-09-18).
     */
    private fun backfillFileHash(db: SQLiteDatabase) {
        db.compileStatement("UPDATE docs SET file_hash = ?, embed_model = coalesce(?, embed_model) WHERE id = ?").use { update ->
            forEachPage(db, "docs", arrayOf("id", "json"), "json IS NOT NULL") { id, row ->
                val json = row[1] ?: return@forEachPage
                // Read properly, not by looking for the field's name in the text: a photo's stored
                // text contains whatever was printed in the picture, and a scan for a name can pick
                // up the wrong thing. This runs once per photo, once ever (Cip, 2026-09-18).
                val (hash, model) = try {
                    val o = org.json.JSONObject(json)
                    o.optString("file_hash").ifBlank { null } to o.optString("embed_model").ifBlank { null }
                } catch (e: Exception) {
                    null to null
                }
                if (hash == null && model == null) return@forEachPage
                update.clearBindings()
                if (hash == null) update.bindNull(1) else update.bindString(1, hash)
                if (model == null) update.bindNull(2) else update.bindString(2, model)
                update.bindString(3, id)
                update.executeUpdateDelete()
            }
        }
    }

    /**
     * Walks a table in pages of [MIGRATION_PAGE] rows and hands each row to [work].
     *
     * Reading a whole library into memory to change it - documents and all - is what an update must
     * never do on a phone (Cip, 2026-09-18). The paging is by the key, not by an offset: the rows
     * are being rewritten as they are read, and an offset over a moving table can skip one.
     *
     * No transaction is opened here, deliberately. Android already runs the whole upgrade inside
     * one, and a transaction inside it that ends without being marked successful marks THAT one as
     * failed too - the schema changes and the new version number would roll back and the same
     * upgrade would run again on every start, for ever (Cip, 2026-09-18).
     */
    private fun forEachPage(
        db: SQLiteDatabase,
        table: String,
        columns: Array<String>,
        where: String?,
        work: (String, Array<String?>) -> Unit,
    ) {
        var after = ""
        while (true) {
            val page = ArrayList<Array<String?>>(MIGRATION_PAGE)
            val clause = if (where == null) "id > ?" else "id > ? AND ($where)"
            db.query(table, columns, clause, arrayOf(after), null, null, "id ASC", MIGRATION_PAGE.toString()).use { c ->
                while (c.moveToNext()) {
                    page += Array(columns.size) { i -> if (c.isNull(i)) null else c.getString(i) }
                }
            }
            if (page.isEmpty()) return
            page.forEach { row ->
                val id = row[0] ?: return@forEach
                work(id, row)
            }
            after = page.last()[0] ?: return
            if (page.size < MIGRATION_PAGE) return
        }
    }

    /**
     * Fills the new date column from what the rows already hold, so nothing has to be read from the
     * index again after an update.
     */
    private fun backfillTakenMs(db: SQLiteDatabase) {
        db.compileStatement("UPDATE docs SET taken_ms = ? WHERE id = ?").use { update ->
            forEachPage(db, "docs", arrayOf("id", "taken_at"), "taken_at IS NOT NULL") { id, row ->
                val at = com.opensolr.photos.ui.Actions.solrDateMillis(row[1]) ?: return@forEachPage
                update.clearBindings()
                update.bindLong(1, at)
                update.bindString(2, id)
                update.executeUpdateDelete()
            }
        }
    }

    /**
     * Fills the words table from the documents already stored, so an update costs one pass here
     * instead of a question to the index.
     */
    private fun backfillWords(db: SQLiteDatabase) {
        // Only the three word columns, never the documents themselves; the vector's model is filled
        // in by the step that follows. Nothing is deleted first either: the table was created empty
        // moments ago in this same step (Cip, 2026-09-18).
        db.compileStatement("INSERT OR IGNORE INTO doc_words (id, kind, word) VALUES (?, ?, ?)").use { insert ->
            forEachPage(db, "docs", arrayOf("id", "meaning", "tags_json", "persons_json"), null) { id, row ->
                fun write(kind: String, word: String) {
                    val clean = word.trim()
                    if (clean.isEmpty()) return
                    insert.clearBindings()
                    insert.bindString(1, id)
                    insert.bindString(2, kind)
                    insert.bindString(3, clean)
                    insert.executeInsert()
                }
                jsonWords(row[2]).forEach { write(WORD_TAG, it) }
                jsonWords(row[3]).forEach { write(WORD_PERSON, it) }
                row[1]?.split(',')?.forEach { write(WORD_MEANING, it) }
            }
        }
    }

    /**
     * Replaces the words stored for one photo.
     */
    private fun writeWords(db: SQLiteDatabase, id: String, tags: List<String>, persons: List<String>, meaning: String?) {
        db.delete("doc_words", "id = ?", arrayOf(id))
        // Named for what it does and NOT "put": inside ContentValues.apply, a local put(String,
        // String) shadows ContentValues.put and the row-writer calls itself for ever
        // (Cip, 2026-09-18: the app died with a StackOverflowError on start).
        fun writeWord(kind: String, word: String) {
            val clean = word.trim()
            if (clean.isEmpty()) return
            val values = ContentValues()
            values.put("id", id)
            values.put("kind", kind)
            values.put("word", clean)
            db.insertWithOnConflict("doc_words", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
        tags.forEach { writeWord(WORD_TAG, it) }
        persons.forEach { writeWord(WORD_PERSON, it) }
        meaning?.split(',')?.forEach { writeWord(WORD_MEANING, it) }
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
        /**
         * Which model made the photo's vector, or null when it has none. A column of its own so
         * that "which photos still owe the server a reading" is a question the database answers.
         */
        val embedModel: String? = null,
        /**
         * The md5 of the file itself as the index holds it. A column of its own so that the sync
         * can ask for it without fetching and taking apart the whole document (Cip, 2026-09-18).
         */
        val fileHash: String? = null,
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
            put("taken_ms", com.opensolr.photos.ui.Actions.solrDateMillis(doc.takenAt) ?: 0L)
            // Taken from the caller, which already has the document open, rather than parsed out of
            // the stored text here: writing a photo is the innermost step of tagging a whole
            // library, and it did that parse once per photo (Cip, 2026-09-18).
            if (doc.embedModel == null) putNull("embed_model") else put("embed_model", doc.embedModel)
            if (doc.fileHash == null) putNull("file_hash") else put("file_hash", doc.fileHash)
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("docs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            writeWords(writableDatabase, doc.id, doc.tags, doc.persons, doc.meaning)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * The md5 of the file itself as the index holds it for [id], or null when that photo was
     * written before the app started sending one. It is the last word on whether a file really
     * changed: the size and the time it was written are only the cheap signal that says which
     * photos are worth looking at (Cip, 2026-09-18).
     */
    fun docFileHash(id: String): String? {
        readableDatabase.query("docs", arrayOf("file_hash"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0).ifBlank { null } else null
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
        readableDatabase.query("docs", arrayOf("taken_ms"), "taken_ms > 0", null, null, null, "taken_ms DESC").use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    /**
     * The documents taken between two instants, newest first: how the grid is filled while
     * browsing, with nothing asked of the index (Cip, 2026-09-18). [limit] is the same ceiling the
     * index is asked for, so opening or ticking a group holds the same photos either way.
     */
    fun docsBetween(from: Long, to: Long, limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query(
            "docs", arrayOf("json"),
            "taken_ms BETWEEN ? AND ? AND json IS NOT NULL",
            arrayOf(from.toString(), to.toString()),
            null, null, "taken_ms DESC", limit.coerceAtLeast(1).toString(),
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
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
        // A photo can carry words kept from before and still have no vector of its own - written
        // while the plan had no AI. Both cases are a column the database can test.
        readableDatabase.query(
            "docs", arrayOf("id"),
            "meaning IS NULL OR meaning = '' OR embed_model IS NULL OR embed_model = ''",
            null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
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
        val where = StringBuilder("(ocr IS NOT NULL AND ocr != '')")
        val args = ArrayList<String>()
        needles.forEach {
            where.append(" OR lower(meaning) LIKE ?")
            args += "%$it%"
        }
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id"), where.toString(), args.toTypedArray(), null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
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
        writableDatabase.execSQL("UPDATE docs SET file_hash = ? WHERE id = ?", arrayOf<Any>(fileHash, id))
        // The stored document is what the grid draws a photo from, so it has to say the same as the
        // columns beside it. Only the document of this one photo is read, and only when the app has
        // really just written into the file.
        val json = readableDatabase.query("docs", arrayOf("json"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: return
        val updated = try {
            org.json.JSONObject(json).put("file_hash", fileHash).put("size_bytes", size).toString()
        } catch (e: Exception) {
            return
        }
        writableDatabase.execSQL("UPDATE docs SET json = ? WHERE id = ?", arrayOf<Any>(updated, id))
    }

    /** Forgets the phone's copy of [ids] - they are no longer in the index. */
    fun removeDocs(ids: Collection<String>) {
        deleteByIds("docs", ids)
        deleteByIds("doc_words", ids)
    }

    /**
     * Deletes [ids] from [table] a few hundred at a time - one statement per batch instead of one
     * per photo, which is what emptying a library of ten thousand used to cost (Cip, 2026-09-18).
     */
    private fun deleteByIds(table: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(DELETE_BATCH).forEach { batch ->
                val marks = batch.joinToString(",") { "?" }
                db.delete(table, "id IN ($marks)", batch.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Empties the phone's copy of the index (a reset, a new index, a rebuild). */
    fun clearDocs() {
        writableDatabase.delete("docs", null, null)
        writableDatabase.delete("doc_words", null, null)
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
        readableDatabase.rawQuery("SELECT kind, word, COUNT(*) FROM doc_words GROUP BY kind, word", null).use { c ->
            while (c.moveToNext()) {
                val into = when (c.getString(0)) {
                    WORD_TAG -> tags
                    WORD_PERSON -> persons
                    else -> meanings
                }
                into[c.getString(1)] = c.getInt(2)
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
            val args = batch.toTypedArray()
            readableDatabase.rawQuery(
                "SELECT kind, word, COUNT(*) FROM doc_words WHERE kind IN ('$WORD_TAG','$WORD_PERSON') AND id IN ($marks) GROUP BY kind, word",
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    val into = if (c.getString(0) == WORD_TAG) tags else persons
                    into[c.getString(1)] = (into[c.getString(1)] ?: 0) + c.getInt(2)
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
        val winner = winningAction(actionOf(id), kind)
        val values = ContentValues().apply {
            put("id", id)
            put("kind", winner)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Runs [work] as one transaction. A thousand photos tagged at once means thousands of small
     * writes, and one transaction turns them into one commit instead of one each
     * (Cip, 2026-09-18).
     */
    fun <T> inTransaction(work: () -> T): T {
        writableDatabase.beginTransaction()
        return try {
            val result = work()
            writableDatabase.setTransactionSuccessful()
            result
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Queues the same action for many photos at once, in one transaction and with what is already
     * queued read once rather than per photo.
     */
    fun queueActions(ids: Collection<String>, kind: String) {
        if (ids.isEmpty()) return
        val current = HashMap<String, String>()
        readableDatabase.query("actions", arrayOf("id", "kind"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) current[c.getString(0)] = c.getString(1)
        }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // One statement, prepared once and filled in per photo: building a row of values and
            // having the database work out the SQL again for each of ten thousand photos is most of
            // what tagging a whole library used to cost (Cip, 2026-09-18).
            db.compileStatement("INSERT OR REPLACE INTO actions (id, kind, at) VALUES (?, ?, ?)").use { statement ->
                ids.forEach { id ->
                    statement.clearBindings()
                    statement.bindString(1, id)
                    statement.bindString(2, winningAction(current[id], kind))
                    statement.bindLong(3, now)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Which of two things waiting for one photo wins: gone beats everything, and reading the
     * picture again beats a change of words.
     */
    private fun winningAction(current: String?, incoming: String): String = when {
        current == null -> incoming
        current == ACTION_DELETE || incoming == ACTION_DELETE -> ACTION_DELETE
        current == ACTION_INDEX || incoming == ACTION_INDEX -> ACTION_INDEX
        else -> incoming
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
        deleteByIds("actions", ids)
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
        embedModel = if (c.isNull(12)) null else c.getString(12),
        fileHash = if (c.isNull(13)) null else c.getString(13),
    )

    /**
     * What the owner wrote about a photo: their tags, and their own wording of what the
     * photo shows when they changed it (null = keep what Opensolr saw).
     *
     */
    data class Edits(val tags: List<String>, val meaning: String?, val persons: List<String>? = null)

    /**
     * The photos this phone has edits for, all at once.
     */
    fun editedIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.query("edits", arrayOf("id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

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
        private const val VERSION = 13

        /**
         * The one handle on this database for the whole app.
         *
         * Every screen, every sync and every edit opened one of its own and none of them was ever
         * closed, so each run left a connection behind for the collector to complain about and the
         * writes of one had to wait on the locks of another (Cip, 2026-09-18).
         */
        @Volatile private var shared: PhotoCache? = null

        /** The shared handle, made on first use. */
        fun of(context: Context): PhotoCache = shared ?: synchronized(this) {
            shared ?: PhotoCache(context.applicationContext).also { shared = it }
        }

        private const val DOCS_TABLE = "CREATE TABLE IF NOT EXISTS docs (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, indexed_at INTEGER NOT NULL, taken_at TEXT, tags_json TEXT, persons_json TEXT, meaning TEXT, ocr TEXT, city TEXT, country TEXT, json TEXT, modified INTEGER NOT NULL DEFAULT 0, taken_ms INTEGER NOT NULL DEFAULT 0, embed_model TEXT, file_hash TEXT)"
        /** When each photo was taken, as a number: the grid asks for a stretch of time constantly. */
        private const val DOCS_TAKEN_INDEX = "CREATE INDEX IF NOT EXISTS docs_taken_ms ON docs (taken_ms)"

        /** "Which photos were written before this moment" - asked by Re-read at every sync. */
        private const val DOCS_INDEXED_INDEX = "CREATE INDEX IF NOT EXISTS docs_indexed_at ON docs (indexed_at)"

        /**
         * The three things a sync compares the phone's folders against, together in one index. The
         * whole document sits in the same row, after them, so reading them off the rows meant
         * pulling the entire library - tens of megabytes - through the database for a question that
         * needs a few bytes per photo. Answered from this index, the rows are never touched
         * (Cip, 2026-09-18).
         */
        private const val DOCS_STAMP_INDEX = "CREATE INDEX IF NOT EXISTS docs_stamp ON docs (id, size_bytes, modified)"

        /** "What is waiting to be indexed, oldest first" - asked at every sync and after every edit. */
        private const val ACTIONS_KIND_INDEX = "CREATE INDEX IF NOT EXISTS actions_kind_at ON actions (kind, at)"

        /**
         * The photos that still owe the server a reading, and only those. A partial index: it holds
         * a row for a photo with no words and nothing for the rest, so the question "which photos
         * have none" is answered from a list that is usually empty instead of a walk over the whole
         * library - and that walk had to pull every stored document off its overflow pages to reach
         * the two columns it was testing (Cip, 2026-09-18).
         */
        private const val DOCS_WORDLESS_INDEX = "CREATE INDEX IF NOT EXISTS docs_wordless ON docs (id) WHERE meaning IS NULL OR meaning = '' OR embed_model IS NULL OR embed_model = ''"

        /**
         * Every word of every photo, one row each: the owner's tags, the names of the people, and
         * the words the photos were read into. The suggestion lists and the "already on these
         * photos" counts are a GROUP BY over this, instead of reading and taking apart the whole
         * library on every keystroke (Cip, 2026-09-18).
         */
        private const val WORDS_TABLE = "CREATE TABLE IF NOT EXISTS doc_words (id TEXT NOT NULL, kind TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY (id, kind, word))"
        private const val WORDS_INDEX = "CREATE INDEX IF NOT EXISTS doc_words_kind ON doc_words (kind, word)"

        /** The three kinds of word a photo carries. */
        const val WORD_TAG = "tag"
        const val WORD_PERSON = "person"
        const val WORD_MEANING = "meaning"

        private const val ACTIONS_TABLE = "CREATE TABLE IF NOT EXISTS actions (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL)"

        /** A photo whose picture has to be read again: new, or its file changed. */
        const val ACTION_INDEX = "index"

        /** Only the owner's words changed: no picture is sent, just the text. */
        const val ACTION_WORDS = "words"

        /** The photo is gone from the phone, so it goes from the index. */
        const val ACTION_DELETE = "delete"

        private val DOC_COLUMNS = arrayOf("id", "size_bytes", "indexed_at", "taken_at", "tags_json", "persons_json", "meaning", "ocr", "city", "country", "json", "modified", "embed_model", "file_hash")

        /** How many rows an update converts at once, so no library is ever held in memory whole. */
        private const val MIGRATION_PAGE = 500

        /** How many photos one delete names. SQLite takes 999 bound values, so this stays under it. */
        private const val DELETE_BATCH = 400
        private val EDIT_COLUMNS = arrayOf("tags_json", "meaning", "persons_json")
        private const val EDITS_TABLE = "CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL, persons_json TEXT, pending INTEGER NOT NULL DEFAULT 0, tags_mode TEXT, persons_mode TEXT)"
        private const val SKIPPED_TABLE = "CREATE TABLE IF NOT EXISTS skipped (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, media_id INTEGER NOT NULL, path TEXT NOT NULL, folder TEXT NOT NULL, file_name TEXT NOT NULL, mime TEXT NOT NULL, taken_ms INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)"
        private const val WORD_RETRIES_TABLE = "CREATE TABLE IF NOT EXISTS word_retries (id TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, next_at INTEGER NOT NULL)"
    }
}
