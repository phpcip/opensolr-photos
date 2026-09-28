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

private val ThumbCorner = RoundedCornerShape(2.dp)

val SCROLL_THUMB_WIDTH = 28.dp
val SCROLL_THUMB_HEIGHT = 84.dp

/** The strip along the right edge that answers a drag. */
val SCROLL_TRACK = 48.dp
