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
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeAll(profileId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND slug = :slug)")
    fun observeIsFavorite(profileId: String, slug: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND slug = :slug")
    suspend fun delete(profileId: String, slug: String)

    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)
}

@Dao
interface WatchProgressDao {
    @Upsert
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND id = :id")
    suspend fun get(profileId: String, id: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND ownerId = :ownerId")
    fun observeForOwner(profileId: String, ownerId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observeAll(profileId: String): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

@Dao
interface KnownTitleDao {
    @Query("SELECT * FROM known_titles WHERE slug IN (:slugs)")
    suspend fun get(slugs: List<String>): List<KnownTitleEntity>

    @Upsert
    suspend fun upsert(entity: KnownTitleEntity)
}

@Database(
    entities = [ProfileEntity::class, FavoriteEntity::class, WatchProgressEntity::class, KnownTitleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun knownTitleDao(): KnownTitleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vodtv.db")
                // v1 → v2 (profili): la prima versione era appena uscita, si riparte da zero.
                // Dalle prossime versioni servirà una Migration vera per non perdere i dati.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
