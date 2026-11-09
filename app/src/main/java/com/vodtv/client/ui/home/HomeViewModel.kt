package com.vodtv.client.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vodtv.client.data.model.Origin
import com.vodtv.client.data.model.WatchProgress
import com.vodtv.client.data.playback.PlaybackSession
import com.vodtv.client.data.playback.PlaybackSessionStore
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.rsi.RsiRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class OpenDetail(val slug: String) : HomeEvent
    data object OpenPlayer : HomeEvent
}

class HomeViewModel(
    library: LibraryRepository,
    private val rsi: RsiRepository,
    private val sessions: PlaybackSessionStore,
) : ViewModel() {

    /** null = caricamento in corso. */
    val continueWatching: StateFlow<List<WatchProgress>?> = library.continueWatching
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    /**
     * Titoli VOD: si apre il Dettaglio, che propone "Riprendi" o il prossimo episodio.
     * Contenuti RSI: si richiede di nuovo il flusso alla RSI e si riprende da dove eri.
     */
    fun open(item: WatchProgress) {
        _message.value = null
        when (item.origin) {
            Origin.VOD -> viewModelScope.launch { _events.send(HomeEvent.OpenDetail(item.ownerId)) }
            Origin.RSI -> viewModelScope.launch {
                val mediaId = item.ownerId.substringAfterLast(':')
                rsi.resolveStream(mediaId)
                    .onSuccess { stream ->
                        sessions.start(
                            PlaybackSession(
                                progressKey = item.id,
                                ownerId = item.ownerId,
                                origin = Origin.RSI,
                                title = item.title,
                                subtitle = item.subtitle,
                                image = item.image,
                                type = item.type,
                                stream = stream,
                                resumeFromMs = if (item.watched) 0 else item.positionMs,
                            ),
                        )
                        _events.send(HomeEvent.OpenPlayer)
                    }
                    .onFailure { _message.value = it.toUserMessage() }
            }
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ ->
            HomeViewModel(c.libraryRepository, c.rsiRepository, c.playbackSessionStore)
        }
    }
}
