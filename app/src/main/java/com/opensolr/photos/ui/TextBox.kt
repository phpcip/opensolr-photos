package com.opensolr.photos.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

// Every text input of the app sits on a TextFieldState, whose field selects the word on a double tap (handles and copy bar), as in Google's apps.

/** A TextFieldState kept in step with a String the caller owns: typing reports it, a change from outside is put in with the cursor at the end. */
@Composable
fun rememberSyncedText(value: String, onValueChange: (String) -> Unit, cursorAtStart: Boolean = false): TextFieldState {
    val state = rememberTextFieldState(value, if (cursorAtStart) TextRange(0) else TextRange(value.length))
    val last = remember { arrayOf(value) }
    val report by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != last[0]) { last[0] = it; report(it) } }
    }
    SideEffect {
        if (value != last[0]) {
            last[0] = value
            if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    return state
}

/** The plain text input: a String in, every edit out. */
@Composable
fun TextBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(androidx.compose.ui.graphics.Color.Black),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
    cursorAtStart: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    decorator: TextFieldDecorator? = null,
) {
    BasicTextField(
        state = rememberSyncedText(value, onValueChange, cursorAtStart),
        modifier = modifier,
        enabled = enabled,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onImeAction?.let { action -> KeyboardActionHandler { action() } },
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
        decorator = decorator,
    )
}

/** The Material outlined input, drawn with the same decoration as OutlinedTextField. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlinedTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val c = colors ?: OutlinedTextFieldDefaults.colors()
    val s = shape ?: OutlinedTextFieldDefaults.shape
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color = textStyle.color.takeOrElse { if (!enabled) c.disabledTextColor else if (focused) c.focusedTextColor else c.unfocusedTextColor }
    val state = rememberSyncedText(value, onValueChange)
    CompositionLocalProvider(LocalTextSelectionColors provides c.textSelectionColors) {
        BasicTextField(
            state = state,
            modifier = modifier
                .then(if (label != null) Modifier.padding(top = 8.dp) else Modifier)
                .defaultMinSize(OutlinedTextFieldDefaults.MinWidth, OutlinedTextFieldDefaults.MinHeight),
            enabled = enabled,
            textStyle = textStyle.merge(TextStyle(color = color)),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onImeAction?.let { action -> KeyboardActionHandler { action() } },
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
            interactionSource = interaction,
            cursorBrush = SolidColor(c.cursorColor),
            decorator = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = state.text.toString(),
                    innerTextField = inner,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interaction,
                    label = label,
                    placeholder = placeholder,
                    trailingIcon = trailingIcon,
                    supportingText = supportingText,
                    colors = c,
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(),
                    container = { OutlinedTextFieldDefaults.Container(enabled, false, interaction, Modifier, c, s) },
                )
            },
        )
    }
}
