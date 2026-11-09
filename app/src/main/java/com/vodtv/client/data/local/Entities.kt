package com.vodtv.client.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val image: String?,
    val type: String,
    val addedAt: Long,
)

/**
 * Un record per film, episodio o contenuto RSI.
 * id: vedi ProgressKeys. ownerId: slug del titolo (o "rsi:urn") per raggruppare gli episodi di una serie.
 */
@Entity(tableName = "watch_progress", indices = [Index("ownerId"), Index("updatedAt")])
data class WatchProgressEntity(
    @PrimaryKey val id: String,
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

/** Cache della classificazione film/serie, per non richiamare /api/load ogni volta. */
@Entity(tableName = "known_titles")
data class KnownTitleEntity(
    @PrimaryKey val slug: String,
    val type: String,
    val updatedAt: Long,
)
