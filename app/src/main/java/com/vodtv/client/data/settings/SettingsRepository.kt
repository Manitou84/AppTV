package com.vodtv.client.data.settings

import com.vodtv.client.data.profile.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

data class AppSettings(
    val baseUrl: String,
    val iframeDomain: String,
    /** Dominio del sito da cui il backend recupera film e serie (inviato come parametro "domain"). */
    val sourceDomain: String = "",
    /** Chiave API v3 o token v4 (Read Access Token) di TMDB. */
    val tmdbApiKey: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
    val hasTmdb: Boolean get() = tmdbApiKey.isNotBlank()
}

/** Impostazioni del profilo attivo: cambiano da sole quando si cambia profilo. */
class SettingsRepository(private val profiles: ProfileRepository) {

    val settings: Flow<AppSettings> = profiles.active
        .filterNotNull()
        .map { it.settings }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = profiles.awaitActive().settings
}
