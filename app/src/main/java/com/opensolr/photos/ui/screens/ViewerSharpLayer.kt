package com.opensolr.photos.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The original file's pixels for the visible part of a zoomed photo, decoded region by region so any size fits in memory. */
@Composable
internal fun ViewerSharpLayer(
    uri: Uri,
    active: Boolean,
    baseSize: Size?,
    scale: () -> Float,
    offset: () -> Offset,
    lift: () -> Float,
) {
    val context = LocalContext.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var tile by remember(uri) { mutableStateOf<SharpTile?>(null) }
    val source = remember(uri) { SharpSource(context.contentResolver, uri) }
    DisposableEffect(source) { onDispose { source.close() } }

    LaunchedEffect(source, active, baseSize) {
        if (!active || baseSize == null) { tile = null; return@LaunchedEffect }
        snapshotFlow { Triple(scale(), offset(), box) }.collectLatest { (s, t, size) ->
            if (s <= 1.01f || size.width == 0 || size.height == 0) { tile = null; return@collectLatest }
            delay(SHARP_SETTLE_MS)
            tile = withContext(Dispatchers.IO) { source.decode(size, s, t, baseSize) } ?: tile
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
                translationX = offset().x
                translationY = offset().y + lift()
            },
    ) {
        val shown = tile ?: return@Canvas
        if (!active || scale() <= 1.01f || shown.box != box) return@Canvas
        drawImage(shown.image, dstOffset = shown.at, dstSize = shown.size, filterQuality = FilterQuality.High)
    }
}

private class SharpTile(val image: ImageBitmap, val at: IntOffset, val size: IntSize, val box: IntSize)

/** Opens the file lazily on the first zoom and keeps one region decoder for it while the page is on screen. */
private class SharpSource(private val resolver: android.content.ContentResolver, private val uri: Uri) {
    private val lock = Mutex()
    private var decoder: BitmapRegionDecoder? = null
    private var orientation = ExifInterface.ORIENTATION_NORMAL
    private var failed = false
    private var closed = false

    suspend fun decode(box: IntSize, s: Float, t: Offset, baseSize: Size): SharpTile? = lock.withLock {
        try { region(box, s, t, baseSize) } finally { if (closed) { decoder?.recycle(); decoder = null } }
    }

    private fun region(box: IntSize, s: Float, t: Offset, baseSize: Size): SharpTile? {
        if (closed || failed) return null
        val d = decoder ?: open() ?: return null
        val rw = d.width
        val rh = d.height
        val turned = orientation in TURNED
        val ow = if (turned) rh else rw
        val oh = if (turned) rw else rh

        // bail out when the file does not match what the base image shows (a decoder that already applied the rotation)
        if (baseSize.width <= 0f || baseSize.height <= 0f) return null
        if (kotlin.math.abs(ow.toFloat() / oh - baseSize.width / baseSize.height) > 0.02f * (ow.toFloat() / oh)) return null

        val w = box.width.toFloat()
        val h = box.height.toFloat()
        val fit = min(w / ow, h / oh)
        val left = (w - ow * fit) / 2f
        val top = (h - oh * fit) / 2f

        // visible part of the layout under the current zoom, clipped to the photo
        val vl = max(left, w / 2f + (-w / 2f - t.x) / s)
        val vt = max(top, h / 2f + (-h / 2f - t.y) / s)
        val vr = min(left + ow * fit, w / 2f + (w / 2f - t.x) / s)
        val vb = min(top + oh * fit, h / 2f + (h / 2f - t.y) / s)
        if (vr <= vl || vb <= vt) return null

        val ox0 = ((vl - left) / fit).toInt().coerceIn(0, ow)
        val oy0 = ((vt - top) / fit).toInt().coerceIn(0, oh)
        val ox1 = kotlin.math.ceil((vr - left) / fit).toInt().coerceIn(0, ow)
        val oy1 = kotlin.math.ceil((vb - top) / fit).toInt().coerceIn(0, oh)
        if (ox1 <= ox0 || oy1 <= oy0) return null

        // largest power-of-two step that still gives at least one file pixel per screen pixel
        val perScreenPx = 1f / (fit * s)
        var sample = 1
        while (sample * 2 <= perScreenPx) sample *= 2

        val raw = rawRect(ox0, oy0, ox1, oy1, rw, rh)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val part = runCatching { d.decodeRegion(raw, opts) }.getOrNull() ?: return null
        val upright = orient(part)
        return SharpTile(
            image = upright.asImageBitmap(),
            at = IntOffset((left + ox0 * fit).roundToInt(), (top + oy0 * fit).roundToInt()),
            size = IntSize(((ox1 - ox0) * fit).roundToInt().coerceAtLeast(1), ((oy1 - oy0) * fit).roundToInt().coerceAtLeast(1)),
            box = box,
        )
    }

    private fun open(): BitmapRegionDecoder? {
        val made = runCatching {
            orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            resolver.openInputStream(uri)?.use {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(it)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it, false)
            }
        }.getOrNull()
        if (made == null) failed = true
        decoder = made
        return made
    }

    /** Oriented (as shown) rectangle back to the stored pixel grid. */
    private fun rawRect(x0: Int, y0: Int, x1: Int, y1: Int, rw: Int, rh: Int): Rect {
        fun map(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rw - x to y
            ExifInterface.ORIENTATION_ROTATE_180 -> rw - x to rh - y
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to rh - y
            ExifInterface.ORIENTATION_TRANSPOSE -> y to x
            ExifInterface.ORIENTATION_ROTATE_90 -> y to rh - x
            ExifInterface.ORIENTATION_TRANSVERSE -> rw - y to rh - x
            ExifInterface.ORIENTATION_ROTATE_270 -> rw - y to x
            else -> x to y
        }
        val a = map(x0, y0)
        val b = map(x1, y1)
        return Rect(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    private fun orient(part: Bitmap): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return part
        }
        return Bitmap.createBitmap(part, 0, 0, part.width, part.height, m, true)
    }

    fun close() {
        closed = true
        // a decode in flight releases it itself when it finishes
        if (lock.tryLock()) {
            try { decoder?.recycle(); decoder = null } finally { lock.unlock() }
        }
    }

    private companion object {
        val TURNED = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
    }
}

private const val SHARP_SETTLE_MS = 120L
