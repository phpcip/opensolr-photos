package com.opensolr.photos.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/** What a file in the photo folders is: a photo, a video (its first frame stands for it) or one page of a PDF. */
enum class Kind { PHOTO, VIDEO, PDF }

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

    val addedSec: Long = 0,

    val kind: Kind = Kind.PHOTO,
    val durationMs: Long = 0,
    /** PDF: this page (1-based), how many the file has, and the id of the file itself. */
    val page: Int = 0,
    val pages: Int = 0,
    val parentId: String? = null,
) {

    val uri: Uri get() = MediaScanner.uriOf(kind, mediaId)
}

/** Which files beside photos a scan takes in, and how many pages a PDF has (cached by the caller). */
class ScanOptions(val videos: Boolean = false, val pdfs: Boolean = false, val pdfMaxPages: Int = 0, val pdfPages: (LocalPhoto) -> Int = { 0 }) {
    companion object {
        val PHOTOS_ONLY = ScanOptions()

        /** The owner's choices, with the page counts kept in the clone. */
        fun of(context: Context): ScanOptions {
            val prefs = com.opensolr.photos.data.AppPrefs(context)
            if (!prefs.includeVideos && !prefs.includePdfs) return PHOTOS_ONLY
            val cache = com.opensolr.photos.data.PhotoCache.of(context)
            return ScanOptions(prefs.includeVideos, prefs.includePdfs, prefs.pdfMaxPages) { pdf -> PdfPages.count(context, cache, pdf) }
        }
    }
}

data class PhotoFolder(val relativePath: String, val count: Int)

object MediaScanner {

    fun photoId(relativePath: String): String {
        val digest = MD5.get()!!
        digest.reset()
        val bytes = digest.digest(relativePath.toByteArray(Charsets.UTF_8))

        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private val MD5 = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    private val HEX = "0123456789abcdef".toCharArray()

    fun listFolders(context: Context): List<PhotoFolder> {
        val counts = HashMap<String, Int>()
        query(context) { row -> counts[row.folder] = (counts[row.folder] ?: 0) + 1 }
        // folders holding only clips or PDFs are offered too, when those are indexed
        val prefs = com.opensolr.photos.data.AppPrefs(context)
        if (prefs.includeVideos) query(context, kind = Kind.VIDEO) { row -> counts[row.folder] = (counts[row.folder] ?: 0) + 1 }
        if (prefs.includePdfs) query(context, kind = Kind.PDF) { row -> counts[row.folder] = (counts[row.folder] ?: 0) + 1 }
        return counts.map { PhotoFolder(it.key, it.value) }.sortedWith(compareByDescending<PhotoFolder> { it.count }.thenBy { it.relativePath })
    }

    fun defaultFolders(folders: List<PhotoFolder>): Set<String> =
        folders.map { it.relativePath }.filter { it.startsWith("DCIM/", ignoreCase = true) }.toSet()
            .ifEmpty { setOf("DCIM/") }

    /** The photos in [folders]; null when Android would not answer (permission gone, no volume): nothing is concluded from that. */
    fun scan(context: Context, folders: Set<String>, aliases: Map<String, String> = emptyMap(), options: ScanOptions = ScanOptions.PHOTOS_ONLY): Map<String, LocalPhoto>? {
        val prefixes = folders.map { normalizeFolder(it) }
        val result = LinkedHashMap<String, LocalPhoto>()
        if (prefixes.isEmpty()) return result

        // a file restored where Android allowed it instead of its own folder keeps the photo's id
        fun keep(row: LocalPhoto) {
            val known = aliases[row.id]
            if (known == null) result[row.id] = row else result[known] = row.copy(id = known)
        }
        val inFolders = { folder: String -> prefixes.any { folder.startsWith(it, ignoreCase = true) } }
        val answered = query(context, keep = inFolders) { keep(it) }
        if (!answered) return null
        if (options.videos) query(context, kind = Kind.VIDEO, keep = inFolders) { keep(it) }
        if (options.pdfs) query(context, kind = Kind.PDF, keep = inFolders) { pdf ->
            // one entry per page, the file itself is not a result
            val pages = options.pdfPages(pdf).coerceAtMost(options.pdfMaxPages.takeIf { it > 0 } ?: Int.MAX_VALUE)
            for (n in 1..pages) keep(pdf.copy(id = pageId(pdf.id, n), page = n, pages = pages, parentId = pdf.id))
        }
        return result
    }

    /** The id of page [n] of the PDF whose own id is [parentId]. */
    fun pageId(parentId: String, n: Int): String = photoId("$parentId#p$n")

    fun uriOf(kind: Kind, mediaId: Long): Uri = ContentUris.withAppendedId(
        when (kind) {
            Kind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Kind.PDF -> MediaStore.Files.getContentUri("external")
        },
        mediaId,
    )

    /** How many photos lie in [folders], without keeping them. */
    fun count(context: Context, folders: Set<String>): Int {
        val prefixes = folders.map { normalizeFolder(it) }
        if (prefixes.isEmpty()) return 0
        var n = 0
        query(context, keep = { folder -> prefixes.any { folder.startsWith(it, ignoreCase = true) } }) { n++ }
        return n
    }

    fun addedSince(context: Context, folders: Set<String>, sinceSec: Long): List<LocalPhoto> {
        val prefixes = folders.map { normalizeFolder(it) }
        if (prefixes.isEmpty()) return emptyList()
        val out = ArrayList<LocalPhoto>()
        query(
            context,
            where = "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            args = arrayOf(sinceSec.toString()),
            keep = { folder -> prefixes.any { folder.startsWith(it, ignoreCase = true) } },
        ) { out += it }
        return out
    }

    fun findByPath(context: Context, absolutePath: String): LocalPhoto? {
        if (absolutePath.isBlank()) return null

        var found: LocalPhoto? = null
        query(context, where = "${MediaStore.Images.Media.DATA} = ?", args = arrayOf(absolutePath)) { row ->
            if (found == null) found = row
        }
        if (found != null) return found

        query(context) { row -> if (found == null && row.absolutePath == absolutePath) found = row }
        return found
    }

    fun exists(context: Context, mediaId: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

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

            }
        }
        return out
    }

    /** Which of [paths] MediaStore still holds, in chunks. */
    @Suppress("DEPRECATION")
    fun existingPaths(context: Context, paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val out = HashSet<String>(paths.size)
        paths.chunked(500).forEach { batch ->
            val marks = batch.joinToString(",") { "?" }
            try {
                // every kind: photos, videos and PDFs all live in the files collection
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns.DATA} IN ($marks)",
                    batch.toTypedArray(),
                    null,
                )?.use { c -> while (c.moveToNext()) out += c.getString(0) }
            } catch (e: Exception) {
                // unreadable: treat every path as still there, so nothing is dropped by mistake
                out += batch
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun query(
        context: Context,
        where: String? = null,
        args: Array<String>? = null,

        keep: ((String) -> Boolean)? = null,
        kind: Kind = Kind.PHOTO,
        onRow: (LocalPhoto) -> Unit,
    ): Boolean {
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // PDFs come from the files table, which has no picture columns before Android 10
        val pictureColumns = kind != Kind.PDF || hasRelativePath
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (pictureColumns) projection += listOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_TAKEN)
        if (kind == Kind.VIDEO) projection += MediaStore.Video.Media.DURATION
        if (hasRelativePath) projection += MediaStore.MediaColumns.RELATIVE_PATH
        val collection = when (kind) {
            Kind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Kind.PDF -> MediaStore.Files.getContentUri("external")
        }
        val selection = if (kind == Kind.PDF) listOfNotNull("${MediaStore.MediaColumns.MIME_TYPE} = 'application/pdf'", where).joinToString(" AND ") else where

        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val cursor = try {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                selection, args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return false

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val widthCol = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val takenCol = it.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val durationCol = it.getColumnIndex(MediaStore.Video.Media.DURATION)
            val modifiedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val relCol = if (hasRelativePath) it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1

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
                        // the id follows the path inside the storage volume, never its mount point: the same
                        // folders copied to another phone keep every photo's identity in the index
                        id = photoId(folder + name),
                        mediaId = mediaId,
                        absolutePath = absolute,
                        folder = folder,
                        fileName = name,
                        mime = it.getString(mimeCol) ?: when (kind) { Kind.VIDEO -> "video/mp4"; Kind.PDF -> "application/pdf"; else -> "image/jpeg" },
                        sizeBytes = it.getLong(sizeCol),
                        width = if (widthCol >= 0) it.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) it.getInt(heightCol) else 0,
                        dateTakenMs = if (takenCol >= 0) it.getLong(takenCol) else 0L,
                        modifiedSec = it.getLong(modifiedCol),
                        addedSec = it.getLong(addedCol),
                        kind = kind,
                        durationMs = if (durationCol >= 0) it.getLong(durationCol) else 0L,
                    )
                )
            }
        }
        return true
    }

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

    private fun String.likePrefix(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun folderFromPath(absolute: String, root: String): String {
        val parent = File(absolute).parent ?: return ""
        val withSlash = parent.trimEnd('/') + "/"
        return if (withSlash.startsWith(root)) withSlash.removePrefix(root) else withSlash.trimStart('/')
    }

    /** A folder as the id is made of it: relative to the volume, no leading slash, one trailing slash. */
    fun folderKey(folder: String): String = normalizeFolder(folder)

    private fun normalizeFolder(folder: String): String {
        val trimmed = folder.trim().trimStart('/')
        return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
