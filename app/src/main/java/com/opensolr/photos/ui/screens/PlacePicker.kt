package com.opensolr.photos.ui.screens

import com.opensolr.photos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.theme.LocalPalette
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The map on which the owner puts a photo somewhere else (Cip, 2026-09-19): the pin stays in the
 * middle and the map moves under it - dragged, or brought there by a tap. [start] is where the
 * photo is now; without one the map opens on the whole world. [onPick] gets the point under the
 * pin.
 */
@Composable
fun PlacePickerDialog(start: Pair<Double, Double>?, onDismiss: () -> Unit, onPick: (Double, Double) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var centre by remember { mutableStateOf(start) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            setHorizontalMapRepetitionEnabled(false)
            if (start != null) {
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(start.first, start.second))
            } else {
                controller.setZoom(3.0)
                controller.setCenter(GeoPoint(30.0, 10.0))
            }
            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                    centre = mapCenter.latitude to mapCenter.longitude
                    return false
                }

                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
            })
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                    point?.let { controller.animateTo(it) }
                    return point != null
                }

                override fun longPressHelper(point: GeoPoint?): Boolean = false
            }))
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // The system bars as the activity's own window sees them: a dialog window reports none on
    // some phones, which put the buttons under the navigation bar (Cip, 2026-09-19).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bars = remember {
        var host: android.content.Context? = context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        val decor = (host as? android.app.Activity)?.window?.decorView
        val insets = decor?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        with(density) { (insets?.top ?: 0).toDp() to (insets?.bottom ?: 0).toDp() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.paper).padding(top = bars.first, bottom = bars.second)) {
            Box(Modifier.padding(horizontal = 8.dp)) { ScreenHeader(stringResource(R.string.pp_title), onBack = onDismiss) }
            Text(
                stringResource(R.string.pp_lead),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())
                // The pin's point is its bottom tip, so it is lifted by half its height to put the
                // tip on the centre of the map.
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = p.accentFill,
                    modifier = Modifier.size(44.dp).offset(y = (-22).dp),
                )
                Box(Modifier.size(6.dp).background(p.ink, CircleShape).border(1.dp, p.paper, CircleShape))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    centre?.let { (lat, lon) -> String.format(Locale.US, "%.5f, %.5f", lat, lon) } ?: stringResource(R.string.pp_move),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.ink,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.pp_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                    AccentButton(
                        stringResource(R.string.pp_use),
                        onClick = { centre?.let { (lat, lon) -> onPick(lat, lon) } },
                        enabled = centre != null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Asks Android, once for all of them, to let the app write the positions given to new photos into
 * their files, and writes them on yes (Cip, 2026-09-19). Returns the function that starts it; the
 * screens that offer it hold one each.
 */
@Composable
fun rememberPlaceWriter(viewModel: com.opensolr.photos.ui.AppViewModel): () -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.finishPlaceWrite(result.resultCode == android.app.Activity.RESULT_OK) }
    return remember(viewModel) {
        {
            scope.launch {
                val sender = viewModel.preparePlaceWrite() ?: return@launch
                launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            }
            Unit
        }
    }
}

/**
 * Keeps a bottom sheet where it is while its form is scrolled (Cip, 2026-09-19): whatever the form
 * does not scroll itself - a pull down at its top, a fling past its end - is taken here, so the
 * sheet under it never moves and never closes on its own. Put before the form's verticalScroll.
 */
@Composable
fun rememberNoSheetDrag(): androidx.compose.ui.input.nestedscroll.NestedScrollConnection = remember {
    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset = available

        override suspend fun onPostFling(
            consumed: androidx.compose.ui.unit.Velocity,
            available: androidx.compose.ui.unit.Velocity,
        ): androidx.compose.ui.unit.Velocity = available
    }
}
