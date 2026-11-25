package com.group41.kotlinapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import com.group41.kotlinapp.App
import com.group41.kotlinapp.data.local.dao.EnrolledActivity
import com.group41.kotlinapp.data.local.dao.UserEnrollmentDao
import com.group41.kotlinapp.network.ApiClient
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Observes the signed-in account's Room enrollments for the offline agenda. */
internal class AgendaViewModel internal constructor(
    application: Application,
    enrollmentDao: UserEnrollmentDao,
    accountIds: Flow<String?>
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as App).database.userEnrollmentDao(),
        ApiClient.tokens.observeUserId()
    )

    private val _activities = MutableStateFlow<List<AgendaActivity>>(emptyList())
    val activities: StateFlow<List<AgendaActivity>> = _activities.asStateFlow()

    init {
        viewModelScope.launch {
            accountIds.distinctUntilChanged().collectLatest { userId ->
                // Cancel the previous Room observation and clear its rows before reading the next account.
                _activities.value = emptyList()
                if (!userId.isNullOrBlank()) {
                    enrollmentDao.observeEnrolledActivities(userId)
                        .map { rows ->
                            rows.mapNotNull { it.toAgendaActivity() }.sortedBy { it.startsAt.toInstant() }
                        }
                        .catch { error ->
                            Log.e("AgendaViewModel", "Unable to read agenda from Room", error)
                            emit(emptyList())
                        }
                        .collect { _activities.value = it }
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
