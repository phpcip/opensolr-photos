package com.opensolr.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensolr.photos.ui.screens.AccountScreen
import com.opensolr.photos.ui.screens.FoldersScreen
import com.opensolr.photos.ui.screens.PermissionsScreen
import com.opensolr.photos.ui.screens.SearchScreen
import com.opensolr.photos.ui.screens.SetupScreen
import com.opensolr.photos.ui.screens.SignInScreen
import com.opensolr.photos.ui.screens.SyncScreen
import com.opensolr.photos.ui.screens.WelcomeScreen
import com.opensolr.photos.ui.theme.LocalPalette

/**
 * Draws the current screen and the one app-wide dialog.
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current

    BackHandler(enabled = state.screen == Screen.Sync || state.screen == Screen.Account ||
        (state.screen == Screen.Folders && state.foldersReturnTo == Screen.Sync)) {
        viewModel.back()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.paper)
            .safeDrawingPadding()
    ) {
        when (state.screen) {
            Screen.SignIn -> SignInScreen(state, viewModel)
            Screen.Welcome -> WelcomeScreen(state, viewModel)
            Screen.Permissions -> PermissionsScreen(state, viewModel)
            Screen.Folders -> FoldersScreen(state, viewModel)
            Screen.Setup -> SetupScreen(state, viewModel)
            Screen.Search -> SearchScreen(state, viewModel)
            Screen.Sync -> SyncScreen(state, viewModel)
            Screen.Account -> AccountScreen(state, viewModel)
        }
    }

    if (state.showBusyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBusyDialog() },
            confirmButton = { TextButton(onClick = { viewModel.dismissBusyDialog() }) { Text("OK") } },
            title = { Text("A sync is already running") },
            text = { Text("Your photos are being synced right now. Please be patient, it finishes on its own, and a new sync is not needed.") },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}
