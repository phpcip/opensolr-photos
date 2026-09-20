package com.opensolr.photos.ui.screens

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.unit.Dp
import android.app.Activity
import android.os.Build
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.opensolr.photos.ui.Haptics
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.opensolr.photos.search.DateRange
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntOffset

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
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
    var showFilters by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PhotoHit?>(null) }
    // The photo opened full screen, from which the results are swiped through in their own order.
    var viewing by remember { mutableStateOf<PhotoHit?>(null) }
    val view = LocalView.current
    // New photos were given the phone's position by the sync, which runs where Android lets no app
    // write a file: the question is asked here, once, the next time the photos are on screen
    // (Cip, 2026-09-19). A no is not asked again this session; Me keeps the button.
    val writePlaces = rememberPlaceWriter(viewModel)
    // Counted again whenever the photos come back on screen: the sync that gave the places may have
    // run while the app was away.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshPlacesToWrite()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.placesToWrite, state.placesDeclined) {
        if (state.placesToWrite > 0 && !state.placesDeclined) writePlaces()
    }
    // Read here, on the screen, where the system bars are reported correctly; the viewer runs in
    // a dialog window, where some phones report nothing at all.
    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val topInset = systemBars.calculateTopPadding()
    val bottomInset = systemBars.calculateBottomPadding()
    var editing by remember { mutableStateOf<PhotoHit?>(null) }
    // Open while tags are being put on every photo of the view at once.
    var bulkTagging by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Two ways to cut the grid, both from what is already on screen. Browsing, the results come
    // back newest first, so the cut is the day or the month: "Today", "Yesterday", "September".
    // On a typed search the order is the score - Fresh only boosts it - so the cut is the biggest
    // drop in score, where the vector's near misses begin.
    // Grouped by the search that produced the hits, not by the text being typed: typing alone
    // never regroups the grid; Enter (a new search) does.
    // The library's skeleton belongs to plain browsing only: a duplicates stop or the skipped list
    // with nothing in it showed the whole library under its "nothing here" line (Cip, 2026-09-19).
    // The headings the grid writes itself, in the app's language; their keys stay in English.
    val gridWords = GridWords(stringResource(R.string.best_matches), stringResource(R.string.also_similar), stringResource(R.string.of_the_same))
    // Read out by the accessibility services for the action that starts picking photos.
    val selectLabel = stringResource(R.string.cd_select_photo)
    val rows = remember(state.hits, state.searchedQuery, state.duplicateGroups, state.collapsedHeadings, state.skeleton, state.duplicatesMode, state.skippedMode, state.resultGroups) {
        if (state.resultGroups.isNotEmpty() && (!state.duplicatesMode || state.similarToId != null) && !state.skippedMode) {
            buildResultGroupRows(state.resultGroups, state.hits, state.collapsedHeadings)
        } else if (state.skeleton.isNotEmpty() && state.searchedQuery.isBlank() && state.duplicateGroups.isEmpty() && !state.duplicatesMode && !state.skippedMode) {
            buildSkeletonRows(state.skeleton, state.hits, state.collapsedHeadings)
        } else buildRows(state.hits, byDate = state.searchedQuery.isBlank(), groups = state.duplicateGroups, collapsed = state.collapsedHeadings, words = gridWords)
    }
    // The search box is out of the way until asked for: the magnifier in the header opens it.
    // Active filters keep it on screen, so the filters button next to it stays reachable.
    var searchOpen by remember { mutableStateOf(false) }
    // Only the magnifier shows and hides the search line (Cip, 2026-09-15): applied filters
    // stay visible on their own row of pills, so they no longer hold the line open.
    // A query that is actually in force keeps it on screen too (Cip, 2026-09-16): coming back
    // from albums, duplicates or the similar view, the words the results answer to must be
    // visible, not only remembered. Closing with the magnifier clears the query, so the line
    // still goes away on the second tap.
    val searchBarVisible = searchOpen || state.query.isNotBlank()
    val searchFocus = remember { FocusRequester() }
    // The autocomplete closes on any tap outside the search line and its list, and comes back
    // with the next keystroke.
    var suggestionsHidden by remember { mutableStateOf(false) }
    LaunchedEffect(state.query) { suggestionsHidden = false }
    val outside = com.opensolr.photos.ui.rememberOutsideTap(onOutside = { suggestionsHidden = true })
    // Deleting is Android's job: from Android 11 the system shows its own confirmation and does
    // the removing, and only when it comes back OK are the photos dropped from the index too.
    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.removeDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    val deleteScope = rememberCoroutineScope()
    val deleteSelected = {
        val chosen = viewModel.photosToTag()
        pendingDelete = chosen.map { it.id }.toSet()
        // Finding the files of a whole selection asks the phone's media store about every one of
        // them, so it is done off the screen's thread (Cip, 2026-09-18).
        deleteScope.launch {
            val sender = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Actions.deleteRequest(context, Actions.contentUris(context, chosen))
            }
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                // Below Android 11 nothing asks on the app's behalf, so the app asked first.
                viewModel.removeDeleted(pendingDelete)
                pendingDelete = emptySet()
            }
        }
        Unit
    }

    val nearEnd by remember(rows) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Halfway through the last page loaded, not at its very end: the next page is on its
            // way (or already held in the cache) before the owner reaches it (Cip, 2026-09-17).
            // Only while the last group is open: a folded group at the bottom keeps the list
            // short whatever arrives, so every page loaded would leave it "near the end" again
            // and the pages were fetched one after another to the end of the results, a tap
            // for each (Cip, 2026-09-17). Opening that group resumes the loading.
            rows.lastOrNull() is GridRow.Photo && last >= gridState.layoutInfo.totalItemsCount - PREFETCH_REMAINING
        }
    }
    // The grid is taken where the view model says, once per change it announces: the place this
    // exact search was last left at, or the top for a search never seen before. A new search, an
    // album, duplicates and the similar photos each have their own place, so leaving one and
    // coming back lands where it was, not at the top (Cip, 2026-09-16).
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.restoreGeneration) {
        if (rows.isEmpty()) return@LaunchedEffect
        // By the row that was at the top, not by its number: deleting photos or folding a group
        // makes the list shorter, and a remembered number then points somewhere else - which is
        // how a delete used to land the grid in a random place (Cip, 2026-09-16). The number is
        // only the fallback, for when that row is gone from the list altogether.
        val byKey = state.gridKey?.let { key -> rows.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        // The row is gone and the number points past the end of a shorter list: that is not "where
        // the owner was", it is the bottom of something else, so the grid goes to the top instead
        // (Cip, 2026-09-18).
        val target = byKey ?: state.gridIndex.takeIf { it <= rows.lastIndex } ?: 0
        gridState.scrollToItem(target.coerceAtLeast(0), if (byKey != null) state.gridOffset else 0)
        restored = true
    }
    // Written back only after the grid has been put where it belongs, so the restore is never
    // overwritten by the 0 of a grid that has not been placed yet.
    // The key is read from the rows on screen now, not from the list this effect started with:
    // it outlives every reload, and the old list's row at the same number is another photo, so
    // the grid was put back on the wrong one after each sync (Cip, 2026-09-18).
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> viewModel.rememberGridPosition(currentRows.getOrNull(index)?.key, index, offset) }
    }
    LaunchedEffect(nearEnd, state.hits.size) {
        if (nearEnd && state.hits.isNotEmpty() && !state.endReached && !state.searching) {
            viewModel.search(reset = false)
        }
    }
    // With the last group folded, the prefetch above stays off (else every page arriving left
    // the short list at its end again and the pages chained). The owner still gets more by
    // scrolling against the bottom: one page per scroll that reaches it, never on its own
    // (Cip, 2026-09-17).
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress && !gridState.canScrollForward }
            .collect { pushedAtBottom ->
                val now = latestState
                if (pushedAtBottom && now.hits.isNotEmpty() && !now.endReached && !now.searching) {
                    viewModel.search(reset = false)
                }
            }
    }

    Box(with(outside) { Modifier.fillMaxSize().root() }) {
    Column(Modifier.fillMaxSize()) {
        // The header is the menu and only the menu (Cip, 2026-09-15): the app's logo first (the
        // Opensolr dashboard in the default browser), then every screen and action, each a small
        // bordered button with its label. No title; "15 selected" lives over the photos.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Order, left to right (Cip, 2026-09-16): Me, Sync, Map, Albums, Select, Search.
            HeaderItem(stringResource(R.string.nav_me), onClick = { viewModel.open(Screen.Account) }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.cd_account), tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.nav_sync), active = state.sync.busy, onClick = { viewModel.open(Screen.Sync) }) {
                SyncIcon(running = state.sync.busy)
            }
            HeaderItem(stringResource(R.string.nav_map), onClick = { viewModel.openMap() }) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = stringResource(R.string.nav_map), tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.nav_albums), onClick = { viewModel.openAlbums() }) {
                Icon(painterResource(R.drawable.ic_albums), contentDescription = stringResource(R.string.nav_albums), tint = p.ink, modifier = Modifier.size(20.dp))
            }
            // Nothing here for picking at all: a long press starts it and unticking the last photo
            // ends it (Cip, 2026-09-18).
            // Search: tapping again puts the line away and clears the query. In the accent while
            // open, and while closed with filters applied: something is narrowing the photos.
            HeaderItem(stringResource(R.string.nav_search), active = searchOpen || state.filters.count > 0, onClick = {
                if (searchOpen) {
                    searchOpen = false
                    keyboard?.hide()
                    if (state.query.isNotBlank()) { viewModel.onQueryChange(""); viewModel.search(reset = true) }
                } else {
                    searchOpen = true
                }
            }) {
                Icon(Icons.Filled.Search, contentDescription = if (searchOpen) stringResource(R.string.cd_close_search) else stringResource(R.string.nav_search), tint = if (searchOpen || state.filters.count > 0) p.accent else p.ink, modifier = Modifier.size(20.dp))
            }
        }

        // One compact line: the query on the left, the filters button on the right, like a
        // search widget. Opened from the header, it takes the keyboard straight away.
        if (searchBarVisible) {
            LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
            Row(
                with(outside) { Modifier.keep("search") }
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
                            Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 1)
                        }
                        field()
                    },
                )
                if (state.query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear), tint = p.muted,
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickableCompat { viewModel.onQueryChange(""); viewModel.search(reset = true) }
                            .padding(6.dp),
                    )
                }
            }
        }

        // Autocomplete: labels containing what was typed, shown under the search box.
        if (searchBarVisible && !suggestionsHidden && state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
            Column(
                with(outside) { Modifier.keep("suggestions") }
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

        // What is filtered right now, removable; the button itself is on the buttons line.
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
                // Compact like the result count, with a tick before it instead of the word (Cip,
                // 2026-09-17): "34 selected" did not fit beside the buttons.
                state.selecting -> Actions.formatCompact(state.selectedIds.size.toLong())
                state.searching && state.hits.isEmpty() -> stringResource(R.string.count_searching)
                // Anchored to one photo: one group, so say what it is like instead of counting groups.
                state.skippedMode ->
                    pluralStringResource(R.plurals.unreadable_count, state.hits.size, Actions.formatCount(state.hits.size.toLong()))
                state.duplicatesMode && state.similarToId != null ->
                    stringResource(R.string.similar_count, Actions.formatCompact(state.hits.size.toLong()))
                state.duplicatesMode ->
                    // Only the photos: how many groups they fall into interests nobody (Cip, 2026-09-17).
                    pluralStringResource(R.plurals.photos_count, state.hits.size, Actions.formatCount(state.hits.size.toLong()))
                // Compact, so it always fits beside the buttons and nothing moves (Cip, 2026-09-17).
                else -> Actions.formatCompact(state.numFound)
            }
            // While selecting, a tap on the tick and the count ends the selection: every tick goes
            // and the checkboxes with it (Cip, 2026-09-18). Outside a selection it is only a count.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = if (state.selecting) Modifier.combinedClickable(onClick = {
                        Haptics.tick(view, strong = false)
                        viewModel.setSelecting(false)
                    }).padding(vertical = 6.dp, horizontal = 2.dp) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selecting) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_end_selection), tint = p.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(countText, style = MaterialTheme.typography.bodySmall, color = if (state.selecting) p.accent else p.muted)
                }
            }
            // AI: on, the search blends meaning with words; off, it matches words only. The same
            // switch search.opensolr.com carries, and it only means anything once something is
            // typed - browsing has no query to search by meaning (Cip, 2026-09-16). No border
            // around it: a switch already looks like something to touch, and a frame would make
            // it read as one more of the buttons beside it.
            if (state.query.isNotBlank()) {
                // Greyed out and off where search by meaning cannot run: no vector search on the
                // plan, or no AI requests left this month (Cip, 2026-09-17).
                val aiUsable = state.account?.let { a -> a.vectorAllowed && (a.maxAiRequests <= 0 || a.aiRequestsUsed < a.maxAiRequests) } == true
                Text(
                    stringResource(R.string.ai),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.wordsOnly || !aiUsable) p.muted else p.accent,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Switch(
                    checked = aiUsable && !state.wordsOnly,
                    enabled = aiUsable,
                    onCheckedChange = { viewModel.setWordsOnly(!it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = p.accentFill,
                        checkedThumbColor = p.onAccentFill,
                        uncheckedTrackColor = p.chip,
                        uncheckedBorderColor = p.hairline,
                        uncheckedThumbColor = p.muted,
                    ),
                    modifier = Modifier.scale(0.7f),
                )
                Spacer(Modifier.width(2.dp))
            }
            // How the results are laid out: by best match, or in groups by date, place, people or
            // tags (Cip, 2026-09-19), right after the AI switch, which stays first. Only for a search
            // or filtered view; browsing is by date.
            // Everywhere except the duplicates, whose groups are the point, and the photos the
            // phone could not read. Plain browsing has no "best match": it is by date.
            if (!state.skippedMode && (!state.duplicatesMode || state.similarToId != null)) {
                val browsing = !state.duplicatesMode && state.query.isBlank() && state.filters.count == 0
                GroupByButton(
                    current = if (browsing && state.groupBy == com.opensolr.photos.ui.GroupBy.RELEVANCE) com.opensolr.photos.ui.GroupBy.DATE else state.groupBy,
                    options = if (browsing) com.opensolr.photos.ui.GroupBy.entries - com.opensolr.photos.ui.GroupBy.RELEVANCE else com.opensolr.photos.ui.GroupBy.entries,
                    onPick = { viewModel.setGroupBy(it) },
                )
            }
            // Filters, moved here from the search line so they are there without opening the
            // search (Cip, 2026-09-17); the number of filters in force rides on the button.
            IconAction(
                icon = R.drawable.ic_filters,
                label = stringResource(R.string.filters),
                active = state.filters.count > 0,
                badge = state.filters.count,
                onClick = { showFilters = true },
            )
            // Photos of the same thing, grouped. On when it is what the grid is showing.
            IconAction(
                icon = R.drawable.ic_duplicates,
                label = if (!state.duplicatesMode) stringResource(R.string.dup_open) else stringResource(R.string.back_all),
                active = state.duplicatesMode,
                onClick = { viewModel.showDuplicates() },
            )
            // The photos the phone could not read, and so never sent to Opensolr: only there
            // when there are some.
            if (state.skippedCount > 0 || state.skippedMode) {
                IconAction(
                    icon = R.drawable.ic_skipped,
                    label = if (!state.skippedMode) stringResource(R.string.skipped_open) else stringResource(R.string.back_all),
                    active = state.skippedMode,
                    danger = true,
                    onClick = { viewModel.showSkipped() },
                )
            }
            // Reloads the results from the index, for photos a sync added in the meantime.
            // Reload keeps the view: duplicates stay duplicates, on the same slider stop. Pressed
            // on purpose, so held answers go and the index itself is asked.
            IconAction(
                icon = R.drawable.ic_reload,
                label = stringResource(R.string.reload),
                accent = true,
                enabled = !state.searching,
                onClick = { viewModel.forceRefresh() },
            )
            // Every group of the view on screen folded away, or all of them opened again: only
            // there when the grid has headings to fold (Cip, 2026-09-17).
            val headingKeys = remember(rows) { rows.filterIsInstance<GridRow.Heading>().map { it.key }.toSet() }
            if (headingKeys.isNotEmpty()) {
                val anyCollapsed = state.collapsedHeadings.any { it in headingKeys }
                IconAction(
                    icon = if (anyCollapsed) R.drawable.ic_expand_all else R.drawable.ic_collapse_all,
                    label = if (anyCollapsed) stringResource(R.string.expand_all) else stringResource(R.string.collapse_all),
                    onClick = {
                        Haptics.tick(view, strong = false)
                        viewModel.setAllHeadings(if (anyCollapsed) emptySet() else headingKeys)
                    },
                )
            }
        }
        // The kind of duplicates (Cip, 2026-09-19): from the loosest (any two words the same)
        // through all five words to the same photo by its EXIF (green), then the file stops.
        // Every stop is one facet request, asked a moment after the move.
        // The way out of the similar view: back to the search that was in force, with its query
        // and filters intact (Cip, 2026-09-16). Getting back to a photo's details is a long press
        // on it, so no button spends a row on that.
        if (state.duplicatesMode && state.similarToId != null) {
            TextButton(
                onClick = { viewModel.backToSearch() },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.padding(horizontal = 20.dp).height(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.back_to_search),
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
                canSelect = state.duplicateGroups.isNotEmpty(),
                // "One of each" belongs to the whole index; anchored to one photo there is a
                // single group and nothing to thin out, so it is not shown at all.
                showSelectOneOfEach = state.similarToId == null,
                onSelectOneOfEach = { viewModel.selectOneOfEachDuplicate() },
            )
        }
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }
        // Tagging many photos carries on in the background, so it says so on screen the whole
        // time: hundreds of photos take a while and the owner is free to go elsewhere meanwhile
        // (Cip, 2026-09-16).
        if (state.bulkTagging) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    if (state.bulkTagTotal > 0)
                        stringResource(R.string.tagging_progress, Actions.formatCount(state.bulkTagDone.toLong()), Actions.formatCount(state.bulkTagTotal.toLong()))
                    else stringResource(R.string.tagging),
                    style = MaterialTheme.typography.bodySmall, color = p.accent,
                )
                Spacer(Modifier.height(6.dp))
                if (state.bulkTagTotal > 0) {
                    LinearProgressIndicator(
                        progress = { state.bulkTagDone.toFloat() / state.bulkTagTotal },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = p.accent, trackColor = p.chip, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = p.accent, trackColor = p.chip)
                }
            }
        }

        // What the photos on screen have in common, one tap away from being a filter. Browsing,
        // these are the index's own counts; on a typed search they come from the words-only
        // request, so nothing the vector dragged in can suggest a filter.
        if (!state.selecting) {
            SuggestedFacets(state, onPick = { field, value -> viewModel.setFilters(state.filters.toggled(field, value)) })
        }

        // What the photos on screen have in common, straight from the facets the same /select
        // already returned: one tap narrows to it. Nothing here costs an extra request.

        // "Did you mean": the spellchecker's correction, one tap away.
        state.didYouMean?.takeIf { state.query.isNotBlank() }?.let { corrected ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat { viewModel.applySuggestion(corrected) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.did_you_mean), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                Text(corrected, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.accent)
                Text("?", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        state.searchNotice?.let { Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // A line that says what just happened and goes by itself: tags saved, the index catching
        // up in the background. Never a warning - those live in Me (Cip, 2026-09-17).
        state.flash?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (state.selectingGroup) {
            Text(
                stringResource(R.string.ticking_group),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        state.searchError?.let { Notice(it, title = stringResource(R.string.search_failed_title), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

        // Nothing on the grid is only "nothing indexed" when the library itself is empty: while
        // browsing, the photos live inside groups and every group can be folded away
        // (Cip, 2026-09-18).
        if (!state.searching && state.hits.isEmpty() && state.skeleton.isEmpty() && state.resultGroups.isEmpty() && state.searchError == null) {
            EmptyResults(state)
        }

        // Swipe down on the grid reloads the results, like the reload icon. Asked for by hand,
        // so it goes to the index and drops what the phone was holding.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.searching) { if (!state.searching) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.searching,
            // Felt as well as seen: the pull has taken, and what follows is a reload and a sync.
            onRefresh = { Haptics.thud(view); pulled = true; viewModel.forceRefresh(toTop = true); viewModel.syncNow() },
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
                modifier = Modifier.fillMaxSize().dragSelect(
                    gridState = gridState,
                    rowsNow = { currentRows },
                    onStart = { id -> Haptics.tick(view, strong = true); viewModel.beginDragSelect(id) },
                    onRange = { ids, felt -> if (felt) Haptics.tick(view, strong = false); viewModel.dragSelectTo(ids) },
                    onEnd = { movedAway -> viewModel.endDragSelect(movedAway) },
                ),
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
                            // An open group whose photos are not among the loaded pages asks for
                            // exactly its own stretch of time, once (Cip, 2026-09-18).
                            if (!row.collapsed && row.range != null && row.ids.size < row.count) {
                                LaunchedEffect(row.key, row.count, row.ids.size) {
                                    viewModel.loadGroupPhotos(row.key, row.range.first, row.range.second, row.ids.size, row.count)
                                }
                            }
                            // A group of a grouped search fetches its photos the first time it is
                            // open on screen, from the phone's own copy of the index.
                            if (row.missing) {
                                LaunchedEffect(row.key) { viewModel.loadResultGroup(row.name, row.ids) }
                            }
                            // Google Photos style: the tick on a heading takes the whole group.
                            // A long press on it starts selecting with that group already ticked.
                            val allPicked = state.selecting &&
                                (row.key in state.selectedGroups || (row.ids.isNotEmpty() && state.selectedIds.containsAll(row.ids)))
                            // A quiet band in the accent, so a heading still reads as something
                            // to tap without shouting (Cip, 2026-09-17). Shared with the albums.
                            val band = headingBand(row.level)
                            val onBand = p.ink
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp + (row.level * 12).dp,
                                        end = 8.dp,
                                        top = if (row.level > 0) 5.dp else 10.dp,
                                        bottom = 5.dp,
                                    )
                                    .clip(Corner)
                                    .background(band)
                                    .combinedClickable(
                                        // While selecting, a tap still takes the whole group, as
                                        // before; otherwise it folds the group away and opens it
                                        // again (Cip, 2026-09-16). A group whose edge moves with
                                        // every page that arrives ("Best matches", "Also similar")
                                        // cannot be taken whole, so there it only folds.
                                        onClick = {
                                            if (state.selecting && row.selectable) viewModel.toggleSelectedGroup(row.key, row.ids, row.range)
                                            else viewModel.toggleHeading(row.key)
                                        },
                                        onLongClick = {
                                            if (row.selectable) {
                                                if (!state.selecting) Haptics.tick(view, strong = true)
                                                viewModel.toggleSelectedGroup(row.key, row.ids, row.range)
                                            }
                                        },
                                    )
                                    // Big enough to aim a thumb at: a heading is the tick that
                                    // takes the whole group and the fold (Cip, 2026-09-16).
                                    // Every heading is a tap target for folding and for ticking a
                                    // whole group, so none of them is allowed to get thin.
                                    .padding(start = 8.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (row.collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (row.collapsed) stringResource(R.string.open_group) else stringResource(R.string.fold_group),
                                    tint = p.accent,
                                    modifier = Modifier.size(if (row.level > 0) 20.dp else 22.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    row.text,
                                    style = headingStyle(row.level),
                                    fontWeight = FontWeight.Bold,
                                    color = onBand,
                                    modifier = Modifier.weight(1f),
                                )
                                if (state.selecting && row.selectable) {
                                    // Its own tap target, and kept clear of the right edge: the
                                    // fast scroller's strip lives there and swallowed every tap
                                    // that landed on the tick (Cip, 2026-09-18).
                                    PickTick(
                                        selected = allPicked,
                                        onClick = { viewModel.toggleSelectedGroup(row.key, row.ids, row.range) },
                                        modifier = Modifier.padding(end = FAST_SCROLL_WIDTH),
                                        dense = true,
                                    )
                                }
                            }
                        }
                        is GridRow.Photo -> {
                            val hit = row.hit
                            val selected = hit.id in state.selectedIds
                            // The photo "Show similar photos" started from: ringed and named, so
                            // it is never a guess which one the others are being compared with.
                            val anchor = state.duplicatesMode && hit.id == state.similarToId
                            Box(
                                when {
                                    state.skippedMode -> Modifier.border(2.dp, SkippedRed)
                                    anchor -> Modifier.border(2.dp, p.accent)
                                    else -> Modifier
                                },
                            ) {
                                Thumbnail(
                                    hit = hit,
                                    // The long press that starts picking photos belongs to the
                                    // grid itself now (see dragSelect), so the finger can stay
                                    // down and go on picking as it travels. It must not also sit
                                    // on the photo: two long presses with the same timeout fired
                                    // together and the second one untucked what the first ticked.
                                    // The action is still declared for the accessibility
                                    // services, which perform it by name rather than by gesture.
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { if (state.selecting) viewModel.toggleSelected(hit.id) else viewing = hit },
                                        )
                                        .semantics {
                                            onLongClick(label = selectLabel) {
                                                if (!state.selecting) viewModel.setSelecting(true)
                                                viewModel.toggleSelected(hit.id)
                                                true
                                            }
                                        },
                                )
                                // Photos the owner has tagged, marked in every view (Cip,
                                // 2026-09-16): a small tag on a translucent dark square, so it
                                // reads on a bright photo and on a dark one alike. While
                                // selecting, the corner belongs to the tick instead.
                                if (state.skippedMode && !state.selecting) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .background(SkippedRed, androidx.compose.foundation.shape.CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("!", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(
                                        "${hit.fileName} · ${hit.meaning}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Color(0x99000000))
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                                if (hit.customTags.isNotEmpty() && !state.selecting && !state.skippedMode) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(18.dp)
                                            .background(Color(0x99000000), Corner),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_tag),
                                            contentDescription = stringResource(R.string.cd_has_tags),
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                                if (anchor) {
                                    Text(
                                        stringResource(R.string.this_one),
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
                                    PickTick(
                                        selected = selected,
                                        onClick = { viewModel.toggleSelected(hit.id) },
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Over the grid, not inside it: a drag down the right edge crosses months in one
            // movement, with a tap back at every heading it passes.
            FastScroller(gridState, rows)
        }
    }

    // What to do with the selected photos, floating over the grid instead of pushing it down.
    if (state.selecting) {
        SelectionDock(
            count = state.selectedIds.size,
            modifier = Modifier.align(Alignment.BottomCenter),
            onShare = { Actions.sharePhotos(context, viewModel.photosToTag()) },
            // The app always asks first (Cip, 2026-09-16); from Android 11 the system's own
            // confirmation follows.
            onDelete = { confirmDelete = true },
            onResync = { viewModel.resyncSelected() },
            // Only what is ticked, always: nothing else can be tagged by accident
            // (Cip, 2026-09-18).
            onTag = { bulkTagging = true },
        )
    }
    }

    // The app's own warning before anything is removed, on every Android version.
    if (confirmDelete) {
        val chosen = state.selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.delete_title, chosen, Actions.formatCount(chosen.toLong()))) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; deleteSelected() }) { Text(stringResource(R.string.delete), color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel), color = p.ink) } },
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
            open = state.openFilterSections,
            onToggleSection = { viewModel.toggleFilterSection(it) },
            onChange = { viewModel.setFilters(it) },
            onDismiss = { showFilters = false },
        )
    }

    // Tags for the whole view at once, over the grid it applies to.
    if (bulkTagging) {
        BulkTagSheet(state = state, viewModel = viewModel, onDismiss = { bulkTagging = false })
    }
    // Full screen, one photo at a time, swiped through in the order the results came back -
    // which is the whole point: the gallery cannot swipe through a search of yours, because it
    // knows nothing about it (Cip, 2026-09-16).
    viewing?.let { hit ->
        // Where the opened photo sits in the results, worked out when it opens and when the
        // results change - not on every redraw behind it, which on ten thousand photos was a walk
        // through the whole list each time (Cip, 2026-09-18).
        // In groups, the photos are swiped through in the order the grid draws them, each once.
        val viewerHits = remember(rows, state.hits, state.resultGroups) {
            if (state.resultGroups.isEmpty()) state.hits
            else rows.filterIsInstance<GridRow.Photo>().map { it.hit }.distinctBy { it.id }
        }
        val startAt = remember(hit.id, viewerHits) { viewerHits.indexOfFirst { it.id == hit.id }.coerceAtLeast(0) }
        PhotoViewer(
            hits = viewerHits,
            start = startAt,
            onClose = { viewing = null },
            // Anything that leads somewhere else closes the picture first, or the new screen is
            // built behind a photo still filling the display (Cip, 2026-09-16).
            onSheet = { photo, close, openEdit ->
                DetailsSheet(
                    hit = photo,
                    viewModel = viewModel,
                    onDismiss = close,
                    onLeave = { viewing = null },
                    // Not a departure: the tags open over the photo and hand it back on Cancel.
                    onEdit = { openEdit(it); close() },
                )
            },
            onEditSheet = { photo, close -> EditSheet(hit = photo, state = state, viewModel = viewModel, onDismiss = close) },
            topInset = topInset,
            bottomInset = bottomInset,
            onNeedMore = { if (!state.endReached && !state.searching) viewModel.search(reset = false) },
            viewModel = viewModel,
            onDelete = { one ->
                val sender = Actions.deleteRequest(context, Actions.contentUris(context, listOf(one)))
                pendingDelete = setOf(one.id)
                if (sender != null) {
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    viewModel.removeDeleted(pendingDelete)
                    pendingDelete = emptySet()
                }
                viewing = null
            },
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
                label = facetLabel(LocalContext.current, field, value.value),
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
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove), tint = if (accent) p.accent else p.muted, modifier = Modifier.size(12.dp))
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
    onTag: () -> Unit,
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
        // Tag first, on the left, exactly where it is under a single photo, then share, then the
        // re-sync, and delete last, away from the rest (Cip, 2026-09-20). They act on the photos
        // that are ticked, and only those.
        DockAction(R.drawable.ic_tag, stringResource(R.string.dock_tag_n, Actions.formatCompact(count.toLong())), enabled = count > 0, onClick = onTag)
        DockAction(R.drawable.ic_share, stringResource(R.string.act_share), enabled = count > 0, onClick = onShare)
        DockAction(R.drawable.ic_sync, if (count > 0) stringResource(R.string.dock_resync_n, Actions.formatCompact(count.toLong())) else stringResource(R.string.dock_resync), enabled = count > 0, accent = true, onClick = onResync)
        // Short word here: four labels with counts in them do not fit a narrow phone (Cip, 2026-09-20).
        DockAction(R.drawable.ic_delete, stringResource(R.string.dock_delete), enabled = count > 0, onClick = onDelete)
    }
}

/**
 * One icon of the dock with its word, greyed out while nothing is selected.
 */
@Composable
internal fun DockAction(icon: Int, label: String, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = when {
        !enabled -> p.hairline
        accent -> p.accent
        else -> p.ink
    }
    Column(
        Modifier
            .padding(horizontal = 3.dp)
            .clip(Corner)
            // Each one is drawn as a button of its own, on a shade neither row above it uses, so
            // it is plain that these are things to press (Cip, 2026-09-20).
            .background(p.dockFill)
            .border(1.dp, p.hairline, Corner)
            .then(if (enabled) Modifier.combinedClickableCompat(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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

    /**
     * A full-width heading, with the photos of its group so its tick can take them all, and
     * whether the group is folded away under it.
     */
    data class Heading(
        /** What the heading says, which changes when it is folded ("Friday (42)"). */
        val text: String,
        val ids: List<String>,
        val collapsed: Boolean = false,
        /**
         * What the heading *is*, which never changes. Kept apart from [text] on purpose: the key
         * used to be built from the words on screen, so folding a group renamed it, the next tap
         * toggled a different name, and the group could never be opened again (Cip, 2026-09-16).
         */
        val name: String = text,
        /**
         * 0 for a month, 1 for a day inside it. Only the size and the indent of the heading
         * change with it; everything else (folding, the tick that takes the group) is the same.
         */
        val level: Int = 0,
        /**
         * When the group is a stretch of time (a month, a day), the first and last instant of it.
         * The tick then takes every photo of the view in that stretch, not only the pages the grid
         * happens to hold. Groups that are not a stretch of time - "Best matches" and "Also
         * similar", whose boundary moves as more results arrive - have none, and no tick
         * (Cip, 2026-09-18).
         */
        val range: Pair<Long, Long>? = null,
        /** How many photos the group holds in all, which is not always how many are loaded. */
        val count: Int = ids.size,
        /** A group of a grouped search: [ids] are every photo in it, so its tick takes exactly those. */
        val exact: Boolean = false,
        /** An open group of a grouped search showing its photos, some of which are not loaded yet. */
        val missing: Boolean = false,
    ) : GridRow {
        override val key: String get() = "h:$name"

        /** True when ticking this heading means something exact. */
        val selectable: Boolean get() = exact || range != null || (ids.isNotEmpty() && name.contains(" of the same"))
    }

    /**
     * A photo. In a grouped search a photo can sit in several groups (two people, two tags), so it
     * is keyed by its [group] as well: the grid keeps its items by key, and the same key twice
     * crashes it (Cip, 2026-09-16; 2026-09-19).
     */
    data class Photo(val hit: PhotoHit, val group: String? = null) : GridRow {
        override val key: String get() = if (group == null) hit.id else "g:$group|${hit.id}"
    }
}

/**
 * The grid of plain browsing: every year, month and day of the library - as the phone knows them
 * from its own copy of the index - with the photos that have been loaded sitting inside them.
 *
 * Browsing used to show only the groups the loaded pages happened to reach, so with everything
 * folded the first sixty photos hid every year below them (Cip, 2026-09-18). The shape comes from
 * the phone, the photos from the index, and a group opened before its photos have arrived asks
 * for exactly that stretch of time.
 */
private fun buildSkeletonRows(
    skeleton: List<com.opensolr.photos.ui.DateGroup>,
    hits: List<PhotoHit>,
    collapsed: Set<String>,
): List<GridRow> {
    val rows = ArrayList<GridRow>(hits.size + skeleton.size)
    // Every photo filed under the groups it belongs to, in ONE pass, keyed exactly as the skeleton
    // names them. Looking for each group's photos by walking the whole list meant the loaded
    // photos times the groups - a library of ten thousand across two hundred groups was two
    // million comparisons for every redraw (Cip, 2026-09-18).
    val byName = HashMap<String, MutableList<PhotoHit>>()
    hits.forEach { hit ->
        val at = hit.takenMs
        if (at == null) {
            rows += GridRow.Photo(hit)
            return@forEach
        }
        if (Actions.isRecentDay(at)) {
            byName.getOrPut(Actions.dateHeading(at)) { ArrayList() } += hit
            return@forEach
        }
        byName.getOrPut(Actions.yearHeading(at)) { ArrayList() } += hit
        byName.getOrPut(Actions.monthKey(at)) { ArrayList() } += hit
        byName.getOrPut(Actions.dayKey(at)) { ArrayList() } += hit
    }
    // A month is only drawn under its year, and a day under its month, so folding a year folds
    // everything inside it.
    // One pass: a group has children when the group before it in the list is its parent, which is
    // how the skeleton is built - year, then its months, then that month's days.
    val withChildren = HashSet<String>()
    for (i in skeleton.indices) {
        val next = skeleton.getOrNull(i + 1) ?: continue
        if (next.level > skeleton[i].level) withChildren += skeleton[i].name
    }
    var yearFolded = false
    var monthFolded = false
    skeleton.forEach { group ->
        if (group.level >= 1 && yearFolded) return@forEach
        if (group.level == 2 && monthFolded) return@forEach
        val folded = "h:${group.name}" in collapsed
        if (group.level == 0) { yearFolded = folded; monthFolded = false }
        if (group.level == 1) monthFolded = folded
        val mine = byName[group.name] ?: emptyList()
        rows += GridRow.Heading(
            text = if (folded) "${group.text} (${Actions.formatCount(group.count.toLong())})" else group.text,
            ids = mine.map { it.id },
            collapsed = folded,
            name = group.name,
            level = group.level,
            range = group.from to group.to,
            count = group.count,
        )
        if (folded) return@forEach
        // The photos belong to the deepest open group that holds them: a year with months under
        // it, or a month with days under it, shows none of its own. Today and the last few days
        // have nothing under them, so they show theirs. Which groups have children is worked out
        // once, above, and not by scanning every group for every group (Cip, 2026-09-18).
        if (group.name in withChildren) return@forEach
        mine.forEach { rows += GridRow.Photo(it) }
    }
    return rows
}

/**
 * The grid of a grouped search (Cip, 2026-09-19): every group as a heading, the ones inside it
 * under it, and the photos in the deepest open group that holds them - keyed by group, because a
 * photo can be in more than one. A folded group hides everything inside it. Photos of a group that
 * are not loaded yet are asked for by its heading.
 */
private fun buildResultGroupRows(
    groups: List<com.opensolr.photos.ui.ResultGroup>,
    hits: List<PhotoHit>,
    collapsed: Set<String>,
): List<GridRow> {
    val byId = HashMap<String, PhotoHit>(hits.size * 2)
    hits.forEach { byId[it.id] = it }
    val rows = ArrayList<GridRow>(hits.size + groups.size)
    var foldedAt = Int.MAX_VALUE
    groups.forEachIndexed { i, group ->
        if (group.level > foldedAt) return@forEachIndexed
        foldedAt = Int.MAX_VALUE
        val folded = "h:${group.name}" in collapsed
        if (folded) foldedAt = group.level
        val leaf = (groups.getOrNull(i + 1)?.level ?: -1) <= group.level
        val photos = if (!folded && leaf) group.ids.mapNotNull { byId[it] } else emptyList()
        rows += GridRow.Heading(
            text = if (folded) "${group.text} (${Actions.formatCount(group.ids.size.toLong())})" else "${group.text} · ${Actions.formatCount(group.ids.size.toLong())}",
            ids = group.ids,
            collapsed = folded,
            name = group.name,
            level = group.level,
            count = group.ids.size,
            exact = true,
            missing = !folded && leaf && photos.size < group.ids.size,
        )
        photos.forEach { rows += GridRow.Photo(it, group.name) }
    }
    return rows
}

/** The headings the grid writes itself, translated; "%1$s of the same" takes the group's size. */
private data class GridWords(val best: String, val similar: String, val ofTheSame: String)

/**
 * Turns the results into grid rows with headings over their groups.
 *
 * [byDate] cuts on the day or the month, for results that come back in date order; photos with
 * no date land under the last heading seen, or under none at the top. Otherwise the cut is on
 * the score: the hybrid query returns the strong matches first and the vector's near misses
 * after them, so the biggest fall in score is where "Also similar" begins.
 */
private fun buildRows(
    hits: List<PhotoHit>,
    byDate: Boolean,
    groups: List<Int> = emptyList(),
    collapsed: Set<String> = emptySet(),
    words: GridWords,
): List<GridRow> {
    if (hits.isEmpty()) return emptyList()

    // One group: its heading, then its photos - unless it is folded away, in which case the
    // heading stands alone and says how many are under it (Cip, 2026-09-16).
    fun MutableList<GridRow>.addGroup(
        name: String,
        photos: List<PhotoHit>,
        text: String = name,
        level: Int = 0,
        range: Pair<Long, Long>? = null,
    ): Boolean {
        val folded = "h:$name" in collapsed
        // The name is what the group is, and never changes; the text is only what it says now.
        this += GridRow.Heading(
            text = if (folded) "$text (${Actions.formatCount(photos.size.toLong())})" else text,
            ids = photos.map { it.id },
            collapsed = folded,
            name = name,
            level = level,
            range = range,
        )
        if (!folded) photos.forEach { this += GridRow.Photo(it) }
        return folded
    }

    if (groups.isNotEmpty()) {
        // Photos of the same thing: the groups come laid out one after another.
        val rows = ArrayList<GridRow>(hits.size + groups.size)
        var from = 0
        groups.forEachIndexed { index, size ->
            val group = hits.drop(from).take(size)
            if (group.isEmpty()) return@forEachIndexed
            // The number stays in the NAME, which is the key and must stay unique, and is kept
            // off the screen: nobody wants to read which group it is (Cip, 2026-09-16).
            rows.addGroup(
                "${group.size} of the same · ${index + 1}",
                group,
                text = String.format(words.ofTheSame, Actions.formatCount(group.size.toLong())),
            )
            from += size
        }
        return rows
    }
    if (!byDate) {
        val cut = scoreCut(hits) ?: return hits.map { GridRow.Photo(it) }
        val rows = ArrayList<GridRow>(hits.size + 2)
        rows.addGroup("Best matches", hits.take(cut), text = words.best)
        rows.addGroup("Also similar", hits.drop(cut), text = words.similar)
        return rows
    }
    // Three levels: the year, the months in it, and the days in each month. A month of holiday
    // photos used to be one unbroken run of hundreds of thumbnails with nothing to aim a tap at
    // (Cip, 2026-09-16), and a library of ten years was one long ladder of months
    // (Cip, 2026-09-18). Today / Yesterday / a weekday in the last six days are already days, so
    // they stay flat at the top, outside the years.
    val recent = LinkedHashMap<String, MutableList<PhotoHit>>()
    val years = LinkedHashMap<String, LinkedHashMap<String, MutableList<PhotoHit>>>()
    val loose = ArrayList<PhotoHit>()
    // A photo with no date of its own sits where the one before it sits.
    var lastYear: String? = null
    var lastMonth: String? = null
    var lastRecent: String? = null
    hits.forEach { hit ->
        val millis = hit.takenMs
        when {
            millis != null && Actions.isRecentDay(millis) -> {
                val heading = Actions.dateHeading(millis)
                recent.getOrPut(heading) { ArrayList() } += hit
                lastRecent = heading
                lastYear = null
                lastMonth = null
            }
            millis != null -> {
                val year = Actions.yearHeading(millis)
                val month = Actions.monthKey(millis)
                years.getOrPut(year) { LinkedHashMap() }.getOrPut(month) { ArrayList() } += hit
                lastYear = year
                lastMonth = month
                lastRecent = null
            }
            lastYear != null && lastMonth != null -> years[lastYear]?.get(lastMonth)?.add(hit)
            lastRecent != null -> recent[lastRecent]?.add(hit)
            else -> loose += hit
        }
    }

    val rows = ArrayList<GridRow>(hits.size + years.size * 2)
    loose.forEach { rows += GridRow.Photo(it) }
    // The last few days, by themselves, as they have always been.
    recent.forEach { (heading, photos) ->
        val millis = photos.firstNotNullOfOrNull { it.takenMs }
        rows.addGroup(heading, photos, level = 0, range = millis?.let { Actions.daySpan(it) })
    }
    years.forEach { (year, months) ->
        val yearPhotos = months.values.flatten()
        val yearMillis = yearPhotos.firstNotNullOfOrNull { it.takenMs }
        if (rows.addGroup(year, yearPhotos, level = 0, range = yearMillis?.let { Actions.yearSpan(it) })) return@forEach
        // The year's own photos were written by addGroup; its months replace them.
        repeat(yearPhotos.size) { rows.removeAt(rows.size - 1) }
        months.forEach { (monthKey, monthPhotos) ->
            val monthMillis = monthPhotos.firstNotNullOfOrNull { it.takenMs }
            val monthText = monthMillis?.let { Actions.monthHeading(it) } ?: monthKey
            if (rows.addGroup(monthKey, monthPhotos, text = monthText, level = 1, range = monthMillis?.let { Actions.monthSpan(it) })) return@forEach
            val days = LinkedHashMap<String, MutableList<PhotoHit>>()
            monthPhotos.forEach { hit ->
                val key = hit.takenMs?.let { Actions.dayKey(it) } ?: days.keys.lastOrNull()
                if (key == null) days.getOrPut("") { ArrayList() } += hit else days.getOrPut(key) { ArrayList() } += hit
            }
            // Every month is spelled out by its days; only photos with no date at all stay
            // directly under the month (Cip, 2026-09-18).
            if (days.keys.any { it.isEmpty() }) return@forEach
            repeat(monthPhotos.size) { rows.removeAt(rows.size - 1) }
            days.forEach { (dayKey, dayPhotos) ->
                val millis = dayPhotos.firstNotNullOfOrNull { it.takenMs }
                rows.addGroup(dayKey, dayPhotos, text = millis?.let { Actions.dayHeading(it) } ?: dayKey, level = 2, range = millis?.let { Actions.daySpan(it) })
            }
        }
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

/** Rows below which the fast scroller is not worth showing: a couple of screens. */
/** How far a finger must travel up the picture before the details open. */
private const val VIEWER_SWIPE_UP = 90f

/** How much of the screen the picture must be carried down before the viewer closes. */
private const val VIEWER_DISMISS_SHARE = 0.18f

/** How long the double tap takes to magnify or come back, in milliseconds. */
private const val VIEWER_DOUBLE_TAP_MS = 260

/** How far a double tap magnifies, as Google Photos does it. */
private const val VIEWER_DOUBLE_TAP_SCALE = 3f

/**
 * Makes the viewer's dialog window cover the whole screen and returns how far its bottom still
 * reaches past the screen's real bottom edge, so the actions can be lifted by exactly that much.
 *
 * The activity is edge to edge and lays out into the display cutout; a dialog window is not, and
 * on MIUI / HyperOS it is pushed down below the camera cutout while keeping its full height, so
 * its lower part sat outside the screen and the buttons under the photo were cut off whatever
 * padding they had (Cip, 2026-09-17). The window gets the activity's cutout mode and the full
 * size; the measured overflow covers any phone that still places it lower.
 */
@Composable
private fun viewerWindowOverflow(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var overflowPx by remember { mutableIntStateOf(0) }
    val window = (view.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.let { w ->
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = w.attributes
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (attrs.layoutInDisplayCutoutMode != mode) {
                    attrs.layoutInDisplayCutoutMode = mode
                    w.attributes = attrs
                }
            }
        }
    }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                android.util.DisplayMetrics().also { view.display?.getRealMetrics(it) }.heightPixels
            }
            val past = (location[1] + view.height - screenHeight).coerceAtLeast(0)
            if (screenHeight > 0 && past != overflowPx) overflowPx = past
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return with(density) { overflowPx.toDp() }
}

/** How many rows may still be below the screen when the next page of results is asked for. */
private val PREFETCH_REMAINING = com.opensolr.photos.search.SearchRepository.PAGE / 2

/** The least room kept under the viewer's actions, whatever a phone says its bars measure. */
private val VIEWER_MIN_BOTTOM = 28.dp

private const val FAST_SCROLL_MIN_ROWS = 60

/** The grab handle of the fast scroller: tall enough for a thumb to land on. */
private val FAST_SCROLL_THUMB = 72.dp

/** How far above the thumb the month bubble sits, so a thumb never covers it (Cip, 2026-09-16). */
private val FAST_SCROLL_LABEL_LIFT = 64.dp

/** How wide the strip on the right edge is: narrow enough to leave a photo tappable. */
private val FAST_SCROLL_WIDTH = 36.dp

/** Room kept to the left of the bar for the month bubble while a finger is dragging. */
private val FAST_SCROLL_LABEL_ROOM = 240.dp
/** "Best matches" is never shorter than this, so a single strong hit does not stand alone. */
private const val MIN_BEST_MATCHES = 3
/** A photo below this share of the best score is what the vector brought along, not a match. */
private const val ALSO_SIMILAR_SHARE = 0.6

/**
 * One button of the header menu: a small bordered cell with its icon over a short label,
 * sharing the row's width equally with the others. [active] draws it in the accent.
 */
@Composable
internal fun RowScope.HeaderItem(label: String, active: Boolean = false, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .weight(1f)
            .clip(Corner)
            .background(p.buttonFill)
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
            fontWeight = FontWeight.SemiBold,
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
        contentDescription = if (running) stringResource(R.string.cd_sync_running) else stringResource(R.string.nav_sync),
        tint = if (running) p.accent else p.ink,
        modifier = Modifier.size(20.dp).then(if (running) Modifier.rotate(angle) else Modifier),
    )
}

/**
 * The duplicates slider: one stop per SearchRepository.DUPLICATE_FIELDS, the name of the kind
 * under it. Its colour tells where it stands: black at the loosest "first 3 words" stop, turning
 * green by the EXIF stop, then a neutral colour for the file stops, which are not on that scale.
 * The thumb follows the finger at once; the level reaches [onLevel] on every stop crossed, and
 * the view model waits for the finger to settle before asking the index.
 */
@Composable
private fun DuplicateLevelSlider(level: Int, onLevel: (Int) -> Unit, canSelect: Boolean, showSelectOneOfEach: Boolean, onSelectOneOfEach: () -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    var value by remember { mutableStateOf(level.toFloat()) }
    LaunchedEffect(level) { if (value.roundToInt() != level) value = level.toFloat() }
    val stop = level.coerceIn(0, DUPLICATE_KIND_NAMES.size - 1)
    // Which set of scale colours reads on the current background: ink is near-black on paper
    // and near-white on a dark screen, so it says which theme is in force without asking.
    val dark = p.ink.red > 0.5f
    val loose = if (dark) DUPLICATE_LOOSE_DARK else DUPLICATE_LOOSE_LIGHT
    val green = if (dark) DUPLICATE_GREEN_DARK else DUPLICATE_GREEN_LIGHT
    val colour = when {
        stop <= DUPLICATE_EXIF_STOP -> lerp(loose, green, stop / DUPLICATE_EXIF_STOP.toFloat())
        // File name, size and the file itself are not on the words / EXIF scale: a neutral
        // colour of their own.
        else -> if (dark) DUPLICATE_NEUTRAL_DARK else DUPLICATE_NEUTRAL_LIGHT
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Slider(
            value = value,
            // The thumb glides instead of snapping from notch to notch, and a stop is only left
            // once the finger is most of the way to the next one (Cip, 2026-09-20): rounding at
            // the halfway mark made the kinds flick past under a thumb that had barely moved.
            onValueChange = {
                value = it
                val next = if (kotlin.math.abs(it - level) < STOP_SLOP) level else it.roundToInt()
                if (next != level) {
                    Haptics.tick(view, strong = false)
                    onLevel(next)
                }
            },
            // Let go and the thumb settles on the stop it chose, rather than between two of them.
            onValueChangeFinished = { value = level.toFloat() },
            valueRange = 0f..(DUPLICATE_KIND_NAMES.size - 1).toFloat(),
            // No notches for the thumb to jump between: with them the slider moved a whole stop
            // at a time, which is what made it feel twitchy. The stops are still exactly where
            // they were - it is only the way the thumb travels between them that changed.
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = colour,
                activeTrackColor = colour,
                inactiveTrackColor = p.chip,
                activeTickColor = colour,
                inactiveTickColor = p.hairline,
            ),
        )
        Text("$stop · ${stringArrayResource(R.array.dup_kinds)[stop]}", style = MaterialTheme.typography.labelMedium, color = colour)
        // One tap selects one photo of every group (the last of each, the first one stays
        // unticked), for review; the selection dock then shares, deletes or re-syncs them.
        if (showSelectOneOfEach) {
            TextButton(
                onClick = onSelectOneOfEach,
                enabled = canSelect,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(stringResource(R.string.select_one_each), style = MaterialTheme.typography.labelMedium, color = if (canSelect) p.accent else p.muted)
            }
        }
    }
}

/** What each stop of the slider groups, in the order of DUPLICATE_FIELDS. */
private val DUPLICATE_KIND_NAMES = listOf(
    "Same first 3 words", "Any 4 words the same", "All 5 words the same",
    "Same photo (EXIF)",
    "Same file name", "Same file size", "Same file (exact copy)",
)

/**
 * How far towards the next stop the finger has to travel before the slider takes it: most of the
 * way, not half of it (Cip, 2026-09-20). Halfway meant a thumb that had barely moved flicked
 * through two or three kinds, and every one of them asked the index.
 */
private const val STOP_SLOP = 0.7f

/** The EXIF stop, where the words-to-EXIF colour scale ends. */
private const val DUPLICATE_EXIF_STOP = 3
/**
 * The ends of the duplicates scale, one set per theme (Cip, 2026-09-16). On paper the loosest
 * stop is near-black; on a dark screen that is the colour of the screen itself, so the thumb,
 * the track, the ticks and the name under them all disappeared. The dark set turns that end
 * light and lifts the others off the background as well.
 */
private val DUPLICATE_LOOSE_LIGHT = Color(0xFF111111)
private val DUPLICATE_NEUTRAL_LIGHT = Color(0xFF495057)
private val DUPLICATE_GREEN_LIGHT = Color(0xFF2F9E44)

private val DUPLICATE_LOOSE_DARK = Color(0xFFF4F1EC)
private val DUPLICATE_NEUTRAL_DARK = Color(0xFFADB5BD)
private val DUPLICATE_GREEN_DARK = Color(0xFF51CF66)

/**
 * Removable chips for the filters currently applied, on ONE row that scrolls sideways, like the
 * suggestion pills of a typed search (Cip, 2026-09-15): many filters never push the grid down.
 */
@Composable
private fun ActiveFilterChips(filters: SearchFilters, onRemove: (SearchFilters) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chips = buildList {
        SearchFilters.FACETS.forEach { (field, _) ->
            filters.values(field).forEach { value -> add(facetLabel(context, field, value) to filters.toggled(field, value)) }
        }
        filters.folders.sorted().forEach { root -> add(root.trimEnd('/') to filters.copy(folders = filters.folders - root)) }
        if (filters.withLocation) add(context.getString(R.string.chip_with_location) to filters.copy(withLocation = false))
        filters.tagged?.let { add(context.getString(if (it) R.string.chip_tagged else R.string.chip_not_tagged) to filters.copy(tagged = null)) }
        filters.near?.let { add(context.getString(R.string.within, it.radiusText) to filters.copy(near = null)) }
        filters.taken?.let { add(it.label to filters.copy(taken = null)) }
        filters.hasOcr?.let { add(context.getString(if (it) R.string.chip_ocr else R.string.chip_no_ocr) to filters.copy(hasOcr = null)) }
        filters.hasPeople?.let { add(context.getString(if (it) R.string.chip_has_people else R.string.chip_no_people) to filters.copy(hasPeople = null)) }
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
 * One of the small actions over the grid: a bordered cell, so it reads as a button rather than a
 * mark on the page (Cip, 2026-09-16). [active] draws it in the accent, as the header cells do.
 */
@Composable
internal fun IconAction(
    icon: Int,
    label: String,
    active: Boolean = false,
    accent: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val tint = when {
        !enabled -> p.hairline
        danger -> SkippedRed
        active || accent -> p.accent
        else -> p.ink
    }
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(36.dp)
            .clip(Corner)
            // A shade apart from the header row above it, so the two rows do not read as one
            // long strip of buttons (Cip, 2026-09-20).
            .background(p.toolFill)
            .border(1.dp, if (active) (if (danger) SkippedRed else p.accent) else p.hairline, Corner)
            .combinedClickableCompat { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        if (badge > 0) {
            Text(
                "$badge",
                style = MaterialTheme.typography.labelSmall,
                color = p.onAccentFill,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(p.accentFill, Corner)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * The button that picks how the results are laid out, and its short list (Cip, 2026-09-19). It is
 * lit when the results are in groups, so a grouped view never looks like the plain one.
 */
@Composable
private fun GroupByButton(
    current: com.opensolr.photos.ui.GroupBy,
    options: List<com.opensolr.photos.ui.GroupBy>,
    onPick: (com.opensolr.photos.ui.GroupBy) -> Unit,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon = R.drawable.ic_group,
            label = stringResource(R.string.cd_group_results, stringResource(current.labelRes)),
            active = current != options.first(),
            onClick = { open = true },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.paper) {
            Text(
                stringResource(R.string.group_by_title),
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            options.forEach { how ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(how.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (how == current) FontWeight.Bold else FontWeight.Normal,
                            color = if (how == current) p.accent else p.ink,
                        )
                    },
                    leadingIcon = {
                        if (how == current) Icon(Icons.Filled.Check, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = { open = false; onPick(how) },
                )
            }
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
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove), tint = p.accent, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * A photo's place as a button: the look of the People and tag chips, with a pin in front and a
 * heavier accent border, so it reads as something to press (Cip, 2026-09-19).
 */
@Composable
private fun PlaceButton(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(Corner)
            .background(p.paper)
            .border(1.5.dp, p.accent, Corner)
            .combinedClickableCompat(onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Place, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = p.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_change_place), tint = p.accent, modifier = Modifier.size(15.dp))
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
    // Built once per photo, not once per redraw: a thumbnail is redrawn on every tick of a scroll
    // through ten thousand of them, and each rebuild was a new request object for the same picture
    // (Cip, 2026-09-18).
    // The file's size is part of the cache key: an edit in another app keeps the photo's address
    // and changes its size, so the edited picture is drawn instead of the one held in memory.
    val request = remember(uri, hit.sizeBytes) { ImageRequest.Builder(context).data(uri).size(360).setParameter("bytes", hit.sizeBytes).crossfade(true).build() }
    AsyncImage(
        model = request,
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
    if (state.duplicatesMode || state.skippedMode) return
    val p = LocalPalette.current
    val text = when {
        state.sync.busy && state.query.isBlank() -> stringResource(R.string.empty_indexing)
        state.query.isBlank() && state.filters.count == 0 -> stringResource(R.string.empty_nothing)
        else -> stringResource(R.string.empty_nomatch)
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
    val view = LocalView.current
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
            Text(stringResource(R.string.clear_all), style = MaterialTheme.typography.labelMedium)
        }
        Button(
            onClick = { Haptics.tick(view, strong = true); onDone() },
            modifier = Modifier.height(34.dp),
            shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = p.accentFill, contentColor = p.onAccentFill),
            contentPadding = PaddingValues(horizontal = 12.dp),
            elevation = null,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.done_count, Actions.formatCount(count)), style = MaterialTheme.typography.labelMedium)
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
    open: Set<String>,
    onToggleSection: (String) -> Unit,
    onChange: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
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
            Text(stringResource(R.string.filters), style = MaterialTheme.typography.headlineSmall, color = p.ink)
            // The same two actions at the top as at the bottom, small, so a long list of values
            // never has to be scrolled through to clear or close (Cip, 2026-09-15).
            Spacer(Modifier.height(8.dp))
            FilterActions(count, onClear = { onChange(SearchFilters()) }, onDone = onDismiss)
            Spacer(Modifier.height(12.dp))

            // The order people reach for (Cip, 2026-09-17): when; then what the photo has; then
            // the lists of people, tags and places; everything else after. Every one of them is a
            // heading exactly like a month on the grid, folded away until it is wanted, and each
            // says how many of its own filters are on (Cip, 2026-09-18).
            val facetTitles = SearchFilters.FACETS.toMap()
            val facet: @Composable (String) -> Unit = { field ->
                facetTitles[field]?.let { title ->
                    val values = facets[field]
                    val chosen = draft.values(field)
                    if (!values.isNullOrEmpty() || chosen.isNotEmpty()) {
                        FilterGroup(facetTitle(field), chosen.size, title in open, { onToggleSection(title) }) {
                            FacetValues(values, chosen, label = { facetLabel(context, field, it) }) { onChange(draft.toggled(field, it)) }
                        }
                    }
                }
            }
            // The two ways of filtering by time do not argue with each other: years narrow the
            // calendar, and a chosen stretch of days puts the years aside (Cip, 2026-09-18).
            val years = draft.values("year").mapNotNull { it.toIntOrNull() }
            if (draft.taken == null) {
                facet("year")
            } else {
                FilterGroup(stringResource(R.string.facet_year), 0, "Year" in open, { onToggleSection("Year") }) {
                    Text(
                        stringResource(R.string.year_set_by_days),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                }
            }
            // Under the years, which say which years you actually have photos in, a picker
            // for anything narrower than a whole year (Cip, 2026-09-16). The years chosen above
            // bound what the calendar offers.
            FilterGroup(stringResource(R.string.taken_between), if (draft.taken != null) 1 else 0, "Taken between" in open, { onToggleSection("Taken between") }) {
                DateRangeValues(draft.taken, years) { onChange(draft.copy(taken = it, fields = if (it != null) draft.fields - "year" else draft.fields)) }
            }

            // Only the switches of this group count here; the words of "Meaning" carry their own
            // badge, under their own heading (Cip, 2026-09-18).
            val switchesOn = listOf(draft.hasOcr == true, draft.tagged == true, draft.hasPeople == true, draft.withLocation).count { it }
            FilterGroup(stringResource(R.string.photo_has), switchesOn, "Photo has" in open, { onToggleSection("Photo has") }) {
                FilterSwitch(stringResource(R.string.sw_ocr), stringResource(R.string.sw_ocr_hint), draft.hasOcr == true) { onChange(draft.copy(hasOcr = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_tagged), stringResource(R.string.sw_tagged_hint), draft.tagged == true) { onChange(draft.copy(tagged = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_people), stringResource(R.string.sw_people_hint), draft.hasPeople == true) { onChange(draft.copy(hasPeople = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_location), stringResource(R.string.sw_location_hint), draft.withLocation) { onChange(draft.copy(withLocation = it)) }
                Spacer(Modifier.height(12.dp))
            }
            // "Meaning": the words Opensolr read the photos into, right under the switches, as
            // before (Cip, 2026-09-17).
            facet("labels")

            facet("persons_ss")
            facet("custom_tags")
            facet("city")
            facet("country")
            // The folders chosen in Sync, named as Sync names them, right after the places
            // (Cip, 2026-09-19). A folder inside another chosen one is counted by the outer one.
            val roots = facets[SearchFilters.FOLDER_ROOTS]
            if (!roots.isNullOrEmpty() || draft.folders.isNotEmpty()) {
                FilterGroup(stringResource(R.string.folder), draft.folders.size, "Folder" in open, { onToggleSection("Folder") }) {
                    FacetValues(roots, draft.folders, label = { it.trimEnd('/') }) { root ->
                        onChange(draft.copy(folders = if (root in draft.folders) draft.folders - root else draft.folders + root))
                    }
                }
            }
            SearchFilters.FACETS.map { it.first }
                .filter { it !in setOf("year", "labels", "custom_tags", "persons_ss", "city", "country") }
                .forEach { facet(it) }

            draft.near?.let { near ->
                // Radius of the "near a point" filter set from the map or a photo's details.
                val title = "Distance from " + String.format(java.util.Locale.US, "%.4f, %.4f", near.lat, near.lon)
                FilterGroup(stringResource(R.string.distance_from, String.format(java.util.Locale.US, "%.4f, %.4f", near.lat, near.lon)), 1, title in open, { onToggleSection(title) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val choices = (NearFilter.RADII + near.radiusKm).distinct().sorted()
                        choices.forEach { km ->
                            Chip(
                                label = near.copy(radiusKm = km).radiusText,
                                selected = km == near.radiusKm,
                                onClick = { onChange(draft.copy(near = near.copy(radiusKm = km))) },
                            )
                        }
                        Chip(label = stringResource(R.string.anywhere), selected = false, onClick = { onChange(draft.copy(near = null)) })
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }

            HorizontalDivider(color = p.hairline)
            Spacer(Modifier.height(12.dp))
            FilterActions(count, onClear = { onChange(SearchFilters()) }, onDone = onDismiss)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One group of filters under a heading drawn exactly like a month on the grid: the same band, the
 * same type, the same arrow, and the same tap to fold it away (Cip, 2026-09-18).
 *
 * Folded is how they all start, and how each one is found again on the next visit, because the
 * phone remembers which were opened. A folded heading carries a badge with how many of its own
 * filters are on, so nothing applied can hide under it.
 */
@Composable
internal fun FilterGroup(title: String, active: Int, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
                .clip(Corner)
                .background(headingBand(0))
                .combinedClickableCompat { Haptics.tick(view, strong = false); onToggle() }
                .padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (open) stringResource(R.string.fold_group) else stringResource(R.string.open_group),
                tint = p.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = headingStyle(0), fontWeight = FontWeight.Bold, color = p.ink, modifier = Modifier.weight(1f))
            if (active > 0) {
                Text(
                    active.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = p.onAccentFill,
                    modifier = Modifier
                        .clip(Corner)
                        .background(p.accentFill)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * The bar down the right edge that a finger can drag to cross months in one movement, instead of
 * flicking through a year of thumbnails (Cip, 2026-09-16).
 *
 * It rides the rows already in the grid - the same list the headings come from - so it needs no
 * request of its own and cannot disagree with what is on screen. Crossing a heading gives a tap
 * back: the heavier one for a month, the lighter one for a day inside it, which is what tells a
 * thumb how far it has travelled without looking.
 *
 * It appears while the grid is moving and fades out when it stops, so it never sits over photos.
 */
@Composable
private fun BoxScope.FastScroller(gridState: LazyGridState, rows: List<GridRow>) {
    val p = LocalPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val total = rows.size
    var dragging by remember { mutableStateOf(false) }
    // The row the finger is over, which is not where the grid is until it has caught up.
    var aimed by remember { mutableIntStateOf(-1) }
    // How far into that row the finger stands, and the one scroll the bar is allowed to have
    // running at a time.
    var lastInto by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The rows as they are now, read by the drag, which outlives any one list: filters, groups
    // opening and photos loading all change it under a finger. A drag that went on reading the
    // list it started with walked past the end of a shorter one and crashed the app (Cip, 2026-09-18).
    val currentRows by rememberUpdatedState(rows)
    // Too short to be worth a shortcut: a couple of screens are flicked through faster by hand.
    if (total < FAST_SCROLL_MIN_ROWS) return

    // The bar shows itself while the grid moves and fades when it stops, so it never sits over
    // photos - but the strip stays touchable even when nothing is drawn, otherwise there would be
    // nothing to take hold of from a standing start.
    val alpha by animateFloatAsState(
        targetValue = if (dragging || gridState.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = if (dragging) 0 else 450),
        label = "fastScrollerAlpha",
    )

    // How tall the list is, row by row, as a running total (Cip, 2026-09-20).
    //
    // The bar used to map the finger onto the ROW NUMBER, which made every row the same width on
    // it: a folded group, one row, took as much of the bar as a single photo, so with one group
    // open and forty folded the forty shared a sliver and the finger could not stop on any of
    // them. The bar is a map of the scroll itself instead - a folded group is a heading tall, an
    // open one is as tall as its photos.
    //
    // The map is PACKED THE WAY THE GRID PACKS, and this is the whole of it: photos fill a line
    // of [columns], a heading takes a line of its own and ends the line before it, and a group
    // whose last line is half empty still costs a whole line. Adding a third of a cell per photo
    // instead made the map shorter than the grid by one part-line per group - with a hundred
    // groups the bar and the grid were talking about different places, which is why the thumb
    // could not be taken hold of once photos were on screen (Cip, 2026-09-20).
    //
    // The heights themselves are learned ONCE from the grid, the first time a kind of row is
    // drawn, and never read again while it moves: reading them every frame changed the map under
    // the finger and the bar shivered. Until a kind has been seen, the theme's own sizes stand in.
    val density = LocalDensity.current
    val learned = remember { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .collect { items ->
                items.forEach { item ->
                    val row = currentRows.getOrNull(item.index) ?: return@forEach
                    val height = item.size.height
                    if (height <= 0) return@forEach
                    // Photos are kept under -1, whatever their level; headings under their own;
                    // how many photos stand side by side under -2, counted from photos alone.
                    val key = if (row is GridRow.Heading) row.level.coerceIn(0, HEADING_HEIGHTS.lastIndex) else -1
                    if (!learned.containsKey(key)) learned[key] = height.toFloat()
                    if (row !is GridRow.Heading) {
                        val wide = (item.column + 1).toFloat()
                        if ((learned[-2] ?: 0f) < wide) learned[-2] = wide
                    }
                }
            }
    }
    val fallbackCell = with(density) { 114.dp.toPx() }
    val fallbackHeadings = with(density) { HEADING_HEIGHTS.map { it.toPx() } }
    // What has been learned so far, taken as a set and then held while the finger is on the bar:
    // meeting a kind of heading for the first time mid-drag would otherwise redraw the map under
    // the finger, which is the one thing this map must never do.
    val heightsKey = learned.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}" }
    var heights by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    LaunchedEffect(heightsKey, dragging) { if (!dragging) heights = learned.toMap() }
    val cellPx = heights[-1] ?: fallbackCell
    // How many photos stand side by side, as counted off the grid itself and then left alone.
    val columns = (heights[-2] ?: 3f).toInt().coerceAtLeast(1)
    val tops = remember(rows, columns, heights, cellPx) {
        val out = FloatArray(rows.size + 1)
        var y = 0f
        var inLine = 0
        rows.forEachIndexed { i, row ->
            if (row is GridRow.Heading) {
                // A heading is a line of its own, and it closes whatever line was being filled.
                if (inLine > 0) { y += cellPx; inLine = 0 }
                out[i] = y
                val level = row.level.coerceIn(0, HEADING_HEIGHTS.lastIndex)
                y += heights[level] ?: fallbackHeadings[level]
            } else {
                // Every photo of a line stands at the line's own top: aiming inside a line is
                // then the offset the grid itself is given.
                out[i] = y
                inLine++
                if (inLine >= columns) { y += cellPx; inLine = 0 }
            }
        }
        if (inLine > 0) y += cellPx
        out[rows.size] = y
        out
    }
    // What the grid can actually travel: its content, plus the room it keeps above and below it
    // (the bottom one grows while photos are being picked, to leave the dock clear), less what
    // fits on screen. Leaving the padding out left the bar unable to reach the last line.
    val info = gridState.layoutInfo
    val viewportPx = info.viewportSize.height.toFloat()
    val paddingPx = (info.beforeContentPadding + info.afterContentPadding).toFloat()
    val travelledPx = tops[rows.size] + paddingPx - viewportPx
    val scrollable = travelledPx > 1f
    val scrollablePx = travelledPx.coerceAtLeast(1f)
    val topsNow by rememberUpdatedState(tops)
    val scrollableNow by rememberUpdatedState(scrollablePx)
    val cellNow by rememberUpdatedState(cellPx)
    val canTravel by rememberUpdatedState(scrollable)
    // Where the thumb stands, worked out WITHOUT being read here: the grid's first visible row and
    // its offset change on every frame of a scroll, and reading them in the body of this function
    // rebuilt the whole bar sixty times a second (Cip, 2026-09-20). Read inside a derived value,
    // and then only by the offset below - which is laid out, not recomposed.
    val fraction = remember(gridState) {
        derivedStateOf {
            val map = topsNow
            if (!canTravel || map.size < 2) 0f
            else {
                val lastRow = map.size - 2
                val px = if (dragging && aimed >= 0) map[aimed.coerceIn(0, lastRow)]
                else map[gridState.firstVisibleItemIndex.coerceIn(0, lastRow)] + gridState.firstVisibleItemScrollOffset
                (px / scrollableNow).coerceIn(0f, 1f)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            // Wide enough for the month bubble to be drawn beside the bar; only the narrow strip
            // inside it takes touches, so the rest of this box is see-through and tappable.
            .width(FAST_SCROLL_LABEL_ROOM),
    ) {
        val travel = maxHeight - FAST_SCROLL_THUMB
        val density = LocalDensity.current
        val travelPx = with(density) { travel.toPx() }
        val halfThumbPx = with(density) { FAST_SCROLL_THUMB.toPx() } / 2f
        val labelLiftPx = with(density) { FAST_SCROLL_LABEL_LIFT.toPx() }
        // A drag anywhere on the bar takes the thumb, wherever the finger landed.
        fun aimAt(y: Float) {
            val now = currentRows
            if (now.isEmpty()) return
            val last = now.lastIndex
            val at = if (travelPx <= 0f) 0f else ((y - halfThumbPx) / travelPx).coerceIn(0f, 1f)
            // Where in the whole height of the list the finger is standing, and the row that
            // holds that place - the row numbers are no longer evenly spread along the bar.
            val tops = topsNow
            val wanted = at * scrollableNow
            var lo = 0
            var hi = minOf(last, tops.size - 2)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tops[mid] <= wanted) lo = mid else hi = mid - 1
            }
            var target = lo.coerceIn(0, last)
            // A heading within reach of where the finger points takes it: what a thumb is aiming
            // at is a group, not the third photo inside it, and a folded group is too small a
            // mark to hit otherwise (Cip, 2026-09-20).
            // Bounded by the map as well as by the list: a group opening under the finger makes
            // the list longer than the map that was drawn when the drag began.
            val mapped = minOf(last, tops.size - 2)
            val near = (target - SNAP_ROWS).coerceAtLeast(0)..(target + SNAP_ROWS).coerceAtMost(mapped)
            near.minByOrNull { i ->
                if (now.getOrNull(i) !is GridRow.Heading) Float.MAX_VALUE else kotlin.math.abs(tops[i] - wanted)
            }?.let { i -> if (now.getOrNull(i) is GridRow.Heading && kotlin.math.abs(tops[i] - wanted) <= cellNow) target = i }
            val into = (wanted - tops[target]).coerceAtLeast(0f)
            // The same row, and the finger has barely moved inside it: nothing to do. Without the
            // second half of this the bar could only move a whole line at a time.
            if (target == aimed && kotlin.math.abs(into - lastInto) < 2f) return
            lastInto = into
            // Every heading between where the finger was and where it is now. Only a year and a
            // month are felt - a year firmly, a month faintly; days go by in silence, or a long
            // library would buzz without stopping (Cip, 2026-09-18).
            // A place aimed at in a longer list is brought inside this one. Only when the row
            // itself changes: sliding INSIDE a heading's own row would otherwise tap for every
            // pixel of it (Cip, 2026-09-20).
            if (target != aimed) {
                val from = if (aimed < 0) target else aimed.coerceAtMost(last)
                var crossedMonth = false
                var crossedYear = false
                for (i in minOf(from, target)..maxOf(from, target)) {
                    val row = now[i]
                    if (row !is GridRow.Heading) continue
                    if (row.level == 0) crossedYear = true else if (row.level == 1) crossedMonth = true
                }
                if (crossedYear || crossedMonth) Haptics.tick(view, crossedYear)
            }
            aimed = target
            // Onto the row, and into it by however much of it lies above the finger, so the list
            // follows the finger smoothly instead of hopping a whole row at a time. One scroll at
            // a time: a coroutine per finger movement left a queue of them fighting over the grid.
            scrollJob?.cancel()
            scrollJob = scope.launch { gridState.scrollToItem(target, into.roundToInt()) }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(FAST_SCROLL_WIDTH)
                .pointerInput(travelPx) {
                    try {
                        detectVerticalDragGestures(
                            onDragStart = { offset -> dragging = true; aimAt(offset.y) },
                            onDragEnd = { dragging = false; aimed = -1 },
                            onDragCancel = { dragging = false; aimed = -1 },
                            onVerticalDrag = { change, _ -> aimAt(change.position.y) },
                        )
                    } finally {
                        // Taken down mid-drag (the bar resized), no end or cancel is reported:
                        // the drag is over all the same, and its aim must not outlive it.
                        dragging = false
                        aimed = -1
                    }
                },
        )

        Box(
            Modifier
                // Placed in the layout pass, from the value above: moving the thumb costs no
                // recomposition of the bar or of anything else on the screen.
                .offset { IntOffset(0, (travelPx * fraction.value).roundToInt()) }
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .size(width = 16.dp, height = FAST_SCROLL_THUMB)
                .alpha(alpha)
                .background(if (dragging) p.accentFill else p.ink, Corner)
                .border(1.dp, if (dragging) p.accentFill else p.paper, Corner),
        )

        // While dragging, what the finger is standing on, so the jump is aimed rather than lucky.
        if (dragging) {
            // Walked backwards from the finger and stopped as soon as both are found: copying the
            // list and filtering it again for every frame of a drag was the scroller's own cost
            // (Cip, 2026-09-18).
            var heading: String? = null
            var day: String? = null
            var walk = aimed.coerceAtLeast(0).coerceAtMost(rows.lastIndex)
            while (walk >= 0 && (heading == null || day == null)) {
                val row = rows[walk]
                if (row is GridRow.Heading) {
                    // What the heading SAYS, never what it is called under the hood (Cip,
                    // 2026-09-20): laid out by place, people, tags, folder or camera, a group is
                    // named by a key of its own making, and the badge was showing that key.
                    // The count the heading carries is dropped, whether the group is open
                    // (" . 120") or folded away (" (120)"); only the name belongs in the badge.
                    if (row.level == 0 && heading == null) heading = headingLabel(row)
                    // The day under the month, so the bar says exactly where the finger is, not
                    // only which month it is passing (Cip, 2026-09-16).
                    if (row.level == 1 && day == null) day = headingLabel(row)
                }
                walk--
            }
            if (!heading.isNullOrBlank()) {
                Box(
                    Modifier
                        // Lifted clear of the thumb: under the finger it could not be read. Laid
                        // out from the same value as the thumb, so the two never disagree.
                        .offset { IntOffset(0, (travelPx * fraction.value - labelLiftPx).roundToInt().coerceAtLeast(0)) }
                        .align(Alignment.TopEnd)
                        .padding(end = FAST_SCROLL_WIDTH + 4.dp)
                        .background(p.accentFill, Corner)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onAccentFill, maxLines = 1)
                        if (!day.isNullOrBlank()) {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelSmall,
                                color = p.onAccentFill.copy(alpha = 0.75f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Taken between": the two days are chosen in a calendar, because a list of years cannot say
 * "that week in July". Built on `taken_at`, which every photo already carries, so this asks
 * nothing new of the index.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeValues(current: DateRange?, years: List<Int> = emptyList(), onChange: (DateRange?) -> Unit) {
    val p = LocalPalette.current
    var picking by remember { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(label = current?.label ?: stringResource(R.string.choose_dates), selected = current != null, onClick = { picking = true })
        if (current != null) {
            Chip(label = stringResource(R.string.any_date), selected = false, onClick = { onChange(null) })
        }
    }
    Spacer(Modifier.height(18.dp))

    if (picking) {
        // With years chosen above, the calendar offers those years and nothing else.
        val allowed = remember(years) {
            object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    if (years.isEmpty()) return true
                    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    c.timeInMillis = utcTimeMillis
                    return c.get(java.util.Calendar.YEAR) in years
                }

                override fun isSelectableYear(year: Int): Boolean = years.isEmpty() || year in years
            }
        }
        // Opened on the years that are filtered for, not on this month: filtering for 2025 and
        // then having to scroll a calendar back a year is work for nothing (Cip, 2026-09-18).
        val openAt = remember(years, current) {
            current?.fromUtcMillis ?: years.maxOrNull()?.let { year ->
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(year, java.util.Calendar.JANUARY, 1)
                }.timeInMillis
            }
        }
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = current?.fromUtcMillis,
            initialSelectedEndDateMillis = current?.toUtcMillis,
            initialDisplayedMonthMillis = openAt,
            selectableDates = allowed,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            colors = DatePickerDefaults.colors(containerColor = p.paper),
            confirmButton = {
                TextButton(
                    // One day chosen and not the other means that single day, rather than
                    // nothing at all.
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val from = state.selectedStartDateMillis
                        val to = state.selectedEndDateMillis ?: from
                        if (from != null && to != null) onChange(DateRange(minOf(from, to), maxOf(from, to)))
                        picking = false
                    },
                ) { Text(stringResource(R.string.apply), color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel), color = p.muted) }
            },
        ) {
            DateRangePicker(
                state = state,
                title = { Text(stringResource(R.string.taken_between), style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.padding(start = 20.dp, top = 16.dp)) },
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = p.paper,
                    selectedDayContainerColor = p.accentFill,
                    selectedDayContentColor = p.onAccentFill,
                    dayInSelectionRangeContainerColor = p.chip,
                    todayDateBorderColor = p.accent,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One on / off filter of the filter sheet: on keeps only the photos that have the thing, off
 * shows them all (Cip, 2026-09-17: switches, not "has / has not" chips).
 */
@Composable
private fun FilterSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accentFill, checkedThumbColor = p.onAccentFill, uncheckedTrackColor = p.chip, uncheckedBorderColor = p.hairline, uncheckedThumbColor = p.muted),
        )
    }
}

/**
 * The photo, full screen, at its own size - and the rest of the results either side of it.
 *
 * A tap used to hand the photo straight to the gallery, which left the result set behind: swiping
 * there walked the camera roll, not the photos you had just searched for. Here the swipe follows
 * the order the results came back in, and the gallery is one tap away when you want it.
 *
 * A tap on the picture shows the actions; a swipe up opens the same sheet a long press on the
 * grid opens (Cip, 2026-09-16).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoViewer(
    hits: List<PhotoHit>,
    start: Int,
    onClose: () -> Unit,
    /** The details, given the photo, a way to close them, and a way to open the tags. */
    onSheet: @Composable (PhotoHit, () -> Unit, (PhotoHit) -> Unit) -> Unit,
    onEditSheet: @Composable (PhotoHit, () -> Unit) -> Unit,
    /**
     * The system bars as the SCREEN sees them, not as the dialog does. Inside a dialog window
     * some phones report nothing at all, and the labels under the icons ended up below the edge
     * of the screen - on a Poco they disappeared entirely (Cip, 2026-09-16).
     */
    topInset: Dp,
    bottomInset: Dp,
    onNeedMore: () -> Unit,
    onDelete: (PhotoHit) -> Unit,
    /** For the actions that lead elsewhere: similar photos, the map, photos nearby. */
    viewModel: AppViewModel,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { hits.size }
    val view = LocalView.current
    var showActions by remember { mutableStateOf(true) }
    // How far the photo on screen is magnified, and where it is held - kept HERE rather than per
    // page. Writing it from inside a page meant a state write during composition, which asks for
    // another composition, on every frame of a drag: moving a magnified photo about crawled
    // (Cip, 2026-09-16). Swiping to another photo starts it whole again.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(pager.currentPage) { zoomJob?.cancel(); scale = 1f; offset = Offset.Zero }
    // The details of the photo being looked at, opened over it rather than in its place: a swipe
    // up must not take the picture away (Cip, 2026-09-16).
    var sheetFor by remember { mutableStateOf<PhotoHit?>(null) }
    // Editing happens over the picture too: closing it puts you back on the photo you were
    // looking at, not on a sheet halfway there (Cip, 2026-09-16).
    var editFor by remember { mutableStateOf<PhotoHit?>(null) }

    // Swiping towards the end asks for the next page, so the viewer runs as far as the results do.
    LaunchedEffect(pager.currentPage, hits.size) {
        if (pager.currentPage >= hits.size - 3) onNeedMore()
    }
    // Nothing left to show (the last photo was deleted): close rather than stand on an empty page.
    LaunchedEffect(hits.size) { if (hits.isEmpty()) onClose() }

    // How far the picture has been dragged down, and how far it must go before it closes.
    val dismiss = remember { Animatable(0f) }
    // How far the finger has travelled upwards on a picture that is not being carried down.
    val upBy = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // decorFitsSystemWindows = false, or the window reports no system bars at all and the
    // labels under the icons are cut off by its own edge (Cip, 2026-09-16).
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val overflow = viewerWindowOverflow()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val closeAt = with(LocalDensity.current) { maxHeight.toPx() } * VIEWER_DISMISS_SHARE
            // The ground fades as the picture is carried down, so the grid shows through.
            val shade = (1f - (kotlin.math.abs(dismiss.value) / (closeAt * 2f))).coerceIn(0.35f, 1f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade))) {
            HorizontalPager(
                state = pager,
                // While a photo is magnified the finger belongs to it, not to the next photo.
                userScrollEnabled = scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val hit = hits.getOrNull(page) ?: return@HorizontalPager
                val uri = remember(hit.mediaId) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId)
                }
                AsyncImage(
                    // No size: the original, not the thumbnail the grid is drawn from.
                    model = ImageRequest.Builder(context).data(uri).setParameter("bytes", hit.sizeBytes).crossfade(true).build(),
                    contentDescription = hit.meaning.ifBlank { hit.fileName },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(hit.id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1) {
                                        // Magnify about the point BETWEEN the fingers, not about
                                        // the middle of the picture: whatever is under the hand
                                        // stays under it, wherever on the photo it is pinched
                                        // (Cip, 2026-09-16).
                                        //
                                        // A point sits at centre + (p - centre) * scale + offset.
                                        // Holding the point under the centroid still while the
                                        // scale goes from s to s' (k = s'/s) gives
                                        // offset' = d * (1 - k) + offset * k, with d the centroid
                                        // measured from the middle.
                                        zoomJob?.cancel()
                                        val next = (scale * event.calculateZoom()).coerceAtLeast(1f)
                                        if (next <= 1.01f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            val k = next / scale
                                            val centre = Offset(size.width / 2f, size.height / 2f)
                                            val d = event.calculateCentroid(useCurrent = true) - centre
                                            val moved = d * (1f - k) + offset * k + event.calculatePan()
                                            val limitX = size.width * (next - 1f) / 2f
                                            val limitY = size.height * (next - 1f) / 2f
                                            scale = next
                                            offset = Offset(
                                                moved.x.coerceIn(-limitX, limitX),
                                                moved.y.coerceIn(-limitY, limitY),
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (scale > 1f) {
                                        // One finger on a magnified photo moves it, handled HERE
                                        // rather than by a drag detector of its own: a detector
                                        // waits for its own slop before it starts, so the picture
                                        // fell behind the finger every time the hand changed
                                        // direction, and the whole thing felt slow (Cip, 2026-09-16).
                                        val pan = event.calculatePan()
                                        if (pan != Offset.Zero) {
                                            val limitX = size.width * (scale - 1f) / 2f
                                            val limitY = size.height * (scale - 1f) / 2f
                                            offset = Offset(
                                                (offset.x + pan.x).coerceIn(-limitX, limitX),
                                                (offset.y + pan.y).coerceIn(-limitY, limitY),
                                            )
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(hit.id) {
                            detectTapGestures(
                                // Google Photos' gesture: in on the spot you tapped, out again.
                                // Animated, the pinch is not (Cip, 2026-09-17): the jump read as
                                // unpolished, a finger on the glass already moves at its own pace.
                                onDoubleTap = { at ->
                                    val fromScale = scale
                                    val fromOffset = offset
                                    val toScale: Float
                                    val toOffset: Offset
                                    if (scale > 1f) {
                                        toScale = 1f
                                        toOffset = Offset.Zero
                                    } else {
                                        toScale = VIEWER_DOUBLE_TAP_SCALE
                                        val centre = Offset(size.width / 2f, size.height / 2f)
                                        val limitX = size.width * (VIEWER_DOUBLE_TAP_SCALE - 1f) / 2f
                                        val limitY = size.height * (VIEWER_DOUBLE_TAP_SCALE - 1f) / 2f
                                        val wanted = (centre - at) * (VIEWER_DOUBLE_TAP_SCALE - 1f)
                                        toOffset = Offset(
                                            wanted.x.coerceIn(-limitX, limitX),
                                            wanted.y.coerceIn(-limitY, limitY),
                                        )
                                    }
                                    zoomJob?.cancel()
                                    zoomJob = scope.launch {
                                        androidx.compose.animation.core.animate(
                                            0f, 1f,
                                            animationSpec = tween(VIEWER_DOUBLE_TAP_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                        ) { t, _ ->
                                            scale = fromScale + (toScale - fromScale) * t
                                            offset = fromOffset + (toOffset - fromOffset) * t
                                        }
                                    }
                                },
                                onTap = { showActions = !showActions },
                            )
                        }
                        // One finger, and only ONE detector for it, chosen by whether the
                        // picture is magnified. Two of them competing meant the one that crossed
                        // its threshold first consumed the drag, and moving about a magnified
                        // photo up and down was a matter of luck (Cip, 2026-09-16).
                        .pointerInput(hit.id, scale > 1f) {
                            if (scale <= 1f) {
                                // Whole: down carries the picture away to leave, up opens its
                                // details. Sideways is left alone, so the pager still has it.
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        if (amount < 0 && dismiss.value == 0f) {
                                            // The first hint that the details are on their way up.
                                            if (upBy.value == 0f) Haptics.tick(view, strong = false)
                                            upBy.value -= amount
                                        }
                                        scope.launch { dismiss.snapTo((dismiss.value + amount).coerceAtLeast(0f)) }
                                    },
                                    onDragEnd = {
                                        // Up, unmagnified: the details, over the picture, which stays.
                                        if (dismiss.value == 0f && upBy.value > VIEWER_SWIPE_UP) {
                                            // It arrived: a second tap, so the hand knows.
                                            Haptics.tick(view, strong = false)
                                            sheetFor = hit
                                        }
                                        upBy.value = 0f
                                        if (dismiss.value > closeAt) {
                                            // Carried away for good: the firmer one.
                                            Haptics.tick(view, strong = true)
                                            scope.launch {
                                                dismiss.animateTo(closeAt * 4f, tween(160))
                                                onClose()
                                            }
                                        } else {
                                            scope.launch { dismiss.animateTo(0f, spring()) }
                                        }
                                    },
                                    onDragCancel = {
                                        upBy.value = 0f
                                        scope.launch { dismiss.animateTo(0f, spring()) }
                                    },
                                )
                            }
                        }
                        // graphicsLayer LAST, after every gesture: a pointer area placed INSIDE a
                        // scaled layer is measured in that layer's own coordinates, so a finger
                        // crossing 300px of screen reported 300/scale, and the more the photo was
                        // magnified the slower it moved (Cip, 2026-09-16).
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y + (if (page == pager.currentPage) dismiss.value else 0f)
                        },
                )
            }

            val hit = hits.getOrNull(pager.currentPage)
            // Nothing is written over the photo: the counter that used to sit at the top is gone
            // (Cip, 2026-09-20). A photo shown whole is shown whole.
            if (showActions && hit != null) {
                // A quiet hint that the photo has more to say: three chevrons drifting upwards
                // over the action bar, faintest at the top (Cip, 2026-09-16).
                // Only while the photo is whole: magnified, a swipe up moves the picture, so the
                // hint would be promising something that does not happen (Cip, 2026-09-16).
                if (scale <= 1.01f) {
                val drift = rememberInfiniteTransition(label = "swipeHint")
                val rise by drift.animateFloat(
                    initialValue = 0f,
                    targetValue = -6f,
                    animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                    label = "swipeHintRise",
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = maxOf(bottomInset, VIEWER_MIN_BOTTOM) + overflow + 104.dp)
                        .graphicsLayer { translationY = rise * density },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf(0.16f, 0.30f, 0.55f).forEach { shade ->
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = shade),
                            modifier = Modifier.size(20.dp).offset(y = 6.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.details),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        // The room below is whatever this phone actually reserves, and never
                        // less than VIEWER_MIN_BOTTOM: a gesture bar, a chin, a cutout - the
                        // labels have to clear all of them, on any phone (Cip, 2026-09-16).
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = maxOf(bottomInset, VIEWER_MIN_BOTTOM) + overflow + 12.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Every action of a photo lives here, and only here: the details below the
                    // photo carry none (Cip, 2026-09-17). Icons without words, a little bigger.
                    // The ones that lead elsewhere close the photo first.
                    ViewerAction(stringResource(R.string.act_tag), R.drawable.ic_tag) { editFor = hit }
                    ViewerAction(stringResource(R.string.act_gallery), R.drawable.ic_open) { Actions.openPhoto(context, hit) }
                    ViewerAction(stringResource(R.string.act_similar), R.drawable.ic_duplicates) { onClose(); viewModel.showSimilar(hit) }
                    ViewerAction(stringResource(R.string.act_share), R.drawable.ic_share) { Actions.sharePhotos(context, listOf(hit)) }
                    hit.latLon?.let { (lat, lon) ->
                        ViewerAction(stringResource(R.string.act_map), R.drawable.ic_map) { onClose(); viewModel.openMap(MapFocus(lat, lon, 15.0)) }
                        ViewerIconAction(stringResource(R.string.act_nearby), Icons.Filled.LocationOn) { onClose(); viewModel.searchNear(lat, lon, 5.0) }
                    }
                    ViewerAction(stringResource(R.string.act_delete), R.drawable.ic_delete) { onDelete(hit) }
                }
            }
            // Closing the details is felt too, the firmer one, as opening them was (Cip, 2026-09-16).
            // Always the photo as it is now in the results, never the copy taken when the sheet was
            // opened: after a save the old copy lacked the new names (Cip, 2026-09-17).
            sheetFor?.let { held ->
                val photo = hits.firstOrNull { it.id == held.id } ?: held
                onSheet(
                    photo,
                    { Haptics.tick(view, strong = true); sheetFor = null },
                    { editFor = it },
                )
            }
            // Leaving the tags puts the photo's details back, where they were entered from -
            // and with whatever was just saved showing on them (Cip, 2026-09-16).
            editFor?.let { held ->
                val photo = hits.firstOrNull { it.id == held.id } ?: held
                onEditSheet(photo) {
                    editFor = null
                    sheetFor = photo
                }
            }
            }
        }
    }
}

/**
 * One action under the full screen photo: a framed icon on the dark bar, no word under it (Cip,
 * 2026-09-17); [label] is what a screen reader says.
 */
@Composable
private fun RowScope.ViewerAction(label: String, icon: Int, onClick: () -> Unit) {
    ViewerActionFrame(onClick) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** The same action as [ViewerAction], for an icon that is a vector rather than a drawable. */
@Composable
private fun RowScope.ViewerIconAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ViewerActionFrame(onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** The frame every action under the full screen photo sits in. */
@Composable
private fun RowScope.ViewerActionFrame(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(Corner)
            // Framed like every other action in the app, so they read as buttons on the picture.
            .border(1.dp, Color.White.copy(alpha = 0.45f), Corner)
            .combinedClickableCompat(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The values of one filter group: tap a value to select it, tap again to clear it. The heading
 * above it is drawn by FilterGroup, so every group of the sheet looks the same.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetValues(
    values: List<FacetValue>?,
    selected: Set<String>,
    label: (String) -> String = { it },
    onToggle: (String) -> Unit,
) {
    if (values.isNullOrEmpty() && selected.isEmpty()) return
    val view = LocalView.current
    val all = values.orEmpty().ifEmpty { selected.map { FacetValue(it, 0) } }
    if (all.size > FACET_SEARCH_OVER) {
        FacetSearch(all, selected, label, onToggle)
        return
    }
    // Long lists (the CLIP words) start with the most frequent values; "Show all" opens the rest.
    var expanded by remember(values) { mutableStateOf(false) }
    val shown = if (expanded || all.size <= FACET_PREVIEW) all else all.take(FACET_PREVIEW) + all.drop(FACET_PREVIEW).filter { it.value in selected }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { facet ->
            Chip(
                label = if (facet.count > 0) "${label(facet.value)} (${Actions.formatCount(facet.count.toLong())})" else label(facet.value),
                selected = facet.value in selected,
                onClick = {
                    Haptics.tick(view, strong = facet.value !in selected)
                    onToggle(facet.value)
                },
            )
        }
        if (all.size > FACET_PREVIEW) {
            Chip(label = if (expanded) stringResource(R.string.show_fewer) else stringResource(R.string.show_all, Actions.formatCount(all.size.toLong())), selected = false, onClick = { expanded = !expanded })
        }
    }
    Spacer(Modifier.height(18.dp))
}

/**
 * A filter with too many values to lay out as chips (Cip, 2026-09-18): the values picked so far
 * as chips, to take off with a tap, and under them a small search field. Tapping it lists every
 * value; typing narrows the list; each tap on a value puts it on or takes it off and the list
 * stays open, so several can be picked one after another, as with tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetSearch(
    all: List<FacetValue>,
    selected: Set<String>,
    label: (String) -> String,
    onToggle: (String) -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var text by remember(all) { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    // Folded once per list, not once per keystroke for every value.
    val folded = remember(all) { all.map { com.opensolr.photos.data.Words.fold(label(it.value)) } }
    // Nothing typed: the most frequent values, a short list to start from. Typed: the values
    // that contain it. Either way no more than FACET_SEARCH_ROWS are drawn (Cip, 2026-09-18).
    val matches = remember(all, text) {
        val needle = com.opensolr.photos.data.Words.fold(text)
        if (needle.isEmpty()) all.sortedByDescending { it.count }
        else all.filterIndexed { i, _ -> folded[i].contains(needle) }
    }
    if (selected.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { value ->
                val count = all.firstOrNull { it.value == value }?.count ?: 0
                Chip(
                    label = if (count > 0) "${label(value)} (${Actions.formatCount(count.toLong())})" else label(value),
                    selected = true,
                    onClick = {
                        Haptics.tick(view, strong = false)
                        onToggle(value)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (open) p.accent else p.hairline, Corner)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(stringResource(R.string.search_values, Actions.formatCount(all.size.toLong())), style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = p.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { open = it.isFocused },
            )
        }
        if (open) {
            Text(
                stringResource(R.string.done),
                style = MaterialTheme.typography.labelSmall,
                color = p.accent,
                modifier = Modifier.clickable { text = ""; focus.clearFocus() }.padding(start = 8.dp),
            )
        }
    }
    if (open) {
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .background(p.paper, Corner)
                .border(1.dp, p.hairline, Corner)
                .verticalScroll(rememberScrollState()),
        ) {
            if (matches.isEmpty()) {
                Text(stringResource(R.string.no_value), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            // Never all of them: a list of thousands would be thousands of rows built for a finger
            // that reads twenty.
            matches.take(FACET_SEARCH_ROWS).forEach { facet ->
                val on = facet.value in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Haptics.tick(view, strong = !on)
                            onToggle(facet.value)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${label(facet.value)} (${Actions.formatCount(facet.count.toLong())})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) p.accent else p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (on) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected), tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
            if (matches.size > FACET_SEARCH_ROWS) {
                Text(
                    stringResource(R.string.more_values, Actions.formatCount((matches.size - FACET_SEARCH_ROWS).toLong())),
                    style = MaterialTheme.typography.bodySmall, color = p.muted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

/**
 * How a facet value reads on a chip: folders without the trailing slash, orientations capitalised.
 */
private fun facetLabel(context: android.content.Context, field: String, value: String): String = when (field) {
    "folder" -> value.trimEnd('/')
    "orientation" -> when (value) {
        "landscape" -> context.getString(R.string.orientation_landscape)
        "portrait" -> context.getString(R.string.orientation_portrait)
        "square" -> context.getString(R.string.orientation_square)
        else -> value.replaceFirstChar { it.uppercase() }
    }
    else -> value
}

/**
 * The title of a facet's group on the filter sheet, in the app's language. The English title in
 * SearchFilters.FACETS stays the key the open groups are remembered by.
 */
@Composable
private fun facetTitle(field: String): String = when (field) {
    "year" -> stringResource(R.string.facet_year)
    "city" -> stringResource(R.string.facet_city)
    "country" -> stringResource(R.string.facet_country)
    "camera_model" -> stringResource(R.string.facet_camera_model)
    "custom_tags" -> stringResource(R.string.facet_custom_tags)
    "labels" -> stringResource(R.string.facet_labels)
    "orientation" -> stringResource(R.string.facet_orientation)
    "persons_ss" -> stringResource(R.string.facet_persons)
    else -> SearchFilters.FACETS.toMap()[field] ?: field
}

/**
 * Long-press details: the photo, when and with what it was taken, where, and the words it was
 * read into.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DetailsSheet(
    /** Called by the actions that take the viewer somewhere else, so it can close first. */
    onLeave: () -> Unit = {},hit: PhotoHit, viewModel: AppViewModel, onDismiss: () -> Unit, onEdit: (PhotoHit) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    // Putting the photo somewhere else: the map, then Android's permission to write the file, then
    // the position into the file and everywhere else (Cip, 2026-09-19). Declined, nothing changes.
    var picking by remember { mutableStateOf(false) }
    var pendingPlace by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val placeWriter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val point = pendingPlace
        pendingPlace = null
        if (point != null && result.resultCode == Activity.RESULT_OK) {
            val written = Actions.contentUris(context, listOf(hit)).firstOrNull()
                ?.let { com.opensolr.photos.media.PhotoReader.writeGps(context, it, hit.mime, point.first, point.second) } ?: false
            viewModel.setPlace(listOf(hit), point.first, point.second, if (written) setOf(hit.id) else emptySet())
            onDismiss()
        }
    }
    fun savePlace(lat: Double, lon: Double) {
        picking = false
        val file = Actions.contentUris(context, listOf(hit)).firstOrNull()
        when {
            file == null || !com.opensolr.photos.media.PhotoReader.canWriteExif(hit.mime) -> {
                viewModel.setPlace(listOf(hit), lat, lon, emptySet())
                onDismiss()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingPlace = lat to lon
                placeWriter.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(file)).intentSender).build())
            }
            else -> {
                val written = com.opensolr.photos.media.PhotoReader.writeGps(context, file, hit.mime, lat, lon)
                viewModel.setPlace(listOf(hit), lat, lon, if (written) setOf(hit.id) else emptySet())
                onDismiss()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.paper, shape = Corner) {
        // The map belongs to the sheet, not beside it: opened as a sibling its window was made
        // before the sheet's and the sheet could sit over its buttons on some phones, which is
        // why moving a single photo misbehaved where moving many did not (Cip, 2026-09-20).
        if (picking) {
            PlacePickerDialog(start = hit.latLon, viewModel = viewModel, onDismiss = { picking = false }, onPick = { lat, lon -> savePlace(lat, lon) })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId)
            // A small picture, and beside it the plate a camera shows for a frame: what the
            // file is, where it sits, when it was taken, on what, how big, and how it was shot.
            // Nothing below repeats any of it (Cip, 2026-09-16).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(104.dp).clip(Corner).background(p.chip)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(uri).size(360).setParameter("bytes", hit.sizeBytes).build(),
                        contentDescription = hit.meaning,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        hit.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = p.ink,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                    )
                    Spacer(Modifier.height(4.dp))
                    val plate = listOfNotNull(
                        hit.folder.trim('/').ifBlank { null },
                        Actions.formatSolrDate(hit.takenAt),
                        listOfNotNull(hit.cameraMake, hit.cameraModel).joinToString(" ").takeIf { it.isNotBlank() },
                        hit.lens,
                        listOfNotNull(
                            if (hit.width != null && hit.height != null) "${hit.width} × ${hit.height}" else null,
                            Actions.formatFileSize(hit.sizeBytes).takeIf { it.isNotBlank() },
                        ).joinToString("  ").takeIf { it.isNotBlank() },
                        listOfNotNull(
                            hit.fNumber?.let { "f/" + String.format(java.util.Locale.US, "%.1f", it) },
                            hit.exposure,
                            hit.iso?.let { "ISO $it" },
                            hit.focalLength?.let { String.format(java.util.Locale.US, "%.0f mm", it) },
                        ).joinToString("  ").takeIf { it.isNotBlank() },
                    )
                    plate.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            maxLines = 2,
                            textAlign = TextAlign.End,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Where it was taken, as a button that opens the map to put it somewhere else
            // (Cip, 2026-09-19); a photo with no position gets the same button to give it one.
            SectionLabel(stringResource(R.string.sec_place))
            Spacer(Modifier.height(10.dp))
            PlaceButton(hit.placeLabel ?: stringResource(R.string.add_place), onClick = { picking = true })
            Spacer(Modifier.height(16.dp))
            // People first: the names are what most owners look for, and each name reads as its
            // own thing rather than as one run-on line (Cip, 2026-09-18). Tapping any of them
            // opens the editor, as a tag does.
            val people = hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (people.isNotEmpty()) {
                SectionLabel(stringResource(R.string.sec_people))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEach { Chip(label = it, selected = true, onClick = { onEdit(hit) }) }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (hit.customTags.isNotEmpty()) {
                SectionLabel(stringResource(R.string.sec_my_tags))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hit.customTags.forEach { Chip(label = it, selected = true, onClick = { onEdit(hit) }) }
                }
                Spacer(Modifier.height(16.dp))
            }
            // Plain text, not chips: there are a lot of these words, they are edited as one piece
            // of writing anyway, and as chips they filled the sheet (Cip, 2026-09-18).
            SectionLabel(stringResource(R.string.sec_shows))
            Text(hit.meaning.ifBlank { stringResource(R.string.no_words_yet) }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = p.ink, modifier = Modifier.padding(vertical = 12.dp))
            // What was read printed IN the photo - a receipt, a label, a screenshot. Shown apart
            // from the words above, which say what the photo is OF (Cip, 2026-09-16).
            if (hit.ocrText.isNotBlank()) {
                SectionLabel(stringResource(R.string.sec_printed))
                Text(hit.ocrText, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The square tick of a heading, a photo or an album: high contrast on both themes (outlined in the
 * theme's own ink, filled with the accent when it is on) and a tap target of its own, wider than
 * the square, so the tick itself answers a finger (Cip, 2026-09-18).
 */
@Composable
internal fun PickTick(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    val p = LocalPalette.current
    val view = LocalView.current
    Box(
        modifier
            .combinedClickableCompat { Haptics.tick(view, strong = !selected); onClick() }
            // On a heading the tap area grows sideways only: taller and every title would jump
            // the moment picking started (Cip, 2026-09-18).
            .padding(horizontal = if (dense) 10.dp else 6.dp, vertical = if (dense) 0.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (selected) p.accentFill else p.paper.copy(alpha = 0.55f), Corner)
                .border(2.dp, if (selected) p.accentFill else p.ink, Corner),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccentFill, modifier = Modifier.size(16.dp))
        }
    }
}

/** Facet values shown before "Show all". */
private const val FACET_PREVIEW = 10

/** Above this many values a filter is a search field instead of chips (Cip, 2026-09-18). */
private const val FACET_SEARCH_OVER = 50

/** How many values the search field lists at once. */
private const val FACET_SEARCH_ROWS = 50

/** Sync phases during which the index is being rebuilt and search is unavailable. */
private val REBUILD_PHASES = setOf("Resetting your index", "Updating the index configuration", "Rebuilding your index")

/**
 * A plain click without the long-press semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/**
 * How many rows either side of the finger the bar looks at for a heading to settle on. A group is
 * what a thumb aims for; landing three photos into one is landing nowhere.
 */
private const val SNAP_ROWS = 4

/**
 * How tall a group heading stands, by its level, as the bar's map of the grid reckons it: the
 * padding and the type of the row as they are laid out above. Worked out rather than measured, so
 * that the map never changes while the finger is on the bar.
 */
private val HEADING_HEIGHTS = listOf(46.dp, 40.dp, 38.dp)

/**
 * What a group heading says, without the count it carries: the badge on the scroll bar names the
 * group the finger is standing on, and a group laid out by place, people, tags, folder or camera
 * is named under the hood by a key ("folder:Pictures/2019") that nobody should ever read.
 */
private fun headingLabel(row: GridRow.Heading): String =
    row.text.substringBefore(" \u00b7 ").substringBefore(" (").trim().ifBlank { row.name }

/** The mark of a photo the phone could not read: a red frame and a red "!" (Cip, 2026-09-17). */
private val SkippedRed = Color(0xFFE53E3E)

/**
 * The band behind a group heading (a month at level 0, a day under it), on the photos grid and in
 * the albums alike: the app's accent, faint, stronger for a month than for a day. The text on it is
 * the theme's own ink, so it reads the same on light and dark (Cip, 2026-09-17).
 */
@Composable
internal fun headingBand(level: Int): Color =
    LocalPalette.current.accent.copy(alpha = when (level) {
        0 -> 0.18f
        1 -> 0.11f
        else -> 0.06f
    })

/**
 * The type of a group heading: a month (level 0) or a day under it. A touch under the title
 * sizes they had, which read too big (Cip, 2026-09-17). Shared with the album sections.
 */
@Composable
internal fun headingStyle(level: Int): androidx.compose.ui.text.TextStyle =
    when (level) {
        0 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp)
        1 -> MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)
        // A day is the one a thumb aims at most often, so it stays big enough to hit and to read
        // (Cip, 2026-09-18).
        else -> MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp)
    }

/**
 * Picking photos by dragging, as every gallery does (Cip, 2026-09-20): press and hold a photo,
 * keep the finger down, and everything between that photo and the one under the finger is ticked
 * as it travels. Dragging back up unticks what the drag itself ticked. Near the top or the bottom
 * edge the grid scrolls itself, so a selection can run past what is on screen.
 *
 * The press is taken here, on the grid, rather than on each photo: a photo's own long press ends
 * the moment it fires, and what is wanted is a gesture that goes on while the finger is down. The
 * events are taken in the first pass and consumed from the long press onwards, so neither the
 * grid's own scrolling nor the photo underneath sees them - which is what keeps the list still
 * while the finger picks, and what keeps a lifted finger from also counting as a tap.
 *
 * Nothing is consumed before the press is recognised, so an ordinary tap and an ordinary scroll
 * behave exactly as they did.
 *
 * @param rowsNow the rows the grid is drawing right now, read at the moment they are needed:
 *                the list is rebuilt while a drag is running (every tick changes the state) and
 *                a captured copy would go stale under the finger.
 */
private fun Modifier.dragSelect(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    rowsNow: () -> List<GridRow>,
    onStart: (String) -> Unit,
    onRange: (List<String>, Boolean) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    // Where the finger is, while it is down: the edge scroller reads it on every frame.
    val at = remember { mutableStateOf<Offset?>(null) }
    val anchorIndex = remember { mutableStateOf<Int?>(null) }
    val edgePx = with(LocalDensity.current) { DRAG_EDGE.toPx() }
    val stepPx = with(LocalDensity.current) { DRAG_STEP.toPx() }

    /** The row under [point], or the last one above it when the point is in a gap. */
    fun rowIndexAt(point: Offset): Int? {
        val items = gridState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return null
        items.forEach { item ->
            val withinY = point.y >= item.offset.y && point.y <= item.offset.y + item.size.height
            val withinX = point.x >= item.offset.x && point.x <= item.offset.x + item.size.width
            if (withinY && withinX) return item.index
        }
        // Between two photos of a row, or out to the side: the last one that starts above the
        // finger, so the range never stalls while the finger is in a gap.
        return items.lastOrNull { it.offset.y <= point.y }?.index ?: items.first().index
    }

    /** Every photo between the row the drag began on and the row under the finger. */
    fun idsTo(point: Offset): List<String>? {
        val anchor = anchorIndex.value ?: return null
        val here = rowIndexAt(point) ?: return null
        val rows = rowsNow()
        val from = minOf(anchor, here).coerceAtLeast(0)
        val to = maxOf(anchor, here).coerceAtMost(rows.lastIndex)
        if (from > to) return null
        // Headings are skipped: a drag picks photos, and a group is taken whole by its own tick.
        return (from..to).mapNotNull { (rows.getOrNull(it) as? GridRow.Photo)?.hit?.id }
    }

    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A press that moves, or lets go, before the time is up is a scroll or a tap: left
            // alone entirely, unconsumed, for the grid and the photo to deal with as always.
            val left = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var moved = false
                while (!moved) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!change.pressed) return@withTimeoutOrNull true
                    moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            if (left != null) return@awaitEachGesture
            val startIndex = rowIndexAt(down.position) ?: return@awaitEachGesture
            val startId = (rowsNow().getOrNull(startIndex) as? GridRow.Photo)?.hit?.id ?: return@awaitEachGesture
            anchorIndex.value = startIndex
            at.value = down.position
            onStart(startId)

            // The grid scrolls itself while the finger rests near an edge, and the range is
            // worked out again on every step, since the photos move under the finger.
            var covered = 1
            var movedAway = false
            val scrolling = scope.launch {
                while (true) {
                    val point = at.value ?: break
                    val height = gridState.layoutInfo.viewportSize.height.toFloat()
                    val speed = when {
                        point.y < edgePx -> -(edgePx - point.y) / edgePx * stepPx
                        point.y > height - edgePx -> (point.y - (height - edgePx)) / edgePx * stepPx
                        else -> 0f
                    }
                    if (speed != 0f) {
                        gridState.scrollBy(speed)
                        idsTo(point)?.let { ids ->
                            if (ids.size != covered) {
                                covered = ids.size
                                movedAway = true
                                // Quietly: the finger is standing still, the grid is doing the
                                // travelling, and a tap per photo at sixty a second is a buzz.
                                onRange(ids, false)
                            }
                        }
                    }
                    kotlinx.coroutines.delay(16)
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    change.consume()
                    at.value = change.position
                    val ids = idsTo(change.position) ?: continue
                    if (ids.size != covered) {
                        covered = ids.size
                        movedAway = true
                        onRange(ids, true)
                    }
                }
            } finally {
                scrolling.cancel()
                at.value = null
                anchorIndex.value = null
                onEnd(movedAway)
            }
        }
    }
}

/** How close to an edge the finger has to be for the grid to start scrolling under it. */
private val DRAG_EDGE = 72.dp

/** How far the grid scrolls per frame when the finger sits right at the edge. */
private val DRAG_STEP = 14.dp
