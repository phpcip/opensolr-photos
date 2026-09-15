package com.opensolr.photos.ui.screens

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

/**
 * Albums: the library grouped by what the index already knows about each photo - the owner's
 * tags, the words CLIP used most, places, cameras and years - every album with at least one
 * photos. Tapping one opens the photos grid filtered to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 8.dp)) { ScreenHeader("Albums", onBack = { viewModel.back() }) }
        if (state.albumsLoading) {
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
        // Swipe down asks the index again, as on the photos grid (Cip, 2026-09-15).
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.albumsLoading) { if (!state.albumsLoading) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && state.albumsLoading,
            onRefresh = { pulled = true; viewModel.openAlbums() },
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
                    item(key = "section:${section.title}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(section.title, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(section.albums, key = { "${it.field}:${it.value}" }) { album ->
                        AlbumCard(album, onClick = { viewModel.openAlbum(album) })
                    }
                }
            }
        }
    }
}

/**
 * One album: its newest photos stacked one over the other, slightly turned, the newest on top;
 * the album's name and its number of photos under it.
 */
@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
