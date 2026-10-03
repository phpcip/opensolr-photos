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
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [hits] as one PDF: the file is named here, the place to save it is picked in the system picker, one 1024 px page per photo. */
@Composable
fun PdfExport(hits: List<PhotoHit>, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.pdf_default_name, java.text.SimpleDateFormat("MM-dd-yyyy", java.util.Locale.US).format(java.util.Date()))
    var name by remember { mutableStateOf(defaultName) }
    var picking by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(0) }
    fun close() { job?.cancel(); onDismiss() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        picking = false
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = fileName(name)
        job = scope.launch {
            withContext(Dispatchers.IO) { writePdf(context, hits, uri, fileName) { n -> done = n } }
            onDismiss()
        }
    }

    if (picking) return
    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = {
            if (job == null) {
                TextButton(onClick = { picking = true; picker.launch(fileName(name)) }, enabled = cleanName(name).isNotEmpty()) {
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
                    onImeAction = { if (cleanName(name).isNotEmpty()) { picking = true; picker.launch(fileName(name)) } },
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

/** Writes the pages into [target]; a file left with no page, a failed write or a cancel removes it again. */
private suspend fun writePdf(context: Context, hits: List<PhotoHit>, target: Uri, fileName: String, progress: (Int) -> Unit) {
    val sources = Actions.contentUris(context, hits)
    if (sources.isEmpty()) {
        discard(context, target)
        toast(context, AppText.s(R.string.ac_photos_gone))
        return
    }
    var pages = 0
    try {
        val stream = context.contentResolver.openOutputStream(target, "wt") ?: throw java.io.IOException("no output stream")
        stream.buffered(BUFFER).use { out ->
            val pdf = PdfWriter(out, fileName.removeSuffix(PDF_EXT))
            sources.forEachIndexed { i, source ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val image = runCatching { PhotoReader.pdfImage(context, source, PDF_EDGE_PX) }.getOrNull()
                if (image != null) pdf.addPage(image.jpeg, image.width, image.height)
                progress(i + 1)
            }
            pages = pdf.pages
            if (pages > 0) pdf.finish()
        }
    } catch (e: CancellationException) {
        discard(context, target)
        throw e
    } catch (e: Exception) {
        discard(context, target)
        toast(context, AppText.s(R.string.pdf_failed))
        return
    }
    when {
        pages == 0 -> { discard(context, target); toast(context, AppText.s(R.string.ac_photos_gone)) }
        pages < hits.size -> toast(context, AppText.s(R.string.pdf_saved_some, fileName, Actions.formatCount(pages.toLong()), Actions.formatCount(hits.size.toLong())))
        else -> toast(context, AppText.s(R.string.pdf_saved, fileName))
    }
}

private fun discard(context: Context, uri: Uri) {
    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
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
private const val PDF_EDGE_PX = 1024
private const val MAX_NAME = 120
private const val BUFFER = 64 * 1024
