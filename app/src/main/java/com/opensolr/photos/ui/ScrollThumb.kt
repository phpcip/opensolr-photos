package com.opensolr.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.opensolr.photos.ui.theme.LocalPalette

/** The fast scroller's handle, as in Opensolr Mail: wide, with up and down marks, easy to find and to hold. */
@Composable
fun ScrollThumb(dragging: Boolean, alpha: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val mark = if (dragging) p.onAccentFill else p.paper
    Box(
        modifier
            .size(width = SCROLL_THUMB_WIDTH, height = SCROLL_THUMB_HEIGHT)
            .alpha(alpha)
            .background(if (dragging) p.accentFill else p.ink, ThumbCorner)
            .border(1.dp, if (dragging) p.accentFill else p.paper, ThumbCorner),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = mark, modifier = Modifier.size(18.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = mark, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The handle's opacity: full while the list moves or the handle is held, then it lingers a while before it fades,
 * so it is still there when the finger goes back for it. One timing for every scroller in the app.
 */
@Composable
fun scrollThumbAlpha(active: Boolean): Float {
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(active) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (active) shown = true
        else { kotlinx.coroutines.delay(SCROLL_THUMB_LINGER_MS); shown = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (shown) 0 else 450),
        label = "scrollThumbAlpha",
    )
    return alpha
}

private const val SCROLL_THUMB_LINGER_MS = 2000L

private val ThumbCorner = RoundedCornerShape(2.dp)

val SCROLL_THUMB_WIDTH = 28.dp
val SCROLL_THUMB_HEIGHT = 84.dp

/** The strip along the right edge that answers a drag. */
val SCROLL_TRACK = 48.dp
