package com.group41.kotlinapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import com.group41.kotlinapp.App
import com.group41.kotlinapp.BuildConfig
import com.group41.kotlinapp.cache.AgendaMemoryCache
import com.group41.kotlinapp.data.local.dao.EnrolledActivity
import com.group41.kotlinapp.data.local.dao.UserEnrollmentDao
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.storage.AgendaPreferences
import com.group41.kotlinapp.storage.AgendaPreferencesStore
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/*
 * Combines the signed-in account's Room enrollments with its DataStore settings
 * for the personal agenda. When the account changes, observation for the former
 * account stops and the screen waits for the new account's settings.
 *
 * Enrollment rows are mapped to scheduled activities here. Calendar export
 * reads a fresh Room snapshot independently of the agenda's display preferences.
 */

/** A snapshot labeled with its owner so the UI never shows another account's data. */
internal data class AgendaUiState(
    val userId: String? = null,
    val preferences: AgendaPreferences? = null,
    val activities: List<AgendaActivity> = emptyList()
)

/** Observes the signed-in account's Room enrollments and offline display preferences together. */
internal class AgendaViewModel internal constructor(
    application: Application,
    private val enrollmentDao: UserEnrollmentDao,
    accountIds: Flow<String?>,
    private val preferencesStore: AgendaPreferencesStore
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as App).database.userEnrollmentDao(),
        ApiClient.tokens.userIds,
        application.agendaPreferences
    )

    private val _uiState = MutableStateFlow(AgendaUiState())
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()
    internal val monthCache = AgendaMemoryCache { event ->
        if (BuildConfig.DEBUG) Log.d("AgendaMemoryCache", event.name)
    }

    fun setShowPending(userId: String, enabled: Boolean) =
        updatePreferences { preferencesStore.setShowPending(userId, enabled) }

    fun setRememberDate(userId: String, enabled: Boolean, date: LocalDate) =
        updatePreferences { preferencesStore.setRememberDate(userId, enabled, date) }

    fun selectDate(userId: String, date: LocalDate) =
        updatePreferences { preferencesStore.saveSelectedDate(userId, date) }

    private fun updatePreferences(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (error: IOException) {
                Log.e("AgendaViewModel", "Unable to save agenda preferences", error)
            }
        }
    }

    /** Reads the requested account's current Room rows when the user starts an export. */
    suspend fun activitiesForExport(userId: String): List<AgendaActivity> =
        enrollmentDao.observeEnrolledActivities(userId).first().mapNotNull { it.toAgendaActivity() }

    init {
        viewModelScope.launch {
            accountIds.distinctUntilChanged().collectLatest { userId ->
                // Cancel the previous Room observation and clear its rows before reading the next account.
                monthCache.invalidate()
                _uiState.value = AgendaUiState(userId)
                if (!userId.isNullOrBlank()) {
                    val activities = enrollmentDao.observeEnrolledActivities(userId)
                        .map { rows ->
                            rows.mapNotNull { it.toAgendaActivity() }
                        }
                        .catch { error ->
                            Log.e("AgendaViewModel", "Unable to read agenda from Room", error)
                            emit(emptyList())
                        }
                        .onStart { emit(emptyList()) }
                    preferencesStore.observe(userId).combine(activities) { preferences, rows ->
                        AgendaUiState(userId, preferences, rows)
                    }.collect { next ->
                        val previous = _uiState.value
                        if (previous.activities != next.activities ||
                            previous.preferences?.showPending != next.preferences?.showPending
                        ) {
                            monthCache.invalidate()
                        }
                        _uiState.value = next
                    }
                }
            }
        }
    }
}

/** Maps a local enrollment that has enough schedule information to appear on the calendar. */
private fun EnrolledActivity.toAgendaActivity(): AgendaActivity? {
    val status = when {
        isCanceled -> return null
        cancellationPending -> AgendaEnrollmentStatus.PENDING_CANCELLATION
        joinPending -> AgendaEnrollmentStatus.PENDING_JOIN
        enrollmentJoined -> AgendaEnrollmentStatus.CONFIRMED
        else -> return null
    }
    val details = activity ?: return null
    val start = parseAgendaTime(details.startTime) ?: return null

    // Keep the recorded zone or offset for export, and leave unrecorded end times unknown.
    return AgendaActivity(
        activityId = enrollmentActivityId,
        title = details.name,
        startsAt = start,
        endsAt = parseAgendaTime(details.endTime),
        enrollmentStatus = status,
        location = details.locationName
    )
}

private fun parseAgendaTime(value: String?): ZonedDateTime? {
    if (value.isNullOrBlank()) return null
    return try {
        ZonedDateTime.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
