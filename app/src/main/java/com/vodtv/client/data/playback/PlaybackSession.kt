package com.vodtv.client.data.playback

import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Origin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dati tecnici del flusso da passare a ExoPlayer. */
data class StreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** URL licenza Widevine, se il flusso è protetto (alcuni contenuti RSI). */
    val widevineLicenseUrl: String? = null,
)

data class EpisodeRef(val season: Int, val episode: Int, val name: String)

/**
 * Tutto ciò che serve al player: cosa riprodurre, dove salvare l'avanzamento,
 * da dove riprendere e (per le serie) quale episodio viene dopo.
 */
data class PlaybackSession(
    val progressKey: String,
    val ownerId: String,
    val origin: Origin,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val type: ContentType,
    val stream: StreamInfo,
    val isLive: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val tmdbId: String? = null,
    val episodePlan: List<EpisodeRef> = emptyList(),
    val resumeFromMs: Long = 0,
) {
    val displayTitle: String get() = if (subtitle.isNullOrBlank()) title else "$title · $subtitle"

    fun nextEpisode(): EpisodeRef? {
        if (season == null || episode == null) return null
        val index = episodePlan.indexOfFirst { it.season == season && it.episode == episode }
        return if (index >= 0) episodePlan.getOrNull(index + 1) else null
    }
}

/**
 * Sessione di riproduzione corrente, condivisa tra Dettaglio/Home/Motori e Player.
 * Evita di serializzare oggetti complessi negli argomenti di navigazione.
 */
class PlaybackSessionStore {
    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val session: StateFlow<PlaybackSession?> = _session.asStateFlow()

    fun start(session: PlaybackSession) {
        _session.value = session
    }

    fun clear() {
        _session.value = null
    }
}
