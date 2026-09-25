package com.opensolr.photos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhotoCache private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    data class Entry(val docJson: String, val vector: FloatArray?)

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
        db.execSQL(DOCS_CAMERA_INDEX)
        db.execSQL(DOCS_PLACE_INDEX)
        db.execSQL(WORDS_TABLE)
        db.execSQL(WORDS_INDEX)
        db.execSQL(ACTIONS_TABLE)
        db.execSQL(ACTIONS_KIND_INDEX)
        db.execSQL(WORD_RETRIES_TABLE)
        db.execSQL(SKIPPED_TABLE)
        db.execSQL(SET_PLACES_TABLE)
        db.execSQL(INCOMING_TABLE)
        db.execSQL(INCOMING_TAKEN_INDEX)
    }

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

            db.execSQL(DOCS_TABLE)
            db.execSQL(ACTIONS_TABLE)
        }
        if (oldVersion in 8..8) {

            db.execSQL("DROP TABLE IF EXISTS docs")
            db.execSQL(DOCS_TABLE)
        }
        if (oldVersion < 10) {
            addColumnIfMissing(db, "docs", "modified", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "docs", "json", "TEXT")
        }
        if (oldVersion < 11) {

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

            addColumnIfMissing(db, "docs", "file_hash", "TEXT")
            db.execSQL(DOCS_STAMP_INDEX)
            db.execSQL(ACTIONS_KIND_INDEX)
            backfillFileHash(db)

            db.execSQL(DOCS_WORDLESS_INDEX)
        }
        if (oldVersion < 14) {
            db.execSQL(SET_PLACES_TABLE)
        }
        if (oldVersion < 15) {
            addColumnIfMissing(db, "set_places", "synced", "INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 16) {

            addColumnIfMissing(db, "docs", "region", "TEXT")
            backfillRegion(db)
        }
        if (oldVersion < 17) {

            addColumnIfMissing(db, "docs", "folder", "TEXT")
            addColumnIfMissing(db, "docs", "camera", "TEXT")
            backfillFolderAndCamera(db)
        }
        if (oldVersion in 17..17) {

            backfillFolderAndCamera(db)
        }
        if (oldVersion < 19) {
            addColumnIfMissing(db, "docs", "camera_model", "TEXT")
            db.execSQL(DOCS_CAMERA_INDEX)
            db.execSQL(DOCS_PLACE_INDEX)
            backfillLabelsAndModel(db)
        }
        if (oldVersion < 20) {
            db.execSQL(INCOMING_TABLE)
            db.execSQL(INCOMING_TAKEN_INDEX)
        }
        if (oldVersion < 21) {
            // places the app used to guess for new photos: kept for the index, never written into the files
            db.execSQL("UPDATE set_places SET written = 1 WHERE owner = 0")
        }
    }

    private fun backfillLabelsAndModel(db: SQLiteDatabase) {
        db.compileStatement("INSERT OR IGNORE INTO doc_words (id, kind, word) VALUES (?, '$WORD_LABEL', ?)").use { insert ->
            db.compileStatement("UPDATE docs SET camera_model = ? WHERE id = ?").use { update ->
                forEachPage(db, "docs", arrayOf("id", "json"), "json IS NOT NULL") { id, row ->
                    val doc = try {
                        org.json.JSONObject(row[1] ?: return@forEachPage)
                    } catch (e: Exception) {
                        return@forEachPage
                    }
                    labelsOf(doc).forEach { word ->
                        insert.clearBindings()
                        insert.bindString(1, id)
                        insert.bindString(2, word)
                        insert.executeInsert()
                    }
                    val model = doc.optString("camera_model").trim().ifBlank { null } ?: return@forEachPage
                    update.clearBindings()
                    update.bindString(1, model)
                    update.bindString(2, id)
                    update.executeUpdateDelete()
                }
            }
        }
    }

    private fun backfillFolderAndCamera(db: SQLiteDatabase) {
        db.compileStatement("UPDATE docs SET folder = ?, camera = ? WHERE id = ?").use { update ->
            forEachPage(db, "docs", arrayOf("id", "json"), "json IS NOT NULL") { id, row ->
                val doc = try {
                    org.json.JSONObject(row[1] ?: return@forEachPage)
                } catch (e: Exception) {
                    return@forEachPage
                }
                val folder: String? = com.opensolr.photos.search.SearchFilters.folderPath(doc.optString("folder"))
                val camera: String? = com.opensolr.photos.search.SearchFilters.cameraName(doc.optString("camera_make"), doc.optString("camera_model"))
                if (folder == null && camera == null) return@forEachPage
                update.clearBindings()
                if (folder == null) update.bindNull(1) else update.bindString(1, folder)
                if (camera == null) update.bindNull(2) else update.bindString(2, camera)
                update.bindString(3, id)
                update.executeUpdateDelete()
            }
        }
    }

    private fun backfillRegion(db: SQLiteDatabase) {
        db.compileStatement("UPDATE docs SET region = ? WHERE id = ?").use { update ->
            forEachPage(db, "docs", arrayOf("id", "json"), "json IS NOT NULL") { id, row ->
                val region = try {
                    org.json.JSONObject(row[1] ?: return@forEachPage).optString("region").trim().ifBlank { null }
                } catch (e: Exception) {
                    null
                } ?: return@forEachPage
                update.clearBindings()
                update.bindString(1, region)
                update.bindString(2, id)
                update.executeUpdateDelete()
            }
        }
    }

    fun groupingRows(): List<com.opensolr.photos.search.SearchRepository.GroupedHit> {
        val out = ArrayList<com.opensolr.photos.search.SearchRepository.GroupedHit>()
        fun list(text: String?): List<String> = if (text.isNullOrEmpty() || text == "[]") emptyList() else try {
            org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }
        } catch (e: Exception) {
            emptyList()
        }
        readableDatabase.query("docs", arrayOf("id", "taken_ms", "city", "region", "country", "persons_json", "tags_json", "folder", "camera"), null, null, null, null, "taken_ms DESC").use { c ->
            while (c.moveToNext()) {
                out += com.opensolr.photos.search.SearchRepository.GroupedHit(
                    id = c.getString(0),
                    takenMs = c.getLong(1).takeIf { it > 0 },
                    city = c.getString(2)?.trim()?.ifBlank { null },
                    region = c.getString(3)?.trim()?.ifBlank { null },
                    country = c.getString(4)?.trim()?.ifBlank { null },
                    persons = list(c.getString(5)),
                    tags = list(c.getString(6)),
                    folder = c.getString(7)?.trim()?.ifBlank { null },
                    camera = c.getString(8)?.trim()?.ifBlank { null },
                )
            }
        }
        return out
    }

    private fun backfillFileHash(db: SQLiteDatabase) {
        db.compileStatement("UPDATE docs SET file_hash = ?, embed_model = coalesce(?, embed_model) WHERE id = ?").use { update ->
            forEachPage(db, "docs", arrayOf("id", "json"), "json IS NOT NULL") { id, row ->
                val json = row[1] ?: return@forEachPage

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

    private fun backfillWords(db: SQLiteDatabase) {

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

    private fun writeWords(db: SQLiteDatabase, id: String, tags: List<String>, persons: List<String>, meaning: String?, labels: List<String>?) {
        if (labels == null) {
            db.delete("doc_words", "id = ? AND kind != ?", arrayOf(id, WORD_LABEL))
        } else {
            db.delete("doc_words", "id = ?", arrayOf(id))
        }

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
        labels?.forEach { writeWord(WORD_LABEL, it) }
    }

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

            }
        }
    }

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

    fun skippedSizes(): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.query("skipped", arrayOf("id", "size_bytes"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getLong(1)
        }
        return out
    }

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

    fun skippedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM skipped", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearSkipped(id: String) {
        writableDatabase.delete("skipped", "id = ?", arrayOf(id))
    }

    fun keepSkippedOnly(onPhone: Set<String>) {
        val gone = skippedSizes().keys.filter { it !in onPhone }
        gone.forEach { clearSkipped(it) }
    }

    fun wordRetriesWaiting(now: Long): Set<String> {
        val out = HashSet<String>()
        readableDatabase.query("word_retries", arrayOf("id"), "next_at > ?", arrayOf(now.toString()), null, null, null).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getString(0)
        }
        return out
    }

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

    fun clearWordRetry(id: String) {
        writableDatabase.delete("word_retries", "id = ?", arrayOf(id))
    }

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

        val json: String? = null,

        val modified: Long = 0,

        val embedModel: String? = null,

        val fileHash: String? = null,

        val region: String? = null,

        val folder: String? = null,

        val camera: String? = null,

        val cameraModel: String? = null,

        val labels: List<String>? = null,
    )

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
            if (doc.region == null) putNull("region") else put("region", doc.region)
            if (doc.folder == null) putNull("folder") else put("folder", doc.folder)
            if (doc.camera == null) putNull("camera") else put("camera", doc.camera)
            if (doc.cameraModel == null) putNull("camera_model") else put("camera_model", doc.cameraModel)
            if (doc.json == null) putNull("json") else put("json", doc.json)
            put("modified", doc.modified)
            put("taken_ms", com.opensolr.photos.ui.Actions.solrDateMillis(doc.takenAt) ?: 0L)

            if (doc.embedModel == null) putNull("embed_model") else put("embed_model", doc.embedModel)
            if (doc.fileHash == null) putNull("file_hash") else put("file_hash", doc.fileHash)
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("docs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            writeWords(writableDatabase, doc.id, doc.tags, doc.persons, doc.meaning, doc.labels)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun docFileHash(id: String): String? {
        readableDatabase.query("docs", arrayOf("file_hash"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0).ifBlank { null } else null
        }
    }

    fun doc(id: String): Doc? {
        readableDatabase.query("docs", DOC_COLUMNS, "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) readDoc(c) else null
        }
    }

    fun docSizes(): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.query("docs", arrayOf("id", "size_bytes", "modified"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) to c.getLong(2)
        }
        return out
    }

    // The browsing list: what is indexed plus the photos still on their way up.
    fun takenTimes(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT taken_ms FROM docs WHERE taken_ms > 0 UNION ALL $INCOMING_TAKEN ORDER BY taken_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun docsBetween(from: Long, to: Long, limit: Int): List<String> {
        val rows = ArrayList<Pair<Long, String>>()
        readableDatabase.query(
            "docs", arrayOf("taken_ms", "json"),
            "taken_ms BETWEEN ? AND ? AND json IS NOT NULL",
            arrayOf(from.toString(), to.toString()),
            null, null, "taken_ms DESC", limit.coerceAtLeast(1).toString(),
        ).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        readableDatabase.rawQuery(
            "SELECT $INCOMING_COLUMNS FROM incoming WHERE taken_ms BETWEEN ? AND ? AND id NOT IN (SELECT id FROM docs) ORDER BY taken_ms DESC LIMIT ?",
            arrayOf(from.toString(), to.toString(), limit.coerceAtLeast(1).toString()),
        ).use { c ->
            while (c.moveToNext()) rows += c.getLong(7) to incomingJson(c)
        }
        if (rows.size == 1) return listOf(rows[0].second)
        return rows.sortedByDescending { it.first }.take(limit.coerceAtLeast(1)).map { it.second }
    }

    fun docsByIds(ids: Collection<String>): List<String> {
        val out = ArrayList<String>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT json FROM docs WHERE id IN ($marks) AND json IS NOT NULL",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
            readableDatabase.rawQuery(
                "SELECT $INCOMING_COLUMNS FROM incoming WHERE id IN ($marks) AND id NOT IN (SELECT id FROM docs)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out += incomingJson(c)
            }
        }
        return out
    }

    fun docCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM docs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun docIdsAmong(ids: Collection<String>): Set<String> {
        val out = HashSet<String>()
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT id FROM docs WHERE id IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
        }
        return out
    }

    fun browseCount(): Int =
        readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM docs) + (SELECT COUNT(*) FROM incoming WHERE id NOT IN (SELECT id FROM docs))", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun putIncoming(photos: Collection<com.opensolr.photos.media.LocalPhoto>, takenMs: (com.opensolr.photos.media.LocalPhoto) -> Long) {
        if (photos.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR IGNORE INTO incoming (id, media_id, path, folder, file_name, mime, size_bytes, taken_ms, width, height, at) VALUES (?,?,?,?,?,?,?,?,?,?,?)").use { st ->
                val now = System.currentTimeMillis()
                photos.forEach { p ->
                    st.clearBindings()
                    st.bindString(1, p.id)
                    st.bindLong(2, p.mediaId)
                    st.bindString(3, p.absolutePath)
                    st.bindString(4, p.folder)
                    st.bindString(5, p.fileName)
                    st.bindString(6, p.mime)
                    st.bindLong(7, p.sizeBytes)
                    st.bindLong(8, takenMs(p))
                    st.bindLong(9, p.width.toLong())
                    st.bindLong(10, p.height.toLong())
                    st.bindLong(11, now)
                    st.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun incomingIds(): Set<String> = incomingMedia().keys

    fun incomingMedia(): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.query("incoming", arrayOf("id", "media_id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    fun removeIncoming(ids: Collection<String>) = deleteByIds("incoming", ids)

    // What is indexed by now, or no longer on the phone, is not on its way any more.
    fun pruneIncoming(onPhone: Set<String>?) {
        writableDatabase.execSQL("DELETE FROM incoming WHERE id IN (SELECT id FROM docs)")
        if (onPhone != null) removeIncoming(incomingIds().filter { it !in onPhone })
    }

    private fun incomingJson(c: android.database.Cursor): String {
        val taken = c.getLong(7)
        return org.json.JSONObject()
            .put("id", c.getString(0))
            .put("media_id", c.getLong(1))
            .put("path", c.getString(2))
            .put("folder", c.getString(3))
            .put("file_name", c.getString(4))
            .put("mime", c.getString(5))
            .put("size_bytes", c.getLong(6))
            .apply { if (taken > 0) put("taken_at", isoUtc(taken)) }
            .put("width", c.getInt(8))
            .put("height", c.getInt(9))
            .put("pending", true)
            .toString()
    }

    private fun isoUtc(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(millis)

    fun docsWithoutWords(): List<String> {
        val out = ArrayList<String>()

        readableDatabase.query(
            "docs", arrayOf("id"),
            "meaning IS NULL OR meaning = '' OR embed_model IS NULL OR embed_model = ''",
            null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** The newest photos the phone holds that carry a place: what the map draws by itself. */
    fun locatedDocs(limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT json FROM docs WHERE json LIKE '%\"location\"%' ORDER BY taken_ms DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) if (!c.isNull(0)) out += c.getString(0)
        }
        return out
    }

    fun docsWithText(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id"), "ocr IS NOT NULL AND ocr != ''", null, null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun docsIndexedBefore(before: Long): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("docs", arrayOf("id"), "indexed_at < ?", arrayOf(before.toString()), null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun updateDocSize(id: String, size: Long, modified: Long, fileHash: String? = null) {
        if (size <= 0) return
        writableDatabase.execSQL("UPDATE docs SET size_bytes = ?, modified = ? WHERE id = ?", arrayOf<Any>(size, modified, id))

        if (fileHash == null) return
        writableDatabase.execSQL("UPDATE docs SET file_hash = ? WHERE id = ?", arrayOf<Any>(fileHash, id))

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

    fun removeDocs(ids: Collection<String>) {
        deleteByIds("docs", ids)
        deleteByIds("doc_words", ids)
        deleteByIds("set_places", ids)
    }

    data class SetPlace(val id: String, val lat: Double, val lon: Double, val owner: Boolean, val written: Boolean, val synced: Boolean = true)

    fun putSetPlace(place: SetPlace) {
        val values = ContentValues().apply {
            put("id", place.id)
            put("lat", place.lat)
            put("lon", place.lon)
            put("owner", if (place.owner) 1 else 0)
            put("written", if (place.written) 1 else 0)
            put("synced", if (place.synced) 1 else 0)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("set_places", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setPlaces(ids: Collection<String>): Map<String, SetPlace> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, SetPlace>()
        ids.chunked(DELETE_BATCH).forEach { batch ->
            val marks = batch.joinToString(",") { "?" }
            readableDatabase.query("set_places", SET_PLACE_COLUMNS, "id IN ($marks)", batch.toTypedArray(), null, null, null).use { c ->
                while (c.moveToNext()) setPlaceOf(c).let { out[it.id] = it }
            }
        }
        return out
    }

    fun unwrittenPlaces(): List<SetPlace> =
        readableDatabase.query("set_places", SET_PLACE_COLUMNS, "written = 0", null, null, null, "at").use { c ->
            val out = ArrayList<SetPlace>(c.count)
            while (c.moveToNext()) out += setPlaceOf(c)
            out
        }

    fun markPlacesWritten(ids: Collection<String>) = markPlaces("written", ids)

    fun markPlacesSynced(ids: Collection<String>) = markPlaces("synced", ids)

    private fun markPlaces(column: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(DELETE_BATCH).forEach { batch ->
                val marks = batch.joinToString(",") { "?" }
                db.execSQL("UPDATE set_places SET $column = 1 WHERE id IN ($marks)", batch.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun setPlaceOf(c: android.database.Cursor) =
        SetPlace(c.getString(0), c.getDouble(1), c.getDouble(2), c.getInt(3) == 1, c.getInt(4) == 1, c.getInt(5) == 1)

    fun positionsBetween(from: Long, to: Long): List<Triple<Long, Double, Double>> {
        val out = ArrayList<Triple<Long, Double, Double>>()
        readableDatabase.rawQuery(
            "SELECT d.taken_ms, d.json FROM docs d LEFT JOIN set_places p ON p.id = d.id " +
                "WHERE d.taken_ms BETWEEN ? AND ? AND d.json LIKE '%\"location\"%' AND (p.id IS NULL OR p.owner = 1)",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val position = try {
                    com.opensolr.photos.search.parseLatLon(org.json.JSONObject(c.getString(1)).optString("location"))
                } catch (e: Exception) {
                    null
                } ?: continue
                out += Triple(c.getLong(0), position.first, position.second)
            }
        }
        return out
    }

    fun moveDocPlace(id: String, lat: Double, lon: Double) {
        val json = readableDatabase.query("docs", arrayOf("json"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: return
        val updated = try {
            org.json.JSONObject(json).apply {
                put("location", String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon))
                put("has_location", true)
                PLACE_NAME_FIELDS.forEach { remove(it) }
                remove("altitude")
            }.toString()
        } catch (e: Exception) {
            return
        }
        writableDatabase.execSQL("UPDATE docs SET json = ?, city = NULL, region = NULL, country = NULL WHERE id = ?", arrayOf<Any>(updated, id))
    }

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

    fun clearDocs() {
        writableDatabase.delete("docs", null, null)
        writableDatabase.delete("incoming", null, null)
        writableDatabase.delete("doc_words", null, null)
        writableDatabase.delete("actions", null, null)
    }

    fun wordCounts(): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
        val tags = HashMap<String, Int>()
        val persons = HashMap<String, Int>()
        val meanings = HashMap<String, Int>()
        // the third kind is the model's labels: meaning is one sentence now, never a list of words
        readableDatabase.rawQuery("SELECT kind, word, COUNT(*) FROM doc_words WHERE kind IN ('$WORD_TAG','$WORD_PERSON','$WORD_LABEL') GROUP BY kind, word", null).use { c ->
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

    fun libraryStats(): LibraryStats {
        val db = readableDatabase
        var total = 0
        var bytes = 0L
        var tagged = 0
        var withPeople = 0
        var withPlace = 0
        var withText = 0
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0), " +
                "COALESCE(SUM(tags_json IS NOT NULL AND tags_json NOT IN ('', '[]')), 0), " +
                "COALESCE(SUM(persons_json IS NOT NULL AND persons_json NOT IN ('', '[]')), 0), " +
                "COALESCE(SUM(COALESCE(city, '') != '' OR COALESCE(country, '') != ''), 0), " +
                "COALESCE(SUM(COALESCE(ocr, '') != ''), 0) FROM docs",
            null,
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                bytes = c.getLong(1)
                tagged = c.getInt(2)
                withPeople = c.getInt(3)
                withPlace = c.getInt(4)
                withText = c.getInt(5)
            }
        }
        val unread = db.rawQuery(
            "SELECT COUNT(*) FROM docs WHERE meaning IS NULL OR meaning = '' OR embed_model IS NULL OR embed_model = ''",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val years = HashMap<String, Int>()
        val months = IntArray(12)
        val weekdays = IntArray(7)
        val hours = IntArray(24)
        var dated = 0
        db.rawQuery(
            "SELECT strftime('%Y', taken_ms / 1000, 'unixepoch'), " +
                "strftime('%m', taken_ms / 1000, 'unixepoch', 'localtime'), " +
                "strftime('%w', taken_ms / 1000, 'unixepoch', 'localtime'), " +
                "strftime('%H', taken_ms / 1000, 'unixepoch', 'localtime'), COUNT(*) " +
                "FROM docs WHERE taken_ms != 0 GROUP BY 1, 2, 3, 4",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(4)
                dated += n
                c.getString(0)?.let { years[it] = (years[it] ?: 0) + n }
                c.getString(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { months[it - 1] += n }
                c.getString(2)?.toIntOrNull()?.takeIf { it in 0..6 }?.let { weekdays[it] += n }
                c.getString(3)?.toIntOrNull()?.takeIf { it in 0..23 }?.let { hours[it] += n }
            }
        }

        val people = ArrayList<StatRow>()
        val tags = ArrayList<StatRow>()
        val things = ArrayList<StatRow>()
        db.rawQuery(
            "SELECT kind, word, COUNT(*) FROM doc_words WHERE kind IN ('$WORD_TAG', '$WORD_PERSON', '$WORD_LABEL') GROUP BY kind, word",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val word = c.getString(1)
                val n = c.getInt(2)
                when (c.getString(0)) {
                    WORD_PERSON -> people += StatRow(word, n, "persons_ss", word)
                    WORD_TAG -> tags += StatRow(word, n, "custom_tags", word)
                    WORD_LABEL -> things += StatRow(word, n, "labels", word)
                }
            }
        }

        val countries = HashMap<String, Int>()
        val cities = ArrayList<StatRow>()
        db.rawQuery(
            "SELECT country, city, COUNT(*) FROM docs WHERE COALESCE(country, '') != '' OR COALESCE(city, '') != '' GROUP BY country, city",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val country = c.getString(0)?.trim()?.ifBlank { null }
                val city = c.getString(1)?.trim()?.ifBlank { null }
                val n = c.getInt(2)
                if (country != null) countries[country] = (countries[country] ?: 0) + n
                if (city != null) cities += StatRow(if (country != null) "$city, $country" else city, n, "city", city)
            }
        }

        val cameras = ArrayList<StatRow>()
        db.rawQuery(
            "SELECT camera_model, camera, COUNT(*) FROM docs WHERE COALESCE(camera_model, '') != '' GROUP BY camera_model, camera",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val model = c.getString(0)
                cameras += StatRow(c.getString(1)?.ifBlank { null } ?: model, c.getInt(2), "camera_model", model)
            }
        }

        val byCount = compareByDescending<StatRow> { it.count }.thenBy { it.label.lowercase() }
        return LibraryStats(
            total = total,
            bytes = bytes,
            tagged = tagged,
            withPeople = withPeople,
            withPlace = withPlace,
            withText = withText,
            unread = unread,
            undated = (total - dated).coerceAtLeast(0),
            years = years.entries.sortedByDescending { it.key }.map { StatRow(it.key, it.value, "year", it.key) },
            months = months,
            weekdays = weekdays,
            hours = hours,
            people = people.sortedWith(byCount),
            tags = tags.sortedWith(byCount),
            things = things.sortedWith(byCount),
            countries = countries.entries.map { StatRow(it.key, it.value, "country", it.key) }.sortedWith(byCount),
            cities = cities.sortedWith(byCount),
            cameras = cameras.sortedWith(byCount),
        )
    }

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

    fun queueAction(id: String, kind: String) {
        val winner = winningAction(actionOf(id), kind)
        val values = ContentValues().apply {
            put("id", id)
            put("kind", winner)
            put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

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

    private fun winningAction(current: String?, incoming: String): String = when {
        current == null -> incoming
        current == ACTION_DELETE || incoming == ACTION_DELETE -> ACTION_DELETE
        current == ACTION_INDEX || incoming == ACTION_INDEX -> ACTION_INDEX
        else -> incoming
    }

    fun actionOf(id: String): String? {
        readableDatabase.query("actions", arrayOf("kind"), "id = ?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun actions(kind: String, limit: Int = 100000): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query("actions", arrayOf("id"), "kind = ?", arrayOf(kind), null, null, "at ASC", limit.toString()).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun actionCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM actions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearActions(ids: Collection<String>) {
        deleteByIds("actions", ids)
    }

    private fun labelsOf(doc: org.json.JSONObject): List<String> =
        doc.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    private fun jsonWords(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

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
        region = if (c.isNull(14)) null else c.getString(14),
        folder = if (c.isNull(15)) null else c.getString(15),
        camera = if (c.isNull(16)) null else c.getString(16),
        cameraModel = if (c.isNull(17)) null else c.getString(17),
    )

    data class Edits(val tags: List<String>, val meaning: String?, val persons: List<String>? = null)

    fun editedIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.query("edits", arrayOf("id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun getEdits(id: String): Edits? {
        readableDatabase.query("edits", EDIT_COLUMNS, "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return null
            return readEdits(cursor)
        }
    }

    fun putEdits(id: String, edits: Edits) {
        // The row is kept even when everything in it is empty: an empty tag list is the owner
        // saying the photo has no tags, and dropping the row would let the old ones come back
        // from the index at the next write.
        val values = ContentValues().apply {
            put("id", id)
            put("tags_json", org.json.JSONArray(edits.tags).toString())
            if (edits.meaning == null) putNull("meaning") else put("meaning", edits.meaning)
            if (edits.persons == null) putNull("persons_json") else put("persons_json", org.json.JSONArray(edits.persons).toString())
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("edits", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readEdits(cursor: android.database.Cursor, offset: Int = 0): Edits {
        val array = org.json.JSONArray(cursor.getString(offset))
        val tags = (0 until array.length()).map { array.getString(it) }
        val persons = if (cursor.isNull(offset + 2)) null else org.json.JSONArray(cursor.getString(offset + 2)).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Edits(tags, if (cursor.isNull(offset + 1)) null else cursor.getString(offset + 1), persons)
    }

    fun has(id: String): Boolean {
        readableDatabase.query("photos", arrayOf("id"), "id = ?", arrayOf(id), null, null, null, "1").use { return it.moveToFirst() }
    }

    fun getPlace(key: String): String? {
        readableDatabase.query("places", arrayOf("place_json"), "key = ?", arrayOf(key), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun putPlace(key: String, placeJson: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("place_json", placeJson)
        }
        writableDatabase.insertWithOnConflict("places", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

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

    fun allStamps(): Map<String, Stamp> {
        val out = HashMap<String, Stamp>()
        readableDatabase.query("photos", arrayOf("id", "size_bytes", "modified"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = Stamp(cursor.getLong(1), cursor.getLong(2))
        }
        return out
    }

    data class Stamp(val sizeBytes: Long, val modified: Long)

    fun getLatest(id: String): Entry? {
        readableDatabase.query("photos", arrayOf("doc_json", "vector"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return null
            val blob = if (cursor.isNull(1)) null else cursor.getBlob(1)
            return Entry(cursor.getString(0), blob?.let { toFloats(it) })
        }
    }

    fun isStale(id: String, sizeBytes: Long, modified: Long): Boolean {
        readableDatabase.query("photos", arrayOf("size_bytes", "modified"), "id = ?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return false
            return cursor.getLong(0) != sizeBytes || cursor.getLong(1) != modified
        }
    }

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

    fun clear() {

        writableDatabase.delete("photos", null, null)
        writableDatabase.delete("places", null, null)
        writableDatabase.delete("word_retries", null, null)
        writableDatabase.delete("skipped", null, null)
        writableDatabase.delete("incoming", null, null)
    }

    private fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun toFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    companion object {
        private const val NAME = "photo_cache.db"
        private const val VERSION = 21

        @Volatile private var shared: PhotoCache? = null

        fun of(context: Context): PhotoCache = shared ?: synchronized(this) {
            shared ?: PhotoCache(context.applicationContext).also { shared = it }
        }

        private const val SET_PLACES_TABLE = "CREATE TABLE IF NOT EXISTS set_places (id TEXT PRIMARY KEY NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, owner INTEGER NOT NULL, written INTEGER NOT NULL, at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 1)"
        private val SET_PLACE_COLUMNS = arrayOf("id", "lat", "lon", "owner", "written", "synced")

        private val PLACE_NAME_FIELDS = listOf("city", "region", "province", "community", "country", "country_code")

        private const val DOCS_TABLE = "CREATE TABLE IF NOT EXISTS docs (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, indexed_at INTEGER NOT NULL, taken_at TEXT, tags_json TEXT, persons_json TEXT, meaning TEXT, ocr TEXT, city TEXT, country TEXT, json TEXT, modified INTEGER NOT NULL DEFAULT 0, taken_ms INTEGER NOT NULL DEFAULT 0, embed_model TEXT, file_hash TEXT, region TEXT, folder TEXT, camera TEXT, camera_model TEXT)"

        private const val DOCS_TAKEN_INDEX = "CREATE INDEX IF NOT EXISTS docs_taken_ms ON docs (taken_ms)"

        private const val DOCS_INDEXED_INDEX = "CREATE INDEX IF NOT EXISTS docs_indexed_at ON docs (indexed_at)"

        private const val DOCS_STAMP_INDEX = "CREATE INDEX IF NOT EXISTS docs_stamp ON docs (id, size_bytes, modified)"

        private const val ACTIONS_KIND_INDEX = "CREATE INDEX IF NOT EXISTS actions_kind_at ON actions (kind, at)"

        private const val DOCS_WORDLESS_INDEX = "CREATE INDEX IF NOT EXISTS docs_wordless ON docs (id) WHERE meaning IS NULL OR meaning = '' OR embed_model IS NULL OR embed_model = ''"

        private const val WORDS_TABLE = "CREATE TABLE IF NOT EXISTS doc_words (id TEXT NOT NULL, kind TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY (id, kind, word))"
        private const val WORDS_INDEX = "CREATE INDEX IF NOT EXISTS doc_words_kind ON doc_words (kind, word)"

        const val WORD_TAG = "tag"
        const val WORD_PERSON = "person"
        const val WORD_MEANING = "meaning"

        const val WORD_LABEL = "label"

        private const val DOCS_CAMERA_INDEX = "CREATE INDEX IF NOT EXISTS docs_camera ON docs (camera_model, camera)"

        private const val DOCS_PLACE_INDEX = "CREATE INDEX IF NOT EXISTS docs_place ON docs (country, city)"

        private const val ACTIONS_TABLE = "CREATE TABLE IF NOT EXISTS actions (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL)"

        const val ACTION_INDEX = "index"

        const val ACTION_WORDS = "words"

        const val ACTION_DELETE = "delete"

        private val DOC_COLUMNS = arrayOf("id", "size_bytes", "indexed_at", "taken_at", "tags_json", "persons_json", "meaning", "ocr", "city", "country", "json", "modified", "embed_model", "file_hash", "region", "folder", "camera", "camera_model")

        private const val MIGRATION_PAGE = 500

        private const val DELETE_BATCH = 400
        private val EDIT_COLUMNS = arrayOf("tags_json", "meaning", "persons_json")
        private const val EDITS_TABLE = "CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY NOT NULL, tags_json TEXT NOT NULL, meaning TEXT, updated INTEGER NOT NULL, persons_json TEXT, pending INTEGER NOT NULL DEFAULT 0, tags_mode TEXT, persons_mode TEXT)"
        private const val SKIPPED_TABLE = "CREATE TABLE IF NOT EXISTS skipped (id TEXT PRIMARY KEY NOT NULL, size_bytes INTEGER NOT NULL, media_id INTEGER NOT NULL, path TEXT NOT NULL, folder TEXT NOT NULL, file_name TEXT NOT NULL, mime TEXT NOT NULL, taken_ms INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)"
        private const val INCOMING_TABLE = "CREATE TABLE IF NOT EXISTS incoming (id TEXT PRIMARY KEY NOT NULL, media_id INTEGER NOT NULL, path TEXT NOT NULL, folder TEXT NOT NULL, file_name TEXT NOT NULL, mime TEXT NOT NULL, size_bytes INTEGER NOT NULL, taken_ms INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, at INTEGER NOT NULL)"
        private const val INCOMING_TAKEN_INDEX = "CREATE INDEX IF NOT EXISTS incoming_taken_ms ON incoming (taken_ms)"
        private const val INCOMING_COLUMNS = "id, media_id, path, folder, file_name, mime, size_bytes, taken_ms, width, height"
        private const val INCOMING_TAKEN = "SELECT taken_ms FROM incoming WHERE taken_ms > 0 AND id NOT IN (SELECT id FROM docs)"
        private const val WORD_RETRIES_TABLE = "CREATE TABLE IF NOT EXISTS word_retries (id TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, next_at INTEGER NOT NULL)"
    }
}
