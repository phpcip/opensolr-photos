package com.opensolr.photos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * First screen: what the app is, and the one button that signs in with Opensolr.
 */
@Composable
fun SignInScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp)
    ) {
        Text("Find any photo by what is in it.", style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(
            "Opensolr Photos reads every picture in the folders you choose into words, keeps them in your own Opensolr Index, and lets you search your phone like a search engine. Tap a result and it opens in your gallery.",
            style = MaterialTheme.typography.bodyLarge, color = p.muted,
        )
        Spacer(Modifier.height(32.dp))

        state.notice?.let {
            Notice(it)
            Spacer(Modifier.height(20.dp))
        }
        state.signInError?.let {
            Notice(it, title = "Sign-in did not finish")
            Spacer(Modifier.height(20.dp))
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 14.dp))
                Text("Signing in…", style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
        } else {
            AccentButton("Sign in with Opensolr", onClick = { viewModel.beginSignIn(context) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        GhostButton("Create a free Opensolr account", onClick = { Actions.openUrl(context, "https://opensolr.com/register") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(36.dp))
        SectionLabel("How it works")
        Spacer(Modifier.height(6.dp))
        listOf(
            "You sign in on opensolr.com in your browser. The app never sees your password.",
            "The app creates one Opensolr Index for this phone and keeps it in step with your photo folders.",
            "Search matches the words each photo was read into, and with vector search also what your words mean.",
        ).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 10.dp))
            HorizontalDivider(color = p.hairline)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Open source. Learn more at opensolr.com/opensolr-photos",
            style = MaterialTheme.typography.bodySmall, color = p.accent,
            modifier = Modifier.clickable { Actions.openUrl(context, Actions.PROJECT_URL) },
        )
    }
}

/**
 * After sign-in: what the plan allows, in plain numbers, and where to upgrade.
 */
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
        Text("You're signed in.", style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(12.dp))
        Text(state.email ?: "", style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(28.dp))

        if (account != null) {
            if (!account.vectorAllowed) {
                Notice(
                    "On this plan Opensolr does not look at what is in your photos. They are indexed by date, camera, place, file name and the tags you add, and that is all a search can match: \"dog on the beach\" finds nothing unless you tagged it. To have every photo read and searchable by meaning, pick a plan with AI at opensolr.com/pricing. Photos already on the phone are read at the next sync after the upgrade.",
                    title = "Photos are not recognised on this plan",
                )
                Spacer(Modifier.height(20.dp))
            }

            SectionLabel("What your plan covers")
            InfoRow("Plan", account.plan.ifBlank { "Opensolr" })
            InfoRow("Search by meaning", if (account.vectorAllowed) "Included" else "Not included")
            InfoRow(
                "Photos you can add per month",
                account.photosPerMonth?.let { "about " + Actions.formatCount(it.toLong()) } ?: "No monthly cap",
            )
            account.photosLeftThisMonth?.let { InfoRow("Left this month", "about " + Actions.formatCount(it.toLong())) }
            InfoRow("Disk space of the index", Actions.formatMb(account.diskLimitMb))
            InfoRow("Search bandwidth per month", Actions.formatMb(account.bandwidthLimitMb))
            InfoRow("Indexes on the account", "${account.indexesUsed} of ${account.indexLimit}")
            Spacer(Modifier.height(16.dp))
            Text(
                "Each new photo uses ${account.requestsPerPhoto} AI request${if (account.requestsPerPhoto > 1) "s" else ""}: one to read it into words${if (account.vectorAllowed) ", one to turn those words into a search vector" else ""}. Photos already indexed cost nothing again.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(20.dp))
            Notice(
                "If you need more photos per month, or your index goes over its disk space or search bandwidth, upgrade your plan at opensolr.com/pricing.",
                title = "Need more?",
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("See plans at opensolr.com/pricing", onClick = { Actions.openUrl(context, Actions.PRICING_URL) }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(28.dp))
        AccentButton("Continue", onClick = { viewModel.continueFromWelcome() }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Asks for access to photos (required), their location data and notifications (both optional).
 */
@Composable
fun PermissionsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val requested = buildList {
        add(photoPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        // Coarse position only, read once, to create the index on the nearest Opensolr server.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[photoPermission] == true ||
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionsResult(granted)
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED &&
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
        Text("Allow access to your photos", style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text("Four permissions, and only the first one is required.", style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(24.dp))
        SectionLabel("What they are for")
        InfoRow("Photos", "To find and read the photos in the folders you pick")
        InfoRow("Photo locations", "To let you filter by where a photo was taken")
        InfoRow("Notifications", "To show sync progress and plan alerts")
        InfoRow("Your location", "To create your index on the Opensolr server nearest to you")
        state.permissionError?.let {
            Spacer(Modifier.height(20.dp))
            Notice(it)
        }
        Spacer(Modifier.height(28.dp))
        AccentButton("Allow", onClick = { launcher.launch(requested.toTypedArray()) }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Picks the folders to index. DCIM, where the camera saves, is proposed.
 */
@Composable
fun FoldersScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("Photo folders", onBack = if (state.foldersReturnTo == Screen.Sync) ({ viewModel.back() }) else null)
        Text(
            "Choose the folders to index. A folder includes everything inside it. DCIM is where your camera saves photos.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (state.foldersLoading) {
            CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(16.dp))
        } else if (state.folders.isEmpty()) {
            Notice("No photos were found on this phone yet.")
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(state.folders, key = { it.relativePath }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleFolder(folder.relativePath) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = folder.relativePath in state.selectedFolders,
                        onCheckedChange = { viewModel.toggleFolder(folder.relativePath) },
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.muted, checkmarkColor = p.onAccent),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(folder.relativePath.ifBlank { "/" }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                        Text("${Actions.formatCount(folder.count.toLong())} photos", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
                HorizontalDivider(color = p.hairline)
            }
        }
        AccentButton(
            if (state.foldersReturnTo == Screen.Sync) "Save and sync" else "Continue",
            onClick = { viewModel.saveFolders() },
            enabled = state.selectedFolders.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

/**
 * Finding or creating the phone's index, step by step.
 */
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
        Text("Setting up your photo index", style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(
            "This phone gets its own Opensolr Index. If it already has one, it is reused and only the differences are synced.",
            style = MaterialTheme.typography.bodyLarge, color = p.muted,
        )
        Spacer(Modifier.height(32.dp))
        if (state.setupError == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 14.dp))
                Text(state.setupStep.ifBlank { "Connecting to Opensolr" }, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
        } else {
            Notice(state.setupError, title = "Setup did not finish")
            Spacer(Modifier.height(20.dp))
            AccentButton("Try again", onClick = { viewModel.runSetup() }, modifier = Modifier.fillMaxWidth())
            if (state.setupNeedsUpgrade) {
                Spacer(Modifier.height(12.dp))
                GhostButton("See plans at opensolr.com/pricing", onClick = { Actions.openUrl(context, Actions.PRICING_URL) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
