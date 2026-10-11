package com.opensolr.photos.ui

import android.app.WallpaperManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets [uri] as the wallpaper of the home screen, the lock screen, or both. */
@Composable
fun WallpaperChooser(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun set(which: Int) {
        if (working) return
        working = true
        scope.launch {
            val done = withContext(Dispatchers.IO) { Actions.setWallpaper(context, uri, which) }
            Toast.makeText(context, AppText.s(if (done) R.string.ac_wallpaper_set else R.string.ac_no_wallpaper), Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        confirmButton = {},
        dismissButton = { if (!working) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.ink) } },
        title = { Text(stringResource(R.string.ac_set_wallpaper)) },
        text = { com.opensolr.photos.ui.KeyboardBack();
            HideStatusBar()
            if (working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.hairline)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceOption(stringResource(R.string.wp_home), stringResource(R.string.wp_home_sub)) { set(WallpaperManager.FLAG_SYSTEM) }
                    ChoiceOption(stringResource(R.string.wp_lock), stringResource(R.string.wp_lock_sub)) { set(WallpaperManager.FLAG_LOCK) }
                    ChoiceOption(stringResource(R.string.wp_both), stringResource(R.string.wp_both_sub)) { set(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                }
            }
        },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}
