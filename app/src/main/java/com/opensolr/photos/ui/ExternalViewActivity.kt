package com.opensolr.photos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensolr.photos.R

/**
 * A photo, a clip or a sound opened from another app (a file manager, a camera, a chat): shown on its own, over that
 * app, whether or not it is in the library. Back returns to the app it came from.
 */
class ExternalViewActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val name = displayName(uri)
        val mime = listOf(
            intent.type,
            runCatching { contentResolver.getType(uri) }.getOrNull(),
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()),
        ).firstOrNull { it != null && isMedia(it) }.orEmpty()
        setContent { ExternalView(uri, mime, name) }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

private fun isMedia(mime: String) = mime.startsWith("image/") || isPlayed(mime)

private fun isPlayed(mime: String) = mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/ogg"

@Composable
private fun ExternalView(uri: Uri, mime: String, name: String) {
    val context = LocalContext.current
    // a file whose type says nothing useful is tried as a photo, then in the player
    var played by remember(uri) { mutableStateOf(isPlayed(mime)) }
    var tried by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    val other = { if (tried) failed = true else { tried = true; played = !played } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.ext_cannot_open), color = Color.White) }
            played -> ExternalClip(uri, name, other)
            else -> ExternalPhoto(uri, other)
        }
        IconButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: mime.ifEmpty { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.act_share), tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** The photo at full size, pinch to zoom and pan, double tap to zoom in or back. */
@Composable
private fun ExternalPhoto(uri: Uri, onFail: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val request = remember(uri) { ImageRequest.Builder(context).data(uri).size(PHOTO_EDGE).build() }
    val scope = rememberCoroutineScope()
    var glide by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val panDecay = rememberSplineBasedDecay<Float>()
    Box(
        Modifier.fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = { glide?.cancel(); if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = DOUBLE_TAP_ZOOM })
            }
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    glide?.cancel()
                    val tracker = VelocityTracker()
                    var panned = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            tracker.resetTracking()
                            panned = false
                        } else {
                            event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom == 1f && (pan == Offset.Zero || scale <= 1f)) continue
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        val limitX = size.width * (scale - 1f) / 2f
                        val limitY = size.height * (scale - 1f) / 2f
                        offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                        if (event.changes.size == 1) panned = true
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    // let go while panning a zoomed photo: it keeps going and slows down
                    if (panned && scale > 1f) {
                        val limit = Offset(size.width * (scale - 1f) / 2f, size.height * (scale - 1f) / 2f)
                        val velocity = tracker.calculateVelocity()
                        glide = scope.launch { glidePan(velocity, offset, limit, panDecay) { offset = it } }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { onFail() },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}

/** The clip or the sound in the app's player, with its controls; a sound keeps them on screen, under its name. */
@Composable
private fun ExternalClip(uri: Uri, name: String, onFail: () -> Unit) {
    val context = LocalContext.current
    var sound by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onFail() }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    sound = !tracks.isEmpty && !tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                }
            })
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowPreviousButton(false)
                setShowNextButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.controllerHideOnTouch = !sound
            view.controllerShowTimeoutMs = if (sound) 0 else androidx.media3.ui.PlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
            if (sound) view.showController()
        },
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    )
    if (sound && name.isNotEmpty()) {
        Text(
            name,
            color = Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 20.dp, end = 64.dp),
        )
    }
}

private const val PHOTO_EDGE = 4096
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
