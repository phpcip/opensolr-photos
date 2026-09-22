package com.opensolr.photos.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.opensolr.photos.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.photos.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

// Every tap has to be seen: the control shrinks a little while the finger is down and the
// press ripple keeps its own colour. PRESS_SCALE is the whole effect, in one place.
private const val PRESS_SCALE = 0.90f

/** How much of the accent washes over a control while it is held. */
private const val PRESS_TINT = 0.28f

/** A press source and the scale it drives: pass the source to the control, the modifier to its layout. */
@Composable
fun pressedScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, label = "press")
    return scale
}

/** The accent wash a control carries while it is held, transparent otherwise. */
@Composable
fun pressedTint(source: MutableInteractionSource): androidx.compose.ui.graphics.Color {
    val p = LocalPalette.current
    val pressed by source.collectIsPressedAsState()
    val colour by androidx.compose.animation.animateColorAsState(
        if (pressed) p.accent.copy(alpha = PRESS_TINT) else androidx.compose.ui.graphics.Color.Transparent,
        label = "presstint",
    )
    return colour
}

/** clickable with the press effect, for anything that is not a Material button. */
@Composable
fun Modifier.tapClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .scale(if (enabled) pressedScale(source) else 1f)
        .background(if (enabled) pressedTint(source) else androidx.compose.ui.graphics.Color.Transparent, Corner)
        .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.material3.ripple()) { onClick() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by androidx.compose.animation.animateColorAsState(if (pressed) p.ink else p.accentFill, label = "accentfill")
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.height(52.dp).scale(pressedScale(source)),
        shape = Corner,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = p.onAccentFill, disabledContainerColor = p.chip, disabledContentColor = p.muted),
        contentPadding = PaddingValues(horizontal = 24.dp),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.height(52.dp).scale(pressedScale(source)),
        shape = Corner,
        border = BorderStroke(if (pressed) 2.dp else 1.dp, if (!enabled) p.hairline else if (pressed) p.accent else p.ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink, containerColor = pressedTint(source)),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Material's TextButton with the same press effect as every other control here: the accent washes
 * over it and it shrinks while the finger is down. Imported in place of the Material one, so a
 * Cancel in a dialog answers the tap exactly like the buttons around it.
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.scale(if (enabled) pressedScale(source) else 1f),
        enabled = enabled,
        interactionSource = source,
        shape = Corner,
        colors = ButtonDefaults.textButtonColors(containerColor = pressedTint(source)),
        contentPadding = contentPadding,
        content = content,
    )
}

/** Material's IconButton with the same press effect. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.scale(if (enabled) pressedScale(source) else 1f),
        enabled = enabled,
        interactionSource = source,
        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(containerColor = pressedTint(source)),
        content = content,
    )
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier, title: String? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()

            .height(IntrinsicSize.Min)
            .background(p.paper, Corner)
            .padding(0.dp)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(p.accent)
        )
        Column(
            Modifier
                .weight(1f)
                .background(p.band)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
                Spacer(Modifier.height(4.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, onOpen: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(end = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)

                if (onOpen != null) {
                    IconButton(onClick = onOpen, modifier = Modifier.size(28.dp).padding(start = 6.dp)) {
                        Icon(painterResource(R.drawable.ic_open), contentDescription = stringResource(R.string.cp_open_site), tint = p.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

@Composable
fun UsageRow(label: String, used: String, limit: String, fraction: Float?) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(stringResource(R.string.cp_x_of_y, used, limit), style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
        if (fraction != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (fraction >= 0.9f) p.accent else p.ink,
                trackColor = p.chip,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cp_back), tint = p.ink)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.padding(start = if (onBack == null) 4.dp else 0.dp))
    }
}
