package com.opensolr.photos.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                "Search bandwidth this month",
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
                "If your index goes over its disk space or its search bandwidth, it stops taking new photos and answering searches. If the AI requests of the month run out, or the plan has no photo recognition, new photos are still indexed by date, camera, place, file name and your tags, without being read into words, and search matches words only. You are warned here and with a notification at 90% and at the limit. Upgrade at opensolr.com/pricing to lift any of these limits.",
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

        SectionLabel("Advanced")
        Spacer(Modifier.height(10.dp))
        Text(
            "Your photos' index is a regular Opensolr Index. In the Opensolr control panel you can empty it, back it up, or query it from anything else. You never need to for this app to work.",
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
