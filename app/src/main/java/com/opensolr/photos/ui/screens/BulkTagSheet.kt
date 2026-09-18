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

/**
 * Tagging every ticked photo at once.
 *
 * People come first: a name is what most people come here to put right (Cip, 2026-09-18). Each of
 * the two fields has its own switch - **Add** puts what is typed on top of what each photo already
 * carries, **Replace** makes it the whole of that field on every ticked photo, so a person renamed
 * overnight is renamed everywhere in one save.
 *
 * Under the form, what the ticked photos already carry is listed, counted by the index over the
 * whole selection - for the owner to read, not to edit.
 *
 * Nothing here waits for the index: the save is written on the phone and the sync that follows
 * carries it up. Only the writing into the photo files themselves happens on the spot, because
 * Android asks the owner to allow it.
 */
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
    // Names of people, with the names already in the index offered as you type.
    var persons by remember { mutableStateOf(emptyList<String>()) }
    var newPerson by remember { mutableStateOf("") }
    var personFieldFocused by remember { mutableStateOf(false) }
    var personsReplace by remember { mutableStateOf(false) }
    var personSuggestions by remember { mutableStateOf(emptyList<String>()) }

    // What the ticked photos carry today, read once for the whole selection.
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

    // Everything the two fields say, once what is still typed in them is counted in. A field left
    // untouched stays null, so it is not changed on any photo.
    fun typedTags(): List<String>? {
        val all = (tags + newTag.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
        return if (all.isEmpty() && !tagsReplace) null else all
    }
    fun typedPersons(): List<String>? {
        val all = (persons + newPerson.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctWords()
        return if (all.isEmpty() && !personsReplace) null else all
    }

    // One request from Android for every photo of the batch; on yes the words go into each file
    // too. The sheet stays until Android answers, so the answer has somewhere to land.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val writeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.tagPhotos(typedTags(), tagsReplace, typedPersons(), personsReplace, writeFiles = result.resultCode == android.app.Activity.RESULT_OK)
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

    // The files are written while the owner waits, so the sheet closes itself when that is done.
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
                "These go on the photos you ticked, and on nothing else in your index. Saving " +
                    "writes them on this phone at once; your index is updated by the sync that follows.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(20.dp))

            // ---- People, first: the field most people come here for.
            ModeHeader(
                title = "People",
                replace = personsReplace,
                enabled = !state.bulkTagging,
                onChange = { personsReplace = it },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (personsReplace) "Replace: these names become the only names on every ticked photo. Names they have now are removed, in your index and in the files."
                else "Add: these names go on top of the names each photo already has.",
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

            Spacer(Modifier.height(24.dp))

            // ---- The owner's tags, which become albums of their own.
            ModeHeader(
                title = "My tags (Albums)",
                replace = tagsReplace,
                enabled = !state.bulkTagging,
                onChange = { tagsReplace = it },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (tagsReplace) "Replace: these tags become the only tags on every ticked photo. Tags they have now are removed, in your index and in the files."
                else "Add: these tags go on top of the tags each photo already has.",
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

            state.bulkTagError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, title = "Not saved")
            }

            // While the files are being written there is nothing to do but wait, so the bar says
            // how far it has got. Only the files: the index is updated afterwards, by the sync.
            if (state.bulkTagging && state.bulkTagTotal > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Writing your words into ${Actions.formatCount(state.bulkTagDone.toLong())} of ${Actions.formatCount(state.bulkTagTotal.toLong())} photos…",
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
                GhostButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !state.bulkTagging)
                AccentButton(
                    "Save",
                    onClick = {
                        // Looking the files up asks the phone's media store about every ticked
                        // photo, so it happens off the screen's own thread: on a selection of
                        // thousands it froze the sheet before anything was written
                        // (Cip, 2026-09-18).
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

            // ---- What the ticked photos carry today, for the owner to see. Not editable: the
            // two fields above are where changes are made (Cip, 2026-09-18).
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(16.dp))
            SectionLabel("Already on these photos")
            Spacer(Modifier.height(4.dp))
            if (state.selectionWordsLoading) {
                Text("Reading your index…", style = MaterialTheme.typography.bodySmall, color = p.muted)
            } else if (state.selectionPersons.isEmpty() && state.selectionTags.isEmpty()) {
                Text("No names and no tags yet.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            if (state.selectionPersons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("PEOPLE", style = MaterialTheme.typography.labelSmall, color = p.muted)
                Spacer(Modifier.height(6.dp))
                CountedWords(state.selectionPersons)
            }
            if (state.selectionTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("TAGS", style = MaterialTheme.typography.labelSmall, color = p.muted)
                Spacer(Modifier.height(6.dp))
                CountedWords(state.selectionTags)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * A field's title with its Add / Replace switch on the same line.
 */
@Composable
private fun ModeHeader(title: String, replace: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, modifier = Modifier.weight(1f))
        Text(
            if (replace) "Replace" else "Add",
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

/**
 * The words typed into a field so far, each removable with a tap.
 */
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
                    .clickable(enabled = enabled) { onRemove(word) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(word, style = MaterialTheme.typography.labelSmall, color = p.accent)
                Spacer(Modifier.size(4.dp))
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = p.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * What the ticked photos already carry, with how many of them carry each word.
 */
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
                "+${Actions.formatCount((values.size - SHOWN_EXISTING).toLong())} more",
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
            )
        }
    }
}

/** How many of the words already on the photos are listed before the rest are summed up. */
private const val SHOWN_EXISTING = 40

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
