package com.vodtv.client.data.model

import java.io.IOException

class NotConfiguredException : IllegalStateException("Base URL non configurato")

class ApiException(val statusCode: Int, detail: String) : IOException("HTTP $statusCode: $detail")

class NoStreamException : IOException("Nessun flusso riproducibile")

class MissingTmdbIdException : IllegalStateException("tmdb_id mancante")

/** La RSI ha negato la riproduzione: blockReason dell'integration layer (GEOBLOCK, LEGAL, STARTDATE…). */
class RsiBlockedException(val reason: String) : IOException("Contenuto RSI bloccato: $reason")
