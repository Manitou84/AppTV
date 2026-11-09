package com.vodtv.client

import android.app.Application
import android.content.Context
import com.vodtv.client.data.local.AppDatabase
import com.vodtv.client.data.playback.PlaybackSessionStore
import com.vodtv.client.data.remote.HttpClientFactory
import com.vodtv.client.data.remote.VodApiService
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.data.rsi.RsiRepository
import com.vodtv.client.data.settings.SettingsRepository
import com.vodtv.client.data.tmdb.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Dependency injection manuale: un solo contenitore per tutta l'app. */
class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = HttpClientFactory.create()
    private val database = AppDatabase.create(context)

    val settingsRepository = SettingsRepository(context)
    val libraryRepository = LibraryRepository(database, applicationScope)
    val tmdbRepository = TmdbRepository(httpClient, settingsRepository)
    val vodRepository = VodRepository(VodApiService(httpClient), settingsRepository, libraryRepository, tmdbRepository)
    val rsiRepository = RsiRepository(httpClient)
    val playbackSessionStore = PlaybackSessionStore()
}

class VodTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
