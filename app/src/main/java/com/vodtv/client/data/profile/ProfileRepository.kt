package com.vodtv.client.data.profile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vodtv.client.data.local.AppDatabase
import com.vodtv.client.data.local.ProfileEntity
import com.vodtv.client.data.remote.UrlUtils
import com.vodtv.client.data.settings.AppSettings
import com.vodtv.client.data.settings.appStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class Profile(
    val id: String,
    val name: String,
    val isKid: Boolean,
    val colorIndex: Int,
    val baseUrl: String,
    val sourceDomain: String,
    val iframeDomain: String,
    val tmdbApiKey: String,
    val createdAt: Long,
) {
    val settings: AppSettings get() = AppSettings(baseUrl, iframeDomain, sourceDomain, tmdbApiKey)
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/**
 * Profili (standard e bambini), profilo attivo e PIN genitori.
 * Il PIN è salvato come hash SHA-256 con sale casuale, mai in chiaro.
 */
class ProfileRepository(
    private val context: Context,
    private val db: AppDatabase,
    scope: CoroutineScope,
) {
    private val dao = db.profileDao()

    private object Keys {
        val ACTIVE = stringPreferencesKey("active_profile_id")
        val PIN_HASH = stringPreferencesKey("parent_pin_hash")
        val PIN_SALT = stringPreferencesKey("parent_pin_salt")
        // Impostazioni della v1, importate nel primo profilo
        val LEGACY_BASE_URL = stringPreferencesKey("base_url")
        val LEGACY_IFRAME = stringPreferencesKey("iframe_domain")
        val LEGACY_SOURCE = stringPreferencesKey("source_domain")
        val LEGACY_TMDB = stringPreferencesKey("tmdb_api_key")
    }

    private val prefs = context.appStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** null finché il database non ha risposto. */
    val profiles: StateFlow<List<Profile>?> = dao.observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val hasPin: StateFlow<Boolean> = prefs.map { !it[Keys.PIN_HASH].isNullOrEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Profilo attivo; se l'id salvato non esiste più, il primo profilo. */
    val active: StateFlow<Profile?> = combine(profiles, prefs.map { it[Keys.ACTIVE] }) { list, id ->
        list?.let { all -> all.firstOrNull { it.id == id } ?: all.firstOrNull() }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun awaitActive(): Profile = active.filterNotNull().first()

    /** Al primo avvio crea il profilo "Principale", con le impostazioni della versione precedente. */
    suspend fun ensureDefaultProfile() {
        if (dao.count() > 0) return
        val p = prefs.first()
        val profile = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = "Principale",
            isKid = false,
            colorIndex = 0,
            baseUrl = p[Keys.LEGACY_BASE_URL].orEmpty(),
            sourceDomain = p[Keys.LEGACY_SOURCE].orEmpty(),
            iframeDomain = p[Keys.LEGACY_IFRAME].orEmpty(),
            tmdbApiKey = p[Keys.LEGACY_TMDB].orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(profile)
        switchTo(profile.id)
    }

    /** Nuovo profilo: copia server, domini e chiave TMDB da [template], così è subito utilizzabile. */
    suspend fun create(name: String, isKid: Boolean, template: Profile?): Profile {
        val existing = profiles.value.orEmpty()
        val entity = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { if (isKid) "Bambini" else "Profilo ${existing.size + 1}" },
            isKid = isKid,
            colorIndex = (existing.maxOfOrNull { it.colorIndex } ?: -1) + 1,
            baseUrl = template?.baseUrl.orEmpty(),
            sourceDomain = template?.sourceDomain.orEmpty(),
            iframeDomain = template?.iframeDomain.orEmpty(),
            tmdbApiKey = template?.tmdbApiKey.orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        return entity.toModel()
    }

    suspend fun update(profile: Profile) {
        dao.upsert(
            ProfileEntity(
                id = profile.id,
                name = profile.name.trim().ifEmpty { "Profilo" },
                isKid = profile.isKid,
                colorIndex = profile.colorIndex,
                baseUrl = UrlUtils.normalizeBaseUrl(profile.baseUrl),
                sourceDomain = UrlUtils.normalizeDomain(profile.sourceDomain),
                iframeDomain = UrlUtils.normalizeDomain(profile.iframeDomain),
                tmdbApiKey = profile.tmdbApiKey.trim(),
                createdAt = profile.createdAt,
            ),
        )
    }

    /** Elimina profilo, preferiti e cronologia. Le regole (non l'attivo, non l'ultimo standard) le applica chi chiama. */
    suspend fun delete(id: String) {
        db.favoriteDao().deleteForProfile(id)
        db.watchProgressDao().clear(id)
        dao.delete(id)
    }

    suspend fun switchTo(id: String) {
        context.appStore.edit { it[Keys.ACTIVE] = id }
    }

    // ---------- PIN genitori ----------

    suspend fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) { "Il PIN deve avere $PIN_LENGTH cifre." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        context.appStore.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = prefs.first()
        val salt = p[Keys.PIN_SALT] ?: return false
        val stored = p[Keys.PIN_HASH] ?: return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun ProfileEntity.toModel() =
        Profile(id, name, isKid, colorIndex, baseUrl, sourceDomain, iframeDomain, tmdbApiKey, createdAt)

    companion object {
        const val PIN_LENGTH = 4
    }
}
