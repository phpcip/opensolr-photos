package com.opensolr.photos.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A zoomed picture keeps gliding after the finger leaves, slowing to a stop, each axis held at its edge. */
internal suspend fun glidePan(velocity: Velocity, from: Offset, limit: Offset, decay: DecayAnimationSpec<Float>, set: (Offset) -> Unit) = coroutineScope {
    var x = from.x
    var y = from.y
    launch {
        AnimationState(x, velocity.x).animateDecay(decay) {
            x = value.coerceIn(-limit.x, limit.x)
            set(Offset(x, y))
            if (x != value) cancelAnimation()
        }
    }
    launch {
        AnimationState(y, velocity.y).animateDecay(decay) {
            y = value.coerceIn(-limit.y, limit.y)
            set(Offset(x, y))
            if (y != value) cancelAnimation()
        }
    }
}
