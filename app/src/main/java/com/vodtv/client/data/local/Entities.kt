package com.vodtv.client.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Profilo: ognuno ha impostazioni, preferiti e cronologia propri. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isKid: Boolean,
    val colorIndex: Int,
    val baseUrl: String,
    val sourceDomain: String,
    val iframeDomain: String,
    val tmdbApiKey: String,
    val createdAt: Long,
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "slug"])
data class FavoriteEntity(
    val profileId: String,
    val slug: String,
    val title: String,
    val image: String?,
    val type: String,
    val addedAt: Long,
)

/**
 * Un record per film, episodio o contenuto RSI, per profilo.
 * id: vedi ProgressKeys. ownerId: slug del titolo (o "rsi:urn") per raggruppare gli episodi di una serie.
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["profileId", "id"],
    indices = [Index(value = ["profileId", "ownerId"]), Index(value = ["profileId", "updatedAt"])],
)
data class WatchProgressEntity(
    val profileId: String,
    val id: String,
    val ownerId: String,
    val origin: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val type: String,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
)

/** Cache della classificazione film/serie, condivisa fra i profili. */
@Entity(tableName = "known_titles")
data class KnownTitleEntity(
    @PrimaryKey val slug: String,
    val type: String,
    val updatedAt: Long,
)
