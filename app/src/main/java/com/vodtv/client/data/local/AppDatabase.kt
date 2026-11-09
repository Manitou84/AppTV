package com.vodtv.client.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE slug = :slug)")
    fun observeIsFavorite(slug: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE slug = :slug")
    suspend fun delete(slug: String)
}

@Dao
interface WatchProgressDao {
    @Upsert
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE id = :id")
    suspend fun get(id: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE ownerId = :ownerId")
    fun observeForOwner(ownerId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface KnownTitleDao {
    @Query("SELECT * FROM known_titles WHERE slug IN (:slugs)")
    suspend fun get(slugs: List<String>): List<KnownTitleEntity>

    @Upsert
    suspend fun upsert(entity: KnownTitleEntity)
}

@Database(
    entities = [FavoriteEntity::class, WatchProgressEntity::class, KnownTitleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun knownTitleDao(): KnownTitleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vodtv.db").build()
    }
}
