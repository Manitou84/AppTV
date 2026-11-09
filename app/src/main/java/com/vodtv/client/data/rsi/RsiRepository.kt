package com.vodtv.client.data.rsi

import com.vodtv.client.data.model.ApiException
import com.vodtv.client.data.model.NoStreamException
import com.vodtv.client.data.model.RsiBlockedException
import com.vodtv.client.data.playback.StreamInfo
import com.vodtv.client.data.remote.HttpClientFactory
import com.vodtv.client.data.remote.findList
import com.vodtv.client.data.remote.long
import com.vodtv.client.data.remote.obj
import com.vodtv.client.data.remote.str
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

/**
 * Integration layer pubblico SRG SSR, lo stesso usato dal player web della RSI.
 *
 * Verificato (es. estrattore yt-dlp):  mediaComposition/{type}/{id}.json, token Akamai, blockReason.
 * DA VERIFICARE sul campo: i percorsi delle liste (ricerca, dirette, palinsesto). Per questo ogni
 * lista prova più percorsi candidati e usa il primo che risponde con dati.
 *
 * L'app non aggira alcun blocco: se la RSI risponde con blockReason, il contenuto viene mostrato
 * come non disponibile.
 */
private object RsiEndpoints {
    const val IL = "https://il.srgssr.ch/integrationlayer/2.0/rsi"

    fun search(q: String): List<String> {
        val e = q.encodeURLQueryComponent()
        return listOf(
            "$IL/searchResultMediaList?q=$e&sortBy=date&pageSize=40",
            "$IL/mediaList/video/search?q=$e&pageSize=40",
        )
    }

    val livestreams = listOf("$IL/mediaList/video/livestreams")
    val scheduled = listOf("$IL/mediaList/video/scheduledLivestreams?pageSize=60")

    fun mediaComposition(id: String) = "$IL/mediaComposition/video/$id.json?onlyChapters=true"

    const val AKAMAI_TOKEN = "https://tp.srgssr.ch/akahd/token?acl=*"
}

class RsiRepository(
    private val client: HttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun feed(sport: MotorSport): Result<RsiFeed> = withContext(io) {
        try {
            coroutineScope {
                val liveJob = async { fetchFirstList(RsiEndpoints.livestreams) }
                val scheduledJob = async { fetchFirstList(RsiEndpoints.scheduled) }
                val searchJobs = sport.queries.map { q -> async { fetchFirstList(RsiEndpoints.search(q)) } }

                val liveRaw = liveJob.await()
                val scheduledRaw = scheduledJob.await()
                val searchRaw = searchJobs.awaitAll()

                if (liveRaw == null && scheduledRaw == null && searchRaw.all { it == null }) {
                    throw ApiException(0, "integration layer RSI non raggiungibile")
                }

                val now = OffsetDateTime.now()
                val matches: (RsiMedia) -> Boolean = { m ->
                    val text = " ${m.searchableText()} "
                    sport.keywords.any { text.contains(it) }
                }

                val live = (liveRaw.orEmpty() + scheduledRaw.orEmpty())
                    .filter(matches)
                    .filter { it.validTo?.isBefore(now) != true }
                    .distinctBy { it.urn }
                    .sortedWith(compareBy<RsiMedia> { it.status(now) != RsiStatus.LIVE }.thenBy { it.validFrom ?: it.date })

                val onDemand = searchRaw.filterNotNull().flatten()
                    .filterNot { it.isLiveType }
                    .filter(matches)
                    .distinctBy { it.urn }
                    .sortedByDescending { it.date }

                Result.success(RsiFeed(live = live, onDemand = onDemand))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Chiede alla RSI il flusso riproducibile. Se la RSI lo nega, restituisce RsiBlockedException. */
    suspend fun resolveStream(mediaId: String): Result<StreamInfo> = withContext(io) {
        try {
            val root = getJson(RsiEndpoints.mediaComposition(mediaId)) as? JsonObject
                ?: throw ApiException(0, "mediaComposition non valida")
            val chapters = root["chapterList"] as? JsonArray ?: throw NoStreamException()
            val chapter = chapters.mapNotNull { it as? JsonObject }
                .let { list -> list.firstOrNull { it.str("id") == mediaId } ?: list.firstOrNull() }
                ?: throw NoStreamException()

            chapter.str("blockReason")?.let { throw RsiBlockedException(it) }

            val resources = (chapter["resourceList"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val hls = resources
                .filter { r -> r.str("protocol").equals("HLS", true) || r.str("url")?.contains(".m3u8") == true }
                .sortedBy { r -> if (r.str("quality").equals("HD", true)) 0 else 1 }
            val resource = hls.firstOrNull() ?: throw NoStreamException()
            var url = resource.str("url") ?: throw NoStreamException()

            if (resource.str("tokenType").equals("AKAMAI", true)) {
                val auth = (getJson(RsiEndpoints.AKAMAI_TOKEN) as? JsonObject)?.obj("token")?.str("authparams")
                if (auth != null) url += (if ('?' in url) "&" else "?") + auth
            }

            val widevine = (resource["drmList"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("type").equals("WIDEVINE", true) }
                ?.str("licenseUrl")

            Result.success(StreamInfo(url = url, widevineLicenseUrl = widevine))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- interni ----------

    /** Prova i percorsi in ordine; null se nessuno risponde. */
    private suspend fun fetchFirstList(candidates: List<String>): List<RsiMedia>? {
        for (url in candidates) {
            val parsed = runCatching { getJson(url) }.getOrNull() ?: continue
            val items = parsed.findList("mediaList", "searchResultMediaList", "list")
                .mapNotNull { (it as? JsonObject)?.toRsiMedia() }
            if (items.isNotEmpty() || parsed is JsonObject) return items
        }
        return null
    }

    private suspend fun getJson(url: String): JsonElement {
        val response = client.get(url)
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw ApiException(response.status.value, text.take(120))
        return HttpClientFactory.json.parseToJsonElement(text)
    }

    private fun JsonObject.toRsiMedia(): RsiMedia? {
        val id = str("id") ?: return null
        return RsiMedia(
            id = id,
            urn = str("urn") ?: "urn:rsi:video:$id",
            title = str("title") ?: return null,
            showTitle = obj("show")?.str("title"),
            description = str("description", "lead"),
            imageUrl = str("imageUrl"),
            date = str("date")?.toDateTime(),
            durationMs = long("duration"),
            type = str("type"),
            blockReason = str("blockReason"),
            validFrom = str("validFrom")?.toDateTime(),
            validTo = str("validTo")?.toDateTime(),
        )
    }

    private fun String.toDateTime(): OffsetDateTime? = runCatching { OffsetDateTime.parse(this) }.getOrNull()
}
