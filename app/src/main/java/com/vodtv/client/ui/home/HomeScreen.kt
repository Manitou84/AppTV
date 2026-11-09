package com.vodtv.client.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.WatchProgress
import com.vodtv.client.ui.common.formatRemaining
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.components.MessagePanel
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.PosterCard
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.theme.VodColors

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    onSelectTab: (NavTab) -> Unit,
    onOpenDetail: (String) -> Unit,
    onPlay: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val items by viewModel.continueWatching.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenDetail -> onOpenDetail(event.slug)
                HomeEvent.OpenPlayer -> onPlay()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        TopNavBar(current = NavTab.HOME, onSelect = onSelectTab)

        val list = items
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> MessagePanel(
                message = "Qui troverai film ed episodi che stai guardando, pronti da riprendere.",
                actionLabel = "Cerca un film",
                onAction = { onSelectTab(NavTab.MOVIES) },
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Continua a guardare", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
                message?.let { Text(it, fontSize = 16.sp, color = VodColors.Live) }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    modifier = Modifier.focusRestorer(),
                ) {
                    itemsIndexed(list, key = { _, p -> p.id }) { index, p ->
                        PosterCard(
                            title = p.title,
                            image = p.image,
                            onClick = { viewModel.open(p) },
                            meta = metaFor(p),
                            progress = if (p.watched) null else p.fraction,
                            modifier = Modifier
                                .width(211.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
                LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
            }
        }
    }
}

private fun metaFor(p: WatchProgress): String = when {
    p.watched && p.type == ContentType.SERIES -> "Prossimo episodio"
    else -> listOfNotNull(
        when {
            p.season != null -> "S${p.season} · E${p.episode}"
            p.type == ContentType.MOVIE -> "Film"
            else -> null
        },
        if (p.durationMs > 0) formatRemaining(p.remainingMs) else null,
    ).joinToString(" · ")
}
