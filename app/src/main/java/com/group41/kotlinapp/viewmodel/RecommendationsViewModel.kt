package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.PreferencesRepository
import kotlinx.coroutines.launch

class RecommendationsViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()
    private val preferencesRepository = PreferencesRepository()

    fun loadRecommendedActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
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

                onSuccess(orderedActivities)

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
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
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
    }

}