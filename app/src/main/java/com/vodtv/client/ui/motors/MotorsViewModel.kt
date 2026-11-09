package com.vodtv.client.ui.motors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Origin
import com.vodtv.client.data.model.ProgressKeys
import com.vodtv.client.data.playback.PlaybackSession
import com.vodtv.client.data.playback.PlaybackSessionStore
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.rsi.MotorSport
import com.vodtv.client.data.rsi.RsiFeed
import com.vodtv.client.data.rsi.RsiMedia
import com.vodtv.client.data.rsi.RsiRepository
import com.vodtv.client.data.rsi.RsiStatus
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MotorsUiState(
    val sport: MotorSport = MotorSport.MOTOGP,
    val isLoading: Boolean = true,
    val feed: RsiFeed? = null,
    val error: String? = null,
    /** Avviso puntuale (contenuto bloccato, diretta non ancora iniziata…). */
    val notice: String? = null,
    val resolvingId: String? = null,
)

class MotorsViewModel(
    private val rsi: RsiRepository,
    private val library: LibraryRepository,
    private val sessions: PlaybackSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MotorsUiState())
    val state: StateFlow<MotorsUiState> = _state.asStateFlow()

    private val _openPlayer = Channel<Unit>(Channel.BUFFERED)
    val openPlayer: Flow<Unit> = _openPlayer.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectSport(sport: MotorSport) {
        if (sport == _state.value.sport) return
        _state.update { it.copy(sport = sport) }
        load()
    }

    fun load() {
        loadJob?.cancel()
        val sport = _state.value.sport
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, notice = null) }
            rsi.feed(sport)
                .onSuccess { feed -> _state.update { it.copy(isLoading = false, feed = feed) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, feed = null, error = e.toUserMessage()) } }
        }
    }

    /** Chiede il flusso alla RSI: se la RSI lo concede si apre il player, altrimenti si spiega perché. */
    fun open(media: RsiMedia) {
        if (_state.value.resolvingId != null) return
        if (media.status() == RsiStatus.UPCOMING) {
            _state.update { it.copy(notice = "Diretta in programma: torna all'orario indicato.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(resolvingId = media.id, notice = null) }
            rsi.resolveStream(media.id)
                .onSuccess { stream ->
                    val key = ProgressKeys.rsi(media.urn)
                    val saved = if (media.isLiveType) null else library.progress(key)
                    sessions.start(
                        PlaybackSession(
                            progressKey = key,
                            ownerId = ProgressKeys.rsiOwner(media.urn),
                            origin = Origin.RSI,
                            title = media.title,
                            subtitle = media.showTitle,
                            image = media.imageUrl,
                            type = ContentType.UNKNOWN,
                            stream = stream,
                            isLive = media.isLiveType,
                            resumeFromMs = saved?.takeIf { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS }?.positionMs ?: 0,
                        ),
                    )
                    _state.update { it.copy(resolvingId = null) }
                    _openPlayer.send(Unit)
                }
                .onFailure { e -> _state.update { it.copy(resolvingId = null, notice = e.toUserMessage()) } }
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ ->
            MotorsViewModel(c.rsiRepository, c.libraryRepository, c.playbackSessionStore)
        }
    }
}
