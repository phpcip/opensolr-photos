package com.opensolr.photos.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.auth.AuthFlow
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.data.StorageConfig
import com.opensolr.photos.data.StorageReport
import com.opensolr.photos.media.LocalPhoto
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.SignInRequiredException
import com.opensolr.photos.ui.Actions
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * One run against the index's storage backend: the manifests read, the photos only the backend has brought
 * down as 720 px copies, then the indexed photos on this phone that the backend lacks sent up (the full-size
 * copy or the original, plus the 720 px copy), the manifest of this phone rewritten on the way.
 */
class StorageEngine(private val context: Context) {

    data class Progress(val phase: String, val done: Int, val total: Int)

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi()
    private val cache = PhotoCache.of(context)

    private class StoppedException : Exception()
    private class LowBatteryException : Exception()
    private class MeteredException : Exception()

    suspend fun run(onProgress: suspend (Progress) -> Unit): StorageReport {
        val session = prefs.session ?: return report("sign_in_required")
        val indexName = prefs.connection?.indexName ?: return report("no_index")
        var uploaded = 0
        var restored = 0
        var failed = 0
        var backend: Backend? = null
        try {
            val config = resolveConfig(session, indexName) ?: return report("no_backend")
            if (!networkAllowed()) throw MeteredException()
            checkBattery()
            onProgress(Progress(AppText.s(R.string.stg_connecting), 0, 0))
            val device = AuthFlow.deviceId(context)
            val layout = Layout(config, indexName)
            backend = Backend.open(config).also { it.connect() }
            val local = MediaScanner.scan(context, prefs.folders, cache.aliases(), com.opensolr.photos.media.ScanOptions.of(context))
                ?: return report("failed", message = AppText.s(R.string.sy_cannot_read))
            val localIds = local.keys

            if (prefs.manifestsReadAt == 0L || System.currentTimeMillis() - prefs.manifestsReadAt > MANIFEST_MAX_AGE_MS) {
                onProgress(Progress(AppText.s(R.string.stg_reading_manifests), 0, 0))
                readManifests(backend, layout)
                prefs.manifestsReadAt = System.currentTimeMillis()
            }

            // once: the clone read before 3.23.3 lacked the kind of each document (video, PDF page): read again
            val sp = context.getSharedPreferences("opensolr_photos", Context.MODE_PRIVATE)
            if (!sp.getBoolean("clone_kinds_3233", false)) {
                runCatching { com.opensolr.photos.search.EditRepository(context).readDocsIntoCache(cache.cloudIds().toList()) }
                    .onSuccess { sp.edit().putBoolean("clone_kinds_3233", true).apply() }
            }

            // what another phone indexed after this one read the index: its documents into the clone first
            cache.remoteWithoutDoc().takeIf { it.isNotEmpty() }?.let { ids ->
                onProgress(Progress(AppText.s(R.string.stg_reading_manifests), 0, 0))
                runCatching { com.opensolr.photos.search.EditRepository(context).readDocsIntoCache(ids) }
            }

            // a run cut short (reinstall, killed app) can leave a half-written file pending in MediaStore: it goes
            dropPendingWrites()
            dropOwnDuplicates()

            // videos and PDF pages an older version brought back as pictures come back as their files
            val oldCopies = cache.cloudVideosAndPages()
            oldCopies.forEach { id -> cache.removeCloud(id); CloudFiles.remove(context, id) }

            // the whole backed-up library is this phone's, whatever folders it syncs: hidden photos come back
            if (cache.unparkBackedUp() > 0) prefs.cloneComplete = true

            // originals asked for in bulk (before a change or a clear) come down first
            if (prefs.downloadAllOriginals) {
                var total = cache.cloudOnlyTotals().first.toInt()
                var done = 0
                while (true) {
                    val batch = cache.cloudOnlyIds(BATCH)
                    if (batch.isEmpty()) break
                    for (id in batch) {
                        guard()
                        try {
                            downloadOriginal(backend, layout, id)
                            done++
                        } catch (e: NotFoundException) {
                            failed++
                            cache.removeCloud(id)
                            CloudFiles.remove(context, id)
                        }
                        onProgress(Progress(AppText.s(R.string.stg_downloading_originals), done, total))
                    }
                    if (batch.size < BATCH) break
                }
                prefs.downloadAllOriginals = false
            }

            // restore: the photos in the backend that this phone has no file for
            val cloudDir = CloudFiles.dir(context)
            // the total is counted once, over everything to bring, so the bar and N of M are true from the start
            // files an earlier run already wrote outside the synced folders are not counted again
            val toRestore = cache.restorePendingIds(localIds).let { ids ->
                val paths = cache.mediaFor(ids)
                val onDisk = MediaScanner.existingPaths(context, paths.values.map { it.second }.filter { it.isNotEmpty() && !it.contains("/files/cloud/") })
                ids.count { id -> paths[id]?.second?.let { it in onDisk } != true }
            }
            // ids dealt with in this run: a clip or a PDF comes back as its file, every page of a PDF at once
            val handled = HashSet<String>()
            while (true) {
                // a file an earlier run already wrote (outside the synced folders, so not in the scan) is there: not again
                val raw = cache.restoreCandidates(localIds, BATCH).filter { it.id !in handled && it.id !in missing }
                val paths = cache.mediaFor(raw.map { it.id })
                val onDisk = MediaScanner.existingPaths(context, paths.values.map { it.second }.filter { it.isNotEmpty() && !it.contains("/files/cloud/") })
                raw.filter { r -> paths[r.id]?.second?.let { it in onDisk } == true }.forEach { handled += it.id }
                val batch = raw.filter { it.id !in handled }
                if (raw.isNotEmpty() && batch.isEmpty()) { if (raw.size < BATCH) break else continue }
                if (batch.isEmpty()) break
                for (remote in batch) {
                    guard()
                    if (remote.id in handled) continue
                    handled += remote.id
                    // videos and PDFs come back as the original file; photos as 720 px copies
                    val doc = cache.doc(remote.id)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val kind = doc?.optString("kind_s").orEmpty()
                    if (kind == "video" || kind == "pdf_page") {
                        try {
                            downloadOriginal(backend, layout, remote.id)
                            if (kind == "pdf_page") doc?.optString("parent_s")?.takeIf { it.isNotBlank() }?.let { parent -> handled += cache.docIdsOfParent(parent) }
                            restored++
                        } catch (e: NotFoundException) {
                            failed++
                            missing += remote.id
                        }
                        onProgress(Progress(AppText.s(R.string.stg_restoring), restored + failed, maxOf(toRestore, restored + failed)))
                        continue
                    }
                    val target = File(cloudDir, remote.id + ".jpg")
                    val tmp = File(cloudDir, remote.id + ".part")
                    try {
                        tmp.outputStream().use { backend.get(layout.restoreCopy(remote.id), it) }
                        if (!tmp.renameTo(target)) throw BackendException("cannot write")
                        cache.putCloud(remote.id, remote.bytes)
                        CloudFiles.add(remote.id)
                        restored++
                    } catch (e: NotFoundException) {
                        tmp.delete()
                        // no 720 px copy up there (an older backup): the full copy stands in for it
                        try {
                            tmp.outputStream().use { backend.get(layout.main(remote.path.substringBeforeLast('/', ""), remote.path.substringAfterLast('/')), it) }
                            val shrunk = shrink(tmp, target, RESTORE_EDGE)
                            tmp.delete()
                            if (!shrunk) throw NotFoundException(remote.id)
                            cache.putCloud(remote.id, remote.bytes)
                            CloudFiles.add(remote.id)
                            restored++
                        } catch (e2: NotFoundException) {
                            tmp.delete()
                            failed++
                            missing += remote.id
                        }
                    } catch (e: BackendException) {
                        tmp.delete()
                        throw e
                    }
                    onProgress(Progress(AppText.s(R.string.stg_restoring), restored + failed, maxOf(toRestore, restored + failed)))
                }
                if (batch.size < BATCH) break
            }

            // backup: what is indexed and on this phone but not in the backend yet
            val quality = config.quality
            var toSend = -1
            var sinceManifest = 0
            while (true) {
                val batch = cache.backupCandidates(localIds, BATCH).filter { it.id !in unreadable }
                if (batch.isEmpty()) break
                if (toSend < 0) toSend = cache.backupPendingCount(localIds)
                for (item in batch) {
                    guard()
                    val photo = local[item.id] ?: continue
                    try {
                        val sent = upload(backend, layout, photo, quality)
                        cache.putRemote(listOf(PhotoCache.Remote(item.id, device, sent.first, sent.second, item.folder.trim('/') + "/" + item.fileName, sent.third, System.currentTimeMillis())))
                        uploaded++
                        sinceManifest++
                    } catch (e: UnreadableException) {
                        unreadable += item.id
                        failed++
                    }
                    if (sinceManifest >= MANIFEST_EVERY) {
                        writeManifest(backend, layout, device)
                        sinceManifest = 0
                    }
                    onProgress(Progress(AppText.s(R.string.stg_backing_up), uploaded + failed, maxOf(toSend, uploaded + failed)))
                }
            }
            if (sinceManifest > 0) writeManifest(backend, layout, device)
            return report("ok", uploaded, restored, failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StoppedException) {
            return report("stopped", uploaded, restored, failed)
        } catch (e: LowBatteryException) {
            return report("waiting_battery", uploaded, restored, failed)
        } catch (e: MeteredException) {
            return report("waiting_wifi", uploaded, restored, failed)
        } catch (e: SignInRequiredException) {
            return report("sign_in_required", uploaded, restored, failed, e.message ?: "")
        } catch (e: NotFoundException) {
            return report("failed", uploaded, restored, failed, e.message ?: "")
        } catch (e: BackendException) {
            return report("failed", uploaded, restored, failed, e.message ?: "")
        } catch (e: Exception) {
            return report("failed", uploaded, restored, failed, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { backend?.close() }
        }
    }

    private val missing = HashSet<String>()
    private val unreadable = HashSet<String>()

    private class UnreadableException : Exception()

    /** The backend of this index: what this phone holds, else what the account holds (asked at most hourly). */
    private suspend fun resolveConfig(session: com.opensolr.photos.data.Session, indexName: String): StorageConfig? {
        val held = prefs.storage
        if (held != null) {
            if (!prefs.storageSynced) {
                runCatching { api.photosStorageSave(session, indexName, held) }.onSuccess { prefs.storageSynced = true }
            } else if (System.currentTimeMillis() - prefs.storageAskedAt > CONFIG_MAX_AGE_MS) {
                // the account's copy wins when another phone changed it since
                runCatching { api.photosStorageGet(session, indexName) }.onSuccess { answer ->
                    prefs.storageAskedAt = System.currentTimeMillis()
                    val theirs = answer.first
                    if (theirs != null && theirs.updated > held.updated) prefs.storage = theirs
                }
            }
            return prefs.storage
        }
        if (System.currentTimeMillis() - prefs.storageAskedAt < CONFIG_ASK_MS) return null
        val answer = api.photosStorageGet(session, indexName)
        prefs.storageAskedAt = System.currentTimeMillis()
        answer.first?.let {
            prefs.storage = it
            prefs.storageSynced = true
            prefs.manifestsReadAt = 0L
        }
        return answer.first
    }

    /** Every manifest in the index's folder read into the clone, line by line. */
    private fun readManifests(backend: Backend, layout: Layout) {
        val names = backend.list(layout.home).filter { it.startsWith("manifest-") && it.endsWith(".jsonl") }
        val tmp = File.createTempFile("manifest", ".jsonl", context.cacheDir)
        try {
            for (name in names) {
                val device = name.removePrefix("manifest-").removeSuffix(".jsonl")
                tmp.outputStream().use { backend.get(layout.home + "/" + name, it) }
                val rows = ArrayList<PhotoCache.Remote>(500)
                tmp.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val row = parseLine(line, device) ?: continue
                        rows += row
                        if (rows.size >= 500) {
                            cache.putRemote(rows)
                            rows.clear()
                        }
                    }
                }
                cache.putRemote(rows)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun parseLine(line: String, device: String): PhotoCache.Remote? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val id = json.optString("id")
        val path = json.optString("path")
        if (!Regex("^[a-f0-9]{32}$").matches(id) || path.isBlank()) return null
        return PhotoCache.Remote(id, device, json.optString("kind").ifBlank { StorageConfig.QUALITY_ORIGINAL }, json.optLong("bytes"), path, json.optString("md5").ifBlank { null }, json.optLong("at"))
    }

    /** This phone's manifest, rewritten whole from the clone: one line per photo it sent. */
    private fun writeManifest(backend: Backend, layout: Layout, device: String) {
        val tmp = File.createTempFile("manifest", ".jsonl", context.cacheDir)
        try {
            tmp.bufferedWriter().use { out ->
                cache.forEachRemoteOfDevice(device) { r ->
                    out.write(JSONObject().put("id", r.id).put("path", r.path).put("kind", r.kind).put("bytes", r.bytes).put("md5", r.md5 ?: "").put("at", r.at).toString())
                    out.newLine()
                }
            }
            backend.put(layout.manifest(device), tmp.length()) { tmp.inputStream() }
        } finally {
            tmp.delete()
        }
    }

    /** The photo sent up: (kind, bytes of the full copy, md5 of it). */
    // the files sent up in this run: the pages of one PDF share their file, which goes up once
    private val sentFiles = HashMap<String, Pair<Long, String?>>()

    private fun upload(backend: Backend, layout: Layout, photo: LocalPhoto, quality: String): Triple<String, Long, String?> {
        val mainPath = layout.main(photo.folder, photo.fileName)
        val resolver = context.contentResolver
        val small = File.createTempFile("restore", ".jpg", context.cacheDir)
        val big = File.createTempFile("main", ".jpg", context.cacheDir)
        try {
            // the restore copy: the photo, a clip's first frame or the page itself, at 720 px
            // a file whose picture cannot be drawn still goes up; its restore copy is the stand-in
            val smallBytes = PhotoReader.representativeJpeg(context, photo, RESTORE_EDGE) ?: PhotoReader.placeholderJpeg(photo.kind, 1280)
            small.writeBytes(smallBytes)
            val result: Triple<String, Long, String?>
            val already = sentFiles[mainPath]
            if (already != null) {
                result = Triple(StorageConfig.QUALITY_ORIGINAL, already.first, already.second)
            } else if (quality == StorageConfig.QUALITY_1080 && photo.kind == com.opensolr.photos.media.Kind.PHOTO) {
                if (!PhotoReader.shareCopy(context, photo.uri, big, FULL_HD_EDGE)) throw UnreadableException()
                backend.put(mainPath, big.length()) { big.inputStream() }
                result = Triple(StorageConfig.QUALITY_1080, big.length(), md5Of(big))
            } else {
                val size = resolver.openFileDescriptor(photo.uri, "r")?.use { it.statSize } ?: throw UnreadableException()
                if (size <= 0) throw UnreadableException()
                // a file another page of it (or an earlier run) already put there, whole, is not sent again
                if (photo.kind == com.opensolr.photos.media.Kind.PDF && backend.stat(mainPath) == size) {
                    result = Triple(StorageConfig.QUALITY_ORIGINAL, size, PhotoReader.fileMd5(context, photo))
                } else {
                    var digest: MessageDigest? = null
                    backend.put(mainPath, size) {
                        val stream = resolver.openInputStream(photo.uri) ?: throw UnreadableException()
                        MessageDigest.getInstance("MD5").let { d -> digest = d; DigestInputStream(stream, d) }
                    }
                    result = Triple(StorageConfig.QUALITY_ORIGINAL, size, digest?.digest()?.joinToString("") { "%02x".format(it) })
                }
                sentFiles[mainPath] = result.second to result.third
            }
            backend.put(layout.restoreCopy(photo.id), small.length()) { small.inputStream() }
            return result
        } finally {
            small.delete()
            big.delete()
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shrink(source: File, target: File, edge: Int): Boolean =
        runCatching { PhotoReader.shareCopy(context, Uri.fromFile(source), target, edge) }.getOrDefault(false)

    /**
     * The full copy of [id] brought into the phone's photo folders, into the photo's own folder where Android
     * allows it (DCIM, Pictures), else under Pictures with the folder's name; the photo keeps its id either way.
     */
    fun downloadOriginal(backend: Backend, layout: Layout, id: String) {
        val remote = cache.remote(id) ?: throw NotFoundException(id)
        val folder = remote.path.substringBeforeLast('/', "").trim('/')
        val name = remote.path.substringAfterLast('/')
        val tmp = File.createTempFile("original", ".bin", context.cacheDir)
        try {
            tmp.outputStream().use { backend.get(layout.main(folder, name), it) }
            val md5 = md5Of(tmp)
            val written = writeIntoFolders(tmp, folder, name)
            val fileId = MediaScanner.photoId(MediaScanner.folderKey(folder) + name)
            val newFileId = MediaScanner.photoId(MediaScanner.folderKey(written.folder) + written.name)
            // a PDF: every page of it is back with the file; a photo or clip: the one document
            val pages = if (id != fileId) cache.docIdsOfParent(fileId).ifEmpty { listOf(id) } else listOf(id)
            if (newFileId != fileId) {
                cache.putAlias(newFileId, fileId)
                for (pageId in pages) {
                    val n = cache.doc(pageId)?.json?.let { runCatching { org.json.JSONObject(it).optInt("page_i") }.getOrNull() } ?: continue
                    if (n > 0) cache.putAlias(MediaScanner.pageId(newFileId, n), pageId)
                }
            }
            LocalFiles.add(pages)
            for (docId in pages) {
                // the file on the phone is now the one the index knows: its size and hash, so the sync reads nothing
                cache.updateDocSize(docId, tmp.length(), 0L, md5)
                cache.relocateDoc(docId, written.mediaId, written.absolutePath)
                cache.removeCloud(docId)
                CloudFiles.remove(context, docId)
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Copies an earlier version wrote twice ("name (1).ext" next to "name.ext", same size, written by this app) are
     * removed: they are the same file. Only this app's own files, never anything else.
     */
    private fun dropOwnDuplicates() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        data class Row(val id: Long, val folder: String, val name: String, val size: Long)
        val rows = ArrayList<Row>()
        runCatching {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)?.use { c ->
                while (c.moveToNext()) rows += Row(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3))
            }
        }
        val byKey = rows.associateBy { it.folder + "\u0000" + it.name }
        val suffix = Regex("^(.*) \\((\\d+)\\)(\\.[^.]+)?$")
        for (r in rows) {
            val m = suffix.matchEntire(r.name) ?: continue
            val base = m.groupValues[1] + m.groupValues[3]
            val original = byKey[r.folder + "\u0000" + base] ?: continue
            if (original.size == r.size && r.size > 0) runCatching { resolver.delete(android.content.ContentUris.withAppendedId(collection, r.id), null, null) }
        }
    }

    /** This app's files still marked pending in MediaStore (a write cut short) are removed before new ones are written. */
    private fun dropPendingWrites() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val resolver = context.contentResolver
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName))
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) runCatching { resolver.delete(android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
            }
        }
    }

    private data class Written(val folder: String, val name: String, val mediaId: Long, val absolutePath: String)

    private fun writeIntoFolders(file: File, folder: String, name: String): Written {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
        }
        // each kind into a folder Android lets it live in: its own when allowed, else a folder of the same name there
        fun under(vararg tops: String) = tops.any { folder.equals(it, true) || folder.startsWith("$it/", true) }
        val (allowed, fallback) = when {
            mime.startsWith("video/") -> under("DCIM", "Movies", "Pictures") to "Movies/"
            mime == "application/pdf" -> under("Download", "Documents") to "Documents/"
            else -> under("DCIM", "Pictures") to "Pictures/"
        }
        val target = (if (allowed) folder else fallback + folder).trim('/') + "/"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val root = android.os.Environment.getExternalStorageDirectory()
            val dir = File(root, target).apply { mkdirs() }
            var out = File(dir, name)
            var n = 1
            while (out.exists()) out = File(dir, name.substringBeforeLast('.') + " (" + n++ + ")" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
            file.copyTo(out, overwrite = false)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mime), null)
            val found = MediaScanner.findByPath(context, out.absolutePath)
            return Written(target, out.name, found?.mediaId ?: 0L, out.absolutePath)
        }
        val collection = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mime == "application/pdf" -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // the same file already there (written by an earlier run): used as it is, never written twice
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(target, name), null)?.use { c ->
            if (c.moveToFirst() && c.getLong(1) == file.length()) {
                @Suppress("DEPRECATION")
                return Written(target, name, c.getLong(0), c.getString(2).orEmpty())
            }
        }
        val created = resolver.insert(collection, values) ?: throw BackendException("cannot write into $target")
        try {
            val out = resolver.openOutputStream(created, "w") ?: throw java.io.IOException("no output stream")
            out.use { stream -> file.inputStream().use { it.copyTo(stream, 64 * 1024) } }
            resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(created, null, null) }
            throw BackendException(e.message ?: "cannot write")
        }
        var shownName = name
        var shownFolder = target
        var absolute = ""
        @Suppress("DEPRECATION")
        resolver.query(created, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { shownName = it }
                c.getString(1)?.let { shownFolder = it }
                c.getString(2)?.let { absolute = it }
            }
        }
        return Written(shownFolder, shownName, android.content.ContentUris.parseId(created), absolute)
    }

    private fun guard() {
        if (StorageWorker.stopRequested.get()) throw StoppedException()
        checkBattery()
        if (!networkAllowed()) throw MeteredException()
    }

    private fun checkBattery() {
        if (Actions.batteryPercent(context) < BATTERY_PAUSE_PERCENT) throw LowBatteryException()
    }

    private fun networkAllowed(): Boolean = prefs.useMobileData || !Actions.onMeteredNetwork(context)

    private fun report(status: String, uploaded: Int = 0, restored: Int = 0, failed: Int = 0, message: String = "") =
        StorageReport(System.currentTimeMillis(), status, uploaded, restored, failed, message)

    companion object {
        const val RESTORE_EDGE = 1280
        const val FULL_HD_EDGE = 1920
        const val BATTERY_PAUSE_PERCENT = 10
        private const val BATCH = 200
        private const val MANIFEST_EVERY = 200
        private const val MANIFEST_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val CONFIG_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val CONFIG_ASK_MS = 60 * 60 * 1000L

        /**
         * The backend as typed, tried for real: connect, the index's folder made, a small file written, read back
         * and removed. The host key seen on the way (SFTP) comes back inside the config. Throws [BackendException].
         */
        fun test(config: StorageConfig, indexName: String): StorageConfig {
            var hostKey = config.field("host_key")
            val probed = config.copy(fields = config.fields - "host_key")
            val backend = Backend.open(if (hostKey.isBlank()) probed else config) { seen -> hostKey = seen }
            try {
                backend.connect()
                val layout = Layout(config, indexName)
                backend.mkdirs(layout.home)
                val bytes = "opensolr-photos".toByteArray()
                backend.put(layout.probe, bytes.size.toLong()) { bytes.inputStream() }
                val size = backend.stat(layout.probe)
                if (size != bytes.size.toLong()) throw BackendException("The test file could not be read back")
                backend.delete(layout.probe)
            } finally {
                backend.close()
            }
            return if (config.kind == StorageConfig.KIND_SFTP) config.copy(fields = config.fields + ("host_key" to hostKey)) else config
        }
    }
}
