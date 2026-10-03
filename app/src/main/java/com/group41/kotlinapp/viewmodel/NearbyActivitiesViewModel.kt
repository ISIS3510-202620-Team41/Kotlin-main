package com.group41.kotlinapp.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository

class NearbyActivitiesViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadNearbyActivities(
        accessToken: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val activities = activityRepository.getNearbyActivities(
                    accessToken = accessToken,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm
                )

                mainHandler.post {
                    onSuccess(activities)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    onError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }
}