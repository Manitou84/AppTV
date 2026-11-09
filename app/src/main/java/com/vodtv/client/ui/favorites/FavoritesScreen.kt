package com.vodtv.client.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Favorite
import com.vodtv.client.data.model.WatchProgress
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.components.MessagePanel
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.PosterCard
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.theme.VodColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FavoriteItem(val favorite: Favorite, val status: String?)

data class FavoritesUiState(val series: List<FavoriteItem>, val movies: List<FavoriteItem>)

class FavoritesViewModel(library: LibraryRepository) : ViewModel() {

    /** Preferiti divisi per tipo, con lo stato di visione ricavato dalla cronologia. */
    val state: StateFlow<FavoritesUiState?> = combine(
        library.favorites,
        library.continueWatching,
    ) { favorites, recent ->
        val byOwner = recent.associateBy { it.ownerId }
        val items = favorites.map { FavoriteItem(it, statusFor(it, byOwner[it.slug])) }
        FavoritesUiState(
            series = items.filter { it.favorite.type == ContentType.SERIES },
            // UNKNOWN finisce tra i film: succede solo per vecchi salvataggi non classificati
            movies = items.filter { it.favorite.type != ContentType.SERIES },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun statusFor(f: Favorite, p: WatchProgress?): String? = when {
        p == null -> null
        f.type == ContentType.SERIES && p.season != null ->
            if (p.watched) "Visto fino a S${p.season} · E${p.episode}" else "In corso: S${p.season} · E${p.episode}"
        !p.watched -> "In corso · ${(p.fraction * 100).toInt()}%"
        else -> null
    }

    companion object {
        val Factory = appViewModelFactory { c, _ -> FavoritesViewModel(c.libraryRepository) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FavoritesScreen(
    onSelectTab: (NavTab) -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: FavoritesViewModel = viewModel(factory = FavoritesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TopNavBar(current = NavTab.FAVORITES, onSelect = onSelectTab)

        val s = state
        when {
            s == null -> LoadingBox()
            s.series.isEmpty() && s.movies.isEmpty() -> MessagePanel(
                "Nessun preferito. Apri un titolo e premi «Aggiungi ai preferiti».",
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (s.series.isNotEmpty()) item { FavoriteRow("Serie TV", s.series, onOpenDetail) }
                if (s.movies.isNotEmpty()) item { FavoriteRow("Film", s.movies, onOpenDetail) }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FavoriteRow(title: String, items: List<FavoriteItem>, onOpenDetail: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("$title · ${items.size}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = { it.favorite.slug }) { item ->
                PosterCard(
                    title = item.favorite.title,
                    image = item.favorite.image,
                    meta = item.status,
                    onClick = { onOpenDetail(item.favorite.slug) },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}
