package com.vodtv.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.vodtv.client.ui.theme.VodColors
import androidx.compose.material3.Text as M3Text

/**
 * Campo di testo adatto al telecomando:
 * - spostarsi col D-pad NON apre la tastiera (showKeyboardOnFocus = false)
 * - OK apre la tastiera a schermo
 * - ▲/▼ escono dal campo verso l'elemento sopra/sotto (i TextField altrimenti "intrappolano" il focus)
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { M3Text(label) },
        placeholder = if (placeholder != null) {
            { M3Text(placeholder) }
        } else {
            null
        },
        textStyle = TextStyle(fontSize = 20.sp),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onAny = {
            keyboard?.hide()
            onImeAction()
        }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = VodColors.Text,
            unfocusedTextColor = VodColors.Text,
            focusedContainerColor = VodColors.Surface,
            unfocusedContainerColor = VodColors.Surface,
            focusedBorderColor = VodColors.Accent,
            unfocusedBorderColor = VodColors.Outline,
            focusedLabelColor = VodColors.Accent,
            unfocusedLabelColor = VodColors.TextMuted,
            focusedPlaceholderColor = VodColors.TextMuted,
            unfocusedPlaceholderColor = VodColors.TextMuted,
            cursorColor = VodColors.Accent,
        ),
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    keyboard?.show(); true
                }
                Key.DirectionDown -> {
                    keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down)
                }
                Key.DirectionUp -> {
                    keyboard?.hide(); focusManager.moveFocus(FocusDirection.Up)
                }
                else -> false
            }
        },
    )
}

/** Messaggio centrato con azione opzionale (errori, stati vuoti). Il pulsante prende il focus. */
@Composable
fun MessagePanel(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = message,
                fontSize = 20.sp,
                color = VodColors.TextSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 640.dp),
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.focusRequester(focus)) {
                    Text(actionLabel, modifier = Modifier.padding(horizontal = 8.dp))
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VodColors.Accent)
    }
}
