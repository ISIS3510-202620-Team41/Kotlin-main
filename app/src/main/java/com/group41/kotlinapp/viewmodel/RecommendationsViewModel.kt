package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecommendationsViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()
    private val preferencesRepository = PreferencesRepository()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    fun loadRecommendedActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val online = try { ApiClient.isOnline() } catch (_: Exception) { true }
                _isOffline.value = !online

                val activities = activityRepository.getRecommendedActivities(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm
                )

                val preferences = preferencesRepository.getPreferences()

                val orderedActivities =
                    if (preferences.preferredCategories.isEmpty()) {
                        activities
                    } else {
                        activities.sortedByDescending { activity ->
                            activity.category in preferences.preferredCategories
                        }
                    }

                withContext(Dispatchers.Main) {
                    onSuccess(orderedActivities)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al cargar recomendaciones")
                }
            }
        }
    }

    fun getActivityById(
        activityId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        onResult: (Activity?) -> Unit
    ) {
        viewModelScope.launch {
            val act = activityRepository.getActivityById(activityId, latitude, longitude)
            withContext(Dispatchers.Main) {
                onResult(act)
            }
        }
    }

    fun updatePreferences(categories: Set<String>) {
        preferencesRepository.updatePreferences(categories)
    }

    fun joinActivity(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                activityRepository.joinActivity(
                    activityId = activity.id,
                    recommendationId = activity.recommendationId,
                    freeTimeMinutes = activity.freeTimeMinutes
                )
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al unirse a la actividad")
                }
            }
        }
    }
}
