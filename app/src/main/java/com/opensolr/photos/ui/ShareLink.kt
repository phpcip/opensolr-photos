package com.opensolr.photos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opensolr.photos.R
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where a link form stands: being filled in, being made, made (address and count), or failed. */
private sealed interface LinkStep {
    data object Form : LinkStep
    data object Making : LinkStep
    data class Made(val url: String, val count: Int, val days: Int) : LinkStep
}

/** The link copied: Android shows that it was. */
fun copyLink(context: Context, url: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("Opensolr Photos", url))
}

/**
 * The album link dialog: a name, never or after a number of days, then Copy link. The link is copied as it is
 * made and shown, ready to paste anywhere. [live]: an album (words and filters) rather than chosen items.
 */
@Composable
fun ShareLinkDialog(spec: JSONObject, title: String, live: Boolean, viewModel: AppViewModel, fullScreen: Boolean = false, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var step by remember { mutableStateOf<LinkStep>(LinkStep.Form) }
    AlertDialog(
        onDismissRequest = { if (step != LinkStep.Making) onDismiss() },
        confirmButton = {},
        dismissButton = {},
        title = { Text(if (step is LinkStep.Made) stringResource(R.string.sl_copied) else if (live) stringResource(R.string.sl_album_title) else stringResource(R.string.sl_items_title, title)) },
        text = {
            KeyboardBack()
            if (fullScreen) HideStatusBar()
            ShareLinkBody(spec, title, live, viewModel, step, { step = it }, onDismiss)
        },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

/** The form and its result, for the dialog above and for the share chooser's own "Copy link". */
@Composable
internal fun ShareLinkForm(spec: JSONObject, title: String, live: Boolean, viewModel: AppViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf<LinkStep>(LinkStep.Form) }
    ShareLinkBody(spec, title, live, viewModel, step, { step = it }, onDismiss)
}

@Composable
private fun ShareLinkBody(spec: JSONObject, title: String, live: Boolean, viewModel: AppViewModel, step: LinkStep, setStep: (LinkStep) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(title) }
    var expires by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("30") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (step) {
            is LinkStep.Made -> {
                val what = pluralStringResource(R.plurals.sl_items, step.count, Actions.formatCount(step.count.toLong()))
                val until = if (step.days > 0) pluralStringResource(R.plurals.sl_for_days, step.days, Actions.formatCount(step.days.toLong())) else stringResource(R.string.sl_never_lc)
                Text(stringResource(R.string.sl_made_text, what, until), color = p.muted)
                SelectionContainer {
                    Text(step.url, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.sl_copy_again), onClick = { copyLink(context, step.url) }, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(R.string.sl_done), onClick = onDismiss, modifier = Modifier.weight(1f))
                }
            }
            else -> {
                val making = step == LinkStep.Making
                Text(stringResource(if (live) R.string.sl_album_text else R.string.sl_items_text), color = p.muted)
                OutlinedTextBox(
                    value = name,
                    onValueChange = { name = it.take(160) },
                    label = { Text(stringResource(R.string.sl_name)) },
                    singleLine = true,
                    enabled = !making,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !making) { expires = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !expires, onClick = { expires = false }, enabled = !making, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.muted))
                    Text(stringResource(R.string.sl_never), color = p.ink)
                }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !making) { expires = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = expires, onClick = { expires = true }, enabled = !making, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.muted))
                    Text(stringResource(R.string.sl_after), color = p.ink)
                }
                // the number of days on its own line, under its choice: nothing wraps at any text size
                Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextBox(
                        value = days,
                        onValueChange = { typed -> days = typed.filter { it.isDigit() }.take(4); expires = true },
                        singleLine = true,
                        enabled = !making,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.sl_days), color = p.ink, maxLines = 1, softWrap = false)
                }
                error?.let { Text(it, color = p.accent) }
                if (making) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = p.accent, trackColor = p.hairline)
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.cancel), onClick = onDismiss, enabled = !making, modifier = Modifier.weight(1f))
                    GhostButton(
                        stringResource(R.string.sl_copy_link),
                        enabled = !making && (!expires || (days.toIntOrNull() ?: 0) in 1..3650),
                        onClick = {
                            val n = if (expires) (days.toIntOrNull() ?: 0).coerceIn(1, 3650) else 0
                            error = null
                            setStep(LinkStep.Making)
                            scope.launch {
                                try {
                                    val (url, count) = viewModel.makeShareLink(spec, name, n)
                                    if (url.isBlank()) throw IllegalStateException(context.getString(R.string.sl_no_address))
                                    copyLink(context, url)
                                    setStep(LinkStep.Made(url, count, n))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = context.getString(R.string.sl_failed, e.message ?: e.javaClass.simpleName)
                                    setStep(LinkStep.Form)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
