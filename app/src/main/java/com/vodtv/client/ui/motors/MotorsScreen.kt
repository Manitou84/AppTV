package com.vodtv.client.ui.motors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Text
import com.vodtv.client.data.rsi.MotorSport
import com.vodtv.client.data.rsi.RsiMedia
import com.vodtv.client.data.rsi.RsiStatus
import com.vodtv.client.ui.common.formatDuration
import com.vodtv.client.ui.components.Badge
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.components.MessagePanel
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.components.WideCard
import com.vodtv.client.ui.theme.VodColors
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sezione Motori: MotoGP e Formula 1 dall'archivio RSI. */
@Composable
fun MotorsScreen(
    onSelectTab: (NavTab) -> Unit,
    onPlay: () -> Unit,
    viewModel: MotorsViewModel = viewModel(factory = MotorsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.openPlayer.collect { onPlay() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopNavBar(current = NavTab.MOTORS, onSelect = onSelectTab)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MotorSport.entries.forEach { sport ->
                FilterChip(
                    selected = sport == state.sport,
                    onClick = { viewModel.selectSport(sport) },
                    colors = FilterChipDefaults.colors(
                        containerColor = VodColors.SurfaceHigh,
                        contentColor = VodColors.TextSoft,
                        selectedContainerColor = VodColors.Text,
                        selectedContentColor = VodColors.Ground,
                        focusedContainerColor = VodColors.Accent,
                        focusedContentColor = VodColors.OnAccent,
                        focusedSelectedContainerColor = VodColors.Accent,
                        focusedSelectedContentColor = VodColors.OnAccent,
                    ),
                ) {
                    Text(sport.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            Box(Modifier.weight(1f))
            Text("Contenuti RSI", fontSize = 14.sp, color = VodColors.TextMuted)
        }

        state.notice?.let { Text(it, fontSize = 16.sp, color = VodColors.Accent) }

        val feed = state.feed
        when {
            state.isLoading && feed == null -> LoadingBox()
            state.error != null -> MessagePanel(state.error!!, actionLabel = "Riprova", onAction = viewModel::load)
            feed != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                item {
                    MediaRow(
                        title = "Dirette e prossimi appuntamenti",
                        empty = "Nessuna diretta ${state.sport.label} in programma sulla RSI.",
                        items = feed.live,
                        resolvingId = state.resolvingId,
                        onOpen = viewModel::open,
                    )
                }
                item {
                    MediaRow(
                        title = "Highlight, repliche e servizi",
                        empty = "Nessun contenuto ${state.sport.label} trovato nell'archivio RSI.",
                        items = feed.onDemand,
                        resolvingId = state.resolvingId,
                        onOpen = viewModel::open,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MediaRow(
    title: String,
    empty: String,
    items: List<RsiMedia>,
    resolvingId: String?,
    onOpen: (RsiMedia) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
        if (items.isEmpty()) {
            Text(empty, fontSize = 16.sp, color = VodColors.TextMuted)
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = { it.urn }) { media ->
                val status = media.status()
                WideCard(
                    title = media.title,
                    image = media.imageUrl,
                    meta = if (media.id == resolvingId) "Chiedo il flusso alla RSI…" else metaFor(media, status),
                    onClick = { onOpen(media) },
                    modifier = Modifier.width(270.dp),
                ) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status == RsiStatus.LIVE) Badge("IN DIRETTA", container = VodColors.Live)
                        if (status == RsiStatus.UPCOMING) Badge("IN PROGRAMMA")
                        media.showTitle?.let { Badge(it.take(18)) }
                    }
                    if (media.isGeoBlocked) {
                        Badge("Solo in Svizzera", modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                    }
                    media.durationMs?.takeIf { it > 0 && !media.isLiveType }?.let {
                        Badge(formatDuration(it), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
                    }
                }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ITALIAN)

private fun OffsetDateTime.local(): String = atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)

private fun metaFor(media: RsiMedia, status: RsiStatus): String = when (status) {
    RsiStatus.LIVE -> "In onda ora"
    RsiStatus.UPCOMING -> media.validFrom?.local() ?: media.date?.local() ?: "Prossimamente"
    RsiStatus.BLOCKED -> "Non disponibile"
    RsiStatus.AVAILABLE -> media.date?.local() ?: ""
}
