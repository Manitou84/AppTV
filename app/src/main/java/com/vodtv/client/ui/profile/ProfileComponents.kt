package com.vodtv.client.ui.profile

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.vodtv.client.data.profile.Profile
import com.vodtv.client.data.profile.ProfileRepository
import com.vodtv.client.ui.components.SecondaryButton
import com.vodtv.client.ui.theme.VodColors

/** Profilo attivo, disponibile a tutta l'interfaccia (barra in alto, schermate). */
val LocalActiveProfile = compositionLocalOf<Profile?> { null }

private val AvatarColors = listOf(
    Color(0xFFF2B13B), Color(0xFF5FB0FF), Color(0xFF5BD6A2), Color(0xFFFF7A6B),
    Color(0xFFB08CFF), Color(0xFFFF9ED2), Color(0xFF7FD6E8),
)

fun avatarColor(index: Int): Color = AvatarColors[((index % AvatarColors.size) + AvatarColors.size) % AvatarColors.size]

@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(profile.colorIndex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = profile.initial,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.Bold,
            color = VodColors.OnAccent,
        )
    }
}

/**
 * Tastierino PIN a schermo: 4 cifre, navigabile col D-pad.
 * Accetta anche i tasti numerici del telecomando; il tasto "Canc" cancella l'ultima cifra.
 * Alla quarta cifra chiama [onComplete] e si azzera.
 */
@Composable
fun PinPadDialog(
    title: String,
    subtitle: String?,
    error: String?,
    onComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }

    fun add(d: Char) {
        if (digits.length >= ProfileRepository.PIN_LENGTH) return
        digits += d
        if (digits.length == ProfileRepository.PIN_LENGTH) {
            val pin = digits
            digits = ""
            onComplete(pin)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = VodColors.Surface),
            modifier = Modifier
                .width(440.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    when (code) {
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { add('0' + (code - KeyEvent.KEYCODE_0)); true }
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { add('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true }
                        KeyEvent.KEYCODE_DEL -> { digits = digits.dropLast(1); true }
                        else -> false
                    }
                },
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text, textAlign = TextAlign.Center)
                subtitle?.let { Text(it, fontSize = 15.sp, color = VodColors.TextMuted, textAlign = TextAlign.Center) }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(ProfileRepository.PIN_LENGTH) { i ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (i < digits.length) VodColors.Accent else VodColors.Outline),
                        )
                    }
                }
                error?.let { Text(it, fontSize = 15.sp, color = VodColors.Live) }

                val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('⌫', '0', '✕'))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { c, key ->
                                SecondaryButton(
                                    onClick = {
                                        when (key) {
                                            '⌫' -> digits = digits.dropLast(1)
                                            '✕' -> onDismiss()
                                            else -> add(key)
                                        }
                                    },
                                    modifier = Modifier
                                        .width(96.dp)
                                        .then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                                ) {
                                    Text(
                                        text = key.toString(),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(64.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    }
}
