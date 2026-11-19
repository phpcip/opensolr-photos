package com.opensolr.photos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.opensolr.photos.ui.theme.LocalPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

val DockShape = RoundedCornerShape(16.dp)

/** Every button that stands on its own. */
val ButtonShape = RoundedCornerShape(14.dp)

// the sheen every button and dock carries: lighter at the top, a touch darker at the bottom, kept faint
private const val SHEEN_TOP = 0.16f
private const val SHEEN_MID = 0.03f
private const val SHEEN_BOTTOM = 0.08f
private const val RIM_LIGHT = 0.55f

/** The faint light that falls on a glass surface, drawn over whatever fills it. */
fun Modifier.sheen(shape: androidx.compose.ui.graphics.Shape, strength: Float = 1f): Modifier = background(
    androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to Color.White.copy(alpha = SHEEN_TOP * strength),
        0.5f to Color.White.copy(alpha = SHEEN_MID * strength),
        1f to Color.Black.copy(alpha = SHEEN_BOTTOM * strength),
    ),
    shape,
)

/** The rim of a glass surface: it catches the light at the top and fades into [edge]. */
fun Modifier.rim(shape: androidx.compose.ui.graphics.Shape, edge: Color): Modifier = border(
    1.dp,
    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(edge, Color.White, RIM_LIGHT), edge)),
    shape,
)

/** A glass surface filled with [base]: the fill, the sheen over it and the rim around it. */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, base: Color, edge: Color, strength: Float = 1f): Modifier =
    clip(shape).background(base).sheen(shape, strength).rim(shape, edge)

/** A lone action outside a dock keeps the dock's look at its own size. */
val DockSoloShape = RoundedCornerShape(12.dp)

/** True for what is composed inside a [Dock]: an action there draws no frame of its own. */
val LocalDock = compositionLocalOf { false }

/** True inside a dock that floats over a photo: its actions are white on glass. */
val LocalDockOnPhoto = compositionLocalOf { false }

// how far a seam stays from the top and the bottom of the dock, as a share of its height
private const val SEAM_INSET = 0.24f

/**
 * One bar for a row of actions: a single rounded frame, a fine line between one action and the next.
 * With [fill] the actions share the width equally, otherwise each is as wide as it needs. Given [glass],
 * the dock floats over that content and shows it blurred through itself; [dark] is the same look on a black screen.
 */
@Composable
fun Dock(
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    glass: HazeState? = null,
    dark: Boolean = glass != null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val onPhoto = dark
    val line = if (onPhoto) Color.White.copy(alpha = 0.22f) else p.hairline
    val edge = if (onPhoto) Color.White.copy(alpha = 0.22f) else p.hairline
    val seams = remember { mutableStateOf(IntArray(0)) }
    val frame = modifier
        .clip(DockShape)
        .then(
            when {
                glass != null -> Modifier.hazeChild(glass, GlassStyle)
                dark -> Modifier.background(Color.White.copy(alpha = 0.10f))
                else -> Modifier.background(p.buttonFill)
            },
        )
        .sheen(DockShape)
        .rim(DockShape, edge)
        .drawWithContent {
            drawContent()
            val inset = size.height * SEAM_INSET
            val stroke = 1.dp.toPx()
            seams.value.forEach { x -> drawLine(line, Offset(x.toFloat(), inset), Offset(x.toFloat(), size.height - inset), stroke) }
        }
    CompositionLocalProvider(LocalDock provides true, LocalDockOnPhoto provides onPhoto) {
        Layout(content, frame) { measurables, constraints ->
            if (measurables.isEmpty()) return@Layout layout(0, 0) {}
            val count = measurables.size
            val shared = fill && constraints.hasBoundedWidth
            val placeables = if (shared) {
                val each = constraints.maxWidth / count
                val tall = measurables.maxOf { it.maxIntrinsicHeight(each) }.coerceIn(constraints.minHeight, constraints.maxHeight)
                // the last one takes the pixels the division left over
                measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(if (i == count - 1) constraints.maxWidth - each * (count - 1) else each, tall)) }
            } else {
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                measurables.map { it.measure(loose) }
            }
            val width = if (shared) constraints.maxWidth else placeables.sumOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = placeables.maxOf { it.height }
            val at = IntArray(count - 1)
            layout(width, height) {
                var x = 0
                placeables.forEachIndexed { i, placeable ->
                    if (i > 0) at[i - 1] = x
                    placeable.place(x, (height - placeable.height) / 2)
                    x += placeable.width
                }
                if (!at.contentEquals(seams.value)) seams.value = at
            }
        }
    }
}

/** One action of a dock: its cell lights up while held and what is in it shrinks a little. */
@Composable
fun DockCell(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    wash: Color = Color.Transparent,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val onPhoto = LocalDockOnPhoto.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val held by animateColorAsState(
        when {
            !pressed || !enabled -> wash
            onPhoto -> Color.White.copy(alpha = 0.30f)
            else -> p.accent.copy(alpha = 0.24f)
        },
        label = "dockcell",
    )
    Box(
        modifier
            .background(held)
            .then(
                if (enabled) Modifier.clickable(interactionSource = source, indication = ripple(color = if (onPhoto) Color.White else p.accent)) { Haptics.tap(view); onClick() }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.scale(if (enabled) pressedScale(source) else 1f), contentAlignment = Alignment.Center) { content() }
    }
}

private val GlassStyle = HazeStyle(
    backgroundColor = Color.Black,
    tint = HazeTint(Color.Black.copy(alpha = 0.32f)),
    blurRadius = 26.dp,
    noiseFactor = 0.06f,
    fallbackTint = HazeTint(Color(0xD9101010)),
)
