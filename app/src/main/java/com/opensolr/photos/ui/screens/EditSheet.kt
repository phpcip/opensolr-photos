package com.opensolr.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

/**
 * Editing what a photo is found by: the owner's tags, one per entry, and the words that
 * describe what the photo shows. The owner's words always win over what Opensolr saw, and
 * "Reset" brings Opensolr's words back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(hit: PhotoHit, state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val clipWords = remember(hit.id) { hit.labels.filter { it.isNotBlank() }.joinToString(", ") }
    var tags by remember(hit.id) { mutableStateOf(hit.customTags) }
    var newTag by remember(hit.id) { mutableStateOf("") }
    var meaning by remember(hit.id) { mutableStateOf(hit.meaning) }

    // Adds what was typed as one or more tags (commas split), ignoring blanks and repeats.
    fun addTag() {
        val parts = newTag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        tags = (tags + parts).distinctBy { it.lowercase() }
        newTag = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.paper, shape = Corner) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Text("Edit ${hit.fileName}", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            Spacer(Modifier.height(16.dp))

            SectionLabel("My tags")
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
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = p.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a tag, e.g. Maria, holiday 2021", color = p.muted) },
                    singleLine = true,
                    shape = Corner,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                )
                TextButton(onClick = { addTag() }, enabled = newTag.isNotBlank()) { Text("Add", color = p.accent) }
            }
            Text("Names, places, events, anything you would search for. Your tags count more than the words Opensolr saw.", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))

            SectionLabel("What the photo shows")
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
                Text("Words separated by commas. Your wording is kept even when the photo is read again.", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f).padding(top = 6.dp, end = 8.dp))
                TextButton(onClick = { meaning = clipWords }, enabled = clipWords.isNotBlank() && meaning != clipWords) { Text("Reset", color = p.accent) }
            }
            state.editError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, title = "Not saved")
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !state.editSaving)
                AccentButton(
                    if (state.editSaving) "Saving…" else "Save",
                    onClick = {
                        addTag()
                        // The wording is an edit only when it differs from what Opensolr saw.
                        val wording = meaning.trim().takeIf { it.isNotEmpty() && it != clipWords }
                        viewModel.saveEdits(hit, tags, wording, onDone = onDismiss)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.editSaving,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
