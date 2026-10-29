package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for managing user category preferences in the 'user_preferences' table.
 */
@Dao
interface PreferencesDao {

    /**
     * Observes preferences in real-time for a specific user ID.
     */
    @Query("SELECT * FROM user_preferences WHERE userId = :userId")
    fun getUserPreferences(userId: String): Flow<List<UserPreferenceEntity>>

    /**
     * Inserts a list of user preferences if absent, or updates them if already present.
     * suspend is used for asynchronous, non-blocking execution, it is used to prevent
     * UI freezing (Main-Thread Safety), it allows for pauses without blocking, and sequential
     * syntax for Asynchronous code
     */
    @Upsert
    suspend fun insertPreferences(preferences: List<UserPreferenceEntity>)

    /**
     * Deletes all preference entries belonging specifically to the given userId.
     */
    @Query("DELETE FROM user_preferences WHERE userId = :userId")
    suspend fun deleteUserPreferences(userId: String)

    /**
     * Atomically replaces a user's preferences by deleting previous entries for that userId
     * and inserting the new list in a single transaction.
     *
     * Validates that all preferences in the list strictly belong to the provided userId
     * before deleting or inserting any data.
     */
    @Transaction
    suspend fun replaceUserPreferences(userId: String, preferences: List<UserPreferenceEntity>) {
        // Validation check: throws an IllegalArgumentException if any entity has a mismatched userId
        require(preferences.all { it.userId == userId }) {
            "All preferences in the list must match the provided userId: $userId"
        }

        deleteUserPreferences(userId)
        insertPreferences(preferences)
    }
}