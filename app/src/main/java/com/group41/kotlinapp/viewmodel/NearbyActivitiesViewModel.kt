package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import kotlinx.coroutines.launch

class NearbyActivitiesViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()

    fun loadNearbyActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val activities = activityRepository.getNearbyActivities(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm
                )

                onSuccess(activities)

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
    }
}