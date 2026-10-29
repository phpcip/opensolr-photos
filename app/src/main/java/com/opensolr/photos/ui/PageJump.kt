package com.opensolr.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * "3 / 6" of a document; a tap turns it into a small box for a page number and Go (or the keyboard's Go),
 * which jumps there. Leaving the box without Go puts the number back.
 */
@Composable
fun PageJump(page: Int, count: Int, color: Color, onGo: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val style = MaterialTheme.typography.labelMedium.copy(color = color)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun go() {
        typed.text.toIntOrNull()?.let { onGo(it.coerceIn(1, count.coerceAtLeast(1))) }
        keyboard?.hide()
        editing = false
    }
    if (!editing) {
        Text(
            "${Actions.formatCount(page.toLong())} / ${Actions.formatCount(count.toLong())}", style = style,
            modifier = Modifier.clickable { val t = page.toString(); typed = TextFieldValue(t, TextRange(0, t.length)); editing = true },
        )
        return
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var had by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = typed, onValueChange = { v -> typed = v.copy(text = v.text.filter { it.isDigit() }.take(5)) },
            singleLine = true, textStyle = style.copy(textAlign = TextAlign.Center), cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.width(48.dp).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                // Enter from a keyboard that sends a key instead of the Go action
                .onPreviewKeyEvent { e -> if (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter) { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) go(); true } else false }
                .focusRequester(focus).onFocusChanged { if (it.isFocused) had = true else if (had) editing = false },
        )
        Text("/ ${Actions.formatCount(count.toLong())}", style = style)
        Box(
            Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(2.dp)).background(Color.Transparent).clickable { go() }.padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("Go", style = style) }
    }
}
