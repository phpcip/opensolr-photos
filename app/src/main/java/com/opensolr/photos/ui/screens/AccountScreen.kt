package com.opensolr.photos.ui.screens

import com.opensolr.photos.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    val account = state.account

    // What the cache holds right now, read when the screen opens.
    LaunchedEffect(Unit) {
        viewModel.refreshCacheInfo()
        viewModel.refreshPlacesToWrite()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(stringResource(R.string.acc_title), onBack = { viewModel.back() })

        // Five zones, all folded until opened, each remembered as it was left for as long as
        // the app runs (Cip, 2026-09-20). A folded zone still says, on its heading, how many
        // things in it want attention: a new version, a plan warning.
        FilterGroup(stringResource(R.string.acc_zone_updates), if (state.update != null) 1 else 0, "updates" in state.meZonesOpen, { viewModel.toggleMeZone("updates") }) {
            Column {
                // The installed version, and a check that does not wait for the once-a-day one.
                InfoRow(stringResource(R.string.acc_app_version), BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    if (state.updateChecking) stringResource(R.string.acc_checking) else stringResource(R.string.acc_check_updates),
                    onClick = { viewModel.checkForUpdateNow() },
                    enabled = !state.updateChecking,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A newer release found by the daily check is said here, never on the photos screen
                // (Cip, 2026-09-17: the main screen carries as few messages as possible).
                if (state.updateResult == null) {
                    state.update?.let { newer ->
                        Spacer(Modifier.height(12.dp))
                        Notice(newer.notes.ifBlank { stringResource(R.string.acc_update_notes) }, title = stringResource(R.string.acc_version_available, newer.version))
                        Spacer(Modifier.height(10.dp))
                        AccentButton(stringResource(R.string.acc_download, newer.version), onClick = { Actions.openUrl(context, newer.pageUrl) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                state.updateResult?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    Notice(result, title = if (state.update != null) stringResource(R.string.acc_update_available) else stringResource(R.string.acc_version))
                    // A newer release leads to its page; the install stays the user's and Android's.
                    state.update?.let { newer ->
                        Spacer(Modifier.height(10.dp))
                        AccentButton(stringResource(R.string.acc_download, newer.version), onClick = { Actions.openUrl(context, newer.pageUrl) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        FilterGroup(stringResource(R.string.acc_zone_info), 0, "info" in state.meZonesOpen, { viewModel.toggleMeZone("info") }) {
            Column {
                InfoRow(stringResource(R.string.acc_email), state.email ?: "")
                account?.let { InfoRow(stringResource(R.string.acc_plan), it.planLabel, onOpen = { Actions.openUrl(context, Actions.DASHBOARD_URL) }) }
                state.indexName?.let { InfoRow(stringResource(R.string.acc_index), it) }
                state.environment?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.acc_environment), it) }
                Spacer(Modifier.height(18.dp))
            }
        }
        FilterGroup(stringResource(R.string.acc_zone_stats), state.planWarnings.size, "stats" in state.meZonesOpen, { viewModel.toggleMeZone("stats") }) {
            Column {
                if (account != null) {
                    // What the limits mean right now, before the numbers.
                    state.planWarnings.forEach { warning ->
                        Notice(warning.text, title = warning.title)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (state.planWarnings.isNotEmpty()) Spacer(Modifier.height(10.dp))
                    UsageRow(
                        stringResource(R.string.acc_disk),
                        Actions.formatMb(account.diskUsedMb), Actions.formatMb(account.diskLimitMb),
                        if (account.diskLimitMb > 0) (account.diskUsedMb / account.diskLimitMb).toFloat() else null,
                    )
                    UsageRow(
                        // Short on purpose: a big allowance ("7 MB of 200000.0 GB") leaves the value no
                        // room next to a long label (Cip, 2026-09-16).
                        stringResource(R.string.acc_bw),
                        Actions.formatMb(account.bandwidthUsedMb), Actions.formatMb(account.bandwidthLimitMb),
                        if (account.bandwidthLimitMb > 0) (account.bandwidthUsedMb / account.bandwidthLimitMb).toFloat() else null,
                    )
                    if (account.maxAiRequests > 0) {
                        UsageRow(
                            stringResource(R.string.acc_ai_month),
                            Actions.formatCount(account.aiRequestsUsed.toLong()), Actions.formatCount(account.maxAiRequests.toLong()),
                            account.aiRequestsUsed.toFloat() / account.maxAiRequests,
                        )
                    } else {
                        InfoRow(stringResource(R.string.acc_ai_month), stringResource(R.string.acc_no_cap))
                    }
                    InfoRow(stringResource(R.string.acc_photos_month), account.photosPerMonth?.let { stringResource(R.string.acc_about, Actions.formatCount(it.toLong())) } ?: stringResource(R.string.acc_no_cap))
                    account.photosLeftThisMonth?.let { InfoRow(stringResource(R.string.acc_photos_left), stringResource(R.string.acc_about, Actions.formatCount(it.toLong()))) }
                    InfoRow(stringResource(R.string.acc_meaning_search), if (account.vectorAllowed) stringResource(R.string.acc_included) else stringResource(R.string.acc_not_included))
                    InfoRow(stringResource(R.string.acc_indexes), stringResource(R.string.acc_x_of_y, account.indexesUsed.toString(), account.indexLimit.toString()))
                    if (account.indexedDocs > 0) InfoRow(stringResource(R.string.acc_photos_index), Actions.formatCount(account.indexedDocs))
                    InfoRow(stringResource(R.string.acc_updated), Actions.formatDate(account.refreshedAt))
                    Spacer(Modifier.height(20.dp))
    
                    Notice(
                        stringResource(R.string.acc_limit_text),
                        title = stringResource(R.string.acc_limit_title),
                    )
                    Spacer(Modifier.height(14.dp))
                    AccentButton(stringResource(R.string.acc_upgrade), onClick = { Actions.openUrl(context, Actions.PRICING_URL) }, modifier = Modifier.fillMaxWidth())
                }
                state.accountError?.let {
                    Spacer(Modifier.height(14.dp))
                    Notice(it, title = stringResource(R.string.acc_could_not_refresh))
                }
                Spacer(Modifier.height(10.dp))
                GhostButton(if (state.accountRefreshing) stringResource(R.string.acc_refreshing) else stringResource(R.string.acc_refresh), onClick = { viewModel.refreshAccount() }, enabled = !state.accountRefreshing, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
            }
        }
        FilterGroup(stringResource(R.string.acc_zone_settings), 0, "settings" in state.meZonesOpen, { viewModel.toggleMeZone("settings") }) {
            Column {
                // The taps the app gives back under a finger, which not everyone wants.
                SectionLabel(stringResource(R.string.acc_feedback))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.acc_haptics), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                        Text(
                            stringResource(R.string.acc_haptics_text),
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
    
                // The app's language (Cip, 2026-09-20): the phone's own by default, or one picked here.
                // On Android 13 and later it is the same setting as the system's per-app language.
                SectionLabel(stringResource(R.string.acc_language))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.acc_language_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
                Spacer(Modifier.height(10.dp))
                val chosenLanguage = remember { com.opensolr.photos.ui.AppLanguage.chosen(context) }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf("" to stringResource(R.string.acc_language_phone)) + com.opensolr.photos.ui.AppLanguage.SUPPORTED).forEach { (tag, name) ->
                        val on = tag == chosenLanguage
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) p.accent else p.ink,
                            modifier = Modifier
                                .border(if (on) 1.5.dp else 1.dp, if (on) p.accent else p.hairline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .background(if (on) p.paper else p.chip, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .clickable {
                                    var host: android.content.Context? = context
                                    while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
                                    (host as? android.app.Activity)?.let { com.opensolr.photos.ui.AppLanguage.choose(it, tag) }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
    
                // New photos that come without a position get the phone's own (Cip, 2026-09-19). Android
                // asks first; in the background, where the sync runs, it asks a second time.
                SectionLabel(stringResource(R.string.acc_places))
                val backgroundAsk = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { viewModel.refreshPlacesToWrite() }
                val locationAsk = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    viewModel.refreshPlacesToWrite()
                    if (granted.values.any { it } && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        backgroundAsk.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.acc_place_new), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                        Text(
                            stringResource(R.string.acc_place_new_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.autoPlace,
                        onCheckedChange = { on ->
                            viewModel.setAutoPlace(on)
                            if (on && !state.autoPlaceLocation) {
                                locationAsk.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accentFill, checkedThumbColor = p.onAccentFill, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
                    )
                }
                if (state.autoPlace && !state.autoPlaceLocation) {
                    Text(
                        stringResource(R.string.acc_place_no_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                } else if (state.autoPlace && !state.autoPlaceBackground) {
                    Text(
                        stringResource(R.string.acc_place_no_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (state.placesToWrite > 0) {
                    val writePlaces = rememberPlaceWriter(viewModel)
                    Text(
                        pluralStringResource(R.plurals.acc_places_pending, state.placesToWrite, Actions.formatCount(state.placesToWrite.toLong())),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton(stringResource(R.string.acc_save_places), onClick = writePlaces, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(28.dp))
    
                // Semantic <-> lexical balance of a search by meaning, as the search platform's settings
                // have it (Cip, 2026-09-17): 0 is meaning only, 1 is words only.
                SectionLabel(stringResource(R.string.acc_tuning))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.acc_balance), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                        Text(stringResource(R.string.acc_balance_text), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Spacer(Modifier.width(12.dp))
                    GhostButton(
                        stringResource(R.string.acc_reset),
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
                        if (limits?.vectorAllowed == true) stringResource(R.string.acc_balance_quota)
                        else stringResource(R.string.acc_balance_plan),
                        style = MaterialTheme.typography.bodySmall, color = p.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                val balanceView = androidx.compose.ui.platform.LocalView.current
                var balance by remember(state.lexicalWeight) { mutableStateOf(state.lexicalWeight) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.acc_semantic), style = MaterialTheme.typography.bodySmall, color = p.muted)
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
                    Text(stringResource(R.string.acc_lexical), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    Spacer(Modifier.width(10.dp))
                    Text(String.format(java.util.Locale.US, "%.2f", balance), style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.ink)
                }
                Spacer(Modifier.height(28.dp))
    
                // How long an answer from the index may be reused, and a way to throw them all away.
                SectionLabel(stringResource(R.string.acc_cache))
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.acc_cache_text),
                    style = MaterialTheme.typography.bodyMedium, color = p.muted,
                )
                Spacer(Modifier.height(14.dp))
                // Reset whenever the stored value changes, so the field always shows what is in force.
                var seconds by remember(state.cacheSeconds) { mutableStateOf(state.cacheSeconds.toString()) }
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { typed -> seconds = typed.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.acc_cache_seconds)) },
                    supportingText = { Text(stringResource(R.string.acc_cache_limits, SearchCache.MIN_SECONDS.toString(), state.cacheSeconds.toString())) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        stringResource(R.string.acc_save),
                        onClick = { viewModel.setCacheSeconds(seconds.toIntOrNull() ?: SearchCache.DEFAULT_SECONDS) },
                        enabled = seconds.toIntOrNull()?.let { it != state.cacheSeconds } ?: false,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        if (state.cachedCount > 0) stringResource(R.string.acc_clear_cache_n, state.cachedCount.toString()) else stringResource(R.string.acc_clear_cache),
                        onClick = { viewModel.clearSearchCache() },
                        enabled = state.cachedCount > 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        FilterGroup(stringResource(R.string.acc_advanced), 0, "advanced" in state.meZonesOpen, { viewModel.toggleMeZone("advanced") }) {
            Column {
                Text(
                    stringResource(R.string.acc_regular_index),
                    style = MaterialTheme.typography.bodyMedium, color = p.muted,
                )
                Spacer(Modifier.height(14.dp))
                state.indexName?.let { name ->
                    GhostButton(stringResource(R.string.acc_open_index), onClick = { Actions.openUrl(context, Actions.indexPanelUrl(name)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                }
                GhostButton(stringResource(R.string.acc_sign_out), onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.acc_sign_out_q)) },
            text = { Text(stringResource(R.string.acc_sign_out_text)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text(stringResource(R.string.acc_sign_out), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.acc_cancel), color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }
}
