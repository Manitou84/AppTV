package com.vodtv.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vodtv.client.ui.theme.VodColors

enum class NavTab(val label: String) {
    HOME("Home"), MOVIES("Film"), SERIES("Serie TV"), FAVORITES("Preferiti"), MOTORS("Motori"), SETTINGS("Impostazioni")
}

/** Barra di navigazione in alto, presente in tutte le sezioni principali. */
@Composable
fun TopNavBar(current: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                append("VOD")
                withStyle(SpanStyle(color = VodColors.Accent)) { append(".") }
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = VodColors.Text,
        )
        Spacer(Modifier.width(24.dp))
        NavTab.entries.filter { it != NavTab.SETTINGS }.forEach { tab ->
            NavPill(label = tab.label, selected = tab == current, onClick = { onSelect(tab) })
        }
        Spacer(Modifier.weight(1f))
        NavPill(
            label = NavTab.SETTINGS.label,
            selected = current == NavTab.SETTINGS,
            onClick = { onSelect(NavTab.SETTINGS) },
            idleContainer = VodColors.SurfaceHigh,
        )
    }
}

@Composable
private fun NavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    idleContainer: Color = Color.Transparent,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) VodColors.Text else idleContainer,
            contentColor = if (selected) VodColors.Ground else VodColors.TextSoft,
            focusedContainerColor = VodColors.Accent,
            focusedContentColor = VodColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 22.dp),
        )
    }
}
