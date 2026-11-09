package com.vodtv.client.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vodtv.client.data.model.ContentDetail
import com.vodtv.client.data.model.Season
import com.vodtv.client.data.model.StreamLink
import com.vodtv.client.ui.common.formatRemaining
import com.vodtv.client.ui.components.ButtonTextPadding
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.components.MessagePanel
import com.vodtv.client.ui.components.PrimaryButton
import com.vodtv.client.ui.components.ProgressLine
import com.vodtv.client.ui.components.SecondaryButton
import com.vodtv.client.ui.theme.VodColors
import java.util.Locale

@Composable
fun DetailScreen(
    onPlay: () -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.openPlayer.collect { onPlay() }
    }

    Box(Modifier.fillMaxSize()) {
        val detail = state.detail
        when {
            state.isLoading && detail == null -> LoadingBox()
            detail == null -> MessagePanel(state.error ?: "Errore", actionLabel = "Riprova", onAction = viewModel::load)
            state.kidBlocked -> MessagePanel("«${detail.title}» non è disponibile nel profilo bambini.")
            else -> DetailContent(detail = detail, state = state, viewModel = viewModel)
        }
        state.linkChoices?.let { links ->
            SourceDialog(links = links, onChoose = viewModel::chooseLink, onDismiss = viewModel::dismissLinkChoice)
        }
    }
}

@Composable
private fun DetailContent(detail: ContentDetail, state: DetailUiState, viewModel: DetailViewModel) {
    val playFocus = remember { FocusRequester() }

    // Sfondo TMDB sfumato verso il nero, dietro a tutto il contenuto
    detail.backdrop?.let { backdrop ->
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to VodColors.Ground,
                            0.55f to VodColors.Ground.copy(alpha = 0.85f),
                            1f to VodColors.Ground.copy(alpha = 0.3f),
                        ),
                    ),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        AsyncImage(
            model = detail.image,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(280.dp)
                .height(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(VodColors.SurfaceHigh),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val kicker = buildList {
                add(if (detail.isSeries) "SERIE TV" else "FILM")
                detail.year?.let { add(it) }
                if (detail.isSeries) add("${detail.seasons.size} stagioni")
                if (!detail.isSeries) detail.runtimeMin?.let { add(formatRuntime(it)) }
                detail.rating?.let { add("★ " + String.format(Locale.ITALY, "%.1f", it)) }
            }.joinToString(" · ")
            Text(kicker, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = VodColors.TextMuted, letterSpacing = 1.4.sp)
            Text(detail.title, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = VodColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.genres.isNotEmpty()) {
                Text(detail.genres.take(4).joinToString(" · "), fontSize = 16.sp, color = VodColors.TextSoft)
            }
            detail.plot?.let {
                Text(
                    it,
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    color = VodColors.TextSoft,
                    maxLines = if (detail.isSeries) 2 else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 780.dp),
                )
            }

            // --- Azioni ---
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val resume = state.resume
                PrimaryButton(
                    onClick = viewModel::playResume,
                    // Pressione lunga sul film: segna visto / non visto
                    onLongClick = if (detail.isSeries) null else viewModel::toggleMovieWatched,
                    enabled = resume != null,
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Text(
                        text = if (state.isResolving) "Carico il flusso…" else "▶  ${resume?.label ?: "Riproduci"}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = ButtonTextPadding,
                    )
                }
                if (resume != null && resume.positionMs > 0) {
                    SecondaryButton(onClick = viewModel::playFromStart) {
                        Text("Dall'inizio", fontSize = 17.sp, modifier = ButtonTextPadding)
                    }
                }
                SecondaryButton(onClick = viewModel::toggleFavorite) {
                    Text(
                        if (state.isFavorite) "♥  Nei preferiti" else "♡  Aggiungi ai preferiti",
                        fontSize = 17.sp,
                        modifier = ButtonTextPadding,
                    )
                }
            }
            state.playError?.let { Text(it, fontSize = 16.sp, color = androidx.tv.material3.MaterialTheme.colorScheme.error) }
            if (!detail.isSeries && state.progress.values.any { it.watched }) {
                Text("✓ Visto · tieni premuto OK su Riproduci per segnarlo come non visto", fontSize = 14.sp, color = VodColors.Watched)
            }

            if (detail.isSeries) {
                SeasonRow(detail.seasons, state, viewModel)
                EpisodeList(state, viewModel)
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
}

@Composable
private fun SeasonRow(seasons: List<Season>, state: DetailUiState, viewModel: DetailViewModel) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.number }) { index, season ->
            val selected = index == state.selectedSeasonIndex
            FilterChip(
                selected = selected,
                onClick = { viewModel.selectSeason(index) },
                // La stagione cambia appena ci passi sopra col D-pad
                modifier = Modifier.onFocusChanged { if (it.isFocused) viewModel.selectSeason(index) },
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
                val watched = state.watchedCount(season)
                val label = if (selected && watched > 0) "${season.name} · $watched/${season.episodes.size} visti" else season.name
                Text(label, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun EpisodeList(state: DetailUiState, viewModel: DetailViewModel) {
    val season = state.selectedSeason ?: return
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(season.episodes, key = { "${season.number}-${it.number}" }) { episode ->
            val progress = state.episodeProgress(season.number, episode.number)
            val watched = progress?.watched == true
            val inProgress = !watched && progress != null && progress.positionMs > 0 && progress.durationMs > 0

            ListItem(
                selected = false,
                onClick = { viewModel.playEpisode(season.number, episode) },
                // Pressione lunga: segna visto / non visto
                onLongClick = { viewModel.toggleEpisodeWatched(season.number, episode) },
                colors = ListItemDefaults.colors(
                    containerColor = VodColors.Surface,
                    contentColor = VodColors.Text,
                    focusedContainerColor = VodColors.SurfaceHigh,
                    focusedContentColor = VodColors.Text,
                ),
                border = ListItemDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, VodColors.Accent), shape = RoundedCornerShape(12.dp)),
                ),
                shape = ListItemDefaults.shape(shape = RoundedCornerShape(12.dp)),
                leadingContent = {
                    Text("${episode.number}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = VodColors.TextMuted)
                },
                headlineContent = {
                    Text(
                        episode.name,
                        fontSize = 17.sp,
                        color = if (watched) VodColors.TextMuted else VodColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = if (inProgress) {
                    { ProgressLine(fraction = progress!!.fraction, color = VodColors.Accent, modifier = Modifier.width(240.dp).padding(top = 6.dp)) }
                } else {
                    null
                },
                trailingContent = {
                    when {
                        watched -> Text("✓ Visto", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = VodColors.Watched)
                        inProgress -> Text(formatRemaining(progress!!.remainingMs), fontSize = 14.sp, color = VodColors.TextSoft)
                        episode.runtimeMin != null -> Text("${episode.runtimeMin} min", fontSize = 14.sp, color = VodColors.TextMuted)
                    }
                },
            )
        }
    }
}

private fun formatRuntime(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Scelta del flusso quando get_links ne restituisce più di uno. */
@Composable
private fun SourceDialog(links: List<StreamLink>, onChoose: (StreamLink) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = VodColors.Surface),
            modifier = Modifier.width(620.dp),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Scegli la sorgente", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(320.dp)) {
                    itemsIndexed(links) { index, link ->
                        ListItem(
                            selected = false,
                            onClick = { onChoose(link) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            headlineContent = { Text(link.label, fontSize = 18.sp) },
                            supportingContent = {
                                Text("…" + link.url.substringBefore('?').takeLast(48), fontSize = 13.sp, maxLines = 1)
                            },
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }
}
