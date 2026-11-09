package com.vodtv.client.data.repository

import com.vodtv.client.data.model.ContentDetail
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.NoStreamException
import com.vodtv.client.data.model.NotConfiguredException
import com.vodtv.client.data.model.SearchResult
import com.vodtv.client.data.model.StreamLink
import com.vodtv.client.data.remote.UrlUtils
import com.vodtv.client.data.remote.VodApiService
import com.vodtv.client.data.remote.VodJsonParser
import com.vodtv.client.data.settings.AppSettings
import com.vodtv.client.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Repository di rete verso il backend VOD.
 * Ogni metodo restituisce Result: i ViewModel non vedono mai eccezioni grezze.
 */
class VodRepository(
    private val api: VodApiService,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Limita le chiamate /api/load fatte in background per classificare i risultati. */
    private val classifySemaphore = Semaphore(permits = 3)

    suspend fun search(query: String): Result<List<SearchResult>> = runApi { s ->
        val results = VodJsonParser.parseSearch(api.search(s.baseUrl, query), s.baseUrl)
        // Applica i tipi già noti (dal backend o dalla cache locale)
        val known = library.knownTypes(results.filter { it.type == ContentType.UNKNOWN }.map { it.slug })
        results.forEach { if (it.type != ContentType.UNKNOWN) library.rememberType(it.slug, it.type) }
        results.map { r -> if (r.type == ContentType.UNKNOWN) r.copy(type = known[r.slug] ?: ContentType.UNKNOWN) else r }
    }

    suspend fun load(slug: String): Result<ContentDetail> = runApi { s ->
        VodJsonParser.parseDetail(api.load(s.baseUrl, slug), slug, s.baseUrl)
            .also { library.rememberType(slug, it.type) }
    }

    /**
     * Scopre se un risultato è film o serie aprendone il dettaglio (max 3 in parallelo).
     * Il risultato viene salvato: ogni titolo si classifica una volta sola.
     */
    suspend fun classify(slug: String): ContentType {
        library.knownTypes(listOf(slug))[slug]?.let { return it }
        return classifySemaphore.withPermit {
            load(slug).getOrNull()?.type ?: ContentType.UNKNOWN
        }
    }

    suspend fun getLinks(tmdbId: String, season: Int?, episode: Int?): Result<List<StreamLink>> = runApi { s ->
        require(s.iframeDomain.isNotBlank()) { "Dominio iframe non configurato: impostalo nelle Impostazioni." }
        val links = VodJsonParser.parseLinks(api.getLinks(s.baseUrl, s.iframeDomain, tmdbId, season, episode))
        if (links.isEmpty()) throw NoStreamException()
        links
    }

    /** Header Referer da inviare al server video (alcuni CDN HLS lo richiedono). */
    suspend fun playbackHeaders(): Map<String, String> {
        val referer = UrlUtils.refererFor(settings.current().iframeDomain) ?: return emptyMap()
        return mapOf("Referer" to referer, "Origin" to referer.trimEnd('/'))
    }

    private suspend fun <T> runApi(block: suspend (AppSettings) -> T): Result<T> = withContext(io) {
        val s = settings.current()
        if (!s.isConfigured) return@withContext Result.failure(NotConfiguredException())
        try {
            Result.success(block(s))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
