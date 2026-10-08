package com.opensolr.photos.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.media.DocumentEdges
import com.opensolr.photos.media.DocumentScan
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Haptics
import com.opensolr.photos.ui.IconButton
import com.opensolr.photos.ui.pressedScale
import com.opensolr.photos.ui.pressedTint
import com.opensolr.photos.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min

/**
 * The document scanner: the camera with the outline of the page drawn live, then the photo with its four corners
 * to adjust, then the page straightened and saved as a new photo in a synced folder. Page after page until closed.
 */
@Composable
internal fun ScanDialog(folders: Set<String>, topInset: Dp, bottomInset: Dp, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var capture by remember { mutableStateOf<File?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf<FloatArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableIntStateOf(0) }
    val live = remember { mutableStateOf<FloatArray?>(null) }
    val frameAspect = remember { mutableFloatStateOf(FALLBACK_ASPECT) }
    var cameraFailed by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    fun retake() {
        capture?.delete()
        capture = null
        shown = null
        corners = null
    }

    fun close() {
        if (busy) return
        retake()
        onClose()
    }

    DisposableEffect(Unit) { onDispose { DocumentScan.clear(app) } }

    val needed = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            // Android 8 and 9: writing into the photo folders takes the storage permission
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    fun allGranted() = needed.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(allGranted()) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = allGranted()
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(needed.toTypedArray()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        // back from the app's settings with the permission given
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = allGranted()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val reviewing = capture != null

    // the camera runs only while it is shown: bound when the camera view opens, released for the review and on close
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    DisposableEffect(granted, reviewing) {
        if (!granted || reviewing) return@DisposableEffect onDispose {}
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(app)
        val wide = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val preview = Preview.Builder().setResolutionSelector(wide.build()).build()
        val photo = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(ANALYSIS_W, ANALYSIS_H), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var lastAt = 0L
        var misses = 0
        // the page of the frame before, as the frame is seen: the next frame starts from it
        var before: FloatArray? = null
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastAt >= ANALYZE_EVERY_MS) {
                    lastAt = now
                    val degrees = image.imageInfo.rotationDegrees
                    val found = framePage(image, before?.let { DocumentEdges.turn(it, 360 - degrees) })?.let { DocumentEdges.turn(it, degrees) }
                    before = found
                    val aspect = if (degrees % 180 == 0) image.width.toFloat() / image.height else image.height.toFloat() / image.width
                    main.execute {
                        if (disposed) return@execute
                        frameAspect.floatValue = aspect
                        if (found == null) {
                            misses++
                            if (misses >= MISSES_TO_HIDE) live.value = null
                        } else {
                            misses = 0
                            live.value = smooth(live.value, found)
                        }
                    }
                }
            } finally {
                image.close()
            }
        }
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                val turned = previewView.display?.rotation ?: Surface.ROTATION_0
                photo.targetRotation = turned
                analysis.targetRotation = turned
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, photo, analysis)
                provider = p
                imageCapture = photo
                cameraFailed = false
            } catch (e: Exception) {
                cameraFailed = true
            }
        }, main)
        onDispose {
            disposed = true
            imageCapture = null
            live.value = null
            analysis.clearAnalyzer()
            provider?.unbind(preview, photo, analysis)
            executor.shutdown()
        }
    }

    fun shoot() {
        val photo = imageCapture ?: return
        if (busy) return
        busy = true
        Haptics.tick(view, true)
        val seen = live.value
        val file = File(DocumentScan.captureDir(app), "capture-" + System.nanoTime() + ".jpg")
        photo.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        photo.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(app), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                scope.launch {
                    val ready = withContext(Dispatchers.IO) {
                        val bitmap = PhotoReader.uprightBitmap(app, Uri.fromFile(file), REVIEW_EDGE)
                        bitmap?.let { it to DocumentScan.detect(it, seen) }
                    }
                    if (ready == null) {
                        file.delete()
                        toast(AppText.s(R.string.scan_capture_failed))
                    } else {
                        shown = ready.first
                        corners = ready.second ?: seen ?: DEFAULT_CORNERS.copyOf()
                        capture = file
                    }
                    busy = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                busy = false
                toast(AppText.s(R.string.scan_capture_failed))
            }
        })
    }

    fun save() {
        val file = capture ?: return
        val quad = corners ?: return
        if (busy) return
        if (!DocumentEdges.usable(quad)) {
            toast(AppText.s(R.string.scan_bad_corners))
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(DocumentScan.save(app, file, quad, folders))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            busy = false
            result.onSuccess { where ->
                saved++
                toast(AppText.s(R.string.scan_saved, where.substringBeforeLast('/')))
                retake()
            }.onFailure { e ->
                toast(AppText.s(if (e is DocumentScan.NoFolderException) R.string.scan_no_folder else R.string.scan_failed))
            }
        }
    }

    Dialog(
        onDismissRequest = { if (reviewing && !busy) retake() else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val overflow = viewerWindowOverflow()
        Column(Modifier.fillMaxSize().background(Black)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = maxOf(topInset, 24.dp), bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { close() }, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.scan_close), tint = White, modifier = Modifier.size(26.dp))
                }
                Text(
                    stringResource(if (reviewing) R.string.scan_hint_corners else R.string.scan_hint_camera),
                    color = White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (saved > 0) {
                    Text(
                        pluralStringResource(R.plurals.scan_saved_count, saved, saved),
                        color = White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bitmap = shown
                val quad = corners
                when {
                    !granted -> PermissionAsk(asked, needed, onAsk = { ask.launch(needed.toTypedArray()) })
                    bitmap != null && quad != null -> CornerEditor(bitmap, quad, enabled = !busy) { corners = it }
                    cameraFailed -> Text(
                        stringResource(R.string.scan_no_camera),
                        color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        LiveOutline(live, frameAspect.floatValue)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = maxOf(bottomInset, VIEWER_MIN_BOTTOM) + overflow + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reviewing) {
                    LightGhostButton(stringResource(R.string.scan_retake), enabled = !busy, modifier = Modifier.weight(1f)) { retake() }
                    AccentButton(
                        stringResource(if (busy) R.string.scan_saving else R.string.scan_save),
                        onClick = { save() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                } else if (granted && !cameraFailed) {
                    Shutter(enabled = imageCapture != null && !busy) { shoot() }
                } else {
                    Spacer(Modifier.height(SHUTTER))
                }
            }
        }
    }
}

// the page outline the camera sees, over the preview (which is fitted whole into the view, centred)
@Composable
private fun LiveOutline(live: MutableState<FloatArray?>, aspect: Float) {
    val accent = LocalPalette.current.accent
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val q = live.value ?: return@Canvas
        val viewAspect = size.width / size.height
        val w = if (viewAspect > aspect) size.height * aspect else size.width
        val h = if (viewAspect > aspect) size.height else size.width / aspect
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        val path = Path().apply {
            moveTo(left + q[0] * w, top + q[1] * h)
            for (i in 1 until 4) lineTo(left + q[i * 2] * w, top + q[i * 2 + 1] * h)
            close()
        }
        drawPath(path, accent.copy(alpha = OUTLINE_FILL))
        drawPath(path, accent, style = Stroke(with(density) { 3.dp.toPx() }))
    }
}

// the photo with its four corners: each one dragged with a finger; outside the page is dimmed
@Composable
private fun CornerEditor(bitmap: Bitmap, corners: FloatArray, enabled: Boolean, onChange: (FloatArray) -> Unit) {
    val accent = LocalPalette.current.accent
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val view = LocalView.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = with(density) { HANDLE_MARGIN.toPx() }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min((boxW - margin * 2) / bitmap.width, (boxH - margin * 2) / bitmap.height).coerceAtLeast(0.01f)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (boxW - w) / 2f
        val top = (boxH - h) / 2f
        val reach = with(density) { HANDLE_REACH.toPx() }
        var active by remember { mutableIntStateOf(-1) }
        var current by remember(bitmap) { mutableStateOf(corners) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap, enabled, w, h) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { at ->
                            var nearest = -1
                            var best = reach
                            for (i in 0 until 4) {
                                val d = hypot(left + current[i * 2] * w - at.x, top + current[i * 2 + 1] * h - at.y)
                                if (d <= best) {
                                    best = d
                                    nearest = i
                                }
                            }
                            active = nearest
                            if (nearest >= 0) Haptics.tap(view)
                        },
                        onDrag = { change, amount ->
                            val i = active
                            if (i >= 0) {
                                change.consume()
                                val next = current.copyOf()
                                next[i * 2] = (next[i * 2] + amount.x / w).coerceIn(0f, 1f)
                                next[i * 2 + 1] = (next[i * 2 + 1] + amount.y / h).coerceIn(0f, 1f)
                                current = next
                                onChange(next)
                            }
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    )
                }
        ) {
            drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
            val page = Path().apply {
                moveTo(left + current[0] * w, top + current[1] * h)
                for (i in 1 until 4) lineTo(left + current[i * 2] * w, top + current[i * 2 + 1] * h)
                close()
            }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(left, top, left + w, top + h))
                addPath(page)
            }
            drawPath(outside, Black.copy(alpha = DIM))
            drawPath(page, accent, style = Stroke(with(density) { 2.dp.toPx() }))
            val radius = with(density) { HANDLE_RADIUS.toPx() }
            for (i in 0 until 4) {
                val c = Offset(left + current[i * 2] * w, top + current[i * 2 + 1] * h)
                drawCircle(if (i == active) accent else White, radius, c)
                drawCircle(accent, radius, c, style = Stroke(with(density) { 2.dp.toPx() }))
            }
        }
    }
}

@Composable
private fun PermissionAsk(asked: Boolean, needed: List<String>, onAsk: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.scan_camera_needed),
            color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        AccentButton(stringResource(R.string.scan_allow), onClick = {
            var host: android.content.Context? = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            val activity = host as? android.app.Activity
            // asked before and Android will not ask again: only the app's settings can give it now
            val final = asked && activity != null && needed.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            if (final) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                onAsk()
            }
        })
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.scan_take)
    val source = remember { MutableInteractionSource() }
    val colour = if (enabled) White else White.copy(alpha = DISABLED)
    Box(
        Modifier
            .size(SHUTTER)
            .scale(pressedScale(source))
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .border(4.dp, colour, CircleShape)
            .padding(9.dp)
            .clip(CircleShape)
            .background(colour),
    )
}

// the light-on-dark outline button of the scanner (the app's ghost button is dark on light)
@Composable
private fun LightGhostButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    OutlinedButton(
        onClick = { Haptics.tap(view); onClick() },
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.height(52.dp).scale(pressedScale(source)),
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, if (enabled) White else White.copy(alpha = DISABLED)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = White, containerColor = pressedTint(source), disabledContentColor = White.copy(alpha = DISABLED)),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// the page in one frame of the camera, as fractions of the frame as the sensor gives it; [previous] the one before
private fun framePage(image: ImageProxy, previous: FloatArray?): FloatArray? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val step = DocumentEdges.step(image.width, image.height, LIVE_EDGE)
    val w = image.width / step
    val h = image.height / step
    val grey = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * step * rowStride
        for (x in 0 until w) grey[y * w + x] = buffer.get(row + x * step * pixelStride).toInt() and 0xFF
    }
    return DocumentEdges.find(grey, w, h, previous)
}

// the outline goes straight to a page that moved far, and halfway to one that barely moved, so it neither lags nor shakes
private fun smooth(previous: FloatArray?, found: FloatArray): FloatArray {
    if (previous == null) return found
    var far = 0f
    for (i in 0 until 4) far = maxOf(far, hypot(found[i * 2] - previous[i * 2], found[i * 2 + 1] - previous[i * 2 + 1]))
    if (far > SNAP) return found
    return FloatArray(8) { previous[it] + (found[it] - previous[it]) * SMOOTHING }
}

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val SHUTTER = 72.dp
private val HANDLE_RADIUS = 11.dp
private val HANDLE_REACH = 48.dp
private val HANDLE_MARGIN = 20.dp
private const val ANALYSIS_W = 640
private const val ANALYSIS_H = 480
private const val ANALYZE_EVERY_MS = 50L
private const val LIVE_EDGE = 320
private const val REVIEW_EDGE = 1600
private const val MISSES_TO_HIDE = 6
private const val SMOOTHING = 0.5f
private const val SNAP = 0.04f
private const val OUTLINE_FILL = 0.18f
private const val DIM = 0.55f
private const val DISABLED = 0.4f
private const val FALLBACK_ASPECT = 0.75f
private val DEFAULT_CORNERS = floatArrayOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f)
