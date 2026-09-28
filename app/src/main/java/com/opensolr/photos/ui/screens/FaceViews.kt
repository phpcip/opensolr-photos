package com.opensolr.photos.ui.screens

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.Transformation
import com.opensolr.photos.R
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.search.FaceMatcher
import com.opensolr.photos.ui.AccentButton
import com.opensolr.photos.ui.Actions
import com.opensolr.photos.ui.FaceReview
import com.opensolr.photos.ui.GhostButton
import com.opensolr.photos.ui.TextButton
import com.opensolr.photos.ui.tapClickable
import com.opensolr.photos.ui.theme.LocalPalette
import kotlin.math.max
import kotlin.math.roundToInt

/** Frames on the faces of the photo on screen, following its zoom; a tap on one names it. Names live in [FaceStrip]. */
@Composable
internal fun FaceBoxes(
    baseSize: Size?,
    faces: List<PhotoCache.FaceRow>,
    scale: () -> Float,
    offset: () -> Offset,
    highlight: Long?,
    onTap: (PhotoCache.FaceRow) -> Unit,
) {
    val size = baseSize ?: return
    if (size.width <= 0f || size.height <= 0f) return
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale(); scaleY = scale(); translationX = offset().x; translationY = offset().y
        },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fit = minOf(w / size.width, h / size.height)
        val left = (w - size.width * fit) / 2f
        val top = (h - size.height * fit) / 2f
        faces.forEach { f ->
            val x = left + f.x * size.width * fit
            val y = top + f.y * size.height * fit
            val bw = f.w * size.width * fit
            val bh = f.h * size.height * fit
            val lit = f.fid == highlight
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                    .border(if (lit) 3.dp else 2.dp, if (lit) Color(0xFFC05520) else if (f.person != null) Color.White else Color(0xFFC05520), RoundedCornerShape(2.dp))
                    .background(if (lit) Color(0x33C05520) else Color.Transparent, RoundedCornerShape(2.dp))
                    .tapClickable { onTap(f) },
            )
        }
    }
}

/**
 * The people in the photo as a row of cut-out faces with their names, left to right as in the photo: nothing is
 * written over the photo, and in a group photo it is clear which face a name belongs to.
 */
@Composable
internal fun FaceStrip(faces: List<PhotoCache.FaceRow>, highlight: Long?, onTap: (PhotoCache.FaceRow) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(faces.sortedBy { it.x }.size) { i ->
            val f = faces.sortedBy { it.x }[i]
            val lit = f.fid == highlight
            Column(
                Modifier.width(STRIP_FACE + 16.dp).tapClickable { onTap(f) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(STRIP_FACE).clip(RoundedCornerShape(2.dp))
                        .border(if (lit) 3.dp else 1.dp, if (lit) Color(0xFFC05520) else if (f.person != null) Color.White else Color(0xFFC05520), RoundedCornerShape(2.dp)),
                ) { FaceThumb(f) }
                Text(
                    f.person ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val STRIP_FACE = 56.dp

/** Names a face: type or pick a person; a named face can lose its name or look for the person elsewhere. */
@Composable
internal fun FaceNameDialog(
    face: PhotoCache.FaceRow,
    suggest: suspend (String) -> List<String>,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onFindMore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var typed by remember(face.fid) { mutableStateOf(face.person.orEmpty()) }
    var offered by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(typed) { offered = runCatching { suggest(typed) }.getOrDefault(emptyList()).filterNot { it.equals(typed.trim(), ignoreCase = true) }.take(6) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(face.person ?: stringResource(R.string.fc_who)) },
        text = { com.opensolr.photos.ui.KeyboardBack();
            com.opensolr.photos.ui.HideStatusBar()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // the face being named, so there is no doubt which one it is
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, p.hairline, RoundedCornerShape(2.dp))) { FaceThumb(face) }
                com.opensolr.photos.ui.TextBox(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth().border(1.dp, p.hairline, RoundedCornerShape(2.dp)).padding(12.dp),
                    decorator = { field ->
                        if (typed.isEmpty()) Text(stringResource(R.string.fc_hint), style = MaterialTheme.typography.bodyLarge, color = p.muted)
                        field()
                    },
                )
                offered.forEach { name ->
                    Text(
                        name, style = MaterialTheme.typography.bodyLarge, color = p.ink,
                        modifier = Modifier.fillMaxWidth().tapClickable { typed = name }.padding(vertical = 8.dp),
                    )
                }
                face.person?.let { name ->
                    GhostButton(stringResource(R.string.fc_more, name), onClick = { onFindMore(name) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onClear) { Text(stringResource(R.string.fc_clear), color = p.muted) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (typed.isNotBlank()) onSave(typed.trim()) }, enabled = typed.isNotBlank()) {
                Text(stringResource(R.string.fc_save), color = p.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.ink) } },
        containerColor = p.paper,
        titleContentColor = p.ink,
        textContentColor = p.muted,
    )
}

/**
 * Faces that look like the person just named: the close ones ticked, the owner confirms or unticks. Drawn inside
 * the viewer's own window, over the photo, with the same top and bottom margins as the viewer's buttons.
 */
@Composable
internal fun FaceReviewPanel(review: FaceReview, topPad: androidx.compose.ui.unit.Dp, bottomPad: androidx.compose.ui.unit.Dp, onConfirm: (Set<Long>) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var chosen by remember(review) { mutableStateOf(review.faces.filter { it.similarity >= FaceMatcher.SURE }.map { it.fid }.toSet()) }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
        // touches stop here and never reach the photo under the panel
        Column(Modifier.fillMaxSize().background(p.paper).pointerInput(Unit) { detectTapGestures { } }.padding(top = topPad, bottom = bottomPad)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.fc_title, review.person), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = p.ink)
                Text(stringResource(R.string.fc_lead, review.person), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            }
            val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val view = androidx.compose.ui.platform.LocalView.current
            // long press then drag: the faces swept over are ticked, or unticked when the first one was ticked
            var dragBase by remember(review) { mutableStateOf(chosen) }
            var dragUntick by remember(review) { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
            if (review.faces.isEmpty()) {
                Text(stringResource(R.string.fc_none, review.person), style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.padding(horizontal = 16.dp))
            } else
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().dragSelect(
                    gridState = grid,
                    idAt = { review.faces.getOrNull(it)?.fid },
                    onStart = { fid ->
                        com.opensolr.photos.ui.Haptics.tick(view, strong = true)
                        dragBase = chosen
                        dragUntick = fid in chosen
                        chosen = if (dragUntick) chosen - fid else chosen + fid
                    },
                    onRange = { fids, felt ->
                        if (felt) com.opensolr.photos.ui.Haptics.tick(view, strong = false)
                        chosen = if (dragUntick) dragBase - fids.toSet() else dragBase + fids
                    },
                    onEnd = { },
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(review.faces, key = { it.fid }) { f ->
                    val on = f.fid in chosen
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .border(if (on) 3.dp else 1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(2.dp))
                            .tapClickable { chosen = if (on) chosen - f.fid else chosen + f.fid },
                    ) {
                        FaceThumb(f)
                        if (on) {
                            Icon(
                                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(p.accent, RoundedCornerShape(2.dp)).padding(2.dp).size(18.dp),
                            )
                        }
                    }
                }
            }
            if (review.faces.isNotEmpty()) GridScroller(grid, review.faces.size, 3)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                if (review.faces.isNotEmpty()) AccentButton(
                    pluralStringResource(R.plurals.fc_add, chosen.size, Actions.formatCount(chosen.size.toLong())),
                    onClick = { onConfirm(chosen) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
}

/** The face cut out of its photo, read just large enough to be sharp. */
@Composable
internal fun FaceThumb(f: PhotoCache.FaceRow) {
    val context = LocalContext.current
    val request = remember(f.fid) {
        val edge = (THUMB_PX / max(f.w, 0.01f)).roundToInt().coerceIn(256, 2048)
        ImageRequest.Builder(context)
            .data(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f.mediaId))
            .size(edge)
            .transformations(FaceCrop(f))
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/** Crops the square around a face (with a margin) out of the upright photo Coil decoded. */
private class FaceCrop(private val f: PhotoCache.FaceRow) : Transformation {
    override val cacheKey: String = "face:${f.fid}:${f.x}:${f.y}"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val cx = (f.x + f.w / 2f) * input.width
        val cy = (f.y + f.h / 2f) * input.height
        val side = (max(f.w * input.width, f.h * input.height) * 1.6f).roundToInt().coerceIn(1, minOf(input.width, input.height))
        val left = (cx - side / 2f).roundToInt().coerceIn(0, input.width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, input.height - side)
        return Bitmap.createBitmap(input, left, top, side, side)
    }
}

private const val THUMB_PX = 220f

/** A fast scroller for a plain grid of [count] items in [columns] columns: drag the handle to go anywhere at once. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GridScroller(state: androidx.compose.foundation.lazy.grid.LazyGridState, count: Int, columns: Int) {
    if (count < columns * GRID_SCROLL_MIN_ROWS) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var dragging by remember { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (dragging || state.isScrollInProgress) 1f else 0f, label = "gridScroller")
    BoxWithConstraints(Modifier.align(Alignment.CenterEnd).fillMaxSize()) {
        val density = LocalDensity.current
        val travelPx = with(density) { (maxHeight - com.opensolr.photos.ui.SCROLL_THUMB_HEIGHT).toPx() }.coerceAtLeast(1f)
        val halfThumb = with(density) { com.opensolr.photos.ui.SCROLL_THUMB_HEIGHT.toPx() } / 2f
        val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val last = (count - visible).coerceAtLeast(1)
        val fraction = (state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
        fun aimAt(y: Float) {
            val at = ((y - halfThumb) / travelPx).coerceIn(0f, 1f)
            val target = ((at * last).roundToInt() / columns) * columns
            scope.launch { state.scrollToItem(target) }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(com.opensolr.photos.ui.SCROLL_TRACK).pointerInput(travelPx) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; com.opensolr.photos.ui.Haptics.tap(view); aimAt(o.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> change.consume(); aimAt(change.position.y) },
                )
            },
        )
        com.opensolr.photos.ui.ScrollThumb(
            dragging, alpha,
            Modifier.align(Alignment.TopEnd).offset { androidx.compose.ui.unit.IntOffset(0, (travelPx * fraction).roundToInt()) }.padding(end = 4.dp),
        )
    }
}

private const val GRID_SCROLL_MIN_ROWS = 12
