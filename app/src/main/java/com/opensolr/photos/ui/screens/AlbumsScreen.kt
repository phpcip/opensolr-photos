package com.opensolr.photos.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.opensolr.photos.ui.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val view = LocalView.current
    val albumScope = rememberCoroutineScope()

    val selecting = state.selectedAlbums.isNotEmpty() || state.selectedSections.isNotEmpty()

    val folded = state.foldedAlbumSections
    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.albumPhotosDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 8.dp)) { ScreenHeader(stringResource(R.string.al_title), onBack = { viewModel.back() }) }
        if (state.albumsLoading || state.albumsWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }
        state.albumsError?.let {
            Notice(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (!state.albumsLoading && state.albumsError == null && state.albums.isEmpty()) {
            Text(
                stringResource(R.string.al_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }

        if (state.albums.isNotEmpty()) {
            val anyFolded = state.albums.any { it.title in folded }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {

                val allPicked = state.albums.all { it.title in state.selectedSections }
                IconAction(
                    icon = if (allPicked) R.drawable.ic_check_none else R.drawable.ic_check_all,
                    label = if (allPicked) stringResource(R.string.al_check_none) else stringResource(R.string.al_check_all),
                    active = allPicked,
                    onClick = {
                        Haptics.tick(view, strong = false)
                        viewModel.selectAllAlbums(!allPicked)
                    },
                )
                IconAction(
                    icon = if (anyFolded) R.drawable.ic_expand_all else R.drawable.ic_collapse_all,
                    label = if (anyFolded) stringResource(R.string.al_expand_all) else stringResource(R.string.al_collapse_all),
                    onClick = {
                        Haptics.tick(view, strong = false)
                        viewModel.setAlbumSections(if (anyFolded) emptySet() else state.albums.map { it.title }.toSet())
                    },
                )
            }
        }

        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.albumsLoading) { if (!state.albumsLoading) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.albumsLoading,

            onRefresh = { Haptics.thud(view); pulled = true; viewModel.openAlbums(force = true) },
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
                                Haptics.tick(view, strong = false)
                                if (selecting) viewModel.toggleSectionSelected(section.title)
                                else viewModel.toggleAlbumSection(section.title)
                            },
                            onLongClick = {
                                if (!selecting) Haptics.tick(view, strong = true)
                                viewModel.toggleSectionSelected(section.title)
                            },
                            onTick = { Haptics.tick(view, strong = false); viewModel.toggleSectionSelected(section.title) },
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
                                onTick = { viewModel.toggleAlbumSelected(album) },
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
            DockAction(R.drawable.ic_share, stringResource(R.string.al_share), enabled = shareEnabled) {

                viewModel.withSelectedAlbumPhotos { photos ->
                    albumScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Actions.sharePhotos(context, photos) }
                    }
                }
            }
            DockAction(R.drawable.ic_delete, stringResource(R.string.al_delete), enabled = !state.albumsWorking) { confirmDelete = true }
            Column(
                Modifier
                    .clip(Corner)
                    .combinedClickable(onClick = { viewModel.clearAlbumSelection() })
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.al_cancel), tint = p.ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.al_cancel), style = MaterialTheme.typography.labelSmall, color = p.ink, maxLines = 1)
            }
        }
    }

    if (confirmDelete && selecting) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.paper,
            title = { Text(stringResource(R.string.al_delete_q), color = p.ink) },
            text = { Text(deleteWarning(LocalContext.current, state), color = p.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.withSelectedAlbumPhotos { photos ->
                        pendingDelete = photos.map { it.id }.toSet()
                        albumScope.launch {
                            val sender = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                Actions.deleteRequest(context, Actions.contentUris(context, photos))
                            }
                            if (sender != null) {
                                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            } else {
                                viewModel.albumPhotosDeleted(pendingDelete)
                                pendingDelete = emptySet()
                            }
                        }
                    }
                }) { Text(stringResource(R.string.al_delete), color = Color(0xFFE53E3E)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.al_cancel), color = p.ink) } },
        )
    }
    }
}

private fun deleteWarning(context: android.content.Context, state: UiState): String {
    val sections = state.albums.filter { it.title in state.selectedSections }.map { sectionTitle(context, it.title) }
    val albums = state.albums.filter { it.title !in state.selectedSections }
        .flatMap { it.albums }
        .filter { "${it.field}:${it.value}" in state.selectedAlbums }
        .map { albumTitle(it.title) }
    val parts = ArrayList<String>()
    if (sections.isNotEmpty()) {
        parts += context.getString(R.string.al_del_sections, sections.joinToString(", "))
    }
    if (albums.isNotEmpty()) {
        parts += if (albums.size == 1) context.getString(R.string.al_del_album, albums[0])
        else context.getString(R.string.al_del_albums, albums.joinToString(", "))
    }
    parts += context.getString(R.string.al_del_note)
    return parts.joinToString("\n\n")
}

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

    onTick: () -> Unit,
) {
    val p = LocalPalette.current
    val band = headingBand(0)
    val onBand = p.ink
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(Corner)
            .background(band)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (folded) stringResource(R.string.al_open_section) else stringResource(R.string.al_fold_section),
            tint = p.accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            sectionTitle(LocalContext.current, title).let { shown -> if (folded) "$shown (${Actions.formatCount(count.toLong())})" else shown },
            style = headingStyle(0),
            fontWeight = FontWeight.Bold,
            color = onBand,
            modifier = Modifier.weight(1f),
        )
        if (selecting) PickTick(selected, onTick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: Album, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onTick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp)) {
            val covers = album.covers.take(3)
            if (covers.isEmpty()) {
                Box(Modifier.matchParentSize().clip(Corner).background(p.chip))
            }

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
            if (selecting) PickTick(selected, onTick, Modifier.align(Alignment.TopEnd))
        }
        Text(albumTitle(album.title), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            pluralStringResource(R.plurals.al_n_photos, album.count, Actions.formatCount(album.count.toLong())),
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun sectionTitle(context: android.content.Context, title: String): String = when (title) {
    "People" -> context.getString(R.string.al_sec_people)
    "My tags (Albums)" -> context.getString(R.string.al_sec_tags)
    "Things" -> context.getString(R.string.al_sec_things)
    "Years" -> context.getString(R.string.al_sec_years)
    "Places" -> context.getString(R.string.al_sec_places)
    "Cameras" -> context.getString(R.string.al_sec_cameras)
    else -> title
}

private fun albumTitle(title: String): String =
    title.split(" ").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }

private val STACK_ANGLES = listOf(0f, -6f, 5f)
