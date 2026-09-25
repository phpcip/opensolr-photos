package com.opensolr.photos.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

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
) {

    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
}

data class PhotoFolder(val relativePath: String, val count: Int)

object MediaScanner {

    fun photoId(absolutePath: String): String {
        val digest = MD5.get()!!
        digest.reset()
        val bytes = digest.digest(absolutePath.toByteArray(Charsets.UTF_8))

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
        return counts.map { PhotoFolder(it.key, it.value) }.sortedWith(compareByDescending<PhotoFolder> { it.count }.thenBy { it.relativePath })
    }

    fun defaultFolders(folders: List<PhotoFolder>): Set<String> =
        folders.map { it.relativePath }.filter { it.startsWith("DCIM/", ignoreCase = true) }.toSet()
            .ifEmpty { setOf("DCIM/") }

    fun scan(context: Context, folders: Set<String>): Map<String, LocalPhoto> {
        val prefixes = folders.map { normalizeFolder(it) }
        val result = LinkedHashMap<String, LocalPhoto>()
        if (prefixes.isEmpty()) return result

        query(context, keep = { folder -> prefixes.any { folder.startsWith(it, ignoreCase = true) } }) { row ->
            result[row.id] = row
        }
        return result
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

    @Suppress("DEPRECATION")
    private fun query(
        context: Context,
        where: String? = null,
        args: Array<String>? = null,

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

    private fun normalizeFolder(folder: String): String {
        val trimmed = folder.trim().trimStart('/')
        return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
