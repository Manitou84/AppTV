package com.vodtv.client.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vodtv.client.data.remote.UrlUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "vod_settings")

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

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val IFRAME_DOMAIN = stringPreferencesKey("iframe_domain")
        val SOURCE_DOMAIN = stringPreferencesKey("source_domain")
        val TMDB_KEY = stringPreferencesKey("tmdb_api_key")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                baseUrl = prefs[Keys.BASE_URL].orEmpty(),
                iframeDomain = prefs[Keys.IFRAME_DOMAIN].orEmpty(),
                sourceDomain = prefs[Keys.SOURCE_DOMAIN].orEmpty(),
                tmdbApiKey = prefs[Keys.TMDB_KEY].orEmpty(),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(baseUrl: String, iframeDomain: String, sourceDomain: String, tmdbApiKey: String) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.BASE_URL] = UrlUtils.normalizeBaseUrl(baseUrl)
            prefs[Keys.IFRAME_DOMAIN] = UrlUtils.normalizeDomain(iframeDomain)
            prefs[Keys.SOURCE_DOMAIN] = UrlUtils.normalizeDomain(sourceDomain)
            prefs[Keys.TMDB_KEY] = tmdbApiKey.trim()
        }
    }
}
