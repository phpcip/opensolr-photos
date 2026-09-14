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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensolr.photos.R
import com.opensolr.photos.search.FacetValue
import com.opensolr.photos.search.NearFilter
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.search.SearchFilters
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.InfoRow
import com.opensolr.photos.ui.MapFocus
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
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilters by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PhotoHit?>(null) }
    var editing by remember { mutableStateOf<PhotoHit?>(null) }
    val gridState = rememberLazyGridState()

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    // A fresh search shows its best matches first: back to the top when it starts, and again
    // when its results arrive (a lazy grid otherwise follows the item that used to be on top).
    LaunchedEffect(state.searchGeneration) { gridState.scrollToItem(0) }
    LaunchedEffect(state.resultsGeneration) { gridState.scrollToItem(0) }
    LaunchedEffect(nearEnd, state.hits.size) {
        if (nearEnd && state.hits.isNotEmpty() && !state.endReached && !state.searching) viewModel.search(reset = false)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.selecting) "${state.selectedIds.size} selected" else "Photos",
                style = MaterialTheme.typography.headlineMedium, color = p.ink, modifier = Modifier.weight(1f),
            )
            // Selection: tap photos, then re-sync them (read again by CLIP).
            IconButton(onClick = { viewModel.setSelecting(!state.selecting) }) {
                Icon(
                    if (state.selecting) Icons.Filled.Close else Icons.Filled.CheckCircle,
                    contentDescription = if (state.selecting) "Stop selecting" else "Select photos",
                    tint = if (state.selecting) p.accent else p.ink,
                )
            }
            IconButton(onClick = { viewModel.openMap() }) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = "Map", tint = p.ink)
            }
            SyncIndicator(running = state.sync.busy, onClick = { viewModel.open(Screen.Sync) })
            IconButton(onClick = { viewModel.open(Screen.Account) }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Opensolr account", tint = p.ink)
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    // A hardware Enter is consumed here so that its key-up never reaches the
                    // next focusable control (it used to "click" the Sync button).
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) { keyboard?.hide(); viewModel.search(reset = true) }
                    true
                },
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
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); viewModel.search(reset = true) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.hairline,
                cursorColor = p.accent,
                focusedTextColor = p.ink,
                unfocusedTextColor = p.ink,
            ),
        )

        // Autocomplete: labels containing what was typed, shown under the search box.
        if (state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(p.paper, Corner)
                    .border(1.dp, p.hairline, Corner)
            ) {
                state.suggestions.take(8).forEachIndexed { index, term ->
                    if (index > 0) HorizontalDivider(color = p.hairline)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickableCompat { keyboard?.hide(); viewModel.applySuggestion(term) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(term, style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

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
            Text(countText, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
            // Reloads the results from the index, for photos a sync added in the meantime.
            IconButton(onClick = { viewModel.search(reset = true) }, enabled = !state.searching, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_reload), contentDescription = "Reload", tint = p.accent, modifier = Modifier.size(20.dp))
            }
        }
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }

        // While the index is rebuilt for a newer configuration, search is unavailable.
        if (state.sync.running && state.sync.phase in REBUILD_PHASES) {
            Notice(
                "Your index is being rebuilt for the new version of Opensolr Photos. Search is unavailable until the re-sync is done" +
                    (if (state.sync.total > 0) ": ${Actions.formatCount(state.sync.done.toLong())} of ${Actions.formatCount(state.sync.total.toLong())} photos written." else "."),
                title = "Rebuilding your index",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (state.selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(
                    "Re-sync ${state.selectedIds.size} photo${if (state.selectedIds.size == 1) "" else "s"}",
                    onClick = { viewModel.resyncSelected() },
                    enabled = state.selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "The chosen photos are read again by Opensolr, so what changed in them is reflected in the words they are found by. Each one counts as new AI requests.",
                style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        // "Did you mean": the spellchecker's correction, one tap away.
        state.didYouMean?.takeIf { state.query.isNotBlank() }?.let { corrected ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat { viewModel.applySuggestion(corrected) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text("Did you mean ", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                Text(corrected, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.accent)
                Text("?", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        state.searchNotice?.let { Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // Words-only search, said once per typed search rather than silently.
        if (state.query.isNotBlank() && state.searchNotice == null && state.hits.isNotEmpty() && state.account?.vectorAllowed == false) {
            Notice("Your plan has no photo recognition: results match the date, camera, place, file name and your tags only.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        state.searchError?.let { Notice(it, title = "Search did not work", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

        if (!state.searching && state.hits.isEmpty() && state.searchError == null) {
            EmptyResults(state)
        }

        // Swipe down on the grid reloads the results, like the reload icon.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.searching) { if (!state.searching) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.searching,
            onRefresh = { pulled = true; viewModel.search(reset = true) },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = pulled && state.searching,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = p.paper,
                    color = p.accent,
                )
            },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.hits, key = { _, hit -> hit.id }) { _, hit ->
                    val selected = hit.id in state.selectedIds
                    Box {
                        Thumbnail(
                            hit = hit,
                            modifier = Modifier.combinedClickable(
                                onClick = { if (state.selecting) viewModel.toggleSelected(hit.id) else Actions.openPhoto(context, hit) },
                                onLongClick = { if (state.selecting) viewModel.toggleSelected(hit.id) else details = hit },
                            ),
                        )
                        if (state.selecting) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(22.dp)
                                    .background(if (selected) p.accent else p.paper, Corner)
                                    .border(1.dp, if (selected) p.accent else p.hairline, Corner),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            facets = state.facets,
            current = state.filters,
            onChange = { viewModel.setFilters(it) },
            onDismiss = { showFilters = false },
        )
    }

    details?.let { hit ->
        DetailsSheet(hit = hit, viewModel = viewModel, onDismiss = { details = null }, onEdit = { editing = it; details = null })
    }
    editing?.let { hit ->
        EditSheet(hit = hit, state = state, viewModel = viewModel, onDismiss = { editing = null })
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
            painterResource(R.drawable.ic_sync),
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
        SearchFilters.FACETS.forEach { (field, _) ->
            filters.values(field).forEach { value -> add(facetLabel(field, value) to filters.toggled(field, value)) }
        }
        if (filters.withLocation) add("With location" to filters.copy(withLocation = false))
        filters.near?.let { add(it.label to filters.copy(near = null)) }
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
    onChange: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    // Every tap applies at once: the results and the counts behind the sheet follow along,
    // so there is nothing to scroll down to and confirm.
    val draft = current
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

            SearchFilters.FACETS.forEach { (field, title) ->
                FacetSection(title, facets[field], draft.values(field), label = { facetLabel(field, it) }) { onChange(draft.toggled(field, it)) }
            }

            draft.near?.let { near ->
                // Radius of the "near a point" filter set from the map or a photo's details.
                SectionLabel("Distance from " + String.format(java.util.Locale.US, "%.4f, %.4f", near.lat, near.lon))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val choices = (NearFilter.RADII + near.radiusKm).distinct().sorted()
                    choices.forEach { km ->
                        Chip(
                            label = near.copy(radiusKm = km).label.removePrefix("Within "),
                            selected = km == near.radiusKm,
                            onClick = { onChange(draft.copy(near = near.copy(radiusKm = km))) },
                        )
                    }
                    Chip(label = "Anywhere", selected = false, onClick = { onChange(draft.copy(near = null)) })
                }
                Spacer(Modifier.height(18.dp))
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Only photos with a location", style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.withLocation,
                    onCheckedChange = { onChange(draft.copy(withLocation = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
                )
            }
            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Clear all", onClick = { onChange(SearchFilters()) }, modifier = Modifier.weight(1f))
                AccentButton("Done", onClick = onDismiss, modifier = Modifier.weight(1f))
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
    selected: Set<String>,
    label: (String) -> String = { it },
    onToggle: (String) -> Unit,
) {
    if (values.isNullOrEmpty() && selected.isEmpty()) return
    val p = LocalPalette.current
    // Long lists (the CLIP words) start with the most frequent values; "Show all" opens the rest.
    var expanded by remember(title) { mutableStateOf(false) }
    val all = values.orEmpty().ifEmpty { selected.map { FacetValue(it, 0) } }
    val shown = if (expanded || all.size <= FACET_PREVIEW) all else all.take(FACET_PREVIEW) + all.drop(FACET_PREVIEW).filter { it.value in selected }
    SectionLabel(title)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { facet ->
            Chip(
                label = if (facet.count > 0) "${label(facet.value)} · ${facet.count}" else label(facet.value),
                selected = facet.value in selected,
                onClick = { onToggle(facet.value) },
            )
        }
        if (all.size > FACET_PREVIEW) {
            Chip(label = if (expanded) "Show fewer" else "Show all · ${all.size}", selected = false, onClick = { expanded = !expanded })
        }
    }
    Spacer(Modifier.height(18.dp))
}

/**
 * How a facet value reads on a chip: folders without the trailing slash, orientations capitalised.
 */
private fun facetLabel(field: String, value: String): String = when (field) {
    "folder" -> value.trimEnd('/')
    "orientation" -> value.replaceFirstChar { it.uppercase() }
    else -> value
}

/**
 * Long-press details: the photo, when and with what it was taken, where, and the words it was
 * read into.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailsSheet(hit: PhotoHit, viewModel: AppViewModel, onDismiss: () -> Unit, onEdit: (PhotoHit) -> Unit) {
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
            // City and country in words; the raw coordinates only until the place is known.
            val place = listOfNotNull(hit.city, hit.country).distinct().joinToString(", ")
            if (place.isNotBlank()) InfoRow("Location", place)
            else hit.latLon?.let { (lat, lon) -> InfoRow("Location", String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)) }
            Spacer(Modifier.height(16.dp))
            if (hit.customTags.isNotEmpty()) {
                SectionLabel("My tags")
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hit.customTags.forEach { Chip(label = it, selected = true, onClick = { onEdit(hit) }) }
                }
                Spacer(Modifier.height(16.dp))
            }
            SectionLabel("What the photo shows")
            Text(hit.meaning.ifBlank { "No words yet" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = p.ink, modifier = Modifier.padding(vertical = 12.dp))
            GhostButton("Edit tags and words", onClick = { onEdit(hit) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            AccentButton("Open in gallery", onClick = { Actions.openPhoto(context, hit) }, modifier = Modifier.fillMaxWidth())
            hit.latLon?.let { (lat, lon) ->
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Show on map", onClick = { onDismiss(); viewModel.openMap(MapFocus(lat, lon, 15.0)) }, modifier = Modifier.weight(1f))
                    GhostButton("Photos nearby", onClick = { onDismiss(); viewModel.searchNear(lat, lon, 5.0) }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Facet values shown before "Show all". */
private const val FACET_PREVIEW = 12

/** Sync phases during which the index is being rebuilt and search is unavailable. */
private val REBUILD_PHASES = setOf("Reading your index", "Updating the index configuration", "Rebuilding your index")

/**
 * A plain click without the long-press semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
