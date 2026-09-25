package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.Haptics
import com.opensolr.photos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.InfoRow
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.Screen
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

@Composable
fun SignInScreen(state: UiState, viewModel: AppViewModel) {
    val view = androidx.compose.ui.platform.LocalView.current
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp)
    ) {
        Text(stringResource(R.string.ob_tagline), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.ob_lead),
            style = MaterialTheme.typography.bodyLarge, color = p.muted,
        )
        Spacer(Modifier.height(32.dp))

        state.notice?.let {
            Notice(it)
            Spacer(Modifier.height(20.dp))
        }
        state.signInError?.let {
            Notice(it, title = stringResource(R.string.ob_signin_failed))
            Spacer(Modifier.height(20.dp))
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 14.dp))
                Text(stringResource(R.string.ob_signing_in), style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
        } else {
            AccentButton(stringResource(R.string.ob_signin), onClick = { viewModel.beginSignIn(context) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        GhostButton(stringResource(R.string.ob_create), onClick = { Actions.openUrl(context, "https://opensolr.com/register") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(36.dp))
        SectionLabel(stringResource(R.string.ob_how))
        Spacer(Modifier.height(6.dp))
        listOf(
            stringResource(R.string.ob_how_1),
            stringResource(R.string.ob_how_2),
            stringResource(R.string.ob_how_3),
        ).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 10.dp))
            HorizontalDivider(color = p.hairline)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.ob_open_source),
            style = MaterialTheme.typography.bodySmall, color = p.accent,
            modifier = Modifier.clickable { Haptics.tap(view); Actions.openUrl(context, Actions.PROJECT_URL) },
        )
    }
}

@Composable
fun WelcomeScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val account = state.account
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(stringResource(R.string.ob_signed_in), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(12.dp))
        Text(state.email ?: "", style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(28.dp))

        if (account != null) {
            if (!account.vectorAllowed) {
                Notice(
                    stringResource(R.string.ob_no_ai_text),
                    title = stringResource(R.string.ob_no_ai_title),
                )
                Spacer(Modifier.height(20.dp))
            }

            SectionLabel(stringResource(R.string.ob_covers))
            InfoRow(stringResource(R.string.ob_plan), account.planLabel, onOpen = { Actions.openUrl(context, Actions.DASHBOARD_URL) })
            InfoRow(stringResource(R.string.ob_meaning), if (account.vectorAllowed) stringResource(R.string.ob_included) else stringResource(R.string.ob_not_included))
            InfoRow(
                stringResource(R.string.ob_per_month),
                account.photosPerMonth?.let { stringResource(R.string.ob_about, Actions.formatCount(it.toLong())) } ?: stringResource(R.string.ob_no_cap),
            )
            account.photosLeftThisMonth?.let { InfoRow(stringResource(R.string.ob_left), stringResource(R.string.ob_about, Actions.formatCount(it.toLong()))) }
            InfoRow(stringResource(R.string.ob_disk), Actions.formatMb(account.diskLimitMb))
            InfoRow(stringResource(R.string.ob_bandwidth), Actions.formatMb(account.bandwidthLimitMb))
            InfoRow(stringResource(R.string.ob_indexes), stringResource(R.string.ob_x_of_y, account.indexesUsed.toString(), account.indexLimit.toString()))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.ob_cost),
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
        }

        Spacer(Modifier.height(28.dp))
        AccentButton(stringResource(R.string.ob_continue), onClick = { viewModel.continueFromWelcome() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PermissionsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    val partialPermission = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    fun photosAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, partialPermission) == PackageManager.PERMISSION_GRANTED)
    val requested = buildList {
        add(photoPermission)
        if (Build.VERSION.SDK_INT >= 34) add(partialPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[photoPermission] == true || result[partialPermission] == true || photosAllowed()
        viewModel.onPermissionsResult(granted)
    }

    LaunchedEffect(Unit) {
        if (photosAllowed() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ) {
            viewModel.onPermissionsResult(true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(stringResource(R.string.ob_allow_title), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.ob_allow_lead), style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.ob_for))
        InfoRow(stringResource(R.string.ob_p_photos), stringResource(R.string.ob_p_photos_why))
        InfoRow(stringResource(R.string.ob_p_locations), stringResource(R.string.ob_p_locations_why))
        InfoRow(stringResource(R.string.ob_p_notifications), stringResource(R.string.ob_p_notifications_why))
        InfoRow(stringResource(R.string.ob_p_location), stringResource(R.string.ob_p_location_why))
        state.permissionError?.let {
            Spacer(Modifier.height(20.dp))
            Notice(it)
        }
        Spacer(Modifier.height(28.dp))
        AccentButton(stringResource(R.string.ob_allow), onClick = { launcher.launch(requested.toTypedArray()) }, modifier = Modifier.fillMaxWidth())
    }
}

private data class FolderNode(val path: String, val name: String, val count: Int, val hasChildren: Boolean)

private fun childFolders(all: List<com.opensolr.photos.media.PhotoFolder>, path: String): List<FolderNode> {
    val counts = LinkedHashMap<String, Int>()
    val withChildren = HashSet<String>()
    all.forEach { folder ->
        val rel = folder.relativePath
        if (rel.length <= path.length || !rel.startsWith(path, ignoreCase = true)) return@forEach
        val rest = rel.substring(path.length).trim('/')
        if (rest.isEmpty()) return@forEach
        val full = path + rest.substringBefore('/') + "/"
        counts[full] = (counts[full] ?: 0) + folder.count
        if (rest.contains('/')) withChildren += full
    }
    return counts.map { (full, count) ->
        FolderNode(full, full.trimEnd('/').substringAfterLast('/'), count, full in withChildren)
    }.sortedBy { it.name.lowercase() }
}

private fun coveringFolder(selected: Set<String>, path: String): String? =
    selected.firstOrNull { it.isNotEmpty() && !it.equals(path, ignoreCase = true) && path.startsWith(it, ignoreCase = true) }

@Composable
fun FoldersScreen(state: UiState, viewModel: AppViewModel) {
    val view = androidx.compose.ui.platform.LocalView.current
    val p = LocalPalette.current

    var path by remember { mutableStateOf("") }
    val children = remember(state.folders, path) { childFolders(state.folders, path) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(stringResource(R.string.ob_folders), onBack = if (state.foldersReturnTo == Screen.Sync) ({ viewModel.back() }) else null)
        Text(
            stringResource(R.string.ob_folders_lead),
            style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        val steps = path.trim('/').split('/').filter { it.isNotEmpty() }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ob_all_folders),
                style = MaterialTheme.typography.labelLarge,
                color = if (steps.isEmpty()) p.ink else p.accent,
                modifier = Modifier.clickable { Haptics.tap(view); path = "" }.padding(end = 6.dp),
            )
            steps.forEachIndexed { index, step ->
                Text("/", style = MaterialTheme.typography.labelLarge, color = p.muted, modifier = Modifier.padding(end = 6.dp))
                val upTo = steps.take(index + 1).joinToString("/") + "/"
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (index == steps.lastIndex) p.ink else p.accent,
                    modifier = Modifier.clickable { Haptics.tap(view); path = upTo }.padding(end = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.foldersLoading) {
            CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(16.dp))
        } else if (state.folders.isEmpty()) {
            Notice(stringResource(R.string.ob_no_photos))
        } else if (children.isEmpty()) {
            Notice(stringResource(R.string.ob_nothing_inside))
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(children, key = { it.path }) { folder ->
                val covered = coveringFolder(state.selectedFolders, folder.path)
                val ticked = folder.path in state.selectedFolders || covered != null
                Row(
                    Modifier
                        .fillMaxWidth()

                        .clickable {
                            if (folder.hasChildren) { Haptics.tap(view); path = folder.path }
                            else if (covered == null) { Haptics.toggle(view, !ticked); viewModel.toggleFolder(folder.path) }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ticked,
                        enabled = covered == null,
                        onCheckedChange = { Haptics.toggle(view, it); viewModel.toggleFolder(folder.path) },
                        colors = CheckboxDefaults.colors(checkedColor = p.accentFill, uncheckedColor = p.muted, checkmarkColor = p.onAccentFill),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                        Text(
                            if (covered != null) stringResource(R.string.ob_included_by, covered)
                            else pluralStringResource(R.plurals.ob_n_photos, folder.count, Actions.formatCount(folder.count.toLong())).let {
                                if (folder.hasChildren) stringResource(R.string.ob_with_folders, it) else it
                            },
                            style = MaterialTheme.typography.bodySmall, color = p.muted,
                        )
                    }
                    if (folder.hasChildren) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.ob_open_folder, folder.name), tint = p.muted)
                    }
                }
                HorizontalDivider(color = p.hairline)
            }
        }

        Text(
            if (state.selectedFolders.isEmpty()) stringResource(R.string.ob_nothing_chosen)
            else state.selectedFolders.sorted().joinToString(", ") { it.trimEnd('/') },
            style = MaterialTheme.typography.bodySmall, color = p.muted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        AccentButton(
            if (state.foldersReturnTo == Screen.Sync) stringResource(R.string.ob_save_sync) else stringResource(R.string.ob_continue),
            onClick = { viewModel.saveFolders() },
            enabled = state.selectedFolders.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

@Composable
fun SetupScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(stringResource(R.string.ob_setting_up), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.ob_setup_lead),
            style = MaterialTheme.typography.bodyLarge, color = p.muted,
        )
        Spacer(Modifier.height(32.dp))
        if (state.setupError == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 14.dp))
                Text(state.setupStep.ifBlank { stringResource(R.string.ob_connecting) }, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
        } else {
            Notice(state.setupError, title = stringResource(R.string.ob_setup_failed))
            Spacer(Modifier.height(20.dp))
            AccentButton(stringResource(R.string.ob_try_again), onClick = { viewModel.runSetup() }, modifier = Modifier.fillMaxWidth())
            if (state.setupNeedsUpgrade) {
                Spacer(Modifier.height(12.dp))
                GhostButton(stringResource(R.string.acc_open_account), onClick = { Actions.openUrl(context, Actions.DASHBOARD_URL) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
