package com.opensolr.photos.ui

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * While the keyboard is up, the first back (gesture or button) only puts the keyboard away, nothing else.
 * Placed inside every window the owner can type in (the screen, each sheet, each dialog): back handlers
 * belong to the window they are composed in. On Android 13+ the sheet windows register their own system back
 * callback, so this one is registered above everything (overlay priority) for as long as the keyboard is up.
 */
@Composable
fun KeyboardBack() {
    val density = LocalDensity.current
    val up = WindowInsets.ime.getBottom(density) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        DisposableEffect(up, view) {
            val dispatcher = if (up) view.findOnBackInvokedDispatcher() else null
            val callback = OnBackInvokedCallback {
                keyboard?.hide()
                focus.clearFocus(force = true)
            }
            dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
        }
    } else {
        BackHandler(enabled = up) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }
}
