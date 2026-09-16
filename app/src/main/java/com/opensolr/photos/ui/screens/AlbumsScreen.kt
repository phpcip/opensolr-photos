package com.opensolr.photos.ui.screens

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import com.opensolr.photos.R
import com.opensolr.photos.ui.Haptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensolr.photos.search.Album
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

/**
 * Albums: the library grouped by what the index already knows about each photo - the owner's
 * tags, the words CLIP used most, places, cameras and years - every album with at least one
 * photos. Tapping one opens the photos grid filtered to it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val view = LocalView.current
    // Long press on a section title or an album picks it; while anything is picked, a tap picks
    // or unpicks, and the bar at the bottom deletes or shares their photos (Cip, 2026-09-17).
    val selecting = state.selectedAlbums.isNotEmpty() || state.selectedSections.isNotEmpty()
    var folded by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.albumPhotosDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 8.dp)) { ScreenHeader("Albums", onBack = { viewModel.back() }) }
        if (state.albumsLoading || state.albumsWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }
        state.albumsError?.let {
            Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (!state.albumsLoading && state.albumsError == null && state.albums.isEmpty()) {
            Text(
                "No albums yet: albums come from your tags, the words photos were read into, places, cameras and years.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
        // Every section folded away, or all of them opened again, as on the photos grid
        // (Cip, 2026-09-17).
        if (state.albums.isNotEmpty()) {
            val anyFolded = state.albums.any { it.title in folded }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                // Check all / check none: every section picked, or nothing (Cip, 2026-09-17).
                val allPicked = state.albums.all { it.title in state.selectedSections }
                IconAction(
                    icon = if (allPicked) R.drawable.ic_check_none else R.drawable.ic_check_all,
                    label = if (allPicked) "Check none" else "Check all",
                    active = allPicked,
                    onClick = {
                        Haptics.tick(view, strong = false)
                        viewModel.selectAllAlbums(!allPicked)
                    },
                )
                IconAction(
                    icon = if (anyFolded) R.drawable.ic_expand_all else R.drawable.ic_collapse_all,
                    label = if (anyFolded) "Expand all" else "Collapse all",
                    onClick = {
                        Haptics.tick(view, strong = false)
                        folded = if (anyFolded) emptySet() else state.albums.map { it.title }.toSet()
                    },
                )
            }
        }
        // Swipe down asks the index again, as on the photos grid (Cip, 2026-09-15).
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.albumsLoading) { if (!state.albumsLoading) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.albumsLoading,
            onRefresh = { pulled = true; viewModel.openAlbums(force = true) },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = pulled && state.albumsLoading,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = p.paper,
                    color = p.accent,
                )
            },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                state.albums.forEach { section ->
                    val sectionFolded = section.title in folded
                    item(key = "section:${section.title}", span = { GridItemSpan(maxLineSpan) }) {
                        AlbumSectionHeading(
                            title = section.title,
                            count = section.albums.size,
                            folded = sectionFolded,
                            selecting = selecting,
                            selected = section.title in state.selectedSections,
                            onClick = {
                                if (selecting) viewModel.toggleSectionSelected(section.title)
                                else folded = if (sectionFolded) folded - section.title else folded + section.title
                            },
                            onLongClick = {
                                if (!selecting) Haptics.tick(view, strong = true)
                                viewModel.toggleSectionSelected(section.title)
                            },
                        )
                    }
                    if (!sectionFolded) {
                        items(section.albums, key = { "${it.field}:${it.value}" }) { album ->
                            val key = "${album.field}:${album.value}"
                            AlbumCard(
                                album,
                                selecting = selecting,
                                selected = key in state.selectedAlbums || section.title in state.selectedSections,
                                onClick = { if (selecting) viewModel.toggleAlbumSelected(album) else viewModel.openAlbum(album) },
                                onLongClick = {
                                    if (!selecting) Haptics.tick(view, strong = true)
                                    viewModel.toggleAlbumSelected(album)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (selecting) {
        // Share is for exactly one album: a whole section at once is too much to hand anywhere.
        val shareEnabled = state.selectedSections.isEmpty() && state.selectedAlbums.size == 1 && !state.albumsWorking
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
                .clip(Corner)
                .background(p.paper)
                .border(1.dp, p.hairline, Corner)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockAction(R.drawable.ic_share, "Share", enabled = shareEnabled) {
                viewModel.withSelectedAlbumPhotos { photos -> Actions.sharePhotos(context, photos) }
            }
            DockAction(R.drawable.ic_delete, "Delete", enabled = !state.albumsWorking) { confirmDelete = true }
            Column(
                Modifier
                    .clip(Corner)
                    .combinedClickable(onClick = { viewModel.clearAlbumSelection() })
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = p.ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text("Cancel", style = MaterialTheme.typography.labelSmall, color = p.ink, maxLines = 1)
            }
        }
    }
    // Says exactly what goes before anything does (Cip, 2026-09-17): a section takes every
    // album under it, an album every photo in it, from the phone and from the index.
    if (confirmDelete && selecting) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.paper,
            title = { Text("Delete photos?", color = p.ink) },
            text = { Text(deleteWarning(state), color = p.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.withSelectedAlbumPhotos { photos ->
                    val sender = Actions.deleteRequest(context, Actions.contentUris(context, photos))
                    pendingDelete = photos.map { it.id }.toSet()
                    if (sender != null) {
                        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        viewModel.albumPhotosDeleted(pendingDelete)
                        pendingDelete = emptySet()
                    }
                    }
                }) { Text("Delete", color = Color(0xFFE53E3E)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = p.ink) } },
        )
    }
    }
}

/**
 * What a delete of the picked sections and albums does, in words: every album of each picked
 * section, and every photo of each picked album, gone from the phone and from the index.
 */
private fun deleteWarning(state: UiState): String {
    val sections = state.albums.filter { it.title in state.selectedSections }.map { it.title }
    val albums = state.albums.filter { it.title !in state.selectedSections }
        .flatMap { it.albums }
        .filter { "${it.field}:${it.value}" in state.selectedAlbums }
        .map { albumTitle(it.title) }
    val parts = ArrayList<String>()
    if (sections.isNotEmpty()) {
        parts += "This deletes every photo in every album of: ${sections.joinToString(", ")}."
    }
    if (albums.isNotEmpty()) {
        parts += if (albums.size == 1) "This deletes every photo in the album ${albums[0]}."
        else "This deletes every photo in the albums: ${albums.joinToString(", ")}."
    }
    parts += "The photos are deleted from this phone and removed from your index. A photo that is in several albums goes from all of them."
    return parts.joinToString("\n\n")
}

/**
 * The title of an album section, drawn exactly as the photos grid draws a month: a solid band
 * with a fold arrow (Cip, 2026-09-17). A tap folds the section; while picking, it picks the
 * section, and a long press starts picking with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumSectionHeading(
    title: String,
    count: Int,
    folded: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = LocalPalette.current
    val dark = p.ink.red > 0.5f
    val band = if (dark) HEADING_MONTH_DARK else HEADING_MONTH_LIGHT
    val onBand = if (dark) Color(0xFF111111) else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(Corner)
            .background(band)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (folded) "Open this section" else "Fold this section away",
            tint = onBand,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (folded) "$title (${Actions.formatCount(count.toLong())})" else title,
            style = headingStyle(0),
            fontWeight = FontWeight.Bold,
            color = onBand,
            modifier = Modifier.weight(1f),
        )
        if (selecting) SelectTick(selected)
    }
}

/** The square tick of a picked section or album, as on the photos grid. */
@Composable
private fun SelectTick(selected: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .size(22.dp)
            .background(if (selected) p.accentFill else p.paper, Corner)
            .border(1.dp, if (selected) p.accentFill else p.hairline, Corner),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccentFill, modifier = Modifier.size(16.dp))
    }
}

/**
 * One album: its newest photos stacked one over the other, slightly turned, the newest on top;
 * the album's name and its number of photos under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: Album, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp)) {
            val covers = album.covers.take(3)
            if (covers.isEmpty()) {
                Box(Modifier.matchParentSize().clip(Corner).background(p.chip))
            }
            // Drawn back to front, so the newest photo (the first) ends up on top, straight.
            covers.indices.reversed().forEach { index ->
                val uri = remember(covers[index]) { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, covers[index]) }
                AsyncImage(
                    model = ImageRequest.Builder(context).data(uri).size(360).crossfade(true).build(),
                    contentDescription = if (index == 0) album.title else null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .rotate(STACK_ANGLES[index])
                        .clip(Corner)
                        .background(p.chip)
                        .border(2.dp, p.paper, Corner),
                )
            }
            if (selecting) SelectTick(selected, Modifier.align(Alignment.TopEnd))
        }
        Text(albumTitle(album.title), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            "${Actions.formatCount(album.count.toLong())} photos",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * An album's name as shown (Cip, 2026-09-16): the first letter of every word upper-cased, the
 * rest left as written - "my wife" shows as "My Wife", "iPhone 15" as "IPhone 15". Only the
 * display changes; the album still filters on the value as stored.
 */
private fun albumTitle(title: String): String =
    title.split(" ").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }

/** The turn of each photo in a cover stack: the newest straight, the two under it fanned out. */
private val STACK_ANGLES = listOf(0f, -6f, 5f)
