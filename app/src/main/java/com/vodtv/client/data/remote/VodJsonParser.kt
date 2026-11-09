package com.vodtv.client.data.remote

import com.vodtv.client.data.model.ContentDetail
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Episode
import com.vodtv.client.data.model.SearchResult
import com.vodtv.client.data.model.Season
import com.vodtv.client.data.model.StreamLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Converte le risposte del backend nei modelli dell'app.
 * Volutamente tollerante: accetta varianti comuni dei nomi di campo e delle strutture.
 */
internal object VodJsonParser {

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")

    fun parseSearch(root: JsonElement, baseUrl: String): List<SearchResult> =
        root.findList("results", "items", "data")
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val slug = o.str("slug", "id") ?: return@mapNotNull null
                SearchResult(
                    name = o.str("name", "title") ?: slug,
                    slug = slug,
                    image = UrlUtils.resolve(baseUrl, o.str("image", "poster", "cover", "thumbnail")),
                    // Se il backend un giorno aggiunge "type", lo usiamo subito
                    type = ContentType.from(o.str("type", "category", "kind", "media_type")),
                )
            }
            .distinctBy { it.slug }

    fun parseDetail(root: JsonElement, slug: String, baseUrl: String): ContentDetail {
        val top = root as? JsonObject ?: throw IllegalArgumentException("Formato dettagli non valido")
        val o = top.obj("data") ?: top.obj("result") ?: top
        val seasons = parseSeasons(o, baseUrl)
        val declared = ContentType.from(o.str("type", "category", "kind", "media_type"))
        val type = when {
            seasons.isNotEmpty() -> ContentType.SERIES
            declared != ContentType.UNKNOWN -> declared
            else -> ContentType.MOVIE // nessuna stagione e nessun tipo: lo trattiamo come film
        }
        return ContentDetail(
            slug = slug,
            title = o.str("name", "title") ?: slug,
            plot = o.str("plot", "description", "overview", "trama"),
            image = UrlUtils.resolve(baseUrl, o.str("image", "poster", "cover")),
            backdrop = UrlUtils.resolve(baseUrl, o.str("backdrop", "background", "fanart", "banner")),
            year = o.str("year", "release_date", "date")?.take(4),
            tmdbId = o.str("tmdb_id", "tmdbId", "tmdb"),
            type = type,
            seasons = seasons,
        )
    }

    /** Raccoglie ricorsivamente tutti gli URL http(s) dal dizionario di get_links, preferendo gli .m3u8. */
    fun parseLinks(root: JsonElement): List<StreamLink> {
        val found = mutableListOf<StreamLink>()

        fun walk(el: JsonElement, path: String) {
            when (el) {
                is JsonObject -> el.forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path · $k") }
                is JsonArray -> el.forEachIndexed { i, v ->
                    walk(v, if (path.isEmpty()) "Sorgente ${i + 1}" else "$path ${i + 1}")
                }
                is JsonPrimitive -> if (el.isString) {
                    val s = el.content.trim()
                    if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
                        found += StreamLink(label = path.ifEmpty { "Sorgente" }, url = s)
                    }
                }
            }
        }

        walk(root, "")
        val hls = found.filter { it.url.contains(".m3u8", ignoreCase = true) }
        val candidates = hls.ifEmpty {
            found.filterNot { link -> IMAGE_EXTENSIONS.any { link.url.substringBefore('?').endsWith(it, true) } }
        }
        return candidates.distinctBy { it.url }
    }

    // --- Stagioni ed episodi ---

    private fun parseSeasons(o: JsonObject, baseUrl: String): List<Season> {
        val raw = o["seasons"] ?: o["episodes"] ?: return emptyList()
        val seasons = when (raw) {
            is JsonArray -> {
                val objs = raw.mapNotNull { it as? JsonObject }
                if (objs.isNotEmpty() && objs.none { it["episodes"] != null }) {
                    // Lista piatta di episodi: [{season:1, episode:3, …}]
                    objs.groupBy { it.int("season", "season_number") ?: 1 }
                        .map { (n, eps) -> Season(n, "Stagione $n", parseEpisodes(JsonArray(eps), baseUrl)) }
                } else {
                    raw.mapIndexedNotNull { i, el -> parseSeason(el, i + 1, baseUrl) }
                }
            }
            // Mappa {"1": [...], "2": [...]}
            is JsonObject -> raw.entries.mapIndexedNotNull { i, (key, el) ->
                parseSeason(el, key.toIntOrNull() ?: (i + 1), baseUrl)
            }
            else -> emptyList()
        }
        return seasons.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }
    }

    private fun parseSeason(el: JsonElement, fallbackNumber: Int, baseUrl: String): Season? = when (el) {
        is JsonObject -> {
            val n = el.int("number", "season", "season_number") ?: fallbackNumber
            Season(n, el.str("name", "title") ?: "Stagione $n", parseEpisodes(el["episodes"], baseUrl))
        }
        is JsonArray -> Season(fallbackNumber, "Stagione $fallbackNumber", parseEpisodes(el, baseUrl))
        else -> null
    }

    private fun parseEpisodes(el: JsonElement?, baseUrl: String): List<Episode> {
        val items: List<Pair<String?, JsonElement>> = when (el) {
            is JsonArray -> el.map { null to it }
            is JsonObject -> el.entries.map { it.key to it.value }
            else -> return emptyList()
        }
        return items.mapIndexedNotNull { i, (key, item) ->
            val fallback = key?.toIntOrNull() ?: (i + 1)
            when (item) {
                is JsonObject -> {
                    val n = item.int("number", "episode", "episode_number") ?: fallback
                    Episode(
                        number = n,
                        name = item.str("name", "title") ?: "Episodio $n",
                        plot = item.str("plot", "description", "overview"),
                        image = UrlUtils.resolve(baseUrl, item.str("image", "still", "thumbnail")),
                    )
                }
                is JsonNull -> null
                is JsonPrimitive -> item.intOrNull?.let { Episode(it, "Episodio $it", null, null) }
                    ?: Episode(fallback, item.content, null, null)
                else -> null
            }
        }.distinctBy { it.number }.sortedBy { it.number }
    }
}
