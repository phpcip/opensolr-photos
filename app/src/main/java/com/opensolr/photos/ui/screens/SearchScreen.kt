package com.opensolr.photos.ui.screens

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.roundToInt
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
    // Two ways to cut the grid, both from what is already on screen. Browsing, the results come
    // back newest first, so the cut is the day or the month: "Today", "Yesterday", "September".
    // On a typed search the order is the score - Fresh only boosts it - so the cut is the biggest
    // drop in score, where the vector's near misses begin.
    // Grouped by the search that produced the hits, not by the text being typed: typing alone
    // never regroups the grid; Enter (a new search) does.
    val rows = remember(state.hits, state.searchedQuery, state.duplicateGroups) {
        buildRows(state.hits, byDate = state.searchedQuery.isBlank(), groups = state.duplicateGroups)
    }
    // The search box is out of the way until asked for: the magnifier in the header opens it.
    // Active filters keep it on screen, so the filters button next to it stays reachable.
    var searchOpen by remember { mutableStateOf(false) }
    // Only the magnifier shows and hides the search line (Cip, 2026-09-15): applied filters
    // stay visible on their own row of pills, so they no longer hold the line open.
    val searchBarVisible = searchOpen
    val searchFocus = remember { FocusRequester() }
    // Deleting is Android's job: from Android 11 the system shows its own confirmation and does
    // the removing, and only when it comes back OK are the photos dropped from the index too.
    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.removeDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    val deleteSelected = {
        val chosen = state.hits.filter { it.id in state.selectedIds }
        val sender = Actions.deleteRequest(context, Actions.contentUris(context, chosen))
        pendingDelete = chosen.map { it.id }.toSet()
        if (sender != null) {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            // Below Android 11 nothing asks on the app's behalf, so the app asked first.
            viewModel.removeDeleted(pendingDelete)
            pendingDelete = emptySet()
        }
    }

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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // The header is the menu and only the menu (Cip, 2026-09-15): the app's logo first (the
        // Opensolr dashboard in the default browser), then every screen and action, each a small
        // bordered button with its label. No title; "15 selected" lives over the photos.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Order, left to right (Cip, 2026-09-16): Opensolr, Me, Sync, Map, Albums, Select, Search.
            HeaderItem("Opensolr", onClick = { Actions.openUrl(context, OPENSOLR_DASHBOARD_URL) }) {
                // The launcher icon drawn small: its artwork sits in the middle two thirds of
                // the adaptive canvas, so the canvas is drawn larger than the clipped square.
                Box(Modifier.size(20.dp).clip(Corner).background(p.accentFill), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Opensolr dashboard", tint = p.onAccentFill, modifier = Modifier.requiredSize(32.dp))
                }
            }
            HeaderItem("Me", onClick = { viewModel.open(Screen.Account) }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Opensolr account", tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem("Sync", active = state.sync.busy, onClick = { viewModel.open(Screen.Sync) }) {
                SyncIcon(running = state.sync.busy)
            }
            HeaderItem("Map", onClick = { viewModel.openMap() }) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = "Map", tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem("Albums", onClick = { viewModel.openAlbums() }) {
                Icon(painterResource(R.drawable.ic_albums), contentDescription = "Albums", tint = p.ink, modifier = Modifier.size(20.dp))
            }
            // Selection: tap photos, then share, delete or re-sync them.
            HeaderItem(if (state.selecting) "Done" else "Select", active = state.selecting, onClick = { viewModel.setSelecting(!state.selecting) }) {
                Icon(
                    if (state.selecting) Icons.Filled.Close else Icons.Filled.CheckCircle,
                    contentDescription = if (state.selecting) "Stop selecting" else "Select photos",
                    tint = if (state.selecting) p.accent else p.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Search: tapping again puts the line away and clears the query. In the accent while
            // open, and while closed with filters applied: something is narrowing the photos.
            HeaderItem("Search", active = searchOpen || state.filters.count > 0, onClick = {
                if (searchOpen) {
                    searchOpen = false
                    keyboard?.hide()
                    if (state.query.isNotBlank()) { viewModel.onQueryChange(""); viewModel.search(reset = true) }
                } else {
                    searchOpen = true
                }
            }) {
                Icon(Icons.Filled.Search, contentDescription = if (searchOpen) "Close search" else "Search", tint = if (searchOpen || state.filters.count > 0) p.accent else p.ink, modifier = Modifier.size(20.dp))
            }
        }

        // One compact line: the query on the left, the filters button on the right, like a
        // search widget. Opened from the header, it takes the keyboard straight away.
        if (searchBarVisible) {
            LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(36.dp)
                    .clip(Corner)
                    .background(p.paper)
                    .border(1.dp, p.hairline, Corner)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = state.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus)
                        .onPreviewKeyEvent { event ->
                            // A hardware Enter is consumed here so that its key-up never reaches the
                            // next focusable control (it used to "click" the Sync button).
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyUp) { keyboard?.hide(); viewModel.search(reset = true) }
                            true
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); viewModel.search(reset = true) }),
                    decorationBox = { field ->
                        if (state.query.isEmpty()) {
                            Text("Search your photos", style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 1)
                        }
                        field()
                    },
                )
                if (state.query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Clear, contentDescription = "Clear", tint = p.muted,
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickableCompat { viewModel.onQueryChange(""); viewModel.search(reset = true) }
                            .padding(6.dp),
                    )
                }
                // Filters sit on the same line, as a divider plus an icon rather than a button.
                Box(Modifier.size(width = 1.dp, height = 20.dp).background(p.hairline))
                // Slightly bigger and tappable over the line's full height (Cip, 2026-09-15).
                Row(
                    Modifier
                        .fillMaxHeight()
                        .combinedClickableCompat { showFilters = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List, contentDescription = "Filters",
                        tint = if (state.filters.count > 0) p.accent else p.muted, modifier = Modifier.size(22.dp),
                    )
                    if (state.filters.count > 0) {
                        Spacer(Modifier.size(4.dp))
                        Text("${state.filters.count}", style = MaterialTheme.typography.labelSmall, color = p.accent)
                    }
                }
            }
        }

        // Autocomplete: labels containing what was typed, shown under the search box.
        if (searchBarVisible && state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
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

        // What is filtered right now, removable; the button itself moved into the search line.
        if (state.filters.count > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActiveFilterChips(state.filters, onRemove = { viewModel.setFilters(it) }, modifier = Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // While selecting, this line says how many are picked: the header stays the menu.
            val countText = when {
                state.selecting -> "${Actions.formatCount(state.selectedIds.size.toLong())} selected"
                state.searching && state.hits.isEmpty() -> "Searching…"
                // Anchored to one photo: one group, so say what it is like instead of counting groups.
                state.similarToId != null ->
                    "${Actions.formatCount(state.hits.size.toLong())} like ${state.similarToHit?.fileName ?: "this photo"}"
                state.duplicatesMode ->
                    "${Actions.formatCount(state.hits.size.toLong())} photos in ${state.duplicateGroups.size} group${if (state.duplicateGroups.size == 1) "" else "s"}"
                else -> "${Actions.formatCount(state.numFound)} photo${if (state.numFound == 1L) "" else "s"}"
            }
            Text(countText, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
            // Photos of the same thing, grouped. On when it is what the grid is showing.
            IconButton(onClick = { viewModel.showDuplicates() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.ic_duplicates),
                    contentDescription = if (!state.duplicatesMode) "Photos of the same thing" else "Back to all photos",
                    tint = if (state.duplicatesMode) p.accent else p.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Fresh: recent photos are boosted among the matches, nothing is dropped or resorted.
            if (state.query.isNotBlank()) {
                IconButton(onClick = { viewModel.setFreshBias(!state.freshBias) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_newest),
                        contentDescription = if (state.freshBias) "Stop favouring recent photos" else "Favour recent photos",
                        tint = if (state.freshBias) p.accent else p.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Reloads the results from the index, for photos a sync added in the meantime.
            // Reload keeps the view: duplicates stay duplicates, on the same slider stop. Pressed
            // on purpose, so held answers go and the index itself is asked.
            IconButton(onClick = { viewModel.forceRefresh() }, enabled = !state.searching, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_reload), contentDescription = "Reload", tint = p.accent, modifier = Modifier.size(20.dp))
            }
        }
        // The kind of duplicates, 0..10 (Cip, 2026-09-15): from the loosest (the same first word)
        // through the same photo by its EXIF (green, the middle) to the strictest (EXIF and the
        // first five words, red). Every stop is one facet request, asked a moment after the move.
        // Anchored to one photo: the way back to the details it was started from. The sheet
        // opens over the grid, so closing it leaves the similar photos exactly as they were.
        if (state.similarToId != null) {
            val anchorHit = state.similarToHit
            TextButton(
                onClick = { anchorHit?.let { details = it } },
                enabled = anchorHit != null,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.padding(horizontal = 20.dp).height(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Back to ${anchorHit?.fileName ?: "the photo"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.duplicatesMode) {
            DuplicateLevelSlider(
                level = state.duplicateLevel,
                onLevel = { viewModel.setDuplicateLevel(it) },
                // "One of each" belongs to the whole index; anchored to one photo there is a
                // single group and nothing to thin out.
                canSelect = state.duplicateGroups.isNotEmpty() && state.similarToId == null,
                onSelectOneOfEach = { viewModel.selectOneOfEachDuplicate() },
            )
        }
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }

        // What the photos on screen have in common, one tap away from being a filter. Browsing,
        // these are the index's own counts; on a typed search they come from the words-only
        // request, so nothing the vector dragged in can suggest a filter.
        if (!state.selecting) {
            SuggestedFacets(state, onPick = { field, value -> viewModel.setFilters(state.filters.toggled(field, value)) })
        }

        // What the photos on screen have in common, straight from the facets the same /select
        // already returned: one tap narrows to it. Nothing here costs an extra request.

        // A newer release on GitHub: a link to its page, the install is the user's and Android's.
        state.update?.let { update ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Notice(
                    update.notes.ifBlank { "Download it from the releases page; it installs over this one and keeps everything." },
                    title = "Version ${update.version} is available",
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentButton("Download", onClick = { Actions.openUrl(context, update.pageUrl) }, modifier = Modifier.weight(1f))
                    GhostButton("Not now", onClick = { viewModel.dismissUpdate() }, modifier = Modifier.weight(1f))
                }
            }
        }
        // While the index is rebuilt for a newer configuration: search keeps working and finds
        // the photos as they are written back (soft commit every 10 s).
        if (state.sync.running && state.sync.phase in REBUILD_PHASES) {
            Notice(
                "Your photos are being added back; search finds them as they arrive" +
                    (if (state.sync.total > 0) ": ${Actions.formatCount(state.sync.done.toLong())} of ${Actions.formatCount(state.sync.total.toLong())} photos written." else "."),
                title = "Rebuilding your index",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
            Notice("Words only: your plan has no photo recognition.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        state.searchError?.let { Notice(it, title = "Search did not work", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

        if (!state.searching && state.hits.isEmpty() && state.searchError == null) {
            EmptyResults(state)
        }

        // Swipe down on the grid reloads the results, like the reload icon. Asked for by hand,
        // so it goes to the index and drops what the phone was holding.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.searching) { if (!state.searching) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.searching,
            onRefresh = { pulled = true; viewModel.forceRefresh() },
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
                // Room under the last row for the dock, so it never covers a photo.
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = if (state.selecting) 96.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    rows,
                    key = { row -> row.key },
                    span = { row -> if (row is GridRow.Heading) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                ) { row ->
                    when (row) {
                        is GridRow.Heading -> {
                            // Google Photos style: the tick on a heading takes the whole group.
                            // A long press on it starts selecting with that group already ticked.
                            val allPicked = state.selecting && row.ids.isNotEmpty() && state.selectedIds.containsAll(row.ids)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { if (state.selecting) viewModel.toggleSelectedGroup(row.ids) },
                                        onLongClick = { viewModel.toggleSelectedGroup(row.ids) },
                                    )
                                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(row.text, style = MaterialTheme.typography.labelLarge, color = p.ink, modifier = Modifier.weight(1f))
                                if (state.selecting) {
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .background(if (allPicked) p.accentFill else p.paper, Corner)
                                            .border(1.dp, if (allPicked) p.accentFill else p.hairline, Corner),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (allPicked) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccentFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        is GridRow.Photo -> {
                            val hit = row.hit
                            val selected = hit.id in state.selectedIds
                            // The photo "Show similar photos" started from: ringed and named, so
                            // it is never a guess which one the others are being compared with.
                            val anchor = hit.id == state.similarToId
                            Box(if (anchor) Modifier.border(2.dp, p.accent) else Modifier) {
                                Thumbnail(
                                    hit = hit,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { if (state.selecting) viewModel.toggleSelected(hit.id) else Actions.openPhoto(context, hit) },
                                        onLongClick = { if (state.selecting) viewModel.toggleSelected(hit.id) else details = hit },
                                    ),
                                )
                                if (anchor) {
                                    Text(
                                        "This one",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = p.onAccentFill,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(4.dp)
                                            .background(p.accentFill, Corner)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (state.selecting) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(22.dp)
                                            .background(if (selected) p.accentFill else p.paper, Corner)
                                            .border(1.dp, if (selected) p.accentFill else p.hairline, Corner),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccentFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // What to do with the selected photos, floating over the grid instead of pushing it down.
    if (state.selecting) {
        SelectionDock(
            count = state.selectedIds.size,
            modifier = Modifier.align(Alignment.BottomCenter),
            onShare = { Actions.sharePhotos(context, state.hits.filter { it.id in state.selectedIds }) },
            // The app always asks first (Cip, 2026-09-16); from Android 11 the system's own
            // confirmation follows.
            onDelete = { confirmDelete = true },
            onResync = { viewModel.resyncSelected() },
        )
    }
    }

    // The app's own warning before anything is removed, on every Android version.
    if (confirmDelete) {
        val chosen = state.selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $chosen photo${if (chosen == 1) "" else "s"}?") },
            text = { Text("They are removed from this phone and from your index. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; deleteSelected() }) { Text("Delete", color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = p.ink) } },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.muted,
        )
    }

    if (showFilters) {
        FilterSheet(
            facets = state.facets,
            current = state.filters,
            count = state.numFound,
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
 * The pills above the grid: what the photos matching a typed search have in common, one tap
 * from a filter. Only on a typed search (Cip, 2026-09-15): browsing shows no pills at all.
 *
 * They come from [UiState.queryFacets] - a second, words-only request - because the facets of
 * the hybrid query cover every candidate the vector leg brought along, near misses included.
 * When the words match nothing, there is nothing to suggest and the strip stays away.
 */
@Composable
private fun SuggestedFacets(state: UiState, onPick: (String, String) -> Unit) {
    if (state.query.isBlank()) return
    val source = state.queryFacets
    val picks = remember(source, state.filters) {
        val lists = STRIP_FIELDS.map { field ->
            (source[field] ?: emptyList())
                .asSequence()
                // On the words-only facets the count is over that smaller set, where a value
                // shared by all still narrows.
                .filter { it.count >= 2 && it.value !in state.filters.values(field) }
                .sortedByDescending { it.count }
                .take(PER_FIELD)
                .map { field to it }
                .toList()
        }
        // Round robin, so the first pills are the best value of each field rather than three of one.
        (0 until PER_FIELD).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }.take(10)
    }
    if (picks.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(picks, key = { (field, value) -> "$field:${value.value}" }) { (field, value) ->
            // No count: the words-only facets do not count the same photos the grid shows.
            Pill(
                label = facetLabel(field, value.value),
                onClick = { onPick(field, value.value) },
            )
        }
    }
}

/** The fields the pills draw from, in the order one of each is offered. */
private val STRIP_FIELDS = listOf("custom_tags", "labels", "city", "country", "year", "camera_model")
/** How many values one field may put on the strip. */
private const val PER_FIELD = 3

/**
 * A soft round pill: a suggestion in grey, or an applied filter in the accent with a cross that
 * takes it off. Quieter than the square chips of the filter sheet.
 */
@Composable
private fun Pill(label: String, onClick: () -> Unit, accent: Boolean = false, trailingClose: Boolean = false) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(p.chip)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (accent) p.accent else p.muted, maxLines = 1)
        if (trailingClose) {
            Spacer(Modifier.size(5.dp))
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = if (accent) p.accent else p.muted, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * The actions for the selected photos: a small bar of icons floating above the grid, each with
 * a word under it. Nothing is explained here; what the actions do is said where it is decided
 * (the system's own delete confirmation, and the Sync screen for a re-sync).
 */
@Composable
private fun SelectionDock(
    count: Int,
    modifier: Modifier = Modifier,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onResync: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .clip(Corner)
            .background(p.paper)
            .border(1.dp, p.hairline, Corner)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockAction(R.drawable.ic_share, "Share", enabled = count > 0, onClick = onShare)
        DockAction(R.drawable.ic_delete, "Delete", enabled = count > 0, onClick = onDelete)
        DockAction(R.drawable.ic_sync, if (count > 0) "Re-sync $count" else "Re-sync", enabled = count > 0, accent = true, onClick = onResync)
    }
}

/**
 * One icon of the dock with its word, greyed out while nothing is selected.
 */
@Composable
private fun DockAction(icon: Int, label: String, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = when {
        !enabled -> p.hairline
        accent -> p.accent
        else -> p.ink
    }
    Column(
        Modifier
            .clip(Corner)
            .then(if (enabled) Modifier.combinedClickableCompat(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/**
 * What the grid lays out: a full-width date heading, or one photo.
 */
private sealed interface GridRow {
    /** The key the lazy grid keeps items by; headings carry their own text. */
    val key: String

    /** A full-width heading, with the photos of its group so its tick can take them all. */
    data class Heading(val text: String, val ids: List<String>) : GridRow {
        override val key: String get() = "h:$text"
    }

    data class Photo(val hit: PhotoHit) : GridRow {
        override val key: String get() = hit.id
    }
}

/**
 * Turns the results into grid rows with headings over their groups.
 *
 * [byDate] cuts on the day or the month, for results that come back in date order; photos with
 * no date land under the last heading seen, or under none at the top. Otherwise the cut is on
 * the score: the hybrid query returns the strong matches first and the vector's near misses
 * after them, so the biggest fall in score is where "Also similar" begins.
 */
private fun buildRows(hits: List<PhotoHit>, byDate: Boolean, groups: List<Int> = emptyList()): List<GridRow> {
    if (hits.isEmpty()) return emptyList()
    if (groups.isNotEmpty()) {
        // Photos of the same thing: the groups come laid out one after another.
        val rows = ArrayList<GridRow>(hits.size + groups.size)
        var from = 0
        groups.forEachIndexed { index, size ->
            val group = hits.drop(from).take(size)
            if (group.isEmpty()) return@forEachIndexed
            rows += GridRow.Heading("${group.size} of the same · ${index + 1}", group.map { it.id })
            group.forEach { rows += GridRow.Photo(it) }
            from += size
        }
        return rows
    }
    if (!byDate) {
        val cut = scoreCut(hits) ?: return hits.map { GridRow.Photo(it) }
        val rows = ArrayList<GridRow>(hits.size + 2)
        rows += GridRow.Heading("Best matches", hits.take(cut).map { it.id })
        hits.take(cut).forEach { rows += GridRow.Photo(it) }
        rows += GridRow.Heading("Also similar", hits.drop(cut).map { it.id })
        hits.drop(cut).forEach { rows += GridRow.Photo(it) }
        return rows
    }
    val groups = LinkedHashMap<String, MutableList<PhotoHit>>()
    val loose = ArrayList<PhotoHit>()
    hits.forEach { hit ->
        val heading = Actions.solrDateMillis(hit.takenAt)?.let { Actions.dateHeading(it) }
        if (heading == null && groups.isEmpty()) loose += hit
        else if (heading == null) groups.values.last() += hit
        else groups.getOrPut(heading) { ArrayList() } += hit
    }
    val rows = ArrayList<GridRow>(hits.size + groups.size)
    loose.forEach { rows += GridRow.Photo(it) }
    groups.forEach { (heading, photos) ->
        rows += GridRow.Heading(heading, photos.map { it.id })
        photos.forEach { rows += GridRow.Photo(it) }
    }
    return rows
}

/**
 * Where the strong matches end, or null when there is no honest place to cut: too few results,
 * no scores (an index on the older configuration answers without them), or a list that fades
 * evenly. The cut is the biggest fall between one score and the next, and it only counts when
 * what follows scores below [ALSO_SIMILAR_SHARE] of the best hit.
 */
private fun scoreCut(hits: List<PhotoHit>): Int? {
    if (hits.size < MIN_HITS_TO_CUT) return null
    val top = hits.first().score
    if (top <= 0.0) return null
    var cut = -1
    var biggest = 0.0
    for (i in MIN_BEST_MATCHES until hits.size) {
        val fall = hits[i - 1].score - hits[i].score
        if (fall > biggest && hits[i].score < top * ALSO_SIMILAR_SHARE) {
            biggest = fall
            cut = i
        }
    }
    return cut.takeIf { it > 0 }
}

/** Fewer results than this are a short enough list to read without a heading. */
private const val MIN_HITS_TO_CUT = 8
/** "Best matches" is never shorter than this, so a single strong hit does not stand alone. */
private const val MIN_BEST_MATCHES = 3
/** A photo below this share of the best score is what the vector brought along, not a match. */
private const val ALSO_SIMILAR_SHARE = 0.6

/**
 * One button of the header menu: a small bordered cell with its icon over a short label,
 * sharing the row's width equally with the others. [active] draws it in the accent.
 */
@Composable
private fun RowScope.HeaderItem(label: String, active: Boolean = false, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .weight(1f)
            .clip(Corner)
            .border(1.dp, if (active) p.accent else p.hairline, Corner)
            .combinedClickableCompat { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = if (active) p.accent else p.ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * The sync icon of the header: a refresh icon that turns while a sync runs.
 */
@Composable
private fun SyncIcon(running: Boolean) {
    val p = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Icon(
        painterResource(R.drawable.ic_sync),
        contentDescription = if (running) "Sync in progress" else "Sync",
        tint = if (running) p.accent else p.ink,
        modifier = Modifier.size(20.dp).then(if (running) Modifier.rotate(angle) else Modifier),
    )
}

/**
 * The duplicates slider: eleven stops over SearchRepository.DUPLICATE_FIELDS, the name of the
 * kind under it. Its colour tells where it stands: black at the loosest words-only stop,
 * green at the EXIF-only stop in the middle, red at the strictest EXIF-plus-words stop. The
 * thumb follows the finger at once; the level reaches [onLevel] on every stop crossed, and the
 * view model waits for the finger to settle before asking the index.
 */
@Composable
private fun DuplicateLevelSlider(level: Int, onLevel: (Int) -> Unit, canSelect: Boolean, onSelectOneOfEach: () -> Unit) {
    val p = LocalPalette.current
    var value by remember { mutableStateOf(level.toFloat()) }
    LaunchedEffect(level) { if (value.roundToInt() != level) value = level.toFloat() }
    val stop = value.roundToInt().coerceIn(0, DUPLICATE_KIND_NAMES.size - 1)
    // Which set of scale colours reads on the current background: ink is near-black on paper
    // and near-white on a dark screen, so it says which theme is in force without asking.
    val dark = p.ink.red > 0.5f
    val loose = if (dark) DUPLICATE_LOOSE_DARK else DUPLICATE_LOOSE_LIGHT
    val green = if (dark) DUPLICATE_GREEN_DARK else DUPLICATE_GREEN_LIGHT
    val red = if (dark) DUPLICATE_RED_DARK else DUPLICATE_RED_LIGHT
    val colour = when {
        stop <= 5 -> lerp(loose, green, stop / 5f)
        stop <= 10 -> lerp(green, red, (stop - 5) / 5f)
        // File name and size are not on the words / EXIF scale: a neutral colour of their own.
        else -> if (dark) DUPLICATE_NEUTRAL_DARK else DUPLICATE_NEUTRAL_LIGHT
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Slider(
            value = value,
            onValueChange = {
                value = it
                val rounded = it.roundToInt()
                if (rounded != level) onLevel(rounded)
            },
            valueRange = 0f..(DUPLICATE_KIND_NAMES.size - 1).toFloat(),
            steps = DUPLICATE_KIND_NAMES.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = colour,
                activeTrackColor = colour,
                inactiveTrackColor = p.chip,
                activeTickColor = colour,
                inactiveTickColor = p.hairline,
            ),
        )
        Text("$stop · ${DUPLICATE_KIND_NAMES[stop]}", style = MaterialTheme.typography.labelMedium, color = colour)
        // One tap selects one photo of every group (the last of each, the first one stays
        // unticked), for review; the selection dock then shares, deletes or re-syncs them.
        TextButton(
            onClick = onSelectOneOfEach,
            enabled = canSelect,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text("Select 1 of each duplicate", style = MaterialTheme.typography.labelMedium, color = if (canSelect) p.accent else p.muted)
        }
    }
}

/** What each stop of the duplicates slider groups, in the order of DUPLICATE_FIELDS. */
private val DUPLICATE_KIND_NAMES = listOf(
    "Same first word", "Same first 2 words", "Same first 3 words", "Same first 4 words", "Same first 5 words",
    "Same photo (EXIF)",
    "Same photo + first word", "Same photo + first 2 words", "Same photo + first 3 words", "Same photo + first 4 words",
    "Same photo + first 5 words",
    "Same file name", "Same file size",
)
/**
 * The ends of the duplicates scale, one set per theme (Cip, 2026-09-16). On paper the loosest
 * stop is near-black; on a dark screen that is the colour of the screen itself, so the thumb,
 * the track, the ticks and the name under them all disappeared. The dark set turns that end
 * light and lifts the others off the background as well.
 */
private val DUPLICATE_LOOSE_LIGHT = Color(0xFF111111)
private val DUPLICATE_NEUTRAL_LIGHT = Color(0xFF495057)
private val DUPLICATE_GREEN_LIGHT = Color(0xFF2F9E44)
private val DUPLICATE_RED_LIGHT = Color(0xFFE03131)

private val DUPLICATE_LOOSE_DARK = Color(0xFFF4F1EC)
private val DUPLICATE_NEUTRAL_DARK = Color(0xFFADB5BD)
private val DUPLICATE_GREEN_DARK = Color(0xFF51CF66)
private val DUPLICATE_RED_DARK = Color(0xFFFF6B6B)

/** Where the logo of the header leads: the Opensolr dashboard, in the default browser. */
private const val OPENSOLR_DASHBOARD_URL = "https://opensolr.com/admin/solr_manager"

/**
 * Removable chips for the filters currently applied, on ONE row that scrolls sideways, like the
 * suggestion pills of a typed search (Cip, 2026-09-15): many filters never push the grid down.
 */
@Composable
private fun ActiveFilterChips(filters: SearchFilters, onRemove: (SearchFilters) -> Unit, modifier: Modifier = Modifier) {
    val chips = buildList {
        SearchFilters.FACETS.forEach { (field, _) ->
            filters.values(field).forEach { value -> add(facetLabel(field, value) to filters.toggled(field, value)) }
        }
        if (filters.withLocation) add("With location" to filters.copy(withLocation = false))
        filters.near?.let { add(it.label to filters.copy(near = null)) }
    }
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // No key: the same label can be applied in two fields (a city and a word), and a
        // repeated key would crash the row.
        items(chips) { (label, without) ->
            // Same round pills as the suggestions above the grid, in the accent, with a cross.
            Pill(label = label, accent = true, trailingClose = true, onClick = { onRemove(without) })
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
    // Duplicates say it themselves ("No duplicates of this kind"): nothing else to add there.
    if (state.duplicatesMode) return
    val p = LocalPalette.current
    val text = when {
        state.sync.busy && state.query.isBlank() -> "Your photos are being indexed. They appear here as they are added."
        state.query.isBlank() && state.filters.count == 0 -> "Nothing is indexed yet. Open Sync to start."
        else -> "No photos match. Try other words or fewer filters."
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

/**
 * The filter sheet's two actions, identical at its top and bottom: small and discreet, each
 * with its icon. Done carries [count], the photos shown with the filters as they are now
 * (every tap applies at once, so the results behind the sheet already are that set).
 */
@Composable
private fun FilterActions(count: Long, onClear: () -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.height(34.dp),
            shape = Corner,
            border = BorderStroke(1.dp, p.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Clear all", style = MaterialTheme.typography.labelMedium)
        }
        Button(
            onClick = onDone,
            modifier = Modifier.height(34.dp),
            shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = p.accentFill, contentColor = p.onAccentFill),
            contentPadding = PaddingValues(horizontal = 12.dp),
            elevation = null,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Done (${Actions.formatCount(count)})", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Bottom sheet with every filter, built from the facets of the current results. [count] is
 * the number of photos the current filters show.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    facets: Map<String, List<FacetValue>>,
    current: SearchFilters,
    count: Long,
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
            // The same two actions at the top as at the bottom, small, so a long list of values
            // never has to be scrolled through to clear or close (Cip, 2026-09-15).
            Spacer(Modifier.height(8.dp))
            FilterActions(count, onClear = { onChange(SearchFilters()) }, onDone = onDismiss)
            Spacer(Modifier.height(12.dp))

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
                    colors = SwitchDefaults.colors(checkedTrackColor = p.accentFill, checkedThumbColor = p.onAccentFill, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
                )
            }
            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(12.dp))
            FilterActions(count, onClear = { onChange(SearchFilters()) }, onDone = onDismiss)
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
            // Everything this photo can do, as one row of small bordered icons with their words,
            // the same shape as the header of the photos screen (Cip, 2026-09-16): there are too
            // many of them now for full-width buttons.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderItem("Edit", onClick = { onEdit(hit) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit tags and words", tint = p.ink, modifier = Modifier.size(20.dp))
                }
                HeaderItem("Gallery", onClick = { Actions.openPhoto(context, hit) }) {
                    Icon(painterResource(R.drawable.ic_open), contentDescription = "Open in gallery", tint = p.ink, modifier = Modifier.size(20.dp))
                }
                HeaderItem("Similar", onClick = { onDismiss(); viewModel.showSimilar(hit) }) {
                    Icon(painterResource(R.drawable.ic_duplicates), contentDescription = "Show similar photos", tint = p.ink, modifier = Modifier.size(20.dp))
                }
                hit.latLon?.let { (lat, lon) ->
                    HeaderItem("Map", onClick = { onDismiss(); viewModel.openMap(MapFocus(lat, lon, 15.0)) }) {
                        Icon(painterResource(R.drawable.ic_map), contentDescription = "Show on map", tint = p.ink, modifier = Modifier.size(20.dp))
                    }
                    HeaderItem("Nearby", onClick = { onDismiss(); viewModel.searchNear(lat, lon, 5.0) }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "Photos nearby", tint = p.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Facet values shown before "Show all". */
private const val FACET_PREVIEW = 12

/** Sync phases during which the index is being rebuilt and search is unavailable. */
private val REBUILD_PHASES = setOf("Resetting your index", "Updating the index configuration", "Rebuilding your index")

/**
 * A plain click without the long-press semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
