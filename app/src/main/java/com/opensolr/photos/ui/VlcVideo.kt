package com.opensolr.photos.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.asCoroutineDispatcher
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * A clip played by VLC: its own decoders (the phone's hardware first, software when that cannot), late frames
 * dropped so picture and sound stay in time, any container and codec VLC reads. With its own controls: play and
 * pause, the slider and the times; they show on a tap and hide after a few seconds of play.
 */
@Composable
fun VlcVideo(
    uri: Uri,
    active: Boolean,
    startMs: Long = 0L,
    modifier: Modifier = Modifier,
    controlsRoom: androidx.compose.ui.unit.Dp = 0.dp,
    onControls: (Boolean) -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val context = LocalContext.current
    val p = LocalPalette.current
    var playing by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var length by remember(uri) { mutableLongStateOf(0L) }
    var dragging by remember(uri) { mutableStateOf<Float?>(null) }
    var shown by remember(uri) { mutableStateOf(true) }
    var touched by remember(uri) { mutableLongStateOf(System.currentTimeMillis()) }
    val layout = remember(uri) { VLCVideoLayout(context) }
    // VLC is started and stopped off the screen's thread: both can take seconds, and the screen never waits
    val engine by androidx.compose.runtime.produceState<Triple<LibVLC, MediaPlayer, android.os.ParcelFileDescriptor?>?>(null, uri) {
        value = kotlinx.coroutines.withContext(vlcThread) {
            val fd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            val vlc = LibVLC(context, arrayListOf("--no-video-title-show", "--audio-time-stretch"))
            val player = MediaPlayer(vlc)
            if (fd != null) {
                val media = Media(vlc, fd.fileDescriptor)
                // the phone's hardware decoders through Android's native interface (no object made per frame), software when they refuse
                media.addOption(":codec=mediacodec_ndk,avcodec,all")
                player.media = media
                media.release()
            }
            Triple(vlc, player, fd)
        }
    }
    val ready = engine
    if (ready == null) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }
    val player = ready.second
    // the picture is bound to the surface only once it is on screen with a size: bound earlier, VLC gets a
    // surface of no size and plays the sound alone
    var bound by remember(uri) { mutableStateOf(false) }
    DisposableEffect(uri) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> playing = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> { playing = false; position = length; shown = true }
                MediaPlayer.Event.TimeChanged -> position = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EncounteredError -> onFailed()
            }
        }
        val bind = {
            if (!bound) {
                player.attachViews(layout, null, false, false)
                player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                bound = true
            }
        }
        val onLayout = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (v.width > 0 && v.height > 0) v.post { bind() } }
        layout.addOnLayoutChangeListener(onLayout)
        if (layout.isAttachedToWindow && layout.width > 0 && layout.height > 0) bind()
        if (ready.third == null) onFailed()
        onDispose {
            layout.removeOnLayoutChangeListener(onLayout)
            player.setEventListener(null)
            runCatching { player.detachViews() }
            onControls(false)
            vlcExecutor.execute {
                runCatching { player.stop() }
                runCatching { player.release() }
                runCatching { ready.first.release() }
                runCatching { ready.third?.close() }
            }
        }
    }
    LaunchedEffect(active, bound) {
        if (!bound) return@LaunchedEffect
        if (active) {
            if (startMs > 0 && position == 0L) player.time = startMs
            player.play()
        } else player.pause()
    }
    // the controls hide after three seconds of play without a touch
    LaunchedEffect(shown, playing, touched) {
        onControls(shown)
        if (shown && playing && dragging == null) {
            kotlinx.coroutines.delay(3000)
            if (System.currentTimeMillis() - touched >= 2900) shown = false
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { layout }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(uri) { detectTapGestures(onTap = { shown = !shown; touched = System.currentTimeMillis() }) })
        if (shown) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color(0x99000000), CircleShape)
                    .pointerInput(uri) { detectTapGestures(onTap = { touched = System.currentTimeMillis(); if (player.isPlaying) player.pause() else { if (length > 0 && position >= length - 300) player.time = 0; player.play() } }) },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    // pause: two bars
                    Row { Box(Modifier.size(8.dp, 28.dp).background(Color.White)); Spacer(Modifier.width(8.dp)); Box(Modifier.size(8.dp, 28.dp).background(Color.White)) }
                } else Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = controlsRoom).navigationBarsPadding().background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shownPos = dragging?.let { (it * length).toLong() } ?: position
                Text(clock(shownPos), color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Slider(
                    value = dragging ?: if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it; touched = System.currentTimeMillis() },
                    onValueChangeFinished = { dragging?.let { if (length > 0) player.time = (it * length).toLong() }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = p.accentFill, activeTrackColor = p.accentFill, inactiveTrackColor = Color(0x66FFFFFF)),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(clock(length), color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** One thread for starting and stopping VLC, in order, never on the screen's thread. */
private val vlcExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
private val vlcThread = vlcExecutor.asCoroutineDispatcher()

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}
