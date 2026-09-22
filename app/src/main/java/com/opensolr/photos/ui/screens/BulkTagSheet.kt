package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.tapClickable
import com.opensolr.photos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.opensolr.photos.data.distinctWords

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opensolr.photos.search.FacetValue
import com.opensolr.photos.search.TagSuggestions
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Corner = RoundedCornerShape(2.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkTagSheet(state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val targets = remember(state.hits, state.selectedIds, state.selectedOffscreen) { viewModel.photosToTag() }
    val count = targets.size
    var tags by remember { mutableStateOf(emptyList<String>()) }
    var newTag by remember { mutableStateOf("") }
    var tagFieldFocused by remember { mutableStateOf(false) }
    var tagsReplace by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(TagSuggestions(emptyList(), emptyList())) }
    var suggestionsLoading by remember { mutableStateOf(false) }

    var persons by remember { mutableStateOf(emptyList<String>()) }
    var newPerson by remember { mutableStateOf("") }
    var personFieldFocused by remember { mutableStateOf(false) }
    var personsReplace by remember { mutableStateOf(false) }
    var personSuggestions by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(state.selectedIds) { viewModel.loadSelectionWords() }

    LaunchedEffect(newPerson, personFieldFocused, persons) {
        if (!personFieldFocused) return@LaunchedEffect
        if (newPerson.isNotEmpty()) delay(250)
        personSuggestions = viewModel.personSuggestions(newPerson, persons)
    }
    fun addPerson() {
        val parts = newPerson.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        persons = (persons + parts).distinctWords()
        newPerson = ""
    }

    LaunchedEffect(newTag, tagFieldFocused, tags) {
        if (!tagFieldFocused) { suggestionsLoading = false; return@LaunchedEffect }
        suggestionsLoading = true
        try {
            if (newTag.isNotEmpty()) delay(250)
            suggestions = viewModel.tagSuggestions(newTag, tags)
        } finally {
            suggestionsLoading = false
        }
    }

    fun pick(tag: String) {
        tags = (tags + tag).distinctWords()
        newTag = ""
    }

    fun addTag() {
        val parts = newTag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        tags = (tags + parts).distinctWords()
        newTag = ""
    }

    fun typedTags(): List<String>? {
        val all = (tags + newTag.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
        return if (all.isEmpty() && !tagsReplace) null else all
    }
    fun typedPersons(): List<String>? {
        val all = (persons + newPerson.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
        return if (all.isEmpty() && !personsReplace) null else all
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val writeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.tagPhotos(typedTags(), tagsReplace, typedPersons(), personsReplace, writeFiles = result.resultCode == android.app.Activity.RESULT_OK)
    }

    var placeOnlyMissing by remember { mutableStateOf(false) }
    var pickingPlace by remember { mutableStateOf(false) }
    var pendingPlace by remember { mutableStateOf<Triple<Double, Double, List<com.opensolr.photos.search.PhotoHit>>?>(null) }
    val placeWriteLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val (lat, lon, chosen) = pendingPlace ?: return@rememberLauncherForActivityResult
        pendingPlace = null

        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.placePhotos(chosen, lat, lon, writeFiles = true)
            onDismiss()
        }
    }

    var tagsDismissed by remember { mutableStateOf(false) }
    var peopleDismissed by remember { mutableStateOf(false) }
    val outside = com.opensolr.photos.ui.rememberOutsideTap(
        onInside = { area ->
            if (area == "tags") tagsDismissed = false
            if (area == "people") peopleDismissed = false
        },
        onOutside = { tagsDismissed = true; peopleDismissed = true },
    )

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(state.bulkTagging) {
        if (state.bulkTagging) started = true
        else if (started && state.bulkTagError == null) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.bulkTagging) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.paper,
        shape = Corner,
        dragHandle = null,
    ) {
        Column(
            with(outside) { Modifier.root() }
                .fillMaxWidth()
                .fillMaxHeight()
                .nestedScroll(rememberNoSheetDrag())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                pluralStringResource(R.plurals.tg_title, count, Actions.formatCount(count.toLong())),
                style = MaterialTheme.typography.headlineSmall, color = p.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.tg_lead),
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(20.dp))

            ModeHeader(
                title = stringResource(R.string.tg_people),
                replace = personsReplace,
                enabled = !state.bulkTagging,
                onChange = { personsReplace = it },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (personsReplace) stringResource(R.string.tg_people_replace)
                else stringResource(R.string.tg_people_add),
                style = MaterialTheme.typography.bodySmall,
                color = if (personsReplace) p.accent else p.muted,
            )
            Spacer(Modifier.height(10.dp))
            if (persons.isNotEmpty()) {
                WordChips(persons, enabled = !state.bulkTagging) { persons = persons - it }
                Spacer(Modifier.height(10.dp))
            }
            Row(with(outside) { Modifier.keep("people") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPerson,
                    onValueChange = { newPerson = it; peopleDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged { personFieldFocused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.tg_add_name), color = p.muted) },
                    singleLine = true,
                    enabled = !state.bulkTagging,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addPerson() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addPerson() }, enabled = newPerson.isNotBlank() && !state.bulkTagging) { Text(stringResource(R.string.tg_add), color = p.accent) }
            }
            if (personFieldFocused && !peopleDismissed && personSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    with(outside) { Modifier.keep("peopleList") }
                        .fillMaxWidth()
                        .background(p.paper, Corner)
                        .border(1.dp, p.hairline, Corner)
                ) {
                    BulkSuggestionHeading(stringResource(R.string.tg_people_in_photos))
                    personSuggestions.forEach { name ->
                        BulkSuggestionRow(name, onPick = {
                            persons = (persons + name).distinctWords()
                            newPerson = ""
                        })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            ModeHeader(
                title = stringResource(R.string.tg_my_tags),
                replace = tagsReplace,
                enabled = !state.bulkTagging,
                onChange = { tagsReplace = it },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (tagsReplace) stringResource(R.string.tg_tags_replace)
                else stringResource(R.string.tg_tags_add),
                style = MaterialTheme.typography.bodySmall,
                color = if (tagsReplace) p.accent else p.muted,
            )
            Spacer(Modifier.height(10.dp))
            if (tags.isNotEmpty()) {
                WordChips(tags, enabled = !state.bulkTagging) { tags = tags - it }
                Spacer(Modifier.height(10.dp))
            }
            Row(with(outside) { Modifier.keep("tags") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it; tagsDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged { tagFieldFocused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.tg_add_tag), color = p.muted) },
                    singleLine = true,
                    enabled = !state.bulkTagging,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addTag() }, enabled = newTag.isNotBlank() && !state.bulkTagging) { Text(stringResource(R.string.tg_add), color = p.accent) }
            }

            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp)) {
                if (tagFieldFocused && suggestionsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
                }
            }
            if (tagFieldFocused && !tagsDismissed && !suggestions.isEmpty) {
                Spacer(Modifier.height(6.dp))
                Column(
                    with(outside) { Modifier.keep("tagList") }
                        .fillMaxWidth()
                        .background(p.paper, Corner)
                        .border(1.dp, p.hairline, Corner)
                ) {
                    if (suggestions.mine.isNotEmpty()) {
                        BulkSuggestionHeading(stringResource(R.string.tg_your_tags))
                        suggestions.mine.forEach { BulkSuggestionRow(it, onPick = { pick(it) }) }
                    }
                    if (suggestions.mine.isNotEmpty() && suggestions.fromMeanings.isNotEmpty()) {
                        HorizontalDivider(color = p.hairline)
                    }
                    if (suggestions.fromMeanings.isNotEmpty()) {
                        BulkSuggestionHeading(stringResource(R.string.tg_from_photos))
                        suggestions.fromMeanings.forEach { BulkSuggestionRow(it, onPick = { pick(it) }) }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.tg_place))
            Spacer(Modifier.height(4.dp))
            val placeless = remember(targets) { targets.filter { it.latLon == null } }
            Text(
                if (placeOnlyMissing) pluralStringResource(R.plurals.tg_place_missing_text, placeless.size, Actions.formatCount(placeless.size.toLong()))
                else stringResource(R.string.tg_place_all_text),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaceModeChip(stringResource(R.string.tg_place_all), selected = !placeOnlyMissing, enabled = !state.bulkTagging) { placeOnlyMissing = false }
                PlaceModeChip(stringResource(R.string.tg_place_missing, Actions.formatCount(placeless.size.toLong())), selected = placeOnlyMissing, enabled = !state.bulkTagging) { placeOnlyMissing = true }
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(
                stringResource(R.string.tg_choose_map),
                onClick = { pickingPlace = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.bulkTagging && (if (placeOnlyMissing) placeless.isNotEmpty() else targets.isNotEmpty()),
            )
            if (pickingPlace) {
                PlacePickerDialog(
                    start = targets.firstNotNullOfOrNull { it.latLon },
                    viewModel = viewModel,
                    onDismiss = { pickingPlace = false },
                    onPick = { lat, lon ->
                        pickingPlace = false
                        val chosen = if (placeOnlyMissing) placeless else targets
                        pendingPlace = Triple(lat, lon, chosen)
                        scope.launch {
                            val uris = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                Actions.contentUris(context, chosen.filter { com.opensolr.photos.media.PhotoReader.canWriteExif(it.mime) })
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && uris.isNotEmpty()) {
                                val request = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                                placeWriteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                            } else {
                                pendingPlace = null
                                viewModel.placePhotos(chosen, lat, lon, writeFiles = uris.isNotEmpty())
                                onDismiss()
                            }
                        }
                    },
                )
            }

            state.bulkTagError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, title = stringResource(R.string.tg_not_saved))
            }

            if (state.bulkTagging && state.bulkTagTotal > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (state.bulkTagWriting) R.string.tg_writing else R.string.tg_saving, Actions.formatCount(state.bulkTagDone.toLong()), Actions.formatCount(state.bulkTagTotal.toLong())),
                    style = MaterialTheme.typography.bodySmall, color = p.muted,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (state.bulkTagTotal == 0) 0f else state.bulkTagDone.toFloat() / state.bulkTagTotal },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = p.accent,
                    trackColor = p.chip,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(stringResource(R.string.tg_cancel), onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !state.bulkTagging)
                AccentButton(
                    stringResource(R.string.tg_save),
                    onClick = {

                        scope.launch {
                            val uris = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                Actions.contentUris(context, targets)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && uris.isNotEmpty()) {
                                val request = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                                writeLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                            } else {
                                viewModel.tagPhotos(typedTags(), tagsReplace, typedPersons(), personsReplace, writeFiles = true)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.bulkTagging && (typedTags() != null || typedPersons() != null),
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.tg_already))
            Spacer(Modifier.height(4.dp))
            if (state.selectionWordsLoading) {
                Text(stringResource(R.string.tg_reading), style = MaterialTheme.typography.bodySmall, color = p.muted)
            } else if (state.selectionPersons.isEmpty() && state.selectionTags.isEmpty()) {
                Text(stringResource(R.string.tg_none_yet), style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            if (state.selectionPersons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.tg_people_caps), style = MaterialTheme.typography.labelSmall, color = p.muted)
                Spacer(Modifier.height(6.dp))
                CountedWords(state.selectionPersons)
            }
            if (state.selectionTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.tg_tags_caps), style = MaterialTheme.typography.labelSmall, color = p.muted)
                Spacer(Modifier.height(6.dp))
                CountedWords(state.selectionTags)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PlaceModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.accent else p.ink,
        modifier = Modifier
            .border(if (selected) 1.5.dp else 1.dp, if (selected) p.accent else p.hairline, Corner)
            .background(if (selected) p.paper else p.chip, Corner)
            .tapClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun ModeHeader(title: String, replace: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, modifier = Modifier.weight(1f))
        Text(
            if (replace) stringResource(R.string.tg_replace) else stringResource(R.string.tg_mode_add),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (replace) p.accent else p.muted,
        )
        Spacer(Modifier.size(8.dp))
        Switch(
            checked = replace,
            onCheckedChange = { if (enabled) onChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccentFill,
                checkedTrackColor = p.accentFill,
                uncheckedThumbColor = p.paper,
                uncheckedTrackColor = p.hairline,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordChips(words: List<String>, enabled: Boolean, onRemove: (String) -> Unit) {
    val p = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        words.forEach { word ->
            Row(
                Modifier
                    .clip(Corner)
                    .background(p.paper)
                    .border(1.dp, p.accent, Corner)
                    .tapClickable(enabled = enabled) { onRemove(word) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(word, style = MaterialTheme.typography.labelSmall, color = p.accent)
                Spacer(Modifier.size(4.dp))
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tg_remove), tint = p.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountedWords(values: List<FacetValue>) {
    val p = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.take(SHOWN_EXISTING).forEach { value ->
            Text(
                "${value.value} (${Actions.formatCount(value.count.toLong())})",
                style = MaterialTheme.typography.labelSmall,
                color = p.ink,
                modifier = Modifier
                    .clip(Corner)
                    .background(p.chip)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        if (values.size > SHOWN_EXISTING) {
            Text(
                stringResource(R.string.tg_n_more, Actions.formatCount((values.size - SHOWN_EXISTING).toLong())),
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
            )
        }
    }
}

private const val SHOWN_EXISTING = 40

@Composable
private fun BulkSuggestionHeading(text: String) {
    val p = LocalPalette.current
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun BulkSuggestionRow(text: String, onPick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = p.ink,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().tapClickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
