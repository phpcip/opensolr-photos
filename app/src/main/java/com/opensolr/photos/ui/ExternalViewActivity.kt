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
 * A photo or a clip opened from another app (a file manager, a camera, a chat): shown on its own, over that app,
 * whether or not it is in the library. Back returns to the app it came from.
 */
class ExternalViewActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val mime = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        setContent { ExternalView(uri, mime.startsWith("video/")) }
    }
}

@Composable
private fun ExternalView(uri: Uri, video: Boolean) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video) ExternalClip(uri) else ExternalPhoto(uri)
        IconButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: if (video) "video/*" else "image/*"
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
private fun ExternalPhoto(uri: Uri) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var failed by remember { mutableStateOf(false) }
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
            onError = { failed = true },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        if (failed) Text(stringResource(R.string.ext_cannot_open), color = Color.White)
    }
}

/** The clip in the app's player, with its controls. */
@Composable
private fun ExternalClip(uri: Uri) {
    val context = LocalContext.current
    var failed by remember(uri) { mutableStateOf(false) }
    // VLC for what the phone's decoders cannot play smoothly, or when the phone's player fails or drops frames
    var vlc by remember(uri) { mutableStateOf<Long?>(null) }
    val checked by androidx.compose.runtime.produceState<Boolean?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.opensolr.photos.media.VideoSupport.phoneCanPlay(context, uri) }
    }
    if (checked == false && vlc == null) vlc = 0L
    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.ext_cannot_open), color = Color.White) }
        return
    }
    vlc?.let { startAt ->
        VlcVideo(uri = uri, active = true, startMs = startAt, modifier = Modifier.navigationBarsPadding(), onFailed = { failed = true })
        return
    }
    if (checked == null) return
    val player = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { vlc = currentPosition.coerceAtLeast(0L) }
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
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    )
}

private const val PHOTO_EDGE = 4096
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
