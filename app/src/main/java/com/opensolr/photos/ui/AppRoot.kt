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
import com.opensolr.photos.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensolr.photos.ui.screens.AccountScreen
import com.opensolr.photos.ui.screens.AlbumsScreen
import com.opensolr.photos.ui.screens.StatsScreen
import com.opensolr.photos.ui.screens.FoldersScreen
import com.opensolr.photos.ui.screens.MapScreen
import com.opensolr.photos.ui.screens.PermissionsScreen
import com.opensolr.photos.ui.screens.SearchScreen
import com.opensolr.photos.ui.screens.SetupScreen
import com.opensolr.photos.ui.screens.SignInScreen
import com.opensolr.photos.ui.screens.SyncScreen
import com.opensolr.photos.ui.screens.WelcomeScreen
import androidx.compose.animation.togetherWith
import com.opensolr.photos.ui.theme.LocalPalette

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current

    BackHandler(enabled = state.screen == Screen.Sync || state.screen == Screen.Account || state.screen == Screen.Storage || state.screen == Screen.Map || state.screen == Screen.Albums || state.screen == Screen.Stats || (state.screen == Screen.Search && state.returnToStats) ||
        (state.screen == Screen.Folders && state.foldersReturnTo == Screen.Sync)) {
        viewModel.back()
    }

    // Videos on (the default) and Android 13+: the clips need their own permission, asked once on a phone set up before it existed
    val context = androidx.compose.ui.platform.LocalContext.current
    val videoAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.forceResync()
    }
    androidx.compose.runtime.LaunchedEffect(state.screen, state.includeVideos) {
        if (state.screen == Screen.Search && state.includeVideos && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            viewModel.askVideoPermissionOnce()
        ) videoAsk.launch(android.Manifest.permission.READ_MEDIA_VIDEO)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.paper)
            .safeDrawingPadding()
    ) {
        // Screens fade and lift into place instead of snapping: short enough to stay out of the way
        androidx.compose.animation.AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(SCREEN_FADE_MS)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(SCREEN_FADE_MS)) { it / 24 })
                    .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(SCREEN_FADE_MS / 2)))
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.SignIn -> SignInScreen(state, viewModel)
                Screen.Welcome -> WelcomeScreen(state, viewModel)
                Screen.Permissions -> PermissionsScreen(state, viewModel)
                Screen.Folders -> FoldersScreen(state, viewModel)
                Screen.Setup -> SetupScreen(state, viewModel)
                Screen.Search -> SearchScreen(state, viewModel)
                Screen.Sync -> SyncScreen(state, viewModel)
                Screen.Account -> AccountScreen(state, viewModel)
                Screen.Storage -> com.opensolr.photos.ui.screens.StorageScreen(state, viewModel)
                Screen.Map -> MapScreen(state, viewModel)
                Screen.Albums -> AlbumsScreen(state, viewModel)
                Screen.Stats -> StatsScreen(state, viewModel)
            }
        }
    }

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


private const val SCREEN_FADE_MS = 180
