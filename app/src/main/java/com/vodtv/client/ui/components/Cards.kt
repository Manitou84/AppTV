package com.vodtv.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vodtv.client.ui.theme.VodColors

private val CardShape = RoundedCornerShape(10.dp)

/**
 * Locandina verticale 2:3 focalizzabile.
 * Il focus D-pad è gestito dalla Card di tv-material (focusable + click su OK + long press);
 * qui leggiamo lo stato di focus dall'InteractionSource per evidenziare anche il titolo.
 */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = CardDefaults.shape(shape = CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(4.dp, VodColors.Accent), shape = CardShape),
            ),
            glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black, elevation = 16.dp)),
        ) {
            ArtworkBox(image = image, title = title) {
                if (progress != null && progress > 0f) {
                    ProgressLine(
                        fraction = progress,
                        color = if (focused) VodColors.Accent else VodColors.Text,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp),
                    )
                }
            }
        }
        Text(
            text = title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) Color.White else VodColors.TextSoft,
        )
        if (meta != null) {
            Text(text = meta, maxLines = 1, fontSize = 13.sp, color = VodColors.TextMuted)
        }
    }
}

/** Card orizzontale 16:9 (contenuti RSI). overlay = badge sovrapposti all'immagine. */
@Composable
fun WideCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(4.dp, VodColors.Accent), shape = RoundedCornerShape(12.dp)),
            ),
        ) {
            ArtworkBox(image = image, title = title, content = overlay)
        }
        Text(
            text = title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.White else VodColors.TextSoft,
        )
        if (meta != null) Text(text = meta, maxLines = 1, fontSize = 14.sp, color = VodColors.TextMuted)
    }
}

@Composable
private fun ArtworkBox(image: String?, title: String, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VodColors.SurfaceHigh),
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title.take(2).uppercase(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = VodColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        content()
    }
}

@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .background(color),
        )
    }
}

/** Piccola etichetta sovrapposta (IN DIRETTA, RSI LA2, Solo in Svizzera…). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = Color.Black.copy(alpha = 0.7f)) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
