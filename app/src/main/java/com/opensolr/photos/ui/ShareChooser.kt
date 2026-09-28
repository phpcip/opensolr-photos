package com.opensolr.photos.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensolr.photos.R
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share [hits] as the original files or as 1024 px copies; the copies are made here, with progress, and can be cancelled. */
@Composable
fun ShareChooser(hits: List<PhotoHit>, fullScreen: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(0) }
    val preparing = job != null
    fun close() { job?.cancel(); onDismiss() }

    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel), color = p.ink) } },
        title = { Text(pluralStringResource(R.plurals.sh_title, hits.size, Actions.formatCount(hits.size.toLong()))) },
        text = {
            // over the full screen viewer the status bar stays hidden
            if (fullScreen) HideStatusBar()
            if (preparing) {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceOption(stringResource(R.string.sh_original), stringResource(R.string.sh_original_sub)) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Actions.sharePhotos(context, hits) }
                            onDismiss()
                        }
                    }
                    ChoiceOption(stringResource(R.string.sh_small), stringResource(R.string.sh_small_sub)) {
                        job = scope.launch {
                            withContext(Dispatchers.IO) {
                                Actions.shareCopies(context, hits, Actions.SHARE_EDGE_PX) { n, _ -> done = n }
                            }
                            onDismiss()
                        }
                    }
                }
            }
        },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

@Composable
internal fun ChoiceOption(title: String, sub: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OptionCorner)
            .border(1.dp, p.ink, OptionCorner)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = p.muted)
    }
}

private val OptionCorner = RoundedCornerShape(2.dp)

/** Keeps the status bar hidden while the dialog this is composed in is shown (over the full screen viewer). */
@Composable
internal fun HideStatusBar() {
    val dialogView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
