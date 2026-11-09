package com.vodtv.client.data.tmdb

import com.vodtv.client.data.model.ApiException
import com.vodtv.client.data.model.ContentDetail
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Episode
import com.vodtv.client.data.model.Season
import com.vodtv.client.data.remote.HttpClientFactory
import com.vodtv.client.data.remote.int
import com.vodtv.client.data.remote.str
import com.vodtv.client.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Arricchisce i titoli con i dati di TMDB (in italiano): titolo, trama, locandina, sfondo,
 * generi, voto, durata e, per le serie, nomi/trame/immagini/durate degli episodi.
 *
 * Accetta sia la chiave API v3 (32 caratteri) sia il Read Access Token v4 ("eyJ…").
 * Se TMDB non risponde, il dettaglio resta quello del backend: l'app funziona comunque.
 */
class TmdbRepository(
    private val client: HttpClient,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = ConcurrentHashMap<String, ContentDetail>()

    suspend fun enrich(detail: ContentDetail): ContentDetail {
        val id = detail.tmdbId ?: return detail
        val key = settings.current().tmdbApiKey
        if (key.isBlank()) return detail
        cache["${detail.slug}:$id"]?.let { return it }

        return withContext(io) {
            try {
                val (kind, info) = fetchInfo(id, detail.type, key) ?: return@withContext detail
                val enriched = merge(detail, kind, info, key)
                cache["${detail.slug}:$id"] = enriched
                enriched
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detail
            }
        }
    }

    /** Prova /movie o /tv in base al tipo noto; se sconosciuto le prova entrambe. */
    private suspend fun fetchInfo(id: String, type: ContentType, key: String): Pair<ContentType, JsonObject>? {
        val order = when (type) {
            ContentType.SERIES -> listOf(ContentType.SERIES)
            ContentType.MOVIE -> listOf(ContentType.MOVIE, ContentType.SERIES)
            ContentType.UNKNOWN -> listOf(ContentType.MOVIE, ContentType.SERIES)
        }
        for (kind in order) {
            val path = if (kind == ContentType.SERIES) "tv/$id" else "movie/$id"
            runCatching { get(path, key) }.getOrNull()?.let { return kind to it }
        }
        return null
    }

    private suspend fun merge(detail: ContentDetail, kind: ContentType, info: JsonObject, key: String): ContentDetail {
        val isSeries = kind == ContentType.SERIES || detail.seasons.isNotEmpty()
        val genreObjects = (info["genres"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val genres = genreObjects.mapNotNull { it.str("name") }
        val genreIds = genreObjects.mapNotNull { it.int("id") }
        val adult = (info["adult"] as? JsonPrimitive)?.content == "true"
        val runtime = info.int("runtime")
            ?: (info["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val rating = (info["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }

        val seasons = if (isSeries) enrichSeasons(detail, info, key) else detail.seasons

        return detail.copy(
            title = info.str("title", "name") ?: detail.title,
            plot = info.str("overview") ?: detail.plot,
            image = image(info.str("poster_path"), POSTER) ?: detail.image,
            backdrop = image(info.str("backdrop_path"), BACKDROP) ?: detail.backdrop,
            year = info.str("release_date", "first_air_date")?.take(4) ?: detail.year,
            type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
            seasons = seasons,
            genres = genres,
            rating = rating,
            runtimeMin = runtime?.takeIf { it > 0 },
            genreIds = genreIds,
            adult = adult,
            tmdbEnriched = true,
        )
    }

    /**
     * Stagioni: se il backend le fornisce, le arricchiamo episodio per episodio;
     * altrimenti usiamo direttamente la struttura di TMDB (stagione 0 "Speciali" esclusa).
     */
    private suspend fun enrichSeasons(detail: ContentDetail, info: JsonObject, key: String): List<Season> = coroutineScope {
        val tmdbSeasons = (info["seasons"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.int("season_number") }
            .filter { it > 0 }
        val numbers = detail.seasons.map { it.number }.ifEmpty { tmdbSeasons }

        val fetched = numbers.map { n ->
            async { n to runCatching { get("tv/${detail.tmdbId}/season/$n", key) }.getOrNull() }
        }.awaitAll().toMap()

        numbers.mapNotNull { n ->
            val own = detail.seasons.firstOrNull { it.number == n }
            val remote = fetched[n]
            val remoteEpisodes = (remote?.get("episodes") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .associateBy { it.int("episode_number") ?: -1 }

            val episodes = if (own != null) {
                own.episodes.map { ep -> remoteEpisodes[ep.number]?.let { mergeEpisode(ep, it) } ?: ep }
            } else {
                remoteEpisodes.values.sortedBy { it.int("episode_number") }.map { e ->
                    val number = e.int("episode_number") ?: 0
                    mergeEpisode(Episode(number, "Episodio $number", null, null), e)
                }
            }
            if (episodes.isEmpty()) null
            else Season(n, remote?.str("name") ?: own?.name ?: "Stagione $n", episodes)
        }
    }

    /** TMDB vince, tranne quando dà solo "Episodio N" e il backend ha un titolo vero. */
    private fun mergeEpisode(ep: Episode, e: JsonObject) = ep.copy(
        name = e.str("name")?.takeUnless { isGeneric(it) && !isGeneric(ep.name) } ?: ep.name,
        plot = e.str("overview") ?: ep.plot,
        image = image(e.str("still_path"), STILL) ?: ep.image,
        runtimeMin = e.int("runtime")?.takeIf { it > 0 } ?: ep.runtimeMin,
    )

    private suspend fun get(path: String, key: String): JsonObject {
        val response = client.get("$API/$path") {
            parameter("language", LANGUAGE)
            if (key.startsWith("eyJ")) header(HttpHeaders.Authorization, "Bearer $key")
            else parameter("api_key", key)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw ApiException(response.status.value, "TMDB: ${text.take(100)}")
        return HttpClientFactory.json.parseToJsonElement(text) as? JsonObject
            ?: throw ApiException(response.status.value, "TMDB: risposta non valida")
    }

    /** Verifica la chiave dalle Impostazioni. */
    suspend fun checkKey(key: String): Result<Unit> = withContext(io) {
        try {
            get("configuration", key.trim())
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun image(path: String?, size: String): String? = path?.let { "$IMAGES/$size$it" }

    private fun isGeneric(name: String) = GENERIC_EPISODE.matches(name.trim())

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p"
        const val LANGUAGE = "it-IT"
        const val POSTER = "w500"
        const val BACKDROP = "w1280"
        const val STILL = "w300"
        val GENERIC_EPISODE = Regex("(?i)(episodio|episode|ep\\.?)\\s*\\d+")
    }
}
