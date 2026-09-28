package com.opensolr.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * While the keyboard is up, the first back (gesture or button) only puts the keyboard away, nothing else.
 * Placed inside every window the owner can type in (the screen, each sheet, each dialog): back handlers
 * belong to the window they are composed in.
 */
@Composable
fun KeyboardBack() {
    val density = LocalDensity.current
    val up = WindowInsets.ime.getBottom(density) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BackHandler(enabled = up) {
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
}
