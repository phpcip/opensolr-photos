package com.opensolr.photos.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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

/**
 * Uppercase section label over a hairline, the house style for section headings.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

/**
 * The primary action: solid accent, white text, 2px corners.
 */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = Corner,
        colors = ButtonDefaults.buttonColors(containerColor = p.accentFill, contentColor = p.onAccentFill, disabledContainerColor = p.chip, disabledContentColor = p.muted),
        contentPadding = PaddingValues(horizontal = 24.dp),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The secondary action: transparent with an ink outline.
 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = Corner,
        border = BorderStroke(1.dp, if (enabled) p.ink else p.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A notice: white panel, hairline, 3dp accent rule on the left.
 */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, title: String? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            // As tall as its text, even inside a bounded column: the accent rule matches the
            // text and never stretches to the bottom of the screen.
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

/**
 * A "label: value" row with a hairline under it.
 */
@Composable
fun InfoRow(label: String, value: String, onOpen: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(end = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                // A small "open" arrow: the row leads somewhere (the account on opensolr.com).
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

/**
 * A usage bar: what is used of a limit, with both numbers.
 */
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

/**
 * Title row of a secondary screen, with a back arrow.
 */
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
