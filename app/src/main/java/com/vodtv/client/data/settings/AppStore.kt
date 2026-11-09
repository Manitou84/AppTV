package com.vodtv.client.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Unico DataStore dell'app: profilo attivo, PIN genitori, vecchie impostazioni (v1). */
internal val Context.appStore by preferencesDataStore(name = "vod_settings")
