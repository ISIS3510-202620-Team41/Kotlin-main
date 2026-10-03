package com.group41.kotlinapp.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.PreferencesRepository

class RecommendationsViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()
    private val preferencesRepository = PreferencesRepository()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadRecommendedActivities(
        accessToken: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val activities = activityRepository.getRecommendedActivities(
                    accessToken = accessToken,
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

                mainHandler.post {
                    onSuccess(orderedActivities)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    fun updatePreferences(categories: Set<String>) {
        preferencesRepository.updatePreferences(categories)
    }

    fun joinActivity(
        accessToken: String,
        activityId: String,
        recommendationId: String?,
        freeTimeMinutes: Int?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                activityRepository.joinActivity(
                    accessToken = accessToken,
                    activityId = activityId,
                    recommendationId = recommendationId,
                    freeTimeMinutes = freeTimeMinutes
                )

                mainHandler.post {
                    onSuccess()
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

}