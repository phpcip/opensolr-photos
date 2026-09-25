package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.Haptics
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.opensolr.photos.ui.Actions
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

private val MIN_TOP_ROOM = 24.dp

private val MIN_BOTTOM_ROOM = 28.dp

@Composable
fun PlacePickerDialog(
    start: Pair<Double, Double>?,
    viewModel: com.opensolr.photos.ui.AppViewModel,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit,
) {
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

    if (start == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.opensolr.photos.media.DevicePlace.now(context)?.let { here ->
                mapView.controller.setZoom(15.0)
                mapView.controller.setCenter(GeoPoint(here.latitude, here.longitude))
                centre = here.latitude to here.longitude
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {

        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            }
        }
        val room = androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues()
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        Column(
            Modifier
                .fillMaxSize()
                .background(p.paper)
                .padding(
                    top = maxOf(room.calculateTopPadding(), MIN_TOP_ROOM),
                    bottom = maxOf(room.calculateBottomPadding(), MIN_BOTTOM_ROOM),
                    start = room.calculateStartPadding(direction),
                    end = room.calculateEndPadding(direction),
                )
        ) {
            Box(Modifier.padding(horizontal = 8.dp)) { ScreenHeader(stringResource(R.string.pp_title), onBack = onDismiss) }
            Text(
                stringResource(R.string.pp_lead),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))

            var term by remember { mutableStateOf("") }
            var hits by remember { mutableStateOf<List<com.opensolr.photos.net.PlaceHit>>(emptyList()) }
            var searched by remember { mutableStateOf(false) }

            var picked by remember { mutableStateOf<String?>(null) }

            var searching by remember { mutableStateOf(false) }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            LaunchedEffect(term) {
                val q = term.trim()
                if (q == picked) {
                    hits = emptyList()
                    searched = false
                    searching = false
                    return@LaunchedEffect
                }
                if (q.length < 2) {
                    hits = emptyList()
                    searched = false
                    searching = false
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(350)
                searching = true
                try {
                    hits = viewModel.searchPlaces(q)
                    searched = true
                } finally {

                    searching = false
                }
            }
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text(stringResource(R.string.pp_search), color = p.muted) },
                trailingIcon = {
                    if (searching) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = p.accent,
                            strokeWidth = 2.dp,
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(2.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.hairline,
                    cursorColor = p.accent,
                    focusedTextColor = p.ink,
                    unfocusedTextColor = p.ink,
                ),
            )
            if (term.trim().length >= 2 && (hits.isNotEmpty() || (searched && !searching))) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .heightIn(max = 190.dp)
                        .background(p.paper, RoundedCornerShape(2.dp))
                        .border(1.dp, p.hairline, RoundedCornerShape(2.dp))
                        .verticalScroll(rememberScrollState())
                ) {
                    if (hits.isEmpty()) {
                        Text(
                            stringResource(R.string.pp_search_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.muted,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }

                    val repeated = hits.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
                    hits.forEach { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Haptics.tap(view)
                                    keyboard?.hide()
                                    picked = hit.label
                                    term = hit.label
                                    hits = emptyList()
                                    searched = false

                                    mapView.controller.setZoom(if (hit.kind == "city") 13.0 else 9.0)
                                    mapView.controller.setCenter(GeoPoint(hit.lat, hit.lon))
                                    centre = hit.lat to hit.lon
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                hit.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.ink,
                                maxLines = 2,
                            )
                            if (hit.label in repeated) {
                                Text(
                                    listOfNotNull(
                                        hit.population.takeIf { it > 0 }?.let { Actions.formatCount(it.toLong()) + " \u00b7 " },
                                        String.format(Locale.US, "%.4f, %.4f", hit.lat, hit.lon),
                                    ).joinToString(""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = p.muted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())

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
