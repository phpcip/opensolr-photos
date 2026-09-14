package com.opensolr.photos.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensolr.photos.search.FacetValue
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.search.SearchFilters
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.InfoRow
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.Screen
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette
import android.content.ContentUris
import android.provider.MediaStore

private val Corner = RoundedCornerShape(2.dp)

/**
 * The main screen: search box, filters, and the photo grid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PhotoHit?>(null) }
    val gridState = rememberLazyGridState()

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.hits.size) {
        if (nearEnd && state.hits.isNotEmpty() && !state.endReached && !state.searching) viewModel.search(reset = false)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Photos", style = MaterialTheme.typography.headlineMedium, color = p.ink, modifier = Modifier.weight(1f))
            SyncIndicator(running = state.sync.busy, onClick = { viewModel.open(Screen.Sync) })
            IconButton(onClick = { viewModel.open(Screen.Account) }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Opensolr account", tint = p.ink)
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChange(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search your photos", color = p.muted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = p.muted) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange(""); viewModel.search(reset = true) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = p.muted)
                    }
                }
            },
            singleLine = true,
            shape = Corner,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); viewModel.search(reset = true) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.hairline,
                cursorColor = p.accent,
                focusedTextColor = p.ink,
                unfocusedTextColor = p.ink,
            ),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterButton(count = state.filters.count, onClick = { showFilters = true })
            ActiveFilterChips(state.filters, onRemove = { viewModel.setFilters(it) }, modifier = Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val countText = when {
                state.searching && state.hits.isEmpty() -> "Searching…"
                else -> "${Actions.formatCount(state.numFound)} photo${if (state.numFound == 1L) "" else "s"}" +
                    if (state.smart) " · matched by meaning and words" else ""
            }
            Text(countText, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }

        state.searchNotice?.let { Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        state.searchError?.let { Notice(it, title = "Search did not work", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

        if (!state.searching && state.hits.isEmpty() && state.searchError == null) {
            EmptyResults(state)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(state.hits, key = { _, hit -> hit.id }) { _, hit ->
                Thumbnail(
                    hit = hit,
                    modifier = Modifier.combinedClickable(
                        onClick = { Actions.openPhoto(context, hit) },
                        onLongClick = { details = hit },
                    ),
                )
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            facets = state.facets,
            current = state.filters,
            onApply = { showFilters = false; viewModel.setFilters(it) },
            onDismiss = { showFilters = false },
        )
    }

    details?.let { hit ->
        DetailsSheet(hit = hit, onDismiss = { details = null })
    }
}

/**
 * The discreet sync indicator: a refresh icon that turns while a sync runs. Tapping opens Sync.
 */
@Composable
private fun SyncIndicator(running: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = if (running) "Sync in progress" else "Sync",
            tint = if (running) p.accent else p.ink,
            modifier = if (running) Modifier.rotate(angle) else Modifier,
        )
    }
}

/**
 * The "Filters" button with the number of active filters.
 */
@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(Corner)
            .border(1.dp, if (count > 0) p.accent else p.ink, Corner)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = if (count > 0) p.accent else p.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(if (count > 0) "Filters · $count" else "Filters", style = MaterialTheme.typography.labelLarge, color = if (count > 0) p.accent else p.ink)
    }
}

/**
 * Removable chips for the filters currently applied.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterChips(filters: SearchFilters, onRemove: (SearchFilters) -> Unit, modifier: Modifier = Modifier) {
    val chips = buildList {
        filters.year?.let { add(it to filters.copy(year = null)) }
        filters.folder?.let { add(it.trimEnd('/') to filters.copy(folder = null)) }
        filters.camera?.let { add(it to filters.copy(camera = null)) }
        filters.orientation?.let { add(it.replaceFirstChar { c -> c.uppercase() } to filters.copy(orientation = null)) }
        if (filters.withLocation) add("With location" to filters.copy(withLocation = false))
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, without) ->
            Chip(label = label, selected = true, onClick = { onRemove(without) }, trailingClose = true)
        }
    }
}

/**
 * A flat 2px chip.
 */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, trailingClose: Boolean = false) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(Corner)
            .background(if (selected) p.paper else p.chip)
            .border(1.dp, if (selected) p.accent else p.hairline, Corner)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) p.accent else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailingClose) {
            Spacer(Modifier.size(4.dp))
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = p.accent, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * A square thumbnail loaded from the phone, never from the network.
 */
@Composable
private fun Thumbnail(hit: PhotoHit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val uri = remember(hit.mediaId) { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(uri).size(360).crossfade(true).build(),
        contentDescription = hit.meaning.ifBlank { hit.fileName },
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(1f)
            .background(p.chip),
    )
}

/**
 * What to show when a search has no results.
 */
@Composable
private fun EmptyResults(state: UiState) {
    val p = LocalPalette.current
    val text = when {
        state.sync.busy && state.query.isBlank() -> "Your photos are being indexed. They appear here as they are added."
        state.query.isBlank() && state.filters.count == 0 -> "Nothing is indexed yet. Open Sync to start."
        else -> "No photos match. Try other words or fewer filters."
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

/**
 * Bottom sheet with every filter, built from the facets of the current results.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    facets: Map<String, List<FacetValue>>,
    current: SearchFilters,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var draft by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.paper, shape = Corner) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            Spacer(Modifier.height(16.dp))

            FacetSection("Year", facets["year"], draft.year) { draft = draft.copy(year = it) }
            FacetSection("Folder", facets["folder"], draft.folder, label = { it.trimEnd('/') }) { draft = draft.copy(folder = it) }
            FacetSection("Camera", facets["camera_model"], draft.camera) { draft = draft.copy(camera = it) }
            FacetSection("Orientation", facets["orientation"], draft.orientation, label = { it.replaceFirstChar { c -> c.uppercase() } }) { draft = draft.copy(orientation = it) }

            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Only photos with a location", style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.withLocation,
                    onCheckedChange = { draft = draft.copy(withLocation = it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
                )
            }
            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Clear all", onClick = { draft = SearchFilters() }, modifier = Modifier.weight(1f))
                AccentButton("Show photos", onClick = { onApply(draft) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One filter group: tap a value to select it, tap again to clear it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetSection(
    title: String,
    values: List<FacetValue>?,
    selected: String?,
    label: (String) -> String = { it },
    onSelect: (String?) -> Unit,
) {
    if (values.isNullOrEmpty() && selected == null) return
    val p = LocalPalette.current
    SectionLabel(title)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shown = values.orEmpty().ifEmpty { listOfNotNull(selected?.let { FacetValue(it, 0) }) }
        shown.forEach { facet ->
            val isSelected = facet.value == selected
            Chip(
                label = if (facet.count > 0) "${label(facet.value)} · ${facet.count}" else label(facet.value),
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else facet.value) },
            )
        }
    }
    Spacer(Modifier.height(18.dp))
}

/**
 * Long-press details: the photo, when and with what it was taken, where, and the words it was
 * read into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(hit: PhotoHit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.paper, shape = Corner) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId)
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(p.chip)) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(uri).size(1080).build(),
                    contentDescription = hit.meaning,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(hit.fileName, style = MaterialTheme.typography.titleLarge, color = p.ink)
            Spacer(Modifier.height(12.dp))
            SectionLabel("Details")
            Actions.formatSolrDate(hit.takenAt)?.let { InfoRow("Taken", it) }
            listOfNotNull(hit.cameraMake, hit.cameraModel).joinToString(" ").takeIf { it.isNotBlank() }?.let { InfoRow("Camera", it) }
            hit.lens?.let { InfoRow("Lens", it) }
            val settings = listOfNotNull(
                hit.fNumber?.let { "f/" + String.format(java.util.Locale.US, "%.1f", it) },
                hit.exposure,
                hit.iso?.let { "ISO $it" },
                hit.focalLength?.let { String.format(java.util.Locale.US, "%.0f mm", it) },
            ).joinToString("  ·  ")
            if (settings.isNotBlank()) InfoRow("Settings", settings)
            if (hit.width != null && hit.height != null) InfoRow("Size", "${hit.width} × ${hit.height}")
            InfoRow("Folder", hit.folder.ifBlank { "/" })
            hit.location?.let { InfoRow("Location", it) }
            Spacer(Modifier.height(16.dp))
            SectionLabel("What Opensolr sees")
            Text(hit.meaning.ifBlank { "No words yet" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = p.ink, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(12.dp))
            AccentButton("Open in gallery", onClick = { Actions.openPhoto(context, hit) }, modifier = Modifier.fillMaxWidth())
            hit.location?.let { location ->
                Spacer(Modifier.height(10.dp))
                GhostButton("Show on map", onClick = { Actions.openMap(context, location) }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A plain click without the long-press semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
