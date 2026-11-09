package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.model.FriendAvailabilityItem
import com.group41.kotlinapp.model.ScheduleConcurrencyEngine
import com.group41.kotlinapp.model.ScheduleMatrixResult
import com.group41.kotlinapp.model.SharedGap
import com.group41.kotlinapp.model.TimeInterval
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.repository.ApiFriendScheduleRepository
import com.group41.kotlinapp.repository.FriendScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun currentTodayString(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

data class FriendScheduleUiState(
    val selectedDate: String = currentTodayString(),
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val friends: List<FriendAvailabilityItem> = emptyList(),
    val matrixResult: ScheduleMatrixResult? = null,
    val errorMessage: String? = null,
    val filterMinMinutes: Int = 0
)

class FriendScheduleViewModel(
    private val repository: FriendScheduleRepository = ApiFriendScheduleRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendScheduleUiState())
    val uiState: StateFlow<FriendScheduleUiState> = _uiState.asStateFlow()

    private var myUser: UserDto = UserDto(
        id = ApiClient.tokens.userId ?: "current-user",
        email = "",
        name = "Yo",
        bio = null,
        avatarUrl = null
    )
    private var mySlots: List<FreeSlotDto> = emptyList()

    init {
        loadData(_uiState.value.selectedDate)
    }

    fun setDate(date: String) {
        if (_uiState.value.selectedDate != date) {
            _uiState.update { it.copy(selectedDate = date) }
            loadData(date)
        }
    }

    fun setMinDurationFilter(minMinutes: Int) {
        _uiState.update { it.copy(filterMinMinutes = minMinutes) }
    }

    fun toggleFriendSelection(userId: String) {
        _uiState.update { state ->
            val updated = state.friends.map { item ->
                if (item.user.id == userId) {
                    item.copy(isSelected = !item.isSelected)
                } else {
                    item
                }
            }
            state.copy(friends = updated)
        }
        recalculateMatrix()
    }

    fun selectAllFriends(selectAll: Boolean) {
        _uiState.update { state ->
            val updated = state.friends.map { it.copy(isSelected = selectAll) }
            state.copy(friends = updated)
        }
        recalculateMatrix()
    }

    fun loadData(date: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                // Consultas concurrentes de mis huecos y los horarios de mis amigos
                mySlots = repository.getMyFreeSlots(date)
                val friendSchedules = repository.getFriendSchedules(date)

                val friendItems = friendSchedules.map { dto ->
                    val parsedIntervals = dto.free.map { slot ->
                        TimeInterval(
                            startMinutes = TimeInterval.parseIsoToMinutes(slot.start),
                            endMinutes = TimeInterval.parseIsoToMinutes(slot.end)
                        )
                    }.filter { it.endMinutes > it.startMinutes }

                    FriendAvailabilityItem(
                        user = dto.friend,
                        isCurrentUser = false,
                        freeIntervals = parsedIntervals,
                        isSelected = true
                    )
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        friends = friendItems
                    )
                }
                recalculateMatrix()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudieron actualizar los horarios (usando datos locales)"
                    )
                }
                recalculateMatrix()
            }
        }
    }

    private fun recalculateMatrix() {
        viewModelScope.launch {
            val currentFriends = _uiState.value.friends
            val result = ScheduleConcurrencyEngine.computeMatrix(
                myUser = myUser,
                mySlots = mySlots,
                friendItems = currentFriends
            )
            _uiState.update { it.copy(matrixResult = result) }
        }
    }

    fun onPlanFromGapClicked(gap: SharedGap) {
        // Emisión de evento para BQ-13 (Analytics Engine)
        Analytics.track("friend_availability_used", mapOf(
            "action" to "create_plan",
            "participants" to gap.totalParticipants,
            "duration" to gap.durationMinutes
        ))
    }

    companion object {
        fun currentDateFormatted(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }
}
