package com.group41.kotlinapp

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.group41.kotlinapp.network.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Declare the delegate once: every AppPreferences wrapper shares this application store.
private val Context.appPreferencesDataStore by preferencesDataStore(
    name = "app_preferences",
    produceMigrations = { context ->
        listOf(LegacySessionPreferencesMigration {
            val tokens = TokenStore(context.applicationContext)
            // Use the same saved-session check as login. A leftover UUID without a
            // refresh token must not make DataStore look like someone is signed in.
            tokens.userId?.takeIf { it.isNotBlank() && tokens.refresh != null }
        })
    }
)

private object AppPreferenceKeys {
    val USER_ID = stringPreferencesKey("userId")
    val LAST_SUCCESSFUL_SYNC = longPreferencesKey("lastSuccessfulSync")
}

/** Copies account metadata for users who were already signed in before DataStore existed. */
internal class LegacySessionPreferencesMigration(
    private val savedSessionUserId: () -> String?
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[AppPreferenceKeys.USER_ID] == null && savedSessionUserId() != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Read the session again in case it was cleared while DataStore was opening.
        // Never replace an account that has already been saved in DataStore.
        if (currentData[AppPreferenceKeys.USER_ID] != null) return currentData
        val userId = savedSessionUserId() ?: return currentData
        return currentData.toMutablePreferences().apply {
            this[AppPreferenceKeys.USER_ID] = userId
            // Without a stored account ID, an old timestamp cannot be attributed safely.
            remove(AppPreferenceKeys.LAST_SUCCESSFUL_SYNC)
        }
    }

    // This is a copy, not a move. Authentication still needs TokenStore's original data.
    override suspend fun cleanUp() = Unit
}

/** Persists account/sync metadata. Authentication tokens remain managed by TokenStore. */
class AppPreferences(context: Context) {
    // Retaining only the application context avoids keeping an Activity alive.
    private val dataStore = context.applicationContext.appPreferencesDataStore

    /** The authenticated user's UUID, or null when it has not been saved or was cleared. */
    val userId: Flow<String?> = dataStore.data.map { it[AppPreferenceKeys.USER_ID] }

    /** Milliseconds since the Unix epoch; null means no successful sync has been recorded. */
    val lastSuccessfulSync: Flow<Long?> = dataStore.data.map { it[AppPreferenceKeys.LAST_SUCCESSFUL_SYNC] }

    suspend fun saveUserId(userId: String) {
        dataStore.edit { preferences ->
            // A sync timestamp belongs to the account that performed that sync. When a
            // different account signs in, remove it so we do not show the previous user's
            // timestamp. Saving the same UUID again keeps that account's sync history.
            if (preferences[AppPreferenceKeys.USER_ID] != userId) {
                preferences.remove(AppPreferenceKeys.LAST_SUCCESSFUL_SYNC)
            }
            // Update both values in one edit so observers see a consistent account state.
            preferences[AppPreferenceKeys.USER_ID] = userId
        }
    }

    suspend fun saveLastSuccessfulSync(timestampMillis: Long, expectedUserId: String? = null) {
        dataStore.edit { preferences ->
            // A worker may finish after logout or after another account signs in.
            // Check the owner inside this edit so old work cannot restore a cleared
            // timestamp or write its result into the new account's preferences.
            if (expectedUserId == null || preferences[AppPreferenceKeys.USER_ID] == expectedUserId) {
                preferences[AppPreferenceKeys.LAST_SUCCESSFUL_SYNC] = timestampMillis
            }
        }
    }

    suspend fun clearUserId() {
        dataStore.edit { it.remove(AppPreferenceKeys.USER_ID) }
    }

    suspend fun clearLastSuccessfulSync() {
        dataStore.edit { it.remove(AppPreferenceKeys.LAST_SUCCESSFUL_SYNC) }
    }

    /** Clear both values atomically, for example when the current account signs out. */
    suspend fun clear() {
        dataStore.edit {
            it.remove(AppPreferenceKeys.USER_ID)
            it.remove(AppPreferenceKeys.LAST_SUCCESSFUL_SYNC)
        }
    }
}
