package com.opensolr.photos.ui.screens

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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

private val Corner = RoundedCornerShape(2.dp)

/**
 * Tags for every photo on the grid at once (Cip, 2026-09-16).
 *
 * The same tag field and the same suggestions as editing one photo, but what is typed here is
 * added to all the photos of the view that is open - the photos like one photo, or a group of
 * duplicates - and to nothing else. Never the whole index: the list is exactly what is on screen.
 *
 * Only tags are offered. What a photo shows belongs to that photo alone, so it is not touched,
 * and the tags each photo already has are kept.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkTagSheet(state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    // The ticked photos, or every photo of the view when nothing is ticked.
    val targets = remember(state.hits, state.selectedIds) { viewModel.photosToTag() }
    val count = targets.size
    val onlySelected = state.selectedIds.isNotEmpty()
    var tags by remember { mutableStateOf(emptyList<String>()) }
    var newTag by remember { mutableStateOf("") }
    var tagFieldFocused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(TagSuggestions(emptyList(), emptyList())) }
    var suggestionsLoading by remember { mutableStateOf(false) }
    // Names of people to add, with the names already in the index offered as you type.
    var persons by remember { mutableStateOf(emptyList<String>()) }
    var newPerson by remember { mutableStateOf("") }
    var personFieldFocused by remember { mutableStateOf(false) }
    var personSuggestions by remember { mutableStateOf(emptyList<String>()) }
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

    // The same autocomplete as one photo's editor: the owner's own tags first, then words the
    // photos were read into, asked again a short pause after the last keystroke.
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

    // One request from Android for every photo of the batch; on yes the tags go into each file
    // too. The sheet stays until Android answers, so the answer has somewhere to land.
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingTags by remember { mutableStateOf(emptyList<String>()) }
    var pendingPersons by remember { mutableStateOf(emptyList<String>()) }
    val writeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.tagPhotos(pendingTags, pendingPersons, writeFiles = result.resultCode == android.app.Activity.RESULT_OK)
        onDismiss()
    }

    // Any tap outside a field and its suggestions closes the suggestions (Cip, 2026-09-17).
    var tagsDismissed by remember { mutableStateOf(false) }
    var peopleDismissed by remember { mutableStateOf(false) }
    val outside = com.opensolr.photos.ui.rememberOutsideTap(
        onInside = { area ->
            if (area == "tags") tagsDismissed = false
            if (area == "people") peopleDismissed = false
        },
        onOutside = { tagsDismissed = true; peopleDismissed = true },
    )

    ModalBottomSheet(
        onDismissRequest = { if (!state.bulkTagging) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.paper,
        shape = Corner,
    ) {
        Column(
            with(outside) { Modifier.root() }
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Text(
                "Tag ${Actions.formatCount(count.toLong())} photo${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.headlineSmall, color = p.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                (if (onlySelected) "These tags go on the photos you ticked, and on nothing else in your index. "
                else "Nothing is ticked, so these tags go on every photo this view is showing, and on nothing else in your index. ") +
                    "The tags each photo already has are kept, and what a photo shows is not touched. " +
                    "Saving closes this and the writing carries on in the background.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(20.dp))

            SectionLabel("Tags to add")
            Spacer(Modifier.height(10.dp))
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Row(
                            Modifier
                                .clip(Corner)
                                .background(p.paper)
                                .border(1.dp, p.accent, Corner)
                                .clickable(enabled = !state.bulkTagging) { tags = tags - tag }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = p.accent)
                            Spacer(Modifier.size(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = p.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(with(outside) { Modifier.keep("tags") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it; tagsDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged { tagFieldFocused = it.isFocused },
                    placeholder = { Text("Add a tag, e.g. Maria, holiday 2021", color = p.muted) },
                    singleLine = true,
                    enabled = !state.bulkTagging,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addTag() }, enabled = newTag.isNotBlank() && !state.bulkTagging) { Text("Add", color = p.accent) }
            }
            // The same discreet line as the single photo editor: the height is kept when idle,
            // so nothing below moves.
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
                        BulkSuggestionHeading("Your tags")
                        suggestions.mine.forEach { BulkSuggestionRow(it, onPick = { pick(it) }) }
                    }
                    if (suggestions.mine.isNotEmpty() && suggestions.fromMeanings.isNotEmpty()) {
                        HorizontalDivider(color = p.hairline)
                    }
                    if (suggestions.fromMeanings.isNotEmpty()) {
                        BulkSuggestionHeading("From your photos")
                        suggestions.fromMeanings.forEach { BulkSuggestionRow(it, onPick = { pick(it) }) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("People to add")
            Spacer(Modifier.height(10.dp))
            if (persons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    persons.forEach { person ->
                        Row(
                            Modifier
                                .clip(Corner)
                                .background(p.paper)
                                .border(1.dp, p.accent, Corner)
                                .clickable(enabled = !state.bulkTagging) { persons = persons - person }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(person, style = MaterialTheme.typography.labelSmall, color = p.accent)
                            Spacer(Modifier.size(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = p.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(with(outside) { Modifier.keep("people") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPerson,
                    onValueChange = { newPerson = it; peopleDismissed = false },
                    modifier = Modifier.weight(1f).onFocusChanged { personFieldFocused = it.isFocused },
                    placeholder = { Text("Add a name", color = p.muted) },
                    singleLine = true,
                    enabled = !state.bulkTagging,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addPerson() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addPerson() }, enabled = newPerson.isNotBlank() && !state.bulkTagging) { Text("Add", color = p.accent) }
            }
            if (personFieldFocused && !peopleDismissed && personSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    with(outside) { Modifier.keep("peopleList") }
                        .fillMaxWidth()
                        .background(p.paper, Corner)
                        .border(1.dp, p.hairline, Corner)
                ) {
                    BulkSuggestionHeading("People in your photos")
                    personSuggestions.forEach { name ->
                        BulkSuggestionRow(name, onPick = {
                            persons = (persons + name).distinctWords()
                            newPerson = ""
                        })
                    }
                }
            }
            Text("The names each photo already has are kept. They are saved into the photos themselves too.", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp))

            state.bulkTagError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, title = "Not saved")
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
                AccentButton(
                    "Save",
                    onClick = {
                        // To the index in the background, and into the files once Android allows it.
                        val all = (tags + newTag.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
                        val names = (persons + newPerson.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
                        val uris = Actions.contentUris(context, targets)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && uris.isNotEmpty()) {
                            pendingTags = all
                            pendingPersons = names
                            val request = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                            writeLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                        } else {
                            viewModel.tagPhotos(all, names, writeFiles = true)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = tags.isNotEmpty() || newTag.isNotBlank() || persons.isNotEmpty() || newPerson.isNotBlank(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The small heading of a group in the tag suggestions.
 */
@Composable
private fun BulkSuggestionHeading(text: String) {
    val p = LocalPalette.current
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
}

/**
 * One tag suggestion: tapping it adds the tag to the list that will be written.
 */
@Composable
private fun BulkSuggestionRow(text: String, onPick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = p.ink,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
