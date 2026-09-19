package com.opensolr.photos.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/**
 * A photo found on the phone.
 *
 * @property id              md5 of [absolutePath], lower-case hex. This is also the Solr document id,
 *                           and [photoId] is the one and only place it is computed.
 * @property mediaId         MediaStore row id, used to open the photo in the gallery
 * @property absolutePath    full path on the device, e.g. /storage/emulated/0/DCIM/Camera/IMG_1.jpg
 * @property folder          MediaStore relative folder, e.g. DCIM/Camera/
 * @property dateTakenMs     MediaStore DATE_TAKEN in millis, 0 when unknown
 * @property modifiedSec     MediaStore DATE_MODIFIED in seconds
 */
data class LocalPhoto(
    val id: String,
    val mediaId: Long,
    val absolutePath: String,
    val folder: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val modifiedSec: Long,
    /** When the file appeared on the phone, in seconds; 0 when MediaStore does not say. */
    val addedSec: Long = 0,
) {

    /**
     * content:// URI of the photo, readable by this app and grantable to a gallery app.
     */
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
}

/**
 * A folder that holds photos, as offered on the folder picker.
 */
data class PhotoFolder(val relativePath: String, val count: Int)

/**
 * Finds photos through MediaStore, the only way that needs no broad storage permission.
 *
 * Folders are MediaStore relative paths ("DCIM/Camera/"). A chosen folder includes every folder
 * below it, so choosing "DCIM/" covers "DCIM/Camera/" too. MediaStore already leaves out files
 * that are still being written and files in the trash.
 */
object MediaScanner {

    /**
     * The one definition of a photo's id: md5 of its absolute path, lower-case hex. Indexing,
     * Re-Sync and the scheduled Sync-Check all call this, so the ids on the phone and in the
     * index are always built the same way.
     */
    fun photoId(absolutePath: String): String {
        val digest = MD5.get()!!
        digest.reset()
        val bytes = digest.digest(absolutePath.toByteArray(Charsets.UTF_8))
        // Written out by hand rather than with a format string per byte: this runs for every photo
        // on the phone in every scan, and sixteen format calls each is most of what a scan of ten
        // thousand photos costs (Cip, 2026-09-18).
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** The digest, one per thread: asking the platform for a new one costs more than the hashing. */
    private val MD5 = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Every folder that holds at least one photo, with its photo count, largest first.
     */
    fun listFolders(context: Context): List<PhotoFolder> {
        val counts = HashMap<String, Int>()
        query(context) { row -> counts[row.folder] = (counts[row.folder] ?: 0) + 1 }
        return counts.map { PhotoFolder(it.key, it.value) }.sortedWith(compareByDescending<PhotoFolder> { it.count }.thenBy { it.relativePath })
    }

    /**
     * The folders proposed before the user chooses: everything under DCIM, where the camera
     * saves by default.
     */
    fun defaultFolders(folders: List<PhotoFolder>): Set<String> =
        folders.map { it.relativePath }.filter { it.startsWith("DCIM/", ignoreCase = true) }.toSet()
            .ifEmpty { setOf("DCIM/") }

    /**
     * Every photo inside [folders] (or below them), keyed by [LocalPhoto.id].
     */
    fun scan(context: Context, folders: Set<String>): Map<String, LocalPhoto> {
        val prefixes = folders.map { normalizeFolder(it) }
        val result = LinkedHashMap<String, LocalPhoto>()
        if (prefixes.isEmpty()) return result
        // The folder decides before the photo is built, not after: a phone whose chosen folders hold
        // a tenth of its pictures was hashing the path of every one of the other nine tenths for
        // nothing (Cip, 2026-09-18).
        query(context, keep = { folder -> prefixes.any { folder.startsWith(it, ignoreCase = true) } }) { row ->
            result[row.id] = row
        }
        return result
    }

    /**
     * Looks a photo up again by its absolute path, for when the MediaStore id stored in the index
     * went stale (the gallery re-scanned the file). Returns null when the file is gone.
     */
    fun findByPath(context: Context, absolutePath: String): LocalPhoto? {
        if (absolutePath.isBlank()) return null
        // Asked of MediaStore by path, not by walking every photo on the phone: this is called once
        // per photo when the owner's words are written into a batch of them, and a walk each time
        // meant a full scan of the library per photo (Cip, 2026-09-18).
        var found: LocalPhoto? = null
        query(context, where = "${MediaStore.Images.Media.DATA} = ?", args = arrayOf(absolutePath)) { row ->
            if (found == null) found = row
        }
        if (found != null) return found
        // A phone that reports no DATA column value for the row: fall back to the walk, once.
        query(context) { row -> if (found == null && row.absolutePath == absolutePath) found = row }
        return found
    }

    /**
     * True when MediaStore still has a row with [mediaId].
     */
    fun exists(context: Context, mediaId: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Which of [mediaIds] MediaStore still has, in one query however many there are. Asking row by
     * row is what made sharing, deleting and tagging a large selection slow (Cip, 2026-09-18).
     */
    fun existing(context: Context, mediaIds: Collection<Long>): Set<Long> {
        if (mediaIds.isEmpty()) return emptySet()
        val out = HashSet<Long>(mediaIds.size)
        mediaIds.chunked(500).forEach { batch ->
            val marks = batch.joinToString(",") { "?" }
            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media._ID} IN ($marks)",
                    batch.map { it.toString() }.toTypedArray(),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) out += c.getLong(0)
                }
            } catch (e: Exception) {
                // Unreadable: treat those ids as gone, and each falls back to a lookup by path.
            }
        }
        return out
    }

    /**
     * Runs one MediaStore query over all images and hands every usable row to [onRow].
     */
    @Suppress("DEPRECATION")
    private fun query(
        context: Context,
        where: String? = null,
        args: Array<String>? = null,
        /** Which folders are wanted, asked before the photo is built; null takes every row. */
        keep: ((String) -> Boolean)? = null,
        onRow: (LocalPhoto) -> Unit,
    ) {
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )
        if (hasRelativePath) projection += MediaStore.Images.Media.RELATIVE_PATH

        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                where, args,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val takenCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val relCol = if (hasRelativePath) it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1

            while (it.moveToNext()) {
                val mediaId = it.getLong(idCol)
                val name = it.getString(nameCol) ?: continue
                val relative = if (relCol >= 0) it.getString(relCol) else null
                val data = it.getString(dataCol)
                val absolute = when {
                    !data.isNullOrBlank() -> data
                    relative != null -> root + relative + name
                    else -> continue
                }
                val folder = normalizeFolder(relative ?: folderFromPath(absolute, root))
                if (keep != null && !keep(folder)) continue
                onRow(
                    LocalPhoto(
                        id = photoId(absolute),
                        mediaId = mediaId,
                        absolutePath = absolute,
                        folder = folder,
                        fileName = name,
                        mime = it.getString(mimeCol) ?: "image/jpeg",
                        sizeBytes = it.getLong(sizeCol),
                        width = it.getInt(widthCol),
                        height = it.getInt(heightCol),
                        dateTakenMs = it.getLong(takenCol),
                        modifiedSec = it.getLong(modifiedCol),
                        addedSec = it.getLong(addedCol),
                    )
                )
            }
        }
    }

    /**
     * A short fingerprint of the photos in [folders]: how many there are and the latest write
     * among them. Adding or deleting a photo changes the count; editing one, in any app, moves
     * the latest write. From Android 11 that is MediaStore's own write counter, which goes up
     * on every change whatever time the file claims; before, the file's modification time.
     * One MediaStore query with the folders in its WHERE and a single narrow column, so
     * MediaStore does the filtering and the phone does not walk the library. Null when it
     * cannot be read.
     */
    @Suppress("DEPRECATION")
    fun folderStamp(context: Context, folders: Set<String>): String? {
        val prefixes = folders.map { normalizeFolder(it) }.filter { it.isNotEmpty() }
        if (prefixes.isEmpty()) return "0:0"
        val byRelative = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val column = if (byRelative) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val where = prefixes.joinToString(" OR ") { "$column LIKE ? ESCAPE '\\'" }
        val args = prefixes.map { (if (byRelative) it else root + it).likePrefix() }.toTypedArray()
        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.MediaColumns.GENERATION_MODIFIED else MediaStore.Images.Media.DATE_MODIFIED
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(written),
                where, args,
                "$written DESC",
            )?.use { c -> "${c.count}:${if (c.moveToFirst()) c.getLong(0) else 0L}" }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True when at least one of [uris] (MediaStore rows reported as changed) is a picture in
     * one of [folders]. When a change cannot be placed - no row id, a row already deleted,
     * nothing reported - the fingerprint of the folders is compared with [lastStamp], the one
     * taken at the last sync that finished: unchanged means the change was somewhere else,
     * such as a screenshot deleted. Without a stamp to compare, the answer is yes.
     */
    fun touchesFolders(context: Context, uris: Collection<Uri>, folders: Set<String>, lastStamp: String?): Boolean {
        if (folders.isEmpty()) return false
        val prefixes = folders.map { normalizeFolder(it) }
        var unknown = uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (!unknown) {
            val resolver = context.contentResolver
            for (uri in uris) {
                if (uri.lastPathSegment?.toLongOrNull() == null) { unknown = true; continue }
                val folder = try {
                    resolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } catch (e: Exception) {
                    null
                }
                if (folder == null) { unknown = true; continue }
                val normalized = normalizeFolder(folder)
                if (prefixes.any { normalized.startsWith(it, ignoreCase = true) }) return true
            }
        }
        if (!unknown) return false
        return lastStamp == null || folderStamp(context, folders) != lastStamp
    }

    /**
     * [this] as the argument of a LIKE that matches everything starting with it: the LIKE
     * wildcards in folder names ("WhatsApp_Images") are escaped so they match themselves.
     */
    private fun String.likePrefix(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * Relative folder of an absolute path on phones older than Android 10, which have no
     * RELATIVE_PATH column.
     */
    private fun folderFromPath(absolute: String, root: String): String {
        val parent = File(absolute).parent ?: return ""
        val withSlash = parent.trimEnd('/') + "/"
        return if (withSlash.startsWith(root)) withSlash.removePrefix(root) else withSlash.trimStart('/')
    }

    /**
     * Folder spelled with a trailing slash and no leading slash.
     */
    private fun normalizeFolder(folder: String): String {
        val trimmed = folder.trim().trimStart('/')
        return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
