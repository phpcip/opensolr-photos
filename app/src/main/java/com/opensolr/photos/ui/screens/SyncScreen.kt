package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.tapClickable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.opensolr.photos.R
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.opensolr.photos.ui.TextButton
import androidx.compose.material3.AlertDialog
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

@Composable
fun SyncScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmReread by remember { mutableStateOf(false) }
    var confirmRebuildOcr by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(stringResource(R.string.sync_title), onBack = { viewModel.back() })

        SectionLabel(stringResource(R.string.sync_status))
        Spacer(Modifier.height(14.dp))
        val sync = state.sync
        when {
            sync.running -> {
                Text(sync.phase.ifBlank { stringResource(R.string.sync_syncing) }, style = MaterialTheme.typography.titleMedium, color = p.ink)
                Spacer(Modifier.height(10.dp))
                if (sync.total > 0) {
                    LinearProgressIndicator(
                        progress = { sync.done.toFloat() / sync.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = p.accent, trackColor = p.chip, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sync_x_of_y, Actions.formatCount(sync.done.toLong()), Actions.formatCount(sync.total.toLong())), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = p.accent, trackColor = p.chip, strokeCap = StrokeCap.Butt)
                }
            }

            sync.queued -> Text(stringResource(R.string.sync_queued), style = MaterialTheme.typography.titleMedium, color = p.ink)
            else -> Text(statusLine(state), style = MaterialTheme.typography.titleMedium, color = p.ink)
        }
        Spacer(Modifier.height(20.dp))

        state.lastReport?.let { report ->

            var showOutcome by remember(report.finishedAt) { mutableStateOf(System.currentTimeMillis() - report.finishedAt < OUTCOME_VISIBLE_MS) }
            LaunchedEffect(report.finishedAt) {
                if (showOutcome) { delay(OUTCOME_VISIBLE_MS); showOutcome = false }
            }
            if (showOutcome && report.status == "ok") {
                val parts = buildList {
                    if (report.added > 0) add(stringResource(R.string.sync_n_synced, Actions.formatCount(report.added.toLong())))
                    if (report.deleted > 0) add(stringResource(R.string.sync_n_removed, Actions.formatCount(report.deleted.toLong())))
                    if (report.failed > 0) add(stringResource(R.string.sync_n_skipped, Actions.formatCount(report.failed.toLong())))
                }
                Text(if (parts.isEmpty()) stringResource(R.string.sync_nothing) else parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.accent)
                Spacer(Modifier.height(14.dp))
            }
            SectionLabel(stringResource(R.string.sync_last))
            InfoRow(stringResource(R.string.sync_finished), Actions.formatDate(report.finishedAt))
            InfoRow(stringResource(R.string.sync_result), resultLabel(report.status))
            InfoRow(stringResource(R.string.sync_photos_folders), Actions.formatCount(report.localCount.toLong()))
            InfoRow(stringResource(R.string.sync_photos_index), Actions.formatCount(report.indexAfter.toLong()))
            if (report.message.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Notice(report.message)
            }
            if (report.recreated) {
                Spacer(Modifier.height(14.dp))
                Notice(stringResource(R.string.sync_recreated_text), title = stringResource(R.string.sync_recreated_title))
            }
            Spacer(Modifier.height(20.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderItem(stringResource(R.string.sync_btn_resync), onClick = { viewModel.forceResync() }) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
            }

            HeaderItem(stringResource(R.string.sync_btn_stop), active = state.sync.busy, onClick = { viewModel.stopSync() }) {
                Icon(
                    painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    tint = if (state.sync.busy) p.accent else p.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            HeaderItem(stringResource(R.string.sync_btn_reread), onClick = { confirmReread = true }) {
                Icon(painterResource(R.drawable.ic_reload), contentDescription = null, tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.sync_btn_documents), onClick = { confirmRebuildOcr = true }) {
                Icon(painterResource(R.drawable.ic_rebuild), contentDescription = null, tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.sync_btn_reset), onClick = { confirmReset = true }) {
                Icon(painterResource(R.drawable.ic_reset), contentDescription = null, tint = p.ink, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sync_buttons_text),
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        Spacer(Modifier.height(28.dp))

        SectionLabel(stringResource(R.string.sync_auto))
        Spacer(Modifier.height(4.dp))
        ScheduleOption(stringResource(R.string.sync_daily), state.schedule == SyncSchedule.DAILY) { viewModel.setSchedule(SyncSchedule.DAILY) }
        HorizontalDivider(color = p.hairline)
        ScheduleOption(stringResource(R.string.sync_weekly), state.schedule == SyncSchedule.WEEKLY) { viewModel.setSchedule(SyncSchedule.WEEKLY) }
        HorizontalDivider(color = p.hairline)
        ScheduleOption(stringResource(R.string.sync_monthly), state.schedule == SyncSchedule.MONTHLY) { viewModel.setSchedule(SyncSchedule.MONTHLY) }
        HorizontalDivider(color = p.hairline)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.sync_safety), style = MaterialTheme.typography.bodySmall, color = p.muted)
        Spacer(Modifier.height(28.dp))

        SectionLabel(stringResource(R.string.sync_folders))
        state.selectedFolders.sorted().forEach { InfoRow(it.trimEnd('/').ifBlank { "/" }, "") }
        Spacer(Modifier.height(14.dp))
        GhostButton(stringResource(R.string.sync_change_folders), onClick = { viewModel.openFolders(Screen.Sync) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(40.dp))
    }

    if (confirmRebuildOcr) {
        AlertDialog(
            onDismissRequest = { confirmRebuildOcr = false },
            title = { Text(stringResource(R.string.sync_ocr_q)) },
            text = { Text(stringResource(R.string.sync_ocr_text)) },
            confirmButton = { TextButton(onClick = { confirmRebuildOcr = false; viewModel.rebuildOcr() }) { Text(stringResource(R.string.sync_rebuild), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmRebuildOcr = false }) { Text(stringResource(R.string.sync_cancel), color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }

    if (confirmReread) {
        AlertDialog(
            onDismissRequest = { confirmReread = false },
            title = { Text(stringResource(R.string.sync_reread_q)) },
            text = {
                Text(
                    stringResource(R.string.sync_reread_text)
                )
            },
            confirmButton = { TextButton(onClick = { confirmReread = false; viewModel.rereadAll() }) { Text(stringResource(R.string.sync_btn_reread), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmReread = false }) { Text(stringResource(R.string.sync_cancel), color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.sync_reset_q)) },
            text = { Text(stringResource(R.string.sync_reset_text)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetIndex() }) { Text(stringResource(R.string.sync_btn_reset), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.sync_cancel), color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}

@Composable
private fun ScheduleOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().tapClickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.muted))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = p.ink)
    }
}

private const val OUTCOME_VISIBLE_MS = 8000L

@Composable
private fun statusLine(state: UiState): String {
    val report = state.lastReport ?: return stringResource(R.string.st_none)
    return when (report.status) {
        "ok" -> when {
            report.localCount == 0 -> stringResource(R.string.st_no_photos)
            report.added + report.deleted == 0 && report.failed > 0 -> stringResource(R.string.st_none_read)
            else -> stringResource(R.string.st_in_step)
        }
        "stopped_quota" -> stringResource(R.string.st_quota)
        "stopped_plan_limit" -> stringResource(R.string.st_plan)
        "sign_in_required" -> stringResource(R.string.st_sign_in)
        "rebuild_required" -> stringResource(R.string.st_rebuild)
        "update_app" -> stringResource(R.string.st_update)
        "device_choice" -> stringResource(R.string.st_device)
        "waiting_charger" -> stringResource(R.string.st_charger, report.message)
        "retry_later" -> stringResource(R.string.st_retry)
        else -> stringResource(R.string.st_failed)
    }
}

@Composable
private fun resultLabel(status: String): String = when (status) {
    "ok" -> stringResource(R.string.rs_ok)
    "stopped_quota" -> stringResource(R.string.rs_quota)
    "stopped_plan_limit" -> stringResource(R.string.rs_plan)
    "sign_in_required" -> stringResource(R.string.rs_sign_in)
    "rebuild_required" -> stringResource(R.string.rs_rebuild)
    "update_app" -> stringResource(R.string.rs_update)
    "device_choice" -> stringResource(R.string.rs_device)
    "waiting_charger" -> stringResource(R.string.rs_charger)
    "retry_later" -> stringResource(R.string.rs_retry)
    else -> stringResource(R.string.rs_failed)
}
