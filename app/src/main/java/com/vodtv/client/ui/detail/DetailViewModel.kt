package com.vodtv.client.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vodtv.client.data.model.ContentDetail
import com.vodtv.client.data.model.Episode
import com.vodtv.client.data.model.KidPolicy
import com.vodtv.client.data.model.MissingTmdbIdException
import com.vodtv.client.data.model.Origin
import com.vodtv.client.data.model.ProgressKeys
import com.vodtv.client.data.model.Season
import com.vodtv.client.data.model.StreamLink
import com.vodtv.client.data.model.WatchProgress
import com.vodtv.client.data.playback.EpisodeRef
import com.vodtv.client.data.playback.PlaybackSession
import com.vodtv.client.data.playback.PlaybackSessionStore
import com.vodtv.client.data.playback.StreamInfo
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.formatDuration
import com.vodtv.client.ui.common.toUserMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cosa fa il pulsante principale: quale episodio e da che punto. */
data class ResumeTarget(val season: Int?, val episode: Int?, val positionMs: Long, val label: String)

data class DetailUiState(
    val isLoading: Boolean = true,
    val detail: ContentDetail? = null,
    val error: String? = null,
    val isFavorite: Boolean = false,
    /** Avanzamento per id (vedi ProgressKeys). */
    val progress: Map<String, WatchProgress> = emptyMap(),
    val selectedSeasonIndex: Int = 0,
    val resume: ResumeTarget? = null,
    val isResolving: Boolean = false,
    val playError: String? = null,
    val linkChoices: List<StreamLink>? = null,
    /** Profilo bambini e titolo non adatto (o non verificabile con TMDB). */
    val kidBlocked: Boolean = false,
) {
    val selectedSeason: Season? get() = detail?.seasons?.getOrNull(selectedSeasonIndex)

    fun episodeProgress(season: Int, episode: Int): WatchProgress? =
        detail?.let { progress[ProgressKeys.episode(it.slug, season, episode)] }

    fun watchedCount(season: Season): Int = season.episodes.count { episodeProgress(season.number, it.number)?.watched == true }
}

class DetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val vod: VodRepository,
    private val library: LibraryRepository,
    private val sessions: PlaybackSessionStore,
    private val kidMode: Boolean,
) : ViewModel() {

    private val slug: String = checkNotNull(savedStateHandle["slug"])

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _openPlayer = Channel<Unit>(Channel.BUFFERED)
    /** Evento one-shot: la sessione è pronta, apri il player. */
    val openPlayer: Flow<Unit> = _openPlayer.receiveAsFlow()

    private var pending: PendingPlay? = null
    private var seasonChosenByUser = false

    private data class PendingPlay(val season: Int?, val episode: Int?, val resumeFromMs: Long)

    init {
        load()
        viewModelScope.launch {
            library.isFavorite(slug).collect { fav -> _state.update { it.copy(isFavorite = fav) } }
        }
        viewModelScope.launch {
            library.progressForOwner(slug).collect { map ->
                _state.update { it.copy(progress = map) }
                recomputeResume()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            vod.load(slug)
                .onSuccess { d ->
                    val blocked = kidMode && !KidPolicy.isKidSafe(d)
                    _state.update { it.copy(isLoading = false, detail = d, kidBlocked = blocked) }
                    recomputeResume()
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.toUserMessage()) } }
        }
    }

    fun selectSeason(index: Int) {
        seasonChosenByUser = true
        _state.update { if (it.selectedSeasonIndex == index) it else it.copy(selectedSeasonIndex = index) }
    }

    fun toggleFavorite() {
        val d = _state.value.detail ?: return
        viewModelScope.launch { library.setFavorite(d.slug, d.title, d.image, d.type, !_state.value.isFavorite) }
    }

    // ---------- Riproduzione ----------

    /** Pulsante principale: "Riprendi…", "Prossimo…", "Riproduci…". */
    fun playResume() {
        val r = _state.value.resume ?: return
        startPlayback(r.season, r.episode, r.positionMs)
    }

    /** "Dall'inizio": stesso episodio del pulsante principale, da 0. */
    fun playFromStart() {
        val r = _state.value.resume ?: return
        startPlayback(r.season, r.episode, 0)
    }

    fun playEpisode(season: Int, episode: Episode) {
        val p = _state.value.episodeProgress(season, episode.number)
        val resumeAt = if (p != null && !p.watched && p.positionMs >= LibraryRepository.MIN_RESUME_MS) p.positionMs else 0
        startPlayback(season, episode.number, resumeAt)
    }

    fun chooseLink(link: StreamLink) {
        _state.update { it.copy(linkChoices = null) }
        viewModelScope.launch { openSession(link) }
    }

    fun dismissLinkChoice() {
        pending = null
        _state.update { it.copy(linkChoices = null) }
    }

    private fun startPlayback(season: Int?, episode: Int?, resumeFromMs: Long) {
        if (_state.value.kidBlocked) return
        val d = _state.value.detail ?: return
        if (_state.value.isResolving) return
        val tmdb = d.tmdbId
        if (tmdb == null) {
            _state.update { it.copy(playError = MissingTmdbIdException().toUserMessage()) }
            return
        }
        pending = PendingPlay(season, episode, resumeFromMs)
        viewModelScope.launch {
            _state.update { it.copy(isResolving = true, playError = null) }
            vod.getLinks(tmdb, season, episode)
                .onSuccess { links ->
                    _state.update { it.copy(isResolving = false) }
                    if (links.size == 1) openSession(links.first())
                    else _state.update { it.copy(linkChoices = links) }
                }
                .onFailure { e -> _state.update { it.copy(isResolving = false, playError = e.toUserMessage()) } }
        }
    }

    private suspend fun openSession(link: StreamLink) {
        val d = _state.value.detail ?: return
        val p = pending ?: return
        val plan = d.seasons.flatMap { s -> s.episodes.map { EpisodeRef(s.number, it.number, it.name) } }
        val episodeName = plan.firstOrNull { it.season == p.season && it.episode == p.episode }?.name
        val isEpisode = d.isSeries && p.season != null && p.episode != null

        sessions.start(
            PlaybackSession(
                progressKey = if (isEpisode) ProgressKeys.episode(d.slug, p.season!!, p.episode!!) else ProgressKeys.movie(d.slug),
                ownerId = d.slug,
                origin = Origin.VOD,
                title = d.title,
                subtitle = if (isEpisode) "S${p.season} · E${p.episode}" + (episodeName?.let { " · $it" } ?: "") else null,
                image = d.image,
                type = d.type,
                stream = StreamInfo(url = link.url, headers = vod.playbackHeaders()),
                season = p.season,
                episode = p.episode,
                tmdbId = d.tmdbId,
                episodePlan = plan,
                resumeFromMs = p.resumeFromMs,
            ),
        )
        pending = null
        _openPlayer.send(Unit)
    }

    // ---------- Visto / non visto (pressione lunga) ----------

    fun toggleEpisodeWatched(season: Int, episode: Episode) {
        val d = _state.value.detail ?: return
        val watched = _state.value.episodeProgress(season, episode.number)?.watched == true
        viewModelScope.launch {
            library.setWatched(
                id = ProgressKeys.episode(d.slug, season, episode.number),
                ownerId = d.slug,
                title = d.title,
                subtitle = "S$season · E${episode.number} · ${episode.name}",
                image = d.image,
                type = d.type,
                season = season,
                episode = episode.number,
                watched = !watched,
            )
        }
    }

    fun toggleMovieWatched() {
        val d = _state.value.detail ?: return
        if (d.isSeries) return
        val watched = _state.value.progress[ProgressKeys.movie(d.slug)]?.watched == true
        viewModelScope.launch {
            library.setWatched(ProgressKeys.movie(d.slug), d.slug, d.title, null, d.image, d.type, null, null, !watched)
        }
    }

    // ---------- Calcolo "Riprendi" ----------

    private fun recomputeResume() {
        val s = _state.value
        val d = s.detail ?: return
        val resume = if (d.isSeries) seriesResume(d, s) else movieResume(d, s)
        val seasonIndex = if (!seasonChosenByUser && resume?.season != null) {
            d.seasons.indexOfFirst { it.number == resume.season }.coerceAtLeast(0)
        } else {
            s.selectedSeasonIndex
        }
        _state.update { it.copy(resume = resume, selectedSeasonIndex = seasonIndex) }
    }

    private fun movieResume(d: ContentDetail, s: DetailUiState): ResumeTarget {
        val p = s.progress[ProgressKeys.movie(d.slug)]
        return when {
            p != null && !p.watched && p.positionMs >= LibraryRepository.MIN_RESUME_MS ->
                ResumeTarget(null, null, p.positionMs, "Riprendi da ${formatDuration(p.positionMs)}")
            p?.watched == true -> ResumeTarget(null, null, 0, "Riguarda")
            else -> ResumeTarget(null, null, 0, "Riproduci")
        }
    }

    private fun seriesResume(d: ContentDetail, s: DetailUiState): ResumeTarget? {
        val plan = d.seasons.flatMap { season -> season.episodes.map { season.number to it.number } }
        if (plan.isEmpty()) return null
        val entries = s.progress.values.filter { it.season != null && it.episode != null }
        fun label(prefix: String, se: Pair<Int, Int>) = "$prefix S${se.first} · E${se.second}"

        // 1) episodio lasciato a metà più recente
        entries.filter { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS }
            .maxByOrNull { it.updatedAt }
            ?.let { p ->
                return ResumeTarget(p.season, p.episode, p.positionMs,
                    label("Riprendi", p.season!! to p.episode!!) + " da ${formatDuration(p.positionMs)}")
            }

        val watched = entries.filter { it.watched }.map { it.season!! to it.episode!! }.toSet()
        // 2) primo episodio non visto dopo l'ultimo visto
        entries.filter { it.watched }.maxByOrNull { it.updatedAt }?.let { last ->
            val from = plan.indexOf(last.season!! to last.episode!!)
            plan.drop(from + 1).firstOrNull { it !in watched }?.let { next ->
                return ResumeTarget(next.first, next.second, 0, label("Prossimo:", next))
            }
        }
        // 3) primo non visto in assoluto, oppure si ricomincia
        val firstUnwatched = plan.firstOrNull { it !in watched }
        return if (firstUnwatched != null) {
            ResumeTarget(firstUnwatched.first, firstUnwatched.second, 0, label("Riproduci", firstUnwatched))
        } else {
            ResumeTarget(plan.first().first, plan.first().second, 0, label("Riguarda", plan.first()))
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, handle ->
            DetailViewModel(
                handle, c.vodRepository, c.libraryRepository, c.playbackSessionStore,
                kidMode = c.profileRepository.active.value?.isKid == true,
            )
        }
    }
}
