package com.vodtv.client.data.repository

import com.vodtv.client.data.local.AppDatabase
import com.vodtv.client.data.local.FavoriteEntity
import com.vodtv.client.data.local.KnownTitleEntity
import com.vodtv.client.data.local.WatchProgressEntity
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.Favorite
import com.vodtv.client.data.model.Origin
import com.vodtv.client.data.model.WatchProgress
import com.vodtv.client.data.playback.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dati personali salvati sulla TV: preferiti, avanzamento di visione, classificazione film/serie.
 * appScope sopravvive alle schermate: un salvataggio lanciato all'uscita dal player non viene annullato.
 */
class LibraryRepository(
    db: AppDatabase,
    private val appScope: CoroutineScope,
) {
    private val favoriteDao = db.favoriteDao()
    private val progressDao = db.watchProgressDao()
    private val knownDao = db.knownTitleDao()

    // ---------- Preferiti ----------

    val favorites: Flow<List<Favorite>> = favoriteDao.observeAll().map { list ->
        list.map { Favorite(it.slug, it.title, it.image, ContentType.fromStored(it.type), it.addedAt) }
    }

    fun isFavorite(slug: String): Flow<Boolean> = favoriteDao.observeIsFavorite(slug)

    suspend fun setFavorite(slug: String, title: String, image: String?, type: ContentType, favorite: Boolean) {
        if (favorite) {
            favoriteDao.upsert(FavoriteEntity(slug, title, image, type.name, System.currentTimeMillis()))
        } else {
            favoriteDao.delete(slug)
        }
    }

    // ---------- Avanzamento di visione ----------

    fun progressForOwner(ownerId: String): Flow<Map<String, WatchProgress>> =
        progressDao.observeForOwner(ownerId).map { list -> list.associate { it.id to it.toModel() } }

    suspend fun progress(id: String): WatchProgress? = progressDao.get(id)?.toModel()

    /**
     * "Continua a guardare": per ogni titolo l'ultima cosa vista.
     * - in corso -> si riprende da lì
     * - serie con ultimo episodio finito -> resta in lista (il Dettaglio propone il successivo)
     * - film finiti -> esclusi
     */
    val continueWatching: Flow<List<WatchProgress>> = progressDao.observeAll().map { list ->
        list.map { it.toModel() }
            .groupBy { it.ownerId }
            .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
            .filter { p ->
                when {
                    !p.watched -> p.positionMs >= MIN_RESUME_MS
                    else -> p.type == ContentType.SERIES
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_CONTINUE_WATCHING)
    }

    /** Salvataggio periodico dal player. Visto = oltre il 90% della durata (titoli di coda esclusi). */
    fun saveProgressAsync(session: PlaybackSession, positionMs: Long, durationMs: Long) {
        if (session.isLive || durationMs <= 0) return
        appScope.launch {
            val previous = progressDao.get(session.progressKey)
            val reachedEnd = positionMs >= durationMs * WATCHED_THRESHOLD
            progressDao.upsert(
                WatchProgressEntity(
                    id = session.progressKey,
                    ownerId = session.ownerId,
                    origin = session.origin.name,
                    title = session.title,
                    subtitle = session.subtitle,
                    image = session.image,
                    type = session.type.name,
                    season = session.season,
                    episode = session.episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    watched = reachedEnd || (previous?.watched == true && positionMs < MIN_RESUME_MS),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Segna a mano visto / non visto (pressione lunga su OK). */
    suspend fun setWatched(
        id: String,
        ownerId: String,
        title: String,
        subtitle: String?,
        image: String?,
        type: ContentType,
        season: Int?,
        episode: Int?,
        watched: Boolean,
    ) {
        val previous = progressDao.get(id)
        progressDao.upsert(
            WatchProgressEntity(
                id = id,
                ownerId = ownerId,
                origin = Origin.VOD.name,
                title = title,
                subtitle = subtitle,
                image = image,
                type = type.name,
                season = season,
                episode = episode,
                positionMs = if (watched) previous?.durationMs ?: 0 else 0,
                durationMs = previous?.durationMs ?: 0,
                watched = watched,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearHistory() = progressDao.clear()

    // ---------- Classificazione film / serie ----------

    suspend fun knownTypes(slugs: List<String>): Map<String, ContentType> =
        if (slugs.isEmpty()) emptyMap()
        else knownDao.get(slugs).associate { it.slug to ContentType.fromStored(it.type) }

    suspend fun rememberType(slug: String, type: ContentType) {
        if (type != ContentType.UNKNOWN) {
            knownDao.upsert(KnownTitleEntity(slug, type.name, System.currentTimeMillis()))
        }
    }

    private fun WatchProgressEntity.toModel() = WatchProgress(
        id = id,
        ownerId = ownerId,
        origin = Origin.entries.firstOrNull { it.name == origin } ?: Origin.VOD,
        title = title,
        subtitle = subtitle,
        image = image,
        type = ContentType.fromStored(type),
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        watched = watched,
        updatedAt = updatedAt,
    )

    companion object {
        const val WATCHED_THRESHOLD = 0.9
        const val MIN_RESUME_MS = 30_000L
        private const val MAX_CONTINUE_WATCHING = 20
    }
}
