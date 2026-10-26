package com.opensolr.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opensolr.photos.R
import com.opensolr.photos.data.StorageConfig
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.Haptics
import com.opensolr.photos.ui.InfoRow
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.TextButton
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

/** The storage backend of this index: set it up and test it, or see what it holds and act on it. */
@Composable
fun StorageScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { viewModel.refreshStorageStats() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(stringResource(R.string.stg_title), onBack = { viewModel.back() })
        val storage = state.storage
        if (storage == null || state.storageEditing) {
            StorageForm(state, viewModel, storage)
        } else {
            StorageOverview(state, viewModel, storage)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StorageOverview(state: UiState, viewModel: AppViewModel, storage: StorageConfig) {
    val p = LocalPalette.current
    val stats = state.storageStats
    val status = state.storageStatus
    var confirmForget by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDownloadAll by remember { mutableStateOf(false) }
    var confirmSaver by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // the ids whose copies are made and whose full files the phone is being asked about
    var saverAsked by remember { mutableStateOf<List<String>>(emptyList()) }
    val saverLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        val done = saverAsked
        saverAsked = emptyList()
        viewModel.spaceSaverDone(done)
    }
    var typed by remember { mutableStateOf("") }

    SectionLabel(stringResource(R.string.stg_status))
    Spacer(Modifier.height(10.dp))
    when {
        status.running -> {
            Text(status.phase.ifBlank { stringResource(R.string.stg_running) }, style = MaterialTheme.typography.titleMedium, color = p.ink)
            if (status.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.hairline)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.stg_x_of_y, Actions.formatCount(status.done.toLong()), Actions.formatCount(status.total.toLong())), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        status.queued -> Text(stringResource(R.string.stg_queued), style = MaterialTheme.typography.titleMedium, color = p.ink)
        else -> state.storageReport?.let { report ->
            val parts = buildList {
                if (report.uploaded > 0) add(stringResource(R.string.stg_n_uploaded, Actions.formatCount(report.uploaded.toLong())))
                if (report.restored > 0) add(stringResource(R.string.stg_n_restored, Actions.formatCount(report.restored.toLong())))
                if (report.failed > 0) add(stringResource(R.string.stg_n_failed, Actions.formatCount(report.failed.toLong())))
            }
            Text(storageResult(report.status) + (if (report.message.isNotBlank()) ": " + report.message else ""), style = MaterialTheme.typography.titleMedium, color = p.ink)
            Spacer(Modifier.height(4.dp))
            Text(if (parts.isEmpty()) stringResource(R.string.stg_nothing) else parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.accent)
            Spacer(Modifier.height(4.dp))
            InfoRow(stringResource(R.string.stg_last_run), Actions.formatDate(report.finishedAt))
        } ?: Text(stringResource(R.string.stg_nothing), style = MaterialTheme.typography.bodyMedium, color = p.muted)
    }
    state.storageError?.let { Spacer(Modifier.height(10.dp)); Notice(it) }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentButton(stringResource(R.string.stg_run_now), onClick = { viewModel.runStorageNow() }, enabled = !status.busy, modifier = Modifier.weight(1f))
        GhostButton(stringResource(R.string.stg_stop), onClick = { viewModel.stopStorage() }, enabled = status.busy, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(24.dp))

    SectionLabel(stringResource(R.string.stg_backend))
    InfoRow(kindLabel(storage.kind), storage.label.ifBlank { storage.host })
    InfoRow(stringResource(R.string.stg_host), storage.host)
    InfoRow(stringResource(R.string.stg_quality), if (storage.quality == StorageConfig.QUALITY_1080) stringResource(R.string.stg_quality_1080) else stringResource(R.string.stg_quality_original))
    if (stats != null) {
        InfoRow(stringResource(R.string.stg_in_backend), Actions.formatCount(stats.inBackend))
        InfoRow(stringResource(R.string.stg_size_backend), Actions.formatFileSize(stats.bytesInBackend).ifBlank { "0" })
        InfoRow(stringResource(R.string.stg_on_phone), Actions.formatCount(stats.onPhone.toLong()))
        InfoRow(stringResource(R.string.stg_size_phone), Actions.formatFileSize(stats.bytesOnPhone).ifBlank { "0" })
        InfoRow(stringResource(R.string.stg_pending), Actions.formatCount(stats.pending.toLong()))
        InfoRow(stringResource(R.string.stg_cloud_only), Actions.formatCount(stats.cloudOnly))
        if (stats.cloudOnly > 0) {
            Spacer(Modifier.height(10.dp))
            Notice(stringResource(R.string.stg_cloud_only_text, Actions.formatCount(stats.cloudOnly), Actions.formatFileSize(stats.cloudOnlyBytes)))
        }
    }
    Spacer(Modifier.height(16.dp))
    GhostButton(stringResource(R.string.stg_read_manifests), onClick = { viewModel.rereadBackend() }, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    val cloudOnly = stats?.cloudOnly ?: 0L
    // every photo held here only as its 720p copy brought down at its backup quality, after a look at the free space
    if (cloudOnly > 0) {
        GhostButton(stringResource(R.string.stg_download_all), onClick = { confirmDownloadAll = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(14.dp))

    // Room back on the phone: a photo the backup already holds does not have to sit here at full size too
    val saver = state.spaceSaver
    if (saver != null) {
        SectionLabel(stringResource(R.string.sv_title))
        Spacer(Modifier.height(10.dp))
        Text(
            if (saver.count > 0) stringResource(R.string.sv_text, Actions.formatCount(saver.count.toLong()), Actions.formatFileSize(saver.savedBytes).ifBlank { "0" })
            else stringResource(R.string.sv_none),
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        Spacer(Modifier.height(10.dp))
        if (saver.count > 0) {
            GhostButton(
                if (state.spaceSaverBusy) stringResource(R.string.sv_working) else stringResource(R.string.sv_move, Actions.formatFileSize(saver.savedBytes).ifBlank { "0" }),
                onClick = { confirmSaver = true },
                enabled = !status.busy && !state.spaceSaverBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    SectionLabel(stringResource(R.string.stg_change))
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.stg_change_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
    Spacer(Modifier.height(10.dp))
    GhostButton(stringResource(R.string.stg_change), onClick = { viewModel.editStorage(true) }, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    GhostButton(stringResource(R.string.stg_forget), onClick = { confirmForget = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(24.dp))

    SectionLabel(stringResource(R.string.stg_clear))
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.stg_clear_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
    Spacer(Modifier.height(10.dp))
    GhostButton(if (state.storageClearing) stringResource(R.string.stg_saving) else stringResource(R.string.stg_clear), onClick = { typed = ""; confirmClear = true }, enabled = !status.busy && !state.storageClearing, modifier = Modifier.fillMaxWidth())

    if (confirmDownloadAll) {
        val need = stats?.cloudOnlyBytes ?: 0L
        val free = remember { Actions.freeBytes() }
        val fits = free > need
        AlertDialog(
            onDismissRequest = { confirmDownloadAll = false },
            title = { Text(stringResource(R.string.stg_download_all)) },
            text = { com.opensolr.photos.ui.KeyboardBack()
                if (fits) Text(stringResource(R.string.stg_download_all_text, Actions.formatCount(cloudOnly), Actions.formatFileSize(need), Actions.formatFileSize(free)))
                else Text(stringResource(R.string.stg_download_all_no_space, Actions.formatFileSize(need), Actions.formatFileSize(free)), color = p.accent)
            },
            confirmButton = { if (fits) TextButton(onClick = { confirmDownloadAll = false; viewModel.downloadAllOriginals() }) { Text(stringResource(R.string.stg_download_all_start), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmDownloadAll = false }) { Text(stringResource(R.string.stg_cancel), color = p.ink) } },
            containerColor = p.paper,
        )
    }
    if (confirmSaver) {
        val saver2 = state.spaceSaver
        val batch = minOf(saver2?.count ?: 0, com.opensolr.photos.storage.SpaceSaver.BATCH)
        AlertDialog(
            onDismissRequest = { confirmSaver = false },
            title = { Text(stringResource(R.string.sv_confirm_title)) },
            text = { com.opensolr.photos.ui.KeyboardBack()
                Text(stringResource(R.string.sv_confirm_text, Actions.formatCount(batch.toLong())))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSaver = false
                    val ids = com.opensolr.photos.storage.SpaceSaver.candidates(context, batch)
                    viewModel.spaceSaverMove(ids) { ready ->
                        val sender = Actions.deleteRequest(context, com.opensolr.photos.storage.SpaceSaver.urisOf(context, ready))
                        if (sender != null) {
                            saverAsked = ready
                            saverLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                        } else {
                            viewModel.spaceSaverDone(ready)
                        }
                    }
                }) { Text(stringResource(R.string.sv_confirm_ok), color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmSaver = false }) { Text(stringResource(R.string.stg_cancel), color = p.ink) } },
            containerColor = p.paper,
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.stg_forget)) },
            text = { com.opensolr.photos.ui.KeyboardBack(); Text(stringResource(R.string.stg_forget_text)) },
            confirmButton = { TextButton(onClick = { confirmForget = false; viewModel.forgetStorage() }) { Text(stringResource(R.string.stg_forget), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.stg_cancel), color = p.ink) } },
            containerColor = p.paper,
        )
    }
    if (confirmClear) {
        val expected = storage.label.ifBlank { storage.host }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stg_clear)) },
            text = { com.opensolr.photos.ui.KeyboardBack()
                Column {
                    Text(stringResource(R.string.stg_clear_text))
                    if (cloudOnly > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.stg_clear_cloud_lost, Actions.formatCount(cloudOnly)), color = p.accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    Field(typed, { typed = it }, expected)
                }
            },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearStorage() }, enabled = typed.trim() == expected) { Text(stringResource(R.string.stg_clear_confirm), color = if (typed.trim() == expected) p.accent else p.muted) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.stg_cancel), color = p.ink) } },
            containerColor = p.paper,
        )
    }
}

@Composable
private fun StorageForm(state: UiState, viewModel: AppViewModel, current: StorageConfig?) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    var kind by remember { mutableStateOf(current?.kind ?: StorageConfig.KIND_S3) }
    var label by remember { mutableStateOf(current?.label.orEmpty()) }
    var quality by remember { mutableStateOf(current?.quality ?: StorageConfig.QUALITY_ORIGINAL) }
    val fields = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { current?.fields?.forEach { (k, v) -> put(k, v) } } }
    fun f(name: String) = fields[name].orEmpty()
    fun set(name: String, value: String) { fields[name] = value }
    // anything typed again has to be tested again
    LaunchedEffect(kind, label, fields.toMap()) { if (state.storageTested != null) viewModel.editStorage(true) }


    if (current == null) {
        Text(stringResource(R.string.stg_none), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.stg_none_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
        Spacer(Modifier.height(16.dp))
    }
    SectionLabel(stringResource(R.string.stg_backend))
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(StorageConfig.KIND_S3, StorageConfig.KIND_WEBDAV).forEach { k ->
            val on = k == kind
            Text(
                kindLabel(k),
                style = MaterialTheme.typography.labelMedium,
                color = if (on) p.accent else p.ink,
                modifier = Modifier
                    .border(if (on) 1.5.dp else 1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(2.dp))
                    .background(if (on) p.paper else p.chip, RoundedCornerShape(2.dp))
                    .clickable { Haptics.tap(view); kind = k }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Field(label, { label = it }, stringResource(R.string.stg_label))
    when (kind) {
        StorageConfig.KIND_S3 -> {
            Field(f("endpoint"), { set("endpoint", it) }, stringResource(R.string.stg_endpoint), KeyboardType.Uri, hint = stringResource(R.string.stg_endpoint_hint))
            Field(f("region"), { set("region", it) }, stringResource(R.string.stg_region))
            Field(f("bucket"), { set("bucket", it) }, stringResource(R.string.stg_bucket))
            Field(f("access_key"), { set("access_key", it) }, stringResource(R.string.stg_access_key))
            Field(f("secret_key"), { set("secret_key", it) }, stringResource(R.string.stg_secret_key), secret = true)
            Field(f("prefix"), { set("prefix", it) }, stringResource(R.string.stg_prefix))
        }
        else -> {
            Field(f("url"), { set("url", it) }, stringResource(R.string.stg_url), KeyboardType.Uri, hint = stringResource(R.string.stg_url_hint))
            Field(f("user"), { set("user", it) }, stringResource(R.string.stg_user))
            Field(f("password"), { set("password", it) }, stringResource(R.string.stg_password), secret = true)
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel(stringResource(R.string.stg_quality))
    Spacer(Modifier.height(4.dp))
    QualityOption(stringResource(R.string.stg_quality_original), quality == StorageConfig.QUALITY_ORIGINAL) { quality = StorageConfig.QUALITY_ORIGINAL }
    QualityOption(stringResource(R.string.stg_quality_1080), quality == StorageConfig.QUALITY_1080) { quality = StorageConfig.QUALITY_1080 }
    Spacer(Modifier.height(16.dp))

    val config = StorageConfig(kind, label.trim(), quality, fields.toMap())
    state.storageTestResult?.let { Notice(it); Spacer(Modifier.height(10.dp)) }
    state.storageError?.let { Notice(it); Spacer(Modifier.height(10.dp)) }
    GhostButton(if (state.storageTesting) stringResource(R.string.stg_testing) else stringResource(R.string.stg_test), onClick = { viewModel.testStorage(config) }, enabled = !state.storageTesting && !state.storageSaving, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    val tested = state.storageTested
    // replacing a backend that is already set is asked once more: the new one starts empty
    var confirmChange by remember { mutableStateOf(false) }
    if (confirmChange) {
        AlertDialog(
            onDismissRequest = { confirmChange = false },
            title = { Text(stringResource(R.string.stg_change)) },
            text = { com.opensolr.photos.ui.KeyboardBack(); Text(stringResource(R.string.stg_change_text)) },
            confirmButton = { TextButton(onClick = { confirmChange = false; viewModel.saveStorage() }) { Text(stringResource(R.string.stg_change), color = LocalPalette.current.accent) } },
            dismissButton = { TextButton(onClick = { confirmChange = false }) { Text(stringResource(R.string.stg_cancel), color = LocalPalette.current.ink) } },
            containerColor = LocalPalette.current.paper,
        )
    }
    AccentButton(
        if (state.storageSaving) stringResource(R.string.stg_saving) else stringResource(R.string.stg_save),
        onClick = { if (current != null) confirmChange = true else viewModel.saveStorage() },
        enabled = tested != null && tested.copy(updated = 0L) == config.copy(updated = 0L) && !state.storageSaving,
        modifier = Modifier.fillMaxWidth(),
    )
    if (current != null) {
        Spacer(Modifier.height(10.dp))
        GhostButton(stringResource(R.string.stg_cancel), onClick = { viewModel.editStorage(false) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, lines: Int = 1, hint: String? = null) {
    val p = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it, color = p.muted) } },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink, focusedLabelColor = p.accent, unfocusedLabelColor = p.muted),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun QualityOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { Haptics.tap(view); onSelect() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = { Haptics.tap(view); onSelect() }, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.muted))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = p.ink)
    }
}

@Composable
internal fun kindLabel(kind: String): String = when (kind) {
    StorageConfig.KIND_S3 -> stringResource(R.string.stg_kind_s3)
    else -> stringResource(R.string.stg_kind_webdav)
}

@Composable
private fun storageResult(status: String): String = when (status) {
    "ok" -> stringResource(R.string.stg_rs_ok)
    "stopped" -> stringResource(R.string.stg_rs_stopped)
    "waiting_battery" -> stringResource(R.string.stg_rs_battery)
    "waiting_wifi" -> stringResource(R.string.stg_rs_wifi)
    "no_backend", "no_index" -> stringResource(R.string.stg_rs_no_backend)
    "sign_in_required" -> stringResource(R.string.stg_rs_sign_in)
    else -> stringResource(R.string.stg_rs_failed)
}

private const val SPACE_MARGIN = 512L * 1024 * 1024
