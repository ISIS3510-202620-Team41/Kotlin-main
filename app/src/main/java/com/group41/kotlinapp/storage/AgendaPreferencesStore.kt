package com.group41.kotlinapp.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Persists the settings for the personal agenda so they can be read and changed
 * offline. Each account has its own setting for showing pending enrollments,
 * its own date restoration toggle, and its own last saved calendar date.
 *
 * The agenda ViewModel observes these settings alongside the account's Room
 * enrollments. The screen uses them to choose its opening date and filter its
 * calendar markers and daily list. This store lives in a dedicated Preferences
 * DataStore file, separate from authentication and enrollment records.
 */

// Declare the delegate once so every production wrapper shares the same DataStore instance.
private val Context.agendaDataStore by preferencesDataStore(name = "agenda_preferences")

/**
 * Snapshot of an account's agenda settings.
 *
 * @property showPending Whether pending joins and cancellations appear in the agenda.
 *   Defaults to true to preserve the agenda's original display behavior.
 * @property rememberDate Whether to reopen the calendar on its last saved selection.
 *   Defaults to false so the agenda opens on today's date.
 * @property selectedEpochDay A calendar date stored as days since January 1, 1970.
 *   An epoch day represents the date independently of the device's time zone.
 */
internal data class AgendaPreferences(
    val showPending: Boolean = true,
    val rememberDate: Boolean = false,
    val selectedEpochDay: Long? = null
) {
    /** Restores a valid saved date, or opens on the supplied current date. */
    fun openingDate(today: LocalDate = LocalDate.now()): LocalDate {
        val day = selectedEpochDay
        return if (rememberDate && day != null && day in LocalDate.MIN.toEpochDay()..LocalDate.MAX.toEpochDay()) {
            LocalDate.ofEpochDay(day)
        } else {
            today
        }
    }
}

/**
 * Reads and updates agenda preferences for an explicit account ID.
 *
 * Production uses the application-scoped store. The DataStore constructor lets
 * tests use a separate file to verify persistence across reopening.
 */
internal class AgendaPreferencesStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.agendaDataStore)

    /**
     * Emits the requested account's current settings and any subsequent changes.
     * Writes for other accounts leave this snapshot unchanged, so duplicate
     * snapshots are suppressed before they reach the ViewModel.
     */
    fun observe(userId: String): Flow<AgendaPreferences> {
        val keys = Keys(userId)
        return dataStore.data
            // If storage cannot be read, let the agenda open with its default settings.
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map {
                AgendaPreferences(
                    showPending = it[keys.showPending] ?: true,
                    rememberDate = it[keys.rememberDate] ?: false,
                    selectedEpochDay = it[keys.selectedDate]
                )
            }
            .distinctUntilChanged()
    }

    /** Saves the display toggle for the account. Room keeps the underlying enrollments. */
    suspend fun setShowPending(userId: String, enabled: Boolean) {
        dataStore.edit { it[Keys(userId).showPending] = enabled }
    }

    /**
     * Changes date restoration and its stored selection in one DataStore edit.
     * Enabling saves the date currently displayed. Disabling removes the saved
     * date so the next opening starts on today.
     */
    suspend fun setRememberDate(userId: String, enabled: Boolean, selectedDate: LocalDate) {
        val keys = Keys(userId)
        dataStore.edit {
            it[keys.rememberDate] = enabled
            if (enabled) it[keys.selectedDate] = selectedDate.toEpochDay()
            else it.remove(keys.selectedDate)
        }
    }

    /**
     * Saves a selection only while date restoration is enabled for the account.
     * The edit checks the stored toggle so a date update processed after disabling
     * restoration cannot recreate the cleared value.
     */
    suspend fun saveSelectedDate(userId: String, date: LocalDate) {
        val keys = Keys(userId)
        dataStore.edit {
            if (it[keys.rememberDate] == true) it[keys.selectedDate] = date.toEpochDay()
        }
    }

    // Each setting has a key per account, so signing out or switching accounts
    // leaves the previous account's preferences available when it returns.
    private class Keys(userId: String) {
        val showPending = booleanPreferencesKey("show_pending:$userId")
        val rememberDate = booleanPreferencesKey("remember_date:$userId")
        val selectedDate = longPreferencesKey("selected_date:$userId")
    }
}
