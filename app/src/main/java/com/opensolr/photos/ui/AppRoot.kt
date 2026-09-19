package com.opensolr.photos.ui

import com.opensolr.photos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensolr.photos.ui.screens.AccountScreen
import com.opensolr.photos.ui.screens.AlbumsScreen
import com.opensolr.photos.ui.screens.FoldersScreen
import com.opensolr.photos.ui.screens.MapScreen
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

    BackHandler(enabled = state.screen == Screen.Sync || state.screen == Screen.Account || state.screen == Screen.Map || state.screen == Screen.Albums ||
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
            Screen.Map -> MapScreen(state, viewModel)
            Screen.Albums -> AlbumsScreen(state, viewModel)
        }
    }

    // A phone without photos of its own, on an account that has some: which phone is this?
    if (state.deviceChoices.isNotEmpty()) {
        DeviceChoiceDialog(state, viewModel)
    }

    // A new index configuration ships with this version: the owner is told plainly that the
    // index is reset and fully re-synced, and it happens only with their consent.
    if (state.rebuildRequired) {
        AlertDialog(
            onDismissRequest = { viewModel.postponeRebuild() },
            confirmButton = { TextButton(onClick = { viewModel.approveRebuild() }) { Text(stringResource(R.string.rt_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { viewModel.postponeRebuild() }) { Text(stringResource(R.string.rt_later)) } },
            title = { Text(stringResource(R.string.rt_reset_title)) },
            text = { Text(stringResource(R.string.rt_reset_text)) },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }

    if (state.showBusyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBusyDialog() },
            confirmButton = { TextButton(onClick = { viewModel.dismissBusyDialog() }) { Text(stringResource(R.string.rt_ok)) } },
            title = { Text(stringResource(R.string.rt_busy_title)) },
            text = { Text(stringResource(R.string.rt_busy_text)) },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}

/**
 * "Which one of these is your device?": the account's photo indexes by phone name. Picking one
 * carries on with its photos; the discreet last choice starts a new one.
 */
@Composable
private fun DeviceChoiceDialog(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = { TextButton(onClick = { viewModel.chooseNewDevice() }) { Text(stringResource(R.string.rt_new_device), color = p.muted) } },
        title = { Text(stringResource(R.string.rt_which_device)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                state.deviceChoices.forEach { choice ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.chooseDevice(choice) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(choice.deviceName ?: stringResource(R.string.rt_unknown_phone), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                        val photos = pluralStringResource(R.plurals.rt_n_photos, choice.numDocs, Actions.formatCount(choice.numDocs.toLong()))
                        val synced = stringResource(R.string.rt_last_synced, Actions.formatDate(choice.lastIndex * 1000L).substringBefore(' '))
                        val since = stringResource(R.string.rt_since, Actions.formatDate(choice.created * 1000L).substringBefore(' '))
                        val details = buildList {
                            if (choice.numDocs > 0) add(photos)
                            if (choice.lastIndex > 0) add(synced)
                            else if (choice.created > 0) add(since)
                        }
                        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    HorizontalDivider(color = p.hairline)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.rt_device_note),
                    style = MaterialTheme.typography.bodySmall, color = p.muted,
                )
            }
        },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}
