package com.opensolr.photos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * One rule for every text field with suggestions, in every screen and dialog (Cip, 2026-09-17):
 * a tap anywhere outside the field and its suggestion list closes the list.
 *
 * The container gets [Modifier.root]; each field together with its list is wrapped in
 * [Modifier.keep]. A touch is looked at before anything under it handles it and is never
 * consumed, so buttons and scrolling work as before. A touch outside every kept area takes the
 * focus away and calls [onOutside]; a touch inside one calls [onInside] with its name, so a list
 * closed by an earlier outside tap opens again when its field is touched. Focus alone is not
 * enough inside a bottom sheet, where clearing it does not always take it from the field, so
 * every list keeps a "dismissed" flag of its own that these two callbacks drive.
 */
class OutsideTap internal constructor(
    private val onOutside: () -> Unit,
    private val onInside: (String) -> Unit,
    private val clearFocus: () -> Unit,
) {
    private var rootCoords by mutableStateOf<LayoutCoordinates?>(null)
    private val keptCoords = HashMap<String, LayoutCoordinates>()

    /** The container every touch is checked in. */
    fun Modifier.root(): Modifier = this
        .onGloballyPositioned { rootCoords = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val root = rootCoords ?: return@awaitEachGesture
                val inside = keptCoords.entries.firstOrNull { (_, area) ->
                    root.isAttached && area.isAttached &&
                        root.localBoundingBoxOf(area, clipBounds = false).contains(down.position)
                }?.key
                if (inside == null) {
                    clearFocus()
                    onOutside()
                } else {
                    onInside(inside)
                }
            }
        }

    /** A field and its suggestion list, where a touch keeps the list open. */
    fun Modifier.keep(name: String): Modifier = onGloballyPositioned { keptCoords[name] = it }
}

/**
 * An [OutsideTap] for this screen or dialog; [onOutside] runs on every tap outside the kept areas.
 */
@Composable
fun rememberOutsideTap(onInside: (String) -> Unit = {}, onOutside: () -> Unit = {}): OutsideTap {
    val focusManager = LocalFocusManager.current
    val latestOutside by rememberUpdatedState(onOutside)
    val latestInside by rememberUpdatedState(onInside)
    return remember { OutsideTap({ latestOutside() }, { latestInside(it) }) { focusManager.clearFocus(force = true) } }
}
