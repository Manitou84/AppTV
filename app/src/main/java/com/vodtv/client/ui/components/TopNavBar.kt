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
import com.vodtv.client.ui.profile.LocalActiveProfile
import com.vodtv.client.ui.profile.ProfileAvatar
import com.vodtv.client.ui.theme.VodColors

enum class NavTab(val label: String) {
    HOME("Home"), MOVIES("Film"), SERIES("Serie TV"), FAVORITES("Preferiti"), MOTORS("Motori"),
    SETTINGS("Impostazioni"), PROFILES("Profili"),
}

/**
 * Barra di navigazione in alto, presente in tutte le sezioni principali.
 * A destra il profilo attivo (apre "Chi guarda?") e, solo per i profili standard, le Impostazioni.
 */
@Composable
fun TopNavBar(current: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    val profile = LocalActiveProfile.current
    val isKid = profile?.isKid == true

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
        listOf(NavTab.HOME, NavTab.MOVIES, NavTab.SERIES, NavTab.FAVORITES, NavTab.MOTORS).forEach { tab ->
            NavPill(selected = tab == current, onClick = { onSelect(tab) }) {
                PillLabel(tab.label, selected = tab == current)
            }
        }
        Spacer(Modifier.weight(1f))
        if (!isKid) {
            NavPill(selected = current == NavTab.SETTINGS, onClick = { onSelect(NavTab.SETTINGS) }, idleContainer = VodColors.SurfaceHigh) {
                PillLabel(NavTab.SETTINGS.label, selected = current == NavTab.SETTINGS)
            }
        }
        if (profile != null) {
            NavPill(selected = false, onClick = { onSelect(NavTab.PROFILES) }, idleContainer = VodColors.SurfaceHigh) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 6.dp, end = 18.dp),
                ) {
                    ProfileAvatar(profile, size = 36.dp)
                    Text(profile.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PillLabel(label: String, selected: Boolean) {
    Text(
        text = label,
        fontSize = 18.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 22.dp),
    )
}

@Composable
private fun NavPill(
    selected: Boolean,
    onClick: () -> Unit,
    idleContainer: Color = Color.Transparent,
    content: @Composable () -> Unit,
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
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
