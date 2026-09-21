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

class OutsideTap internal constructor(
    private val onOutside: () -> Unit,
    private val onInside: (String) -> Unit,
    private val clearFocus: () -> Unit,
) {
    private var rootCoords by mutableStateOf<LayoutCoordinates?>(null)
    private val keptCoords = HashMap<String, LayoutCoordinates>()

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

    fun Modifier.keep(name: String): Modifier = onGloballyPositioned { keptCoords[name] = it }
}

@Composable
fun rememberOutsideTap(onInside: (String) -> Unit = {}, onOutside: () -> Unit = {}): OutsideTap {
    val focusManager = LocalFocusManager.current
    val latestOutside by rememberUpdatedState(onOutside)
    val latestInside by rememberUpdatedState(onInside)
    return remember { OutsideTap({ latestOutside() }, { latestInside(it) }) { focusManager.clearFocus(force = true) } }
}
