package com.vodtv.client.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.vodtv.client.ui.theme.VodColors

/** Azione principale (ambra). Il focus schiarisce e ingrandisce il pulsante. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = VodColors.Accent,
            contentColor = VodColors.OnAccent,
            focusedContainerColor = Color(0xFFFFC966),
            focusedContentColor = VodColors.OnAccent,
        ),
        content = content,
    )
}

/** Azione secondaria: grigia, diventa chiara quando ha il focus. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = VodColors.SurfaceHigh,
            contentColor = VodColors.Text,
            focusedContainerColor = VodColors.Text,
            focusedContentColor = VodColors.Ground,
        ),
        content = content,
    )
}

val ButtonTextPadding = Modifier.padding(horizontal = 6.dp)
