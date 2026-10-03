package com.opensolr.photos.ui.screens

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.media.EditOp
import com.opensolr.photos.media.EditSession
import com.opensolr.photos.media.PhotoEditor
import com.opensolr.photos.media.ShapeFit
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.Haptics
import com.opensolr.photos.ui.IconButton
import com.opensolr.photos.ui.TextButton
import com.opensolr.photos.ui.tapClickable
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photos written over by the editor: the moment of the write, so every image of them is read again. */
internal object EditedPhotos {
    val stamps = mutableStateMapOf<Long, Long>()
    fun stamp(mediaId: Long): Long = stamps[mediaId] ?: 0L
}

private enum class Tool { Crop, Rotate, Resize, Draw, Text }

/** Crop, resize, rotate, draw and text on one photo; Save asks whether it replaces the original or becomes a copy. */
@Composable
internal fun PhotoEditorScreen(hit: PhotoHit, topInset: Dp, bottomInset: Dp, onClose: () -> Unit, onSaved: (overwrote: Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    // every step on the image runs here, one after the other, in the order it was asked for
    val worker = remember { Dispatchers.Default.limitedParallelism(1) }
    val previewEdge = remember(configuration) {
        with(density) { max(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx() }.coerceAtMost(PREVIEW_MAX_EDGE)
    }

    var session by remember { mutableStateOf<EditSession?>(null) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reduced by remember { mutableStateOf<IntArray?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgW by remember { mutableIntStateOf(0) }
    var imgH by remember { mutableIntStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var working by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(Tool.Crop) }
    var color by remember { mutableIntStateOf(PEN_COLORS.first()) }
    var penDp by remember { mutableFloatStateOf(PEN_DEFAULT_DP) }
    var textDp by remember { mutableFloatStateOf(TEXT_DEFAULT_DP) }
    var crop by remember { mutableStateOf<Rect?>(null) }
    val live = remember { mutableStateListOf<Offset>() }
    // marks handed to the worker and not yet back in [marks]: shown meanwhile, so nothing blinks
    val drawn = remember { mutableStateListOf<EditOp>() }
    var marks by remember { mutableStateOf<List<EditOp>>(emptyList()) }
    var shownVersion by remember { mutableIntStateOf(-1) }
    // the perfect shape a held stroke turns into; the mark being moved and how far
    var snapped by remember { mutableStateOf<EditOp.Shape?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var pendingOverwrite by remember { mutableStateOf<Boolean?>(null) }
    // image pixels per screen pixel as shown, so pen and text sizes picked on screen land the same in the photo
    var viewScale by remember { mutableFloatStateOf(1f) }

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    // a step queued on the worker; the screen shows the image again once it is done
    fun edit(step: (EditSession) -> Unit, after: () -> Unit = {}) {
        val s = session ?: return
        working++
        scope.launch {
            try {
                // the picture is fitted for the screen again only when it changed; marks are drawn over it
                val shown = withContext(worker) {
                    step(s)
                    if (s.baseVersion != shownVersion) s.baseVersion to s.preview(previewEdge).asImageBitmap() else null
                }
                shown?.let { shownVersion = it.first; preview = it.second }
                marks = s.marks
                imgW = s.width
                imgH = s.height
                canUndo = s.canUndo
                canRedo = s.canRedo
                changed = s.changed
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast(AppText.s(R.string.ed_too_big))
            } catch (e: Exception) {
                toast(AppText.s(R.string.ed_failed_step))
            } finally {
                after()
                working--
            }
        }
    }

    LaunchedEffect(hit.mediaId) {
        val found = withContext(Dispatchers.IO) { Actions.contentUris(app, listOf(hit)).firstOrNull() }
        if (found == null) { loadFailed = true; return@LaunchedEffect }
        uri = found
        working++
        try {
            val loaded = withContext(worker) {
                try { PhotoEditor.load(app, found) } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
            }
            if (loaded == null) { loadFailed = true; return@LaunchedEffect }
            if (loaded.originalWidth != loaded.bitmap.width || loaded.originalHeight != loaded.bitmap.height) {
                reduced = intArrayOf(loaded.originalWidth, loaded.originalHeight)
            }
            session = EditSession(loaded.bitmap)
        } finally {
            working--
        }
        edit({})
    }

    DisposableEffect(Unit) {
        onDispose {
            val s = session
            CoroutineScope(worker).launch { s?.release(); PhotoEditor.clearCache(app) }
        }
    }

    LaunchedEffect(tool, imgW, imgH) {
        crop = if (tool == Tool.Crop && imgW > 0) Rect(0f, 0f, imgW.toFloat(), imgH.toFloat()) else null
    }

    val cropPending = crop?.let { c -> c.left > 0.5f || c.top > 0.5f || c.right < imgW - 0.5f || c.bottom < imgH - 0.5f } == true

    fun applyCrop() {
        val c = crop ?: return
        if (!cropPending) return
        edit({ it.transform(EditOp.Crop(c.left.roundToInt(), c.top.roundToInt(), c.right.roundToInt(), c.bottom.roundToInt())) })
    }

    fun pick(next: Tool) {
        if (next == tool) return
        applyCrop()
        tool = next
    }

    fun close() {
        if (changed || cropPending || saving) askDiscard = true else onClose()
    }

    fun save(overwrite: Boolean) {
        val target = uri ?: return
        val s = session ?: return
        saving = true
        working++
        // the original's own kind of file; a kind that cannot be written (HEIC, GIF) becomes a JPEG copy
        val format = PhotoEditor.formatFor(hit.mime) ?: android.graphics.Bitmap.CompressFormat.JPEG
        scope.launch {
            try {
                val where = withContext(worker) {
                    val full = s.render()
                    val file = try { PhotoEditor.encode(app, full, format, target) } finally { full.recycle() }
                    if (overwrite) {
                        PhotoEditor.overwrite(app, target, file)
                        null
                    } else {
                        PhotoEditor.saveCopy(app, target, hit.fileName, hit.path, file, format)
                    }
                }
                if (overwrite) {
                    // every image of the old picture goes, wherever it is shown next
                    coil.Coil.imageLoader(app).memoryCache?.let { cache -> cache.keys.filter { it.key == target.toString() }.forEach { cache.remove(it) } }
                    EditedPhotos.stamps[hit.mediaId] = System.currentTimeMillis()
                }
                toast(if (where == null) AppText.s(R.string.ed_saved) else AppText.s(R.string.ed_saved_copy, where))
                onSaved(overwrite)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast(AppText.s(R.string.ed_failed))
            } catch (e: Exception) {
                toast(AppText.s(R.string.ed_failed))
            } finally {
                saving = false
                working--
            }
        }
    }

    // Android 11 and up: the system asks the owner once whether this app may change the file
    val writeAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val overwrite = pendingOverwrite
        pendingOverwrite = null
        if (result.resultCode == Activity.RESULT_OK && overwrite != null) save(overwrite)
    }
    // Android 8 and 9: writing into the photo folders takes the storage permission
    val storageAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val overwrite = pendingOverwrite
        pendingOverwrite = null
        if (granted && overwrite != null) save(overwrite) else if (!granted) toast(AppText.s(R.string.ed_failed))
    }

    fun choose(overwrite: Boolean) {
        askSave = false
        val target = uri ?: return
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                pendingOverwrite = overwrite
                storageAsk.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            overwrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingOverwrite = true
                val sender = MediaStore.createWriteRequest(app.contentResolver, listOf(target)).intentSender
                writeAsk.launch(IntentSenderRequest.Builder(sender).build())
            }
            overwrite -> {
                // Android 10: the write itself is refused once, with the question to ask
                val asked = try {
                    app.contentResolver.openOutputStream(target, "wa")?.close()
                    null
                } catch (e: android.app.RecoverableSecurityException) {
                    e.userAction.actionIntent.intentSender
                } catch (e: Exception) {
                    null
                }
                if (asked != null) {
                    pendingOverwrite = true
                    writeAsk.launch(IntentSenderRequest.Builder(asked).build())
                } else {
                    save(true)
                }
            }
            else -> save(false)
        }
    }

    val sides = WindowInsets.safeDrawing.asPaddingValues()
    val sideLeft = sides.calculateLeftPadding(LayoutDirection.Ltr)
    val sideRight = sides.calculateRightPadding(LayoutDirection.Ltr)

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.opensolr.photos.ui.KeyboardBack()
        val overflow = viewerWindowOverflow()
        val p = LocalPalette.current
        val busy = working > 0
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(
                    start = sideLeft,
                    end = sideRight,
                    top = maxOf(topInset, 24.dp),
                    bottom = maxOf(bottomInset, VIEWER_MIN_BOTTOM) + overflow,
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel), color = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { edit({ it.undo() }) }, enabled = canUndo && !saving) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.ed_undo), tint = Color.White.copy(alpha = if (canUndo) 1f else 0.3f))
                }
                IconButton(onClick = { edit({ it.redo() }) }, enabled = canRedo && !saving) {
                    Icon(painterResource(R.drawable.ic_redo), contentDescription = stringResource(R.string.ed_redo), tint = Color.White.copy(alpha = if (canRedo) 1f else 0.3f))
                }
                Spacer(Modifier.width(8.dp))
                val canSave = (changed || cropPending) && !saving && session != null
                Box(
                    Modifier
                        .clip(Corner)
                        .background(if (canSave) p.accentFill else Color.White.copy(alpha = 0.12f))
                        .tapClickable(enabled = canSave) { applyCrop(); askSave = true }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.ed_save), color = if (canSave) p.onAccentFill else Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelLarge)
                }
            }

            // wide sides: the crop handles stay clear of the edge where the system's back gesture starts
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = IMAGE_SIDE, vertical = 16.dp)) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val s = if (imgW > 0 && imgH > 0) min(boxW / imgW, boxH / imgH) else 1f
                val dispW = imgW * s
                val dispH = imgH * s
                val left = (boxW - dispW) / 2f
                val top = (boxH - dispH) / 2f
                LaunchedEffect(s) { viewScale = s }
                fun toImage(o: Offset) = Offset(((o.x - left) / s).coerceIn(0f, imgW.toFloat()), ((o.y - top) / s).coerceIn(0f, imgH.toFloat()))
                val handle = with(density) { HANDLE_REACH.toPx() }
                val minSide = with(density) { CROP_MIN.toPx() } / s

                // read inside the running gesture loop, so a new mark never restarts a gesture in progress
                val currentMarks by androidx.compose.runtime.rememberUpdatedState(marks)
                val gestures = when (tool) {
                    Tool.Draw, Tool.Text -> Modifier.pointerInput(tool, s, left, top, color, penDp) {
                        val width = with(density) { penDp.dp.toPx() } / s
                        val reach = with(density) { MARK_REACH.toPx() } / s
                        val jitter = with(density) { HOLD_JITTER.toPx() }
                        fun points(): FloatArray = FloatArray(live.size * 2).also { a -> live.forEachIndexed { i, o -> a[i * 2] = o.x; a[i * 2 + 1] = o.y } }
                        fun commit(mark: EditOp) {
                            drawn += mark
                            edit({ it.addMark(mark) }, after = { drawn.remove(mark) })
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = toImage(down.position)
                            val picked = PhotoEditor.hitMark(currentMarks, start.x, start.y, reach)

                            // a mark follows the finger; it lands where the finger lifts
                            suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.carry(index: Int) {
                                dragIndex = index
                                dragOffset = Offset.Zero
                                var last = down.position
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    dragOffset += (c.position - last) / s
                                    last = c.position
                                }
                                val moved = dragOffset
                                if (moved.x != 0f || moved.y != 0f) {
                                    edit({ it.moveMark(index, moved.x, moved.y) }, after = { dragIndex = -1; dragOffset = Offset.Zero })
                                } else {
                                    dragIndex = -1
                                }
                            }

                            if (tool == Tool.Text) {
                                // text: a drag on any mark moves it, a tap on an empty spot places new text
                                if (picked >= 0) {
                                    Haptics.tick(view, false)
                                    carry(picked)
                                } else if (waitForUpOrCancellation() != null) {
                                    textAt = start
                                }
                                return@awaitEachGesture
                            }

                            if (picked >= 0) {
                                // draw: holding still on a mark picks it up; moving right away draws over it
                                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull HOLD_UP
                                        if (!c.pressed) return@withTimeoutOrNull HOLD_UP
                                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull HOLD_MOVED
                                    }
                                    @Suppress("UNREACHABLE_CODE") HOLD_UP
                                }
                                if (outcome == null) {
                                    Haptics.tick(view, true)
                                    carry(picked)
                                    return@awaitEachGesture
                                }
                                if (outcome == HOLD_UP) {
                                    commit(EditOp.Stroke(floatArrayOf(start.x, start.y), color, width))
                                    return@awaitEachGesture
                                }
                            }

                            // a line; held still at the end, it becomes the perfect shape it was meant to be
                            live.clear()
                            live += start
                            snapped = null
                            var anchor = down.position
                            // when the finger last really moved: a finger held still still sends tiny moves
                            var still = down.uptimeMillis
                            var tried = false
                            var lifted = false
                            fun snap() {
                                tried = true
                                if (live.size >= 2) {
                                    snapped = ShapeFit.fit(points(), color, width)
                                    if (snapped != null) Haptics.tick(view, true)
                                }
                            }
                            while (true) {
                                val wait = (still + SNAP_HOLD_MS - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                val event = if (tried) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                                if (event == null) { snap(); continue }
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) { lifted = true; break }
                                c.consume()
                                live += toImage(c.position)
                                if ((c.position - anchor).getDistance() > jitter) {
                                    anchor = c.position
                                    still = c.uptimeMillis
                                    tried = false
                                    snapped = null
                                } else if (!tried && c.uptimeMillis - still >= SNAP_HOLD_MS) {
                                    snap()
                                }
                            }
                            val shape = snapped
                            val stroke = points()
                            live.clear()
                            snapped = null
                            if (!lifted && stroke.size < 4) return@awaitEachGesture
                            commit(shape ?: EditOp.Stroke(stroke, color, width))
                        }
                    }
                    Tool.Crop -> Modifier.pointerInput(tool, s, left, top, imgW, imgH) {
                        var grab = 0
                        detectDragGestures(
                            onDragStart = { at ->
                                val c = crop ?: return@detectDragGestures
                                val r = Rect(left + c.left * s, top + c.top * s, left + c.right * s, top + c.bottom * s)
                                var g = 0
                                val inY = at.y > r.top - handle && at.y < r.bottom + handle
                                val inX = at.x > r.left - handle && at.x < r.right + handle
                                if (inY) { if (abs(at.x - r.left) < handle) g = g or GRAB_LEFT else if (abs(at.x - r.right) < handle) g = g or GRAB_RIGHT }
                                if (inX) { if (abs(at.y - r.top) < handle) g = g or GRAB_TOP else if (abs(at.y - r.bottom) < handle) g = g or GRAB_BOTTOM }
                                if (g == 0 && r.contains(at)) g = GRAB_MOVE
                                grab = g
                            },
                        ) { change, drag ->
                            val c = crop ?: return@detectDragGestures
                            if (grab == 0) return@detectDragGestures
                            change.consume()
                            val dx = drag.x / s
                            val dy = drag.y / s
                            val side = min(minSide, min(imgW, imgH).toFloat())
                            crop = if (grab == GRAB_MOVE) {
                                val mx = dx.coerceIn(-c.left, imgW - c.right)
                                val my = dy.coerceIn(-c.top, imgH - c.bottom)
                                c.translate(mx, my)
                            } else {
                                var l = c.left
                                var t = c.top
                                var r = c.right
                                var b = c.bottom
                                if (grab and GRAB_LEFT != 0) l = (l + dx).coerceIn(0f, r - side)
                                if (grab and GRAB_RIGHT != 0) r = (r + dx).coerceIn(l + side, imgW.toFloat())
                                if (grab and GRAB_TOP != 0) t = (t + dy).coerceIn(0f, b - side)
                                if (grab and GRAB_BOTTOM != 0) b = (b + dy).coerceIn(t + side, imgH.toFloat())
                                Rect(l, t, r, b)
                            }
                        }
                    }
                    else -> Modifier
                }

                // the crop corners take the finger even inside the system's back-gesture strip
                val cropBox = crop?.let { c -> Rect(left + c.left * s, top + c.top * s, left + c.right * s, top + c.bottom * s) }
                val reach = handle * 1.5f
                val exclusions = if (cropBox == null) Modifier else listOf(
                    cropBox.topLeft, Offset(cropBox.right, cropBox.top), Offset(cropBox.left, cropBox.bottom), cropBox.bottomRight,
                ).fold(Modifier as Modifier) { m, corner ->
                    m.systemGestureExclusion { androidx.compose.ui.geometry.Rect(corner.x - reach, corner.y - reach, corner.x + reach, corner.y + reach) }
                }
                Canvas(Modifier.fillMaxSize().then(exclusions).then(gestures)) {
                    val shown = preview ?: return@Canvas
                    drawImage(
                        shown,
                        srcSize = IntSize(shown.width, shown.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )
                    // the marks over the picture, drawn by the same code that sets them into the saved photo
                    if (marks.isNotEmpty() || drawn.isNotEmpty() || live.isNotEmpty()) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(left, top, left + dispW, top + dispH)
                            native.translate(left, top)
                            native.scale(s, s)
                            marks.forEachIndexed { i, m ->
                                if (i == dragIndex) {
                                    native.save()
                                    native.translate(dragOffset.x, dragOffset.y)
                                    PhotoEditor.drawMark(native, m)
                                    native.restore()
                                } else {
                                    PhotoEditor.drawMark(native, m)
                                }
                            }
                            drawn.forEach { PhotoEditor.drawMark(native, it) }
                            val shape = snapped
                            if (shape != null) {
                                PhotoEditor.drawMark(native, shape)
                            } else if (live.isNotEmpty()) {
                                val pts = FloatArray(live.size * 2)
                                live.forEachIndexed { i, o -> pts[i * 2] = o.x; pts[i * 2 + 1] = o.y }
                                PhotoEditor.drawMark(native, EditOp.Stroke(pts, color, penDp.dp.toPx() / s))
                            }
                            native.restore()
                        }
                    }
                    crop?.let { c ->
                        val r = Rect(left + c.left * s, top + c.top * s, left + c.right * s, top + c.bottom * s)
                        val shade = Color.Black.copy(alpha = 0.6f)
                        drawRect(shade, Offset(left, top), Size(dispW, r.top - top))
                        drawRect(shade, Offset(left, r.bottom), Size(dispW, top + dispH - r.bottom))
                        drawRect(shade, Offset(left, r.top), Size(r.left - left, r.height))
                        drawRect(shade, Offset(r.right, r.top), Size(left + dispW - r.right, r.height))
                        val thin = 1.dp.toPx()
                        for (i in 1..2) {
                            drawLine(Color.White.copy(alpha = 0.5f), Offset(r.left + r.width * i / 3f, r.top), Offset(r.left + r.width * i / 3f, r.bottom), thin)
                            drawLine(Color.White.copy(alpha = 0.5f), Offset(r.left, r.top + r.height * i / 3f), Offset(r.right, r.top + r.height * i / 3f), thin)
                        }
                        drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
                        val arm = min(20.dp.toPx(), min(r.width, r.height) / 2f)
                        val bold = 4.dp.toPx()
                        listOf(
                            r.topLeft to Offset(1f, 1f), Offset(r.right, r.top) to Offset(-1f, 1f),
                            Offset(r.left, r.bottom) to Offset(1f, -1f), Offset(r.right, r.bottom) to Offset(-1f, -1f),
                        ).forEach { (corner, dir) ->
                            drawLine(Color.White, corner, Offset(corner.x + arm * dir.x, corner.y), bold)
                            drawLine(Color.White, corner, Offset(corner.x, corner.y + arm * dir.y), bold)
                        }
                    }
                }

                if (busy && preview == null || saving) {
                    CircularProgressIndicator(color = p.accent, modifier = Modifier.align(Alignment.Center).size(40.dp))
                }
                if (loadFailed) {
                    Text(stringResource(R.string.ed_load_failed), color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center))
                }
            }

            Column(Modifier.fillMaxWidth().heightIn(min = PANEL_MIN_HEIGHT).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                when (tool) {
                    Tool.Crop -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton(stringResource(R.string.ed_reset), enabled = cropPending) { crop = Rect(0f, 0f, imgW.toFloat(), imgH.toFloat()) }
                        PanelButton(stringResource(R.string.ed_apply), enabled = cropPending, accent = true) { applyCrop() }
                    }
                    Tool.Rotate -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton(stringResource(R.string.ed_rotate_left), icon = R.drawable.ic_rotate_left, enabled = session != null) { edit({ it.transform(EditOp.Rotate(clockwise = false)) }) }
                        PanelButton(stringResource(R.string.ed_rotate_right), icon = R.drawable.ic_rotate_right, enabled = session != null) { edit({ it.transform(EditOp.Rotate(clockwise = true)) }) }
                    }
                    Tool.Resize -> ResizePanel(
                        imgW, imgH,
                        // before any change the photo is its own file; after one, what saving it now would write
                        fileBytes = if (changed) null else hit.sizeBytes.takeIf { it > 0 },
                        measure = { w, h ->
                            val s = session
                            val format = PhotoEditor.formatFor(hit.mime) ?: android.graphics.Bitmap.CompressFormat.JPEG
                            if (s == null) null else withContext(worker) {
                                try { s.savedBytes(format, w, h) } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
                            }
                        },
                    ) { w, h -> edit({ it.transform(EditOp.Resize(w, h)) }) }
                    Tool.Draw -> {
                        ColorRow(color) { color = it }
                        SizeRow(stringResource(R.string.ed_size), penDp, PEN_MIN_DP..PEN_MAX_DP, color) { penDp = it }
                    }
                    Tool.Text -> {
                        ColorRow(color) { color = it }
                        SizeRow(stringResource(R.string.ed_size), textDp, TEXT_MIN_DP..TEXT_MAX_DP, color) { textDp = it }
                        Text(stringResource(R.string.ed_tap_for_text), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton(stringResource(R.string.ed_crop), R.drawable.ic_crop, tool == Tool.Crop) { pick(Tool.Crop) }
                ToolButton(stringResource(R.string.ed_rotate), R.drawable.ic_rotate_right, tool == Tool.Rotate) { pick(Tool.Rotate) }
                ToolButton(stringResource(R.string.ed_resize), R.drawable.ic_resize, tool == Tool.Resize) { pick(Tool.Resize) }
                ToolButton(stringResource(R.string.ed_draw), R.drawable.ic_pen, tool == Tool.Draw) { pick(Tool.Draw) }
                ToolButton(stringResource(R.string.ed_text), R.drawable.ic_text, tool == Tool.Text) { pick(Tool.Text) }
            }
        }

        textAt?.let { at ->
            TextEntryDialog(onDismiss = { textAt = null }) { text ->
                textAt = null
                val size = with(density) { textDp.dp.toPx() } / viewScale
                val label = EditOp.Label(text, at.x, at.y, color, size)
                drawn += label
                edit({ it.addMark(label) }, after = { drawn.remove(label) })
            }
        }

        if (askDiscard) {
            AlertDialog(
                onDismissRequest = { askDiscard = false },
                title = { Text(stringResource(R.string.ed_discard_title)) },
                confirmButton = { TextButton(onClick = { askDiscard = false; onClose() }) { Text(stringResource(R.string.ed_discard), color = p.accent) } },
                dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.ed_keep), color = p.ink) } },
                containerColor = p.paper,
                titleContentColor = p.ink,
                textContentColor = p.muted,
            )
        }

        if (askSave) {
            val canOverwrite = PhotoEditor.formatFor(hit.mime) != null
            AlertDialog(
                onDismissRequest = { askSave = false },
                title = { Text(stringResource(R.string.ed_save_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SaveOption(
                            stringResource(R.string.ed_overwrite),
                            if (canOverwrite) stringResource(R.string.ed_overwrite_sub, hit.fileName) else stringResource(R.string.ed_overwrite_no),
                            enabled = canOverwrite,
                        ) { choose(true) }
                        SaveOption(stringResource(R.string.ed_copy), stringResource(R.string.ed_copy_sub)) { choose(false) }
                        reduced?.let { o ->
                            Text(
                                stringResource(R.string.ed_reduced, Actions.formatCount(imgW.toLong()), Actions.formatCount(imgH.toLong()), Actions.formatCount(o[0].toLong()), Actions.formatCount(o[1].toLong())),
                                style = MaterialTheme.typography.bodyMedium, color = p.muted,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { askSave = false }) { Text(stringResource(R.string.cancel), color = p.ink) } },
                containerColor = p.paper,
                titleContentColor = p.ink,
                textContentColor = p.muted,
            )
        }
    }
}

@Composable
private fun RowScope.ToolButton(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = if (selected) p.accent else Color.White
    Column(
        Modifier
            .weight(1f)
            .clip(Corner)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else Color.White.copy(alpha = 0.45f), Corner)
            .tapClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun RowScope.PanelButton(label: String, icon: Int? = null, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        accent -> p.onAccentFill
        else -> Color.White
    }
    Row(
        Modifier
            .weight(1f)
            .clip(Corner)
            .background(if (accent && enabled) p.accentFill else Color.Transparent)
            .border(1.dp, if (accent && enabled) p.accentFill else Color.White.copy(alpha = if (enabled) 0.45f else 0.2f), Corner)
            .tapClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        PEN_COLORS.forEach { c ->
            val on = c == selected
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.45f), CircleShape)
                    .padding(if (on) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .tapClickable { Haptics.toggle(view, true); onPick(c) },
            )
        }
    }
}

@Composable
private fun SizeRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, color: Int, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp), maxLines = 1)
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { Haptics.tick(view, false) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(48.dp).height(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(value.coerceAtMost(44f).dp).clip(CircleShape).background(Color(color)))
        }
    }
}

@Composable
private fun ResizePanel(width: Int, height: Int, fileBytes: Long?, measure: suspend (Int, Int) -> Long?, onApply: (Int, Int) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.ed_now, Actions.formatCount(width.toLong()), Actions.formatCount(height.toLong())),
            color = Color.White, style = MaterialTheme.typography.bodyLarge,
        )
        Row {
            PanelButton(stringResource(R.string.ed_change_size), icon = R.drawable.ic_resize, enabled = width > 0, accent = true) { asking = true }
        }
    }
    if (asking) ResizeDialog(width, height, fileBytes, measure, onDismiss = { asking = false }) { w, h -> asking = false; onApply(w, h) }
}

/** Width and height in pixels with the chain between them: closed keeps the aspect ratio, open lets each change alone. */
@Composable
private fun ResizeDialog(width: Int, height: Int, fileBytes: Long?, measure: suspend (Int, Int) -> Long?, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var linked by remember { mutableStateOf(true) }
    val wi = w.toIntOrNull() ?: 0
    val hi = h.toIntOrNull() ?: 0
    val tooBig = wi.toLong() * hi > PhotoEditor.pixelBudget()
    val valid = wi > 0 && hi > 0 && !tooBig && (wi != width || hi != height)
    val context = LocalContext.current
    // file sizes are the real encodings in the format the photo is saved in, measured on the worker
    var before by remember { mutableStateOf<Long?>(fileBytes) }
    var after by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { if (before == null) before = measure(width, height) }
    LaunchedEffect(wi, hi, valid) {
        after = null
        if (!valid) return@LaunchedEffect
        kotlinx.coroutines.delay(SIZE_SETTLE_MS)
        after = measure(wi, hi)
    }
    fun mb(bytes: Long?) = bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) } ?: "…"
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent,
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, focusedLabelColor = p.accent, unfocusedLabelColor = p.muted,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_resize)) },
        text = {
            com.opensolr.photos.ui.KeyboardBack()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.opensolr.photos.ui.OutlinedTextBox(
                        value = w,
                        onValueChange = { typed ->
                            w = typed.filter { it.isDigit() }.take(5)
                            val n = w.toIntOrNull()
                            if (linked && n != null && width > 0) h = max(1, (n.toLong() * height / width).toInt()).toString()
                        },
                        label = { Text(stringResource(R.string.ed_width)) },
                        singleLine = true,
                        shape = Corner,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { linked = !linked; Haptics.toggle(view, linked) }) {
                        Icon(
                            painterResource(if (linked) R.drawable.ic_link else R.drawable.ic_link_off),
                            contentDescription = stringResource(R.string.ed_keep_ratio),
                            tint = if (linked) p.accent else p.muted,
                        )
                    }
                    com.opensolr.photos.ui.OutlinedTextBox(
                        value = h,
                        onValueChange = { typed ->
                            h = typed.filter { it.isDigit() }.take(5)
                            val n = h.toIntOrNull()
                            if (linked && n != null && height > 0) w = max(1, (n.toLong() * width / height).toInt()).toString()
                        },
                        label = { Text(stringResource(R.string.ed_height)) },
                        singleLine = true,
                        shape = Corner,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        onImeAction = { if (valid) onApply(wi, hi) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (tooBig) stringResource(R.string.ed_too_big) else stringResource(R.string.ed_now, Actions.formatCount(width.toLong()), Actions.formatCount(height.toLong())),
                    color = if (tooBig) p.accent else p.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.ed_bytes_before, mb(before)), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                if (valid) {
                    Text(stringResource(R.string.ed_bytes_after, Actions.formatCount(wi.toLong()), Actions.formatCount(hi.toLong()), mb(after)), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(wi, hi) }, enabled = valid) { Text(stringResource(R.string.ed_apply), color = if (valid) p.accent else p.hairline) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.ink) } },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

@Composable
private fun SaveOption(title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(Corner)
            .border(1.dp, p.ink, Corner)
            .tapClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = p.muted)
    }
}

@Composable
private fun TextEntryDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_text)) },
        text = {
            com.opensolr.photos.ui.KeyboardBack()
            com.opensolr.photos.ui.OutlinedTextBox(
                value = text,
                onValueChange = { text = it.take(TEXT_MAX_CHARS) },
                placeholder = { Text(stringResource(R.string.ed_text_hint), color = p.muted) },
                minLines = 1,
                maxLines = 4,
                shape = Corner,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ed_add), color = if (text.isNotBlank()) p.accent else p.hairline) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.ink) } },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

private val Corner = RoundedCornerShape(2.dp)

// bright red first: the pen's colour until another is picked
private val PEN_COLORS = listOf(
    0xFFFF1A1A.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(),
    0xFF22C55E.toInt(), 0xFF2F7BFF.toInt(), 0xFFFF8A00.toInt(), 0xFFD946EF.toInt(),
)
private const val PEN_DEFAULT_DP = 6f
private const val PEN_MIN_DP = 2f
private const val PEN_MAX_DP = 40f
private const val TEXT_DEFAULT_DP = 28f
private const val TEXT_MIN_DP = 14f
private const val TEXT_MAX_DP = 96f
private const val TEXT_MAX_CHARS = 500
private const val PREVIEW_MAX_EDGE = 2048
private val HANDLE_REACH = 28.dp
private val IMAGE_SIDE = 32.dp
private val CROP_MIN = 48.dp
private val PANEL_MIN_HEIGHT = 128.dp
private const val SIZE_SETTLE_MS = 400L
private const val SNAP_HOLD_MS = 450L
private const val HOLD_UP = 1
private const val HOLD_MOVED = 2
private val MARK_REACH = 16.dp
private val HOLD_JITTER = 6.dp
private const val GRAB_LEFT = 1
private const val GRAB_TOP = 2
private const val GRAB_RIGHT = 4
private const val GRAB_BOTTOM = 8
private const val GRAB_MOVE = 16
