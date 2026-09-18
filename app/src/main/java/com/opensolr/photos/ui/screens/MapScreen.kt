package com.opensolr.photos.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensolr.photos.R
import com.opensolr.photos.search.PhotoPin
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.SectionLabel
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.map.PhotoCluster
import com.opensolr.photos.ui.map.PhotoClusterOverlay
import com.opensolr.photos.ui.theme.LocalPalette
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.ceil
import kotlin.math.max

/**
 * The map: every photo of the current search that has a GPS position, grouped into thumbnail
 * markers. Tapping a group zooms into it, or opens its photos once it is small enough.
 * "Search this area" turns the visible area into a radius filter on the photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    // The photo opened full screen from a group, and the photos waiting on the system's own
    // confirmation before they are removed.
    var viewing by remember { mutableStateOf<com.opensolr.photos.search.PhotoHit?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Read here, on the screen, where the system bars are reported correctly; the viewer runs in
    // a dialog window, where some phones report nothing at all.
    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val topInset = systemBars.calculateTopPadding()
    val bottomInset = systemBars.calculateBottomPadding()
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.removeDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var group by remember { mutableStateOf<PhotoCluster?>(null) }
    var fitted by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Pinch and double-tap zoom, plus the +/- buttons that appear on touch.
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            setHorizontalMapRepetitionEnabled(false)
            if (dark) overlayManager.tilesOverlay.setColorFilter(darkMapFilter())
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(30.0, 10.0))
        }
    }
    val onTap by rememberUpdatedState<(PhotoCluster) -> Unit> { cluster ->
        // A tap on a group opens its photos, whatever its size. It used to zoom in instead, which
        // meant tapping five or six times on a place where a whole afternoon was photographed -
        // and the photos never came apart, because they were taken in the same spot
        // (Cip, 2026-09-18). Zooming is the owner's business: pinch, double tap, the +/- buttons.
        group = cluster
    }
    val overlay = remember {
        PhotoClusterOverlay(
            context,
            // The badge of a group carries its count, so it takes the fill tone and its white:
            // the bright accent with white on it would be too faint to read on the map.
            PhotoClusterOverlay.MarkerColors(p.chip.toArgb(), p.paper.toArgb(), p.hairline.toArgb(), p.accentFill.toArgb(), p.onAccentFill.toArgb()),
        ) { onTap(it) }.also { mapView.overlays.add(it) }
    }

    // Follows the activity: tiles stop loading while paused, and everything is released on leave.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // New pins: hand them to the overlay and frame them once (or open on the requested point).
    LaunchedEffect(state.pins, state.pinsLoading) {
        overlay.pins = state.pins
        mapView.invalidate()
        if (state.pinsLoading || fitted) return@LaunchedEffect
        val focus = state.mapFocus
        if (focus != null) {
            mapView.controller.setZoom(focus.zoom)
            mapView.controller.setCenter(GeoPoint(focus.lat, focus.lon))
            fitted = true
        } else if (state.pins.isNotEmpty()) {
            val box = BoundingBox.fromGeoPoints(state.pins.map { GeoPoint(it.lat, it.lon) })
            mapView.post {
                if (mapView.width > 0 && mapView.height > 0) {
                    if (box.latitudeSpan < 0.0005 && box.longitudeSpanWithDateLine < 0.0005) {
                        mapView.controller.setZoom(16.0)
                        mapView.controller.setCenter(box.centerWithDateLine)
                    } else {
                        mapView.zoomToBoundingBox(box, false, (64 * context.resources.displayMetrics.density).toInt())
                    }
                }
            }
            fitted = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(start = 8.dp)) { ScreenHeader("Map", onBack = { viewModel.back() }) }
            Text(
                "${Actions.formatCount(state.pins.size.toLong())} with a place",
                style = MaterialTheme.typography.bodySmall, color = p.muted,
            )
            // Search this area: the visible map becomes a radius filter on the photos.
            IconButton(onClick = {
                // Radius = distance from the centre to the farthest visible corner, rounded up.
                val box = mapView.boundingBox
                val center = GeoPoint(box.centerLatitude, box.centerLongitude)
                val corner = GeoPoint(box.latNorth, box.lonEast)
                val km = max(0.5, ceil(center.distanceToAsDouble(corner) / 100.0) / 10.0)
                viewModel.searchNear(center.latitude, center.longitude, km)
            }) {
                Icon(Icons.Filled.Search, contentDescription = "Search this area", tint = p.accent, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { fitted = false; viewModel.loadPins() }, enabled = !state.pinsLoading) {
                Icon(painterResource(R.drawable.ic_reload), contentDescription = "Reload", tint = p.accent, modifier = Modifier.size(20.dp))
            }
        }
        if (state.pinsLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }
        state.pinsError?.let { Notice(it, title = "The map could not be loaded", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        if (!state.pinsLoading && state.pins.isEmpty() && state.pinsError == null) {
            Notice(
                if (state.query.isBlank() && state.filters.count == 0) "None of your indexed photos carries a GPS position yet."
                else "No photo with a GPS position matches this search.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            // clipToBounds: osmdroid paints tiles beyond its bounds otherwise, over the header.
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())
        }
    }

    // A photo of a place opens exactly as a photo of a search does: full screen, in the app,
    // swiped through the photos of that place (Cip, 2026-09-16). It used to be handed to the
    // gallery, which knows nothing about the group you tapped.
    group?.let { cluster ->
        GroupSheet(cluster, onDismiss = { group = null }, onShowPhoto = { hit -> viewing = hit })
    }
    viewing?.let { hit ->
        // The group's photos and where the opened one sits among them, worked out when it opens -
        // not again on every redraw behind it (Cip, 2026-09-18).
        val photos = remember(group, hit.id) { group?.pins?.map { it.hit } ?: listOf(hit) }
        val startAt = remember(photos, hit.id) { photos.indexOfFirst { it.id == hit.id }.coerceAtLeast(0) }
        PhotoViewer(
            hits = photos,
            start = startAt,
            onClose = { viewing = null },
            onSheet = { photo, close, openEdit ->
                DetailsSheet(hit = photo, viewModel = viewModel, onDismiss = close, onLeave = { viewing = null }, onEdit = { openEdit(it); close() })
            },
            onEditSheet = { photo, close -> EditSheet(hit = photo, state = state, viewModel = viewModel, onDismiss = close) },
            topInset = topInset,
            bottomInset = bottomInset,
            onNeedMore = {},
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
}

/**
 * How the map is darkened at night: not turned inside out, but dimmed and drained of some of its
 * colour, the way the night mode of any map app does it (Cip, 2026-09-18). Turning the colours over
 * made the sea orange and the mountains purple; here the sea stays blue and the land stays land.
 */
private fun darkMapFilter(): android.graphics.ColorMatrixColorFilter {
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0.85f) }
    // Down to a little over a third of its brightness, with the blues kept a touch stronger than
    // the rest so water still reads as water.
    matrix.postConcat(
        android.graphics.ColorMatrix(
            floatArrayOf(
                0.62f, 0f, 0f, 0f, 2f,
                0f, 0.64f, 0f, 0f, 2f,
                0f, 0f, 0.72f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    return android.graphics.ColorMatrixColorFilter(matrix)
}

/**
 * The photos of a tapped group, as a grid of thumbnails. A tap opens the photo in the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupSheet(cluster: PhotoCluster, onDismiss: () -> Unit, onShowPhoto: (com.opensolr.photos.search.PhotoHit) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    // Dragged up, the sheet grows to the whole screen: a place where a hundred photos were taken
    // is a grid worth reading, not a peep-hole (Cip, 2026-09-18).
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = p.paper,
        shape = RoundedCornerShape(2.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionLabel("${Actions.formatCount(cluster.pins.size.toLong())} photo${if (cluster.pins.size == 1) "" else "s"} here")
            // Where "here" is, in the words the photos themselves carry: the most common city and
            // country of the group (Cip, 2026-09-18). Silent when none of them knows.
            val place = remember(cluster) {
                fun commonest(of: (com.opensolr.photos.search.PhotoHit) -> String?): String? =
                    cluster.pins.mapNotNull { of(it.hit)?.trim()?.takeIf { v -> v.isNotEmpty() } }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                listOfNotNull(commonest { it.city }, commonest { it.country }).joinToString(" · ").ifBlank { null }
            }
            place?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(cluster.pins, key = { it.hit.id }) { pin ->
                    val uri = remember(pin.hit.mediaId) { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pin.hit.mediaId) }
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(uri).size(320).setParameter("bytes", pin.hit.sizeBytes).crossfade(true).build(),
                        contentDescription = pin.hit.meaning.ifBlank { pin.hit.fileName },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(p.chip)
                            .combinedClickable(onClick = { onShowPhoto(pin.hit) }),
                    )
                }
            }
        }
    }
}
