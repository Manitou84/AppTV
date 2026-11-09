package com.vodtv.client.data.model

/** Tipo di contenuto. UNKNOWN = il backend non l'ha dichiarato e non l'abbiamo ancora classificato. */
enum class ContentType {
    MOVIE, SERIES, UNKNOWN;

    companion object {
        fun from(raw: String?): ContentType = when (raw?.trim()?.lowercase()) {
            "movie", "movies", "film", "films" -> MOVIE
            "tv", "series", "serie", "serie tv", "tvshow", "tv_show", "show" -> SERIES
            else -> UNKNOWN
        }

        fun fromStored(raw: String?): ContentType = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

data class SearchResult(
    val name: String,
    val slug: String,
    val image: String?,
    val type: ContentType = ContentType.UNKNOWN,
)

data class ContentDetail(
    val slug: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val backdrop: String?,
    val year: String?,
    val tmdbId: String?,
    val type: ContentType,
    val seasons: List<Season>,
) {
    val isSeries: Boolean get() = type == ContentType.SERIES
}

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class Episode(val number: Int, val name: String, val plot: String?, val image: String?)

/** Un flusso restituito da /api/get_links (etichetta = percorso della chiave nel JSON). */
data class StreamLink(val label: String, val url: String)

/** Preferito salvato in locale. */
data class Favorite(
    val slug: String,
    val title: String,
    val image: String?,
    val type: ContentType,
    val addedAt: Long,
)

enum class Origin { VOD, RSI }

/** Avanzamento di visione salvato in locale (un record per film, episodio o contenuto RSI). */
data class WatchProgress(
    val id: String,
    val ownerId: String,
    val origin: Origin,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val type: ContentType,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

object ProgressKeys {
    fun movie(slug: String) = "vod:$slug"
    fun episode(slug: String, season: Int, episode: Int) = "vod:$slug:s$season:e$episode"
    fun rsi(urn: String) = "rsi:$urn"
    fun rsiOwner(urn: String) = "rsi:$urn"
}
