package com.opensolr.photos.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opensolr.photos.BuildConfig
import com.opensolr.photos.data.SearchCache
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.InfoRow
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.UsageRow
import com.opensolr.photos.ui.theme.LocalPalette

/**
 * The Opensolr account zone: every limit of the plan with what is used, and a clear way to upgrade.
 */
@Composable
fun AccountScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    val account = state.account

    // What the cache holds right now, read when the screen opens.
    LaunchedEffect(Unit) { viewModel.refreshCacheInfo() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader("Opensolr account", onBack = { viewModel.back() })

        SectionLabel("Signed in")
        InfoRow("Email", state.email ?: "")
        account?.let { InfoRow("Plan", it.planLabel, onOpen = { Actions.openUrl(context, Actions.DASHBOARD_URL) }) }
        state.indexName?.let { InfoRow("This phone's index", it) }
        state.environment?.takeIf { it.isNotBlank() }?.let { InfoRow("Environment", it) }
        // The installed version, and a check that does not wait for the once-a-day one.
        InfoRow("App version", BuildConfig.VERSION_NAME)
        Spacer(Modifier.height(10.dp))
        GhostButton(
            if (state.updateChecking) "Checking…" else "Check for updates",
            onClick = { viewModel.checkForUpdateNow() },
            enabled = !state.updateChecking,
            modifier = Modifier.fillMaxWidth(),
        )
        // A newer release found by the daily check is said here, never on the photos screen
        // (Cip, 2026-09-17: the main screen carries as few messages as possible).
        if (state.updateResult == null) {
            state.update?.let { newer ->
                Spacer(Modifier.height(12.dp))
                Notice(newer.notes.ifBlank { "Download it from the releases page; it installs over this one and keeps everything." }, title = "Version ${newer.version} is available")
                Spacer(Modifier.height(10.dp))
                AccentButton("Download ${newer.version}", onClick = { Actions.openUrl(context, newer.pageUrl) }, modifier = Modifier.fillMaxWidth())
            }
        }
        state.updateResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            Notice(result, title = if (state.update != null) "Update available" else "Version")
            // A newer release leads to its page; the install stays the user's and Android's.
            state.update?.let { newer ->
                Spacer(Modifier.height(10.dp))
                AccentButton("Download ${newer.version}", onClick = { Actions.openUrl(context, newer.pageUrl) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))

        if (account != null) {
            // What the limits mean right now, before the numbers.
            state.planWarnings.forEach { warning ->
                Notice(warning.text, title = warning.title)
                Spacer(Modifier.height(10.dp))
            }
            if (state.planWarnings.isNotEmpty()) Spacer(Modifier.height(10.dp))
            SectionLabel("Limits and usage")
            UsageRow(
                "Disk space",
                Actions.formatMb(account.diskUsedMb), Actions.formatMb(account.diskLimitMb),
                if (account.diskLimitMb > 0) (account.diskUsedMb / account.diskLimitMb).toFloat() else null,
            )
            UsageRow(
                // Short on purpose: a big allowance ("7 MB of 200000.0 GB") leaves the value no
                // room next to a long label (Cip, 2026-09-16).
                "BW",
                Actions.formatMb(account.bandwidthUsedMb), Actions.formatMb(account.bandwidthLimitMb),
                if (account.bandwidthLimitMb > 0) (account.bandwidthUsedMb / account.bandwidthLimitMb).toFloat() else null,
            )
            if (account.maxAiRequests > 0) {
                UsageRow(
                    "AI requests this month",
                    Actions.formatCount(account.aiRequestsUsed.toLong()), Actions.formatCount(account.maxAiRequests.toLong()),
                    account.aiRequestsUsed.toFloat() / account.maxAiRequests,
                )
            } else {
                InfoRow("AI requests this month", "No monthly cap")
            }
            InfoRow("Photos per month", account.photosPerMonth?.let { "about " + Actions.formatCount(it.toLong()) } ?: "No monthly cap")
            account.photosLeftThisMonth?.let { InfoRow("Photos left this month", "about " + Actions.formatCount(it.toLong())) }
            InfoRow("Search by meaning", if (account.vectorAllowed) "Included" else "Not included")
            InfoRow("Indexes on the account", "${account.indexesUsed} of ${account.indexLimit}")
            if (account.indexedDocs > 0) InfoRow("Photos in the index", Actions.formatCount(account.indexedDocs))
            InfoRow("Updated", Actions.formatDate(account.refreshedAt))
            Spacer(Modifier.height(20.dp))

            Notice(
                "Over disk or bandwidth, it stops. Out of AI requests, photos are indexed without words.",
                title = "When a limit is reached",
            )
            Spacer(Modifier.height(14.dp))
            AccentButton("Upgrade at opensolr.com/pricing", onClick = { Actions.openUrl(context, Actions.PRICING_URL) }, modifier = Modifier.fillMaxWidth())
        }
        state.accountError?.let {
            Spacer(Modifier.height(14.dp))
            Notice(it, title = "Could not refresh")
        }
        Spacer(Modifier.height(10.dp))
        GhostButton(if (state.accountRefreshing) "Refreshing…" else "Refresh", onClick = { viewModel.refreshAccount() }, enabled = !state.accountRefreshing, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))

        // The taps the app gives back under a finger, which not everyone wants.
        SectionLabel("Feedback")
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Haptic feedback", style = MaterialTheme.typography.bodyLarge, color = p.ink)
                Text(
                    "A tap you can feel when you pick photos, cross a month on the scroll bar, or change a filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.hapticsEnabled,
                onCheckedChange = { viewModel.setHaptics(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = p.accentFill, checkedThumbColor = p.onAccentFill, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
            )
        }
        Spacer(Modifier.height(28.dp))

        // Semantic <-> lexical balance of a search by meaning, as the search platform's settings
        // have it (Cip, 2026-09-17): 0 is meaning only, 1 is words only.
        SectionLabel("Search tuning")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Semantic \u2194 Lexical Balance", style = MaterialTheme.typography.bodyLarge, color = p.ink)
                Text("Controls how much keyword matching contributes vs. semantic meaning.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Spacer(Modifier.width(12.dp))
            GhostButton(
                "Reset",
                onClick = { viewModel.setLexicalWeight(com.opensolr.photos.data.AppPrefs.DEFAULT_LEXICAL_WEIGHT) },
                enabled = state.lexicalWeight != com.opensolr.photos.data.AppPrefs.DEFAULT_LEXICAL_WEIGHT &&
                    state.account?.vectorAllowed == true,
            )
        }
        // Only means something where search by meaning runs: with vector search on the plan and
        // AI requests left this month. Otherwise shown greyed out, with the reason (Cip, 2026-09-17).
        val limits = state.account
        val balanceUsable = limits != null && limits.vectorAllowed &&
            (limits.maxAiRequests <= 0 || limits.aiRequestsUsed < limits.maxAiRequests)
        if (!balanceUsable) {
            Text(
                if (limits?.vectorAllowed == true) "Your plan's AI requests for this month are used up, so search matches words only and this has no effect until they reset."
                else "Your plan does not include search by meaning, so search matches words only and this has no effect.",
                style = MaterialTheme.typography.bodySmall, color = p.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        val balanceView = androidx.compose.ui.platform.LocalView.current
        var balance by remember(state.lexicalWeight) { mutableStateOf(state.lexicalWeight) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Semantic", style = MaterialTheme.typography.bodySmall, color = p.muted)
            androidx.compose.material3.Slider(
                value = balance,
                onValueChange = { v ->
                    val stepped = (Math.round(v * 20) / 20f).coerceIn(0f, 1f)
                    if (stepped != balance) com.opensolr.photos.ui.Haptics.tick(balanceView, strong = false)
                    balance = stepped
                },
                onValueChangeFinished = { viewModel.setLexicalWeight(balance) },
                valueRange = 0f..1f,
                steps = 19,
                enabled = balanceUsable,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accentFill, activeTrackColor = p.accentFill, inactiveTrackColor = p.chip, activeTickColor = p.accentFill, inactiveTickColor = p.hairline),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("Lexical", style = MaterialTheme.typography.bodySmall, color = p.muted)
            Spacer(Modifier.width(10.dp))
            Text(String.format(java.util.Locale.US, "%.2f", balance), style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.ink)
        }
        Spacer(Modifier.height(28.dp))

        // How long an answer from the index may be reused, and a way to throw them all away.
        SectionLabel("Search cache")
        Spacer(Modifier.height(10.dp))
        Text(
            "Answers from your index are kept on this phone and reused, so asking the same thing twice does not spend your plan's search bandwidth twice. " +
                "Your own tags, deleted photos and every finished sync empty it straight away, whatever the number below says.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted,
        )
        Spacer(Modifier.height(14.dp))
        // Reset whenever the stored value changes, so the field always shows what is in force.
        var seconds by remember(state.cacheSeconds) { mutableStateOf(state.cacheSeconds.toString()) }
        OutlinedTextField(
            value = seconds,
            onValueChange = { typed -> seconds = typed.filter { it.isDigit() }.take(6) },
            label = { Text("Reuse an answer for this many seconds") },
            supportingText = { Text("At least ${SearchCache.MIN_SECONDS} seconds, at most a day. In force: ${state.cacheSeconds} seconds.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                "Save",
                onClick = { viewModel.setCacheSeconds(seconds.toIntOrNull() ?: SearchCache.DEFAULT_SECONDS) },
                enabled = seconds.toIntOrNull()?.let { it != state.cacheSeconds } ?: false,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                if (state.cachedCount > 0) "Clear cache (${state.cachedCount})" else "Clear cache",
                onClick = { viewModel.clearSearchCache() },
                enabled = state.cachedCount > 0,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(28.dp))

        SectionLabel("Advanced")
        Spacer(Modifier.height(10.dp))
        Text(
            "A regular Opensolr Index.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted,
        )
        Spacer(Modifier.height(14.dp))
        state.indexName?.let { name ->
            GhostButton("Open this index on opensolr.com", onClick = { Actions.openUrl(context, Actions.indexPanelUrl(name)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        GhostButton("Sign out", onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(40.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Syncing stops and this phone forgets your Opensolr account. Your index and the photos in it stay in your account.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text("Sign out", color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel", color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}
