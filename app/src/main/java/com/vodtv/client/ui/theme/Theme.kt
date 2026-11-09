package com.vodtv.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Palette concordata nel mockup: fondo quasi nero, accento ambra per il focus. */
object VodColors {
    val Ground = Color(0xFF0E1014)
    val Surface = Color(0xFF171A21)
    val SurfaceHigh = Color(0xFF20242D)
    val Outline = Color(0xFF2E333D)
    val Text = Color(0xFFECEDEF)
    val TextMuted = Color(0xFFA3A8B3)
    val TextSoft = Color(0xFFC4C8D0)
    val Accent = Color(0xFFF2B13B)
    val OnAccent = Color(0xFF1A1203)
    val Live = Color(0xFFC8343A)
    val Watched = Color(0xFF5BD6A2)
}

private val colors = darkColorScheme(
    primary = VodColors.Accent,
    onPrimary = VodColors.OnAccent,
    background = VodColors.Ground,
    onBackground = VodColors.Text,
    surface = VodColors.Ground,
    onSurface = VodColors.Text,
    surfaceVariant = VodColors.SurfaceHigh,
    onSurfaceVariant = VodColors.TextMuted,
    border = VodColors.Outline,
    error = Color(0xFFFF8A80),
)

@Composable
fun VodTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
