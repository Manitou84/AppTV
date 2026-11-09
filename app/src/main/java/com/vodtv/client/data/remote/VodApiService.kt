package com.vodtv.client.data.remote

import com.vodtv.client.data.model.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * Chiamate HTTP verso il backend VOD. Il Base URL arriva a ogni chiamata
 * (è dinamico, modificabile dalle Impostazioni).
 */
class VodApiService(private val client: HttpClient) {

    /**
     * GET /api/search?q={query}[&domain={sourceDomain}]
     * "domain" = sito da cui il backend recupera film e serie; inviato solo se impostato.
     * Se il tuo backend usa un altro nome di parametro, cambialo in SOURCE_DOMAIN_PARAM.
     */
    suspend fun search(baseUrl: String, query: String, sourceDomain: String): JsonElement =
        getJson("$baseUrl/api/search") {
            parameter("q", query)
            sourceDomain(sourceDomain)
        }

    /** GET /api/load?slug={slug}[&domain={sourceDomain}] */
    suspend fun load(baseUrl: String, slug: String, sourceDomain: String): JsonElement =
        getJson("$baseUrl/api/load") {
            parameter("slug", slug)
            sourceDomain(sourceDomain)
        }

    private fun HttpRequestBuilder.sourceDomain(domain: String) {
        if (domain.isNotBlank()) parameter(SOURCE_DOMAIN_PARAM, domain)
    }

    /**
     * GET /api/get_links?iframe_domain=…&tmdb_id=…&season=…&episode=…
     * Per i film season ed episode sono null: Ktor omette i parametri null.
     * Se il tuo backend vuole la stringa letterale "null", passa sendNullLiteral = true.
     */
    suspend fun getLinks(
        baseUrl: String,
        iframeDomain: String,
        tmdbId: String,
        season: Int?,
        episode: Int?,
        sendNullLiteral: Boolean = false,
    ): JsonElement = getJson("$baseUrl/api/get_links") {
        parameter("iframe_domain", iframeDomain)
        parameter("tmdb_id", tmdbId)
        parameter("season", season ?: if (sendNullLiteral) "null" else null)
        parameter("episode", episode ?: if (sendNullLiteral) "null" else null)
    }

    private suspend fun getJson(url: String, block: HttpRequestBuilder.() -> Unit): JsonElement {
        val response = client.get(url, block)
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ApiException(response.status.value, text.take(200))
        }
        return try {
            HttpClientFactory.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ApiException(response.status.value, "risposta non JSON: ${text.take(120)}")
        }
    }

    companion object {
        const val SOURCE_DOMAIN_PARAM = "domain"
    }
}
