package com.vodtv.client.data.remote

object UrlUtils {

    /** "server-vod.esempio/" -> "http://server-vod.esempio" */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (hasScheme(trimmed)) trimmed else "http://$trimmed"
    }

    /** "https://www.sito.esempio/" -> "www.sito.esempio" (solo host, come si aspetta il backend). */
    fun normalizeDomain(raw: String): String =
        raw.trim()
            .replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')

    /** Risolve percorsi di immagini relativi rispetto al Base URL. */
    fun resolve(baseUrl: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            hasScheme(path) -> path
            path.startsWith("//") -> "https:$path"
            else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
    }

    /** Dominio iframe -> valore per l'header Referer ("https://dominio/"). */
    fun refererFor(iframeDomain: String): String? {
        val d = iframeDomain.trim().trimEnd('/')
        if (d.isEmpty()) return null
        return (if (hasScheme(d)) d else "https://$d") + "/"
    }

    private fun hasScheme(s: String) =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
}
