package com.vodtv.client.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.components.MessagePanel
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.PosterCard
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.components.TvTextField
import com.vodtv.client.ui.theme.VodColors

/** Sezioni "Film" e "Serie TV": stessa schermata, filtro diverso. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(
    target: ContentType,
    onSelectTab: (NavTab) -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: CatalogViewModel = viewModel(key = "catalog-${target.name}", factory = CatalogViewModel.factory(target)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isMovies = target == ContentType.MOVIE
    val tab = if (isMovies) NavTab.MOVIES else NavTab.SERIES
    val title = if (isMovies) "Film" else "Serie TV"
    val visible = remember(state.items) { state.visibleFor(target) }

    val searchFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val restoreSlug = remember { viewModel.lastFocusedSlug }
    val gridState = rememberLazyGridState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TopNavBar(current = tab, onSelect = onSelectTab)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
            TvTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = if (isMovies) "Cerca tra i film" else "Cerca tra le serie",
                imeAction = ImeAction.Search,
                onImeAction = viewModel::submit,
                modifier = Modifier
                    .width(520.dp)
                    .focusRequester(searchFocus),
            )
            Button(onClick = viewModel::submit) { Text("Cerca") }
            val status = when {
                state.classifying > 0 -> "Riconosco film e serie… (${state.classifying})"
                state.hasSearched && !state.isLoading -> "${visible.size} titoli"
                else -> ""
            }
            Text(status, fontSize = 15.sp, color = VodColors.TextMuted)
        }

        when {
            !state.isConfigured -> MessagePanel(
                message = "Configura il Base URL del server per iniziare.",
                actionLabel = "Apri Impostazioni",
                onAction = { onSelectTab(NavTab.SETTINGS) },
            )
            state.isLoading && state.items.isEmpty() -> LoadingBox()
            state.error != null -> MessagePanel(state.error!!, actionLabel = "Riprova", onAction = viewModel::retry)
            state.hasSearched && visible.isEmpty() && state.classifying == 0 ->
                MessagePanel(if (isMovies) "Nessun film trovato." else "Nessuna serie trovata.")
            !state.hasSearched -> MessagePanel(
                if (isMovies) "Cerca un film: premi OK sul campo per aprire la tastiera."
                else "Cerca una serie: premi OK sul campo per aprire la tastiera.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                state = gridState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer(),
            ) {
                items(visible, key = { it.slug }) { item ->
                    PosterCard(
                        title = item.name,
                        image = item.image,
                        onClick = { onOpenDetail(item.slug) },
                        meta = if (item.type == ContentType.UNKNOWN) "…" else null,
                        modifier = Modifier
                            .then(if (item.slug == restoreSlug) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) viewModel.onItemFocused(item.slug) },
                    )
                }
            }
        }
    }

    // Focus iniziale: l'ultimo titolo aperto (tornando dal Dettaglio) oppure il campo di ricerca
    LaunchedEffect(Unit) {
        val index = visible.indexOfFirst { it.slug == restoreSlug }
        if (index >= 0) {
            gridState.scrollToItem(index)
            withFrameNanos { } // attende che la card sia composta
            runCatching { restoreFocus.requestFocus() }
        } else {
            runCatching { searchFocus.requestFocus() }
        }
    }
}
