package com.vodtv.client.ui.common

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vodtv.client.AppContainer
import com.vodtv.client.VodTvApp
import com.vodtv.client.data.model.ApiException
import com.vodtv.client.data.model.MissingTmdbIdException
import com.vodtv.client.data.model.NoStreamException
import com.vodtv.client.data.model.NotConfiguredException
import com.vodtv.client.data.model.RsiBlockedException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** Factory unica per i ViewModel: dà accesso al contenitore e al SavedStateHandle. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as VodTvApp
        create(app.container, createSavedStateHandle())
    }
}

/** Messaggi d'errore leggibili sul divano. */
fun Throwable.toUserMessage(): String = when (this) {
    is NotConfiguredException -> "Imposta il Base URL del server nelle Impostazioni."
    is MissingTmdbIdException -> "Questo titolo non ha un tmdb_id: impossibile recuperare i flussi."
    is NoStreamException -> "Non è stato trovato nessun flusso riproducibile."
    is RsiBlockedException -> when (reason) {
        "GEOBLOCK" -> "La RSI rende disponibile questo contenuto solo in Svizzera."
        "STARTDATE" -> "Non ancora disponibile: la diretta non è iniziata."
        "ENDDATE" -> "Non più disponibile sulla RSI."
        "LEGAL" -> "Non disponibile per motivi di diritti."
        else -> "Contenuto non disponibile ($reason)."
    }
    is ApiException -> when (statusCode) {
        0 -> message ?: "Servizio non raggiungibile."
        404 -> "Contenuto non trovato (404)."
        in 500..599 -> "Errore del server ($statusCode). Riprova più tardi."
        else -> "Errore HTTP $statusCode."
    }
    is UnknownHostException -> "Server non raggiungibile: controlla l'indirizzo."
    is ConnectException -> "Connessione rifiutata dal server."
    is SocketTimeoutException, is HttpRequestTimeoutException, is ConnectTimeoutException ->
        "Il server non ha risposto in tempo."
    is IllegalArgumentException -> message ?: "Parametri non validi."
    else -> message ?: "Errore imprevisto."
}

/** 761_000 -> "12:41", 6_730_000 -> "1:52:10" */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ITALY, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ITALY, "%d:%02d", m, s)
}

fun formatRemaining(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return "$minutes min rimasti"
}

fun episodeLabel(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S$season · E$episode" else null
