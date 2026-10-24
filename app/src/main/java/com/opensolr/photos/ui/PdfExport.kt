package com.opensolr.photos.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.media.PdfWriter
import com.opensolr.photos.media.PdfPhoto
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [hits] as one PDF: the file is named here and saved in the folder of the first photo, one page per photo at its full size. */
@Composable
fun PdfExport(hits: List<PhotoHit>, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.pdf_default_name, java.text.SimpleDateFormat("MM-dd-yyyy", java.util.Locale.US).format(java.util.Date()))
    var name by remember { mutableStateOf(defaultName) }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(0) }
    fun close() { job?.cancel(); onDismiss() }

    // saved at once next to the first photo: it shows in the grid, first, as soon as the phone is synced
    fun save() {
        val fileName = fileName(name)
        job = scope.launch {
            withContext(Dispatchers.IO) { writePdf(context, hits, fileName) { n -> done = n } }
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = {
            if (job == null) {
                TextButton(onClick = { save() }, enabled = cleanName(name).isNotEmpty()) {
                    Text(stringResource(R.string.pdf_pick), color = if (cleanName(name).isNotEmpty()) p.accent else p.hairline)
                }
            }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel), color = p.ink) } },
        title = { Text(pluralStringResource(R.plurals.pdf_title, hits.size, Actions.formatCount(hits.size.toLong()))) },
        text = { KeyboardBack()
            if (job != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sh_preparing, Actions.formatCount(done.toLong()), Actions.formatCount(hits.size.toLong())), color = p.ink)
                    LinearProgressIndicator(
                        progress = { if (hits.isEmpty()) 0f else done.toFloat() / hits.size },
                        modifier = Modifier.fillMaxWidth(),
                        color = p.accent,
                        trackColor = p.hairline,
                    )
                }
            } else {
                OutlinedTextBox(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.pdf_name)) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onImeAction = { if (cleanName(name).isNotEmpty()) save() },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink, focusedLabelColor = p.accent, unfocusedLabelColor = p.muted),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

/**
 * Writes the pages into a new file in the folder of the first photo that is on this phone. With All files access
 * (or before Android 10) the file goes into that very folder; otherwise into the folder of the same name under
 * Documents, where Android lets a PDF be written. A failed write or a cancel removes the file again.
 */
private suspend fun writePdf(context: Context, hits: List<PhotoHit>, fileName: String, progress: (Int) -> Unit) {
    // a photo this phone holds only as its 720p copy goes into the PDF at its backup quality, brought down first
    val pulled = java.io.File(context.cacheDir, "pdf_sources").apply { deleteRecursively(); mkdirs() }
    val sources = try { pdfSources(context, hits, pulled) } catch (e: Exception) { emptyList() }
    if (sources.isEmpty()) {
        pulled.deleteRecursively()
        toast(context, AppText.s(R.string.ac_photos_gone))
        return
    }
    val folder = hits.first().folder.trim('/')
    val target = newTarget(context, folder, fileName) ?: run { pulled.deleteRecursively(); toast(context, AppText.s(R.string.pdf_failed)); return }
    var pages = 0
    try {
        target.open().buffered(BUFFER).use { out ->
            val pdf = PdfWriter(out, fileName.removeSuffix(PDF_EXT))
            sources.forEachIndexed { i, source ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                PdfPhoto.add(context, source, pdf)
                progress(i + 1)
            }
            pages = pdf.pages
            if (pages > 0) pdf.finish()
        }
    } catch (e: CancellationException) {
        target.discard()
        pulled.deleteRecursively()
        throw e
    } catch (e: Exception) {
        target.discard()
        pulled.deleteRecursively()
        toast(context, AppText.s(R.string.pdf_failed))
        return
    }
    pulled.deleteRecursively()
    if (pages == 0) { target.discard(); toast(context, AppText.s(R.string.ac_photos_gone)); return }
    target.done()
    // the folder it went into is synced, so the PDF is indexed and shows in the grid
    val prefs = com.opensolr.photos.data.AppPrefs(context)
    val folderKey = com.opensolr.photos.media.MediaScanner.folderKey(target.folder)
    if (prefs.folders.none { folderKey.startsWith(com.opensolr.photos.media.MediaScanner.folderKey(it), ignoreCase = true) }) prefs.folders = prefs.folders + folderKey
    // the sync finds it right away: indexed, it is the newest item and shows first
    com.opensolr.photos.sync.SyncScheduler.runNow(context)
    if (pages < hits.size) toast(context, AppText.s(R.string.pdf_saved_some, target.shownName, Actions.formatCount(pages.toLong()), Actions.formatCount(hits.size.toLong())))
    else toast(context, AppText.s(R.string.pdf_saved, target.shownName))
}

/**
 * One readable source per photo, in order: the file on this phone; for a photo held here only as its 720p copy,
 * the backup's copy brought into [into] (the 720p copy when there is no backup or it cannot be reached).
 */
private fun pdfSources(context: Context, hits: List<PhotoHit>, into: java.io.File): List<Uri> {
    val prefs = com.opensolr.photos.data.AppPrefs(context)
    val config = prefs.storage
    val index = prefs.connection?.indexName
    val cache = com.opensolr.photos.data.PhotoCache.of(context)
    val cloudOnly = hits.filter { com.opensolr.photos.storage.CloudFiles.has(it.id) && !com.opensolr.photos.storage.LocalFiles.has(it.id) }
    val full = HashMap<String, Uri>()
    if (config != null && index != null && cloudOnly.isNotEmpty()) {
        runCatching {
            com.opensolr.photos.storage.Backend.open(config).use { backend ->
                backend.connect()
                val layout = com.opensolr.photos.storage.Layout(config, index)
                for (hit in cloudOnly) {
                    val remote = cache.remote(hit.id) ?: continue
                    val name = remote.path.substringAfterLast('/')
                    val file = java.io.File(into, hit.id + "." + name.substringAfterLast('.', "jpg"))
                    val ok = runCatching { file.outputStream().use { backend.get(layout.main(remote.path.substringBeforeLast('/', ""), name), it) } }.isSuccess
                    if (ok && file.length() > 0) full[hit.id] = Uri.fromFile(file) else file.delete()
                }
            }
        }
    }
    val readable = Actions.contentUrisByHit(context, hits.filter { it.id !in full })
    return hits.mapNotNull { full[it.id] ?: readable[it.id] }
}

/** Where the PDF is written, and how it is finished or dropped. */
private class PdfTarget(val shownName: String, val folder: String, val open: () -> java.io.OutputStream, val done: () -> Unit, val discard: () -> Unit)

@Suppress("DEPRECATION")
private fun newTarget(context: Context, folder: String, fileName: String): PdfTarget? {
    val direct = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager())
    if (direct) {
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), folder).apply { mkdirs() }
        var file = java.io.File(dir, fileName)
        var n = 1
        while (file.exists()) file = java.io.File(dir, fileName.removeSuffix(PDF_EXT) + " (" + n++ + ")" + PDF_EXT)
        val f = file
        return PdfTarget(f.name, folder, { java.io.FileOutputStream(f) }, {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(PDF_MIME), null)
        }, { f.delete() })
    }
    val resolver = context.contentResolver
    val relative = com.opensolr.photos.storage.StorageEngine.placedFolder(folder, fileName)
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative)
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return null
    return PdfTarget(fileName, relative, { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no output stream") }, {
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }, { runCatching { resolver.delete(uri, null, null) } })
}

private fun toast(context: Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}

// characters no file system takes, and control characters
private fun cleanName(raw: String): String = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')

private fun fileName(raw: String): String {
    val clean = cleanName(raw)
    return if (clean.endsWith(PDF_EXT, ignoreCase = true)) clean else clean + PDF_EXT
}

private const val PDF_MIME = "application/pdf"
private const val PDF_EXT = ".pdf"
private const val MAX_NAME = 120
private const val BUFFER = 64 * 1024
