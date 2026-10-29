package com.opensolr.photos.ui

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The page of a document in a small framed box, "/ 6" after it: a tap on the box empties it, a new page and
 * Go (the keyboard's Go, Enter, or the Go button shown while typing) jumps there. Leaving it puts the page back.
 */
@Composable
fun PageJump(page: Int, count: Int, color: Color, onGo: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(TextFieldValue(page.toString())) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val focusManager = LocalFocusManager.current
    val style = MaterialTheme.typography.labelMedium.copy(color = color)
    LaunchedEffect(page) { if (!focused) typed = TextFieldValue(page.toString()) }
    LaunchedEffect(focused) {
        if (!focused) typed = TextFieldValue(page.toString())
        // a tap empties the box for the new page, a frame later, after the tap has placed its cursor
        else { androidx.compose.runtime.withFrameNanos { }; typed = TextFieldValue("") }
    }
    fun go() {
        typed.text.toIntOrNull()?.let { onGo(it.coerceIn(1, count.coerceAtLeast(1))) }
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        // inside the viewer's own window (a dialog) the window's view lets the keyboard go too
        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.findFocus()?.clearFocus()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = typed, onValueChange = { v -> typed = v.copy(text = v.text.filter { it.isDigit() }.take(5)) },
            singleLine = true, textStyle = style.copy(textAlign = TextAlign.Center), cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.width(44.dp)
                .border(1.dp, color.copy(alpha = if (focused) 0.9f else 0.55f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 3.dp)
                // Enter from a keyboard that sends a key instead of the Go action; run after the key's own handling,
                // which would otherwise keep the box focused
                .onPreviewKeyEvent { e -> if (e.key == Key.Enter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyUp) view.post { go() }; true } else false }
                .onFocusChanged {
                    focused = it.isFocused
                },
        )
        Text("/ ${Actions.formatCount(count.toLong())}", style = style)
        if (focused) Box(
            Modifier.border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(2.dp)).clickable { go() }.padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text("Go", style = style) }
    }
}
