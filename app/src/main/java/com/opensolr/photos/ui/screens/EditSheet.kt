package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.tapClickable
import com.opensolr.photos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.opensolr.photos.data.distinctWords

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.ui.Actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.search.TagSuggestions
import kotlinx.coroutines.delay
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(hit: PhotoHit, state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {

    @Suppress("NAME_SHADOWING")
    val hit = state.hits.firstOrNull { it.id == hit.id } ?: hit
    val p = LocalPalette.current

    val focusManager = LocalFocusManager.current
    val originalMeaning = remember(hit.id) { hit.meaning.trim() }
    var tags by remember(hit.id) { mutableStateOf(hit.customTags) }
    var newTag by remember(hit.id) { mutableStateOf("") }
    var meaning by remember(hit.id) { mutableStateOf(hit.meaning) }
    var resetWording by remember(hit.id) { mutableStateOf(false) }

    val context = LocalContext.current
    val originalPersons = remember(hit.id) { hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    var persons by remember(hit.id) { mutableStateOf(originalPersons) }
    var newPerson by remember(hit.id) { mutableStateOf("") }

    var personFieldFocused by remember(hit.id) { mutableStateOf(false) }

    var peopleDismissed by remember(hit.id) { mutableStateOf(false) }
    var personSuggestions by remember(hit.id) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(newPerson, personFieldFocused, persons) {
        if (!personFieldFocused) return@LaunchedEffect
        if (newPerson.isNotEmpty()) delay(250)
        personSuggestions = viewModel.personSuggestions(newPerson, persons)
    }

    var tagFieldFocused by remember(hit.id) { mutableStateOf(false) }
    var suggestions by remember(hit.id) { mutableStateOf(TagSuggestions(emptyList(), emptyList())) }

    var suggestionsLoading by remember(hit.id) { mutableStateOf(false) }

    var suggestionsDismissed by remember(hit.id) { mutableStateOf(false) }

    var sheetCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tagRowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var suggestionListCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var personAreaCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(newTag, tagFieldFocused, tags, suggestionsDismissed) {
        if (!tagFieldFocused || suggestionsDismissed) { suggestionsLoading = false; return@LaunchedEffect }
        suggestionsLoading = true
        try {
            if (newTag.isNotEmpty()) delay(250)
            suggestions = viewModel.tagSuggestions(newTag, tags)
        } finally {

            suggestionsLoading = false
        }
    }

    fun addPerson() {
        val parts = newPerson.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        persons = (persons + parts).distinctWords()
        newPerson = ""
    }

    // New wording only when the owner changed the text; untouched text is never sent as theirs
    fun ownWording(): String? = meaning.trim().takeIf { it.isNotEmpty() && it != originalMeaning }
    fun wordingCleared(): Boolean = resetWording && meaning.isBlank()

    fun finishSave(changedPersons: List<String>?) {
        val wording = ownWording()
        viewModel.saveEdits(hit, tags, wording, changedPersons, resetWording = wordingCleared(), onDone = onDismiss)
    }

    val originalTags = remember(hit.id) { hit.customTags }

    LaunchedEffect(hit.id) {
        val fromFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Actions.contentUris(context, listOf(hit)).firstOrNull()?.let { PhotoReader.tagsIn(context, it) } ?: emptyList()
        }
        if (fromFile.isNotEmpty()) tags = (tags + fromFile).distinctWords()
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val namesChanged = persons != originalPersons
        if (result.resultCode == Activity.RESULT_OK) {
            Actions.contentUris(context, listOf(hit)).firstOrNull()?.let {
                PhotoReader.writeXmp(context, it, hit.mime, if (namesChanged) persons else null, tags, ownWording(), clearMeaning = wordingCleared())
            }
        }
        finishSave(if (namesChanged) persons else null)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.paper,
        shape = Corner,
        dragHandle = null,
    ) {

        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .onGloballyPositioned { sheetCoords = it }

                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val sheet = sheetCoords
                        fun inside(target: LayoutCoordinates?): Boolean =
                            sheet != null && target != null && sheet.isAttached && target.isAttached &&
                                sheet.localBoundingBoxOf(target, clipBounds = false).contains(down.position)
                        if (inside(personAreaCoords)) {
                            peopleDismissed = false
                            suggestionsDismissed = true
                        } else if (inside(tagRowCoords)) {
                            peopleDismissed = true

                            suggestionsDismissed = false
                        } else if (!inside(suggestionListCoords)) {
                            suggestionsDismissed = true
                            peopleDismissed = true
                            focusManager.clearFocus(force = true)
                        }
                    }
                }
                .nestedScroll(rememberNoSheetDrag())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.tg_edit, hit.fileName), style = MaterialTheme.typography.headlineSmall, color = p.ink)
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.tg_people))
            Spacer(Modifier.height(10.dp))
            if (persons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    persons.forEach { person ->
                        Row(
                            Modifier
                                .clip(Corner)
                                .background(p.paper)
                                .border(1.dp, p.accent, Corner)
                                .clickable { persons = persons - person }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(person, style = MaterialTheme.typography.labelSmall, color = p.accent)
                            Spacer(Modifier.size(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tg_remove), tint = p.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Column(Modifier.onGloballyPositioned { personAreaCoords = it }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPerson,
                    onValueChange = { newPerson = it; peopleDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged { personFieldFocused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.tg_add_name), color = p.muted) },
                    singleLine = true,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addPerson() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addPerson() }, enabled = newPerson.isNotBlank()) { Text(stringResource(R.string.tg_add), color = p.accent) }
            }
            if (personFieldFocused && !peopleDismissed && personSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.paper, Corner)
                        .border(1.dp, p.hairline, Corner)
                ) {
                    SuggestionHeading(stringResource(R.string.tg_people_in_photos))
                    personSuggestions.forEach { name ->
                        SuggestionRow(name, onPick = {
                            persons = (persons + name).distinctWords()
                            newPerson = ""
                        })
                    }
                }
            }
            }
            Text(stringResource(R.string.tg_saved_in_file), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.tg_my_tags))
            Spacer(Modifier.height(10.dp))
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Row(
                            Modifier
                                .clip(Corner)
                                .background(p.paper)
                                .border(1.dp, p.accent, Corner)
                                .clickable { tags = tags - tag }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = p.accent)
                            Spacer(Modifier.size(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tg_remove), tint = p.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                Modifier.onGloballyPositioned { tagRowCoords = it },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it; suggestionsDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged {
                        tagFieldFocused = it.isFocused
                        if (it.isFocused) suggestionsDismissed = false
                    },
                    placeholder = { Text(stringResource(R.string.tg_add_tag), color = p.muted) },
                    singleLine = true,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addTag() }, enabled = newTag.isNotBlank()) { Text(stringResource(R.string.tg_add), color = p.accent) }
            }

            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp)) {
                if (tagFieldFocused && !suggestionsDismissed && suggestionsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
                }
            }

            if (tagFieldFocused && !suggestionsDismissed && !suggestions.isEmpty) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { suggestionListCoords = it }
                        .background(p.paper, Corner)
                        .border(1.dp, p.hairline, Corner)
                ) {
                    if (suggestions.mine.isNotEmpty()) {
                        SuggestionHeading(stringResource(R.string.tg_your_tags))
                        suggestions.mine.forEach { SuggestionRow(it, onPick = { pick(it) }) }
                    }
                    if (suggestions.mine.isNotEmpty() && suggestions.fromMeanings.isNotEmpty()) {
                        HorizontalDivider(color = p.hairline)
                    }
                    if (suggestions.fromMeanings.isNotEmpty()) {
                        SuggestionHeading(stringResource(R.string.tg_from_photos))
                        suggestions.fromMeanings.forEach { SuggestionRow(it, onPick = { pick(it) }) }
                    }
                }
            }
            Text(stringResource(R.string.tg_tags_hint), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.tg_shows))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = meaning,
                onValueChange = { meaning = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = Corner,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tg_words_hint), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f).padding(top = 6.dp, end = 8.dp))
                TextButton(onClick = { meaning = ""; resetWording = true }, enabled = meaning.isNotBlank()) { Text(stringResource(R.string.tg_reset), color = p.accent) }
            }
            state.editError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, title = stringResource(R.string.tg_not_saved))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(stringResource(R.string.tg_cancel), onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !state.editSaving)
                AccentButton(
                    if (state.editSaving) stringResource(R.string.tg_saving) else stringResource(R.string.tg_save),
                    onClick = {
                        addTag()
                        addPerson()

                        val names = persons
                        val namesChanged = names != originalPersons

                        run {
                            val uri = Actions.contentUris(context, listOf(hit)).firstOrNull()
                            when {
                                uri == null -> finishSave(if (namesChanged) names else null)
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                    val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                                    writeLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                }
                                else -> {
                                    PhotoReader.writeXmp(context, uri, hit.mime, if (namesChanged) names else null, tags, ownWording(), clearMeaning = wordingCleared())
                                    finishSave(if (namesChanged) names else null)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.editSaving,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SuggestionHeading(text: String) {
    val p = LocalPalette.current
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun SuggestionRow(text: String, onPick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = p.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().tapClickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
