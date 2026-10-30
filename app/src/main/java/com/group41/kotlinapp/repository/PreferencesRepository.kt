package com.group41.kotlinapp.repository

import com.group41.kotlinapp.data.local.dao.PreferencesDao
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.model.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * User Preferences Repository backed by the Room local database.
 *
 * Maintains strict per-user isolation (userId) to prevent preference leaks
 * between different accounts on the same device.
 *
 * @param preferencesDao Mandatory DAO for accessing and modifying the 'user_preferences' table.
 */
class PreferencesRepository(
    private val preferencesDao: PreferencesDao
) {

    /**
     * Reads stored preferred categories from Room for a specific user.
     * Converts database entities into the UserPreferences domain model.
     */
    suspend fun getPreferences(userId: String): UserPreferences = withContext(Dispatchers.IO) {
        val entities = preferencesDao.getUserPreferences(userId).first()
        val categories = entities.map { it.category }.toSet()

        UserPreferences(preferredCategories = categories)
    }

    /**
     * Atomically replaces user preferences in Room.
     * Transforms the selected set of category strings into UserPreferenceEntity records.
     */
    suspend fun updatePreferences(
        userId: String,
        categories: Set<String>
    ) = withContext(Dispatchers.IO) {
        val entities = categories.map { category ->
            UserPreferenceEntity(
                userId = userId,
                category = category
            )
        }

        preferencesDao.replaceUserPreferences(userId, entities)
    }
}