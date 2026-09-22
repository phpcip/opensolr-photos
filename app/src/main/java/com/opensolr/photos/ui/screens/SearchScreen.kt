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
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
    var showFilters by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PhotoHit?>(null) }

    var viewing by remember { mutableStateOf<PhotoHit?>(null) }
    val view = LocalView.current

    val writePlaces = rememberPlaceWriter(viewModel)

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

    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val topInset = systemBars.calculateTopPadding()
    val bottomInset = systemBars.calculateBottomPadding()
    var editing by remember { mutableStateOf<PhotoHit?>(null) }

    var bulkTagging by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val gridWords = GridWords(stringResource(R.string.best_matches), stringResource(R.string.also_similar), stringResource(R.string.of_the_same))

    val selectLabel = stringResource(R.string.cd_select_photo)
    val rows = remember(state.hits, state.searchedQuery, state.duplicateGroups, state.collapsedHeadings, state.skeleton, state.duplicatesMode, state.skippedMode, state.resultGroups) {
        if (state.resultGroups.isNotEmpty() && (!state.duplicatesMode || state.similarToId != null) && !state.skippedMode) {
            buildResultGroupRows(state.resultGroups, state.hits, state.collapsedHeadings)
        } else if (state.skeleton.isNotEmpty() && state.searchedQuery.isBlank() && state.duplicateGroups.isEmpty() && !state.duplicatesMode && !state.skippedMode) {
            buildSkeletonRows(state.skeleton, state.hits, state.collapsedHeadings)
        } else buildRows(state.hits, byDate = state.searchedQuery.isBlank(), groups = state.duplicateGroups, collapsed = state.collapsedHeadings, words = gridWords)
    }

    var searchOpen by remember { mutableStateOf(false) }

    val searchBarVisible = searchOpen || state.query.isNotBlank()
    val searchFocus = remember { FocusRequester() }

    var suggestionsHidden by remember { mutableStateOf(false) }
    LaunchedEffect(state.query) { suggestionsHidden = false }
    val outside = com.opensolr.photos.ui.rememberOutsideTap(onOutside = { suggestionsHidden = true })

    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showOperators by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.removeDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    val deleteScope = rememberCoroutineScope()
    val deleteSelected = {
        val chosen = viewModel.photosToTag()
        pendingDelete = chosen.map { it.id }.toSet()

        deleteScope.launch {
            val sender = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Actions.deleteRequest(context, Actions.contentUris(context, chosen))
            }
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {

                viewModel.removeDeleted(pendingDelete)
                pendingDelete = emptySet()
            }
        }
        Unit
    }

    val nearEnd by remember(rows) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

            rows.lastOrNull() is GridRow.Photo && last >= gridState.layoutInfo.totalItemsCount - PREFETCH_REMAINING
        }
    }

    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.restoreGeneration) {
        if (rows.isEmpty()) return@LaunchedEffect

        val byKey = state.gridKey?.let { key -> rows.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }

        val target = byKey ?: state.gridIndex.takeIf { it <= rows.lastIndex } ?: 0
        gridState.scrollToItem(target.coerceAtLeast(0), if (byKey != null) state.gridOffset else 0)
        restored = true
    }

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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            HeaderItem(stringResource(R.string.nav_me), onClick = { viewModel.open(Screen.Account) }) {
                Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.cd_account), tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.nav_sync), active = state.sync.busy, onClick = { viewModel.open(Screen.Sync) }) {
                SyncIcon(running = state.sync.busy)
            }
            HeaderItem(stringResource(R.string.nav_stats), onClick = { viewModel.openStats() }) {
                Icon(painterResource(R.drawable.ic_stats), contentDescription = stringResource(R.string.nav_stats), tint = p.ink, modifier = Modifier.size(20.dp))
            }
            HeaderItem(stringResource(R.string.nav_map), onClick = { viewModel.openMap() }) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = stringResource(R.string.nav_map), tint = p.ink, modifier = Modifier.size(20.dp))
            }

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
                Icon(
                    painterResource(R.drawable.ic_help), contentDescription = stringResource(R.string.cd_search_help), tint = p.muted,
                    modifier = Modifier
                        .size(34.dp)
                        .combinedClickableCompat { focusManager.clearFocus(); keyboard?.hide(); showOperators = true }
                        .padding(8.dp),
                )
            }
        }

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

        if (state.filters.count > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActiveFilterChips(state.filters, onRemove = { viewModel.setFilters(it) }, modifier = Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {

            val countText = when {

                state.selecting -> Actions.formatCompact(state.selectedIds.size.toLong())
                state.searching && state.hits.isEmpty() -> stringResource(R.string.count_searching)

                state.skippedMode ->
                    pluralStringResource(R.plurals.unreadable_count, state.hits.size, Actions.formatCount(state.hits.size.toLong()))
                state.duplicatesMode && state.similarToId != null ->
                    stringResource(R.string.similar_count, Actions.formatCompact(state.hits.size.toLong()))
                state.duplicatesMode ->

                    pluralStringResource(R.plurals.photos_count, state.hits.size, Actions.formatCount(state.hits.size.toLong()))

                else -> Actions.formatCompact(state.numFound)
            }

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

            if (state.query.isNotBlank()) {

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

            if (!state.skippedMode && (!state.duplicatesMode || state.similarToId != null)) {
                val browsing = !state.duplicatesMode && state.query.isBlank() && state.filters.count == 0
                GroupByButton(
                    current = if (browsing && state.groupBy == com.opensolr.photos.ui.GroupBy.RELEVANCE) com.opensolr.photos.ui.GroupBy.DATE else state.groupBy,
                    options = if (browsing) com.opensolr.photos.ui.GroupBy.entries - com.opensolr.photos.ui.GroupBy.RELEVANCE else com.opensolr.photos.ui.GroupBy.entries,
                    onPick = { viewModel.setGroupBy(it) },
                )
            }

            IconAction(
                icon = R.drawable.ic_filters,
                label = stringResource(R.string.filters),
                active = state.filters.count > 0,
                badge = state.filters.count,
                onClick = { showFilters = true },
            )

            IconAction(
                icon = R.drawable.ic_duplicates,
                label = if (!state.duplicatesMode) stringResource(R.string.dup_open) else stringResource(R.string.back_all),
                active = state.duplicatesMode,
                onClick = { viewModel.showDuplicates() },
            )

            if (state.skippedCount > 0 || state.skippedMode) {
                IconAction(
                    icon = R.drawable.ic_skipped,
                    label = if (!state.skippedMode) stringResource(R.string.skipped_open) else stringResource(R.string.back_all),
                    active = state.skippedMode,
                    danger = true,
                    onClick = { viewModel.showSkipped() },
                )
            }

            IconAction(
                icon = R.drawable.ic_reload,
                label = stringResource(R.string.reload),
                accent = true,
                enabled = !state.searching,
                onClick = { viewModel.forceRefresh() },
            )

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
                firstStop = com.opensolr.photos.search.SearchRepository.FIRST_LIBRARY_LEVEL,
                onLevel = { viewModel.setDuplicateLevel(it) },
                canSelect = state.duplicateGroups.isNotEmpty(),

                showSelectOneOfEach = state.similarToId == null,
                onSelectOneOfEach = { viewModel.selectOneOfEachDuplicate() },
            )
        }
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }

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

        if (!state.selecting) {
            SuggestedFacets(state, onPick = { field, value -> viewModel.setFilters(state.filters.toggled(field, value)) })
        }

        state.didYouMean?.takeIf { state.query.isNotBlank() }?.let { corrected ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat { viewModel.applySuggestion(corrected) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.did_you_mean), style = MaterialTheme.typography.bodyMedium, color = p.muted)

                Spacer(Modifier.width(4.dp))
                Text(corrected, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.accent)
                Text("?", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        state.searchNotice?.let { Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

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

        if (!state.searching && state.hits.isEmpty() && state.skeleton.isEmpty() && state.resultGroups.isEmpty() && state.searchError == null) {
            EmptyResults(state)
        }

        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.searching) { if (!state.searching) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.searching,

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

                            if (!row.collapsed && row.range != null && row.ids.size < row.count) {
                                LaunchedEffect(row.key, row.count, row.ids.size) {
                                    viewModel.loadGroupPhotos(row.key, row.range.first, row.range.second, row.ids.size, row.count)
                                }
                            }

                            if (row.missing) {
                                LaunchedEffect(row.key) { viewModel.loadResultGroup(row.name, row.ids) }
                            }

                            val allPicked = state.selecting &&
                                (row.key in state.selectedGroups || (row.ids.isNotEmpty() && state.selectedIds.containsAll(row.ids)))

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
                                if (!state.selecting && !state.skippedMode) {
                                    PhotoMarks(
                                        hasPlace = !hit.location.isNullOrBlank(),
                                        hasPeople = hit.persons.isNotBlank(),
                                        hasTags = hit.customTags.isNotEmpty(),
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                    )
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

            FastScroller(gridState, rows)
        }
    }

    if (state.selecting) {
        SelectionDock(
            count = state.selectedIds.size,
            modifier = Modifier.align(Alignment.BottomCenter),
            onShare = { Actions.sharePhotos(context, viewModel.photosToTag()) },

            onDelete = { confirmDelete = true },
            onResync = { viewModel.resyncSelected() },

            onTag = { bulkTagging = true },
        )
    }
    }

    if (showOperators) {
        SearchOperatorsDialog(onDismiss = { showOperators = false })
    }

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

    if (bulkTagging) {
        BulkTagSheet(state = state, viewModel = viewModel, onDismiss = { bulkTagging = false })
    }

    viewing?.let { hit ->

        val viewerHits = remember(rows, state.hits, state.resultGroups) {
            if (state.resultGroups.isEmpty()) state.hits
            else rows.filterIsInstance<GridRow.Photo>().map { it.hit }.distinctBy { it.id }
        }
        val startAt = remember(hit.id, viewerHits) { viewerHits.indexOfFirst { it.id == hit.id }.coerceAtLeast(0) }
        PhotoViewer(
            hits = viewerHits,
            start = startAt,
            onClose = { viewing = null },

            onSheet = { photo, close, openEdit ->
                DetailsSheet(
                    hit = photo,
                    viewModel = viewModel,
                    onDismiss = close,
                    onLeave = { viewing = null },

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

/** The same Search Operators help as on search.opensolr.com; the syntax itself is untranslated. */
@Composable
private fun SearchOperatorsDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val code = SpanStyle(fontFamily = FontFamily.Monospace, color = p.accent, fontWeight = FontWeight.SemiBold)

    @Composable
    fun Heading(label: String, syntax: String?) {
        Text(
            buildAnnotatedString {
                append(label)
                if (syntax != null) { append("  "); withStyle(code) { append(syntax) } }
            },
            style = MaterialTheme.typography.labelLarge, color = p.ink,
        )
    }

    @Composable
    fun Line(lead: String?, syntax: String, rest: String) {
        Text(
            buildAnnotatedString {
                if (lead != null) { append(lead); append(" ") }
                withStyle(code) { append(syntax) }
                append("  ")
                append(rest)
            },
            style = MaterialTheme.typography.bodyMedium, color = p.muted,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.ops_title))
                Text(stringResource(R.string.ops_sub), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        },
        text = {
            val example = stringResource(R.string.ops_example)
            val phraseToo = stringResource(R.string.ops_phrase_too)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Heading(stringResource(R.string.ops_phrase_h), "\"word1 word2\"")
                Text(stringResource(R.string.ops_phrase_b), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Line(example, "\"machine learning\"", stringResource(R.string.ops_phrase_ex))
                Text(stringResource(R.string.ops_phrase_ai), style = MaterialTheme.typography.bodyMedium, color = p.muted)

                Spacer(Modifier.height(8.dp))
                Heading(stringResource(R.string.ops_req_h), "+word")
                Text(stringResource(R.string.ops_req_b), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Line(example, "+laptop 15 inch gaming", stringResource(R.string.ops_req_ex))
                Line(phraseToo, "+\"13 inch\"", stringResource(R.string.ops_req_phrase))

                Spacer(Modifier.height(8.dp))
                Heading(stringResource(R.string.ops_exc_h), "-word")
                Text(stringResource(R.string.ops_exc_b), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Line(example, "laptop -refurbished", stringResource(R.string.ops_exc_ex))
                Line(phraseToo, "-\"open box\"", stringResource(R.string.ops_exc_phrase))

                Spacer(Modifier.height(8.dp))
                Heading(stringResource(R.string.ops_comb_h), null)
                Text(stringResource(R.string.ops_comb_b), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Line(example, "+laptop +\"13 inch\" -refurbished", stringResource(R.string.ops_comb_ex))

                Spacer(Modifier.height(8.dp))
                Heading(stringResource(R.string.ops_why_h), null)
                Text(stringResource(R.string.ops_why_b), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ops_close), color = p.accent) } },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.ink,
    )
}

@Composable
private fun SuggestedFacets(state: UiState, onPick: (String, String) -> Unit) {
    if (state.query.isBlank()) return
    val source = state.queryFacets
    val picks = remember(source, state.filters) {
        val lists = STRIP_FIELDS.map { field ->
            (source[field] ?: emptyList())
                .asSequence()

                .filter { it.count >= 2 && it.value !in state.filters.values(field) }
                .sortedByDescending { it.count }
                .take(PER_FIELD)
                .map { field to it }
                .toList()
        }

        (0 until PER_FIELD).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }.take(10)
    }
    if (picks.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(picks, key = { (field, value) -> "$field:${value.value}" }) { (field, value) ->

            Pill(
                label = facetLabel(LocalContext.current, field, value.value),
                onClick = { onPick(field, value.value) },
            )
        }
    }
}

private val STRIP_FIELDS = listOf("custom_tags", "labels", "city", "country", "year", "camera_model")

private const val PER_FIELD = 3

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

        DockAction(R.drawable.ic_tag, stringResource(R.string.dock_tag_n, Actions.formatCompact(count.toLong())), enabled = count > 0, onClick = onTag)
        DockAction(R.drawable.ic_share, stringResource(R.string.act_share), enabled = count > 0, onClick = onShare)
        DockAction(R.drawable.ic_sync, if (count > 0) stringResource(R.string.dock_resync_n, Actions.formatCompact(count.toLong())) else stringResource(R.string.dock_resync), enabled = count > 0, accent = true, onClick = onResync)

        DockAction(R.drawable.ic_delete, stringResource(R.string.dock_delete), enabled = count > 0, onClick = onDelete)
    }
}

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

private sealed interface GridRow {

    val key: String

    data class Heading(

        val text: String,
        val ids: List<String>,
        val collapsed: Boolean = false,

        val name: String = text,

        val level: Int = 0,

        val range: Pair<Long, Long>? = null,

        val count: Int = ids.size,

        val exact: Boolean = false,

        val missing: Boolean = false,
    ) : GridRow {
        override val key: String get() = "h:$name"

        val selectable: Boolean get() = exact || range != null || (ids.isNotEmpty() && name.contains(" of the same"))
    }

    data class Photo(val hit: PhotoHit, val group: String? = null) : GridRow {
        override val key: String get() = if (group == null) hit.id else "g:$group|${hit.id}"
    }
}

private fun buildSkeletonRows(
    skeleton: List<com.opensolr.photos.ui.DateGroup>,
    hits: List<PhotoHit>,
    collapsed: Set<String>,
): List<GridRow> {
    val rows = ArrayList<GridRow>(hits.size + skeleton.size)

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

        if (group.name in withChildren) return@forEach
        mine.forEach { rows += GridRow.Photo(it) }
    }
    return rows
}

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

private data class GridWords(val best: String, val similar: String, val ofTheSame: String)

private fun buildRows(
    hits: List<PhotoHit>,
    byDate: Boolean,
    groups: List<Int> = emptyList(),
    collapsed: Set<String> = emptySet(),
    words: GridWords,
): List<GridRow> {
    if (hits.isEmpty()) return emptyList()

    fun MutableList<GridRow>.addGroup(
        name: String,
        photos: List<PhotoHit>,
        text: String = name,
        level: Int = 0,
        range: Pair<Long, Long>? = null,
    ): Boolean {
        val folded = "h:$name" in collapsed

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

        val rows = ArrayList<GridRow>(hits.size + groups.size)
        var from = 0
        groups.forEachIndexed { index, size ->
            val group = hits.drop(from).take(size)
            if (group.isEmpty()) return@forEachIndexed

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

    val recent = LinkedHashMap<String, MutableList<PhotoHit>>()
    val years = LinkedHashMap<String, LinkedHashMap<String, MutableList<PhotoHit>>>()
    val loose = ArrayList<PhotoHit>()

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

    recent.forEach { (heading, photos) ->
        val millis = photos.firstNotNullOfOrNull { it.takenMs }
        rows.addGroup(heading, photos, level = 0, range = millis?.let { Actions.daySpan(it) })
    }
    years.forEach { (year, months) ->
        val yearPhotos = months.values.flatten()
        val yearMillis = yearPhotos.firstNotNullOfOrNull { it.takenMs }
        if (rows.addGroup(year, yearPhotos, level = 0, range = yearMillis?.let { Actions.yearSpan(it) })) return@forEach

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

private const val MIN_HITS_TO_CUT = 8

private const val VIEWER_SWIPE_UP = 90f

private const val VIEWER_DISMISS_SHARE = 0.18f

private const val VIEWER_DOUBLE_TAP_MS = 260

private const val VIEWER_DOUBLE_TAP_SCALE = 3f

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

private val PREFETCH_REMAINING = com.opensolr.photos.search.SearchRepository.PAGE / 2

private val VIEWER_MIN_BOTTOM = 28.dp

private const val FAST_SCROLL_MIN_ROWS = 60

private val FAST_SCROLL_THUMB = 72.dp

private val FAST_SCROLL_LABEL_LIFT = 64.dp

private val FAST_SCROLL_WIDTH = 36.dp

private val FAST_SCROLL_LABEL_ROOM = 240.dp

private const val MIN_BEST_MATCHES = 3

private const val ALSO_SIMILAR_SHARE = 0.6

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

@Composable
private fun DuplicateLevelSlider(level: Int, firstStop: Int, onLevel: (Int) -> Unit, canSelect: Boolean, showSelectOneOfEach: Boolean, onSelectOneOfEach: () -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    var value by remember { mutableStateOf(level.toFloat()) }
    LaunchedEffect(level) { if (value.roundToInt() != level) value = level.toFloat() }
    val stop = level.coerceIn(firstStop, com.opensolr.photos.search.SearchRepository.DUPLICATE_STOPS.size - 1)

    val dark = p.ink.red > 0.5f
    val loose = if (dark) DUPLICATE_LOOSE_DARK else DUPLICATE_LOOSE_LIGHT
    val green = if (dark) DUPLICATE_GREEN_DARK else DUPLICATE_GREEN_LIGHT
    val colour = when {
        stop == DUPLICATE_EXIF_STOP -> green
        stop < DUPLICATE_EXIF_STOP -> lerp(loose, green, (stop - firstStop) / (DUPLICATE_EXIF_STOP - firstStop).coerceAtLeast(1).toFloat())

        else -> if (dark) DUPLICATE_NEUTRAL_DARK else DUPLICATE_NEUTRAL_LIGHT
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Slider(
            value = value,

            onValueChange = {
                value = it
                val next = if (kotlin.math.abs(it - level) < STOP_SLOP) level else it.roundToInt()
                if (next != level) {
                    Haptics.tick(view, strong = false)
                    onLevel(next)
                }
            },

            onValueChangeFinished = { value = level.toFloat() },
            valueRange = firstStop.toFloat()..(com.opensolr.photos.search.SearchRepository.DUPLICATE_STOPS.size - 1).toFloat(),

            // one tick per stop between the ends, so the stops are visible on the bar
            steps = (com.opensolr.photos.search.SearchRepository.DUPLICATE_STOPS.size - 1 - firstStop - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = colour,
                activeTrackColor = colour,
                inactiveTrackColor = p.chip,
                activeTickColor = colour,
                inactiveTickColor = p.hairline,
            ),
        )
        Text("${stop - firstStop} · ${duplicateKindName(stop)}", style = MaterialTheme.typography.labelMedium, color = colour)

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

// The words stops are named from the number itself; the five after them come from the array.
@Composable
private fun duplicateKindName(stop: Int): String {
    val words = (com.opensolr.photos.search.SearchRepository.WORD_STOPS_MAX - 1) * 2
    if (stop >= words) return stringArrayResource(R.array.dup_kinds)[(stop - words).coerceIn(0, 4)]
    val count = com.opensolr.photos.ui.Actions.formatCount((stop / 2 + 2).toLong())
    return stringResource(if (stop % 2 == 0) R.string.dup_kind_words else R.string.dup_kind_words_camera, count)
}

private const val STOP_SLOP = 0.7f

private val DUPLICATE_EXIF_STOP = (com.opensolr.photos.search.SearchRepository.WORD_STOPS_MAX - 1) * 2 + 1

private val DUPLICATE_LOOSE_LIGHT = Color(0xFF111111)
private val DUPLICATE_NEUTRAL_LIGHT = Color(0xFF495057)
private val DUPLICATE_GREEN_LIGHT = Color(0xFF2F9E44)

private val DUPLICATE_LOOSE_DARK = Color(0xFFF4F1EC)
private val DUPLICATE_NEUTRAL_DARK = Color(0xFFADB5BD)
private val DUPLICATE_GREEN_DARK = Color(0xFF51CF66)

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

        items(chips) { (label, without) ->

            Pill(label = label, accent = true, trailingClose = true, onClick = { onRemove(without) })
        }
    }
}

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

@Composable
private fun Thumbnail(hit: PhotoHit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val uri = remember(hit.mediaId) { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId) }

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

@Composable
private fun EmptyResults(state: UiState) {

    if (state.duplicatesMode || state.skippedMode) return
    val p = LocalPalette.current
    val text = when {
        state.sync.busy && state.query.isBlank() -> stringResource(R.string.empty_indexing)
        state.query.isBlank() && state.filters.count == 0 -> stringResource(R.string.empty_nothing)
        else -> stringResource(R.string.empty_nomatch)
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

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

            Spacer(Modifier.height(8.dp))
            FilterActions(count, onClear = { onChange(SearchFilters()) }, onDone = onDismiss)
            Spacer(Modifier.height(12.dp))

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

            FilterGroup(stringResource(R.string.taken_between), if (draft.taken != null) 1 else 0, "Taken between" in open, { onToggleSection("Taken between") }) {
                DateRangeValues(draft.taken, years) { onChange(draft.copy(taken = it, fields = if (it != null) draft.fields - "year" else draft.fields)) }
            }

            val switchesOn = listOf(draft.hasOcr == true, draft.tagged == true, draft.hasPeople == true, draft.withLocation).count { it }
            FilterGroup(stringResource(R.string.photo_has), switchesOn, "Photo has" in open, { onToggleSection("Photo has") }) {
                FilterSwitch(stringResource(R.string.sw_ocr), stringResource(R.string.sw_ocr_hint), draft.hasOcr == true) { onChange(draft.copy(hasOcr = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_tagged), stringResource(R.string.sw_tagged_hint), draft.tagged == true) { onChange(draft.copy(tagged = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_people), stringResource(R.string.sw_people_hint), draft.hasPeople == true) { onChange(draft.copy(hasPeople = if (it) true else null)) }
                FilterSwitch(stringResource(R.string.sw_location), stringResource(R.string.sw_location_hint), draft.withLocation) { onChange(draft.copy(withLocation = it)) }
                Spacer(Modifier.height(12.dp))
            }

            facet("labels")

            facet("persons_ss")
            facet("custom_tags")
            facet("city")
            facet("country")

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

@Composable
private fun BoxScope.FastScroller(gridState: LazyGridState, rows: List<GridRow>) {
    val p = LocalPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val total = rows.size
    var dragging by remember { mutableStateOf(false) }

    var aimed by remember { mutableIntStateOf(-1) }

    var lastInto by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val currentRows by rememberUpdatedState(rows)

    if (total < FAST_SCROLL_MIN_ROWS) return

    val alpha by animateFloatAsState(
        targetValue = if (dragging || gridState.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = if (dragging) 0 else 450),
        label = "fastScrollerAlpha",
    )

    val density = LocalDensity.current
    val learned = remember { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .collect { items ->
                items.forEach { item ->
                    val row = currentRows.getOrNull(item.index) ?: return@forEach
                    val height = item.size.height
                    if (height <= 0) return@forEach

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

    val heightsKey = learned.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}" }
    var heights by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    LaunchedEffect(heightsKey, dragging) { if (!dragging) heights = learned.toMap() }
    val cellPx = heights[-1] ?: fallbackCell

    val columns = (heights[-2] ?: 3f).toInt().coerceAtLeast(1)
    val tops = remember(rows, columns, heights, cellPx) {
        val out = FloatArray(rows.size + 1)
        var y = 0f
        var inLine = 0
        rows.forEachIndexed { i, row ->
            if (row is GridRow.Heading) {

                if (inLine > 0) { y += cellPx; inLine = 0 }
                out[i] = y
                val level = row.level.coerceIn(0, HEADING_HEIGHTS.lastIndex)
                y += heights[level] ?: fallbackHeadings[level]
            } else {

                out[i] = y
                inLine++
                if (inLine >= columns) { y += cellPx; inLine = 0 }
            }
        }
        if (inLine > 0) y += cellPx
        out[rows.size] = y
        out
    }

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

            .width(FAST_SCROLL_LABEL_ROOM),
    ) {
        val travel = maxHeight - FAST_SCROLL_THUMB
        val density = LocalDensity.current
        val travelPx = with(density) { travel.toPx() }
        val halfThumbPx = with(density) { FAST_SCROLL_THUMB.toPx() } / 2f
        val labelLiftPx = with(density) { FAST_SCROLL_LABEL_LIFT.toPx() }

        fun aimAt(y: Float) {
            val now = currentRows
            if (now.isEmpty()) return
            val last = now.lastIndex
            val at = if (travelPx <= 0f) 0f else ((y - halfThumbPx) / travelPx).coerceIn(0f, 1f)

            val tops = topsNow
            val wanted = at * scrollableNow
            var lo = 0
            var hi = minOf(last, tops.size - 2)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tops[mid] <= wanted) lo = mid else hi = mid - 1
            }
            var target = lo.coerceIn(0, last)

            val mapped = minOf(last, tops.size - 2)
            val near = (target - SNAP_ROWS).coerceAtLeast(0)..(target + SNAP_ROWS).coerceAtMost(mapped)
            near.minByOrNull { i ->
                if (now.getOrNull(i) !is GridRow.Heading) Float.MAX_VALUE else kotlin.math.abs(tops[i] - wanted)
            }?.let { i -> if (now.getOrNull(i) is GridRow.Heading && kotlin.math.abs(tops[i] - wanted) <= cellNow) target = i }
            val into = (wanted - tops[target]).coerceAtLeast(0f)

            if (target == aimed && kotlin.math.abs(into - lastInto) < 2f) return
            lastInto = into

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

                        dragging = false
                        aimed = -1
                    }
                },
        )

        Box(
            Modifier

                .offset { IntOffset(0, (travelPx * fraction.value).roundToInt()) }
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .size(width = 16.dp, height = FAST_SCROLL_THUMB)
                .alpha(alpha)
                .background(if (dragging) p.accentFill else p.ink, Corner)
                .border(1.dp, if (dragging) p.accentFill else p.paper, Corner),
        )

        if (dragging) {

            var heading: String? = null
            var day: String? = null
            var walk = aimed.coerceAtLeast(0).coerceAtMost(rows.lastIndex)
            while (walk >= 0 && (heading == null || day == null)) {
                val row = rows[walk]
                if (row is GridRow.Heading) {

                    if (row.level == 0 && heading == null) heading = headingLabel(row)

                    if (row.level == 1 && day == null) day = headingLabel(row)
                }
                walk--
            }
            if (!heading.isNullOrBlank()) {
                Box(
                    Modifier

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoViewer(
    hits: List<PhotoHit>,
    start: Int,
    onClose: () -> Unit,

    onSheet: @Composable (PhotoHit, () -> Unit, (PhotoHit) -> Unit) -> Unit,
    onEditSheet: @Composable (PhotoHit, () -> Unit) -> Unit,

    topInset: Dp,
    bottomInset: Dp,
    onNeedMore: () -> Unit,
    onDelete: (PhotoHit) -> Unit,

    viewModel: AppViewModel,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { hits.size }
    val view = LocalView.current
    var showActions by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(pager.currentPage) { zoomJob?.cancel(); scale = 1f; offset = Offset.Zero }

    var sheetFor by remember { mutableStateOf<PhotoHit?>(null) }

    var editFor by remember { mutableStateOf<PhotoHit?>(null) }

    LaunchedEffect(pager.currentPage, hits.size) {
        if (pager.currentPage >= hits.size - 3) onNeedMore()
    }

    LaunchedEffect(hits.size) { if (hits.isEmpty()) onClose() }

    val dismiss = remember { Animatable(0f) }

    val upBy = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val overflow = viewerWindowOverflow()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val closeAt = with(LocalDensity.current) { maxHeight.toPx() } * VIEWER_DISMISS_SHARE

            val shade = (1f - (kotlin.math.abs(dismiss.value) / (closeAt * 2f))).coerceIn(0.35f, 1f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade))) {
            HorizontalPager(
                state = pager,

                userScrollEnabled = scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val hit = hits.getOrNull(page) ?: return@HorizontalPager
                val uri = remember(hit.mediaId) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.mediaId)
                }
                AsyncImage(

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

                        .pointerInput(hit.id, scale > 1f) {
                            if (scale <= 1f) {

                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        if (amount < 0 && dismiss.value == 0f) {

                                            if (upBy.value == 0f) Haptics.tick(view, strong = false)
                                            upBy.value -= amount
                                        }
                                        scope.launch { dismiss.snapTo((dismiss.value + amount).coerceAtLeast(0f)) }
                                    },
                                    onDragEnd = {

                                        if (dismiss.value == 0f && upBy.value > VIEWER_SWIPE_UP) {

                                            Haptics.tick(view, strong = false)
                                            sheetFor = hit
                                        }
                                        upBy.value = 0f
                                        if (dismiss.value > closeAt) {

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

                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y + (if (page == pager.currentPage) dismiss.value else 0f)
                        },
                )
            }

            val hit = hits.getOrNull(pager.currentPage)

            if (showActions && hit != null) {

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

                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = maxOf(bottomInset, VIEWER_MIN_BOTTOM) + overflow + 12.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {

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

            sheetFor?.let { held ->
                val photo = hits.firstOrNull { it.id == held.id } ?: held
                onSheet(
                    photo,
                    { Haptics.tick(view, strong = true); sheetFor = null },
                    { editFor = it },
                )
            }

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

@Composable
private fun RowScope.ViewerAction(label: String, icon: Int, onClick: () -> Unit) {
    ViewerActionFrame(onClick) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun RowScope.ViewerIconAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ViewerActionFrame(onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun RowScope.ViewerActionFrame(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(Corner)

            .border(1.dp, Color.White.copy(alpha = 0.45f), Corner)
            .combinedClickableCompat(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

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

    val folded = remember(all) { all.map { com.opensolr.photos.data.Words.fold(label(it.value)) } }

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DetailsSheet(

    onLeave: () -> Unit = {},hit: PhotoHit, viewModel: AppViewModel, onDismiss: () -> Unit, onEdit: (PhotoHit) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current

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

            SectionLabel(stringResource(R.string.sec_place))
            Spacer(Modifier.height(10.dp))
            PlaceButton(hit.placeLabel ?: stringResource(R.string.add_place), onClick = { picking = true })
            Spacer(Modifier.height(16.dp))

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

            SectionLabel(stringResource(R.string.sec_shows))
            Text(hit.meaning.ifBlank { stringResource(R.string.no_words_yet) }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = p.ink, modifier = Modifier.padding(vertical = 12.dp))

            if (hit.ocrText.isNotBlank()) {
                SectionLabel(stringResource(R.string.sec_printed))
                Text(hit.ocrText, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun PickTick(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    val p = LocalPalette.current
    val view = LocalView.current
    Box(
        modifier
            .combinedClickableCompat { Haptics.tick(view, strong = !selected); onClick() }

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

private const val FACET_PREVIEW = 10

private const val FACET_SEARCH_OVER = 50

private const val FACET_SEARCH_ROWS = 50

private val REBUILD_PHASES = setOf("Resetting your index", "Updating the index configuration", "Rebuilding your index")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .scale(com.opensolr.photos.ui.pressedScale(source))
        .background(com.opensolr.photos.ui.pressedTint(source), Corner)
        .combinedClickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
}

private const val SNAP_ROWS = 4

private val HEADING_HEIGHTS = listOf(46.dp, 40.dp, 38.dp)

private fun headingLabel(row: GridRow.Heading): String =
    row.text.substringBefore(" \u00b7 ").substringBefore(" (").trim().ifBlank { row.name }

@Composable
private fun PhotoMarks(hasPlace: Boolean, hasPeople: Boolean, hasTags: Boolean, modifier: Modifier = Modifier) {
    if (!hasPlace && !hasPeople && !hasTags) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (hasPlace) {
            PhotoMark {
                Icon(painterResource(R.drawable.ic_map), contentDescription = stringResource(R.string.cd_has_location), tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        if (hasPeople) {
            PhotoMark {
                Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.cd_has_people), tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        if (hasTags) {
            PhotoMark {
                Icon(painterResource(R.drawable.ic_tag), contentDescription = stringResource(R.string.cd_has_tags), tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
    }
}

@Composable
private fun PhotoMark(icon: @Composable () -> Unit) {
    Box(Modifier.size(18.dp).background(Color(0x99000000), Corner), contentAlignment = Alignment.Center) { icon() }
}

private val SkippedRed = Color(0xFFE53E3E)

@Composable
internal fun headingBand(level: Int): Color =
    LocalPalette.current.accent.copy(alpha = when (level) {
        0 -> 0.18f
        1 -> 0.11f
        else -> 0.06f
    })

@Composable
internal fun headingStyle(level: Int): androidx.compose.ui.text.TextStyle =
    when (level) {
        0 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp)
        1 -> MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)

        else -> MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp)
    }

private fun Modifier.dragSelect(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    rowsNow: () -> List<GridRow>,
    onStart: (String) -> Unit,
    onRange: (List<String>, Boolean) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()

    val at = remember { mutableStateOf<Offset?>(null) }
    val anchorIndex = remember { mutableStateOf<Int?>(null) }
    val edgePx = with(LocalDensity.current) { DRAG_EDGE.toPx() }
    val stepPx = with(LocalDensity.current) { DRAG_STEP.toPx() }

    fun rowIndexAt(point: Offset): Int? {
        val items = gridState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return null
        items.forEach { item ->
            val withinY = point.y >= item.offset.y && point.y <= item.offset.y + item.size.height
            val withinX = point.x >= item.offset.x && point.x <= item.offset.x + item.size.width
            if (withinY && withinX) return item.index
        }

        return items.lastOrNull { it.offset.y <= point.y }?.index ?: items.first().index
    }

    fun idsTo(point: Offset): List<String>? {
        val anchor = anchorIndex.value ?: return null
        val here = rowIndexAt(point) ?: return null
        val rows = rowsNow()
        val from = minOf(anchor, here).coerceAtLeast(0)
        val to = maxOf(anchor, here).coerceAtMost(rows.lastIndex)
        if (from > to) return null

        return (from..to).mapNotNull { (rows.getOrNull(it) as? GridRow.Photo)?.hit?.id }
    }

    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

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

private val DRAG_EDGE = 72.dp

private val DRAG_STEP = 14.dp
