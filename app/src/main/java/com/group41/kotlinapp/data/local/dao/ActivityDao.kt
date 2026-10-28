package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for interacting with the 'activities' database table.
 * Room automatically implements these methods at compile time.
 */
@Dao
interface ActivityDao {

    /**
     * Observes all cached activities in real-time.
     * Returns a Kotlin Flow so the UI automatically updates whenever local data changes.
     */
    @Query("SELECT * FROM activities")
    fun getAllActivities(): Flow<List<ActivityEntity>>

    /**
     * Uses a suspend function to retrieve an activity asynchronously without blocking the UI.
     */
    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getActivityById(id: String): ActivityEntity?

    /**
     * Inserts entities into the database if their primary key doesn't exist,
     * or updates them if present.
     *
     * Compared with REPLACE, @Upsert avoids unnecessarily deleting and recreating existing rows.
     */
    @Upsert
    suspend fun insertActivities(activities: List<ActivityEntity>)

    /**
     * Inserts a single entity if its primary key doesn't exist, or updates it if present.
     */
    @Upsert
    suspend fun insertActivity(activity: ActivityEntity)

    /**
     * Deletes all rows from the 'activities' table.
     * Useful for clearing cache during logout or when doing a full remote sync.
     */
    @Query("DELETE FROM activities")
    suspend fun clearAll()

    /**
     * Atomically clears the existing cache and inserts the fresh list of activities.
     *
     * @Transaction prevents partial refreshes by completing both operations atomically.
     * Note: Avoid using this method indiscriminately when integrating offline activity creation,
     * as clearing all cached rows could erase locally created activities that haven't synced to the backend.
     */
    @Transaction
    suspend fun refreshActivities(activities: List<ActivityEntity>) {
        clearAll()
        insertActivities(activities)
    }
}