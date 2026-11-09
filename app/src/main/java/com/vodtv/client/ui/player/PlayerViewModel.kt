package com.vodtv.client.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vodtv.client.data.model.MissingTmdbIdException
import com.vodtv.client.data.model.ProgressKeys
import com.vodtv.client.data.playback.EpisodeRef
import com.vodtv.client.data.playback.PlaybackSession
import com.vodtv.client.data.playback.PlaybackSessionStore
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NextUpState(val ref: EpisodeRef, val secondsLeft: Int)

data class PlayerUiState(
    val session: PlaybackSession? = null,
    /** Riquadro "Prossimo episodio tra N s". */
    val nextUp: NextUpState? = null,
    val loadingNext: Boolean = false,
    val nextError: String? = null,
    /** Riproduzione conclusa senza episodio successivo (o annullato): il player si chiude. */
    val finished: Boolean = false,
)

class PlayerViewModel(
    private val sessions: PlaybackSessionStore,
    private val library: LibraryRepository,
    private val vod: VodRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState(session = sessions.session.value))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            sessions.session.collect { s ->
                _state.update { it.copy(session = s, nextUp = null, loadingNext = false, nextError = null) }
            }
        }
    }

    /** Chiamato ogni 10 s durante la riproduzione e all'uscita. */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        _state.value.session?.let { library.saveProgressAsync(it, positionMs, durationMs) }
    }

    fun onPlaybackEnded(durationMs: Long) {
        val session = _state.value.session ?: return
        if (durationMs > 0) library.saveProgressAsync(session, durationMs, durationMs)
        val next = session.nextEpisode()
        if (next == null || session.tmdbId == null) {
            _state.update { it.copy(finished = true) }
            return
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (s in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(nextUp = NextUpState(next, s)) }
                delay(1_000)
            }
            playNext()
        }
    }

    fun playNext() {
        countdownJob?.cancel()
        val session = _state.value.session ?: return
        val next = _state.value.nextUp?.ref ?: session.nextEpisode() ?: return
        val tmdb = session.tmdbId ?: run {
            _state.update { it.copy(nextError = MissingTmdbIdException().toUserMessage()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingNext = true, nextError = null) }
            vod.getLinks(tmdb, next.season, next.episode)
                .onSuccess { links ->
                    val key = ProgressKeys.episode(session.ownerId, next.season, next.episode)
                    val saved = library.progress(key)
                    val resume = saved?.takeIf { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS }?.positionMs ?: 0
                    sessions.start(
                        session.copy(
                            progressKey = key,
                            season = next.season,
                            episode = next.episode,
                            subtitle = "S${next.season} · E${next.episode} · ${next.name}",
                            // Episodio successivo: prendiamo automaticamente la prima sorgente
                            stream = session.stream.copy(url = links.first().url),
                            resumeFromMs = resume,
                        ),
                    )
                }
                .onFailure { e -> _state.update { it.copy(loadingNext = false, nextError = e.toUserMessage()) } }
        }
    }

    fun cancelNext() {
        countdownJob?.cancel()
        _state.update { it.copy(nextUp = null, finished = true) }
    }

    companion object {
        private const val COUNTDOWN_SECONDS = 10

        val Factory = appViewModelFactory { c, _ ->
            PlayerViewModel(c.playbackSessionStore, c.libraryRepository, c.vodRepository)
        }
    }
}
