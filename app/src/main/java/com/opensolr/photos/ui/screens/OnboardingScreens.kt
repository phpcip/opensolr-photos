package com.opensolr.photos.ui.screens

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
            "Your photos, read into words and searchable. Tap a result to open it in your gallery.",
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
                    "Date, camera, place, file name and your tags only. A plan with AI reads the pictures themselves.",
                    title = "Photos are not recognised on this plan",
                )
                Spacer(Modifier.height(20.dp))
            }

            SectionLabel("What your plan covers")
            InfoRow("Plan", account.planLabel, onOpen = { Actions.openUrl(context, Actions.DASHBOARD_URL) })
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
                "Ten new photos, one AI request. Indexed ones cost nothing again.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(20.dp))
            Notice(
                "More photos, disk or bandwidth: upgrade your plan.",
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
 * One folder as the browser shows it: where it is, its name, how many photos are in it and
 * everything below it, and whether there is anything below it to open.
 */
private data class FolderNode(val path: String, val name: String, val count: Int, val hasChildren: Boolean)

/**
 * The folders immediately under [path], with everything deeper counted into each one. Built
 * from the flat list of MediaStore folders, so browsing costs no new query.
 */
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

/**
 * The chosen folder that already covers [path], or null when nothing does. A folder inside a
 * chosen one is indexed anyway, so it is shown as included instead of being ticked again.
 */
private fun coveringFolder(selected: Set<String>, path: String): String? =
    selected.firstOrNull { it.isNotEmpty() && !it.equals(path, ignoreCase = true) && path.startsWith(it, ignoreCase = true) }

/**
 * Picks the folders to index, by walking into them one level at a time (Cip, 2026-09-16): a
 * library kept as year/month/day is thousands of folders, and a flat list of them is unusable.
 *
 * Ticking a folder takes everything below it, which is what the sync already does: it matches a
 * photo's folder against the chosen ones by prefix.
 */
@Composable
fun FoldersScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    // Where the browser stands, as a MediaStore relative path; "" is the list of roots.
    var path by remember { mutableStateOf("") }
    val children = remember(state.folders, path) { childFolders(state.folders, path) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader("Photo folders", onBack = if (state.foldersReturnTo == Screen.Sync) ({ viewModel.back() }) else null)
        Text(
            "Open a folder to see what is inside it. Ticking one takes every photo in it, including the folders below it.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        // Where you are, and a tap on any step to go back up to it.
        val steps = path.trim('/').split('/').filter { it.isNotEmpty() }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "All folders",
                style = MaterialTheme.typography.labelLarge,
                color = if (steps.isEmpty()) p.ink else p.accent,
                modifier = Modifier.clickable { path = "" }.padding(end = 6.dp),
            )
            steps.forEachIndexed { index, step ->
                Text("/", style = MaterialTheme.typography.labelLarge, color = p.muted, modifier = Modifier.padding(end = 6.dp))
                val upTo = steps.take(index + 1).joinToString("/") + "/"
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (index == steps.lastIndex) p.ink else p.accent,
                    modifier = Modifier.clickable { path = upTo }.padding(end = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.foldersLoading) {
            CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.padding(16.dp))
        } else if (state.folders.isEmpty()) {
            Notice("No photos were found on this phone yet.")
        } else if (children.isEmpty()) {
            Notice("Nothing else inside this folder.")
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(children, key = { it.path }) { folder ->
                val covered = coveringFolder(state.selectedFolders, folder.path)
                val ticked = folder.path in state.selectedFolders || covered != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        // The row opens the folder; the tick chooses it. A folder with nothing
                        // below it has only the tick.
                        .clickable { if (folder.hasChildren) path = folder.path else if (covered == null) viewModel.toggleFolder(folder.path) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ticked,
                        enabled = covered == null,
                        onCheckedChange = { viewModel.toggleFolder(folder.path) },
                        colors = CheckboxDefaults.colors(checkedColor = p.accentFill, uncheckedColor = p.muted, checkmarkColor = p.onAccentFill),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                        Text(
                            if (covered != null) "Already included by $covered"
                            else "${Actions.formatCount(folder.count.toLong())} photos" + if (folder.hasChildren) ", with folders inside" else "",
                            style = MaterialTheme.typography.bodySmall, color = p.muted,
                        )
                    }
                    if (folder.hasChildren) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Open ${folder.name}", tint = p.muted)
                    }
                }
                HorizontalDivider(color = p.hairline)
            }
        }

        Text(
            if (state.selectedFolders.isEmpty()) "Nothing chosen yet"
            else state.selectedFolders.sorted().joinToString(", ") { it.trimEnd('/') },
            style = MaterialTheme.typography.bodySmall, color = p.muted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
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
            "This phone gets its own index; an existing one is reused.",
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
