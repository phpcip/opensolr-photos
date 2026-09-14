package com.opensolr.photos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.opensolr.photos.data.SyncSchedule
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
 * The Sync screen: live progress, the result of the last run, the schedule, Force Re-Sync, and
 * the folders being indexed.
 */
@Composable
fun SyncScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader("Sync", onBack = { viewModel.back() })

        SectionLabel("Status")
        Spacer(Modifier.height(14.dp))
        val sync = state.sync
        when {
            sync.running -> {
                Text(sync.phase.ifBlank { "Syncing" }, style = MaterialTheme.typography.titleMedium, color = p.ink)
                Spacer(Modifier.height(10.dp))
                if (sync.total > 0) {
                    LinearProgressIndicator(
                        progress = { sync.done.toFloat() / sync.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = p.accent, trackColor = p.chip, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${Actions.formatCount(sync.done.toLong())} of ${Actions.formatCount(sync.total.toLong())}", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = p.accent, trackColor = p.chip, strokeCap = StrokeCap.Butt)
                }
            }
            sync.queued -> Text("A sync is waiting for a network connection.", style = MaterialTheme.typography.titleMedium, color = p.ink)
            else -> Text(statusLine(state), style = MaterialTheme.typography.titleMedium, color = p.ink)
        }
        Spacer(Modifier.height(20.dp))

        state.lastReport?.let { report ->
            // What the last run did, shown briefly after it finishes, then gone: the rows below
            // always say what is true now, not what one run changed.
            var showOutcome by remember(report.finishedAt) { mutableStateOf(System.currentTimeMillis() - report.finishedAt < OUTCOME_VISIBLE_MS) }
            LaunchedEffect(report.finishedAt) {
                if (showOutcome) { delay(OUTCOME_VISIBLE_MS); showOutcome = false }
            }
            if (showOutcome && report.status == "ok") {
                val parts = buildList {
                    if (report.added > 0) add("${Actions.formatCount(report.added.toLong())} synced")
                    if (report.deleted > 0) add("${Actions.formatCount(report.deleted.toLong())} removed")
                    if (report.failed > 0) add("${Actions.formatCount(report.failed.toLong())} skipped")
                }
                Text(if (parts.isEmpty()) "Nothing to sync" else parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.accent)
                Spacer(Modifier.height(14.dp))
            }
            SectionLabel("Last sync")
            InfoRow("Finished", Actions.formatDate(report.finishedAt))
            InfoRow("Result", resultLabel(report.status))
            InfoRow("Photos in your folders", Actions.formatCount(report.localCount.toLong()))
            InfoRow("Photos in your index", Actions.formatCount(report.indexAfter.toLong()))
            if (report.message.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Notice(report.message)
            }
            if (report.recreated) {
                Spacer(Modifier.height(14.dp))
                Notice("Your index was missing from your Opensolr account, so a new, empty one was created and your photos were synced into it again.", title = "Index recreated")
            }
            Spacer(Modifier.height(20.dp))
        }

        AccentButton("Force Re-Sync", onClick = { viewModel.forceResync() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            "Re-Sync compares every photo in your folders with your index: photos you deleted are removed, new photos are added. Photos already indexed are not read again.",
            style = MaterialTheme.typography.bodySmall, color = p.muted,
        )
        Spacer(Modifier.height(28.dp))

        SectionLabel("Automatic Re-Sync")
        Spacer(Modifier.height(4.dp))
        ScheduleOption("Every day", state.schedule == SyncSchedule.DAILY) { viewModel.setSchedule(SyncSchedule.DAILY) }
        HorizontalDivider(color = p.hairline)
        ScheduleOption("Every week", state.schedule == SyncSchedule.WEEKLY) { viewModel.setSchedule(SyncSchedule.WEEKLY) }
        HorizontalDivider(color = p.hairline)
        ScheduleOption("Every month", state.schedule == SyncSchedule.MONTHLY) { viewModel.setSchedule(SyncSchedule.MONTHLY) }
        HorizontalDivider(color = p.hairline)
        Spacer(Modifier.height(8.dp))
        Text("A sync already runs on its own a minute after your photos change. This one is the safety net for what the phone cannot notice: photos waiting to be read after the monthly AI allowance resets, places to look up again, an index changed on opensolr.com. Runs when the phone is online and the battery is not low.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        Spacer(Modifier.height(28.dp))

        SectionLabel("Folders being indexed")
        state.selectedFolders.sorted().forEach { InfoRow(it.trimEnd('/').ifBlank { "/" }, "") }
        Spacer(Modifier.height(14.dp))
        GhostButton("Change folders", onClick = { viewModel.openFolders(Screen.Sync) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * One radio row of the schedule choice.
 */
@Composable
private fun ScheduleOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.muted))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = p.ink)
    }
}

/**
 * One line summing up the state when nothing runs.
 */
/** How long the outcome of a finished run stays on screen. */
private const val OUTCOME_VISIBLE_MS = 8000L

private fun statusLine(state: UiState): String {
    val report = state.lastReport ?: return "No sync has run yet."
    return when (report.status) {
        "ok" -> when {
            report.localCount == 0 -> "No photos were found in the folders you chose."
            report.added + report.deleted == 0 && report.failed > 0 -> "No photo could be read. Nothing was indexed."
            else -> "Your index is in step with your photos."
        }
        "stopped_quota" -> "Paused: the monthly AI requests of your plan are used up."
        "stopped_plan_limit" -> "Paused: the index reached its disk space or bandwidth."
        "sign_in_required" -> "Paused: sign in to Opensolr again."
        "rebuild_required" -> "Waiting: your index must be rebuilt for the new version. Open the app's photos screen to start it."
        "update_app" -> "Paused: update Opensolr Photos to keep syncing."
        "device_choice" -> "Waiting: open the app and say which one of your devices this phone is."
        else -> "The last sync did not finish. It is tried again at the next Re-Sync."
    }
}

/**
 * Human wording of a report status.
 */
private fun resultLabel(status: String): String = when (status) {
    "ok" -> "Completed"
    "stopped_quota" -> "Paused, AI requests used up"
    "stopped_plan_limit" -> "Paused, plan limit reached"
    "sign_in_required" -> "Sign-in needed"
    "rebuild_required" -> "Rebuild needed"
    "update_app" -> "App update needed"
    "device_choice" -> "Device not chosen yet"
    else -> "Did not finish"
}
